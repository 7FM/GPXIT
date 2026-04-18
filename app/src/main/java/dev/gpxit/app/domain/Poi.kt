package dev.gpxit.app.domain

enum class PoiType {
    GROCERY,       // supermarket, convenience, grocery stores
    BAKERY,        // bakeries
    WATER,         // drinking water taps / fountains
    TOILET,        // public toilets
    BIKE_REPAIR    // bike-repair stations + bike shops
}

data class Poi(
    val id: Long,
    val type: PoiType,
    val lat: Double,
    val lon: Double,
    val name: String?
)
