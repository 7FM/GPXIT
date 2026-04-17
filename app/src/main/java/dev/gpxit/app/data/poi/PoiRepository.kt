package dev.gpxit.app.data.poi

import android.util.Log
import dev.gpxit.app.domain.Poi
import dev.gpxit.app.domain.PoiType
import dev.gpxit.app.domain.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Queries the Overpass API (OpenStreetMap) for POIs.
 *
 * Two modes:
 *  * [fetchPois] — bbox, used by the live map-view fallback.
 *  * [fetchPoisForRoute] — route corridor, used by the import-time prefetch.
 *    Builds a single Overpass `around:` query that unions tight circles
 *    around every sample point, capturing a continuous corridor along the
 *    route instead of a sparse grid of bboxes.
 *
 * The tag filters are deliberately broad to catch the variety of real-world
 * OSM tagging — many small shops are tagged as `kiosk`, `deli`, `greengrocer`
 * etc. rather than `supermarket`, and drinking-water sources sometimes use
 * `man_made=water_tap` instead of `amenity=drinking_water`.
 */
class PoiRepository {

    private val endpoint = "https://overpass-api.de/api/interpreter"

    // Keep these in sync with the parser below. Using a single source of truth.
    private companion object {
        const val GROCERY_SHOP_REGEX =
            "^(supermarket|convenience|grocery|general|kiosk|deli|greengrocer|food|farm|butcher|organic|health_food|frozen_food)$"
        const val BAKERY_SHOP_REGEX = "^(bakery|pastry)$"

        val GROCERY_SHOP_VALUES = setOf(
            "supermarket", "convenience", "grocery", "general", "kiosk",
            "deli", "greengrocer", "food", "farm", "butcher",
            "organic", "health_food", "frozen_food"
        )
        val BAKERY_SHOP_VALUES = setOf("bakery", "pastry")
    }

    /** Bbox fetch — used by the live viewport fallback on the map. */
    suspend fun fetchPois(
        types: Set<PoiType>,
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double
    ): List<Poi> = withContext(Dispatchers.IO) {
        if (types.isEmpty()) return@withContext emptyList()
        if (latNorth - latSouth <= 0.0 || lonEast - lonWest <= 0.0) return@withContext emptyList()
        if ((latNorth - latSouth) > 1.0 || (lonEast - lonWest) > 1.0) return@withContext emptyList()

        val bbox = "$latSouth,$lonWest,$latNorth,$lonEast"
        val clauses = buildString { appendTypeClauses(types) { "($bbox)" } }
        val query = "[out:json][timeout:45];($clauses);out center;"
        parseResponse(postOverpassQuery(query) ?: return@withContext emptyList())
    }

    /**
     * Prefetch POIs covering an entire route corridor. Uses Overpass `around:`
     * clauses so the query area is a tight union of circles along the route
     * rather than a loose grid of bboxes — which means far better coverage
     * (no between-sample gaps on winding routes) and far less wasted data.
     *
     * @param corridorRadiusMeters how far off-route the corridor extends
     * @param sampleIntervalMeters route sampling pitch (samples much closer
     *   than `2 * corridorRadiusMeters` give continuous coverage)
     * @param maxPointsPerQuery cap per Overpass request so very long routes
     *   are split into a handful of polite requests instead of one giant one
     */
    suspend fun fetchPoisForRoute(
        points: List<RoutePoint>,
        types: Set<PoiType>,
        corridorRadiusMeters: Int = 2000,
        sampleIntervalMeters: Int = 1000,
        maxPointsPerQuery: Int = 400
    ): List<Poi> = withContext(Dispatchers.IO) {
        if (points.isEmpty() || types.isEmpty()) return@withContext emptyList()

        // Sample route points ~sampleIntervalMeters apart.
        val samples = ArrayList<RoutePoint>()
        var lastSampleDist = -sampleIntervalMeters.toDouble()
        for (pt in points) {
            if (pt.distanceFromStart - lastSampleDist >= sampleIntervalMeters) {
                samples += pt
                lastSampleDist = pt.distanceFromStart
            }
        }
        if (samples.lastOrNull() != points.last()) samples += points.last()
        if (samples.isEmpty()) return@withContext emptyList()

        val collected = LinkedHashMap<Pair<PoiType, Long>, Poi>()
        samples.chunked(maxPointsPerQuery).forEachIndexed { idx, chunk ->
            val around = buildString {
                append("around:")
                append(corridorRadiusMeters)
                for (p in chunk) {
                    append(',')
                    // Overpass accepts both '.' decimals and unlocalized.
                    append("%.6f".format(java.util.Locale.US, p.lat))
                    append(',')
                    append("%.6f".format(java.util.Locale.US, p.lon))
                }
            }
            val clauses = buildString { appendTypeClauses(types) { "($around)" } }
            val query = "[out:json][timeout:90];($clauses);out center;"
            val resp = postOverpassQuery(query) ?: run {
                if (idx < samples.chunked(maxPointsPerQuery).size - 1) delay(250)
                return@forEachIndexed
            }
            for (p in parseResponse(resp)) collected[p.type to p.id] = p
            if (idx < samples.chunked(maxPointsPerQuery).size - 1) delay(250)
        }
        collected.values.toList()
    }

