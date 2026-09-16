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
    val name: String?,
    /** Raw OSM `opening_hours` value. */
    val openingHours: String? = null,
    /** Holiday region (e.g. `DE-HE`) used to evaluate `PH` / `SH` in [openingHours]. */
    val holidayRegion: String? = null,
)
