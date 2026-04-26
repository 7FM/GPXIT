package dev.gpxit.app.ui.import_route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.gpxit.app.data.OsmTileSource
import dev.gpxit.app.domain.RouteInfo
import dev.gpxit.app.ui.theme.LocalMapPalette
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * Compact, non-interactive osmdroid `MapView` thumbnail used by the
 * home screen's "Active route" card to show the route polyline on
 * top of real OSM tiles (rivers, roads, city footprints) instead of
 * the previous flat-coloured vector preview.
 *
 * Reuses the same `OsmTileSource` and tile cache as the main map
 * (see `MapComposable.kt`), so tiles fetched here are immediately
 * available when the user opens the full map — and any tiles the
 * user pre-cached via "Offline Map" light up the preview for free.
 *
 * The composable is intentionally read-only: gestures are turned
 * off at the source and the parent `LoadedCard` overlays a
 * transparent click-target on top, so a tap anywhere on the
 * thumbnail still routes through `LoadedCard.onOpenMap` instead of
 * being swallowed by the map's own pan/zoom detector.
 */
@Composable
fun RouteMapPreview(
    routeInfo: RouteInfo,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val palette = LocalMapPalette.current
    val routeColorArgb = palette.accent.toArgb()
    val strokeWidthPx = with(density) { 4.dp.toPx() }
    val edgePaddingPx = with(density) { 16.dp.toPx().toInt() }

    // Shared tile-cache config — same call OsmMapView makes. This is
    // idempotent: writing the same paths into Configuration again on
    // every composition is a no-op, so we don't need a one-shot flag.
    DisposableEffect(Unit) {
        val config = Configuration.getInstance()
        config.userAgentValue = context.packageName
        val cacheDir = java.io.File(context.filesDir, "osmdroid")
        cacheDir.mkdirs()
        config.osmdroidBasePath = cacheDir
        config.osmdroidTileCache = java.io.File(cacheDir, "tiles")
        onDispose { }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(OsmTileSource)
            // Read-only thumbnail: no zoom buttons, no pinch / rotate /
            // fling, no horizontal world-wrap (which would let a panned
            // route land on a duplicate copy of the world).
            setBuiltInZoomControls(false)
            setMultiTouchControls(false)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            // Defensive — touches are also blocked by the parent's
            // overlay Spacer in LoadedCard, but flipping these off
            // means any accidental gesture is a no-op even if the
            // overlay ever gets removed.
            isClickable = false
            isFocusable = false
            // Default centre / zoom that's replaced by the bbox fit
            // below. Pick a "world view" so we never see a grey
            // screen if route points haven't arrived yet.
            controller.setZoom(2.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }

    // Lifecycle parity with OsmMapView so background tile workers
    // pause / resume cleanly with the screen.
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            // Drop the previous polyline before adding a new one so
            // route swaps don't leave a stack of overlays piling up.
            map.overlays.removeAll { it is Polyline }

            val pts = routeInfo.points
            if (pts.isEmpty()) {
                map.invalidate()
                return@AndroidView
            }

            val polyline = Polyline().apply {
                getOutlinePaint().color = routeColorArgb
                getOutlinePaint().strokeWidth = strokeWidthPx
                setPoints(pts.map { GeoPoint(it.lat, it.lon) })
            }
            map.overlays.add(polyline)

            // Single-point routes have a degenerate bbox that would
            // crash zoomToBoundingBox. Centre on the lone point at
            // a street-level zoom and call it done.
            if (pts.size == 1) {
                val only = pts.first()
                map.controller.setZoom(15.0)
                map.controller.setCenter(GeoPoint(only.lat, only.lon))
                map.invalidate()
                return@AndroidView
            }

            // zoomToBoundingBox needs a measured viewport, so we
            // need both a synchronous call (warm path: route swap
            // while the home screen is already laid out) AND a
            // first-layout hook (cold path: fresh composition where
            // width / height are still 0). The first-layout hook is
            // a no-op if layout already happened, so it's safe to
            // attach unconditionally.
            val bbox = boundingBox(pts.map { GeoPoint(it.lat, it.lon) })
            val fit = {
                if (map.width > 0 && map.height > 0) {
                    map.zoomToBoundingBox(bbox, false, edgePaddingPx)
                }
            }
            map.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
            fit()
            map.invalidate()
        }
    )
}

private fun boundingBox(points: List<GeoPoint>): BoundingBox {
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    for (p in points) {
        if (p.latitude < minLat) minLat = p.latitude
        if (p.latitude > maxLat) maxLat = p.latitude
        if (p.longitude < minLon) minLon = p.longitude
        if (p.longitude > maxLon) maxLon = p.longitude
    }
    return BoundingBox(maxLat, maxLon, minLat, minLon)
}