    /**
     * Emit Overpass union-body clauses for [types]. The caller supplies the
     * suffix — `"(bbox)"` or `"(around:…)"` — so the same filters can drive
     * either query style.
     */
    private fun StringBuilder.appendTypeClauses(
        types: Set<PoiType>,
        suffix: () -> String
    ) {
        for (type in types) {
            when (type) {
                PoiType.GROCERY -> {
                    val s = suffix()
                    append("node[\"shop\"~\"$GROCERY_SHOP_REGEX\"]$s;")
                    append("way[\"shop\"~\"$GROCERY_SHOP_REGEX\"]$s;")
                }
                PoiType.BAKERY -> {
                    val s = suffix()
                    append("node[\"shop\"~\"$BAKERY_SHOP_REGEX\"]$s;")
                    append("way[\"shop\"~\"$BAKERY_SHOP_REGEX\"]$s;")
                }
                PoiType.WATER -> {
                    val s = suffix()
                    append("node[\"amenity\"=\"drinking_water\"]$s;")
                    append("node[\"drinking_water\"=\"yes\"]$s;")
                    append("node[\"man_made\"=\"water_tap\"][\"drinking_water\"!=\"no\"]$s;")
                    append("node[\"amenity\"=\"water_point\"]$s;")
                }
                PoiType.TOILET -> {
                    val s = suffix()
                    append("node[\"amenity\"=\"toilets\"]$s;")
                    append("way[\"amenity\"=\"toilets\"]$s;")
                }
            }
        }
    }

    private fun postOverpassQuery(query: String): String? {
        val body = "data=" + URLEncoder.encode(query, "UTF-8")
        val conn = try {
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("User-Agent", "GPXIT/1.0 (Android cycling app)")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        } catch (e: Exception) {
            Log.w("PoiRepository", "connect failed: ${e.message}")
            return null
        }

        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) {
                Log.w("PoiRepository", "overpass HTTP ${conn.responseCode}")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w("PoiRepository", "fetch failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(json: String): List<Poi> {
        return try {
            val root = JSONObject(json)
            val elements = root.optJSONArray("elements") ?: return emptyList()
            val out = ArrayList<Poi>(elements.length())
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val elType = el.optString("type")
                val (lat, lon) = when (elType) {
                    "node" -> el.optDouble("lat", Double.NaN) to el.optDouble("lon", Double.NaN)
                    "way", "relation" -> {
                        val center = el.optJSONObject("center") ?: continue
                        center.optDouble("lat", Double.NaN) to center.optDouble("lon", Double.NaN)
                    }
                    else -> continue
                }
                if (lat.isNaN() || lon.isNaN()) continue
                val tags = el.optJSONObject("tags") ?: JSONObject()
                val shop = tags.optString("shop", "")
                val amenity = tags.optString("amenity", "")
                val manMade = tags.optString("man_made", "")
                val drinking = tags.optString("drinking_water", "")
                val type = when {
                    shop in BAKERY_SHOP_VALUES -> PoiType.BAKERY
                    shop in GROCERY_SHOP_VALUES -> PoiType.GROCERY
                    amenity == "drinking_water" ||
                        amenity == "water_point" ||
                        drinking == "yes" ||
                        (manMade == "water_tap" && drinking != "no") -> PoiType.WATER
                    amenity == "toilets" -> PoiType.TOILET
                    else -> continue
                }
                out += Poi(
                    id = el.optLong("id"),
                    type = type,
                    lat = lat,
                    lon = lon,
                    name = tags.optString("name").takeIf { it.isNotBlank() }
                )
            }
            out
        } catch (e: Exception) {
            Log.w("PoiRepository", "parse failed: ${e.message}")
            emptyList()
        }
    }
}
