package dev.gpxit.app.ui.import_route

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gpxit.app.data.RouteStorage
import dev.gpxit.app.data.gpx.GpxParser
import dev.gpxit.app.data.poi.PoiDatabase
import dev.gpxit.app.data.prefs.PrefsRepository
import dev.gpxit.app.data.transit.TransitRepository
import dev.gpxit.app.domain.Poi
import dev.gpxit.app.domain.PoiType
import dev.gpxit.app.domain.RouteInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportViewModel(application: Application) : AndroidViewModel(application) {

    private val transitRepository = TransitRepository()
    private val poiDatabase = PoiDatabase(application)
    private val prefsRepository = PrefsRepository(application)
    private val routeStorage = RouteStorage(application)

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState

    private val _routeInfo = MutableStateFlow<RouteInfo?>(null)
    val routeInfo: StateFlow<RouteInfo?> = _routeInfo

    /** Cached POIs along the currently loaded route (all types). */
    private val _routePois = MutableStateFlow<List<Poi>>(emptyList())
    val routePois: StateFlow<List<Poi>> = _routePois

    init {
        // Restore previously saved route on startup
        if (routeStorage.hasRoute()) {
            viewModelScope.launch {
                try {
                    val route = withContext(Dispatchers.IO) {
                        routeStorage.loadGpxStream().use { GpxParser.parse(it) }
                    }
                    if (route.points.isNotEmpty()) {
                        val stations = withContext(Dispatchers.IO) {
                            routeStorage.loadStations()
                        }
                        val cachedPois = withContext(Dispatchers.IO) {
                            routeStorage.loadPois()
                        }
                        val discoveryFailed = withContext(Dispatchers.IO) {
                            routeStorage.stationDiscoveryFailed()
                        }
                        val routeWithStations = route.copy(stations = stations)
                        _routeInfo.value = routeWithStations
                        _routePois.value = cachedPois
                        _uiState.value = ImportUiState(
                            routeName = route.name,
                            pointCount = route.points.size,
                            totalDistanceKm = route.totalDistanceMeters / 1000.0,
                            stationCount = stations.size,
                            poiCount = cachedPois.size,
                            stationDiscoveryFailed = discoveryFailed,
                            stationDiscoveryStatus = if (discoveryFailed) {
                                "Couldn't reach transit service"
                            } else {
                                "${stations.size} stations loaded"
                            }
                        )
                    }
                } catch (_: Exception) {
                    // Corrupted cache, ignore
                }
            }
        }
    }

    /**
     * Re-run the POI corridor query against the local DB for [route]
     * and update the in-memory cache + on-disk JSON. No-op when the
     * POI dataset isn't downloaded yet.
     */
    fun refreshPoisForRoute(route: RouteInfo) {
        viewModelScope.launch {
            if (!poiDatabase.isAvailable()) return@launch
            val pois = try {
                poiDatabase.queryForRoute(
                    points = route.points,
                    types = setOf(
                        PoiType.GROCERY,
                        PoiType.BAKERY,
                        PoiType.WATER,
                        PoiType.TOILET,
                        PoiType.BIKE_REPAIR
                    )
                )
            } catch (_: Exception) {
                return@launch
            }
            withContext(Dispatchers.IO) { routeStorage.savePois(pois) }
            _routePois.value = pois
            _uiState.value = _uiState.value.copy(poiCount = pois.size)
        }
    }

    /**
     * Wipe the currently-loaded route from disk and memory, plus any
     * derived caches (precomputed stations, nearby search results,
     * route POIs) and the live "take me home" recommendation tied to
     * it. The home screen falls back to its empty-state card; the
     * map screen draws nothing until the user imports a new GPX.
     */
    fun clearRoute() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { routeStorage.clear() }
            _routeInfo.value = null
            _routePois.value = emptyList()
            _uiState.value = ImportUiState()
            dev.gpxit.app.data.tracking.TripTrackingService
                .publishHomeRecommendation(null)
        }
    }

    fun importGpx(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val context = getApplication<Application>()

                // Sniff the first 1 KB before slurping the whole file
                // so a wrong-file pick (random binary, HTML page,
                // non-GPX XML) fails fast instead of being read in
                // full just to die at the XML parser. The manifest
                // filter is intentionally loose so .gpx files always
                // surface in the chooser, which is exactly when this
                // header check matters most.
                val bytes = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("Cannot open file")
                    stream.use { input ->
                        val buffered = input.buffered()
                        val headSize = 1024
                        buffered.mark(headSize + 1)
                        val head = ByteArray(headSize)
                        val read = buffered.read(head, 0, headSize).coerceAtLeast(0)
                        if (!GpxParser.looksLikeGpx(head, read)) {
                            throw NotAGpxFileException()
                        }
                        buffered.reset()
                        buffered.readBytes()
                    }
                }

                val route = withContext(Dispatchers.IO) {
                    bytes.inputStream().use { GpxParser.parse(it) }
                }

                if (route.points.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "No track points found in GPX file"
                    )
                    return@launch
                }

                // Save GPX to internal storage and clear stale caches.
                // Also clear the prior failure marker so a successful new
                // import doesn't inherit a stale "couldn't reach transit
                // service" banner from the previous route.
                withContext(Dispatchers.IO) {
                    routeStorage.saveGpx(bytes)
                    routeStorage.clearNearbyStations()
                    routeStorage.clearPois()
                    routeStorage.clearStationDiscoveryFailed()
                }
                _routePois.value = emptyList()
                // Old "take me home" recommendation is tied to the prior
                // route — drop it so the tracking notification doesn't
                // advertise a train the user won't be near.
                dev.gpxit.app.data.tracking.TripTrackingService
                    .publishHomeRecommendation(null)

                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    routeName = route.name,
                    pointCount = route.points.size,
                    totalDistanceKm = route.totalDistanceMeters / 1000.0,
                    stationDiscoveryFailed = false,
                    stationDiscoveryStatus = "Discovering stations along route..."
                )

                // Precompute stations along the route
                val prefs = prefsRepository.preferences.first()
                val discovery = transitRepository.discoverStationsAlongRoute(
                    points = route.points,
                    samplingIntervalMeters = prefs.samplingIntervalMeters,
                    searchRadiusMeters = prefs.searchRadiusMeters,
                    requiredProducts = prefs.enabledProducts
                )
                val stations = discovery.stations

                // Save stations to disk. On full network failure, mark the
                // sidecar so the home-screen banner reappears across restarts.
                withContext(Dispatchers.IO) {
                    routeStorage.saveStations(stations)
                    if (discovery.networkFailed) {
                        routeStorage.markStationDiscoveryFailed()
                    } else {
                        routeStorage.clearStationDiscoveryFailed()
                    }
                }

                val routeWithStations = route.copy(stations = stations)
                _routeInfo.value = routeWithStations

                val dbAvailable = poiDatabase.isAvailable()
                _uiState.value = _uiState.value.copy(
                    stationCount = stations.size,
                    stationDiscoveryFailed = discovery.networkFailed,
                    stationDiscoveryStatus = if (discovery.networkFailed) {
                        "Couldn't reach transit service"
                    } else if (dbAvailable) {
                        "${stations.size} stations found — extracting POIs\u2026"
                    } else {
                        "${stations.size} stations found"
                    }
                )

                // Pull POIs along the route from the local SQLite dataset.
                // No network — if the DB hasn't been downloaded yet this is a
                // no-op and the map layers stay empty until the user grabs
                // the dataset from Settings.
                val pois = try {
                    poiDatabase.queryForRoute(
                        points = route.points,
                        types = setOf(
                            PoiType.GROCERY,
                            PoiType.BAKERY,
                            PoiType.WATER,
                            PoiType.TOILET,
                            PoiType.BIKE_REPAIR
                        )
                    )
                } catch (_: Exception) {
                    emptyList()
                }
                withContext(Dispatchers.IO) {
                    routeStorage.savePois(pois)
                }
                _routePois.value = pois

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    poiCount = pois.size,
                    stationDiscoveryStatus = when {
                        discovery.networkFailed ->
                            "Couldn't reach transit service"
                        !dbAvailable ->
                            "${stations.size} stations — POI dataset not downloaded"
                        pois.isEmpty() ->
                            "${stations.size} stations, no POIs along route"
                        else ->
                            "${stations.size} stations, ${pois.size} POIs ready"
                    }
                )
            } catch (_: NotAGpxFileException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "That file doesn't look like a GPX route. Pick a .gpx file."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Import failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Re-run station discovery against the currently-loaded route. Used by
     * the offline-import retry banner *and* the "Reload stations" menu item
     * (so even a successful previous discovery can be force-refreshed). On
     * a fresh failure we keep the previously-discovered station list — the
     * user shouldn't lose good data because they tapped reload while
     * offline.
     */
    fun reloadStations() {
        val route = _routeInfo.value ?: return
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                stationDiscoveryStatus = "Retrying station discovery…",
            )
            val prefs = prefsRepository.preferences.first()
            val discovery = transitRepository.discoverStationsAlongRoute(
                points = route.points,
                samplingIntervalMeters = prefs.samplingIntervalMeters,
                searchRadiusMeters = prefs.searchRadiusMeters,
                requiredProducts = prefs.enabledProducts,
            )
            if (discovery.networkFailed) {
                withContext(Dispatchers.IO) {
                    routeStorage.markStationDiscoveryFailed()
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    stationDiscoveryFailed = true,
                    stationDiscoveryStatus = "Couldn't reach transit service",
                )
            } else {
                val stations = discovery.stations
                withContext(Dispatchers.IO) {
                    routeStorage.saveStations(stations)
                    routeStorage.clearStationDiscoveryFailed()
                }
                _routeInfo.value = route.copy(stations = stations)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    stationCount = stations.size,
                    stationDiscoveryFailed = false,
                    stationDiscoveryStatus = "${stations.size} stations found",
                )
            }
        }
    }

    /** Thrown by the head sniff in [importGpx] when the file lacks a `<gpx` tag. */
    private class NotAGpxFileException : RuntimeException()
}

data class ImportUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val routeName: String? = null,
    val pointCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val stationCount: Int = 0,
    val poiCount: Int = 0,
    val stationDiscoveryFailed: Boolean = false,
    val stationDiscoveryStatus: String? = null
)
