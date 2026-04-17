package dev.gpxit.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.schildbach.pte.dto.Product
import dev.gpxit.app.data.gpx.findClosestPointIndex
import dev.gpxit.app.data.location.LocationService
import dev.gpxit.app.data.prefs.PrefsRepository
import dev.gpxit.app.data.transit.TransitRepository
import dev.gpxit.app.domain.ConnectionOption
import dev.gpxit.app.domain.StationCandidate
import dev.gpxit.app.ui.decision.DecisionScreen
import dev.gpxit.app.ui.decision.DecisionViewModel
import dev.gpxit.app.ui.import_route.ImportScreen
import dev.gpxit.app.ui.import_route.ImportViewModel
import dev.gpxit.app.ui.map.MapCommand
import dev.gpxit.app.ui.map.MapScreen
import dev.gpxit.app.ui.settings.SettingsScreen
import dev.gpxit.app.ui.settings.SettingsViewModel
import dev.gpxit.app.ui.theme.GpxitTheme
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.time.Instant
import java.util.EnumSet

private suspend fun triggerDownload(
    routeInfo: dev.gpxit.app.domain.RouteInfo?,
    downloader: dev.gpxit.app.data.MapTileDownloader,
    onState: (GpxitDownloadState) -> Unit
) {
    routeInfo?.let { route ->
        onState(GpxitDownloadState(active = true, label = "Starting..."))
        downloader.downloadRoute(route) { progress ->
            onState(when {
                progress.isComplete && !progress.failed -> GpxitDownloadState(progress = 1f, label = "Done! (${progress.downloadedTiles} tiles)", active = false)
                progress.isComplete && progress.failed -> GpxitDownloadState(progress = 1f, label = "Done (${progress.failedCount} failed)", active = false)
                progress.failed -> GpxitDownloadState(label = "Failed", active = false)
                progress.isDownloading && progress.totalTiles > 0 -> {
                    val pct = progress.downloadedTiles.toFloat() / progress.totalTiles
                    GpxitDownloadState(
                        progress = pct,
                        label = "${(pct * 100).toInt()}% (${progress.downloadedTiles}/${progress.totalTiles})",
                        active = true
                    )
                }
                else -> GpxitDownloadState(active = true, label = "...")
            })
        }
    }
}

data class GpxitDownloadState(val progress: Float = 0f, val label: String = "", val active: Boolean = false)

private fun buildConnectionProducts(productNames: Set<String>): Set<Product> {
    val products = EnumSet.noneOf(Product::class.java)
    for (name in productNames) {
        try { products.add(Product.valueOf(name)) } catch (_: Exception) { }
    }
    if (products.isEmpty()) {
        // Fallback: at least regional trains
        products.add(Product.REGIONAL_TRAIN)
        products.add(Product.SUBURBAN_TRAIN)
    }
    return products
}

