package dev.gpxit.app.data.transit

import dev.gpxit.app.domain.StationCandidate
import dev.gpxit.app.domain.TrainConnection
import java.time.Instant

/**
 * Something that answers transit queries: a public-transport-enabler provider
 * ([PteBackend]) or our own client for a service PTE doesn't have
 * ([TransitousBackend]). Implementations throw [java.io.IOException] when the
 * service can't be reached or refuses the request.
 */
interface TransitBackend {
    /** Stable id, persisted with stations and the home station. */
    val id: String

    /** Name shown to the user, e.g. next to search results. */
    val displayName: String

    /** Whether trips can start or end at bare coordinates instead of station ids. */
    val canRouteFromCoordinates: Boolean

    /** Stations near a point, nearest first, tagged with [id]. */
    suspend fun findNearbyStations(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        maxResults: Int,
    ): List<StationCandidate>

    /** Trips departing at or after [departure], tagged with [id]. */
    suspend fun queryConnections(
        from: TransitPlace,
        to: TransitPlace,
        departure: Instant,
        modes: Set<TransitMode>,
    ): List<TrainConnection>

    /** Stations matching a free-text query. */
    suspend fun suggestStations(query: String): List<StationSuggestion>

    /** Coordinates of one of this backend's stations, if the backend can tell. */
    suspend fun locateStation(stationId: String, name: String): Pair<Double, Double>? = null
}

/** Start or end of a trip, as understood by one backend. */
sealed interface TransitPlace {
    val name: String

    /** A station by the backend's own id. */
    data class Station(
        val id: String,
        override val name: String,
        val lat: Double? = null,
        val lon: Double? = null,
    ) : TransitPlace

    /** A bare point; only for backends with [TransitBackend.canRouteFromCoordinates]. */
    data class Coordinate(
        val lat: Double,
        val lon: Double,
        override val name: String,
    ) : TransitPlace
}

data class StationSuggestion(
    val backendId: String,
    val backendName: String,
    val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
)
