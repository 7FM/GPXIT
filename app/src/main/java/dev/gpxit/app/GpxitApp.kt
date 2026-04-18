package dev.gpxit.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import dev.gpxit.app.ui.theme.StatusBarProtection
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
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

private fun ConnectionOption.toHomeRecommendation():
    dev.gpxit.app.data.tracking.HomeRecommendation {
    val firstConn = connections.firstOrNull()
    return dev.gpxit.app.data.tracking.HomeRecommendation(
        stationName = station.name,
        cyclingTimeMinutes = cyclingTimeMinutes,
        departureTime = firstConn?.departureTime,
        arrivalHomeTime = bestArrivalHome,
        line = firstConn?.line,
        stationDistanceAlongRouteMeters = station.distanceAlongRouteMeters,
    )
}

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
    val poiDatabase = remember { dev.gpxit.app.data.poi.PoiDatabase(context) }
    val poiDownloader = remember { dev.gpxit.app.data.poi.PoiDatasetDownloader(context) }
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
        val needed = buildList {
            if (!hasLocationPermission) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            // On API 33+ we need a runtime grant for foreground-service
            // notifications. Request it up front so the trip-tracking
            // notification actually renders on the first run.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
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

    // User-chosen destination override — wins over the auto-picked
    // recommendation coming out of DecisionViewModel. Set from the
    // station-info bottom sheet on the map, cleared when the user
    // imports a new route (so a stale choice can't leak across trips).
    var userDestination by remember { mutableStateOf<ConnectionOption?>(null) }

    // Last known map pan/zoom — survives the map composable being
    // torn down on navigation to decision/settings, so coming back
    // restores exactly what the user was looking at instead of
    // snapping to the route extent.
    var savedMapCenter by remember { mutableStateOf<GeoPoint?>(null) }
    var savedMapZoom by remember { mutableStateOf<Double?>(null) }

    // Navigation mode — draws a highlighted path from the user's
    // current position along the GPX to the destination station, with
    // a branch-off at the closest GPX point. Only meaningful when
    // userDestination is set, so clearing the destination also stops
    // navigation.
    var navigationActive by remember { mutableStateOf(false) }
    // Bike-aware last-mile geometry computed by BRouter. Null until
    // the user starts navigation AND BRouter has finished routing;
    // MapComposable just omits the branch while it's null.
    var navigationLastMile by remember { mutableStateOf<List<GeoPoint>?>(null) }
    val brouterClient = remember { dev.gpxit.app.data.routing.BRouterClient(context) }
    // Shown when the user hits "Start navigation" without the BRouter
    // app installed. We refuse to guess with a straight line because
    // it's misleading across buildings, rivers, motorways etc.
    var showBRouterInstallPrompt by remember { mutableStateOf(false) }
    // Live BRouter install state — re-checked on every resume so the
    // Import-screen setup card auto-hides when the user returns from
    // F-Droid / Play Store after installing.
    var brouterInstalled by remember { mutableStateOf(brouterClient.isInstalled()) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                brouterInstalled = brouterClient.isInstalled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Offline map download state
    var downloadState by remember { mutableStateOf(GpxitDownloadState()) }
    // POI dataset download state (shared with Settings for the manual button).
    var poiDbState by remember { mutableStateOf(GpxitDownloadState()) }
    // Trip-tracking service state — observed from the service's own flow.
    val tripTrackingState by dev.gpxit.app.data.tracking.TripTrackingService.state
        .collectAsState()

    // Drive the POI dataset download — fires when the DB is missing OR
    // when the last successful update was more than 30 days ago and the
    // user has auto-update enabled. Manual updates from Settings go
    // through the same lambda.
    val triggerPoiDbDownload: () -> Unit = {
        if (!poiDbState.active) {
            scope.launch {
                val ok = poiDownloader.download(poiDatabase) { p ->
                    poiDbState = GpxitDownloadState(
                        progress = p.fraction.coerceIn(0f, 1f),
                        label = p.label,
                        active = p.active
                    )
                }
                if (ok) {
                    prefsRepository.setPoiDbLastUpdate(System.currentTimeMillis())
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val p = prefsRepository.preferences.first()
        val now = System.currentTimeMillis()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        val stale = p.poiDbLastUpdateMs > 0 && now - p.poiDbLastUpdateMs > thirtyDaysMs
        val shouldDownload = !poiDatabase.isAvailable() ||
            (p.poiDbAutoUpdate && stale)
        if (shouldDownload) triggerPoiDbDownload()
    }

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
            userDestination = null
            navigationActive = false
        }
        lastRouteId = newId
    }

    // Clearing the destination pulls nav down with it — navigating to
    // an unset target would be meaningless.
    LaunchedEffect(userDestination) {
        if (userDestination == null) navigationActive = false
    }

    // Compute the bike-aware route whenever nav turns on or the
    // destination changes. We always route from the rider's current
    // position to the station — asking BRouter to start from a
    // pre-picked branch-off on the GPX could force a backtrack if
    // BRouter's preferred road to the station actually diverges from
    // the GPX earlier than the geographically-closest point. MapComposable
    // takes care of finding where BRouter's polyline leaves the GPX
    // corridor and displays GPX-follow up to that point + BRouter
    // after it, so the rider gets both "stick to the GPX" and
    // "no U-turns".
    //
    // Origin is fixed at nav-start; if the rider moves significantly
    // mid-ride the polyline goes slightly stale and they can toggle
    // nav off/on to refresh.
    LaunchedEffect(
        navigationActive,
        userDestination,
        currentRoute,
        // Only a null↔non-null flip re-triggers; we don't want a fresh
        // BRouter call on every 5-second GPS update.
        userLocation != null
    ) {
        if (!navigationActive) {
            navigationLastMile = null
            return@LaunchedEffect
        }
        currentRoute ?: run { navigationLastMile = null; return@LaunchedEffect }
        val dst = userDestination?.station ?: run {
            navigationLastMile = null; return@LaunchedEffect
        }
        if (!brouterClient.isInstalled()) {
            navigationLastMile = null
            return@LaunchedEffect
        }
        val loc = userLocation ?: run {
            navigationLastMile = null; return@LaunchedEffect
        }
        navigationLastMile = brouterClient.routeBike(
            start = GeoPoint(loc.latitude, loc.longitude),
            end = GeoPoint(dst.lat, dst.lon)
        )
    }

    // Publish whichever recommendation should drive the tracking
    // notification: the user's explicit override if set, otherwise
    // the best auto-picked option. Running in the UI composition so
    // DecisionViewModel doesn't have to know about the override.
    val decisionOptionsState by decisionViewModel.uiState.collectAsState()
    LaunchedEffect(userDestination, decisionOptionsState.options) {
        val effective = userDestination
            ?: decisionOptionsState.options.firstOrNull { it.isRecommended }
        val rec = effective?.toHomeRecommendation()
        dev.gpxit.app.data.tracking.TripTrackingService
            .publishHomeRecommendation(rec)
    }

    // Resolve home station coords if missing
    val prefs by settingsViewModel.preferences.collectAsState()

    // If the user flips the master tracking switch off while the service
    // is live, stop it immediately so we aren't holding GPS against
    // their preference.
    LaunchedEffect(prefs.tripTrackingEnabled, tripTrackingState.isActive) {
        if (!prefs.tripTrackingEnabled && tripTrackingState.isActive) {
            dev.gpxit.app.data.tracking.TripTrackingService.stop(context)
        }
    }

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

        Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "import") {
            composable("import") {
                val importRouteInfo by importViewModel.routeInfo.collectAsState()
                ImportScreen(
                    viewModel = importViewModel,
                    homeStationName = prefs.homeStationName,
                    onNavigateToMap = { navController.navigate("map") },
                    onNavigateToSettings = { navController.navigate("settings") },
                    onDownloadOfflineMap = { scope.launch { triggerDownload(importRouteInfo, mapTileDownloader) { downloadState = it } } },
                    downloadState = downloadState,
                    brouterInstalled = brouterInstalled,
                    onInstallBRouter = { showBRouterInstallPrompt = true }
                )
            }
            composable("map") {
                val routeInfo by importViewModel.routeInfo.collectAsState()
                val routePois by importViewModel.routePois.collectAsState()
                val decisionState by decisionViewModel.uiState.collectAsState()
                val stationLabels = remember(decisionState.options, userDestination) {
                    val m = decisionState.options.associate { opt ->
                        opt.station.id to dev.gpxit.app.domain.StationLabel(
                            arrivalAtStation = opt.estimatedArrivalAtStation,
                            nextTrainDeparture = opt.connections.firstOrNull()?.departureTime,
                            isRecommended = opt.isRecommended
                        )
                    }.toMutableMap()
                    // Destination's times come from whichever option the user
                    // pinned — covers the case where they picked a station the
                    // Take-me-home fan-out didn't include (e.g. via a direct
                    // map tap).
                    userDestination?.let { opt ->
                        m[opt.station.id] = dev.gpxit.app.domain.StationLabel(
                            arrivalAtStation = opt.estimatedArrivalAtStation,
                            nextTrainDeparture = opt.connections.firstOrNull()?.departureTime,
                            isRecommended = false
                        )
                    }
                    m
                }

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
                    showElevationGraph = prefs.showElevationGraph,
                    stationLabels = stationLabels,
                    routePois = routePois,
                    poiDatabase = poiDatabase,
                    poiGrocery = prefs.poiGrocery,
                    poiWater = prefs.poiWater,
                    poiToilet = prefs.poiToilet,
                    poiBikeRepair = prefs.poiBikeRepair,
                    onSetPoiGrocery = { settingsViewModel.setPoiGrocery(it) },
                    onSetPoiWater = { settingsViewModel.setPoiWater(it) },
                    onSetPoiToilet = { settingsViewModel.setPoiToilet(it) },
                    onSetPoiBikeRepair = { settingsViewModel.setPoiBikeRepair(it) },
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
                    tripTrackingEnabled = prefs.tripTrackingEnabled,
                    tripTrackingActive = tripTrackingState.isActive,
                    onStartTripTracking = {
                        dev.gpxit.app.data.tracking.TripTrackingService.start(context)
                    },
                    onStopTripTracking = {
                        dev.gpxit.app.data.tracking.TripTrackingService.stop(context)
                    },
                    userDestinationStation = userDestination?.station,
                    onSetDestination = { option -> userDestination = option },
                    navigationActive = navigationActive,
                    onToggleNavigation = {
                        if (navigationActive) {
                            navigationActive = false
                        } else if (brouterClient.isInstalled()) {
                            navigationActive = true
                        } else {
                            showBRouterInstallPrompt = true
                        }
                    },
                    navigationLastMile = navigationLastMile,
                    initialMapCenter = savedMapCenter,
                    initialMapZoom = savedMapZoom,
                    onMapViewportSnapshot = { c, z ->
                        savedMapCenter = c
                        savedMapZoom = z
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
                    onOptionClick = { option ->
                        // DecisionScreen already has full connection data —
                        // reuse it so the map's bottom sheet opens straight
                        // to the "set destination / navigate by bike" view
                        // without re-querying the backend.
                        highlightedStation = option.station
                        zoomToStationTrigger = option.station
                        selectedStationInfo = option
                        isLoadingStationInfo = false
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
                    onSetMaxStationsToCheck = { settingsViewModel.setMaxStationsToCheck(it) },
                    onSetShowElevationGraph = { settingsViewModel.setShowElevationGraph(it) },
                    onSetElevationAwareTime = { settingsViewModel.setElevationAwareTime(it) },
                    onSetPoiDbAutoUpdate = { settingsViewModel.setPoiDbAutoUpdate(it) },
                    onUpdatePoiDb = triggerPoiDbDownload,
                    poiDbDownloadState = poiDbState,
                    poiDbAvailable = poiDatabase.isAvailable(),
                    onSetTripTrackingEnabled = { settingsViewModel.setTripTrackingEnabled(it) },
                    stationSuggestions = suggestions,
                    onBack = { navController.popBackStack() }
                )
            }
        }

            // Draw status-bar scrim on top so system icons stay legible.
            // targetSdk 35 enforces edge-to-edge and ignores statusBarColor;
            // this is the canonical per-Compose fix.
            StatusBarProtection()
        }

        if (showBRouterInstallPrompt) {
            BRouterInstallDialog(
                onDismiss = { showBRouterInstallPrompt = false },
                onInstallFromFDroid = {
                    openUrl(context, "https://f-droid.org/packages/btools.routingapp/")
                    showBRouterInstallPrompt = false
                },
                onInstallFromPlayStore = {
                    // Try the market:// scheme first — drops users
                    // straight into the Play Store app if present;
                    // fall back to the web URL otherwise.
                    if (!openUrl(context, "market://details?id=btools.routingapp")) {
                        openUrl(
                            context,
                            "https://play.google.com/store/apps/details?id=btools.routingapp"
                        )
                    }
                    showBRouterInstallPrompt = false
                }
            )
        }
    }
}

@Composable
private fun BRouterInstallDialog(
    onDismiss: () -> Unit,
    onInstallFromFDroid: () -> Unit,
    onInstallFromPlayStore: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.material3.Text("BRouter required")
        },
        text = {
            androidx.compose.material3.Text(
                "Bike-aware offline routing is powered by the free " +
                    "BRouter app. Install it once, download the region " +
                    "data inside BRouter, and GPXIT will route the last " +
                    "mile to your station along real cycle infrastructure."
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onInstallFromFDroid) {
                androidx.compose.material3.Text("F-Droid")
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                androidx.compose.material3.TextButton(onClick = onInstallFromPlayStore) {
                    androidx.compose.material3.Text("Play Store")
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    androidx.compose.material3.Text("Cancel")
                }
            }
        }
    )
}

/** Tries to launch an ACTION_VIEW for [url]; returns true if an activity was started. */
private fun openUrl(context: android.content.Context, url: String): Boolean {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse(url)
    ).apply {
        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }
}
