package dev.gpxit.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpxit.app.data.prefs.PrefsRepository
import dev.gpxit.app.data.transit.TransitRepository
import dev.gpxit.app.ui.import_route.DesignIcons
import dev.gpxit.app.ui.theme.LocalMapPalette
import dev.gpxit.app.ui.theme.rememberMapPalette
import kotlinx.coroutines.flow.StateFlow

private val ALL_PRODUCTS = listOf(
    "HIGH_SPEED_TRAIN" to "ICE/IC",
    "REGIONAL_TRAIN" to "Regional",
    "SUBURBAN_TRAIN" to "S-Bahn",
    "SUBWAY" to "U-Bahn",
    "TRAM" to "Tram",
    "BUS" to "Bus",
    "FERRY" to "Ferry",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    prefsFlow: StateFlow<PrefsRepository.UserPreferences>,
    onSetHomeStation: (TransitRepository.StationSuggestion) -> Unit,
    onSetSpeed: (Double) -> Unit,
    onSetSearchRadius: (Int) -> Unit,
    onQueryChanged: (String) -> Unit,
    onToggleProduct: (String) -> Unit,
    onToggleConnectionProduct: (String) -> Unit,
    onSetElevationAwareTime: (Boolean) -> Unit,
    onSetMinWaitBuffer: (Int) -> Unit,
    onSetMaxWaitMinutes: (Int) -> Unit,
    onSetMaxStationsToCheck: (Int) -> Unit,
    onSetShowElevationGraph: (Boolean) -> Unit,
    onSetPoiDbAutoUpdate: (Boolean) -> Unit,
    onUpdatePoiDb: () -> Unit,
    poiDbDownloadState: dev.gpxit.app.GpxitDownloadState,
    poiDbAvailable: Boolean,
    onSetTripTrackingEnabled: (Boolean) -> Unit,
    onSetThemeMode: (dev.gpxit.app.ui.theme.ThemeMode) -> Unit,
    stationSuggestions: List<TransitRepository.StationSuggestion>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs by prefsFlow.collectAsState(initial = PrefsRepository.UserPreferences())
    var stationQuery by remember { mutableStateOf("") }
    var openGroup by remember { mutableStateOf<String?>("stations") }

    // System-bar icon tint is handled centrally in GpxitApp.

    val palette = rememberMapPalette()
    CompositionLocalProvider(LocalMapPalette provides palette) {
        val statusBars = WindowInsets.statusBars.asPaddingValues()
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(palette.sheetBg),
        ) {
            // Header bar — chevron-left + title.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .border(1.dp, palette.line)
                    .padding(top = 12.dp + statusBars.calculateTopPadding())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DesignIcons.ChevronLeft,
                        contentDescription = "Back",
                        tint = palette.ink,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Settings",
                    color = palette.ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) {
                // Home
                AccordionGroup(
                    id = "home",
                    title = "Home",
                    summary = prefs.homeStationName ?: "Not set",
                    icon = DesignIcons.Home,
                    isOpen = openGroup == "home",
                    onToggle = { openGroup = if (openGroup == "home") null else "home" },
                ) {
                    Column {
                        Text(
                            text = "Search station",
                            color = palette.inkSoft,
                            fontSize = 11.sp,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = stationQuery,
                            onValueChange = {
                                stationQuery = it
                                onQueryChanged(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFFFAF8F2),
                                unfocusedContainerColor = Color(0xFFFAF8F2),
                            ),
                        )
                        if (stationSuggestions.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            for (s in stationSuggestions) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFFAF8F2))
                                        .border(1.dp, palette.line, RoundedCornerShape(10.dp))
                                        .clickable {
                                            onSetHomeStation(s)
                                            stationQuery = ""
                                        }
                                        .padding(12.dp),
                                ) {
                                    Text(text = s.name, color = palette.ink, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                // Stations on route
                AccordionGroup(
                    id = "stations",
                    title = "Stations on route",
                    summary = formatChipsSummary(prefs.enabledProducts, ALL_PRODUCTS),
                    icon = DesignIcons.Train,
                    isOpen = openGroup == "stations",
                    onToggle = {
                        openGroup = if (openGroup == "stations") null else "stations"
                    },
                ) {
                    ChipGroup(
                        options = ALL_PRODUCTS,
                        selected = prefs.enabledProducts,
                        onToggle = onToggleProduct,
                    )
                }

                // Connections home
                AccordionGroup(
                    id = "conns",
                    title = "Connections home",
                    summary = formatChipsSummary(prefs.connectionProducts, ALL_PRODUCTS),
                    icon = DesignIcons.Route,
                    isOpen = openGroup == "conns",
                    onToggle = {
                        openGroup = if (openGroup == "conns") null else "conns"
                    },
                ) {
                    ChipGroup(
                        options = ALL_PRODUCTS,
                        selected = prefs.connectionProducts,
                        onToggle = onToggleConnectionProduct,
                    )
                }

                // Planning
                AccordionGroup(
                    id = "planning",
                    title = "Planning",
                    summary = "${prefs.avgSpeedKmh.toInt()} km/h \u00B7 " +
                        "${prefs.maxStationsToCheck} exits \u00B7 " +
                        "${prefs.searchRadiusMeters}m radius",
                    icon = DesignIcons.Clock,
                    isOpen = openGroup == "planning",
                    onToggle = {
                        openGroup = if (openGroup == "planning") null else "planning"
                    },
                ) {
                    Column {
                        Text(
                            text = "Exit points to check",
                            color = palette.ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        SliderClassic(
                            min = 4, max = 20, step = 1,
                            value = prefs.maxStationsToCheck,
                            onChange = onSetMaxStationsToCheck,
                            unit = "",
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Average speed",
                            color = palette.ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        SliderClassic(
                            min = 10, max = 35, step = 1,
                            value = prefs.avgSpeedKmh.toInt(),
                            onChange = { onSetSpeed(it.toDouble()) },
                            unit = " km/h",
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Elevation-aware time",
                                    color = palette.ink,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Adjust speed for uphill/downhill",
                                    color = palette.inkSoft,
                                    fontSize = 11.sp,
                                )
                            }
                            SettingsToggle(
                                on = prefs.elevationAwareTime,
                                onChange = onSetElevationAwareTime,
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Station search radius",
                            color = palette.ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        SliderClassic(
                            min = 500, max = 10000, step = 500,
                            value = prefs.searchRadiusMeters,
                            onChange = onSetSearchRadius,
                            unit = " m",
                        )
                    }
                }

                // Wait at station
                AccordionGroup(
                    id = "wait",
                    title = "Wait at station",
                    summary = "${prefs.minWaitBufferMinutes}\u2013${prefs.maxWaitMinutes} min",
                    icon = DesignIcons.Hourglass,
                    isOpen = openGroup == "wait",
                    onToggle = {
                        openGroup = if (openGroup == "wait") null else "wait"
                    },
                ) {
                    Column {
                        Text(
                            text = "Minimum buffer",
                            color = palette.ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        SliderClassic(
                            min = 0, max = 30, step = 1,
                            value = prefs.minWaitBufferMinutes,
                            onChange = onSetMinWaitBuffer,
                            unit = " min",
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Maximum wait",
                            color = palette.ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        SliderClassic(
                            min = 0, max = 120, step = 15,
                            value = prefs.maxWaitMinutes,
                            onChange = onSetMaxWaitMinutes,
                            unit = " min",
                        )
                    }
                }

                // Map & data
                AccordionGroup(
                    id = "data",
                    title = "Map & data",
                    summary = poiSummary(prefs.poiDbLastUpdateMs, poiDbAvailable),
                    icon = DesignIcons.Layers,
                    isOpen = openGroup == "data",
                    onToggle = {
                        openGroup = if (openGroup == "data") null else "data"
                    },
                ) {
                    Column {
                        // POI DB card.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.sheetBg)
                                .border(1.dp, palette.line, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "POI database",
                                        color = palette.ink,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    // While a download is live, the downloader's
                                    // own label wins (e.g. "Downloading 3.2 / 5.0 MB")
                                    // so the user sees progress in MB instead of
                                    // the "Updated today" idle copy.
                                    val statusLine = if (poiDbDownloadState.active) {
                                        poiDbDownloadState.label
                                    } else {
                                        poiSummary(prefs.poiDbLastUpdateMs, poiDbAvailable)
                                    }
                                    Text(
                                        text = statusLine,
                                        color = palette.inkSoft,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                Button(
                                    onClick = onUpdatePoiDb,
                                    enabled = !poiDbDownloadState.active,
                                    shape = RoundedCornerShape(999.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.accent,
                                        contentColor = Color.White,
                                    ),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 12.dp, vertical = 0.dp,
                                    ),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text(
                                        text = if (poiDbAvailable) "Update" else "Download",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            if (poiDbDownloadState.active || poiDbDownloadState.progress > 0f) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { poiDbDownloadState.progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(palette.line),
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Auto-update monthly",
                                    color = palette.ink,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                SettingsToggle(
                                    on = prefs.poiDbAutoUpdate,
                                    onChange = onSetPoiDbAutoUpdate,
                                    small = true,
                                )
                            }
                        }
                    }
                }

                // Appearance — theme override
                AccordionGroup(
                    id = "appearance",
                    title = "Appearance",
                    summary = themeSummary(prefs.themeMode),
                    icon = DesignIcons.Layers,
                    isOpen = openGroup == "appearance",
                    onToggle = {
                        openGroup = if (openGroup == "appearance") null else "appearance"
                    },
                ) {
                    ThemePicker(
                        current = prefs.themeMode,
                        onSelect = onSetThemeMode,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

private fun themeSummary(mode: dev.gpxit.app.ui.theme.ThemeMode): String = when (mode) {
    dev.gpxit.app.ui.theme.ThemeMode.SYSTEM -> "Follow system"
    dev.gpxit.app.ui.theme.ThemeMode.LIGHT -> "Always light"
    dev.gpxit.app.ui.theme.ThemeMode.DARK -> "Always dark"
}

/**
 * Three-segment toggle: System / Light / Dark. The active segment
 * fills with `palette.accent`; the others sit on `palette.sheetBg`
 * with the same border radius so the unselected pair reads as a
 * single bar with the picked option lifted out.
 */
@Composable
private fun ThemePicker(
    current: dev.gpxit.app.ui.theme.ThemeMode,
    onSelect: (dev.gpxit.app.ui.theme.ThemeMode) -> Unit,
) {
    val palette = LocalMapPalette.current
    val outerShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(palette.sheetBg)
            .border(1.dp, palette.line, outerShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            dev.gpxit.app.ui.theme.ThemeMode.SYSTEM to "System",
            dev.gpxit.app.ui.theme.ThemeMode.LIGHT to "Light",
            dev.gpxit.app.ui.theme.ThemeMode.DARK to "Dark",
        ).forEach { (mode, label) ->
            val active = mode == current
            val inner = RoundedCornerShape(9.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(inner)
                    .background(if (active) palette.accent else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) palette.onAccent else palette.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Atoms — accordion group, slider, toggle, chip group.
// ──────────────────────────────────────────────────────────────

@Composable
private fun AccordionGroup(
    @Suppress("UNUSED_PARAMETER") id: String,
    title: String,
    summary: String,
    icon: ImageVector,
    isOpen: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = LocalMapPalette.current
    val shape = RoundedCornerShape(14.dp)
    val borderColor = if (isOpen) palette.accent else palette.line
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, borderColor, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isOpen) palette.accent else palette.accentTint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isOpen) Color.White else palette.accentDark,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = palette.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = summary,
                    color = palette.inkSoft,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                imageVector = DesignIcons.ChevronDown,
                contentDescription = null,
                tint = palette.inkLight,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (isOpen) 180f else 0f),
            )
        }
        if (isOpen) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.line)
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                content()
            }
        }
    }
}

/**
 * Classic slider matching the design handoff:
 *   - 4dp grey track, with the portion up to the thumb filled in
 *     [MapPalette.accent] green.
 *   - 18dp white thumb with a 2dp accent ring.
 *   - Value bubble (dark pill, white text) that sits above the thumb
 *     and tracks its horizontal position.
 *
 * Implemented as an overlay on Material3's [Slider] so we get drag,
 * keyboard, and accessibility for free — the Material thumb/track are
 * hidden (transparent) and our own visuals are drawn on top.
 */
@Composable
private fun SliderClassic(
    min: Int,
    max: Int,
    step: Int,
    value: Int,
    onChange: (Int) -> Unit,
    unit: String,
) {
    val palette = LocalMapPalette.current
    val clamped = value.coerceIn(min, max)
    val steps = (((max - min) / step) - 1).coerceAtLeast(0)
    val pct: Float = if (max == min) 0f else (clamped - min).toFloat() / (max - min).toFloat()

    // Material3's Slider draws its thumb and track inset from the edge
    // by the thumb's own width; querying the node for this exact inset
    // is non-trivial, so we hard-code 10dp (matches the Material3
    // thumb radius) — our 18dp thumb needs to be drawn centered at the
    // same pixel position the Material thumb would occupy so the
    // visible and interactive positions agree.
    val thumbInset = 10.dp

    // Bubble width measured at layout time so we can centre it on the
    // thumb irrespective of the number's character count.
    var bubbleWidthPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bubbleWidthDp = with(density) { bubbleWidthPx.toDp() }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 4.dp),
    ) {
        val totalW = maxWidth
        val trackW = (totalW - thumbInset * 2).coerceAtLeast(0.dp)
        val thumbCenterX = thumbInset + trackW * pct
        val bubbleLeft = (thumbCenterX - bubbleWidthDp / 2)
            .coerceIn(0.dp, (totalW - bubbleWidthDp).coerceAtLeast(0.dp))

        // Value bubble — anchored TopStart and offset horizontally so
        // its centre lands on thumbCenterX (clamped to the row edges).
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = bubbleLeft, y = (-4).dp)
                .onSizeChanged { bubbleWidthPx = it.width }
                .clip(RoundedCornerShape(8.dp))
                .background(palette.ink)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = "$clamped$unit",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 13.sp,
            )
        }

        // Track lane, 22dp tall, vertically centred in the row.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(22.dp),
        ) {
            // Full-width grey track, 4dp tall, vertically centered.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = thumbInset)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFE0DDD2))
            )
            // Accent-filled portion from start to the thumb.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbInset)
                    .width(trackW * pct)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.accent)
            )
            // 18dp white thumb with accent ring, centered at thumbCenterX.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbCenterX - 9.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, palette.accent, CircleShape),
            )
        }

        // Invisible Material slider handles drag + a11y.
        androidx.compose.material3.Slider(
            value = clamped.toFloat(),
            onValueChange = { f ->
                val snapped = (kotlin.math.round(f / step).toInt() * step).coerceIn(min, max)
                if (snapped != clamped) onChange(snapped)
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = steps,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                disabledThumbColor = Color.Transparent,
                disabledActiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent,
            ),
        )
    }
}

