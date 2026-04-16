package dev.gpxit.app.domain

import java.time.Duration
import java.time.Instant

data class TripStop(
    val name: String,
    val arrivalTime: Instant?,
    val departureTime: Instant?
)

data class TripLeg(
    val line: String?,           // null for walking legs
    val direction: String?,      // e.g. "Berlin Hbf"
    val departureStation: String,
    val departureTime: Instant,
    val arrivalStation: String,
    val arrivalTime: Instant,
    val intermediateStops: List<TripStop> = emptyList(),
    val isWalk: Boolean = false,
    val departureDelayMinutes: Int? = null, // null = unknown, 0 = on time
    val arrivalDelayMinutes: Int? = null
)

data class TrainConnection(
    val departureTime: Instant,
    val arrivalTime: Instant,
    val line: String,       // first leg line label, for summary display
    val numChanges: Int,
    val duration: Duration,
    val legs: List<TripLeg> = emptyList()
)

data class ConnectionOption(
    val station: StationCandidate,
    val cyclingTimeMinutes: Int,
    val estimatedArrivalAtStation: Instant,
    val connections: List<TrainConnection>,
    val bestArrivalHome: Instant?
) {
    /** Wait time at the station until the first train departs (minutes). */
    val waitTimeMinutes: Int
        get() {
            val firstDeparture = connections.firstOrNull()?.departureTime ?: return 0
            val wait = Duration.between(estimatedArrivalAtStation, firstDeparture).toMinutes()
            return wait.coerceAtLeast(0).toInt()
        }

    /** Total time from now: cycling + waiting + train travel (minutes). */
    val totalTimeHomeMinutes: Int
        get() {
            val arrival = bestArrivalHome ?: return Int.MAX_VALUE
            val now = estimatedArrivalAtStation.minusSeconds((cyclingTimeMinutes * 60).toLong())
            return Duration.between(now, arrival).toMinutes().coerceAtLeast(0).toInt()
        }

    /** Whether this option gets the user home earliest among a set (set externally). */
    var isRecommended: Boolean = false
}
