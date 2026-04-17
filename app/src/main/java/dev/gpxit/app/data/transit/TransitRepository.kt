package dev.gpxit.app.data.transit

import de.schildbach.pte.DbProvider
import de.schildbach.pte.NetworkProvider
import de.schildbach.pte.dto.Location
import de.schildbach.pte.dto.LocationType
import de.schildbach.pte.dto.Point
import de.schildbach.pte.dto.Product
import de.schildbach.pte.dto.TripOptions
import dev.gpxit.app.data.gpx.haversineMeters
import dev.gpxit.app.domain.StationCandidate
import dev.gpxit.app.domain.TrainConnection
import dev.gpxit.app.domain.TripLeg
import dev.gpxit.app.domain.TripStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.EnumSet

class TransitRepository {

    private val provider: NetworkProvider = DbProvider()

    suspend fun findNearbyStations(
        lat: Double,
        lon: Double,
        maxDistanceMeters: Int = 2000,
        maxLocations: Int = 10,
        requiredProducts: Set<String>? = null
    ): List<StationCandidate> = withContext(Dispatchers.IO) {
        val coord = Location.coord(Point.fromDouble(lat, lon))
        val result = provider.queryNearbyLocations(
            EnumSet.of(LocationType.STATION),
            coord,
            maxDistanceMeters,
            maxLocations
        )
        val raw = result.locations?.mapNotNull { loc ->
            val locId = loc.id ?: return@mapNotNull null
            val locCoord = loc.coord ?: return@mapNotNull null
            val stationProducts = loc.products?.map { it.name }?.toSet() ?: emptySet()

            // Filter: station must offer at least one of the required products
            if (requiredProducts != null && stationProducts.intersect(requiredProducts).isEmpty()) {
                return@mapNotNull null
            }

            StationCandidate(
                id = locId,
                name = loc.name ?: "Unknown",
                lat = locCoord.latAsDouble,
                lon = locCoord.lonAsDouble,
                distanceAlongRouteMeters = 0.0, // computed later
                distanceFromRouteMeters = haversineMeters(lat, lon, locCoord.latAsDouble, locCoord.lonAsDouble),
                products = stationProducts
            )
        } ?: emptyList()
        // Collapse platform-child siblings so the map stays readable.
        clusterStations(raw)
    }

    suspend fun queryConnections(
        fromStationId: String,
        fromStationName: String,
        toStationId: String,
        toStationName: String,
        departureTime: Instant,
        products: Set<Product> = EnumSet.of(
            Product.HIGH_SPEED_TRAIN,
            Product.REGIONAL_TRAIN,
            Product.SUBURBAN_TRAIN
        )
    ): List<TrainConnection> = withContext(Dispatchers.IO) {
        val from = Location(LocationType.STATION, fromStationId, null, fromStationName)
        val to = Location(LocationType.STATION, toStationId, null, toStationName)
        val options = TripOptions(
            products,
            null as de.schildbach.pte.NetworkProvider.Optimize?,
            null as de.schildbach.pte.NetworkProvider.WalkSpeed?,
            null as de.schildbach.pte.NetworkProvider.Accessibility?,
            null as Set<de.schildbach.pte.NetworkProvider.TripFlag>?
        )

        val result = provider.queryTrips(
            from, null, to,
            Date.from(departureTime),
            true, // departure
            options
        )

        result.trips?.map { trip ->
            val dep = trip.firstDepartureTime?.toInstant() ?: departureTime
            val arr = trip.lastArrivalTime?.toInstant() ?: departureTime
            val firstLine = trip.legs.firstOrNull()?.let { leg ->
                if (leg is de.schildbach.pte.dto.Trip.Public) {
                    leg.line?.label ?: "?"
                } else "Walk"
            } ?: "?"

            val legs = trip.legs.map { leg ->
                when (leg) {
                    is de.schildbach.pte.dto.Trip.Public -> {
                        val legDep = leg.getDepartureTime(true)?.toInstant() ?: dep
                        val legArr = leg.getArrivalTime(true)?.toInstant() ?: arr
                        val stops = leg.intermediateStops?.map { stop ->
                            TripStop(
                                name = stop.location?.name ?: "?",
                                arrivalTime = stop.getArrivalTime(true)?.toInstant(),
                                departureTime = stop.getDepartureTime(true)?.toInstant()
                            )
                        } ?: emptyList()
                        val depDelay = leg.getDepartureDelay()?.let { (it / 60).toInt() }
                        val arrDelay = leg.getArrivalDelay()?.let { (it / 60).toInt() }
                        TripLeg(
                            line = leg.line?.label,
                            direction = leg.destination?.name,
                            departureStation = leg.departureStop?.location?.name ?: leg.departure?.name ?: "?",
                            departureTime = legDep,
                            arrivalStation = leg.arrivalStop?.location?.name ?: leg.arrival?.name ?: "?",
                            arrivalTime = legArr,
                            intermediateStops = stops,
                            departureDelayMinutes = depDelay,
                            arrivalDelayMinutes = arrDelay
                        )
                    }
                    is de.schildbach.pte.dto.Trip.Individual -> {
                        val legDep = leg.departureTime?.toInstant() ?: dep
                        val legArr = leg.arrivalTime?.toInstant() ?: dep
                        TripLeg(
                            line = null,
                            direction = null,
                            departureStation = leg.departure?.name ?: "?",
                            departureTime = legDep,
                            arrivalStation = leg.arrival?.name ?: "?",
                            arrivalTime = legArr,
                            isWalk = true
                        )
                    }
                    else -> {
                        TripLeg(
                            line = null,
                            direction = null,
                            departureStation = leg.departure?.name ?: "?",
                            departureTime = dep,
                            arrivalStation = leg.arrival?.name ?: "?",
                            arrivalTime = arr,
                            isWalk = true
                        )
                    }
                }
            }

            TrainConnection(
                departureTime = dep,
                arrivalTime = arr,
                line = firstLine,
                numChanges = trip.numChanges ?: 0,
                duration = Duration.between(dep, arr),
                legs = legs
            )
        } ?: emptyList()
    }

