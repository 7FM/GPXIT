package dev.gpxit.app.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpxit.app.data.gpx.haversineMeters
import dev.gpxit.app.domain.ConnectionOption
import dev.gpxit.app.domain.TrainConnection
import dev.gpxit.app.domain.TripLeg
import dev.gpxit.app.ui.import_route.DesignIcons
import dev.gpxit.app.ui.theme.LocalMapPalette
import dev.gpxit.app.ui.theme.MapPalette
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * In-layout peek sheet shown when a station marker is tapped.
 *
 * Renders inline above the bottom nav (matching [TakeMeHomeSheet] /
 * [ElevationSheet]) instead of as a modal so the map below stays
 * usable and the sheet inherits the parent's `LocalMapPalette`
 * without an extra subcomposition. Layout mirrors the handoff3
 * `StationDetailSheet` in `components/variations.jsx`.
 */
@Composable
fun StationDetailSheet(
    option: ConnectionOption?,
    isLoading: Boolean,
    isThisDestination: Boolean,
    navigationActive: Boolean,
    userLat: Double?,
    userLon: Double?,
    avgSpeedKmh: Double,
    onClose: () -> Unit,
    onSetDestination: () -> Unit,
    onClearDestination: () -> Unit,
    onToggleNavigation: () -> Unit,
    onLoadMoreConnections: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMapPalette.current
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    // Same drag-to-dismiss feel as the other peek sheets, but the
    // detector lives on the top "grabber" zone only — putting it on
    // the whole sheet conflicts with the body's verticalScroll, which
    // wins all vertical drag arbitration and swallows the swipe so
    // it never reaches the dismiss handler.
    val totalDrag = remember { mutableFloatStateOf(0f) }
    val grabberDragModifier = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { totalDrag.floatValue = 0f },
            onDragEnd = {
                val dy = totalDrag.floatValue
                val threshold = 40.dp.toPx()
                if (dy >= threshold) onClose()
                totalDrag.floatValue = 0f
            },
            onDragCancel = { totalDrag.floatValue = 0f },
            onVerticalDrag = { change, delta ->
                totalDrag.floatValue += delta
                change.consume()
            },
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .shadow(12.dp, sheetShape, clip = false)
            .clip(sheetShape)
            .background(palette.surface)
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        when {
            isLoading && option == null -> {
                // Drag handle still present so the loading sheet can be dismissed.
                DragHandle(palette, grabberDragModifier)
                LoadingBlock()
            }
            option == null -> {
                DragHandle(palette, grabberDragModifier)
            }
            else -> {
                // ── Top fixed zone — handle + station header. The
                // drag detector lives here so swipe-down dismisses
                // even when the body is mid-scroll.
                Column(modifier = grabberDragModifier) {
                    DragHandle(palette, Modifier)
                    StationHeaderRow(
                        option = option,
                        userLat = userLat,
                        userLon = userLon,
                        onClose = onClose,
                        palette = palette,
                    )
                }
                // ── Scrollable middle — departures + load more +
                // summary card. Sized via weight(fill = false) so it
                // collapses to its content when small but caps at
                // the remaining sheet height when long.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    StationDeparturesBlock(
                        option = option,
                        onLoadMoreConnections = onLoadMoreConnections,
                        palette = palette,
                    )
                    val cyclingMinutes = computeCyclingMinutes(
                        option = option,
                        userLat = userLat,
                        userLon = userLon,
                        avgSpeedKmh = avgSpeedKmh,
                    )
                    SummaryCard(
                        homeBy = option.bestArrivalHome,
                        cyclingMinutes = cyclingMinutes,
                        palette = palette,
                    )
                }
                // ── Bottom fixed actions.
                StationActionRow(
                    isThisDestination = isThisDestination,
                    navigationActive = navigationActive,
                    onSetDestination = onSetDestination,
                    onClearDestination = onClearDestination,
                    onToggleNavigation = onToggleNavigation,
                    palette = palette,
                )
            }
        }
    }
}

@Composable
private fun DragHandle(palette: MapPalette, modifier: Modifier) {
    // The padding here (vertical = 8.dp) gives the handle a 4 + 8 +
    // 8 = 20dp tap target band; combined with the modifier's drag
    // detector that's enough to comfortably grab even on small
    // screens.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.handle)
        )
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp)
    }
}

private fun computeCyclingMinutes(
    option: ConnectionOption,
    userLat: Double?,
    userLon: Double?,
    avgSpeedKmh: Double,
): Int {
    val isOnRoute = option.station.distanceAlongRouteMeters > 0
    val liveDistanceMeters = if (userLat != null && userLon != null) {
        haversineMeters(userLat, userLon, option.station.lat, option.station.lon)
    } else null
    return if (isOnRoute) {
        option.cyclingTimeMinutes
    } else if (liveDistanceMeters != null) {
        val speedMs = avgSpeedKmh * 1000.0 / 3600.0
        (liveDistanceMeters / speedMs / 60.0).toInt()
    } else {
        option.cyclingTimeMinutes
    }
}

