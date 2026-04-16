package dev.gpxit.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.gpxit.app.domain.RouteInfo
import dev.gpxit.app.domain.StationCandidate
import org.osmdroid.config.Configuration
import dev.gpxit.app.data.OsmTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay

enum class MapCommand {
    NONE, ZOOM_TO_ROUTE, ZOOM_TO_LOCATION, ZOOM_TO_STATION, RESET_ROTATION, GET_MAP_CENTER, ZOOM_IN, ZOOM_OUT
}

// -- Marker bitmaps --

private fun createBlueDotBitmap(): Bitmap {
    val size = 56
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val center = size / 2f

    // Shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center + 2f, 20f, shadowPaint)

    // White border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 20f, borderPaint)

    // Blue fill
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(66, 133, 244)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 16f, fillPaint)

    // Inner highlight
    val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(130, 177, 255)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center - 4f, center - 4f, 6f, highlightPaint)

    return bmp
}

private fun createHomeMarkerBitmap(): Bitmap {
    val width = 64
    val height = 84
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = width / 2f

    // Shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 0, 0)
        style = Paint.Style.FILL
    }
    val shadowPath = android.graphics.Path().apply {
        moveTo(cx - 16f, 54f)
        lineTo(cx + 16f, 54f)
        lineTo(cx, height.toFloat())
        close()
    }
    canvas.drawCircle(cx, 28f, 27f, shadowPaint)
    canvas.drawPath(shadowPath, shadowPaint)

    // White border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, 28f, 28f, borderPaint)

    // Green fill
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 175, 80)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, 28f, 24f, pinPaint)
    val path = android.graphics.Path().apply {
        moveTo(cx - 14f, 46f)
        lineTo(cx + 14f, 46f)
        lineTo(cx, height.toFloat() - 4f)
        close()
    }
    canvas.drawPath(path, pinPaint)

    // White house icon (simplified: triangle roof + square body)
    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // Roof
    val roof = android.graphics.Path().apply {
        moveTo(cx, 14f)
        lineTo(cx - 14f, 28f)
        lineTo(cx + 14f, 28f)
        close()
    }
    canvas.drawPath(roof, iconPaint)
    // Body
    canvas.drawRect(cx - 9f, 28f, cx + 9f, 40f, iconPaint)
    // Door (green cutout)
    canvas.drawRect(cx - 3f, 32f, cx + 3f, 40f, pinPaint)

    return bmp
}

private fun createStationMarkerBitmap(highlighted: Boolean): Bitmap {
    val width = if (highlighted) 72 else 56
    val height = if (highlighted) 92 else 72
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = width / 2f
    val circleR = if (highlighted) 28f else 22f
    val circleY = circleR + 4f

    // Shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleY + 2f, circleR + 2f, shadowPaint)

    if (highlighted) {
        // White border ring (thicker for highlighted)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, circleY, circleR + 4f, borderPaint)

        // Orange fill
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 152, 0) // bright orange
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, circleY, circleR, pinPaint)
        val path = android.graphics.Path().apply {
            moveTo(cx - 16f, circleY + circleR - 6f)
            lineTo(cx + 16f, circleY + circleR - 6f)
            lineTo(cx, height.toFloat() - 2f)
            close()
        }
        canvas.drawPath(path, pinPaint)

        // White "T"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("T", cx, circleY + 10f, textPaint)
    } else {
        // White border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, circleY, circleR + 2f, borderPaint)

        // Pink fill
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(233, 30, 99)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, circleY, circleR, pinPaint)
        val path = android.graphics.Path().apply {
            moveTo(cx - 12f, circleY + circleR - 5f)
            lineTo(cx + 12f, circleY + circleR - 5f)
            lineTo(cx, height.toFloat() - 2f)
            close()
        }
        canvas.drawPath(path, pinPaint)

        // White "T"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("T", cx, circleY + 8f, textPaint)
    }

    return bmp
}

private fun createNearbyMarkerBitmap(): Bitmap {
    val width = 56
    val height = 72
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = width / 2f
    val circleR = 22f
    val circleY = circleR + 4f

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleY + 2f, circleR + 2f, shadowPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleY, circleR + 2f, borderPaint)

    // Cyan/teal fill — distinct from route station pink
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 150, 136) // teal
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleY, circleR, pinPaint)
    val path = android.graphics.Path().apply {
        moveTo(cx - 12f, circleY + circleR - 5f)
        lineTo(cx + 12f, circleY + circleR - 5f)
        lineTo(cx, height.toFloat() - 2f)
        close()
    }
    canvas.drawPath(path, pinPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    canvas.drawText("T", cx, circleY + 8f, textPaint)

    return bmp
}

