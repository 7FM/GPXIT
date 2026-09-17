package dev.gpxit.app.data.poi

import dev.gpxit.app.domain.Poi
import dev.gpxit.app.domain.PoiType
import org.junit.Test
import kotlin.test.assertEquals

class PoiMergeTest {

    private fun row(osmType: Int, osmId: Long, name: String, region: String? = null) =
        DatasetPoi(osmType, osmId, Poi(osmId, PoiType.BAKERY, 47.59, 7.62, name, holidayRegion = region))

    @Test fun `a single dataset is returned as is`() {
        val rows = listOf(row(0, 1, "a"), row(0, 1, "a again"))
        assertEquals(listOf("a", "a again"), mergeDatasetPois(listOf(rows)).map { it.name })
    }

    @Test fun `POIs from two extracts are kept once`() {
        val germany = listOf(row(0, 1, "Weil"), row(1, 2, "Border shop", region = null))
        val switzerland = listOf(row(1, 2, "Border shop", region = "CH-BS"), row(0, 3, "Basel"))
        val merged = mergeDatasetPois(listOf(germany, switzerland))
        assertEquals(listOf("Weil", "Border shop", "Basel"), merged.map { it.name })
        // The copy that knows its holiday region wins.
        assertEquals("CH-BS", merged[1].holidayRegion)
    }

    @Test fun `the first copy with a region stays`() {
        val merged = mergeDatasetPois(
            listOf(listOf(row(0, 5, "x", "DE-BW")), listOf(row(0, 5, "x", "CH-BS")))
        )
        assertEquals(listOf("DE-BW"), merged.map { it.holidayRegion })
    }

    @Test fun `node and way with the same number are different objects`() {
        val merged = mergeDatasetPois(listOf(listOf(row(0, 7, "node")), listOf(row(1, 7, "way"))))
        assertEquals(listOf("node", "way"), merged.map { it.name })
    }

    @Test fun `POIs without OSM ids are all kept`() {
        val legacy = listOf(row(0, 0, "old a"), row(0, 0, "old b"))
        val current = listOf(row(0, 9, "new"))
        assertEquals(listOf("new", "old a", "old b"), mergeDatasetPois(listOf(legacy, current)).map { it.name })
    }
}
