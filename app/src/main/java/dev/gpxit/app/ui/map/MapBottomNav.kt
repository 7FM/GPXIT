package dev.gpxit.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpxit.app.ui.theme.LocalMapPalette

/**
 * Bottom navigation bar used on the map screen. Frosted-white surface
 * with a hairline top border, four equal cells. The first item is the
 * primary action (green rounded-rect around its icon); the Track cell
 * flips to red with a filled square when tracking is active.
 */
enum class MapNavItem { TakeMeHome, Track, Elevation, More }

data class MapNavEntry(
    val item: MapNavItem,
    val icon: ImageVector?,   // null for the custom-drawn Track icon
    val label: String,
    val primary: Boolean = false,
    val active: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun MapBottomNav(
    entries: List<MapNavEntry>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMapPalette.current
    val navInsets = WindowInsets.navigationBars.asPaddingValues()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surface.copy(alpha = 0.98f))
            .border(1.dp, palette.line)
            .padding(
                top = 10.dp,
                bottom = 14.dp + navInsets.calculateBottomPadding(),
                start = 4.dp,
                end = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        // Each entry gets an equal share of the row width so the
        // whole cell (icon + label) is a reliable click target.
        for (e in entries) {
            NavCell(entry = e, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavCell(entry: MapNavEntry, modifier: Modifier = Modifier) {
    val palette = LocalMapPalette.current
    val primaryTint = palette.accentDark
    val activeTint = palette.trackActive
    val foreground = when {
        entry.primary -> primaryTint
        entry.active -> activeTint
        else -> palette.inkSoft
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .height(60.dp)
            .clickable(onClick = entry.onClick)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val iconSize = if (entry.primary) 22.dp else 22.dp
        val iconBoxW = if (entry.primary) 44.dp else 30.dp
        val iconBoxH = if (entry.primary) 32.dp else 30.dp
        val iconBoxShape =
            if (entry.primary) RoundedCornerShape(16.dp) else RoundedCornerShape(0.dp)
        val iconBoxBg = if (entry.primary) palette.accent else Color.Transparent
        val iconTint = if (entry.primary) Color.White else foreground
        Box(
            modifier = Modifier
                .size(width = iconBoxW, height = iconBoxH)
                .clip(iconBoxShape)
                .background(iconBoxBg),
            contentAlignment = Alignment.Center,
        ) {
            when (entry.item) {
                MapNavItem.Track -> TrackIndicator(
                    active = entry.active,
                    tint = iconTint,
                )
                else -> entry.icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = entry.label,
                        tint = iconTint,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
        Box(modifier = Modifier.height(3.dp))
        Text(
            text = entry.label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.1.sp,
            color = foreground,
        )
    }
}

/**
 * The Track button's custom icon: a hollow circle containing either a
 * solid dot (off) or a small red square (on). Mirrors the design's
 * inline JSX for the Track nav entry.
 */
@Composable
private fun TrackIndicator(active: Boolean, tint: Color) {
    val palette = LocalMapPalette.current
    Box(
        modifier = Modifier
            .size(22.dp)
            .border(2.dp, tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.trackActive),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
        }
    }
}
