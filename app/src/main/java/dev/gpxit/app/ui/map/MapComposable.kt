package dev.gpxit.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private val labelTimeFormatter = java.time.format.DateTimeFormatter
    .ofPattern("HH:mm")
    .withZone(java.time.ZoneId.systemDefault())

// -- Marker bitmaps --

private fun createPreviewDotBitmap(): Bitmap {
    val size = 72
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val center = size / 2f

    // Shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center + 2f, 28f, shadowPaint)

    // White border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 28f, borderPaint)

    // Amber fill
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 22f, fillPaint)

    // Inner highlight
    val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 224, 130)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center - 6f, center - 6f, 8f, highlightPaint)

    return bmp
}

/**
 * Current-position marker as a chevron "navigation triangle" — the
 * shape Google Maps uses while you're navigating. A filled blue
 * arrowhead with a concave notch at its base, pointing up (north) by
 * default. The marker is rotated to the device compass heading at
 * runtime via Marker.rotation (with isFlat = true, so the rotation
 * is applied in map coordinates regardless of gestural map rotation).
 */
private fun createBlueDotBitmap(): Bitmap {
    val size = 72
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f

    // Chevron geometry, bitmap-center at (cx, 36):
    //   tip ........................ (cx, 4)
    //   right wing .................. (cx + 20, 60)
    //   center notch ................ (cx, 46)  // concave bottom
    //   left wing ................... (cx - 20, 60)
    val chevron = android.graphics.Path().apply {
        moveTo(cx, 4f)
        lineTo(cx + 20f, 60f)
        lineTo(cx, 46f)
        lineTo(cx - 20f, 60f)
        close()
    }

    // Drop shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
        maskFilter = android.graphics.BlurMaskFilter(
            3f, android.graphics.BlurMaskFilter.Blur.NORMAL
        )
    }
    canvas.save()
    canvas.translate(0f, 2f)
    canvas.drawPath(chevron, shadowPaint)
    canvas.restore()

    // White outline (stroke on top of fill)
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(66, 133, 244)
        style = Paint.Style.FILL
    }
    canvas.drawPath(chevron, fillPaint)
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawPath(chevron, outlinePaint)

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

/**
 * Build a composite bitmap: a two-line text pill stacked above a station pin.
 * Returned bitmap's bottom-center is the pin tip (anchor 0.5, 1.0).
 */
private fun createLabeledStationBitmap(
    lineTop: String,
    lineBottom: String?,
    highlighted: Boolean,
    recommended: Boolean,
    destination: Boolean = false
): Bitmap {
    val pinBmp = if (destination) {
        createDestinationMarkerBitmap()
    } else {
        createStationMarkerBitmap(highlighted)
    }

    val textSizePx = 22f
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = textSizePx
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when {
            destination -> Color.rgb(27, 94, 32)   // dark green
            recommended -> Color.rgb(25, 118, 210) // blue
            else -> Color.rgb(60, 60, 60)
        }
        textSize = textSizePx
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Measure text widths so the pill fits
    val topW = textPaint.measureText(lineTop)
    val botW = lineBottom?.let { textPaint.measureText(it) } ?: 0f
    val contentW = maxOf(topW, botW)
    val pillPaddingX = 10f
    val pillPaddingY = 5f
    val lineGap = 3f
    val pillW = contentW + 2 * pillPaddingX
    val textLines = if (lineBottom != null) 2 else 1
    val pillH = textLines * textSizePx + (textLines - 1) * lineGap + 2 * pillPaddingY

    val gapBetween = 2f
    val totalW = maxOf(pillW.toInt(), pinBmp.width)
    val totalH = (pillH + gapBetween + pinBmp.height).toInt()

    val bmp = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Pill background
    val pillLeft = (totalW - pillW) / 2f
    val pillTop = 0f
    val pillRect = android.graphics.RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)
    val pillShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
        android.graphics.RectF(pillRect.left + 1f, pillRect.top + 2f, pillRect.right + 1f, pillRect.bottom + 2f),
        8f, 8f, pillShadow
    )
    val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when {
            destination -> Color.rgb(232, 245, 233) // pale green
            recommended -> Color.rgb(232, 244, 253) // pale blue
            else -> Color.WHITE
        }
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(pillRect, 8f, 8f, pillBg)
    val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when {
            destination -> Color.rgb(46, 125, 50)  // green (matches pin)
            recommended -> Color.rgb(25, 118, 210) // blue
            highlighted -> Color.rgb(255, 152, 0)  // orange
            else -> Color.rgb(200, 200, 200)        // gray
        }
        style = Paint.Style.STROKE
        strokeWidth = if (recommended || destination) 2.5f else 1.5f
    }
    canvas.drawRoundRect(pillRect, 8f, 8f, pillBorder)

    // Text
    val cxText = totalW / 2f
    val firstBaseline = pillTop + pillPaddingY + textSizePx - 4f
    canvas.drawText(lineTop, cxText, firstBaseline, boldPaint)
    if (lineBottom != null) {
        canvas.drawText(
            lineBottom,
            cxText,
            firstBaseline + textSizePx + lineGap,
            textPaint
        )
    }

    // Pin below the pill, centered horizontally
    val pinLeft = (totalW - pinBmp.width) / 2f
    val pinTop = pillH + gapBetween
    canvas.drawBitmap(pinBmp, pinLeft, pinTop, null)

    return bmp
}

