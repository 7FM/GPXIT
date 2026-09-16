package dev.gpxit.app.data.transit

import dev.gpxit.app.data.gpx.haversineMeters
import dev.gpxit.app.domain.StationCandidate
import dev.gpxit.app.domain.TrainConnection
import dev.gpxit.app.domain.TripLeg
import dev.gpxit.app.domain.TripStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.cos

/**
 * Client for Transitous (https://transitous.org), a community-run public
 * transport routing service built on MOTIS. Uses the same API endpoints as
 * KPublicTransport's MOTIS backend.
 *
 * Usage policy (https://transitous.org/api/): open-source, non-commercial
 * apps only; send a User-Agent with app name, version and contact; link to
 * https://transitous.org/sources/ where results are shown.
 */
class TransitousBackend(
    private val userAgent: String,
    private val baseUrl: String = "https://api.transitous.org",
    /** Performs a GET and returns the body; replaced in tests. */
    private val httpGet: (url: String, userAgent: String) -> String = ::defaultGet,
) : TransitBackend {

    override val id = TransitBackendRegistry.TRANSITOUS
    override val displayName = "Transitous"
    override val canRouteFromCoordinates = true

    override suspend fun findNearbyStations(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        maxResults: Int,
    ): List<StationCandidate> = withContext(Dispatchers.IO) {
        val dLat = radiusMeters / METERS_PER_DEGREE
        val dLon = radiusMeters / (METERS_PER_DEGREE * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        // min = lower right, max = upper left
        val url = "$baseUrl/api/v6/map/stops" +
            "?min=${coord(lat - dLat)},${coord(lon + dLon)}" +
            "&max=${coord(lat + dLat)},${coord(lon - dLon)}"
        parseStops(get(url), lat, lon)
            .filter { it.distanceFromRouteMeters <= radiusMeters }
            .sortedBy { it.distanceFromRouteMeters }
            .take(maxResults)
    }

    override suspend fun queryConnections(
        from: TransitPlace,
        to: TransitPlace,
        departure: Instant,
        modes: Set<TransitMode>,
    ): List<TrainConnection> = withContext(Dispatchers.IO) {
        val motisModes = modes.flatMap { motisModesFor(it) }.distinct()
        val url = "$baseUrl/api/v6/plan" +
            "?fromPlace=${encode(from.toParameter())}" +
            "&toPlace=${encode(to.toParameter())}" +
            // MOTIS misreads microsecond fractions (shifts the date), so send whole seconds.
            "&time=${encode(DateTimeFormatter.ISO_INSTANT.format(departure.truncatedTo(ChronoUnit.SECONDS)))}" +
            "&arriveBy=false" +
            "&numItineraries=6" +
            "&detailedTransfers=false" +
            (if (motisModes.isNotEmpty()) "&transitModes=${motisModes.joinToString(",")}" else "")
        parsePlan(get(url), from, to)
    }

    override suspend fun suggestStations(query: String): List<StationSuggestion> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/v1/geocode?text=${encode(query)}&type=STOP&numResults=10"
            parseGeocode(get(url))
        }

    private fun get(url: String): String = httpGet(url, userAgent)

    private fun TransitPlace.toParameter(): String = when (this) {
        is TransitPlace.Station -> id
        is TransitPlace.Coordinate -> "${coord(lat)},${coord(lon)}"
    }

    internal fun parseStops(body: String, lat: Double, lon: Double): List<StationCandidate> {
        val stops = JSONArray(body)
        return (0 until stops.length()).mapNotNull { i ->
            val stop = stops.getJSONObject(i)
            val stopId = stop.optString("stopId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val stopLat = stop.getDouble("lat")
            val stopLon = stop.getDouble("lon")
            StationCandidate(
                id = stopId,
                name = stop.optString("name", "Unknown"),
                lat = stopLat,
                lon = stopLon,
                distanceAlongRouteMeters = 0.0, // computed later
                distanceFromRouteMeters = haversineMeters(lat, lon, stopLat, stopLon),
                products = transitModesOf(stop.optJSONArray("modes")).mapTo(LinkedHashSet()) { it.name },
                backendId = id,
            )
        }
    }

    internal fun parsePlan(body: String, from: TransitPlace, to: TransitPlace): List<TrainConnection> {
        val itineraries = JSONObject(body).optJSONArray("itineraries") ?: return emptyList()
        return (0 until itineraries.length()).mapNotNull { i ->
            val itinerary = itineraries.getJSONObject(i)
            val legsJson = itinerary.optJSONArray("legs") ?: return@mapNotNull null
            val legs = (0 until legsJson.length()).map { parseLeg(legsJson.getJSONObject(it), from, to) }
            if (legs.isEmpty()) return@mapNotNull null
            val departure = instant(itinerary, "startTime") ?: legs.first().departureTime
            val arrival = instant(itinerary, "endTime") ?: legs.last().arrivalTime
            TrainConnection(
                departureTime = departure,
                arrivalTime = arrival,
                line = legs.first().line ?: "Walk",
                numChanges = itinerary.optInt("transfers", 0),
                duration = Duration.between(departure, arrival),
                legs = legs,
                backendId = id,
            )
        }
    }

    private fun parseLeg(leg: JSONObject, from: TransitPlace, to: TransitPlace): TripLeg {
        val fromJson = leg.getJSONObject("from")
        val toJson = leg.getJSONObject("to")
        val departure = instant(leg, "startTime")!!
        val arrival = instant(leg, "endTime")!!
        if (motisModeToTransitMode(leg.optString("mode")) == null) {
            // walking, cycling, … — anything we don't show as a vehicle
            return TripLeg(
                line = null,
                direction = null,
                departureStation = placeName(fromJson, from, to),
                departureTime = departure,
                arrivalStation = placeName(toJson, from, to),
                arrivalTime = arrival,
                isWalk = true,
            )
        }
        val scheduledDeparture = instant(leg, "scheduledStartTime") ?: departure
        val scheduledArrival = instant(leg, "scheduledEndTime") ?: arrival
        val realTime = leg.optBoolean("realTime", false)
        val stopsJson = leg.optJSONArray("intermediateStops") ?: JSONArray()
        return TripLeg(
            line = lineName(leg),
            direction = leg.optString("headsign").takeIf { it.isNotBlank() }
                ?: leg.optJSONObject("tripTo")?.optString("name")?.takeIf { it.isNotBlank() },
            departureStation = placeName(fromJson, from, to),
            departureTime = scheduledDeparture,
            arrivalStation = placeName(toJson, from, to),
            arrivalTime = scheduledArrival,
            intermediateStops = (0 until stopsJson.length()).map { s ->
                val stop = stopsJson.getJSONObject(s)
                TripStop(
                    name = stop.optString("name", "?"),
                    arrivalTime = instant(stop, "scheduledArrival") ?: instant(stop, "arrival"),
                    departureTime = instant(stop, "scheduledDeparture") ?: instant(stop, "departure"),
                )
            },
            departureDelayMinutes = if (realTime) Duration.between(scheduledDeparture, departure).toMinutes().toInt() else null,
            arrivalDelayMinutes = if (realTime) Duration.between(scheduledArrival, arrival).toMinutes().toInt() else null,
        )
    }

    private fun lineName(leg: JSONObject): String? =
        listOf("displayName", "routeShortName", "tripShortName")
            .map { leg.optString(it) }
            .firstOrNull { it.isNotBlank() }

    /** MOTIS names the ends of a coordinate-based trip "START" / "END". */
    private fun placeName(place: JSONObject, from: TransitPlace, to: TransitPlace): String =
        when (val name = place.optString("name")) {
            "START" -> from.name
            "END" -> to.name
            "" -> "?"
            else -> name
        }

    internal fun parseGeocode(body: String): List<StationSuggestion> {
        val matches = JSONArray(body)
        return (0 until matches.length()).mapNotNull { i ->
            val match = matches.getJSONObject(i)
            if (match.optString("type") != "STOP") return@mapNotNull null
            StationSuggestion(
                backendId = id,
                backendName = displayName,
                id = match.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                name = match.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                lat = match.optDouble("lat").takeUnless { it.isNaN() },
                lon = match.optDouble("lon").takeUnless { it.isNaN() },
            )
        }
    }

    private fun instant(obj: JSONObject, key: String): Instant? =
        obj.optString(key).takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    companion object {
        /** Where Transitous' data comes from; must be linked wherever its results are shown. */
        const val SOURCES_URL = "https://transitous.org/sources/"

        private const val METERS_PER_DEGREE = 111_320.0

        private fun coord(value: Double) = String.format(Locale.ROOT, "%.6f", value)

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        /** MOTIS transit modes for one of ours (MOTIS' `RAIL` also includes subways, so avoid it). */
        internal fun motisModesFor(mode: TransitMode): List<String> = when (mode) {
            TransitMode.HIGH_SPEED_TRAIN -> listOf("HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL")
            TransitMode.REGIONAL_TRAIN -> listOf("REGIONAL_RAIL")
            TransitMode.SUBURBAN_TRAIN -> listOf("SUBURBAN")
            TransitMode.SUBWAY -> listOf("SUBWAY")
            TransitMode.TRAM -> listOf("TRAM")
            TransitMode.BUS -> listOf("BUS", "COACH")
            TransitMode.FERRY -> listOf("FERRY")
            TransitMode.CABLECAR -> listOf("FUNICULAR", "AERIAL_LIFT")
            TransitMode.ON_DEMAND -> emptyList()
        }

        internal fun motisModeToTransitMode(mode: String): TransitMode? = when (mode) {
            "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL" -> TransitMode.HIGH_SPEED_TRAIN
            "RAIL", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL" -> TransitMode.REGIONAL_TRAIN
            "SUBURBAN" -> TransitMode.SUBURBAN_TRAIN
            "SUBWAY", "METRO" -> TransitMode.SUBWAY
            "TRAM" -> TransitMode.TRAM
            "BUS", "COACH" -> TransitMode.BUS
            "FERRY" -> TransitMode.FERRY
            "FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT", "CABLE_CAR" -> TransitMode.CABLECAR
            "ODM", "FLEX" -> TransitMode.ON_DEMAND
            else -> null
        }

        private fun transitModesOf(modes: JSONArray?): Set<TransitMode> =
            if (modes == null) emptySet()
            else (0 until modes.length()).mapNotNullTo(LinkedHashSet()) {
                motisModeToTransitMode(modes.getString(it))
            }

        private fun defaultGet(url: String, userAgent: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException("Transitous: HTTP $code")
                }
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
