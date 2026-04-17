package dev.gpxit.app.ui.import_route

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gpxit.app.data.RouteStorage
import dev.gpxit.app.data.gpx.GpxParser
import dev.gpxit.app.data.poi.PoiRepository
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
    private val poiRepository = PoiRepository()
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
                        val routeWithStations = route.copy(stations = stations)
                        _routeInfo.value = routeWithStations
                        _routePois.value = cachedPois
                        _uiState.value = ImportUiState(
                            routeName = route.name,
                            pointCount = route.points.size,
                            totalDistanceKm = route.totalDistanceMeters / 1000.0,
                            stationCount = stations.size,
                            poiCount = cachedPois.size,
                            stationDiscoveryStatus = "${stations.size} stations loaded"
                        )
                    }
                } catch (_: Exception) {
                    // Corrupted cache, ignore
                }
            }
        }
    }

    fun importGpx(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val context = getApplication<Application>()

                // Read raw bytes so we can save them to disk
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("Cannot open file")
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

                // Save GPX to internal storage and clear stale caches
                withContext(Dispatchers.IO) {
                    routeStorage.saveGpx(bytes)
                    routeStorage.clearNearbyStations()
                    routeStorage.clearPois()
                }
                _routePois.value = emptyList()

                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    routeName = route.name,
                    pointCount = route.points.size,
                    totalDistanceKm = route.totalDistanceMeters / 1000.0,
                    stationDiscoveryStatus = "Discovering stations along route..."
                )

                // Precompute stations along the route
                val prefs = prefsRepository.preferences.first()
                val stations = transitRepository.discoverStationsAlongRoute(
                    points = route.points,
                    samplingIntervalMeters = prefs.samplingIntervalMeters,
                    searchRadiusMeters = prefs.searchRadiusMeters,
                    requiredProducts = prefs.enabledProducts
                )

                // Save stations to disk
                withContext(Dispatchers.IO) {
                    routeStorage.saveStations(stations)
                }

                val routeWithStations = route.copy(stations = stations)
                _routeInfo.value = routeWithStations

                _uiState.value = _uiState.value.copy(
                    stationCount = stations.size,
                    stationDiscoveryStatus = "${stations.size} stations found — discovering POIs\u2026"
                )

                // Prefetch all POI types along the route so the map can serve
                // them offline and layer toggles become instant.
                val pois = try {
                    poiRepository.fetchPoisForRoute(
                        points = route.points,
                        types = setOf(
                            PoiType.GROCERY,
                            PoiType.BAKERY,
                            PoiType.WATER,
                            PoiType.TOILET
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
                    stationDiscoveryStatus =
                        "${stations.size} stations, ${pois.size} POIs ready"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Import failed: ${e.message}"
                )
            }
        }
    }
}

data class ImportUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val routeName: String? = null,
    val pointCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val stationCount: Int = 0,
    val poiCount: Int = 0,
    val stationDiscoveryStatus: String? = null
)