private fun createPoiBitmap(type: dev.gpxit.app.domain.PoiType): Bitmap {
    val size = 40
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val r = 14f

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cx + 1.5f, r + 1.5f, shadowPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cx, r + 2f, borderPaint)

    val (fillColor, label) = when (type) {
        dev.gpxit.app.domain.PoiType.GROCERY -> Color.rgb(46, 125, 50) to "G"    // green
        dev.gpxit.app.domain.PoiType.BAKERY -> Color.rgb(198, 124, 0) to "B"     // amber
        dev.gpxit.app.domain.PoiType.WATER -> Color.rgb(2, 136, 209) to "W"      // blue
        dev.gpxit.app.domain.PoiType.TOILET -> Color.rgb(94, 53, 177) to "WC"    // purple
    }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cx, r, fillPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = if (label.length > 1) 13f else 17f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    canvas.drawText(label, cx, cx + textPaint.textSize / 3f, textPaint)

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

/**
 * Larger green marker with a white star — drawn at the station the
 * user has explicitly marked as their destination. Visually distinct
 * from both the regular pink "T" station marker and the orange
 * highlighted one so "this is where I'm going" is never ambiguous.
 */
private fun createDestinationMarkerBitmap(): Bitmap {
    val width = 80
    val height = 100
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = width / 2f
    val circleR = 32f
    val circleY = circleR + 4f

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleY + 3f, circleR + 3f, shadowPaint)

    // White border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleY, circleR + 5f, borderPaint)

    // Green fill
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(46, 125, 50) // strong green
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleY, circleR, pinPaint)
    val tail = android.graphics.Path().apply {
        moveTo(cx - 18f, circleY + circleR - 6f)
        lineTo(cx + 18f, circleY + circleR - 6f)
        lineTo(cx, height.toFloat() - 2f)
        close()
    }
    canvas.drawPath(tail, pinPaint)

    // White 5-point star inside the circle.
    val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val starPath = android.graphics.Path()
    val outerR = circleR * 0.70f
    val innerR = outerR * 0.42f
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outerR else innerR
        val angle = Math.toRadians((-90 + i * 36).toDouble())
        val x = cx + r * kotlin.math.cos(angle).toFloat()
        val y = circleY + r * kotlin.math.sin(angle).toFloat()
        if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
    }
    starPath.close()
    canvas.drawPath(starPath, starPaint)

    return bmp
}

// -- Map Composable --

