package dev.gpxit.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpxit.app.data.gpx.routeClimbDescentMeters
import dev.gpxit.app.domain.RouteInfo
import dev.gpxit.app.ui.theme.LocalMapPalette
import org.osmdroid.util.GeoPoint

/**
 * Peek sheet wrapping the existing [ElevationProfile] with the design
 * handoff's chrome: grabber, "Elevation" title + climb/descent summary.
 * The chart inside keeps its cursor-drag behaviour, so users can still
 * preview a position on the map from inside the sheet.
 */
@Composable
fun ElevationSheet(
    routeInfo: RouteInfo,
    startDistance: Double? = null,
    endDistance: Double? = null,
    onCursorPositionChanged: (GeoPoint?) -> Unit = {},
    onDragUp: () -> Unit = {},
    onDragDown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalMapPalette.current
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    val (climb, descent) = androidx.compose.runtime.remember(routeInfo.points) {
        routeClimbDescentMeters(routeInfo.points)
    }

    // Same whole-sheet drag capture pattern as TakeMeHomeSheet — the
    // sheet's entire body consumes vertical drags so the map never
    // pans, regardless of where the finger lands inside the sheet.
    val totalDrag = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableFloatStateOf(0f)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(12.dp, sheetShape, clip = false)
            .clip(sheetShape)
            .background(palette.surface)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { totalDrag.floatValue = 0f },
                    onDragEnd = {
                        val dy = totalDrag.floatValue
                        val threshold = 40.dp.toPx()
                        if (dy <= -threshold) onDragUp()
                        else if (dy >= threshold) onDragDown()
                        totalDrag.floatValue = 0f
                    },
                    onDragCancel = { totalDrag.floatValue = 0f },
                    onVerticalDrag = { change, delta ->
                        totalDrag.floatValue += delta
                        change.consume()
                    },
                )
            }
            .padding(start = 16.dp, end = 16.dp, bottom = 22.dp),
    ) {
        SheetGrabber()
        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Elevation",
                color = palette.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${climb} m climb \u00B7 ${descent} m descent",
                color = palette.inkSoft,
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        ElevationProfile(
            routeInfo = routeInfo,
            stations = routeInfo.stations,
            startDistance = startDistance,
            endDistance = endDistance,
            onCursorPositionChanged = { dist ->
                val point = dist?.let { d ->
                    dev.gpxit.app.data.gpx.routePointAtDistance(routeInfo.points, d)
                        ?.let { (lat, lon) -> GeoPoint(lat, lon) }
                }
                onCursorPositionChanged(point)
            },
        )
    }
}
