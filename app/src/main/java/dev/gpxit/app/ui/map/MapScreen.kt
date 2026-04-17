package dev.gpxit.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.dp
import dev.gpxit.app.domain.ConnectionOption
import dev.gpxit.app.domain.StationCandidate
import dev.gpxit.app.ui.components.StationCard
import kotlinx.coroutines.flow.StateFlow
import org.osmdroid.util.GeoPoint

/** Tiny 4-tuple for (latSouth, latNorth, lonWest, lonEast) viewport state. */
private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    routeInfoFlow: StateFlow<dev.gpxit.app.domain.RouteInfo?>,
    userLocation: GeoPoint?,
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
    onSetPoiGrocery: (Boolean) -> Unit = {},
    onSetPoiWater: (Boolean) -> Unit = {},
    onSetPoiToilet: (Boolean) -> Unit = {},
    onSearchNearby: (center: GeoPoint, radiusMeters: Int) -> Unit,
    onClearNearbyStations: () -> Unit,
    onStationClick: (StationCandidate) -> Unit,
    onLoadMoreConnections: (() -> Unit)?,
    onDismissStationInfo: () -> Unit,
    tripTrackingEnabled: Boolean = true,
    tripTrackingActive: Boolean = false,
    onStartTripTracking: () -> Unit = {},
    onStopTripTracking: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val routeInfo by routeInfoFlow.collectAsState()
    var mapCommand by remember { mutableStateOf(initialMapCommand) }
    var mapRotation by remember { mutableFloatStateOf(0f) }
    var zoomLevel by remember { mutableStateOf(13.0) }
    var pendingMapCenterCallback by remember { mutableStateOf<((GeoPoint, Int) -> Unit)?>(null) }
    var previewPosition by remember { mutableStateOf<GeoPoint?>(null) }
    var visibleStartDistance by remember { mutableStateOf<Double?>(null) }
    var visibleEndDistance by remember { mutableStateOf<Double?>(null) }

    // Current viewport (latS, latN, lonW, lonE) — null until map reports it.
    var viewportBounds by remember {
        mutableStateOf<Quadruple<Double, Double, Double, Double>?>(null)
    }
    // Viewport-scoped POIs read from the local SQLite dataset when the
    // user pans outside the prefetched route corridor or before any
    // import has happened.
    var viewportPois by remember { mutableStateOf<List<dev.gpxit.app.domain.Poi>>(emptyList()) }
    val lastFetchKey = remember { mutableStateOf<String?>(null) }

    // Types enabled right now.
    val enabledTypes = remember(poiGrocery, poiWater, poiToilet) {
        buildSet {
            if (poiGrocery) {
                add(dev.gpxit.app.domain.PoiType.GROCERY)
                add(dev.gpxit.app.domain.PoiType.BAKERY)
            }
            if (poiWater) add(dev.gpxit.app.domain.PoiType.WATER)
            if (poiToilet) add(dev.gpxit.app.domain.PoiType.TOILET)
        }
    }

    // Local-DB viewport query — runs whenever the route cache is empty
    // (no GPX imported yet, or the user panned off-corridor).
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
        // Small debounce so we don't thrash the DB on every pan frame.
        kotlinx.coroutines.delay(100)
        if (key != lastFetchKey.value) return@LaunchedEffect
        viewportPois = poiDatabase.queryByBbox(
            enabledTypes, bb.first, bb.second, bb.third, bb.fourth
        )
    }

    // What we actually render: prefetched route POIs filtered by viewport
    // when available; otherwise the DB-viewport query (already bbox-scoped).
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

    // Apply pending command from parent via SideEffect (after composition)
    androidx.compose.runtime.SideEffect {
        if (initialMapCommand != MapCommand.NONE) {
            mapCommand = initialMapCommand
            onMapCommandConsumed()
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(routeInfo?.name ?: "GPXIT") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("<") }
                },
                actions = {
                    // Trip-tracking toggle — only rendered when (a) the
                    // feature is allowed in Settings and (b) a route is
                    // loaded, since tracking without a route is meaningless.
                    if (tripTrackingEnabled && routeInfo != null) {
                        IconButton(
                            onClick = {
                                if (tripTrackingActive) onStopTripTracking()
                                else onStartTripTracking()
                            }
                        ) {
                            Canvas(modifier = Modifier.size(22.dp)) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val r = size.width / 2.4f
                                if (tripTrackingActive) {
                                    // Filled red dot = tracking ON.
                                    drawCircle(
                                        Color(0xFFD32F2F),
                                        radius = r,
                                        center = Offset(cx, cy)
                                    )
                                } else {
                                    // Hollow circle = tracking OFF.
                                    drawCircle(
                                        Color.DarkGray,
                                        radius = r,
                                        center = Offset(cx, cy),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                                    )
                                }
                            }
                        }
                    }
                    if (downloadState.active) {
                        Column(
                            modifier = Modifier.padding(end = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                progress = { downloadState.progress },
                                modifier = Modifier.width(60.dp)
                            )
                            Text(
                                text = downloadState.label,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else {
                        IconButton(onClick = onDownloadOfflineMap) {
                            Text("\u2913", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Canvas(modifier = Modifier.size(22.dp)) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val outerR = size.width / 2f - 1f
                            val innerR = outerR * 0.55f
                            val toothW = outerR * 0.35f
                            val col = Color.DarkGray
                            // Gear teeth (8 rectangles around circle)
                            for (i in 0 until 8) {
                                val angle = Math.toRadians((i * 45.0))
                                val cos = kotlin.math.cos(angle).toFloat()
                                val sin = kotlin.math.sin(angle).toFloat()
                                drawLine(col,
                                    start = Offset(cx + innerR * cos, cy + innerR * sin),
                                    end = Offset(cx + outerR * cos, cy + outerR * sin),
                                    strokeWidth = toothW
                                )
                            }
                            // Inner circle (body)
                            drawCircle(col, radius = innerR + 1f, center = Offset(cx, cy))
                            // Center hole
                            val bgCol = Color(0xFFE0E0E0)
                            drawCircle(bgCol, radius = innerR * 0.5f, center = Offset(cx, cy))
                        }
                    }
                }
            )
        },
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
        Box(modifier = Modifier.weight(1f)) {
            OsmMapView(
                routeInfo = routeInfo,
                userLocation = userLocation,
                homeStationLocation = homeStationLocation,
                highlightedStation = highlightedStation,
                nearbyStations = nearbyStations,
                previewPosition = previewPosition,
                stationLabels = stationLabels,
                pois = pois,
                mapCommand = mapCommand,
                // (pois is the local state declared above)
                onMapCommandHandled = { mapCommand = MapCommand.NONE },
                zoomToStation = zoomToStation,
                onZoomToStationConsumed = onZoomToStationConsumed,
                onStationClick = onStationClick,
                onMapRotationChanged = { mapRotation = it },
                onZoomLevelChanged = { zoomLevel = it },
                onViewportChanged = { n, s, e, w ->
                    viewportBounds = Quadruple(s, n, w, e)
                    val pts = routeInfo?.points
                    if (pts == null) {
                        visibleStartDistance = null
                        visibleEndDistance = null
                    } else {
                        // Handle antimeridian edge case safely
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
                modifier = Modifier.fillMaxSize()
            )

            // Map control buttons (top-right)
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Compass button — shows north, click to reset rotation
                FilledTonalIconButton(
                    onClick = { mapCommand = MapCommand.RESET_ROTATION },
                    modifier = Modifier.size(48.dp)
                ) {
                    // North arrow that rotates opposite to map rotation
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .rotate(-mapRotation),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(28.dp)) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f

                            // North triangle (red)
                            val northPath = Path().apply {
                                moveTo(cx, 0f)
                                lineTo(cx - 8f, cy)
                                lineTo(cx + 8f, cy)
                                close()
                            }
                            drawPath(northPath, Color.Red, style = Fill)

                            // South triangle (gray)
                            val southPath = Path().apply {
                                moveTo(cx, size.height)
                                lineTo(cx - 8f, cy)
                                lineTo(cx + 8f, cy)
                                close()
                            }
                            drawPath(southPath, Color.Gray, style = Fill)

                            // Center dot
                            drawCircle(Color.White, radius = 3f, center = Offset(cx, cy))
                        }
                    }
                }

                // Fit route button (fullscreen icon)
                FilledTonalIconButton(
                    onClick = { mapCommand = MapCommand.ZOOM_TO_ROUTE },
                    modifier = Modifier.size(48.dp)
                ) {
                    Text("\u26F6", style = MaterialTheme.typography.titleMedium) // ⛶ square four corners
                }

                // Layers menu (POI overlays)
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    FilledTonalIconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Text("\u2630", style = MaterialTheme.typography.titleMedium) // ☰ layers
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Grocery / bakery") },
                            leadingIcon = {
                                Checkbox(checked = poiGrocery, onCheckedChange = null)
                            },
                            onClick = { onSetPoiGrocery(!poiGrocery) }
                        )
                        DropdownMenuItem(
                            text = { Text("Drinking water") },
                            leadingIcon = {
                                Checkbox(checked = poiWater, onCheckedChange = null)
                            },
                            onClick = { onSetPoiWater(!poiWater) }
                        )
                        DropdownMenuItem(
                            text = { Text("Toilets") },
                            leadingIcon = {
                                Checkbox(checked = poiToilet, onCheckedChange = null)
                            },
                            onClick = { onSetPoiToilet(!poiToilet) }
                        )
                    }
                }
            }

            // Bottom-center buttons — compact Buttons instead of Extended FABs
            // so the row is narrow enough to share the bottom with the GPS
            // icon on the right.
            val compactPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Always reserve a fixed-size slot so the main buttons don't
                // shift when the clear (✕) button appears / disappears.
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    if (nearbyStations.isNotEmpty()) {
                        FilledTonalIconButton(
                            onClick = onClearNearbyStations,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("\u2715")
                        }
                    }
                }
                Button(
                    onClick = onTakeMeHome,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    contentPadding = compactPadding
                ) {
                    Text("Take me home", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = {
                        pendingMapCenterCallback = { center, radius -> onSearchNearby(center, radius) }
                        mapCommand = MapCommand.GET_MAP_CENTER
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    contentPadding = compactPadding
                ) {
                    if (isSearchingNearby) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Text("  Searching\u2026", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Text("Search nearby", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Zoom buttons (center-right)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalIconButton(
                    onClick = { mapCommand = MapCommand.ZOOM_IN },
                    modifier = Modifier.size(48.dp)
                ) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
                FilledTonalIconButton(
                    onClick = { mapCommand = MapCommand.ZOOM_OUT },
                    modifier = Modifier.size(48.dp)
                ) {
                    Text("\u2212", style = MaterialTheme.typography.titleLarge) // −
                }
            }

            // Debug: zoom level indicator (top-left)
            Text(
                text = "Z: %.1f".format(zoomLevel),
                style = MaterialTheme.typography.labelSmall,
                color = Color.DarkGray,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )

            // My location button — bottom-right corner. The centered action row
            // is compact enough (compact Buttons + small padding) to share the
            // bottom edge without overlapping on typical phone widths.
            FilledTonalIconButton(
                onClick = { mapCommand = MapCommand.ZOOM_TO_LOCATION },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp)
                    .size(48.dp)
            ) {
                Canvas(modifier = Modifier.size(28.dp)) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.width / 2f
                    val lineColor = Color.Black
                    val strokeW = 3f
                    val circleStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
                    drawCircle(lineColor, radius = r * 0.5f, center = Offset(cx, cy), style = circleStroke)
                    drawCircle(lineColor, radius = 3f, center = Offset(cx, cy))
                    val gap = 3f
                    val inner = r * 0.5f
                    drawLine(lineColor, Offset(cx, 0f), Offset(cx, cy - inner - gap), strokeWidth = strokeW)
                    drawLine(lineColor, Offset(cx, cy + inner + gap), Offset(cx, size.height), strokeWidth = strokeW)
                    drawLine(lineColor, Offset(0f, cy), Offset(cx - inner - gap, cy), strokeWidth = strokeW)
                    drawLine(lineColor, Offset(cx + inner + gap, cy), Offset(size.width, cy), strokeWidth = strokeW)
                }
            }

            if (routeInfo == null) {
                Text(
                    text = "No route loaded",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Elevation profile (drag to preview positions on the map)
        val route = routeInfo
        if (showElevationGraph && route != null && route.points.any { it.elevation != null }) {
            ElevationProfile(
                routeInfo = route,
                stations = route.stations,
                startDistance = visibleStartDistance,
                endDistance = visibleEndDistance,
                onCursorPositionChanged = { dist ->
                    previewPosition = dist?.let { d ->
                        dev.gpxit.app.data.gpx.routePointAtDistance(route.points, d)
                            ?.let { (lat, lon) -> GeoPoint(lat, lon) }
                    }
                }
            )
        }
        } // end Column
    }

    // Station info bottom sheet
    if (selectedStationInfo != null || isLoadingStationInfo) {
        ModalBottomSheet(
            onDismissRequest = onDismissStationInfo,
            sheetState = sheetState
        ) {
            if (isLoadingStationInfo) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (selectedStationInfo != null) {
                val context = LocalContext.current
                Column(
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    StationCard(
                        option = selectedStationInfo,
                        isBest = false,
                        onLoadMore = onLoadMoreConnections,
                        userLat = userLocation?.latitude,
                        userLon = userLocation?.longitude
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            val station = selectedStationInfo.station
                            val label = android.net.Uri.encode(station.name)
                            val uri = android.net.Uri.parse(
                                "geo:${station.lat},${station.lon}?q=${station.lat},${station.lon}($label)"
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                            try {
                                context.startActivity(intent)
                            } catch (_: android.content.ActivityNotFoundException) {
                                // no map app installed
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text("Navigate by bike")
                    }
                }
            }
        }
    }
}
