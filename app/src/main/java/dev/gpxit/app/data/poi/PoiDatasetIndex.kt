package dev.gpxit.app.data.poi

import dev.gpxit.app.data.transit.CoverageArea
import dev.gpxit.app.domain.RoutePoint
import org.json.JSONObject

/** One downloadable POI dataset (a Geofabrik extract), as listed in `pois-index.json`. */
data class PoiDataset(
    val id: String,
    val name: String,
    /**
     * ISO 3166-1 codes of the countries covered, main country first; an
     * ISO 3166-2 code (`US-CA`) where the extract is part of a country.
     */
    val countries: List<String>,
    /** Asset name next to the index. */
    val file: String,
    /** Download (gzip) size. */
    val sizeBytes: Long,
    val dbSizeBytes: Long,
    val sha256: String,
    val builtAt: String,
    val poiCount: Int,
    /** The extract's outline, which runs slightly outside the borders. */
    val outline: CoverageArea,
) {
    fun covers(lat: Double, lon: Double): Boolean = outline.hasArea && outline.covers(lat, lon)
}

/**
 * `pois-index.json` from the `poi-data` release, written by
 * `scripts/poi_index.py`. Fields are only ever added; a format the app
 * can't read would be published under a different name.
 */
class PoiDatasetIndex(
    val generatedAt: String?,
    val datasets: List<PoiDataset>,
) {
    fun byId(id: String): PoiDataset? = datasets.firstOrNull { it.id == id }

    /** The dataset of [countryCode] (ISO 3166-1), preferring one whose main country it is. */
    fun forCountry(countryCode: String): PoiDataset? {
        val code = countryCode.uppercase()
        return datasets.firstOrNull { it.countries.firstOrNull() == code }
            ?: datasets.firstOrNull { code in it.countries }
    }

    /** The dataset around a place, if any; the smallest one if outlines overlap. */
    fun forLocation(lat: Double, lon: Double): PoiDataset? =
        datasets.filter { it.covers(lat, lon) }.minByOrNull { it.sizeBytes }

    /**
     * The datasets [points] lie in, in route order. Where outlines overlap
     * (near borders), a dataset from [preferred] (the user's selection)
     * counts as covering the point; the remaining points get as few
     * datasets as possible. Points outside every dataset are ignored.
     */
    fun datasetsFor(points: List<Pair<Double, Double>>, preferred: Set<String>): List<PoiDataset> {
        val chosen = LinkedHashMap<String, Int>() // id -> first point index
        val uncovered = ArrayList<Pair<Int, List<PoiDataset>>>()
        points.forEachIndexed { i, (lat, lon) ->
            val covering = datasets.filter { it.covers(lat, lon) }
            if (covering.isEmpty()) return@forEachIndexed
            val own = covering.firstOrNull { it.id in preferred }
            if (own != null) {
                chosen.putIfAbsent(own.id, i)
            } else {
                uncovered += i to covering
            }
        }
        // Greedy set cover: the dataset covering the most remaining points first.
        while (uncovered.isNotEmpty()) {
            val best = uncovered.flatMap { it.second }
                .groupingBy { it.id }
                .eachCount()
                .maxBy { it.value }
                .key
            uncovered.filter { (_, covering) -> covering.any { it.id == best } }
                .minOf { it.first }
                .let { chosen.putIfAbsent(best, it) }
            uncovered.removeAll { (_, covering) -> covering.any { it.id == best } }
        }
        return chosen.entries.sortedBy { it.value }.mapNotNull { byId(it.key) }
    }

    companion object {
        fun parse(json: String): PoiDatasetIndex {
            val root = JSONObject(json)
            val list = root.getJSONArray("datasets")
            val datasets = (0 until list.length()).map { i ->
                val d = list.getJSONObject(i)
                val outline = d.optJSONObject("outline")
                    ?.let { CoverageArea.parse(it) }
                    ?: CoverageArea(emptyList(), emptyList())
                PoiDataset(
                    id = d.getString("id"),
                    name = d.getString("name"),
                    countries = outline.regions,
                    file = d.getString("file"),
                    sizeBytes = d.getLong("size"),
                    dbSizeBytes = d.optLong("db_size"),
                    sha256 = d.getString("sha256"),
                    builtAt = d.getString("built_at"),
                    poiCount = d.optInt("pois"),
                    outline = outline,
                )
            }
            return PoiDatasetIndex(root.optString("generated_at").ifEmpty { null }, datasets)
        }

        /** A point about every [intervalMeters] along the route, plus its end. */
        fun routeSamples(points: List<RoutePoint>, intervalMeters: Double = 1000.0): List<Pair<Double, Double>> {
            if (points.isEmpty()) return emptyList()
            val samples = ArrayList<Pair<Double, Double>>()
            var next = 0.0
            for (p in points) {
                if (p.distanceFromStart >= next) {
                    samples += p.lat to p.lon
                    next = p.distanceFromStart + intervalMeters
                }
            }
            val last = points.last()
            if (samples.last() != last.lat to last.lon) samples += last.lat to last.lon
            return samples
        }
    }
}