@Composable
fun GpxitApp(
    importViewModel: ImportViewModel = viewModel(),
    decisionViewModel: DecisionViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationService = remember { LocationService(context) }
    val transitRepository = remember { TransitRepository() }
    val prefsRepository = remember { PrefsRepository(context) }
    val routeStorage = remember { dev.gpxit.app.data.RouteStorage(context) }
    val mapTileDownloader = remember { dev.gpxit.app.data.MapTileDownloader(context) }

    // Location permission
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // GPS location
    var userLocation by remember { mutableStateOf<GeoPoint?>(null) }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            locationService.locationUpdates(intervalMs = 5000).collect { location ->
                userLocation = GeoPoint(location.latitude, location.longitude)
            }
        }
    }

    // Shared map state
    var highlightedStation by remember { mutableStateOf<StationCandidate?>(null) }
    var pendingMapCommand by remember { mutableStateOf(MapCommand.NONE) }
    // Separate zoom-to-station trigger — bypasses the command system
    var zoomToStationTrigger by remember { mutableStateOf<StationCandidate?>(null) }

    // Station info bottom sheet state
    var selectedStationInfo by remember { mutableStateOf<ConnectionOption?>(null) }
    var isLoadingStationInfo by remember { mutableStateOf(false) }

    // Offline map download state
    var downloadState by remember { mutableStateOf(GpxitDownloadState()) }

    // Nearby search state — restored from disk, cleared on new route import
    var nearbyStations by remember { mutableStateOf(routeStorage.loadNearbyStations()) }
    var isSearchingNearby by remember { mutableStateOf(false) }

    // Clear nearby stations when a new route is imported
    val currentRoute by importViewModel.routeInfo.collectAsState()
    var lastRouteId by remember { mutableStateOf(currentRoute?.name) }
    LaunchedEffect(currentRoute) {
        val newId = currentRoute?.name
        if (newId != null && newId != lastRouteId && lastRouteId != null) {
            nearbyStations = emptyList()
        }
        lastRouteId = newId
    }

    // Resolve home station coords if missing
    val prefs by settingsViewModel.preferences.collectAsState()
    LaunchedEffect(prefs.homeStationId, prefs.homeStationLat) {
        val id = prefs.homeStationId
        val name = prefs.homeStationName
        if (id != null && name != null && prefs.homeStationLat == null) {
            val coords = transitRepository.resolveStationLocation(id, name)
            if (coords != null) {
                prefsRepository.setHomeStation(id, name, coords.first, coords.second)
            }
        }
    }

    GpxitTheme {
        val navController = rememberNavController()

        NavHost(navController = navController, startDestination = "import") {
            composable("import") {
                val importRouteInfo by importViewModel.routeInfo.collectAsState()
                ImportScreen(
                    viewModel = importViewModel,
                    homeStationName = prefs.homeStationName,
                    onNavigateToMap = { navController.navigate("map") },
                    onNavigateToSettings = { navController.navigate("settings") },
                    onDownloadOfflineMap = { scope.launch { triggerDownload(importRouteInfo, mapTileDownloader) { downloadState = it } } },
                    downloadState = downloadState
                )
            }
            composable("map") {
                val routeInfo by importViewModel.routeInfo.collectAsState()

                val homeLat = prefs.homeStationLat
                val homeLon = prefs.homeStationLon
                val homeStationLocation = if (homeLat != null && homeLon != null) {
                    GeoPoint(homeLat, homeLon)
                } else null

                MapScreen(
                    routeInfoFlow = importViewModel.routeInfo,
                    userLocation = userLocation,
                    homeStationLocation = homeStationLocation,
                    highlightedStation = highlightedStation,
                    nearbyStations = nearbyStations,
                    initialMapCommand = pendingMapCommand,
                    zoomToStation = zoomToStationTrigger,
                    onZoomToStationConsumed = { zoomToStationTrigger = null },
                    selectedStationInfo = selectedStationInfo,
                    isLoadingStationInfo = isLoadingStationInfo,
                    isSearchingNearby = isSearchingNearby,
                    onMapCommandConsumed = { pendingMapCommand = MapCommand.NONE },
                    onTakeMeHome = {
                        highlightedStation = null
                        selectedStationInfo = null
                        routeInfo?.let { route ->
                            val loc = userLocation
                            val lat = loc?.latitude ?: route.points.firstOrNull()?.lat ?: return@let
                            val lon = loc?.longitude ?: route.points.firstOrNull()?.lon ?: return@let
                            decisionViewModel.findConnectionsHome(route, lat, lon)
                        }
                        navController.navigate("decision")
                    },
                    onSearchNearby = { mapCenter, radiusMeters ->
                        scope.launch {
                            isSearchingNearby = true
                            try {
                                val newStations = transitRepository.findNearbyStations(
                                    lat = mapCenter.latitude,
                                    lon = mapCenter.longitude,
                                    maxDistanceMeters = radiusMeters,
                                    maxLocations = 30,
                                    requiredProducts = prefs.enabledProducts
                                )
                                // Merge with existing, deduplicate by ID
                                val merged = (nearbyStations + newStations)
                                    .distinctBy { it.id }
                                nearbyStations = merged
                                routeStorage.saveNearbyStations(merged)
                            } catch (_: Exception) {
                                // keep existing
                            } finally {
                                isSearchingNearby = false
                            }
                        }
                    },
                    onClearNearbyStations = {
                        nearbyStations = emptyList()
                        routeStorage.clearNearbyStations()
                    },
                    onNavigateToSettings = { navController.navigate("settings") },
                    onDownloadOfflineMap = { scope.launch { triggerDownload(routeInfo, mapTileDownloader) { downloadState = it } } },
                    downloadState = downloadState,
                    useDarkMap = prefs.useDarkMap,
                    onStationClick = { station ->
                        highlightedStation = station
                        // Query connections for this single station
                        scope.launch {
                            isLoadingStationInfo = true
                            selectedStationInfo = null
                            try {
                                val homeId = prefs.homeStationId
                                val homeName = prefs.homeStationName
                                if (homeId == null || homeName == null) {
                                    isLoadingStationInfo = false
                                    return@launch
                                }

                                // Compute cycling time from current position
                                val route = routeInfo
                                val loc = userLocation
                                val now = Instant.now()
                                val speedMs = prefs.avgSpeedKmh * 1000.0 / 3600.0

                                var cyclingTimeMinutes = 0
                                var arrivalAtStation = now

                                if (loc != null) {
                                    if (route != null && station.distanceAlongRouteMeters > 0) {
                                        val (closestIdx, _) = findClosestPointIndex(
                                            route.points, loc.latitude, loc.longitude
                                        )
                                        val currentDist = route.points[closestIdx].distanceFromStart
                                        cyclingTimeMinutes = if (prefs.elevationAwareTime) {
                                            dev.gpxit.app.data.gpx.CyclingTimeEstimator
                                                .estimateMinutesAlongRoute(
                                                    route.points, currentDist,
                                                    station.distanceAlongRouteMeters,
                                                    prefs.avgSpeedKmh
                                                )
                                        } else {
                                            val dist = (station.distanceAlongRouteMeters - currentDist).coerceAtLeast(0.0)
                                            (dist / speedMs / 60.0).toInt()
                                        }
                                    } else {
                                        // Nearby station (not on route): straight-line, flat speed
                                        val distToRide = dev.gpxit.app.data.gpx.haversineMeters(
                                            loc.latitude, loc.longitude, station.lat, station.lon
                                        )
                                        cyclingTimeMinutes = (distToRide / speedMs / 60.0).toInt()
                                    }
                                    arrivalAtStation = now.plusSeconds((cyclingTimeMinutes * 60).toLong())
                                }

                                val products = buildConnectionProducts(prefs.connectionProducts)
                                val rawConnections = try {
                                    transitRepository.queryConnections(
                                        fromStationId = station.id,
                                        fromStationName = station.name,
                                        toStationId = homeId,
                                        toStationName = homeName,
                                        departureTime = arrivalAtStation,
                                        products = products
                                    )
                                } catch (_: Exception) {
                                    emptyList()
                                }

                                // Apply wait time filters
                                val minDep = arrivalAtStation.plusSeconds((prefs.minWaitBufferMinutes * 60).toLong())
                                val connections = rawConnections.filter { conn ->
                                    val afterMin = conn.departureTime >= minDep
                                    val withinMax = if (prefs.maxWaitMinutes > 0) {
                                        java.time.Duration.between(arrivalAtStation, conn.departureTime).toMinutes() <= prefs.maxWaitMinutes
                                    } else true
                                    afterMin && withinMax
                                }

                                selectedStationInfo = ConnectionOption(
                                    station = station,
                                    cyclingTimeMinutes = cyclingTimeMinutes,
                                    estimatedArrivalAtStation = arrivalAtStation,
                                    connections = connections,
                                    bestArrivalHome = connections.minByOrNull { it.arrivalTime }?.arrivalTime
                                )
                            } catch (_: Exception) {
                                // ignore
                            } finally {
                                isLoadingStationInfo = false
                            }
                        }
                    },
                    onLoadMoreConnections = {
                        val info = selectedStationInfo ?: return@MapScreen
                        val lastConn = info.connections.lastOrNull() ?: return@MapScreen
                        val homeId = prefs.homeStationId ?: return@MapScreen
                        val homeName = prefs.homeStationName ?: return@MapScreen
                        scope.launch {
                            try {
                                val products = buildConnectionProducts(prefs.connectionProducts)
                                val moreConnections = transitRepository.queryConnections(
                                    fromStationId = info.station.id,
                                    fromStationName = info.station.name,
                                    toStationId = homeId,
                                    toStationName = homeName,
                                    departureTime = lastConn.departureTime.plusSeconds(60),
                                    products = products
                                )
                                val existingIds = info.connections.map { it.departureTime to it.line }.toSet()
                                val newConns = moreConnections.filter {
                                    (it.departureTime to it.line) !in existingIds
                                }
                                selectedStationInfo = info.copy(
                                    connections = info.connections + newConns
                                )
                            } catch (_: Exception) { }
                        }
                    },
                    onDismissStationInfo = {
                        selectedStationInfo = null
                        isLoadingStationInfo = false
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("decision") {
                val routeInfo by importViewModel.routeInfo.collectAsState()
                DecisionScreen(
                    viewModel = decisionViewModel,
                    onRefresh = {
                        routeInfo?.let { route ->
                            val loc = userLocation
                            val lat = loc?.latitude ?: route.points.firstOrNull()?.lat ?: return@let
                            val lon = loc?.longitude ?: route.points.firstOrNull()?.lon ?: return@let
                            decisionViewModel.findConnectionsHome(route, lat, lon)
                        }
                    },
                    onSearchNearby = {
                        val loc = userLocation
                        if (loc != null) {
                            decisionViewModel.searchNearby(loc.latitude, loc.longitude)
                        }
                    },
                    onStationClick = { station ->
                        highlightedStation = station
                        zoomToStationTrigger = station
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                    userLat = userLocation?.latitude,
                    userLon = userLocation?.longitude
                )
            }
            composable("settings") {
                val suggestions by settingsViewModel.stationSuggestions.collectAsState()
                SettingsScreen(
                    prefsFlow = settingsViewModel.preferences,
                    onSetHomeStation = { suggestion -> settingsViewModel.setHomeStation(suggestion) },
                    onSetSpeed = { settingsViewModel.setSpeed(it) },
                    onSetSearchRadius = { settingsViewModel.setSearchRadius(it) },
                    onQueryChanged = { settingsViewModel.onQueryChanged(it) },
                    onToggleProduct = { settingsViewModel.toggleProduct(it) },
                    onToggleConnectionProduct = { settingsViewModel.toggleConnectionProduct(it) },
                    onSetMinWaitBuffer = { settingsViewModel.setMinWaitBuffer(it) },
                    onSetMaxWaitMinutes = { settingsViewModel.setMaxWaitMinutes(it) },
                    onSetUseDarkMap = { settingsViewModel.setUseDarkMap(it) },
                    onSetElevationAwareTime = { settingsViewModel.setElevationAwareTime(it) },
                    stationSuggestions = suggestions,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
