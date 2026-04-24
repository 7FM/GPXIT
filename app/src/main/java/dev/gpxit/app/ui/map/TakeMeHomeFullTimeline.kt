package dev.gpxit.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpxit.app.domain.ConnectionOption
import dev.gpxit.app.domain.TrainConnection
import dev.gpxit.app.ui.import_route.DesignIcons
import dev.gpxit.app.ui.theme.LocalMapPalette
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Fullscreen "Take me home" timeline — every station on the route,
 * each with the earliest departure shown inline and later departures
 * revealed on tap. Mirrors `TakeMeHomeFullTimeline` in the design
 * handoff (`variations.jsx`, lines 363–475).
 */
@Composable
fun TakeMeHomeFullTimeline(
    options: List<ConnectionOption>,
    homeStationName: String?,
    onClose: () -> Unit,
    onStationSelected: (ConnectionOption) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalMapPalette.current
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.sheetBg),
    ) {
        // Top bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .border(1.dp, palette.line)
                .padding(top = 12.dp + statusBarPadding.calculateTopPadding())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DesignIcons.ChevronDown,
                    contentDescription = "Close",
                    tint = palette.ink,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Take me home",
                    color = palette.ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                val subtitle = buildString {
                    append("${options.size} stations")
                    if (!homeStationName.isNullOrBlank()) append(" \u00B7 home: $homeStationName")
                }
                Text(
                    text = subtitle,
                    color = palette.inkSoft,
                    fontSize = 11.sp,
                )
            }
        }

        // Body — station rows. Add the system nav-bar inset to the
        // bottom contentPadding so the last card's "+N later
        // departures" row clears the 3-button bar (and gesture
        // affordance on edge-to-edge devices).
        val expanded = remember { mutableStateMapOf<String, Boolean>() }
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp, end = 14.dp, top = 10.dp, bottom = 20.dp + navBarBottom,
            ),
        ) {
            items(items = options, key = { it.station.id }) { opt ->
                val isOpen = expanded[opt.station.id] == true
                val isBest = opt.isRecommended
                val isLast = opt === options.lastOrNull()
                TimelineRow(
                    option = opt,
                    isBest = isBest,
                    isExpanded = isOpen,
                    isLast = isLast,
                    onToggle = {
                        expanded[opt.station.id] = !(expanded[opt.station.id] ?: false)
                    },
                    onSelectStation = { onStationSelected(opt) },
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(
    option: ConnectionOption,
    isBest: Boolean,
    isExpanded: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onSelectStation: () -> Unit,
) {
    val palette = LocalMapPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        // Dot + connector stem.
        Column(
            modifier = Modifier.width(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val dotBg = if (isBest) palette.accent else palette.surface
            val dotBorder = if (isBest) palette.accent else Color(0xFF1F3B8A)
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(dotBg)
                    .border(2.dp, dotBorder, CircleShape)
            )
            if (!isLast) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(palette.line)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))

        val cardShape = RoundedCornerShape(12.dp)
        val cardBorderColor = if (isBest) palette.accent else palette.line
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(cardShape)
                .background(palette.surface)
                .border(1.dp, cardBorderColor, cardShape),
        ) {
            val extras = option.connections.drop(1)

            // Primary block — station name + best chip + first connection.
            // Tapping the primary area dismisses the timeline and
            // zooms the map to this station; expand / collapse for
            // later departures stays on its own bottom row below.
            Column(
                modifier = Modifier
                    .clickable(onClick = onSelectStation)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = option.station.name,
                        color = palette.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isBest) {
                        Spacer(modifier = Modifier.width(8.dp))
                        BestPill()
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "%.1f km".format(
                            option.station.distanceAlongRouteMeters.coerceAtLeast(0.0) / 1000.0
                        ),
                        color = palette.inkSoft,
                        fontSize = 11.sp,
                    )
                }
                val first = option.connections.firstOrNull()
                if (first != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    ConnectionRow(
                        conn = first,
                        waitMinutes = option.waitTimeMinutes,
                        small = false,
                    )
                } else {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No connections found.",
                        color = palette.inkSoft,
                        fontSize = 11.sp,
                    )
                }
            }

            // Later departures (expanded).
            if (isExpanded && extras.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.line)
                        .background(palette.sheetBg),
                ) {
                    Text(
                        text = "LATER DEPARTURES",
                        color = palette.inkLight,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                        modifier = Modifier.padding(
                            start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp
                        ),
                    )
                    extras.forEachIndexed { i, conn ->
                        if (i > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(palette.line)
                            )
                        }
                        val wait = waitBetween(option.estimatedArrivalAtStation, conn.departureTime)
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            ConnectionRow(
                                conn = conn,
                                waitMinutes = wait.toInt(),
                                small = true,
                            )
                        }
                    }
                }
            }

            // Expand/collapse control.
            if (extras.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.line)
                        .background(if (isExpanded) palette.sheetBg else palette.surface)
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isExpanded) {
                            "Hide later departures"
                        } else {
                            "+${extras.size} later departure${if (extras.size > 1) "s" else ""}"
                        },
                        color = palette.inkSoft,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = DesignIcons.ChevronDown,
                        contentDescription = null,
                        tint = palette.inkSoft,
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(if (isExpanded) 180f else 0f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BestPill() {
    val palette = LocalMapPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(palette.accentTint)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "BEST",
            color = palette.accentDark,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
        )
    }
}

/**
 * A single departure row — direct/transfers chip + line label, then a
 * secondary line with dep/wait/arr times. Used inline for the primary
 * connection and in the expanded later-departure list. Tapping the
 * row expands an inline leg-by-leg breakdown when the connection has
 * multiple legs (delegated to the shared [ConnectionLegs] composable
 * the station-detail sheet also uses).
 */
@Composable
private fun ConnectionRow(conn: TrainConnection, waitMinutes: Int, small: Boolean) {
    val palette = LocalMapPalette.current
    val direct = conn.numChanges == 0
    var expanded by remember { mutableStateOf(false) }
    val canExpand = conn.legs.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canExpand) { expanded = !expanded }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransferChip(direct = direct, transfers = conn.numChanges)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = conn.line,
                color = palette.ink,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (canExpand) {
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = DesignIcons.ChevronDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = palette.inkLight,
                    modifier = Modifier
                        .size(12.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            }
        }
        Spacer(modifier = Modifier.height(if (small) 2.dp else 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "dep ${formatClock(conn.departureTime)}",
                color = palette.inkSoft,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "wait ${waitMinutes}'",
                color = palette.inkSoft,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(text = "arr ", color = palette.inkSoft, fontSize = 11.sp)
            Text(
                text = formatClock(conn.arrivalTime),
                color = palette.ink,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        AnimatedVisibility(visible = expanded && canExpand) {
            ConnectionLegs(legs = conn.legs, palette = palette)
        }
    }
}

private fun formatClock(instant: java.time.Instant): String =
    instant.atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
