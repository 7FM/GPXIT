package dev.gpxit.app.domain

data class RouteInfo(
    val name: String?,
    val points: List<RoutePoint>,
    val totalDistanceMeters: Double,
    val stations: List<StationCandidate> = emptyList()
)
