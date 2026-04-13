package dev.gpxit.app.domain

data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val elevation: Double? = null,
    val distanceFromStart: Double // meters, cumulative
)
