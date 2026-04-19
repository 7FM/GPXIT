package dev.gpxit.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpxit.app.ui.theme.LocalMapPalette

/**
 * Small peek sheet anchored at the top-right of the map (below the
 * Layers button) that lets the user toggle POI marker categories on
 * or off. Lives outside the normal bottom-peek flow so it doesn't
 * fight for space with Take-me-home / Elevation.
 */
@Composable
fun LayersSheet(
    grocery: Boolean,
    water: Boolean,
    toilet: Boolean,
    bikeRepair: Boolean,
    showStations: Boolean,
    onSetGrocery: (Boolean) -> Unit,
    onSetWater: (Boolean) -> Unit,
    onSetToilet: (Boolean) -> Unit,
    onSetBikeRepair: (Boolean) -> Unit,
    onSetShowStations: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMapPalette.current
    val sheetShape = RoundedCornerShape(16.dp)

    Column(
        modifier = modifier
            .shadow(12.dp, sheetShape, clip = false)
            .clip(sheetShape)
            .background(palette.surface)
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Layers",
                color = palette.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "\u2715", color = palette.inkSoft, fontSize = 14.sp)
            }
        }
        PoiRow("Exit points", Color(0xFFDE1B4B), showStations, onSetShowStations)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .padding(horizontal = 6.dp)
                .background(palette.line)
        )
        PoiRow("Grocery / bakery", Color(0xFF2FA04A), grocery, onSetGrocery)
        PoiRow("Drinking water", Color(0xFF3A72F4), water, onSetWater)
        PoiRow("Toilets", Color(0xFF7A4CC0), toilet, onSetToilet)
        PoiRow("Bike repair", Color(0xFFE07A12), bikeRepair, onSetBikeRepair)
    }
}

@Composable
private fun PoiRow(
    label: String,
    dotColor: Color,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val palette = LocalMapPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!on) }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(dotColor)
                .border(1.dp, Color.White, CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = palette.ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        MiniToggle(on = on, onChange = onChange)
    }
}

@Composable
private fun MiniToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalMapPalette.current
    val w = 34.dp
    val h = 20.dp
    val k = 16.dp
    Box(
        modifier = Modifier
            .size(width = w, height = h)
            .clip(RoundedCornerShape(h / 2))
            .background(if (on) palette.accent else Color(0xFFD6D4CC))
            .clickable { onChange(!on) },
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .size(k)
                .clip(CircleShape)
                .background(Color.White)
                .shadow(1.dp, CircleShape)
        )
    }
}
