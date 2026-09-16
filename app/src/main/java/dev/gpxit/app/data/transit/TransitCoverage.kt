package dev.gpxit.app.data.transit

import org.json.JSONArray
import org.json.JSONObject

/** How well a backend covers an area, best first (KPublicTransport's coverage tiers). */
enum class CoverageTier(val key: String) {
    /** Full timetable with live data. */
    REALTIME("realtime"),

    /** Full timetable. */
    REGULAR("regular"),

    /** At least some services. */
    ANY("any"),
}

/**
 * One coverage tier of one backend: ISO 3166 codes plus the outer rings of the
 * area polygons, as vendored from KPublicTransport by
 * `scripts/import_kpt_coverage.py`.
 */
class CoverageArea(
    val regions: List<String>,
    /** Rings as `[lon0, lat0, lon1, lat1, …]`. */
    private val polygons: List<DoubleArray>,
) {
    private val minLon = polygons.minOfOrNull { ring -> ring.filterIndexed { i, _ -> i % 2 == 0 }.min() }
    private val maxLon = polygons.maxOfOrNull { ring -> ring.filterIndexed { i, _ -> i % 2 == 0 }.max() }
    private val minLat = polygons.minOfOrNull { ring -> ring.filterIndexed { i, _ -> i % 2 == 1 }.min() }
    private val maxLat = polygons.maxOfOrNull { ring -> ring.filterIndexed { i, _ -> i % 2 == 1 }.max() }

    /** Worldwide coverage, as KPublicTransport defines it. */
    val isGlobal: Boolean =
        regions == listOf("UN") ||
            (minLon == -180.0 && maxLon == 180.0 && minLat == -90.0 && maxLat == 90.0)

    fun covers(lat: Double, lon: Double): Boolean {
        // Like KPublicTransport: with no area to check a coordinate against,
        // assume the backend can help.
        if (polygons.isEmpty()) return true
        if (lon < minLon!! || lon > maxLon!! || lat < minLat!! || lat > maxLat!!) return false
        return polygons.any { windingNumber(it, lon, lat) != 0 }
    }

    private fun windingNumber(ring: DoubleArray, x: Double, y: Double): Int {
        var winding = 0
        val n = ring.size / 2
        for (i in 0 until n) {
            val x1 = ring[2 * i]
            val y1 = ring[2 * i + 1]
            val x2 = ring[2 * ((i + 1) % n)]
            val y2 = ring[2 * ((i + 1) % n) + 1]
            val side = (x2 - x1) * (y - y1) - (x - x1) * (y2 - y1)
            if (y1 <= y) {
                if (y2 > y && side > 0) winding++
            } else {
                if (y2 <= y && side < 0) winding--
            }
        }
        return winding
    }

    companion object {
        /** Parses `assets/transit/coverage.json` into backend id → tier → area. */
        fun parseAll(json: String): Map<String, Map<CoverageTier, CoverageArea>> {
            val backends = JSONObject(json).getJSONObject("backends")
            val result = LinkedHashMap<String, Map<CoverageTier, CoverageArea>>()
            for (backendId in backends.keys()) {
                val coverage = backends.getJSONObject(backendId).getJSONObject("coverage")
                result[backendId] = CoverageTier.entries
                    .filter { coverage.has(it.key) }
                    .associateWith { parseArea(coverage.getJSONObject(it.key)) }
            }
            return result
        }

        private fun parseArea(obj: JSONObject): CoverageArea {
            val regionsJson = obj.optJSONArray("regions") ?: JSONArray()
            val regions = (0 until regionsJson.length()).map { regionsJson.getString(it) }
            val polygonsJson = obj.optJSONArray("polygons") ?: JSONArray()
            val polygons = (0 until polygonsJson.length()).map { p ->
                val ring = polygonsJson.getJSONArray(p)
                DoubleArray(ring.length() * 2) { i ->
                    ring.getJSONArray(i / 2).getDouble(i % 2)
                }
            }
            return CoverageArea(regions, polygons)
        }
    }
}