@Composable
fun OsmMapView(
    routeInfo: RouteInfo?,
    userLocation: GeoPoint?,
    homeStationLocation: GeoPoint?,
    highlightedStation: StationCandidate?,
    destinationStation: StationCandidate? = null,
    navigationActive: Boolean = false,
    /**
     * Optional bike-routed polyline from the GPX's closest-to-station
     * point to the station itself (computed by BRouter if available).
     * When null, MapComposable falls back to a straight line for the
     * branch-off segment.
     */
    navigationLastMile: List<GeoPoint>? = null,
    nearbyStations: List<StationCandidate>,
    previewPosition: GeoPoint? = null,
    stationLabels: Map<String, dev.gpxit.app.domain.StationLabel> = emptyMap(),
    pois: List<dev.gpxit.app.domain.Poi> = emptyList(),
    mapCommand: MapCommand,
    onMapCommandHandled: () -> Unit,
    zoomToStation: StationCandidate? = null,
    onZoomToStationConsumed: () -> Unit = {},
    onStationClick: (StationCandidate) -> Unit,
    onMapRotationChanged: (Float) -> Unit,
    onZoomLevelChanged: (Double) -> Unit = {},
    onViewportChanged: (north: Double, south: Double, east: Double, west: Double) -> Unit = { _, _, _, _ -> },
    onMapViewportSnapshot: ((center: GeoPoint, zoom: Double) -> Unit)? = null,
    onGetMapCenter: ((center: GeoPoint, radiusMeters: Int) -> Unit)? = null,
    /**
     * Restore the map to this center + zoom instead of the default
     * "zoom to route extent" behaviour. Used to preserve the user's
     * pan/zoom across a round-trip to the Take-me-home screen.
     */
    initialMapCenter: GeoPoint? = null,
    initialMapZoom: Double? = null,
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
    val previewDotBitmap = remember { createPreviewDotBitmap() }
    val homeMarkerBitmap = remember { createHomeMarkerBitmap() }
    val stationMarkerBitmap = remember { createStationMarkerBitmap(false) }
    val highlightedMarkerBitmap = remember { createStationMarkerBitmap(true) }
    val destinationMarkerBitmap = remember { createDestinationMarkerBitmap() }
    val nearbyMarkerBitmap = remember { createNearbyMarkerBitmap() }
    val poiGroceryBmp = remember { createPoiBitmap(dev.gpxit.app.domain.PoiType.GROCERY) }
    val poiBakeryBmp = remember { createPoiBitmap(dev.gpxit.app.domain.PoiType.BAKERY) }
    val poiWaterBmp = remember { createPoiBitmap(dev.gpxit.app.domain.PoiType.WATER) }
    val poiToiletBmp = remember { createPoiBitmap(dev.gpxit.app.domain.PoiType.TOILET) }

    // Track content overlays separately from persistent ones
    val contentOverlays = remember { mutableListOf<Overlay>() }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(dev.gpxit.app.data.OsmTileSource)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            // If we have a saved viewport (round-trip from another
            // screen) restore it; otherwise start at a reasonable
            // default that will be replaced by the route-extent zoom
            // on first import.
            controller.setZoom(initialMapZoom ?: 13.0)
            controller.setCenter(initialMapCenter ?: GeoPoint(51.0, 10.0))

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

    // Lifecycle
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Poll map rotation, zoom, and viewport
    LaunchedEffect(mapView) {
        var lastN = Double.NaN
        var lastS = Double.NaN
        var lastE = Double.NaN
        var lastW = Double.NaN
        while (true) {
            onMapRotationChanged(mapView.mapOrientation)
            onZoomLevelChanged(mapView.zoomLevelDouble)
            val bb = mapView.boundingBox
            val n = bb.latNorth; val s = bb.latSouth; val e = bb.lonEast; val w = bb.lonWest
            if (n != lastN || s != lastS || e != lastE || w != lastW) {
                lastN = n; lastS = s; lastE = e; lastW = w
                onViewportChanged(n, s, e, w)
                onMapViewportSnapshot?.invoke(
                    GeoPoint(
                        mapView.mapCenter.latitude,
                        mapView.mapCenter.longitude
                    ),
                    mapView.zoomLevelDouble
                )
            }
            kotlinx.coroutines.delay(200)
        }
    }

    // If a saved viewport is being restored, we've already "done" the
    // initial zoom — don't fight the user by re-zooming to route extent.
    val hasInitialZoom = remember { mutableMapOf("done" to (initialMapCenter != null)) }

    // Compass heading in degrees (clockwise from true north). Default
    // 0 = north. Updated from the rotation-vector sensor below.
    var deviceHeading by remember { mutableFloatStateOf(0f) }

    // Rotation-vector sensor → azimuth. Sampled at UI rate, but we
    // only bump the state when the heading changes by more than a
    // couple of degrees to avoid flooding recomposition.
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensorManager == null || rotationSensor == null) {
            onDispose {}
        } else {
            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            var lastReported = Float.NaN
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        .let { if (it < 0) it + 360f else it }
                    if (lastReported.isNaN() ||
                        kotlin.math.abs(((azimuth - lastReported + 540f) % 360f) - 180f) > 2f
                    ) {
                        lastReported = azimuth
                        deviceHeading = azimuth
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(
                listener, rotationSensor, SensorManager.SENSOR_DELAY_UI
            )
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

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

                // Navigation overlay — a bright highlighted path from
                // the user's current position to the destination. If
                // the natural branch-off on the GPX is still ahead of
                // the rider, we follow the GPX up to that point and
                // then hand off to BRouter; if it's already behind,
                // we skip the GPX entirely (would be a pointless
                // U-turn) and let BRouter route directly from the
                // rider's position.
                val dst = destinationStation
                if (navigationActive && dst != null && userLocation != null) {
                    val pts = routeInfo.points
                    val (userIdx, _) = dev.gpxit.app.data.gpx.findClosestPointIndex(
                        pts, userLocation.latitude, userLocation.longitude
                    )
                    val (branchIdx, _) = dev.gpxit.app.data.gpx.findClosestPointIndex(
                        pts, dst.lat, dst.lon
                    )
                    val navPoints = ArrayList<GeoPoint>()
                    navPoints += GeoPoint(userLocation.latitude, userLocation.longitude)
                    if (branchIdx >= userIdx) {
                        // Branch-off is ahead — follow the GPX up to it.
                        for (i in userIdx..branchIdx) {
                            navPoints += GeoPoint(pts[i].lat, pts[i].lon)
                        }
                    }
                    // BRouter last-mile — only drawn when the polyline
                    // is available. Straight lines across buildings or
                    // rivers are misleading, so we refuse to guess and
                    // simply leave the branch off until the real route
                    // is ready.
                    if (!navigationLastMile.isNullOrEmpty()) {
                        navPoints += navigationLastMile
                    }

                    val navPolyline = Polyline().apply {
                        getOutlinePaint().color = Color.rgb(46, 125, 50) // green, matches the destination pin
                        getOutlinePaint().strokeWidth = 14f
                        getOutlinePaint().strokeCap = Paint.Cap.ROUND
                        getOutlinePaint().strokeJoin = Paint.Join.ROUND
                        getOutlinePaint().alpha = 200
                        setPoints(navPoints)
                    }
                    map.overlays.add(navPolyline)
                    contentOverlays.add(navPolyline)
                }

                // Station markers
                for (station in routeInfo.stations) {
                    val isHighlighted = highlightedStation?.id == station.id
                    val isDestination = destinationStation?.id == station.id
                    val label = stationLabels[station.id]
                    val arr = label?.arrivalAtStation?.let { labelTimeFormatter.format(it) }
                    val dep = label?.nextTrainDeparture?.let { labelTimeFormatter.format(it) }
                    val lineTop = arr?.let { "arr $it" } ?: ""
                    val lineBottom = dep?.let { "\u2192 $it" }
                    val hasLabelText = lineTop.isNotBlank() || lineBottom != null
                    val iconBitmap = when {
                        // Destination + label → green-star pin with arr/dep pill,
                        // so the user still sees when they'll arrive and when
                        // the first train leaves.
                        isDestination && hasLabelText -> createLabeledStationBitmap(
                            lineTop = lineTop.ifBlank { lineBottom ?: "" },
                            lineBottom = if (lineTop.isBlank()) null else lineBottom,
                            highlighted = false,
                            recommended = false,
                            destination = true
                        )
                        isDestination -> destinationMarkerBitmap
                        hasLabelText -> createLabeledStationBitmap(
                            lineTop = lineTop.ifBlank { lineBottom ?: "" },
                            lineBottom = if (lineTop.isBlank()) null else lineBottom,
                            highlighted = isHighlighted,
                            recommended = label?.isRecommended == true
                        )
                        isHighlighted -> highlightedMarkerBitmap
                        else -> stationMarkerBitmap
                    }

                    val marker = Marker(map).apply {
                        position = GeoPoint(station.lat, station.lon)
                        title = station.name
                        snippet = "%.1f km along route".format(station.distanceAlongRouteMeters / 1000.0)
                        icon = BitmapDrawable(context.resources, iconBitmap)
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

            // POI markers (grocery, bakery, water, toilet)
            for (poi in pois) {
                val bmp = when (poi.type) {
                    dev.gpxit.app.domain.PoiType.GROCERY -> poiGroceryBmp
                    dev.gpxit.app.domain.PoiType.BAKERY -> poiBakeryBmp
                    dev.gpxit.app.domain.PoiType.WATER -> poiWaterBmp
                    dev.gpxit.app.domain.PoiType.TOILET -> poiToiletBmp
                }
                val marker = Marker(map).apply {
                    position = GeoPoint(poi.lat, poi.lon)
                    title = poi.name ?: when (poi.type) {
                        dev.gpxit.app.domain.PoiType.GROCERY -> "Grocery"
                        dev.gpxit.app.domain.PoiType.BAKERY -> "Bakery"
                        dev.gpxit.app.domain.PoiType.WATER -> "Drinking water"
                        dev.gpxit.app.domain.PoiType.TOILET -> "Toilet"
                    }
                    icon = BitmapDrawable(context.resources, bmp)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
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

            // User location: blue dot with a compass-driven heading beam.
            // isFlat = true applies marker.rotation in map coordinates
            // so the arrow points to the user's real-world heading
            // regardless of how the map itself is rotated.
            if (userLocation != null) {
                val userMarker = Marker(map).apply {
                    position = userLocation
                    title = "You are here"
                    icon = BitmapDrawable(context.resources, blueDotBitmap)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    isFlat = true
                    rotation = deviceHeading
                }
                map.overlays.add(userMarker)
                contentOverlays.add(userMarker)
            }

            // Elevation graph cursor preview: amber dot along route
            if (previewPosition != null) {
                val previewMarker = Marker(map).apply {
                    position = previewPosition
                    icon = BitmapDrawable(context.resources, previewDotBitmap)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null)
                }
                map.overlays.add(previewMarker)
                contentOverlays.add(previewMarker)
            }

            map.invalidate()
        }
    )
}
