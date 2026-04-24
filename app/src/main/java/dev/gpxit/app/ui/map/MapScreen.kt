package dev.gpxit.app.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gpxit.app.data.gpx.routeClimbDescentMeters
import dev.gpxit.app.domain.ConnectionOption
import dev.gpxit.app.domain.StationCandidate
import dev.gpxit.app.ui.import_route.DesignIcons
import dev.gpxit.app.ui.theme.LocalMapPalette
import dev.gpxit.app.ui.theme.rememberMapPalette
import kotlinx.coroutines.flow.StateFlow
import org.osmdroid.util.GeoPoint
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Tiny 4-tuple for (latSouth, latNorth, lonWest, lonEast) viewport state. */
private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

/**
 * Which peek sheet is currently raised. At most one is visible at a
 * time so the map stays usable below.
 */
private enum class MapPeek { None, Home, Elevation }

/**
 * Pixel insets on each side of the MapView that must NOT be used for
 * fitting content. Stacks of overlaid controls (stats strip on top,
 * compass/layers at top-left and top-right, zoom+nearby right rail,
 * bottom nav, peek sheet) all cover parts of the map, so fits have to
 * treat `MapView.height × MapView.width` as `(h - top - bottom) × (w
 * - left - right)` clear area.
 */
data class FitInsets(
    val leftPx: Int = 0,
    val topPx: Int = 0,
    val rightPx: Int = 0,
    val bottomPx: Int = 0,
)

/**
 * Ask the map to fit a list of stations to the area inside [insets].
 * Nonce forces the effect to fire again when the same three stations
 * are requested twice in a row (e.g. paging back).
 */