// -- Map Composable --

@Composable
fun OsmMapView(
    routeInfo: RouteInfo?,
    userLocation: GeoPoint?,
    homeStationLocation: GeoPoint?,
    highlightedStation: StationCandidate?,
    nearbyStations: List<StationCandidate>,
    useDarkMap: Boolean = false,
    mapCommand: MapCommand,
    onMapCommandHandled: () -> Unit,
    zoomToStation: StationCandidate? = null,
    onZoomToStationConsumed: () -> Unit = {},
    onStationClick: (StationCandidate) -> Unit,
    onMapRotationChanged: (Float) -> Unit,
    onZoomLevelChanged: (Double) -> Unit = {},
    onGetMapCenter: ((center: GeoPoint, radiusMeters: Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val config = Configuration.getInstance()
        config.userAgentValue = context.packageName
        // Use app-internal storage for tile cache (works without WRITE_EXTERNAL_STORAGE)
        val cacheDir = java.io.File(context.filesDir, "osmdroid")
        cacheDir.mkdirs()
        config.osmdroidBasePath = cacheDir
        config.osmdroidTileCache = java.io.File(cacheDir, "tiles")
        onDispose { }
    }

    val blueDotBitmap = remember { createBlueDotBitmap() }
    val homeMarkerBitmap = remember { createHomeMarkerBitmap() }
    val stationMarkerBitmap = remember { createStationMarkerBitmap(false) }
    val highlightedMarkerBitmap = remember { createStationMarkerBitmap(true) }
    val nearbyMarkerBitmap = remember { createNearbyMarkerBitmap() }

    // Track content overlays separately from persistent ones
    val contentOverlays = remember { mutableListOf<Overlay>() }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(dev.gpxit.app.data.getActiveTileSource(useDarkMap))
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            controller.setZoom(13.0)
            controller.setCenter(GeoPoint(51.0, 10.0))

            // Rotation gesture overlay
            val rotationOverlay = RotationGestureOverlay(this)
            overlays.add(rotationOverlay)

            // Scale bar overlay — middle of the left side
            val dm = context.resources.displayMetrics
            val scaleBarOverlay = ScaleBarOverlay(this).apply {
                setCentred(false)
                drawLatitudeScale(false)
                drawLongitudeScale(true)
                setAlignBottom(false)
                setAlignRight(false)
                setScaleBarOffset((12 * dm.density).toInt(), dm.heightPixels / 2)
                setTextSize(12f * dm.density)
                setBarPaint(android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 2f * dm.density
                })
                setTextPaint(android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    isAntiAlias = true
                    textSize = 12f * dm.density
                })
            }
            overlays.add(scaleBarOverlay)
        }
    }

    // Switch tile source when dark mode changes
    LaunchedEffect(useDarkMap) {
        mapView.setTileSource(dev.gpxit.app.data.getActiveTileSource(useDarkMap))
        mapView.invalidate()
    }

    // Lifecycle
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Poll map rotation and zoom level
    LaunchedEffect(mapView) {
        while (true) {
            onMapRotationChanged(mapView.mapOrientation)
            onZoomLevelChanged(mapView.zoomLevelDouble)
            kotlinx.coroutines.delay(200)
        }
    }

    val hasInitialZoom = remember { mutableMapOf("done" to false) }

    // Handle map commands
    LaunchedEffect(mapCommand) {
        when (mapCommand) {
            MapCommand.ZOOM_TO_ROUTE -> {
                routeInfo?.let { route ->
                    if (route.points.isNotEmpty()) {
                        val lats = route.points.map { it.lat }
                        val lons = route.points.map { it.lon }
                        val bb = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
                        mapView.zoomToBoundingBox(bb, true, 80)
                    }
                }
                onMapCommandHandled()
            }
            MapCommand.ZOOM_TO_LOCATION -> {
                userLocation?.let {
                    mapView.controller.animateTo(it, 15.0, 500L)
                }
                onMapCommandHandled()
            }
            MapCommand.ZOOM_TO_STATION -> {
                // Handled by dedicated LaunchedEffect(zoomToStation) above
                onMapCommandHandled()
            }
            MapCommand.RESET_ROTATION -> {
                mapView.setMapOrientation(0f, true)
                onMapCommandHandled()
            }
            MapCommand.ZOOM_IN -> {
                mapView.controller.zoomIn()
                onMapCommandHandled()
            }
            MapCommand.ZOOM_OUT -> {
                mapView.controller.zoomOut()
                onMapCommandHandled()
            }
            MapCommand.GET_MAP_CENTER -> {
                val center = mapView.mapCenter
                val bb = mapView.boundingBox
                // Approximate radius: half the diagonal of the bounding box
                val radiusMeters = dev.gpxit.app.data.gpx.haversineMeters(
                    bb.latSouth, bb.lonWest, bb.latNorth, bb.lonEast
                ).toInt() / 2
                onGetMapCenter?.invoke(
                    GeoPoint(center.latitude, center.longitude),
                    radiusMeters.coerceIn(500, 50000)
                )
                onMapCommandHandled()
            }
            MapCommand.NONE -> {}
        }
    }

    // Direct zoom to station — bypasses the command system entirely
    LaunchedEffect(zoomToStation) {
        if (zoomToStation != null) {
            // Small delay to let the map composable settle after navigation
            kotlinx.coroutines.delay(300)
            mapView.controller.animateTo(GeoPoint(zoomToStation.lat, zoomToStation.lon), 15.0, 800L)
            onZoomToStationConsumed()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            // Remove only content overlays, keep persistent ones (rotation + compass)
            for (overlay in contentOverlays) {
                map.overlays.remove(overlay)
            }
            contentOverlays.clear()

            // Draw route polyline
            if (routeInfo != null && routeInfo.points.isNotEmpty()) {
                val polyline = Polyline().apply {
                    getOutlinePaint().color = Color.rgb(66, 133, 244)
                    getOutlinePaint().strokeWidth = 8f
                    setPoints(routeInfo.points.map { GeoPoint(it.lat, it.lon) })
                }
                map.overlays.add(polyline)
                contentOverlays.add(polyline)

                // Initial zoom to route (once only)
                if (!hasInitialZoom["done"]!!) {
                    hasInitialZoom["done"] = true
                    val lats = routeInfo.points.map { it.lat }
                    val lons = routeInfo.points.map { it.lon }
                    val bb = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
                    map.post {
                        map.zoomToBoundingBox(bb, true, 80)
                    }
                }

                // Station markers
                for (station in routeInfo.stations) {
                    val isHighlighted = highlightedStation?.id == station.id
                    val marker = Marker(map).apply {
                        position = GeoPoint(station.lat, station.lon)
                        title = station.name
                        snippet = "%.1f km along route".format(station.distanceAlongRouteMeters / 1000.0)
                        icon = BitmapDrawable(
                            context.resources,
                            if (isHighlighted) highlightedMarkerBitmap else stationMarkerBitmap
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _, _ ->
                            onStationClick(station)
                            true
                        }
                    }
                    map.overlays.add(marker)
                    contentOverlays.add(marker)
                }
            }

            // Nearby search result markers (teal, distinct from route stations)
            val routeStationIds = routeInfo?.stations?.map { it.id }?.toSet() ?: emptySet()
            for (station in nearbyStations) {
                if (station.id in routeStationIds) continue // skip duplicates with route stations
                val isHighlighted = highlightedStation?.id == station.id
                val marker = Marker(map).apply {
                    position = GeoPoint(station.lat, station.lon)
                    title = station.name
                    snippet = "Nearby station"
                    icon = BitmapDrawable(
                        context.resources,
                        if (isHighlighted) highlightedMarkerBitmap else nearbyMarkerBitmap
                    )
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ ->
                        onStationClick(station)
                        true
                    }
                }
                map.overlays.add(marker)
                contentOverlays.add(marker)
            }

            // Home station marker
            if (homeStationLocation != null) {
                val homeMarker = Marker(map).apply {
                    position = homeStationLocation
                    icon = BitmapDrawable(context.resources, homeMarkerBitmap)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setInfoWindow(null) // no tooltip on tap
                }
                map.overlays.add(homeMarker)
                contentOverlays.add(homeMarker)
            }

            // User location: blue dot
            if (userLocation != null) {
                val userMarker = Marker(map).apply {
                    position = userLocation
                    title = "You are here"
                    icon = BitmapDrawable(context.resources, blueDotBitmap)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                map.overlays.add(userMarker)
                contentOverlays.add(userMarker)
            }

            map.invalidate()
        }
    )
}