    data class StationSuggestion(
        val id: String,
        val name: String,
        val lat: Double?,
        val lon: Double?
    )

    suspend fun suggestLocations(query: String): List<StationSuggestion> = withContext(Dispatchers.IO) {
        val types: Set<LocationType>? = null
        val result = provider.suggestLocations(query, types, 10)
        val suggestions: List<StationSuggestion> = result.suggestedLocations?.mapNotNull { suggestion ->
            val loc = suggestion.location
            val locId = loc.id ?: return@mapNotNull null
            val locName = loc.name ?: return@mapNotNull null
            StationSuggestion(
                id = locId,
                name = locName,
                lat = loc.coord?.latAsDouble,
                lon = loc.coord?.lonAsDouble
            )
        } ?: emptyList()
        suggestions
    }

    /**
     * Discover stations along a route by sampling points at intervals.
     * Returns deduplicated stations sorted by distance along the route.
     */
    suspend fun discoverStationsAlongRoute(
        points: List<dev.gpxit.app.domain.RoutePoint>,
        samplingIntervalMeters: Int = 2000,
        searchRadiusMeters: Int = 2000,
        requiredProducts: Set<String>? = null
    ): List<StationCandidate> = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext emptyList()

        // Sample points at regular intervals along the route
        val samplePoints = mutableListOf<dev.gpxit.app.domain.RoutePoint>()
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

        // Query nearby stations for each sample point in parallel
        val deferredResults = samplePoints.map { samplePoint ->
            async {
                try {
                    // Fetch a large per-sample batch so train stations aren't
                    // crowded out by nearby tram/bus stops in dense urban areas
                    // (e.g. Mannheim Hbf sits in a cluster of ~50+ stops).
                    findNearbyStations(
                        samplePoint.lat, samplePoint.lon,
                        searchRadiusMeters, 100,
                        requiredProducts
                    )
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        val allStations = deferredResults.awaitAll().flatten()

        // Deduplicate by station ID, keeping the one with smallest distance from route
        val deduped = allStations
            .groupBy { it.id }
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
        // Cluster across samples too: neighbouring sample batches may each
        // have kept a different "child" of the same hub.
        clusterStations(withRouteDistance).sortedBy { it.distanceAlongRouteMeters }
    }

    /**
     * Resolve coordinates for a station by searching for it.
     */
    suspend fun resolveStationLocation(stationId: String, stationName: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            try {
                // Try suggestLocations first
                val suggestions = suggestLocations(stationName)
                val match = suggestions.firstOrNull { it.id == stationId }
                if (match?.lat != null && match.lon != null) {
                    return@withContext match.lat to match.lon
                }
                // Fallback: query nearby the station location object
                val loc = Location(LocationType.STATION, stationId, null, stationName)
                val result = provider.queryNearbyLocations(
                    EnumSet.of(LocationType.STATION), loc, 1000, 1
                )
                val found = result.locations?.firstOrNull { it.id == stationId && it.coord != null }
                val foundCoord = found?.coord
                if (foundCoord != null) {
                    return@withContext foundCoord.latAsDouble to foundCoord.lonAsDouble
                }
                null
            } catch (_: Exception) {
                null
            }
        }
}

/**
 * Collapse "platform-child" stations around a real hub so the map isn't
 * cluttered. Near major hubs the DB API returns the Hbf plus many siblings
 * ("… Gleis 3", "… Vorplatz", bus islands under the same product set) that
 * mean the same stop to a cyclist. Two stations are treated as the same
 * cluster when their names have a word-boundary prefix relationship AND they
 * sit within [proximityMeters] of each other; the one with the shortest name
 * wins.
 */
private fun clusterStations(
    stations: List<StationCandidate>,
    proximityMeters: Double = 400.0
): List<StationCandidate> {
    if (stations.size <= 1) return stations
    val sorted = stations.sortedBy { it.name.length }
    val kept = ArrayList<StationCandidate>(sorted.size)
    for (st in sorted) {
        val base = st.name.stationNameBase()
        if (base.isEmpty()) {
            kept += st
            continue
        }
        val absorbed = kept.any { existing ->
            val eb = existing.name.stationNameBase()
            if (eb.isEmpty()) return@any false
            val related = namesRelated(base, eb)
            if (!related) return@any false
            haversineMeters(st.lat, st.lon, existing.lat, existing.lon) < proximityMeters
        }
        if (!absorbed) kept += st
    }
    return kept
}

/** True if `a` equals `b`, or one is a prefix of the other up to a word boundary. */
private fun namesRelated(a: String, b: String): Boolean {
    if (a == b) return true
    val (shorter, longer) = if (a.length < b.length) a to b else b to a
    if (!longer.startsWith(shorter)) return false
    val nextCh = longer.getOrNull(shorter.length) ?: return true
    return nextCh == ' ' || nextCh == ',' || nextCh == '(' || nextCh == '-' || nextCh == '/'
}

/** Normalize a station name to its comparable base form. */
private fun String.stationNameBase(): String =
    this.replace(Regex("\\s*\\([^)]*\\)"), "")   // drop parentheticals: " (Vorplatz)"
        .replace(Regex(",.*"), "")                 // drop trailing ", City"
        .trim()
        .lowercase()
