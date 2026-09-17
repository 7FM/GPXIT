package dev.gpxit.app.data.poi

import dev.gpxit.app.domain.Poi

/** A POI read from one dataset file, with the OSM object it came from. */
internal class DatasetPoi(val osmType: Int, val osmId: Long, val poi: Poi)

/**
 * Combines the POIs read from several dataset files. Neighbouring extracts
 * overlap slightly at the borders, so a POI there can come from two files:
 * keep one per OSM object, preferring the copy that knows its holiday
 * region (a neighbour's extract usually lacks that country's boundary).
 * Files built before OSM ids were exported have `osm_id` 0; their POIs are
 * kept as they are.
 */
internal fun mergeDatasetPois(perDataset: List<List<DatasetPoi>>): List<Poi> {
    if (perDataset.size == 1) return perDataset[0].map { it.poi }
    val byObject = LinkedHashMap<Pair<Int, Long>, Poi>()
    val withoutId = ArrayList<Poi>()
    for (rows in perDataset) {
        for (row in rows) {
            if (row.osmId == 0L) {
                withoutId += row.poi
                continue
            }
            val key = row.osmType to row.osmId
            val existing = byObject[key]
            if (existing == null || (existing.holidayRegion == null && row.poi.holidayRegion != null)) {
                byObject[key] = row.poi
            }
        }
    }
    return byObject.values + withoutId
}
