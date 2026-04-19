package dev.gpxit.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose replacement for osmdroid's ScaleBarOverlay. Takes the map's
 * current `metersPerPixel` and renders a left-anchored horizontal bar
 * showing a sensible round-number distance (1, 2, 5, 10, 20, 50,
 * 100 … km/m). Rendered as a regular composable so the caller can
 * position it with standard `Modifier.align(Alignment.CenterStart)`
 * and trust the result — no guessing at osmdroid's internal offsets.
 */
@Composable
fun ScaleLegend(
    metersPerPixel: Double,
    modifier: Modifier = Modifier,
) {
    if (metersPerPixel <= 0.0 || !metersPerPixel.isFinite()) return

    // Target bar width on screen: between 40dp and 110dp.
    val density = LocalDensity.current
    val minBarDp = 40.dp
    val maxBarDp = 110.dp
    val minBarPx = with(density) { minBarDp.toPx() }
    val maxBarPx = with(density) { maxBarDp.toPx() }

    // Distance that maxBarPx pixels represents (in meters); pick a
    // "nice" 1/2/5 × 10^n value at-or-below that.
    val maxMeters = metersPerPixel * maxBarPx
    val chosenMeters = niceRound(maxMeters)
    val barPx = (chosenMeters / metersPerPixel).toFloat()
        .coerceAtLeast(minBarPx)
        .coerceAtMost(maxBarPx)
    val barDp = with(density) { barPx.toDp() }

    val label = if (chosenMeters >= 1000.0) {
        val km = chosenMeters / 1000.0
        if (km == km.toInt().toDouble()) "${km.toInt()} km"
        else "%.1f km".format(km)
    } else {
        "${chosenMeters.toInt()} m"
    }

    val stroke = Color(0xCC000000)
    val halo = Color(0xDDFFFFFF)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(halo)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Canvas(
            modifier = Modifier
                .width(barDp)
                .height(8.dp),
        ) {
            val w = size.width
            val h = size.height
            val strokeW = 1.5f * density.density
            // Left cap
            drawLine(stroke, Offset(0f, 0f), Offset(0f, h), strokeWidth = strokeW)
            // Right cap
            drawLine(stroke, Offset(w, 0f), Offset(w, h), strokeWidth = strokeW)
            // Bottom bar
            drawLine(stroke, Offset(0f, h), Offset(w, h), strokeWidth = strokeW)
        }
        Box(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = stroke,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Round [meters] down to the nearest 1/2/5 × 10^n.
 * 1234 → 1000, 678 → 500, 2800 → 2000, 47 → 20, etc.
 */
private fun niceRound(meters: Double): Double {
    if (meters <= 0.0) return 0.0
    val exp = kotlin.math.floor(kotlin.math.log10(meters)).toInt()
    val base = Math.pow(10.0, exp.toDouble())
    val mantissa = meters / base
    val snapped = when {
        mantissa >= 5.0 -> 5.0
        mantissa >= 2.0 -> 2.0
        else -> 1.0
    }
    return snapped * base
}
