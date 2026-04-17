package dev.gpxit.app.ui.decision

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.schildbach.pte.dto.Product
import dev.gpxit.app.data.gpx.CyclingTimeEstimator
import dev.gpxit.app.data.gpx.findClosestPointIndex
import dev.gpxit.app.data.prefs.PrefsRepository
import dev.gpxit.app.data.transit.TransitRepository
import dev.gpxit.app.domain.ConnectionOption
import dev.gpxit.app.domain.RouteInfo
import dev.gpxit.app.domain.StationCandidate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.EnumSet

class DecisionViewModel(application: Application) : AndroidViewModel(application) {

    private val transitRepository = TransitRepository()
    private val prefsRepository = PrefsRepository(application)

    private fun publishHomeRecommendation(option: ConnectionOption?) {
        if (option == null) {
            dev.gpxit.app.data.tracking.TripTrackingService
                .publishHomeRecommendation(null)
            return
        }
        val firstConn = option.connections.firstOrNull()
        dev.gpxit.app.data.tracking.TripTrackingService.publishHomeRecommendation(
            dev.gpxit.app.data.tracking.HomeRecommendation(
                stationName = option.station.name,
                cyclingTimeMinutes = option.cyclingTimeMinutes,
                departureTime = firstConn?.departureTime,
                arrivalHomeTime = option.bestArrivalHome,
                line = firstConn?.line,
            )
        )
    }

    private fun connectionProducts(productNames: Set<String>): Set<Product> {
        val products = EnumSet.noneOf(Product::class.java)
        for (name in productNames) {
            try { products.add(Product.valueOf(name)) } catch (_: Exception) { }
        }
        if (products.isEmpty()) {
            products.add(Product.REGIONAL_TRAIN)
            products.add(Product.SUBURBAN_TRAIN)
        }
        return products
    }

    private val _uiState = MutableStateFlow(DecisionUiState())
    val uiState: StateFlow<DecisionUiState> = _uiState

    /**
     * Find connections home from stations ahead of the current position.
     */
    fun findConnectionsHome(routeInfo: RouteInfo, currentLat: Double, currentLon: Double) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val prefs = prefsRepository.preferences.first()
                if (prefs.homeStationId == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Please set your home station in settings first"
                    )
                    return@launch
                }

                // Find where we are on the route
                val (closestIndex, _) = findClosestPointIndex(routeInfo.points, currentLat, currentLon)
                val currentDistanceAlongRoute = routeInfo.points[closestIndex].distanceFromStart

                // Filter stations ahead of current position
                val stationsAhead = routeInfo.stations.filter {
                    it.distanceAlongRouteMeters > currentDistanceAlongRoute
                }

                if (stationsAhead.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "No stations ahead on the route"
                    )
                    return@launch
                }

                val now = Instant.now()
                val products = connectionProducts(prefs.connectionProducts)

                // Limit to the user-configured number of stations ahead to
                // keep the parallel-fan-out bounded.
                val stationsToQuery = stationsAhead.take(prefs.maxStationsToCheck.coerceAtLeast(1))

                // Query connections for each station in parallel
                val options = stationsToQuery.map { station ->
                    async {
                        val distanceToRide = station.distanceAlongRouteMeters - currentDistanceAlongRoute
                        val cyclingTimeMinutes = if (prefs.elevationAwareTime) {
                            CyclingTimeEstimator.estimateMinutesAlongRoute(
                                routeInfo.points,
                                currentDistanceAlongRoute,
                                station.distanceAlongRouteMeters,
                                prefs.avgSpeedKmh
                            )
                        } else {
                            val speedMs = prefs.avgSpeedKmh * 1000.0 / 3600.0
                            (distanceToRide / speedMs / 60.0).toInt()
                        }
                        val arrivalAtStation = now.plusSeconds((cyclingTimeMinutes * 60).toLong())

                        val rawConnections = try {
                            transitRepository.queryConnections(
                                fromStationId = station.id,
                                fromStationName = station.name,
                                toStationId = prefs.homeStationId,
                                toStationName = prefs.homeStationName ?: "Home",
                                departureTime = arrivalAtStation,
                                products = products
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }

                        // Apply wait time filters
                        val minDeparture = arrivalAtStation.plusSeconds((prefs.minWaitBufferMinutes * 60).toLong())
                        val connections = rawConnections.filter { conn ->
                            val afterMinBuffer = conn.departureTime >= minDeparture
                            val withinMaxWait = if (prefs.maxWaitMinutes > 0) {
                                val waitMin = java.time.Duration.between(arrivalAtStation, conn.departureTime).toMinutes()
                                waitMin <= prefs.maxWaitMinutes
                            } else true
                            afterMinBuffer && withinMaxWait
                        }

                        ConnectionOption(
                            station = station,
                            cyclingTimeMinutes = cyclingTimeMinutes,
                            estimatedArrivalAtStation = arrivalAtStation,
                            connections = connections,
                            bestArrivalHome = connections.minByOrNull { it.arrivalTime }?.arrivalTime
                        )
                    }
                }.awaitAll()

                // Sort by route order (distance along route)
                val sorted = options.sortedBy { it.station.distanceAlongRouteMeters }

                // Mark the one with earliest arrival home as recommended
                val bestOption = sorted.filter { it.bestArrivalHome != null }
                    .minByOrNull { it.bestArrivalHome!! }
                bestOption?.isRecommended = true

                // Push the winner into the tracking notification — no-op
                // when the service isn't running.
                publishHomeRecommendation(bestOption)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    options = sorted
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to find connections: ${e.message}"
                )
            }
        }
    }

    /**
     * Search for stations near current position (off-route fallback).
     */
    fun searchNearby(currentLat: Double, currentLon: Double) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val prefs = prefsRepository.preferences.first()
                if (prefs.homeStationId == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Please set your home station in settings first"
                    )
                    return@launch
                }

                val stations = transitRepository.findNearbyStations(
                    currentLat, currentLon,
                    prefs.searchRadiusMeters,
                    requiredProducts = prefs.enabledProducts
                )

                val now = Instant.now()
                val products = connectionProducts(prefs.connectionProducts)
                val options = stations.map { station ->
                    async {
                        val connections = try {
                            transitRepository.queryConnections(
                                fromStationId = station.id,
                                fromStationName = station.name,
                                toStationId = prefs.homeStationId,
                                toStationName = prefs.homeStationName ?: "Home",
                                departureTime = now,
                                products = products
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }

                        ConnectionOption(
                            station = station,
                            cyclingTimeMinutes = 0,
                            estimatedArrivalAtStation = now,
                            connections = connections,
                            bestArrivalHome = connections.minByOrNull { it.arrivalTime }?.arrivalTime
                        )
                    }
                }.awaitAll()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    nearbyOptions = options.sortedBy { it.bestArrivalHome ?: Instant.MAX }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Search failed: ${e.message}"
                )
            }
        }
    }
}

data class DecisionUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val options: List<ConnectionOption> = emptyList(),
    val nearbyOptions: List<ConnectionOption> = emptyList()
)
