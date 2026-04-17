package dev.gpxit.app.domain

import java.time.Instant

/**
 * Data shown above a station marker on the map after "Take me home" has been pressed.
 * Both fields are optional — a station can have an ETA without any train, or neither.
 */
data class StationLabel(
    val arrivalAtStation: Instant? = null,
    val nextTrainDeparture: Instant? = null,
    val isRecommended: Boolean = false
)
