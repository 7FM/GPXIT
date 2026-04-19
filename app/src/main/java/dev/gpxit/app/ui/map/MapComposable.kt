package dev.gpxit.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay

enum class MapCommand {
    NONE, ZOOM_TO_ROUTE, ZOOM_TO_STATION, RESET_ROTATION, GET_MAP_CENTER, ZOOM_IN, ZOOM_OUT
}

/**
 * Pre-expand a geographic bounding box so that fitting it to the full
 * MapView viewport (via osmdroid's uniform-border zoomToBoundingBox)
 * places the ORIGINAL content inside the "clear" area defined by
 * [insets]. Each inset in pixels is converted into extra degrees of
 * lat / lon padding on the corresponding side of the box, so the map
 * ends up with e.g. the peek sheet covering only empty padding
 * beneath the last station. Works for both the fullscreen route fit
 * and the Take-me-home carousel auto-fit.
 */
private fun extendBboxForInsets(
    bb: org.osmdroid.util.BoundingBox,
    mapHeight: Int,
    mapWidth: Int,
    insets: dev.gpxit.app.ui.map.FitInsets,
): org.osmdroid.util.BoundingBox {
    val h = mapHeight.coerceAtLeast(1)
    val w = mapWidth.coerceAtLeast(1)
    val topPx = insets.topPx.coerceIn(0, h - 1)
    val botPx = insets.bottomPx.coerceIn(0, h - 1 - topPx)
    val leftPx = insets.leftPx.coerceIn(0, w - 1)
    val rightPx = insets.rightPx.coerceIn(0, w - 1 - leftPx)

    val clearH = (h - topPx - botPx).coerceAtLeast(1)
    val clearW = (w - leftPx - rightPx).coerceAtLeast(1)

    // Per-axis expansion: extra span such that clear = original /
    // (total span / ratio). `ratio` is >= 1 when any inset is set.
    val latRatio = h.toDouble() / clearH.toDouble()
    val lonRatio = w.toDouble() / clearW.toDouble()
    val latExtra = bb.latitudeSpan * (latRatio - 1.0)
    val lonExtra = bb.longitudeSpan * (lonRatio - 1.0)

    val latTotalInset = (topPx + botPx).coerceAtLeast(1)
    val lonTotalInset = (leftPx + rightPx).coerceAtLeast(1)
    val topExtra = latExtra * topPx / latTotalInset
    val botExtra = latExtra * botPx / latTotalInset
    val leftExtra = lonExtra * leftPx / lonTotalInset
    val rightExtra = lonExtra * rightPx / lonTotalInset

    return org.osmdroid.util.BoundingBox(
        bb.latNorth + topExtra,
        bb.lonEast + rightExtra,
        bb.latSouth - botExtra,
        bb.lonWest - leftExtra,
    )
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
/**
 * Blue navigation chevron for the user's location. [filled] = true
 * draws the solid-blue "locked" chevron; [filled] = false draws a
 * ring-only "searching" chevron to mimic how Google / Apple Maps
 * indicate a missing or stale GPS fix.
 */
private fun createBlueDotBitmap(filled: Boolean = true): Bitmap {
    val size = 108
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f

    // Chevron geometry, bitmap-center at (cx, 54):
    //   tip ........................ (cx, 6)
    //   right wing .................. (cx + 30, 90)
    //   center notch ................ (cx, 69)  // concave bottom
    //   left wing ................... (cx - 30, 90)
    val chevron = android.graphics.Path().apply {
        moveTo(cx, 6f)
        lineTo(cx + 30f, 90f)
        lineTo(cx, 69f)
        lineTo(cx - 30f, 90f)
        close()
    }

    // Drop shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
        maskFilter = android.graphics.BlurMaskFilter(
            4f, android.graphics.BlurMaskFilter.Blur.NORMAL
        )
    }
    canvas.save()
    canvas.translate(0f, 3f)
    canvas.drawPath(chevron, shadowPaint)
    canvas.restore()

    if (filled) {
        // "GPS locked" — solid blue body with a thin white outline
        // ring around it.
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 133, 244)
            style = Paint.Style.FILL
        }
        canvas.drawPath(chevron, fillPaint)
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(chevron, outlinePaint)
    } else {
        // "Searching / no fix" — WHITE-filled chevron with a blue
        // outline. Same silhouette as the locked state so the marker
        // doesn't jump when a fix is acquired, but unmistakably not
        // filled-blue (matching Google / Apple Maps convention).
        val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawPath(chevron, whiteFill)
        val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 133, 244)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(chevron, bluePaint)
    }

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
    destination: Boolean = false,
    peekSlotIndex: Int? = null,
): Bitmap {
    val pinBmp = when {
        destination -> createDestinationMarkerBitmap()
        peekSlotIndex != null -> createPeekHighlightedMarkerBitmap(peekSlotIndex)
        else -> createStationMarkerBitmap(highlighted)
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
        dev.gpxit.app.domain.PoiType.GROCERY -> Color.rgb(46, 125, 50) to "G"      // green
        dev.gpxit.app.domain.PoiType.BAKERY -> Color.rgb(198, 124, 0) to "B"       // amber
        dev.gpxit.app.domain.PoiType.WATER -> Color.rgb(2, 136, 209) to "W"        // blue
        dev.gpxit.app.domain.PoiType.TOILET -> Color.rgb(94, 53, 177) to "WC"      // purple
        dev.gpxit.app.domain.PoiType.BIKE_REPAIR -> Color.rgb(230, 81, 0) to "R"   // deep orange
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

/**
 * Green pin used for a station that's currently visible in the
 * Take-me-home peek carousel. Instead of the default "T" glyph, it
 * carries the station's 1-based index within the page (1, 2, 3) so
 * the user can trace each marker back to the matching card in the
 * peek sheet. Visually distinct from both the default orange pin
 * and the tap-highlighted orange pin.
 */
private fun createPeekHighlightedMarkerBitmap(index: Int): Bitmap {
    val width = 72
    val height = 92
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = width / 2f
    val circleR = 28f
    val circleY = circleR + 4f

    // Shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleY + 2f, circleR + 2f, shadowPaint)

    // White border ring
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleY, circleR + 4f, borderPaint)

    // Accent green fill (matches MapPalette.accent sRGB ≈ #2FA04A).
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(47, 160, 74)
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

    // White index digit. Shrink the font a touch for two-digit
    // indices so "12" / "13" still clear the circle's inner space.
    val label = index.toString()
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = if (label.length >= 2) 24f else 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    canvas.drawText(label, cx, circleY + 11f, textPaint)

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
    userAccuracyMeters: Float? = null,
    fitStationsRequest: dev.gpxit.app.ui.map.FitStationsRequest? = null,
    onFitStationsConsumed: () -> Unit = {},
    fitRouteInsets: dev.gpxit.app.ui.map.FitInsets? = null,
    peekHighlightedStationIds: Map<String, Int> = emptyMap(),
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
    showStations: Boolean = true,
    mapCommand: MapCommand,
    onMapCommandHandled: () -> Unit,
    zoomToStation: StationCandidate? = null,
    onZoomToStationConsumed: () -> Unit = {},
    onStationClick: (StationCandidate) -> Unit,
    onMapRotationChanged: (Float) -> Unit,
    onZoomLevelChanged: (Double) -> Unit = {},
    onMetersPerPixelChanged: (Double) -> Unit = {},
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
    /**
     * Three-state location-tracking driven by the locate FAB. When
     * non-Off, the map auto-centres on [userLocation]; in
     * [LocateMode.Compass] the map also rotates so the device
     * heading is up. Manual one-finger pans inside the map demote
     * the mode to [LocateMode.Off] via [onLocateModeChanged].
     */
    locateMode: LocateMode = LocateMode.Off,
    onLocateModeChanged: (LocateMode) -> Unit = {},
    /**
     * Device azimuth in degrees clockwise from north. Hoisted into
     * MapScreen so the same sensor reading drives the locate FAB
     * icon, the user-marker rotation, and the heading-up Compass
     * mode without registering three listeners.
     */
    deviceHeading: Float = 0f,
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

    val blueDotBitmap = remember { createBlueDotBitmap(filled = true) }
    // Ring-only variant for when we don't yet have a real GPS lock
    // (e.g. just opened the app, or the location provider is emitting
    // without `hasAccuracy()`). Matches the Google/Apple convention
    // of a hollow pip while searching.
    val blueDotSearchingBitmap = remember { createBlueDotBitmap(filled = false) }
    val previewDotBitmap = remember { createPreviewDotBitmap() }
    val homeMarkerBitmap = remember { createHomeMarkerBitmap() }
    val stationMarkerBitmap = remember { createStationMarkerBitmap(false) }
    val highlightedMarkerBitmap = remember { createStationMarkerBitmap(true) }
    // Lazily-populated cache of the green peek-highlight pin keyed by
    // the station's 1-based index in the full Take-me-home list. The
    // carousel can show indices 1..N where N is `maxStationsToCheck`,
    // so rasterising eagerly would be wasteful; the first tap on a
    // given index fills the cache and subsequent fetches are free.
    val peekHighlightedMarkerCache = remember { mutableMapOf<Int, Bitmap>() }
    val destinationMarkerBitmap = remember { createDestinationMarkerBitmap() }
    val nearbyMarkerBitmap = remember { createNearbyMarkerBitmap() }
    val poiGroceryBmp = remember { createPoiBitmap(dev.gpxit.app.domain.PoiType.GROCERY) }
    val poiBakeryBmp = remember { createPoiBitmap(dev.gpxit.app.domain.PoiType.BAKERY) }
    val poiWaterBmp = remember { createPoiBitmap(dev.gpxit.app.domain.PoiType.WATER) }
    val poiToiletBmp = remember { createPoiBitmap(dev.gpxit.app.domain.PoiType.TOILET) }
    val poiBikeRepairBmp = remember { createPoiBitmap(dev.gpxit.app.domain.PoiType.BIKE_REPAIR) }

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

            // Scale bar is now rendered as a Compose composable in
            // MapScreen (see `ScaleLegend`), driven by
            // `onMetersPerPixelChanged`. Keeping it out of osmdroid's
            // overlay stack means positioning follows normal Compose
            // layout rules — `Alignment.CenterStart` is exact — and
            // avoids the opaque internal offsets of ScaleBarOverlay.
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
            // Meters-per-pixel at the map's current centre — feeds
            // the Compose-drawn scale bar. Derived from the viewport
            // height in degrees × 111320 m / degree (latitudinal
            // metres-per-degree is roughly constant on a WGS84
            // sphere, so this holds for any centre latitude).
            val mapH = mapView.height
            if (mapH > 0) {
                val latSpan = mapView.boundingBox.latitudeSpan
                if (latSpan > 0.0) {
                    onMetersPerPixelChanged(latSpan * 111_320.0 / mapH)
                }
            }
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

    // Handle map commands
    LaunchedEffect(mapCommand) {
        when (mapCommand) {
            MapCommand.ZOOM_TO_ROUTE -> {
                routeInfo?.let { route ->
                    if (route.points.isNotEmpty()) {
                        val lats = route.points.map { it.lat }
                        val lons = route.points.map { it.lon }
                        val bb = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
                        val insets = fitRouteInsets
                        if (insets != null) {
                            val extended = extendBboxForInsets(
                                bb = bb,
                                mapHeight = mapView.height,
                                mapWidth = mapView.width,
                                insets = insets,
                            )
                            mapView.zoomToBoundingBox(extended, true, 40)
                        } else {
                            mapView.zoomToBoundingBox(bb, true, 80)
                        }
                    }
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

    // Location-tracking mode (locate FAB). Three coupled effects:
    //
    //  1) `locateMode` transitions: animate-to on entry to Following
    //     or Compass so the recentre reads as a deliberate camera
    //     move; bump zoom to ≥16 if the user was zoomed way out.
    //  2) New `userLocation` while in a follow mode: snap-centre so
    //     the marker stays under the cursor as GPS streams in.
    //  3) New `deviceHeading` while in Compass: rotate the map so
    //     the heading is up. osmdroid's setMapOrientation rotates
    //     the viewport (not the world), so the bearing-up mapping
    //     is mapOrientation = -deviceHeading. The sensor listener
    //     already throttles to ~2° so we don't redraw per frame.
    //  4) Manual one-finger pan: the touch listener below demotes
    //     the mode to Off so the map stops fighting the user.
    val currentLocateMode by rememberUpdatedState(locateMode)
    val currentOnLocateModeChanged by rememberUpdatedState(onLocateModeChanged)
    LaunchedEffect(locateMode) {
        if (locateMode == LocateMode.Off) return@LaunchedEffect
        userLocation?.let {
            val targetZoom = maxOf(mapView.zoomLevelDouble, 16.0)
            mapView.controller.animateTo(it, targetZoom, 500L)
        }
        if (locateMode == LocateMode.Compass) {
            mapView.setMapOrientation(-deviceHeading, true)
        }
    }
    LaunchedEffect(userLocation) {
        if (locateMode != LocateMode.Off) {
            userLocation?.let { mapView.controller.setCenter(it) }
        }
    }
    LaunchedEffect(deviceHeading) {
        if (locateMode == LocateMode.Compass) {
            mapView.setMapOrientation(-deviceHeading, false)
        }
    }
    DisposableEffect(mapView) {
        // User-gesture detector that drops the locate mode back to
        // Off on any pan or rotate the user performs themselves. We
        // intentionally don't compare mapView.mapOrientation against
        // a snapshot — the heading-up Compass effect mutates that
        // value on every sensor tick, so it can't tell user input
        // from our own writes. Instead we look at the raw pointer
        // geometry:
        //   • single finger past the slop          → pan
        //   • two fingers whose enclosed angle has
        //     drifted past a few degrees           → twist/rotate
        // Pinch-zoom (two fingers, mostly radial motion) is
        // tolerated so the user can zoom while staying centred.
        // Returning false from the listener delegates to
        // MapView.onTouchEvent so osmdroid's own gesture handling
        // (pan, pinch, rotate) keeps working.
        var downX = 0f
        var downY = 0f
        var dragArmed = false
        var twoFingerStartAngle = Float.NaN
        val slopSq = 30f * 30f
        val rotateSlopDeg = 6f

        fun pairAngle(event: MotionEvent): Float {
            val dx = event.getX(1) - event.getX(0)
            val dy = event.getY(1) - event.getY(0)
            return Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        }
        fun deactivate() {
            if (currentLocateMode != LocateMode.Off) {
                currentOnLocateModeChanged(LocateMode.Off)
            }
        }

        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragArmed = true
                    twoFingerStartAngle = Float.NaN
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Second finger landed — pan-arm clears (two
                    // fingers down isn't a pan), and we snapshot
                    // the inter-pointer angle so subsequent MOVEs
                    // can detect a twist.
                    dragArmed = false
                    if (event.pointerCount >= 2) {
                        twoFingerStartAngle = pairAngle(event)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragArmed && event.pointerCount == 1) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (dx * dx + dy * dy > slopSq) {
                            dragArmed = false
                            deactivate()
                        }
                    } else if (event.pointerCount >= 2 && !twoFingerStartAngle.isNaN()) {
                        val now = pairAngle(event)
                        val delta = kotlin.math.abs(
                            ((now - twoFingerStartAngle + 540f) % 360f) - 180f
                        )
                        if (delta > rotateSlopDeg) {
                            twoFingerStartAngle = Float.NaN
                            deactivate()
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Second finger lifted — clear the rotation
                    // baseline so a leftover one-finger pan doesn't
                    // start mid-stream.
                    twoFingerStartAngle = Float.NaN
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragArmed = false
                    twoFingerStartAngle = Float.NaN
                }
            }
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    // Fit a small set of stations into the clear area defined by
    // FitInsets (top: stats strip, right: zoom rail, bottom: peek
    // sheet + nav, left: compass). osmdroid's zoomToBoundingBox uses
    // a uniform border, so we pre-extend the bbox asymmetrically.
    LaunchedEffect(fitStationsRequest?.nonce) {
        val req = fitStationsRequest ?: return@LaunchedEffect
        if (req.stations.isEmpty()) {
            onFitStationsConsumed()
            return@LaunchedEffect
        }
        // Wait one frame so the map view has a measured size we can
        // use for the ratio calculation; cold-open the map could
        // have width/height = 0 if we fired right after composition.
        kotlinx.coroutines.delay(100)
        val bb = org.osmdroid.util.BoundingBox.fromGeoPoints(
            req.stations.map { GeoPoint(it.lat, it.lon) }
        )
        val extended = extendBboxForInsets(
            bb = bb,
            mapHeight = mapView.height,
            mapWidth = mapView.width,
            insets = req.insets,
        )
        // Cap max zoom so a single-station (degenerate) bbox doesn't
        // slam the camera to the tile-source max. 17 is block-level,
        // close enough to pick out the station building without
        // losing the user's surrounding context.
        mapView.zoomToBoundingBox(extended, true, 40, 17.0, 1000L)
        onFitStationsConsumed()
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

                // Initial zoom to route (once only). Use the SAME
                // 4-sided inset expansion as the Fullscreen-fit button
                // so the route doesn't spawn half-hidden under the
                // stats strip or the right rail the moment the user
                // arrives on the map.
                if (!hasInitialZoom["done"]!!) {
                    hasInitialZoom["done"] = true
                    val lats = routeInfo.points.map { it.lat }
                    val lons = routeInfo.points.map { it.lon }
                    val bb = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
                    val insets = fitRouteInsets
                    map.post {
                        if (insets != null) {
                            val extended = extendBboxForInsets(
                                bb = bb,
                                mapHeight = map.height,
                                mapWidth = map.width,
                                insets = insets,
                            )
                            map.zoomToBoundingBox(extended, true, 40)
                        } else {
                            map.zoomToBoundingBox(bb, true, 80)
                        }
                    }
                }

                // Navigation overlay — a bright highlighted path from
                // the rider's position to the destination. BRouter
                // gives us the full route (starting from user
                // position); we find where its polyline leaves the
                // GPX corridor and render:
                //   • GPX points up to that divergence (so the
                //     highlighted path snaps exactly onto the blue
                //     GPX polyline while they coincide), then
                //   • BRouter points from the divergence onward.
                // This way BRouter can freely pick an earlier
                // branch-off than the geographically-closest GPX
                // point without producing a visible U-turn.
                val dst = destinationStation
                if (navigationActive && dst != null && userLocation != null &&
                    !navigationLastMile.isNullOrEmpty()
                ) {
                    val router = navigationLastMile
                    val gpx = routeInfo.points
                    val corridorMeters = 60.0

                    // Find first router point that's clearly off the GPX.
                    var divergenceIdx = -1
                    for (i in router.indices) {
                        val (_, d) = dev.gpxit.app.data.gpx.findClosestPointIndex(
                            gpx, router[i].latitude, router[i].longitude
                        )
                        if (d > corridorMeters) {
                            divergenceIdx = i
                            break
                        }
                    }

                    val navPoints = ArrayList<GeoPoint>()
                    if (divergenceIdx == 0) {
                        // Router leaves the GPX immediately — no follow
                        // segment; just draw BRouter's polyline as-is.
                        navPoints += router
                    } else {
                        // Snap the on-GPX portion to real GPX points and
                        // append BRouter from divergence onward. The
                        // "last on-GPX" router point is whichever one
                        // BRouter visited last before branching off —
                        // that's the actual crossing BRouter wanted to
                        // take, which may be an earlier GPX index than
                        // the geographically-closest-to-station origin
                        // we passed in.
                        val lastOnGpxRouter = router[
                            (if (divergenceIdx < 0) router.lastIndex else divergenceIdx - 1)
                                .coerceAtLeast(0)
                        ]
                        val (userIdx, _) = dev.gpxit.app.data.gpx.findClosestPointIndex(
                            gpx, userLocation.latitude, userLocation.longitude
                        )
                        val (endOnGpxIdx, _) = dev.gpxit.app.data.gpx.findClosestPointIndex(
                            gpx, lastOnGpxRouter.latitude, lastOnGpxRouter.longitude
                        )
                        navPoints += GeoPoint(userLocation.latitude, userLocation.longitude)
                        if (endOnGpxIdx >= userIdx) {
                            // Clean case: BRouter's actual crossing is
                            // still ahead of the rider → follow the GPX
                            // forward to it, then branch off. This
                            // hides any backward portion of BRouter's
                            // polyline that existed only because we
                            // passed a branchIdx later than BRouter's
                            // preferred crossing.
                            for (i in userIdx..endOnGpxIdx) {
                                navPoints += GeoPoint(gpx[i].lat, gpx[i].lon)
                            }
                            if (divergenceIdx >= 0) {
                                for (i in divergenceIdx until router.size) {
                                    navPoints += router[i]
                                }
                            }
                        } else {
                            // Rider has already passed BRouter's
                            // preferred crossing — there's no way to
                            // reach the station without some kind of
                            // U-turn. Draw BRouter's full polyline so
                            // the backtrack is visible and honest,
                            // rather than snapping a fake forward GPX
                            // segment the rider can't actually use.
                            navPoints += router
                        }
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

                // Station markers (toggleable via the Layers sheet's
                // "Exit points" switch — the route's own station set
                // is hidden entirely when showStations is false; the
                // nearby-search results below ignore this flag since
                // those are the user's own search).
                if (showStations) for (station in routeInfo.stations) {
                    val isHighlighted = highlightedStation?.id == station.id
                    val isDestination = destinationStation?.id == station.id
                    val peekSlotIndex = peekHighlightedStationIds[station.id]
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
                        // Peek-highlighted + label → green indexed pin
                        // WITH the arr/dep pill on top so the user
                        // still sees the next-connection info; without
                        // this branch the peek markers lost their
                        // labels the moment they went "green".
                        peekSlotIndex != null && hasLabelText ->
                            createLabeledStationBitmap(
                                lineTop = lineTop.ifBlank { lineBottom ?: "" },
                                lineBottom = if (lineTop.isBlank()) null else lineBottom,
                                highlighted = false,
                                recommended = label?.isRecommended == true,
                                peekSlotIndex = peekSlotIndex,
                            )
                        peekSlotIndex != null -> peekHighlightedMarkerCache
                            .getOrPut(peekSlotIndex) {
                                createPeekHighlightedMarkerBitmap(peekSlotIndex)
                            }
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

            // POI markers (grocery, bakery, water, toilet, bike repair)
            for (poi in pois) {
                val bmp = when (poi.type) {
                    dev.gpxit.app.domain.PoiType.GROCERY -> poiGroceryBmp
                    dev.gpxit.app.domain.PoiType.BAKERY -> poiBakeryBmp
                    dev.gpxit.app.domain.PoiType.WATER -> poiWaterBmp
                    dev.gpxit.app.domain.PoiType.TOILET -> poiToiletBmp
                    dev.gpxit.app.domain.PoiType.BIKE_REPAIR -> poiBikeRepairBmp
                }
                val marker = Marker(map).apply {
                    position = GeoPoint(poi.lat, poi.lon)
                    title = poi.name ?: when (poi.type) {
                        dev.gpxit.app.domain.PoiType.GROCERY -> "Grocery"
                        dev.gpxit.app.domain.PoiType.BAKERY -> "Bakery"
                        dev.gpxit.app.domain.PoiType.WATER -> "Drinking water"
                        dev.gpxit.app.domain.PoiType.TOILET -> "Toilet"
                        dev.gpxit.app.domain.PoiType.BIKE_REPAIR -> "Bike repair"
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
                // Has-lock heuristic: ANY accuracy reading (finite,
                // may be 0) counts as a real fix. The ring is drawn
                // only when accuracy is strictly positive so we don't
                // render a zero-radius polygon. Missing / stale
                // accuracy (cleared by GpxitApp's staleness watcher)
                // gates the hollow "searching" chevron.
                val acc = userAccuracyMeters
                val hasLock = acc != null && acc.isFinite()
                val showAccuracyRing = hasLock && acc!! > 0f
                if (showAccuracyRing) {
                    val accuracyCircle = org.osmdroid.views.overlay.Polygon(map).apply {
                        points = org.osmdroid.views.overlay.Polygon
                            .pointsAsCircle(userLocation, acc.toDouble())
                        fillPaint.color = android.graphics.Color.argb(40, 66, 133, 244)
                        outlinePaint.color = android.graphics.Color.argb(160, 66, 133, 244)
                        outlinePaint.strokeWidth = 2f * context.resources.displayMetrics.density
                        setOnClickListener { _, _, _ -> false } // don't eat taps
                    }
                    map.overlays.add(accuracyCircle)
                    contentOverlays.add(accuracyCircle)
                }

                val userMarker = Marker(map).apply {
                    position = userLocation
                    title = "You are here"
                    icon = BitmapDrawable(
                        context.resources,
                        if (hasLock) blueDotBitmap else blueDotSearchingBitmap,
                    )
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    isFlat = true
                    // osmdroid's Marker.draw applies `canvas.rotate(-bearing)`,
                    // so positive `rotation` spins the icon counter-clockwise.
                    // The chevron tip points up by default; we want it to point
                    // toward the device heading (CW from north), so negate.
                    rotation = -deviceHeading
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
