package dev.gpxit.app.data.transit

import android.content.Context
import android.util.Log
import dev.gpxit.app.data.gpx.haversineMeters
import dev.gpxit.app.data.prefs.PrefsRepository
import dev.gpxit.app.domain.HomeStation
import dev.gpxit.app.domain.RoutePoint
import dev.gpxit.app.domain.StationCandidate
import dev.gpxit.app.domain.TrainConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point for all transit queries. Picks the backends to ask from
 * [TransitBackendRegistry], queries them in parallel and merges the results.
 */
class TransitRepository(context: Context) {

    private val registry = TransitBackendRegistry.get(context)
    private val prefsRepository = PrefsRepository(context.applicationContext)

    private suspend fun enabledBackends(): (String) -> Boolean {
        val transitousEnabled = prefsRepository.preferences.first().transitousEnabled
        return { id -> id != TransitBackendRegistry.TRANSITOUS || transitousEnabled }
    }

    /**
     * Stations near a point from every backend covering it, merged. Throws if
     * every backend failed.
     */
    suspend fun findNearbyStations(
        lat: Double,
        lon: Double,
        maxDistanceMeters: Int = 2000,
        maxLocations: Int = 10,
        requiredProducts: Set<String>? = null
    ): List<StationCandidate> = withContext(Dispatchers.IO) {
        val backendIds = registry.stationBackendIds(lat, lon, enabledBackends())
        val results = queryStations(backendIds, lat, lon, maxDistanceMeters, maxLocations)
        val stations = results.mapNotNull { it.getOrNull() }
        if (stations.isEmpty() && results.isNotEmpty()) {
            throw results.first().exceptionOrNull() ?: IOException("no transit backend answered")
        }
        // Collapse platform-child siblings and cross-backend copies so the map stays readable.
        clusterStations(stations.flatten().filterProducts(requiredProducts), priority = registry::priority)
    }

    private suspend fun queryStations(
        backendIds: List<String>,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        maxResults: Int,
    ): List<Result<List<StationCandidate>>> = coroutineScope {
        backendIds.mapNotNull { registry.byId(it) }.map { backend ->
            async {
                runCatching { backend.findNearbyStations(lat, lon, radiusMeters, maxResults) }
                    .onFailure {
                        Log.w(TAG, "Station query failed at $lat,$lon (${backend.id})", it)
                    }
            }
        }.awaitAll()
    }

    private fun List<StationCandidate>.filterProducts(requiredProducts: Set<String>?) =
        // Filter: station must offer at least one of the required products
        if (requiredProducts == null) this
        else filter { it.products.intersect(requiredProducts).isNotEmpty() }

    /**
     * Trips from [from] to [home], from every backend that can plan them,
     * merged and without duplicates. Throws if every backend asked failed.
     */
    suspend fun queryConnections(
        from: StationCandidate,
        home: HomeStation,
        departureTime: Instant,
        modes: Set<TransitMode> = TransitMode.TRAINS,
    ): List<TrainConnection> = withContext(Dispatchers.IO) {
        val enabled = enabledBackends()
        val homeLat = home.lat
        val homeLon = home.lon
        val backendIds = if (homeLat != null && homeLon != null) {
            registry.journeyBackendIds(from.lat, from.lon, homeLat, homeLon, enabled)
        } else {
            listOf(home.backendId).filter(enabled)
        }
        val homeCandidate = home.asCandidate()
        val backends = backendIds.mapNotNull { registry.byId(it) }
        val results = coroutineScope {
            backends.map { backend ->
                async {
                    runCatching {
                        val fromPlace = placeFor(backend, from) ?: return@runCatching null
                        val toPlace = placeFor(backend, homeCandidate) ?: return@runCatching null
                        backend.queryConnections(fromPlace, toPlace, departureTime, modes)
                    }.onFailure {
                        Log.w(TAG, "Connection query failed from ${from.name} (${backend.id})", it)
                    }
                }
            }.awaitAll()
        }
        val answered = results.mapNotNull { it.getOrNull() }
        if (answered.isEmpty() && results.any { it.isFailure }) {
            throw results.first { it.isFailure }.exceptionOrNull()!!
        }
        mergeConnections(answered, registry::priority).also { merged ->
            val counts = backends.zip(results).joinToString { (backend, result) ->
                "${backend.id}=" + (result.getOrNull()?.size?.toString() ?: if (result.isFailure) "failed" else "skipped")
            }
            Log.i(TAG, "Connections from ${from.name}: $counts, ${merged.size} after merging")
        }
    }

