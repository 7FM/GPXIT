package dev.gpxit.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.gpxit.app.domain.RouteInfo
import dev.gpxit.app.domain.StationCandidate

@Composable
fun ElevationProfile(
    routeInfo: RouteInfo,
    currentDistanceAlongRoute: Double? = null,
    stations: List<StationCandidate> = emptyList(),
    onTapDistance: ((Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val points = routeInfo.points.filter { it.elevation != null }
    if (points.size < 2) return

    val totalDist = routeInfo.totalDistanceMeters
    val minEle = points.minOf { it.elevation!! }
    val maxEle = points.maxOf { it.elevation!! }
    val eleRange = (maxEle - minEle).coerceAtLeast(10.0)

    // Compute total ascent/descent
    var totalAscent = 0.0
    var totalDescent = 0.0
    for (i in 1 until points.size) {
        val diff = (points[i].elevation ?: 0.0) - (points[i - 1].elevation ?: 0.0)
        if (diff > 0) totalAscent += diff else totalDescent -= diff
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        // Stats bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
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
        }

        // Chart
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val tapFraction = offset.x / size.width
                        val tapDistance = tapFraction * totalDist
                        onTapDistance?.invoke(tapDistance)
                    }
                }
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val padding = 4f

                // Elevation path
                val path = Path()
                val fillPath = Path()
                var first = true

                for (pt in points) {
                    val x = (pt.distanceFromStart / totalDist * w).toFloat()
                    val y = (h - padding - (pt.elevation!! - minEle) / eleRange * (h - 2 * padding)).toFloat()
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

                // Close fill path
                fillPath.lineTo((points.last().distanceFromStart / totalDist * w).toFloat(), h)
                fillPath.close()

                // Draw fill
                drawPath(fillPath, Color(0x3366BB6A), style = Fill)
                // Draw line
                drawPath(path, Color(0xFF4CAF50), style = Stroke(width = 2f))

                // Station markers
                for (station in stations) {
                    val sx = (station.distanceAlongRouteMeters / totalDist * w).toFloat()
                    drawLine(
                        Color(0xFFE91E63),
                        Offset(sx, 0f),
                        Offset(sx, h),
                        strokeWidth = 1.5f
                    )
                }

                // Current position marker
                if (currentDistanceAlongRoute != null) {
                    val cx = (currentDistanceAlongRoute / totalDist * w).toFloat()
                    drawLine(
                        Color(0xFF4285F4),
                        Offset(cx, 0f),
                        Offset(cx, h),
                        strokeWidth = 3f
                    )
                }
            }
        }
    }
}
