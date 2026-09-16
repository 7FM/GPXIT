package dev.gpxit.app.data.transit

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Backend selection against the vendored KPublicTransport coverage data. */
class TransitBackendRegistryTest {

    private val coverage = CoverageArea.parseAll(File("src/main/assets/transit/coverage.json").readText())

    private val registry = TransitBackendRegistry.fromCoverage(
        coverage,
        TransitBackendRegistry.backendFactories(userAgent = "test").map { (id, _) ->
            id to { error("backends are not created in this test") }
        },
    )

    private val withoutTransitous: (String) -> Boolean = { it != TransitBackendRegistry.TRANSITOUS }

    @Test fun `every backend has coverage`() {
        assertEquals(setOf("db", "dsb", "se", "tlem", "transitous"), coverage.keys)
    }

    @Test fun `Germany is DB only`() {
        // Darmstadt
        assertEquals(listOf("db"), registry.stationBackendIds(49.8728, 8.6512))
    }

    @Test fun `France asks DB and Transitous`() {
        // Lyon
        assertEquals(listOf("db", "transitous"), registry.stationBackendIds(45.7606, 4.8594))
        assertEquals(listOf("db"), registry.stationBackendIds(45.7606, 4.8594, withoutTransitous))
    }

    @Test fun `national providers where DB is weak`() {
        // Copenhagen: DSB has realtime there, DB only partial coverage
        assertEquals(listOf("dsb"), registry.stationBackendIds(55.6727, 12.5647))
        // London
        assertEquals(listOf("tlem"), registry.stationBackendIds(51.5308, -0.1238))
        // Stockholm: regular tier
        assertEquals(listOf("se", "transitous"), registry.stationBackendIds(59.3303, 18.0596))
    }

    @Test fun `nothing covers the middle of the Atlantic`() {
        assertEquals(emptyList(), registry.stationBackendIds(40.0, -40.0))
    }

    @Test fun `domestic German trip stops at DB`() {
        // Darmstadt -> Frankfurt
        assertEquals(listOf("db"), registry.journeyBackendIds(49.8728, 8.6512, 50.1071, 8.6632))
    }

    @Test fun `France to Germany asks DB and Transitous`() {
        // Lyon -> Frankfurt
        assertEquals(
            listOf("db", "transitous"),
            registry.journeyBackendIds(45.7606, 4.8594, 50.1071, 8.6632)
        )
        assertEquals(
            listOf("db"),
            registry.journeyBackendIds(45.7606, 4.8594, 50.1071, 8.6632, withoutTransitous)
        )
    }

    @Test fun `Denmark to Germany asks both national networks`() {
        // Copenhagen -> Hamburg
        val ids = registry.journeyBackendIds(55.6727, 12.5647, 53.5530, 10.0067)
        assertTrue("db" in ids, ids.toString())
        assertTrue("dsb" in ids, ids.toString())
    }

    @Test fun `priority follows registration order`() {
        assertTrue(registry.priority("db") < registry.priority("transitous"))
        assertEquals(5, registry.priority("unknown"))
    }

    @Test fun `coverage area point test`() {
        val square = CoverageArea(listOf("XX"), listOf(doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)))
        assertTrue(square.covers(5.0, 5.0))
        assertFalse(square.covers(5.0, 11.0))
        assertFalse(square.isGlobal)
        assertTrue(CoverageArea(listOf("UN"), emptyList()).isGlobal)
        // No polygon to test against: assume covered, like KPublicTransport.
        assertTrue(CoverageArea(listOf("XX"), emptyList()).covers(80.0, 170.0))
    }
}