    /**
     * How [backend] can refer to [station]: by its own id if known, by
     * coordinates if it routes from those, otherwise by the id of its own
     * station at that place (looked up once and cached).
     */
    private suspend fun placeFor(backend: TransitBackend, station: StationCandidate): TransitPlace? {
        val ownId = if (station.backendId == backend.id) station.id else station.alternateIds[backend.id]
        if (ownId != null) {
            return TransitPlace.Station(ownId, station.name, station.lat, station.lon)
        }
        if (backend.canRouteFromCoordinates) {
            return TransitPlace.Coordinate(station.lat, station.lon, station.name)
        }
        val resolvedId = resolveStationId(backend, station) ?: return null
        return TransitPlace.Station(resolvedId, station.name, station.lat, station.lon)
    }

    private suspend fun resolveStationId(backend: TransitBackend, station: StationCandidate): String? {
        val key = "${backend.id}|${"%.5f".format(station.lat)}|${"%.5f".format(station.lon)}|${station.name}"
        resolvedStationIds[key]?.let { return it.ifEmpty { null } }
        val match = backend.findNearbyStations(station.lat, station.lon, STATION_MATCH_RADIUS_METERS, 20)
            .filter { isSameStation(station, it) }
            .minByOrNull { it.distanceFromRouteMeters }
        resolvedStationIds[key] = match?.id.orEmpty()
        return match?.id
    }

    private fun HomeStation.asCandidate() = StationCandidate(
        id = id,
        name = name,
        lat = lat ?: 0.0,
        lon = lon ?: 0.0,
        distanceAlongRouteMeters = 0.0,
        distanceFromRouteMeters = 0.0,
        backendId = backendId,
    )

    /**
     * Stations matching a search text, from the wide-area backends (DB, and
     * Transitous if enabled), merged. Throws if every backend failed.
     */
    suspend fun suggestLocations(query: String): List<StationSuggestion> = withContext(Dispatchers.IO) {
        val enabled = enabledBackends()
        val backends = SEARCH_BACKENDS.filter(enabled).mapNotNull { registry.byId(it) }
        val results = coroutineScope {
            backends.map { backend -> async { runCatching { backend.suggestStations(query) } } }.awaitAll()
        }
        val answered = results.mapNotNull { it.getOrNull() }
        if (answered.isEmpty() && results.isNotEmpty()) {
            throw results.first().exceptionOrNull() ?: IOException("no transit backend answered")
        }
        // Interleave the backends' lists so each one's best matches come first,
        // and drop suggestions that duplicate one from another backend.
        val interleaved = (0 until (answered.maxOfOrNull { it.size } ?: 0)).flatMap { rank ->
            answered.mapNotNull { it.getOrNull(rank) }
        }
        val merged = ArrayList<StationSuggestion>()
        for (suggestion in interleaved) {
            val candidate = suggestion.asCandidate() ?: run { merged += suggestion; continue }
            val duplicate = merged.any { existing ->
                existing.backendId != suggestion.backendId &&
                    existing.asCandidate()?.let { isSameStation(it, candidate) } == true
            }
            if (!duplicate) merged += suggestion
        }
        merged
    }

    private fun StationSuggestion.asCandidate(): StationCandidate? {
        val lat = lat ?: return null
        val lon = lon ?: return null
        return StationCandidate(
            id = id,
            name = name,
            lat = lat,
            lon = lon,
            distanceAlongRouteMeters = 0.0,
            distanceFromRouteMeters = 0.0,
            backendId = backendId,
        )
    }

    /**
     * Result of [discoverStationsAlongRoute]. [networkFailed] is true when
     * every station query threw — typically means the user is offline or the
     * transit backends are unreachable, not that the route truly has no
     * stations along it. Partial failures still surface as a successful result
     * with whatever stations made it through.
     */
    data class StationDiscoveryResult(
        val stations: List<StationCandidate>,
        val networkFailed: Boolean,
    )