@Composable
private fun StationHeaderRow(
    option: ConnectionOption,
    userLat: Double?,
    userLon: Double?,
    onClose: () -> Unit,
    palette: MapPalette,
) {
    val isOnRoute = option.station.distanceAlongRouteMeters > 0
    val routeKm = option.station.distanceAlongRouteMeters / 1000.0
    val liveDistanceMeters = if (userLat != null && userLon != null) {
        haversineMeters(userLat, userLon, option.station.lat, option.station.lon)
    } else null
    val liveKm = liveDistanceMeters?.let { it / 1000.0 }

    Row(
        modifier = Modifier.padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.accentTint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DesignIcons.Train,
                contentDescription = null,
                tint = palette.accentDark,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp),
        ) {
            Text(
                text = option.station.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isOnRoute) {
                    Text(
                        text = "%.1f km along route".format(routeKm),
                        fontSize = 11.sp,
                        color = palette.inkSoft,
                    )
                    if (liveKm != null) {
                        DotSep(palette)
                        Text(
                            text = "%.1f km away".format(liveKm),
                            fontSize = 11.sp,
                            color = palette.inkSoft,
                        )
                    }
                } else if (liveKm != null) {
                    Text(
                        text = "%.1f km away".format(liveKm),
                        fontSize = 11.sp,
                        color = palette.inkSoft,
                    )
                } else {
                    Text(
                        text = "%.1f km from route".format(
                            option.station.distanceFromRouteMeters / 1000.0
                        ),
                        fontSize = 11.sp,
                        color = palette.inkSoft,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DesignIcons.ChevronDown,
                contentDescription = "Close",
                tint = palette.inkSoft,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun StationDeparturesBlock(
    option: ConnectionOption,
    onLoadMoreConnections: (() -> Unit)?,
    palette: MapPalette,
) {
    if (option.connections.isNotEmpty()) {
        DepartureColumnHeader(palette)
        HorizontalDivider(
            color = palette.line,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Column {
            option.connections.forEachIndexed { i, conn ->
                DepartureRow(
                    connection = conn,
                    arrivalAtStation = option.estimatedArrivalAtStation,
                    highlighted = i == 0,
                    palette = palette,
                )
                if (i < option.connections.size - 1) {
                    HorizontalDivider(
                        color = palette.line,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
        }
    } else {
        Text(
            text = "No connections found",
            fontSize = 12.sp,
            color = palette.trackActive,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
        )
    }

    if (onLoadMoreConnections != null && option.connections.isNotEmpty()) {
        Box(
            modifier = Modifier
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, palette.line, RoundedCornerShape(999.dp))
                .clickable(onClick = onLoadMoreConnections)
                .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Load later connections",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkSoft,
            )
        }
    }
}

@Composable
private fun StationActionRow(
    isThisDestination: Boolean,
    navigationActive: Boolean,
    onSetDestination: () -> Unit,
    onClearDestination: () -> Unit,
    onToggleNavigation: () -> Unit,
    palette: MapPalette,
) {
    Row(
        modifier = Modifier
            .padding(start = 14.dp, end = 14.dp, top = 14.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.5.dp, palette.line, RoundedCornerShape(14.dp))
                .clickable {
                    if (isThisDestination) onClearDestination() else onSetDestination()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isThisDestination) "Clear destination" else "Set as destination",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
            )
        }
        Box(
            modifier = Modifier
                .weight(1.3f)
                .height(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.accent)
                .clickable {
                    if (!isThisDestination) onSetDestination()
                    onToggleNavigation()
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = DesignIcons.Bike,
                    contentDescription = null,
                    tint = palette.onAccent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (isThisDestination && navigationActive) "Stop navigation"
                    else "Navigate to station",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.onAccent,
                )
            }
        }
    }
}

@Composable
private fun DotSep(palette: MapPalette) {
    Spacer(Modifier.width(6.dp))
    Text(
        text = "·",
        fontSize = 11.sp,
        color = palette.inkLight,
    )
    Spacer(Modifier.width(6.dp))
}

@Composable
private fun DepartureColumnHeader(palette: MapPalette) {
    Row(
        modifier = Modifier
            .padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LINE",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = palette.inkLight,
            modifier = Modifier.width(46.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "DEPARTURE → ARRIVAL",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = palette.inkLight,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "TRANSFERS",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = palette.inkLight,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(end = 2.dp),
        )
    }
}

@Composable
private fun DepartureRow(
    connection: TrainConnection,
    arrivalAtStation: Instant,
    highlighted: Boolean,
    palette: MapPalette,
) {
    val direct = connection.numChanges == 0
    val rowBg = if (highlighted) palette.accentBg else Color.Transparent
    var expanded by remember { mutableStateOf(false) }
    val canExpand = connection.legs.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(enabled = canExpand) { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineBadge(connection.line, palette)
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeFormatter.format(connection.departureTime),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "→",
                    fontSize = 13.sp,
                    color = palette.inkLight,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = timeFormatter.format(connection.arrivalTime),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                )
                val waitMin = Duration
                    .between(arrivalAtStation, connection.departureTime)
                    .toMinutes()
                    .coerceAtLeast(0)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${waitMin}′ wait",
                    fontSize = 10.sp,
                    color = palette.inkLight,
                )
            }
            TransfersChip(direct = direct, transfers = connection.numChanges, palette = palette)
            if (canExpand) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = DesignIcons.ChevronDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = palette.inkLight,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            }
        }
        AnimatedVisibility(visible = expanded && canExpand) {
            ConnectionLegs(
                legs = connection.legs,
                palette = palette,
            )
        }
    }
}

