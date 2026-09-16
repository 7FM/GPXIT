package dev.gpxit.app.domain

/** The station every connection query ends at, as chosen in settings. */
data class HomeStation(
    val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    /** Transit backend that issued [id]. */
    val backendId: String,
)