    /**
     * Discover stations along a route by sampling points at intervals and
     * asking the backends covering each point.
     * Returns deduplicated stations sorted by distance along the route.
     */
    suspend fun discoverStationsAlongRoute(
        points: List<RoutePoint>,
        samplingIntervalMeters: Int = 2000,
        searchRadiusMeters: Int = 2000,
        requiredProducts: Set<String>? = null
    ): StationDiscoveryResult = withContext(Dispatchers.IO) {
        if (points.isEmpty()) {
            return@withContext StationDiscoveryResult(emptyList(), networkFailed = false)
        }

        // Sample points at regular intervals along the route
        val samplePoints = mutableListOf<RoutePoint>()
        var lastSampleDistance = -samplingIntervalMeters.toDouble() // ensure first point is sampled

        for (point in points) {
            if (point.distanceFromStart - lastSampleDistance >= samplingIntervalMeters) {
                samplePoints.add(point)
                lastSampleDistance = point.distanceFromStart
            }
        }
        // Always include the last point
        if (samplePoints.lastOrNull() != points.last()) {
            samplePoints.add(points.last())
        }

        // Query nearby stations for each sample point in parallel. We track
        // success/failure per query so the caller can distinguish "every
        // query threw" (e.g. offline) from "queries returned, just nothing
        // matched" (sparse rural route).
        val enabled = enabledBackends()
        val perSample = samplePoints.map { samplePoint ->
            async {
                val backendIds = registry.stationBackendIds(samplePoint.lat, samplePoint.lon, enabled)
                // Fetch a large per-sample batch so train stations aren't
                // crowded out by nearby tram/bus stops in dense urban areas
                // (e.g. Mannheim Hbf sits in a cluster of ~50+ stops).
                queryStations(backendIds, samplePoint.lat, samplePoint.lon, searchRadiusMeters, 100)
            }
        }.awaitAll()
        val allResults = perSample.flatten()
        val allStations = allResults.mapNotNull { it.getOrNull() }.flatten().filterProducts(requiredProducts)
        val networkFailed = allResults.isNotEmpty() && allResults.all { it.isFailure }
        val involved = allStations.map { it.backendId }.toSortedSet()
        Log.i(TAG, "Route discovery: ${allStations.size} stations from $involved, " +
            "${allResults.count { it.isFailure }} of ${allResults.size} queries failed")

        // Deduplicate by station id, keeping the one with smallest distance from route
        val deduped = allStations
            .groupBy { it.backendId to it.id }
            .map { (_, candidates) -> candidates.minBy { it.distanceFromRouteMeters } }

        // For each station, find the closest route point and use its distanceFromStart
        val withRouteDistance = deduped.map { station ->
            val closestRoutePoint = points.minBy { pt ->
                haversineMeters(station.lat, station.lon, pt.lat, pt.lon)
            }
            station.copy(
                distanceAlongRouteMeters = closestRoutePoint.distanceFromStart,
                distanceFromRouteMeters = haversineMeters(
                    station.lat, station.lon,
                    closestRoutePoint.lat, closestRoutePoint.lon
                )
            )
        }
        // Cluster across samples and backends too: neighbouring sample
        // batches may each have kept a different "child" of the same hub, and
        // two backends may both know the station.
        val stations = clusterStations(withRouteDistance, priority = registry::priority)
            .sortedBy { it.distanceAlongRouteMeters }
        StationDiscoveryResult(stations, networkFailed)
    }

    /** Coordinates of the home station, asked from the backend that issued its id. */
    suspend fun resolveStationLocation(home: HomeStation): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            try {
                registry.byId(home.backendId)?.locateStation(home.id, home.name)
            } catch (_: Exception) {
                null
            }
        }

    private companion object {
        const val TAG = "GpxitTransit"
        const val STATION_MATCH_RADIUS_METERS = 500

        /** Backends with wide coverage, asked for home station search. */
        val SEARCH_BACKENDS = listOf(TransitBackendRegistry.DB, TransitBackendRegistry.TRANSITOUS)

        val resolvedStationIds = ConcurrentHashMap<String, String>()
    }
}
