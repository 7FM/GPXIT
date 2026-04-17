package dev.gpxit.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpxit.app.data.gpx.routeElevationAtDistance
import dev.gpxit.app.domain.RouteInfo
import dev.gpxit.app.domain.StationCandidate
import kotlin.math.floor

@Composable
fun ElevationProfile(
    routeInfo: RouteInfo,
    currentDistanceAlongRoute: Double? = null,
    stations: List<StationCandidate> = emptyList(),
    startDistance: Double? = null,
    endDistance: Double? = null,
    onCursorPositionChanged: (Double?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val allPoints = routeInfo.points.filter { it.elevation != null }
    if (allPoints.size < 2) return

    // Resolve visible range. If not provided or degenerate, fall back to full route.
    val fullEnd = routeInfo.totalDistanceMeters
    val rangeStart = startDistance?.coerceIn(0.0, fullEnd) ?: 0.0
    val rangeEnd = endDistance?.coerceIn(rangeStart, fullEnd) ?: fullEnd
    val rangeSpan = (rangeEnd - rangeStart).coerceAtLeast(1.0)

    // Subset of points inside the visible range, with one "fringe" point on each side
    // so the polyline doesn't end mid-air at the edges.
    val points = run {
        if (rangeStart <= 0.0 && rangeEnd >= fullEnd) {
            allPoints
        } else {
            val startIdx = allPoints.indexOfFirst { it.distanceFromStart >= rangeStart }
                .let { if (it == -1) allPoints.lastIndex else it }
            val endIdx = allPoints.indexOfLast { it.distanceFromStart <= rangeEnd }
                .let { if (it == -1) 0 else it }
            val lo = (startIdx - 1).coerceAtLeast(0)
            val hi = (endIdx + 1).coerceAtMost(allPoints.lastIndex)
            if (hi <= lo) allPoints else allPoints.subList(lo, hi + 1)
        }
    }
    if (points.size < 2) return

    val minEle = points.minOf { it.elevation!! }
    val maxEle = points.maxOf { it.elevation!! }
    val eleRange = (maxEle - minEle).coerceAtLeast(10.0)

    // Total ascent / descent over the visible slice
    var totalAscent = 0.0
    var totalDescent = 0.0
    for (i in 1 until points.size) {
        val diff = (points[i].elevation ?: 0.0) - (points[i - 1].elevation ?: 0.0)
        if (diff > 0) totalAscent += diff else totalDescent -= diff
    }

    // Nice step size for horizontal grid lines
    val step = niceStep(eleRange)

    // Filter stations to the visible range so off-screen ones don't clutter the chart
    val visibleStations = stations.filter {
        it.distanceAlongRouteMeters in rangeStart..rangeEnd
    }

    // Cursor state (absolute distance along the full route, in meters)
    var cursorDistance by remember { mutableStateOf<Double?>(null) }
    // If the visible range changed and the cursor is no longer inside it, clear it
    run {
        val c = cursorDistance
        if (c != null && (c < rangeStart || c > rangeEnd)) {
            cursorDistance = null
            onCursorPositionChanged(null)
        }
    }

    val density = LocalDensity.current
    val textSizePx = with(density) { 10.sp.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        // Stats bar — extra bottom padding so the top gridline label below doesn't
        // visually crowd these numbers.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "%.0fm - %.0fm".format(minEle, maxEle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "  \u2191%.0fm \u2193%.0fm".format(totalAscent, totalDescent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val cur = cursorDistance
            if (cur != null) {
                val curEle = routeElevationAtDistance(routeInfo.points, cur)
                Text(
                    text = "  %.1fkm @ %.0fm".format(cur / 1000.0, curEle ?: 0.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Chart
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .pointerInput(rangeStart, rangeEnd) {
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        val d = rangeStart + f * rangeSpan
                        cursorDistance = d
                        onCursorPositionChanged(d)
                    }
                }
                .pointerInput(rangeStart, rangeEnd) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val f = (offset.x / size.width).coerceIn(0f, 1f)
                            val d = rangeStart + f * rangeSpan
                            cursorDistance = d
                            onCursorPositionChanged(d)
                        },
                        onDrag = { change, _ ->
                            val f = (change.position.x / size.width).coerceIn(0f, 1f)
                            val d = rangeStart + f * rangeSpan
                            cursorDistance = d
                            onCursorPositionChanged(d)
                            change.consume()
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                // Top padding big enough to fit the topmost gridline's label.
                val topPad = textSizePx + 6f
                val bottomPad = 4f
                val usableH = (h - topPad - bottomPad).coerceAtLeast(1f)

                fun xFor(dist: Double): Float =
                    (((dist - rangeStart) / rangeSpan) * w).toFloat().coerceIn(0f, w)

                fun yFor(ele: Double): Float =
                    (h - bottomPad - (ele - minEle) / eleRange * usableH).toFloat()

                // --- Horizontal grid lines ---
                val gridPaint = Color(0x33888888)
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = textSizePx
                    isAntiAlias = true
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

                val firstStep = floor(minEle / step) * step
                var ele = firstStep
                while (ele <= maxEle + step) {
                    if (ele >= minEle) {
                        val y = yFor(ele)
                        drawLine(
                            color = gridPaint,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1f,
                            pathEffect = dash
                        )
                        // Put the label below the line for the topmost band (where there's no
                        // room above), and above the line otherwise — always readable.
                        val labelBaseline = if (y < textSizePx + 2f) y + textSizePx else y - 2f
                        drawContext.canvas.nativeCanvas.drawText(
                            "%.0fm".format(ele),
                            2f,
                            labelBaseline,
                            labelPaint
                        )
                    }
                    ele += step
                }

                // --- Elevation path ---
                val path = Path()
                val fillPath = Path()
                var first = true

                for (pt in points) {
                    val x = xFor(pt.distanceFromStart)
                    val y = yFor(pt.elevation!!)
                    if (first) {
                        path.moveTo(x, y)
                        fillPath.moveTo(x, h)
                        fillPath.lineTo(x, y)
                        first = false
                    } else {
                        path.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(xFor(points.last().distanceFromStart), h)
                fillPath.close()

                drawPath(fillPath, Color(0x3366BB6A), style = Fill)
                drawPath(path, Color(0xFF4CAF50), style = Stroke(width = 2f))

                // --- Station markers (within visible range only) ---
                for (station in visibleStations) {
                    val sx = xFor(station.distanceAlongRouteMeters)
                    drawLine(
                        Color(0xFFE91E63),
                        Offset(sx, 0f),
                        Offset(sx, h),
                        strokeWidth = 1.5f
                    )
                }

                // --- Current GPS position marker ---
                if (currentDistanceAlongRoute != null &&
                    currentDistanceAlongRoute in rangeStart..rangeEnd) {
                    val cx = xFor(currentDistanceAlongRoute)
                    drawLine(
                        Color(0xFF4285F4),
                        Offset(cx, 0f),
                        Offset(cx, h),
                        strokeWidth = 3f
                    )
                }

                // --- Drag cursor ---
                val cur = cursorDistance
                if (cur != null && cur in rangeStart..rangeEnd) {
                    val cx = xFor(cur)
                    drawLine(
                        Color(0xFFFFC107),
                        Offset(cx, 0f),
                        Offset(cx, h),
                        strokeWidth = 2.5f
                    )
                    val curEle = routeElevationAtDistance(routeInfo.points, cur)
                    if (curEle != null) {
                        val cy = yFor(curEle)
                        drawCircle(Color.White, radius = 6f, center = Offset(cx, cy))
                        drawCircle(Color(0xFFFFC107), radius = 4.5f, center = Offset(cx, cy))
                    }
                }
            }
        }
    }
}

/** Pick a "nice" elevation step so the chart has ≤ ~5 grid lines across its range. */
private fun niceStep(range: Double): Double {
    val candidates = doubleArrayOf(2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0)
    val target = range / 5.0
    for (c in candidates) {
        if (c >= target) return c
    }
    return candidates.last()
}
