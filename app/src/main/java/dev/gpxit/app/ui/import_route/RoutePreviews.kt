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
    val palette = LocalHomePalette.current
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

        // Endpoints — peach start dot, dark end pin. Use palette so
        // both colours track the current theme.
        val startO = project(points.first())
        val endRingColor = if (palette.isDark) palette.bgWarm else Color.White
        drawCircle(endRingColor, radius = 6f, center = startO)
        drawCircle(palette.accent, radius = 5f, center = startO)
        drawCircle(endRingColor, radius = 6f, center = end)
        drawCircle(
            if (palette.isDark) palette.ink else palette.cocoa,
            radius = 5f,
            center = end,
        )
        drawCircle(endRingColor, radius = 1.6f, center = end)
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
 * Palette tokens from the GPXIT Home handoff, provided as a data
 * class so we can hold a light and a dark variant and switch at
 * runtime based on the system theme. The resolved instance lives in
 * [LocalHomePalette]; composables on the import screen read it via
 * [homePalette]. Mirrors the `THEMES` object in the handoff's
 * `components/home-screens.jsx`.
 */
internal data class HomePalette(
    val isDark: Boolean,
    val accent: Color,
    val accentDark: Color,
    val accentInk: Color,
    val accentDeep: Color,       // primary button fill
    val accentDeepInk: Color,    // primary button label
    val cocoa: Color,
    val cocoaInk: Color,
    val cocoaInkSoft: Color,
    val bg: Color,
    val bgWarm: Color,
    val surface: Color,
    val ink: Color,
    val inkSoft: Color,
    val cocoaThumbBg: Color,
    val emptyCardBg: Color,
    val emptyCardBorder: Color,
    val emptyIconBg: Color,
    val emptyIconInk: Color,
    val pillBg: Color,
    val pillBgMissing: Color,
    val pillFgMissing: Color,
    val ruleOnCocoa: Color,
)

internal val LightPalette = HomePalette(
    isDark = false,
    accent = Color(0xFFF3B4A0),
    accentDark = Color(0xFFE99A80),
    accentInk = Color(0xFF3D2A24),
    accentDeep = Color(0xFFF3B4A0),
    accentDeepInk = Color(0xFF3D2A24),
    cocoa = Color(0xFF3D3230),
    cocoaInk = Color(0xFFF2E9E2),
    cocoaInkSoft = Color(0xFFB8A8A2),
    bg = Color(0xFFFAFAFA),
    bgWarm = Color(0xFFF5F1EC),
    surface = Color(0xFFFFFFFF),
    ink = Color(0xFF1A1A1A),
    inkSoft = Color(0xFF6B6560),
    cocoaThumbBg = Color(0xFF2A2220),
    emptyCardBg = Color(0xFFF5F1EC),
    emptyCardBorder = Color(0x2E000000),
    emptyIconBg = Color(0xFFF3B4A0),
    emptyIconInk = Color(0xFF3D2A24),
    pillBg = Color(0xFFF5F1EC),
    pillBgMissing = Color(0xFFFADAD3),
    pillFgMissing = Color(0xFF3D2A24),
    ruleOnCocoa = Color(0x14FFFFFF),
)

internal val DarkPalette = HomePalette(
    isDark = true,
    accent = Color(0xFFF5B7A0),
    accentDark = Color(0xFFE99A80),
    accentInk = Color(0xFFF5B7A0),
    accentDeep = Color(0xFF5A1F14),
    accentDeepInk = Color(0xFFF5B7A0),
    cocoa = Color(0xFF242628),
    cocoaInk = Color(0xFFE9E4DE),
    cocoaInkSoft = Color(0xFF8B8680),
    bg = Color(0xFF1B1C1E),
    bgWarm = Color(0xFF242628),
    surface = Color(0xFF242628),
    ink = Color(0xFFE9E4DE),
    inkSoft = Color(0xFF8B8680),
    cocoaThumbBg = Color(0xFF1B1C1E),
    emptyCardBg = Color(0xFF2E2017),
    emptyCardBorder = Color(0x0FFFFFFF),
    emptyIconBg = Color(0xFF5A1F14),
    emptyIconInk = Color(0xFFF5B7A0),
    pillBg = Color(0xFF242628),
    pillBgMissing = Color(0xFF3A1810),
    pillFgMissing = Color(0xFFF5B7A0),
    ruleOnCocoa = Color(0x0FFFFFFF),
)

internal val LocalHomePalette = androidx.compose.runtime.staticCompositionLocalOf { LightPalette }


/**
 * Back-compat shim so existing call sites that reference
 * `Palette.foo` keep working while I migrate them over.
 */
internal object Palette {
    val accent = LightPalette.accent
    val accentDark = LightPalette.accentDark
    val accentInk = LightPalette.accentInk
    val cocoa = LightPalette.cocoa
    val cocoaInk = LightPalette.cocoaInk
    val cocoaInkSoft = LightPalette.cocoaInkSoft
    val bg = LightPalette.bg
    val bgWarm = LightPalette.bgWarm
    val ink = LightPalette.ink
    val inkSoft = LightPalette.inkSoft
    val cocoaThumbBg = LightPalette.cocoaThumbBg
}
