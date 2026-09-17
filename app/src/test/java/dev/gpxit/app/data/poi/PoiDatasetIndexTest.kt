package dev.gpxit.app.data.poi

import dev.gpxit.app.domain.RoutePoint
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoiDatasetIndexTest {

    /** Written by scripts/poi_index.py from real builds of five extracts. */
    private val real = PoiDatasetIndex.parse(File("src/test/resources/poi/pois-index.json").readText())

    /**
     * west: lon 0–10, east: lon 9–20 (overlapping west at 9–10), both lat
     * 40–50; north: lon 0–25, lat 49–60; state: part of country SS, far away.
     */
    private val synthetic = PoiDatasetIndex.parse(
        """
        {"version": 1, "generated_at": "2026-10-01T03:40:00Z", "datasets": [
          ${dataset("west", listOf("WW", "XX"), 5_000_000, 0.0, 40.0, 10.0, 50.0)},
          ${dataset("east", listOf("XX"), 3_000_000, 9.0, 40.0, 20.0, 50.0)},
          ${dataset("north", listOf("NN"), 8_000_000, 0.0, 49.0, 25.0, 60.0)},
          ${dataset("state", listOf("SS-AA"), 1_000_000, 100.0, 40.0, 110.0, 50.0)}
        ]}
        """
    )

    private fun dataset(
        id: String, countries: List<String>, size: Long,
        minLon: Double, minLat: Double, maxLon: Double, maxLat: Double,
    ) = """
        {"id": "$id", "name": "${id.replaceFirstChar { it.uppercase() }}", "file": "$id.db.gz",
         "size": $size, "db_size": ${size * 2}, "sha256": "00", "built_at": "2026-10-01T03:17:00Z",
         "pois": 100, "outline": {"regions": [${countries.joinToString { "\"$it\"" }}], "polygons": [[
           [$minLon, $minLat], [$maxLon, $minLat], [$maxLon, $maxLat], [$minLon, $maxLat], [$minLon, $minLat]
         ]]}}
    """

    private fun ids(datasets: List<PoiDataset>) = datasets.map { it.id }

    @Test fun `parses the index the build script writes`() {
        assertEquals(listOf("france", "germany", "liechtenstein", "luxembourg", "switzerland"), ids(real.datasets))
        val germany = real.byId("germany")!!
        assertEquals("Germany", germany.name)
        assertEquals(listOf("DE"), germany.countries)
        assertEquals("germany.db.gz", germany.file)
        assertTrue(germany.sizeBytes in 5_000_000..30_000_000)
        assertTrue(germany.dbSizeBytes > germany.sizeBytes)
        assertEquals(64, germany.sha256.length)
        assertTrue(germany.poiCount > 100_000)
        assertEquals(listOf("FR", "MC"), real.byId("france")!!.countries)
    }

    @Test fun `places in real outlines`() {
        assertEquals("germany", real.forLocation(48.5730, 7.8150)?.id) // Kehl
        assertEquals("france", real.forLocation(48.5840, 7.7450)?.id) // Strasbourg
        assertEquals("switzerland", real.forLocation(47.5470, 7.5890)?.id) // Basel
        assertEquals("luxembourg", real.forLocation(49.6116, 6.1319)?.id)
        assertEquals("liechtenstein", real.forLocation(47.1410, 9.5209)?.id) // Vaduz
        assertNull(real.forLocation(52.3676, 4.9041)) // Amsterdam: not in this index
    }

    @Test fun `route through three countries`() {
        // Kehl → Strasbourg → Colmar → Basel → Zürich
        val route = listOf(
            48.5730 to 7.8150, 48.5840 to 7.7450, 48.0790 to 7.3580,
            47.5470 to 7.5890, 47.3780 to 8.5400,
        )
        assertEquals(listOf("germany", "france", "switzerland"), ids(real.datasetsFor(route, preferred = setOf("germany"))))
        assertEquals(listOf("germany", "france", "switzerland"), ids(real.datasetsFor(route, preferred = emptySet())))
    }

    @Test fun `country lookup prefers the main country`() {
        assertEquals("east", synthetic.forCountry("XX")?.id)
        assertEquals("west", synthetic.forCountry("ww")?.id)
        assertNull(synthetic.forCountry("ZZ"))
        // An extract of one state isn't offered for the whole country.
        assertNull(synthetic.forCountry("SS"))
        assertEquals("germany", real.forCountry("DE")?.id)
        assertEquals("france", real.forCountry("MC")?.id)
    }

    @Test fun `overlapping outlines pick the smaller dataset for a place`() {
        assertEquals("east", synthetic.forLocation(45.0, 9.5)?.id)
        assertEquals("west", synthetic.forLocation(45.0, 5.0)?.id)
    }

    @Test fun `a selected dataset covers the overlap`() {
        val inOverlap = listOf(45.0 to 9.4, 45.5 to 9.6)
        assertEquals(listOf("west"), ids(synthetic.datasetsFor(inOverlap, preferred = setOf("west"))))
        assertEquals(listOf("east"), ids(synthetic.datasetsFor(inOverlap, preferred = setOf("east"))))
    }

    @Test fun `routes get as few datasets as possible, in route order`() {
        // Along lat 49.5 everything is in north, which beats west + east.
        val along = (1..24).map { 49.5 to it.toDouble() }
        assertEquals(listOf("north"), ids(synthetic.datasetsFor(along, preferred = emptySet())))
        // Unless west is kept already: then north only for the rest.
        assertEquals(listOf("west", "north"), ids(synthetic.datasetsFor(along, preferred = setOf("west"))))

        // From east into west: order of appearance.
        val westward = listOf(45.0 to 15.0, 45.0 to 9.5, 45.0 to 3.0)
        assertEquals(listOf("east", "west"), ids(synthetic.datasetsFor(westward, preferred = emptySet())))
    }

    @Test fun `points outside every dataset are ignored`() {
        assertEquals(emptyList(), synthetic.datasetsFor(listOf(10.0 to 5.0, 70.0 to 5.0), preferred = emptySet()))
        assertEquals(listOf("west"), ids(synthetic.datasetsFor(listOf(10.0 to 5.0, 45.0 to 5.0), preferred = emptySet())))
    }

    @Test fun `datasets without an outline cover nothing`() {
        val index = PoiDatasetIndex.parse(
            """{"datasets": [{"id": "x", "name": "X", "file": "x.db.gz", "size": 1, "sha256": "00", "built_at": "t"}]}"""
        )
        assertNull(index.generatedAt)
        assertEquals(emptyList(), index.datasetsFor(listOf(45.0 to 5.0), preferred = emptySet()))
        assertNull(index.forLocation(45.0, 5.0))
    }

    @Test fun `route samples`() {
        val points = (0..25).map { RoutePoint(lat = 45.0, lon = it / 100.0, distanceFromStart = it * 100.0) }
        val samples = PoiDatasetIndex.routeSamples(points, intervalMeters = 1000.0)
        assertEquals(listOf(0.0, 0.1, 0.2, 0.25), samples.map { it.second })
        assertEquals(emptyList(), PoiDatasetIndex.routeSamples(emptyList()))
        assertEquals(1, PoiDatasetIndex.routeSamples(points.take(1)).size)
    }
}
