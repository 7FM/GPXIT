package dev.gpxit.app.ui.import_route

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.gpxit.app.domain.RoutePoint

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
