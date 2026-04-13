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
import androidx.compose.material3.CircularProgressIndicator
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
    onSearchNearby: (center: GeoPoint, radiusMeters: Int) -> Unit,
    onClearNearbyStations: () -> Unit,
    onStationClick: (StationCandidate) -> Unit,
    onLoadMoreConnections: (() -> Unit)?,
    onDismissStationInfo: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val routeInfo by routeInfoFlow.collectAsState()
    var mapCommand by remember { mutableStateOf(initialMapCommand) }
    var mapRotation by remember { mutableFloatStateOf(0f) }
    var zoomLevel by remember { mutableStateOf(13.0) }
    var pendingMapCenterCallback by remember { mutableStateOf<((GeoPoint, Int) -> Unit)?>(null) }

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
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(
                    onClick = { mapCommand = MapCommand.ZOOM_TO_LOCATION },
                    modifier = Modifier.size(48.dp)
                ) {
                    Canvas(modifier = Modifier.size(28.dp)) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r = size.width / 2f
                        val lineColor = Color.Black
                        val strokeW = 3f
                        val circleStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
                        // Outer circle
                        drawCircle(lineColor, radius = r * 0.5f, center = androidx.compose.ui.geometry.Offset(cx, cy), style = circleStroke)
                        // Center dot
                        drawCircle(lineColor, radius = 3f, center = androidx.compose.ui.geometry.Offset(cx, cy))
                        val gap = 3f
                        val inner = r * 0.5f
                        // Top
                        drawLine(lineColor, androidx.compose.ui.geometry.Offset(cx, 0f), androidx.compose.ui.geometry.Offset(cx, cy - inner - gap), strokeWidth = strokeW)
                        // Bottom
                        drawLine(lineColor, androidx.compose.ui.geometry.Offset(cx, cy + inner + gap), androidx.compose.ui.geometry.Offset(cx, size.height), strokeWidth = strokeW)
                        // Left
                        drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, cy), androidx.compose.ui.geometry.Offset(cx - inner - gap, cy), strokeWidth = strokeW)
                        // Right
                        drawLine(lineColor, androidx.compose.ui.geometry.Offset(cx + inner + gap, cy), androidx.compose.ui.geometry.Offset(size.width, cy), strokeWidth = strokeW)
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            OsmMapView(
                routeInfo = routeInfo,
                userLocation = userLocation,
                homeStationLocation = homeStationLocation,
                highlightedStation = highlightedStation,
                nearbyStations = nearbyStations,
                mapCommand = mapCommand,
                onMapCommandHandled = { mapCommand = MapCommand.NONE },
                zoomToStation = zoomToStation,
                onZoomToStationConsumed = onZoomToStationConsumed,
                onStationClick = onStationClick,
                onMapRotationChanged = { mapRotation = it },
                onZoomLevelChanged = { zoomLevel = it },
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
            }

            // Bottom-center buttons
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (nearbyStations.isNotEmpty()) {
                    FilledTonalIconButton(
                        onClick = onClearNearbyStations,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Text("\u2715")
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = onTakeMeHome,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text("Take me home", style = MaterialTheme.typography.labelLarge)
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        pendingMapCenterCallback = { center, radius -> onSearchNearby(center, radius) }
                        mapCommand = MapCommand.GET_MAP_CENTER
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    if (isSearchingNearby) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Searching...", style = MaterialTheme.typography.labelLarge)
                    } else {
                        Text("Search nearby", style = MaterialTheme.typography.labelLarge)
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

            if (routeInfo == null) {
                Text(
                    text = "No route loaded",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
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