data class FitStationsRequest(
    val stations: List<StationCandidate>,
    val insets: FitInsets,
    val nonce: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    routeInfoFlow: StateFlow<dev.gpxit.app.domain.RouteInfo?>,
    userLocation: GeoPoint?,
    userAccuracyMeters: Float? = null,
    homeStationLocation: GeoPoint?,
    highlightedStation: StationCandidate?,
    nearbyStations: List<StationCandidate>,
    initialMapCommand: MapCommand,
    zoomToStation: StationCandidate?,
    onZoomToStationConsumed: () -> Unit,
    selectedStationInfo: ConnectionOption?,
    isLoadingStationInfo: Boolean,
    isSearchingNearby: Boolean,
    onTakeMeHome: () -> Unit,
    onMapCommandConsumed: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDownloadOfflineMap: () -> Unit,
    downloadState: dev.gpxit.app.GpxitDownloadState,
    showElevationGraph: Boolean = true,
    stationLabels: Map<String, dev.gpxit.app.domain.StationLabel> = emptyMap(),
    routePois: List<dev.gpxit.app.domain.Poi> = emptyList(),
    poiDatabase: dev.gpxit.app.data.poi.PoiDatabase,
    poiGrocery: Boolean = false,
    poiWater: Boolean = false,
    poiToilet: Boolean = false,
    poiBikeRepair: Boolean = false,
    onSetPoiGrocery: (Boolean) -> Unit = {},
    onSetPoiWater: (Boolean) -> Unit = {},
    onSetPoiToilet: (Boolean) -> Unit = {},
    onSetPoiBikeRepair: (Boolean) -> Unit = {},
    onSearchNearby: (center: GeoPoint, radiusMeters: Int) -> Unit,
    onClearNearbyStations: () -> Unit,
    onStationClick: (StationCandidate) -> Unit,
    onOpenStationDetail: (ConnectionOption) -> Unit = {},
    onLoadMoreConnections: (() -> Unit)?,
    onDismissStationInfo: () -> Unit,
    tripTrackingEnabled: Boolean = true,
    tripTrackingActive: Boolean = false,
    onStartTripTracking: () -> Unit = {},
    onStopTripTracking: () -> Unit = {},
    userDestinationStation: StationCandidate? = null,
    onSetDestination: (ConnectionOption?) -> Unit = {},
    navigationActive: Boolean = false,
    onToggleNavigation: () -> Unit = {},
    navigationLastMile: List<GeoPoint>? = null,
    initialMapCenter: GeoPoint? = null,
    initialMapZoom: Double? = null,
    onMapViewportSnapshot: (center: GeoPoint, zoom: Double) -> Unit = { _, _ -> },
    decisionOptions: List<ConnectionOption> = emptyList(),
    isDecisionLoading: Boolean = false,
    homeStationName: String? = null,
    avgSpeedKmh: Double = 18.0,
    tripSnapshot: dev.gpxit.app.data.tracking.TripSnapshot? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val routeInfo by routeInfoFlow.collectAsState()
    var mapCommand by remember { mutableStateOf(initialMapCommand) }
    var mapRotation by remember { mutableFloatStateOf(0f) }
    var zoomLevel by remember { mutableStateOf(13.0) }
    // metersPerPixel at map centre — feeds the Compose-drawn scale
    // bar on the left edge. 0.0 (or negative) suppresses the bar.
    var metersPerPixel by remember { mutableStateOf(0.0) }
    var pendingMapCenterCallback by remember { mutableStateOf<((GeoPoint, Int) -> Unit)?>(null) }
    var previewPosition by remember { mutableStateOf<GeoPoint?>(null) }
    var visibleStartDistance by remember { mutableStateOf<Double?>(null) }
    var visibleEndDistance by remember { mutableStateOf<Double?>(null) }

    // Which peek sheet (if any) is up.
    var peek by remember { mutableStateOf(MapPeek.None) }
    var fullscreenTimeline by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    // Three-state location-tracking toggle driven by the bottom-right
    // FAB. Off → Following → Compass → Off; manual map pans inside
    // OsmMapView reset this back to Off (the locate-button icon
    // updates accordingly).
    var locateMode by remember { mutableStateOf(LocateMode.Off) }
    // Hoisted device azimuth (TYPE_ROTATION_VECTOR). Shared between
    // the locate FAB (rotates the chevron in Following) and the map
    // (rotates the user marker + drives heading-up in Compass) so
    // we only register one sensor listener for the screen.
    val deviceHeading by rememberDeviceHeading()
    // Show / hide route station markers (the Layers sheet's "Exit
    // points" toggle). Ephemeral session state — defaults to visible.
    var showStations by remember { mutableStateOf(true) }

    // Measured peek-sheet height (pixels) + the carousel's current
    // page — both drive the auto-fit that keeps the three
    // currently-visible stations on-screen above the sheet.
    var peekSheetHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var homeCarouselPage by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var fitStationsRequest by remember { mutableStateOf<FitStationsRequest?>(null) }

    // Right-rail control stack width (zoom pill + nearby square +
    // 14dp edge) that the fit effects need to avoid — otherwise the
    // stations/route end up stuck under the controls on the right.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rightRailPx = with(density) { (14 + 44 + 8).dp.toPx().toInt() }
    // The only chrome on the LEFT edge is the top-left compass FAB and
    // the bottom-left scale legend — both of which already sit
    // *inside* the top/bottom insets (topInsetPx ≈ 150dp, bottomNavPx
    // covers the scale legend's strip). Nothing lives in the left
    // middle strip, so we only need a small visual margin from the
    // screen edge, not a full compass-width inset. This lets
    // zoom-to-route (Fullscreen) actually use the left half of the
    // screen instead of wasting ~68dp to avoid a compass that was
    // already excluded vertically.
    val leftRailPx = with(density) { 14.dp.toPx().toInt() }
    val statusBarTopPx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottomPx = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Top chrome = stats strip at the top + clearance for the
    // compass/layers row just below it. ~150dp covers the strip and
    // its ~20dp breathing room.
    val topInsetPx = with(density) { (statusBarTopPx + 150.dp).toPx().toInt() }
    // Bottom chrome = navigation bar inset + bottom nav (~70dp).
    // Peek sheet height is added on top when peek is open.
    val bottomNavPx = with(density) { (navBarBottomPx + 70.dp).toPx().toInt() }

    // Green peek-highlight pin height (matches the 72×92 bitmap,
    // `ANCHOR_BOTTOM` anchor pins the tip at the station's lat so
    // the rest of the pin hangs UPWARD). Without slack below, the
    // southernmost pin's tip ends up exactly at the peek-sheet edge
    // and looks clipped.
    val peekPinSlackPx = with(density) { 40.dp.toPx().toInt() }

    // Re-fire the fit request whenever the user pages the carousel,
    // the peek opens/closes, or the sheet height changes. The nonce
    // makes repeated triggers distinct so the map's LaunchedEffect
    // actually re-runs.
    androidx.compose.runtime.LaunchedEffect(
        peek, homeCarouselPage, peekSheetHeightPx, decisionOptions
    ) {
        if (peek == MapPeek.Home && decisionOptions.isNotEmpty() && peekSheetHeightPx > 0) {
            val page = decisionOptions.chunked(3).getOrNull(homeCarouselPage).orEmpty()
            val stations = page.map { it.station }
            if (stations.isNotEmpty()) {
                fitStationsRequest = FitStationsRequest(
                    stations = stations,
                    insets = FitInsets(
                        leftPx = leftRailPx,
                        topPx = topInsetPx,
                        rightPx = rightRailPx,
                        bottomPx = peekSheetHeightPx + bottomNavPx + peekPinSlackPx,
                    ),
                    nonce = System.nanoTime(),
                )
            }
        }
    }

    // System-back progressively unwinds sheet state before falling
    // through to the NavHost pop. The LAST-registered enabled
    // BackHandler takes priority, so the fullscreen-first order below
    // gives: fullscreen → compressed peek → closed → (nav pop to
    // Import). Compose disables the callbacks when the gating state
    // doesn't hold, so each one can ignore the others.
    BackHandler(enabled = showLayers) { showLayers = false }
    BackHandler(enabled = peek != MapPeek.None && !fullscreenTimeline) {
        peek = MapPeek.None
    }
    BackHandler(enabled = fullscreenTimeline) { fullscreenTimeline = false }
    BackHandler(enabled = selectedStationInfo != null || isLoadingStationInfo) {
        onDismissStationInfo()
    }

    // Current viewport (latS, latN, lonW, lonE) — null until map reports it.
    var viewportBounds by remember {
        mutableStateOf<Quadruple<Double, Double, Double, Double>?>(null)
    }
    var viewportPois by remember { mutableStateOf<List<dev.gpxit.app.domain.Poi>>(emptyList()) }
    val lastFetchKey = remember { mutableStateOf<String?>(null) }

    val enabledTypes = remember(poiGrocery, poiWater, poiToilet, poiBikeRepair) {
        buildSet {
            if (poiGrocery) {
                add(dev.gpxit.app.domain.PoiType.GROCERY)
                add(dev.gpxit.app.domain.PoiType.BAKERY)
            }
            if (poiWater) add(dev.gpxit.app.domain.PoiType.WATER)
            if (poiToilet) add(dev.gpxit.app.domain.PoiType.TOILET)
            if (poiBikeRepair) add(dev.gpxit.app.domain.PoiType.BIKE_REPAIR)
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        viewportBounds, zoomLevel, enabledTypes, routePois.isEmpty()
    ) {
        if (routePois.isNotEmpty()) {
            viewportPois = emptyList()
            return@LaunchedEffect
        }
        val bb = viewportBounds
        if (enabledTypes.isEmpty() || bb == null || zoomLevel < 13.0) {
            viewportPois = emptyList()
            return@LaunchedEffect
        }
        val q = 0.01
        val qs = (bb.first / q).toInt()
        val qn = (bb.second / q).toInt()
        val qw = (bb.third / q).toInt()
        val qe = (bb.fourth / q).toInt()
        val key = "$qs/$qn/$qw/$qe/${enabledTypes.joinToString(",")}"
        if (key == lastFetchKey.value) return@LaunchedEffect
        lastFetchKey.value = key
        kotlinx.coroutines.delay(100)
        if (key != lastFetchKey.value) return@LaunchedEffect
        viewportPois = poiDatabase.queryByBbox(
            enabledTypes, bb.first, bb.second, bb.third, bb.fourth
        )
    }

    val pois: List<dev.gpxit.app.domain.Poi> = remember(
        routePois, viewportPois, viewportBounds, enabledTypes
    ) {
        if (enabledTypes.isEmpty()) return@remember emptyList()
        val source = if (routePois.isNotEmpty()) routePois else viewportPois
        val bb = viewportBounds
        source.filter { p ->
            p.type in enabledTypes &&
                (bb == null || (p.lat in bb.first..bb.second && p.lon in bb.third..bb.fourth))
        }
    }

    androidx.compose.runtime.SideEffect {
        if (initialMapCommand != MapCommand.NONE) {
            mapCommand = initialMapCommand
            onMapCommandConsumed()
        }
    }

    // Pre-compute Climb once per route.
    val climbDescent = remember(routeInfo?.points) {
        routeInfo?.points?.let { routeClimbDescentMeters(it) } ?: (0 to 0)
    }

    // System-bar icon tint is handled centrally in GpxitApp (keyed to
    // the current NavHost route). No per-screen override needed.

    val palette = rememberMapPalette()
    CompositionLocalProvider(LocalMapPalette provides palette) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(palette.sheetBg)
        ) {
            // Chrome insets for the Fullscreen-fit command — keep the
            // route out from under any of the overlaid controls. The
            // top insets cover the stats strip + compass/layers row,
            // the bottom covers the nav bar + system inset + an open
            // peek sheet, and the left/right cover the compass FAB
            // and the right-rail zoom cluster respectively.
            val fullscreenFitInsets = FitInsets(
                leftPx = leftRailPx,
                topPx = topInsetPx,
                rightPx = rightRailPx,
                bottomPx = bottomNavPx + (if (peek != MapPeek.None) peekSheetHeightPx else 0),
            )

            // Ids → 1-based slot index in the FULL Take-me-home list
            // (so page 2 shows 4/5/6 and the matching map pins carry
            // the same digit). The map paints a green pin with that
            // digit so the user can match each marker to its card.
            val peekHighlightedIds: Map<String, Int> = remember(
                peek, homeCarouselPage, decisionOptions
            ) {
                if (peek != MapPeek.Home) emptyMap()
                else {
                    val offset = homeCarouselPage * 3
                    decisionOptions.chunked(3)
                        .getOrNull(homeCarouselPage).orEmpty()
                        .withIndex()
                        .associate { (i, opt) -> opt.station.id to (offset + i + 1) }
                }
            }

            OsmMapView(
                routeInfo = routeInfo,
                userLocation = userLocation,
                userAccuracyMeters = userAccuracyMeters,
                homeStationLocation = homeStationLocation,
                fitStationsRequest = fitStationsRequest,
                onFitStationsConsumed = { fitStationsRequest = null },
                fitRouteInsets = fullscreenFitInsets,
                peekHighlightedStationIds = peekHighlightedIds,
                highlightedStation = highlightedStation,
                destinationStation = userDestinationStation,
                navigationActive = navigationActive,
                navigationLastMile = navigationLastMile,
                nearbyStations = nearbyStations,
                previewPosition = previewPosition,
                stationLabels = stationLabels,
                pois = pois,
                showStations = showStations,
                mapCommand = mapCommand,
                onMapCommandHandled = { mapCommand = MapCommand.NONE },
                zoomToStation = zoomToStation,
                onZoomToStationConsumed = onZoomToStationConsumed,
                onStationClick = onStationClick,
                onMapRotationChanged = { mapRotation = it },
                onZoomLevelChanged = { zoomLevel = it },
                onMetersPerPixelChanged = { metersPerPixel = it },
                onViewportChanged = { n, s, e, w ->
                    viewportBounds = Quadruple(s, n, w, e)
                    val pts = routeInfo?.points
                    if (pts == null) {
                        visibleStartDistance = null
                        visibleEndDistance = null
                    } else {
                        val east = if (e < w) e + 360.0 else e
                        var first: Double? = null
                        var last: Double? = null
                        for (p in pts) {
                            val lon = if (p.lon < w) p.lon + 360.0 else p.lon
                            if (p.lat in s..n && lon in w..east) {
                                if (first == null) first = p.distanceFromStart
                                last = p.distanceFromStart
                            }
                        }
                        val f = first
                        val l = last
                        if (f != null && l != null && l - f > 10.0) {
                            visibleStartDistance = f
                            visibleEndDistance = l
                        } else {
                            visibleStartDistance = null
                            visibleEndDistance = null
                        }
                    }
                },
                onGetMapCenter = { center, radius ->
                    pendingMapCenterCallback?.invoke(center, radius)
                    pendingMapCenterCallback = null
                },
                onMapViewportSnapshot = onMapViewportSnapshot,
                initialMapCenter = initialMapCenter,
                initialMapZoom = initialMapZoom,
                locateMode = locateMode,
                onLocateModeChanged = { locateMode = it },
                deviceHeading = deviceHeading,
                modifier = Modifier.fillMaxSize()
            )

            // ── Top area — stats strip + control clusters ─────────
            val statusBars = WindowInsets.statusBars.asPaddingValues()
            val topPadding = statusBars.calculateTopPadding()

            StatsStrip(
                distanceKm = formatDistanceKm(routeInfo, tripSnapshot),
                etaClock = formatEta(routeInfo, tripSnapshot, avgSpeedKmh),
                climbMeters = climbDescent.first.toString(),
                speedKmh = if (tripTrackingActive) {
                    "%.0f".format(avgSpeedKmh)
                } else {
                    "%.0f".format(avgSpeedKmh)
                },
                speedIsLive = tripTrackingActive,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPadding + 8.dp)
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth(),
            )

            // Compass — top-left, below stats strip.
            CompassButton(
                onClick = {
                    // If we're locked to the device heading, reset
                    // demotes to plain position-following so the
                    // heading effect doesn't immediately re-rotate
                    // the map after the user asks for north-up.
                    if (locateMode == LocateMode.Compass) {
                        locateMode = LocateMode.Following
                    }
                    mapCommand = MapCommand.RESET_ROTATION
                },
                mapRotation = mapRotation,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 14.dp, top = topPadding + 92.dp)
            )

            // Layers + Fullscreen pill — top-right, below stats strip.
            // Pill stays pinned; the popover is rendered as a sibling
            // below so opening it doesn't resize this anchor and push
            // the buttons around.
            VerticalPill(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 14.dp, top = topPadding + 92.dp)
            ) {
                PillButton(onClick = { showLayers = !showLayers }) {
                    GlassIcon(DesignIcons.Layers, size = 20.dp)
                }
                PillDivider()
                PillButton(onClick = { mapCommand = MapCommand.ZOOM_TO_ROUTE }) {
                    GlassIcon(DesignIcons.Fullscreen, size = 20.dp)
                }
            }

            if (showLayers) {
                // Transparent full-screen scrim under the sheet so
                // tapping anywhere outside the popover closes it. No
                // ripple / indication so it doesn't flash; the map
                // stays visible because the scrim has no background.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { showLayers = false }
                )
                // Popover sits to the LEFT of the pill, top-aligned
                // with it. Pill is 44dp wide + 14dp right edge pad +
                // 8dp gap = 66dp total end padding for the sheet.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 14.dp + 44.dp + 8.dp, top = topPadding + 92.dp)
                        .width(232.dp)
                ) {
                    LayersSheet(
                        grocery = poiGrocery,
                        water = poiWater,
                        toilet = poiToilet,
                        bikeRepair = poiBikeRepair,
                        showStations = showStations,
                        onSetGrocery = onSetPoiGrocery,
                        onSetWater = onSetPoiWater,
                        onSetToilet = onSetPoiToilet,
                        onSetBikeRepair = onSetPoiBikeRepair,
                        onSetShowStations = { showStations = it },
                        onDismiss = { showLayers = false },
                    )
                }
            }

            // ── Right rail (middle) — zoom pill + Nearby square ───
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                VerticalPill {
                    PillButton(onClick = { mapCommand = MapCommand.ZOOM_IN }) {
                        GlassIcon(DesignIcons.Plus, size = 20.dp)
                    }
                    PillDivider()
                    PillButton(onClick = { mapCommand = MapCommand.ZOOM_OUT }) {
                        GlassIcon(DesignIcons.Minus, size = 20.dp)
                    }
                }
                SquareGlassButton(
                    onClick = {
                        pendingMapCenterCallback = { c, r -> onSearchNearby(c, r) }
                        mapCommand = MapCommand.GET_MAP_CENTER
                    },
                ) {
                    if (isSearchingNearby) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        GlassIcon(DesignIcons.Search, size = 20.dp)
                    }
                }
                if (nearbyStations.isNotEmpty()) {
                    SquareGlassButton(
                        onClick = onClearNearbyStations,
                    ) {
                        Text("\u2715", color = palette.ink)
                    }
                }
            }

            // Scale legend — Compose-drawn horizontal bar in the
            // bottom-left corner, just above the bottom nav + any
            // system navigation-bar inset. Purely Compose-positioned
            // so no empirical bar-height guessing.
            val bottomNavDp = with(density) { bottomNavPx.toDp() }
            ScaleLegend(
                metersPerPixel = metersPerPixel,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 12.dp + bottomNavDp),
            )

            // ── No-route placeholder ──────────────────────────────
            if (routeInfo == null) {
                Text(
                    text = "No route loaded",
                    modifier = Modifier.align(Alignment.Center),
                    color = palette.inkSoft,
                )
            }

            // ── Bottom stack: locate FAB, then peek sheet, then nav
            // All anchored at BottomCenter so the locate button sits
            // above whatever's currently at the bottom (peek or nav)
            // rather than hiding behind it at fixed pixel offsets.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    LocateButton(
                        mode = locateMode,
                        onClick = {
                            // Cycle Off → Following → Compass → Off.
                            // OsmMapView observes the new mode and
                            // takes care of recentring + (in Compass)
                            // rotating the map to the device heading.
                            locateMode = when (locateMode) {
                                LocateMode.Off -> LocateMode.Following
                                LocateMode.Following -> LocateMode.Compass
                                LocateMode.Compass -> LocateMode.Off
                            }
                        },
                        modifier = Modifier.padding(end = 14.dp, bottom = 12.dp)
                    )
                }
                when (peek) {
                    MapPeek.Home -> TakeMeHomeSheet(
                        options = decisionOptions,
                        isLoading = isDecisionLoading,
                        onExpand = { fullscreenTimeline = true },
                        onDragUp = { fullscreenTimeline = true },
                        onDragDown = { peek = MapPeek.None },
                        onStationSelected = { option ->
                            peek = MapPeek.None
                            fullscreenTimeline = false
                            onOpenStationDetail(option)
                        },
                        onCurrentPageChanged = { homeCarouselPage = it },
                        onSheetHeightChanged = { peekSheetHeightPx = it },
                    )
                    MapPeek.Elevation -> routeInfo?.let {
                        if (it.points.any { p -> p.elevation != null }) {
                            ElevationSheet(
                                routeInfo = it,
                                startDistance = visibleStartDistance,
                                endDistance = visibleEndDistance,
                                onCursorPositionChanged = { gp -> previewPosition = gp },
                                onDragUp = { /* no fullscreen elevation variant */ },
                                onDragDown = { peek = MapPeek.None },
                            )
                        }
                    }
                    MapPeek.None -> {}
                }

                // Station detail peek — sits between any nav-button
                // peek sheet and the bottom nav so its dismiss
                // chevron lines up with the other peek interactions.
                if (selectedStationInfo != null || isLoadingStationInfo) {
                    val isThisDestination =
                        selectedStationInfo?.station?.id == userDestinationStation?.id &&
                        selectedStationInfo != null
                    StationDetailSheet(
                        option = selectedStationInfo,
                        isLoading = isLoadingStationInfo,
                        isThisDestination = isThisDestination,
                        navigationActive = navigationActive,
                        userLat = userLocation?.latitude,
                        userLon = userLocation?.longitude,
                        avgSpeedKmh = avgSpeedKmh,
                        onClose = onDismissStationInfo,
                        onSetDestination = {
                            selectedStationInfo?.let { onSetDestination(it) }
                        },
                        onClearDestination = { onSetDestination(null) },
                        onToggleNavigation = onToggleNavigation,
                        onLoadMoreConnections = onLoadMoreConnections,
                    )
                }

                MapBottomNav(
                    entries = listOf(
                        MapNavEntry(
                            item = MapNavItem.TakeMeHome,
                            icon = DesignIcons.Home,
                            label = "Take me home",
                            onClick = {
                                val wasOpen = peek == MapPeek.Home
                                peek = if (wasOpen) MapPeek.None else MapPeek.Home
                                if (!wasOpen) onTakeMeHome()
                            },
                        ),
                        MapNavEntry(
                            item = MapNavItem.Track,
                            icon = null,
                            label = if (tripTrackingActive) "Stop" else "Track",
                            active = tripTrackingActive,
                            onClick = {
                                if (tripTrackingActive) onStopTripTracking()
                                else onStartTripTracking()
                            },
                        ),
                        MapNavEntry(
                            item = MapNavItem.Elevation,
                            icon = DesignIcons.Mountain,
                            label = "Elevation",
                            onClick = {
                                peek = if (peek == MapPeek.Elevation) MapPeek.None else MapPeek.Elevation
                            },
                        ),
                        MapNavEntry(
                            item = MapNavItem.More,
                            icon = DesignIcons.Menu,
                            label = "More",
                            onClick = onNavigateToSettings,
                        ),
                    )
                )
            }

            // ── Fullscreen timeline (above everything else) ───────
            if (fullscreenTimeline) {
                TakeMeHomeFullTimeline(
                    options = decisionOptions,
                    homeStationName = homeStationName,
                    onClose = { fullscreenTimeline = false },
                    onStationSelected = { option ->
                        fullscreenTimeline = false
                        peek = MapPeek.None
                        onOpenStationDetail(option)
                    },
                )
            }

            // ── Map download progress indicator ───────────────────
            if (downloadState.active) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topPadding + 72.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = downloadState.label,
                        color = palette.inkSoft,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }

}

