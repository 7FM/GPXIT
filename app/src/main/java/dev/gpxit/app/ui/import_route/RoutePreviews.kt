package dev.gpxit.app.ui.import_route

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.gpxit.app.domain.RoutePoint

/**
 * Mini route polyline drawn into a fixed-size canvas. The route is
 * projected via an equirectangular approximation (same as the rest of
 * the app's small-scale geometry) and centred with a uniform scale so
 * the aspect ratio of the actual route is preserved. Endpoints are
 * marked: the start with a [Palette.accent] dot, the end with a
 * cocoa-coloured pin.
 */
@Composable
fun RouteThumb(
    points: List<RoutePoint>,
    routeColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    inset: Float = 12f,
) {
    // Pre-compute the bounding box once per points list.
    val bbox = remember(points) {
        if (points.isEmpty()) null
        else {
            var minLat = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var minLon = Double.POSITIVE_INFINITY
            var maxLon = Double.NEGATIVE_INFINITY
            for (p in points) {
                if (p.lat < minLat) minLat = p.lat
                if (p.lat > maxLat) maxLat = p.lat
                if (p.lon < minLon) minLon = p.lon
                if (p.lon > maxLon) maxLon = p.lon
            }
            Bbox(minLat, maxLat, minLon, maxLon)
        }
    }

    Canvas(modifier = modifier) {
        drawRect(backgroundColor)
        val bb = bbox ?: return@Canvas
        if (points.size < 2) return@Canvas

        val w = size.width
        val h = size.height
        val canvasW = w - 2 * inset
        val canvasH = h - 2 * inset

        // Equirectangular: shrink longitude by cos(meanLat) so the
        // route doesn't look stretched east-west at higher latitudes.
        val meanLatRad = Math.toRadians((bb.minLat + bb.maxLat) / 2.0)
        val lonScale = kotlin.math.cos(meanLatRad).coerceAtLeast(0.01)
        val routeW = ((bb.maxLon - bb.minLon) * lonScale).toFloat().coerceAtLeast(1e-6f)
        val routeH = (bb.maxLat - bb.minLat).toFloat().coerceAtLeast(1e-6f)
        val scale = kotlin.math.min(canvasW / routeW, canvasH / routeH)

        val xPad = (canvasW - routeW * scale) / 2f + inset
        val yPad = (canvasH - routeH * scale) / 2f + inset

        fun project(p: RoutePoint): Offset {
            val x = ((p.lon - bb.minLon) * lonScale).toFloat() * scale + xPad
            // Latitude grows north; canvas Y grows downward → flip.
            val y = (bb.maxLat - p.lat).toFloat() * scale + yPad
            return Offset(x, y)
        }

        // Down-sample to ~120 segments so we're not building a
        // thousands-element Path on every recomposition.
        val maxSegments = 120
        val step = kotlin.math.max(1, points.size / maxSegments)
        val path = Path()
        var first = true
        var i = 0
        while (i < points.size) {
            val o = project(points[i])
            if (first) {
                path.moveTo(o.x, o.y)
                first = false
            } else {
                path.lineTo(o.x, o.y)
            }
            i += step
        }
        // Make sure the very last point is included.
        val end = project(points.last())
        path.lineTo(end.x, end.y)

        // Halo + main stroke for the polyline.
        drawPath(
            path = path,
            color = routeColor.copy(alpha = 0.18f),
            style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = path,
            color = routeColor,
            style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Endpoints — peach start dot, cocoa end pin.
        val startO = project(points.first())
        drawCircle(Color.White, radius = 6f, center = startO)
        drawCircle(Palette.accent, radius = 5f, center = startO)
        drawCircle(Color.White, radius = 6f, center = end)
        drawCircle(Palette.cocoa, radius = 5f, center = end)
        drawCircle(Color.White, radius = 1.6f, center = end)
    }
}

/**
 * Tiny elevation sparkline. Samples [steps] points evenly along the
 * route's distance axis, projects (distance, elevation) to the
 * canvas, draws a simple stroke. Returns silently if the route
 * doesn't carry elevation data.
 */
@Composable
fun ElevationSpark(
    points: List<RoutePoint>,
    strokeColor: Color,
    modifier: Modifier = Modifier,
    steps: Int = 60,
) {
    // Pre-bin elevation samples once per points list.
    val samples = remember(points) {
        val sample = ArrayList<Float>(steps)
        if (points.size < 2) return@remember sample
        val total = points.last().distanceFromStart
        if (total <= 0.0) return@remember sample
        var idx = 0
        for (i in 0 until steps) {
            val targetDist = total * i / (steps - 1).toDouble()
            while (idx < points.lastIndex && points[idx + 1].distanceFromStart < targetDist) {
                idx++
            }
            val ele = points[idx].elevation?.toFloat() ?: continue
            sample += ele
        }
        sample
    }

    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val minE = samples.min()
        val maxE = samples.max()
        val range = (maxE - minE).coerceAtLeast(1f)
        val w = size.width
        val h = size.height
        val pad = 2f
        val plotW = w - 2 * pad
        val plotH = h - 2 * pad

        val path = Path()
        for (i in samples.indices) {
            val x = pad + plotW * i / (samples.size - 1).toFloat()
            // Higher elevation = lower y on screen.
            val y = pad + plotH - ((samples[i] - minE) / range) * plotH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = strokeColor,
            style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

private data class Bbox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

/**
 * Palette pulled from the GPXIT Home design handoff. Kept local to
 * the import screen so the rest of the app can keep using the
 * Material colour scheme; this screen is opinionated and warm.
 */
internal object Palette {
    val accent = Color(0xFFF3B4A0)        // peach
    val accentDark = Color(0xFFE99A80)
    val accentInk = Color(0xFF3D2A24)
    val cocoa = Color(0xFF3D3230)
    val cocoaInk = Color(0xFFF2E9E2)
    val cocoaInkSoft = Color(0xFFB8A8A2)
    val bg = Color(0xFFFAFAFA)
    val bgWarm = Color(0xFFF5F1EC)
    val ink = Color(0xFF1A1A1A)
    val inkSoft = Color(0xFF6B6560)
    val cocoaThumbBg = Color(0xFF2A2220)  // route-thumb fill on cocoa card
}
