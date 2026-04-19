package dev.gpxit.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.gpxit.app.ui.theme.LocalMapPalette
import kotlin.math.cos
import kotlin.math.sin

/**
 * Circular frosted-white button, no card — sits directly on the map.
 * Used for the compass (top-left) and locate (bottom-right) controls
 * in the design. Matches `RoundGlassBtn` in `variations.jsx`.
 */
@Composable
fun RoundGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    content: @Composable () -> Unit,
) {
    val palette = LocalMapPalette.current
    Box(
        modifier = modifier
            .size(size)
            .shadow(6.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(palette.surface.copy(alpha = 0.96f))
            .border(1.dp, palette.line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * Rounded-square white button used inside vertical stacks (zoom cluster
 * and the standalone Nearby button on the right rail).
 */
@Composable
fun SquareGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    corner: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    val palette = LocalMapPalette.current
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .size(size)
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(palette.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * A vertical pill of buttons separated by hairline dividers — used for
 * the top-right layers+fullscreen cluster and the right-rail zoom pair.
 */
@Composable
fun VerticalPill(
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    val palette = LocalMapPalette.current
    val shape = RoundedCornerShape(corner)
    Column(
        modifier = modifier
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(palette.surface)
    ) { content() }
}

/** Square-ish button used inside [VerticalPill] — transparent bg so the pill shows through. */
@Composable
fun PillButton(
    onClick: () -> Unit,
    size: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 1dp hairline divider in the palette's `line` color, inset horizontally. */
@Composable
fun PillDivider(inset: Dp = 8.dp, modifier: Modifier = Modifier) {
    val palette = LocalMapPalette.current
    Box(
        modifier = modifier
            .height(1.dp)
            .width(44.dp - inset * 2)
            .background(palette.line)
    )
}

/**
 * Stroke-only tinted icon — small wrapper so buttons above can just
 * pass an `ImageVector` and a size without re-typing the tint line.
 */
@Composable
fun GlassIcon(icon: ImageVector, size: Dp = 22.dp) {
    val palette = LocalMapPalette.current
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = palette.ink,
        modifier = Modifier.size(size),
    )
}

/**
 * Compass face — matches `IconCompass` in icons.jsx: red north triangle,
 * cream-white south triangle, tiny subtle east/west wings, plus a small
 * "N" label at the top. Rotates opposite to map rotation so the red tip
 * always points at geographic north.
 *
 * [mapRotation] is the map's current compass rotation in degrees (same
 * sign convention as osmdroid: positive = clockwise). The needle here
 * counter-rotates so the red tip is pinned to true north on screen.
 */
@Composable
fun CompassFace(
    mapRotation: Float,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .rotate(-mapRotation),
    ) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        // North triangle (red).
        val north = Path().apply {
            moveTo(cx, h * 0.08f)
            lineTo(cx + w * 0.15f, cy)
            lineTo(cx - w * 0.15f, cy)
            close()
        }
        drawPath(north, Color(0xFFE94B4B), style = Fill)
        // South triangle (cream-white with dark outline).
        val south = Path().apply {
            moveTo(cx, h * 0.92f)
            lineTo(cx + w * 0.15f, cy)
            lineTo(cx - w * 0.15f, cy)
            close()
        }
        drawPath(south, Color(0xFFF7F5EF), style = Fill)
        drawPath(south, Color(0xFF3A3A3A), style = Stroke(width = 0.6f))
        // Centre dot for the hinge.
        drawCircle(Color(0xFF1A1A1A), radius = w * 0.04f, center = Offset(cx, cy))
    }
}

/**
 * Blue paper-plane navigation arrow used for the locate FAB — two
 * triangles (left = darker, right = lighter) meeting at the tip.
 * Matches `IconLocator` in icons.jsx.
 */
@Composable
fun LocatorArrow(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val primary = Color(0xFF3A72F4)
    val secondary = Color(0xFF5A8BFF)
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        // Full arrow outline (tip, right wing, cleft, left wing, back to tip).
        val outline = Path().apply {
            moveTo(cx, h * 0.12f)
            lineTo(w * 0.78f, h * 0.88f)
            lineTo(cx, h * 0.70f)
            lineTo(w * 0.22f, h * 0.88f)
            close()
        }
        drawPath(outline, primary, style = Fill)
        drawPath(outline, primary, style = Stroke(width = 1f))
        // Left half fill in the lighter shade so the arrow reads as
        // two-toned.
        val leftHalf = Path().apply {
            moveTo(cx, h * 0.12f)
            lineTo(cx, h * 0.70f)
            lineTo(w * 0.22f, h * 0.88f)
            close()
        }
        drawPath(leftHalf, secondary, style = Fill)
    }
}

/** Compass state isn't needed for the icon — exposed in case callers want both. */
@Composable
fun CompassButton(
    onClick: () -> Unit,
    mapRotation: Float,
    modifier: Modifier = Modifier,
) {
    RoundGlassButton(onClick = onClick, modifier = modifier) {
        CompassFace(mapRotation = mapRotation)
    }
}

@Composable
fun LocateButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RoundGlassButton(onClick = onClick, modifier = modifier) {
        LocatorArrow()
    }
}

/**
 * Thin horizontal divider used inside the vertical button pill
 * (layers+fullscreen, zoom+/−).
 */
@Composable
fun PillBar() = PillDivider()

/** Degrees helper — avoids explicit conversion at call sites. */
@Suppress("unused")
private fun Float.toRad() = (this * Math.PI / 180.0).toFloat()

private fun rotate(cx: Float, cy: Float, x: Float, y: Float, rad: Float): Offset {
    val c = cos(rad.toDouble()).toFloat()
    val s = sin(rad.toDouble()).toFloat()
    val dx = x - cx
    val dy = y - cy
    return Offset(cx + dx * c - dy * s, cy + dx * s + dy * c)
}
