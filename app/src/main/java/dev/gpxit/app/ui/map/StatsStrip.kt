package dev.gpxit.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpxit.app.ui.theme.LocalMapPalette

/**
 * Top stats strip for the map screen — white rounded card with four
 * stat cells separated by thin vertical dividers. Matches the
 * "Stats strip" in Variation 6.
 *
 * [speedIsLive] paints the Speed value in accent green to signal a
 * live-tracking reading, per the design's `accent` prop on
 * `<StatCell ... accent />`.
 */
@Composable
fun StatsStrip(
    distanceKm: String,
    etaClock: String,
    climbMeters: String,
    speedKmh: String,
    modifier: Modifier = Modifier,
    speedIsLive: Boolean = false,
) {
    val palette = LocalMapPalette.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .shadow(6.dp, shape, clip = false)
            .clip(shape)
            .background(palette.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StatCell(label = "Distance", value = distanceKm, unit = "km")
        StatDivider()
        StatCell(label = "ETA", value = etaClock, unit = null)
        StatDivider()
        StatCell(label = "Climb", value = climbMeters, unit = "m")
        StatDivider()
        StatCell(
            label = "Speed",
            value = speedKmh,
            unit = "km/h",
            accent = speedIsLive,
        )
    }
}

/**
 * Single cell. Label (uppercase 9sp) sits directly above the value
 * line. Value + unit are drawn as a single `AnnotatedString` so the
 * Latin baseline of the small unit lines up with the value's baseline
 * — `Row(verticalAlignment = Bottom)` aligns the text bounding boxes
 * instead, which makes the unit float above where you'd expect.
 */
@Composable
private fun StatCell(
    label: String,
    value: String,
    unit: String?,
    accent: Boolean = false,
) {
    val palette = LocalMapPalette.current
    val valueColor = if (accent) palette.accentDark else palette.ink
    val combined: AnnotatedString = buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = valueColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        ) { append(value) }
        if (unit != null) {
            append(" ")
            withStyle(
                SpanStyle(
                    color = palette.inkSoft,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            ) { append(unit) }
        }
    }
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = palette.inkLight,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            lineHeight = 10.sp,
        )
        Text(
            text = combined,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun StatDivider() {
    val palette = LocalMapPalette.current
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(22.dp)
            .background(palette.line)
    )
}
