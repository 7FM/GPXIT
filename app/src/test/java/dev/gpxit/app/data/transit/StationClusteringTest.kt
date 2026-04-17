package dev.gpxit.app.data.transit

import dev.gpxit.app.domain.StationCandidate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StationClusteringTest {

    @Test fun `empty and singleton lists pass through unchanged`() {
        assertEquals(0, clusterStations(emptyList()).size)
        val only = listOf(station("A", "Foo Hbf", 49.0, 8.0))
        assertEquals(only, clusterStations(only))
    }

    @Test fun `platform-child with prefix name and close location gets absorbed`() {
        // Mannheim Hbf + Mannheim Hbf Gleis 3 sit ~50m apart → cluster.
        val hbf = station("m-1", "Mannheim Hbf", 49.4793, 8.4693)
        val platform = station("m-2", "Mannheim Hbf Gleis 3", 49.4794, 8.4694)
        val result = clusterStations(listOf(hbf, platform))
        assertEquals(1, result.size)
        // Shortest name wins, which is the Hbf itself.
        assertEquals("Mannheim Hbf", result.first().name)
    }

    @Test fun `unrelated names at close proximity are kept separately`() {
        // Two different stops ~20m apart that don't share a name prefix.
        val a = station("a", "Schlossplatz", 49.87300, 8.65100)
        val b = station("b", "Luisenplatz", 49.87315, 8.65110)
        val result = clusterStations(listOf(a, b))
        assertEquals(2, result.size)
    }

    @Test fun `related names but far apart are kept separately`() {
        // Both "Mannheim" but on opposite ends of town (~5 km apart).
        val hbf = station("a", "Mannheim Hbf", 49.4793, 8.4693)
        val friedrich = station("b", "Mannheim Friedrichsfeld", 49.4820, 8.5300)
        val result = clusterStations(listOf(hbf, friedrich))
        assertEquals(2, result.size)
    }

    @Test fun `parenthetical suffix is absorbed when colocated`() {
        val hbf = station("a", "Heidelberg Hbf", 49.4036, 8.6758)
        val vorplatz = station("b", "Heidelberg Hbf (Vorplatz)", 49.4037, 8.6759)
        val result = clusterStations(listOf(hbf, vorplatz))
        assertEquals(1, result.size)
        assertEquals("Heidelberg Hbf", result.first().name)
    }

    @Test fun `comma city suffix is absorbed when colocated`() {
        val a = station("a", "Hauptbahnhof", 49.87, 8.65)
        val b = station("b", "Hauptbahnhof, Darmstadt", 49.871, 8.651)
        val result = clusterStations(listOf(a, b))
        assertEquals(1, result.size)
        assertEquals("Hauptbahnhof", result.first().name)
    }

    @Test fun `namesRelated recognises word-boundary prefixes only`() {
        assertTrue(namesRelated("mannheim hbf", "mannheim hbf gleis 3"))
        assertTrue(namesRelated("heidelberg hbf", "heidelberg hbf"))
        // Prefix but not at a word boundary → false.
        assertFalse(namesRelated("mann", "mannheim"))
    }

    @Test fun `stationNameBase strips parentheticals and city suffix and lowercases`() {
        assertEquals("mannheim hbf", "Mannheim Hbf (Vorplatz)".stationNameBase())
        assertEquals("hauptbahnhof", "Hauptbahnhof, Darmstadt".stationNameBase())
        assertEquals("heidelberg hbf", "  Heidelberg Hbf  ".stationNameBase())
    }

    private fun station(id: String, name: String, lat: Double, lon: Double) = StationCandidate(
        id = id,
        name = name,
        lat = lat,
        lon = lon,
        distanceAlongRouteMeters = 0.0,
        distanceFromRouteMeters = 0.0
    )
}