/**
 * Inline leg-by-leg breakdown for a single connection: each leg
 * shows its line + direction (or a "Walk N min" pill for foot
 * transfers), the dep/arr stops with times, and any intermediate
 * stops underneath. Mirrors the older `ui/components/ConnectionRow`
 * tap-to-expand UX so the user can still see what changes are
 * involved in a connection. `internal` so the take-me-home timeline
 * can render the same breakdown without duplicating the layout.
 */
@Composable
internal fun ConnectionLegs(legs: List<TripLeg>, palette: MapPalette) {
    Column(
        modifier = Modifier
            .padding(top = 8.dp, start = 4.dp, end = 4.dp)
    ) {
        legs.forEachIndexed { index, leg ->
            if (index > 0) {
                Text(
                    text = "⇄ Change",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.transferInk,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
            }
            if (leg.isWalk) {
                val walkMin = Duration.between(leg.departureTime, leg.arrivalTime).toMinutes()
                Text(
                    text = "🚶 Walk ${walkMin} min · ${leg.departureStation} → ${leg.arrivalStation}",
                    fontSize = 11.sp,
                    color = palette.inkSoft,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            } else {
                val lineLabel = leg.line ?: "?"
                val dirLabel = leg.direction?.let { " → $it" } ?: ""
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$lineLabel$dirLabel",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                    )
                    leg.departureDelayMinutes?.takeIf { it > 0 }?.let {
                        Text(
                            text = "  +${it}′",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.trackActive,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${timeFormatter.format(leg.departureTime)}  ${leg.departureStation}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                )
                for (stop in leg.intermediateStops) {
                    val stopTime = stop.arrivalTime ?: stop.departureTime
                    val timeStr = stopTime?.let { timeFormatter.format(it) } ?: "     "
                    Text(
                        text = "$timeStr  ${stop.name}",
                        fontSize = 11.sp,
                        color = palette.inkSoft,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${timeFormatter.format(leg.arrivalTime)}  ${leg.arrivalStation}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                    )
                    leg.arrivalDelayMinutes?.takeIf { it > 0 }?.let {
                        Text(
                            text = "  +${it}′",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.trackActive,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LineBadge(label: String, palette: MapPalette) {
    val (bg, fg) = lineColors(label, palette)
    Box(
        modifier = Modifier
            .widthIn(min = 36.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.replace(" ", ""),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            letterSpacing = 0.2.sp,
        )
    }
}

@Composable
private fun TransfersChip(direct: Boolean, transfers: Int, palette: MapPalette) {
    val bg = if (direct) palette.accentTint else palette.transferBg
    val fg = if (direct) palette.accentDark else palette.transferInk
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (direct) "DIRECT" else "+$transfers",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            color = fg,
        )
    }
}

@Composable
private fun SummaryCard(
    homeBy: Instant?,
    cyclingMinutes: Int,
    palette: MapPalette,
) {
    Row(
        modifier = Modifier
            .padding(start = 14.dp, end = 14.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surfaceAlt)
            .border(1.dp, palette.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "HOME BY",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = palette.inkLight,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = homeBy?.let { timeFormatter.format(it) } ?: "—",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = palette.accentDark,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = DesignIcons.Bike,
                    contentDescription = null,
                    tint = palette.inkLight,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "RIDE TO STATION",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = palette.inkLight,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = cyclingMinutes.toString(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    color = palette.ink,
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = "min",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkSoft,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Pick a (background, foreground) colour pair for a transit line
 * label. Falls back to a neutral surface/ink pair for unknown
 * agencies; the German operator palette covers the common DB lines
 * the app currently surfaces (S-Bahn red, RE/RB violet, IC dark,
 * Bus magenta).
 */
private fun lineColors(label: String, palette: MapPalette): Pair<Color, Color> {
    val prefix = label.split(' ', '\t').firstOrNull()?.replace(Regex("\\d+"), "") ?: ""
    return when (prefix) {
        "S" -> Color(0xFFE4002B) to Color.White
        "RE" -> Color(0xFFE34A3E) to Color.White
        "RB" -> Color(0xFF8B5CF6) to Color.White
        "IC", "ICE", "EC" -> Color(0xFF1A1A1A) to Color.White
        "Bus" -> Color(0xFF9B2E8C) to Color.White
        else -> palette.surfaceMuted to palette.ink
    }
}