/**
 * Distance to destination — live remaining if tracking, else the
 * route's total. One decimal kilometer ("12.4").
 */
private fun formatDistanceKm(
    routeInfo: dev.gpxit.app.domain.RouteInfo?,
    snapshot: dev.gpxit.app.data.tracking.TripSnapshot?,
): String {
    val meters = snapshot?.remainingMeters ?: routeInfo?.totalDistanceMeters ?: return "—"
    return "%.1f".format(meters / 1000.0)
}

/**
 * ETA clock — current time plus remaining distance divided by average
 * cycling speed, formatted as "HH:mm". Returns "—" if no route.
 */
private fun formatEta(
    routeInfo: dev.gpxit.app.domain.RouteInfo?,
    snapshot: dev.gpxit.app.data.tracking.TripSnapshot?,
    avgSpeedKmh: Double,
): String {
    val meters = snapshot?.remainingMeters ?: routeInfo?.totalDistanceMeters ?: return "—"
    if (avgSpeedKmh <= 0) return "—"
    val hours = (meters / 1000.0) / avgSpeedKmh
    val etaMinutes = (hours * 60.0).toLong()
    val now = LocalTime.now(ZoneId.systemDefault())
    return now.plusMinutes(etaMinutes).format(DateTimeFormatter.ofPattern("HH:mm"))
}

