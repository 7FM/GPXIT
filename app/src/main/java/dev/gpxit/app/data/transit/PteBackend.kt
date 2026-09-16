package dev.gpxit.app.data.transit

import de.schildbach.pte.NetworkProvider
import de.schildbach.pte.dto.Location
import de.schildbach.pte.dto.LocationType
import de.schildbach.pte.dto.NearbyLocationsResult
import de.schildbach.pte.dto.Point
import de.schildbach.pte.dto.Product
import de.schildbach.pte.dto.QueryTripsResult
import de.schildbach.pte.dto.Trip
import de.schildbach.pte.dto.TripOptions
import dev.gpxit.app.data.gpx.haversineMeters
import dev.gpxit.app.domain.StationCandidate
import dev.gpxit.app.domain.TrainConnection
import dev.gpxit.app.domain.TripLeg
import dev.gpxit.app.domain.TripStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.EnumSet

/** A public-transport-enabler [NetworkProvider] as a [TransitBackend]. */
class PteBackend(
    override val id: String,
    override val displayName: String,
    override val canRouteFromCoordinates: Boolean,
    providerFactory: () -> NetworkProvider,
) : TransitBackend {

    private val provider: NetworkProvider by lazy(providerFactory)

    override suspend fun findNearbyStations(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        maxResults: Int,
    ): List<StationCandidate> = withContext(Dispatchers.IO) {
        val result = provider.queryNearbyLocations(
            EnumSet.of(LocationType.STATION),
            Location.coord(Point.fromDouble(lat, lon)),
            radiusMeters,
            maxResults
        )
        // The provider sometimes swallows network failures and returns a
        // non-OK status (typically SERVICE_DOWN with null locations) instead
        // of throwing — e.g. when offline or when the upstream HAFAS endpoint
        // refuses the request. Surface that as an exception so callers count
        // it as a failure rather than a valid "zero stations here" result.
        if (result.status != null && result.status != NearbyLocationsResult.Status.OK) {
            throw IOException("$id: nearby status ${result.status}")
        }
        result.locations.orEmpty().mapNotNull { loc ->
            val locId = loc.id ?: return@mapNotNull null
            val coord = loc.coord ?: return@mapNotNull null
            StationCandidate(
                id = locId,
                name = loc.name ?: "Unknown",
                lat = coord.latAsDouble,
                lon = coord.lonAsDouble,
                distanceAlongRouteMeters = 0.0, // computed later
                distanceFromRouteMeters = haversineMeters(lat, lon, coord.latAsDouble, coord.lonAsDouble),
                products = loc.products?.map { it.name }?.toSet() ?: emptySet(),
                backendId = id,
            )
        }
    }

    override suspend fun queryConnections(
        from: TransitPlace,
        to: TransitPlace,
        departure: Instant,
        modes: Set<TransitMode>,
    ): List<TrainConnection> = withContext(Dispatchers.IO) {
        val products = modes.mapNotNullTo(EnumSet.noneOf(Product::class.java)) { mode ->
            Product.entries.firstOrNull { it.name == mode.name }
        }
        val options = TripOptions(
            products,
            null as NetworkProvider.Optimize?,
            null as NetworkProvider.WalkSpeed?,
            null as NetworkProvider.Accessibility?,
            null as Set<NetworkProvider.TripFlag>?
        )
        val result = provider.queryTrips(
            from.toLocation(), null, to.toLocation(),
            Date.from(departure),
            true, // departure
            options
        )
        when (result.status) {
            QueryTripsResult.Status.OK, null -> {}
            // Legitimately nothing to offer.
            QueryTripsResult.Status.NO_TRIPS, QueryTripsResult.Status.TOO_CLOSE -> return@withContext emptyList()
            // Service down, unknown station, … — a failure, not "no trips".
            else -> throw IOException("$id: trips status ${result.status}")
        }
        result.trips.orEmpty().map { trip -> trip.toConnection(departure, from, to) }
    }

    override suspend fun suggestStations(query: String): List<StationSuggestion> =
        withContext(Dispatchers.IO) {
            val result = provider.suggestLocations(query, null as Set<LocationType>?, 10)
            result.suggestedLocations.orEmpty().mapNotNull { suggestion ->
                val loc = suggestion.location
                StationSuggestion(
                    backendId = id,
                    backendName = displayName,
                    id = loc.id ?: return@mapNotNull null,
                    name = loc.name ?: return@mapNotNull null,
                    lat = loc.coord?.latAsDouble,
                    lon = loc.coord?.lonAsDouble,
                )
            }
        }

    override suspend fun locateStation(stationId: String, name: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            // Try suggestLocations first
            val match = suggestStations(name).firstOrNull { it.id == stationId }
            if (match?.lat != null && match.lon != null) {
                return@withContext match.lat to match.lon
            }
            // Fallback: query nearby the station location object
            val loc = Location(LocationType.STATION, stationId, null, name)
            val result = provider.queryNearbyLocations(
                EnumSet.of(LocationType.STATION), loc, 1000, 1
            )
            val coord = result.locations?.firstOrNull { it.id == stationId && it.coord != null }?.coord
            coord?.let { it.latAsDouble to it.lonAsDouble }
        }

    private fun TransitPlace.toLocation(): Location = when (this) {
        is TransitPlace.Station -> Location(LocationType.STATION, id, null, name)
        is TransitPlace.Coordinate -> Location.coord(Point.fromDouble(lat, lon))
    }

    private fun Trip.toConnection(
        requestedDeparture: Instant,
        from: TransitPlace,
        to: TransitPlace,
    ): TrainConnection {
        val dep = firstDepartureTime?.toInstant() ?: requestedDeparture
        val arr = lastArrivalTime?.toInstant() ?: requestedDeparture
        val firstLine = legs.firstOrNull()?.let { leg ->
            if (leg is Trip.Public) leg.line?.label ?: "?" else "Walk"
        } ?: "?"

        // Trips from/to a coordinate come back with the coordinate as the
        // endpoint name ("5.365000"); show the place we asked for instead.
        fun placeName(location: Location?): String {
            val name = location?.name ?: return "?"
            if (location.type != LocationType.COORD && location.type != LocationType.ADDRESS) return name
            if (!COORDINATE_NAME.matches(name)) return name
            return when (location) {
                legs.first().departure -> from.name
                legs.last().arrival -> to.name
                else -> name
            }
        }

        val mappedLegs = legs.map { leg ->
            when (leg) {
                is Trip.Public -> TripLeg(
                    line = leg.line?.label,
                    direction = leg.destination?.name,
                    departureStation = leg.departureStop?.location?.name ?: placeName(leg.departure),
                    departureTime = leg.getDepartureTime(true)?.toInstant() ?: dep,
                    arrivalStation = leg.arrivalStop?.location?.name ?: placeName(leg.arrival),
                    arrivalTime = leg.getArrivalTime(true)?.toInstant() ?: arr,
                    intermediateStops = leg.intermediateStops?.map { stop ->
                        TripStop(
                            name = stop.location?.name ?: "?",
                            arrivalTime = stop.getArrivalTime(true)?.toInstant(),
                            departureTime = stop.getDepartureTime(true)?.toInstant()
                        )
                    } ?: emptyList(),
                    // PTE delays are in milliseconds
                    departureDelayMinutes = leg.departureDelay?.let { (it / 60_000).toInt() },
                    arrivalDelayMinutes = leg.arrivalDelay?.let { (it / 60_000).toInt() }
                )
                is Trip.Individual -> TripLeg(
                    line = null,
                    direction = null,
                    departureStation = placeName(leg.departure),
                    departureTime = leg.departureTime?.toInstant() ?: dep,
                    arrivalStation = placeName(leg.arrival),
                    arrivalTime = leg.arrivalTime?.toInstant() ?: dep,
                    isWalk = true
                )
                else -> TripLeg(
                    line = null,
                    direction = null,
                    departureStation = placeName(leg.departure),
                    departureTime = dep,
                    arrivalStation = placeName(leg.arrival),
                    arrivalTime = arr,
                    isWalk = true
                )
            }
        }

        return TrainConnection(
            departureTime = dep,
            arrivalTime = arr,
            line = firstLine,
            numChanges = numChanges ?: 0,
            duration = Duration.between(dep, arr),
            legs = mappedLegs,
            // `id` alone would be the Trip's own id in this extension.
            backendId = this@PteBackend.id,
        )
    }

    private companion object {
        val COORDINATE_NAME = Regex("""-?\d+\.\d+""")
    }
}
