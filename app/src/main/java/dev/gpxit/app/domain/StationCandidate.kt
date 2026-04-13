package dev.gpxit.app.domain

data class StationCandidate(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceAlongRouteMeters: Double, // distance along route from start to nearest route point
    val distanceFromRouteMeters: Double,  // perpendicular distance from the route
    val products: Set<String> = emptySet() // e.g. "REGIONAL_TRAIN", "SUBURBAN_TRAIN"
)