/** Pill toggle matching the design's `<Toggle>` component. */
@Composable
private fun SettingsToggle(
    on: Boolean,
    onChange: (Boolean) -> Unit,
    small: Boolean = false,
) {
    val palette = LocalMapPalette.current
    val w = if (small) 34.dp else 44.dp
    val h = if (small) 20.dp else 26.dp
    val k = if (small) 16.dp else 22.dp
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
        )
    }
}

/**
 * Multi-select pill chips — on=accent-tinted outline, off=white+line.
 * Matches `ChipGroup` from the design handoff.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val palette = LocalMapPalette.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((key, label) in options) {
            val on = key in selected
            val bg = if (on) palette.accentTint else palette.surface
            val border = if (on) palette.accent else palette.line
            val fg = if (on) palette.accentDark else palette.inkSoft
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(999.dp))
                    .clickable { onToggle(key) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Compact summary of selected chips: "4/7 · A, B, C, D". */
private fun formatChipsSummary(
    selected: Set<String>,
    all: List<Pair<String, String>>,
): String {
    val labels = all.filter { it.first in selected }.joinToString(", ") { it.second }
    val short = if (labels.length > 40) labels.substring(0, 40) + "\u2026" else labels
    return if (selected.isEmpty()) "None selected"
    else "${selected.size}/${all.size} \u00B7 $short"
}

private fun poiSummary(lastUpdateMs: Long, available: Boolean): String {
    if (!available) return "Not downloaded yet"
    if (lastUpdateMs == 0L) return "Installed"
    val nowMs = System.currentTimeMillis()
    val days = ((nowMs - lastUpdateMs) / 86_400_000L).toInt()
    val rel = when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        else -> "$days days ago"
    }
    return "Updated $rel"
}
