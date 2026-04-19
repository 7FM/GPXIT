package dev.gpxit.app.ui.map

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpxit.app.domain.ConnectionOption
import dev.gpxit.app.ui.import_route.DesignIcons
import dev.gpxit.app.ui.theme.LocalMapPalette
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Peeking bottom sheet that rises from the map's bottom edge to show
 * the top connection for each station along the route. Three cards
 * per page, paged horizontally, "Best" card highlighted.
 *
 * Mirrors `TakeMeHomeSheet` + `HomeCarousel` + `HomeCard` in the
 * design handoff (`variations.jsx`, lines 330–637).
 */
@Composable
fun TakeMeHomeSheet(
    options: List<ConnectionOption>,
    isLoading: Boolean,
    onExpand: () -> Unit,
    onDragUp: () -> Unit = onExpand,
    onDragDown: () -> Unit = {},
    onStationSelected: (ConnectionOption) -> Unit = {},
    onCurrentPageChanged: (Int) -> Unit = {},
    onSheetHeightChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalMapPalette.current
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    // Accumulates vertical drag between fingerdown and fingerup so the
    // whole sheet body (not just the grabber) can open fullscreen /
    // close on a meaningful swipe; an `absorb` pointer-input sibling
    // below also consumes horizontal nothings so the map below never
    // pans while the user is interacting with the sheet.
    val totalDrag = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableFloatStateOf(0f)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onSheetHeightChanged(it.height) }
            .shadow(12.dp, sheetShape, clip = false)
            .clip(sheetShape)
            .background(palette.surface)
            // Pointer input MUST be attached to the sheet's root (not
            // an inner child) so the map below never sees the drag,
            // regardless of where on the sheet the finger started.
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

        // Header row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Take me home",
                color = palette.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (options.isEmpty()) "" else "${options.size} options",
                color = palette.inkSoft,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onExpand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DesignIcons.ChevronUp,
                    contentDescription = "Expand",
                    tint = palette.inkSoft,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        when {
            isLoading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }
            options.isEmpty() -> {
                Text(
                    text = "No stations ahead on the route.",
                    color = palette.inkSoft,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            else -> HomeCarousel(
                options = options,
                onStationSelected = onStationSelected,
                onCurrentPageChanged = onCurrentPageChanged,
            )
        }
    }
}

/**
 * Paged carousel — three cards per page, swipeable, dot indicator with
 * the active dot widened into a pill.
 */
@Composable
private fun HomeCarousel(
    options: List<ConnectionOption>,
    onStationSelected: (ConnectionOption) -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
) {
    val palette = LocalMapPalette.current
    val pages = remember(options) { options.chunked(3) }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
        onCurrentPageChanged(pagerState.currentPage)
    }

    HorizontalPager(state = pagerState) { pageIdx ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val page = pages[pageIdx]
            for (i in 0 until 3) {
                val opt = page.getOrNull(i)
                if (opt != null) {
                    // Slot index is the 1-based position in the FULL
                    // options list, not per-page — so swiping to page
                    // 2 shows "4 5 6" instead of "1 2 3" and the
                    // matching map pins carry the same digit.
                    val globalIndex = pageIdx * 3 + i + 1
                    HomeCard(
                        option = opt,
                        isBest = (pageIdx == 0 && i == 0),
                        slotIndex = globalIndex,
                        onClick = { onStationSelected(opt) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        for (i in pages.indices) {
            val active = i == pagerState.currentPage
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (active) palette.accent else Color(0x26000000))
                    .clickable {
                        scope.launch { pagerState.animateScrollToPage(i) }
                    }
            )
        }
    }
}

@Composable
private fun HomeCard(
    option: ConnectionOption,
    isBest: Boolean,
    slotIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMapPalette.current
    val conn = option.connections.firstOrNull()
    val direct = (conn?.numChanges ?: 0) == 0
    val borderColor = if (isBest) palette.accent else palette.line
    val bg = if (isBest) palette.accentTint else palette.surface
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .widthIn(min = 0.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        // 1/2/3 badge mirroring the digit on the matching map marker.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(20.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(palette.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = slotIndex.toString(),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
        Text(
            text = if (isBest) "BEST" else "\u00A0",
            color = if (isBest) palette.accentDark else palette.inkLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = option.station.name,
            color = palette.ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%.1f km".format(
                    option.station.distanceAlongRouteMeters.coerceAtLeast(0.0) / 1000.0
                ),
                color = palette.inkSoft,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "\u2022", color = palette.inkLight, fontSize = 11.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "wait ${option.waitTimeMinutes}'",
                color = palette.inkSoft,
                fontSize = 11.sp,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransferChip(direct = direct, transfers = conn?.numChanges ?: 0)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = conn?.line ?: "—",
                color = palette.inkLight,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "arr ${formatClock(option.bestArrivalHome)}",
            color = palette.ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        }  // end of inner Column
    }
}

/** "DIRECT" accent chip vs. "1× CHG" amber chip. */
@Composable
internal fun TransferChip(direct: Boolean, transfers: Int) {
    val palette = LocalMapPalette.current
    val bg = if (direct) palette.accentTint else palette.transferBg
    val fg = if (direct) palette.accentDark else palette.transferInk
    val label = if (direct) "DIRECT" else "${transfers}\u00D7 CHG"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
        )
    }
}

internal fun formatClock(instant: Instant?): String {
    if (instant == null) return "—"
    val zoned = instant.atZone(ZoneId.systemDefault())
    return zoned.format(DateTimeFormatter.ofPattern("HH:mm"))
}

/** Wait minutes between two instants, clamped to 0. */
internal fun waitBetween(start: Instant, end: Instant): Long =
    Duration.between(start, end).toMinutes().coerceAtLeast(0)

