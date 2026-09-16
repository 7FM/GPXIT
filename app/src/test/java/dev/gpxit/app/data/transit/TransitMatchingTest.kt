package dev.gpxit.app.data.transit

import dev.gpxit.app.domain.StationCandidate
import dev.gpxit.app.domain.TrainConnection
import dev.gpxit.app.domain.TripLeg
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitMatchingTest {

    private val priority: (String) -> Int = { listOf("db", "dsb", "transitous").indexOf(it) }

    @Test fun `same station from two backends is merged, higher priority wins`() {
        val db = station("db", "8700023", "Strasbourg", 48.5853, 7.7341, rail = true)
        val transitous = station("transitous", "de-DELFI_fr:24067:1284", "Strasbourg Bahnhof", 48.5854, 7.7340, rail = true)
        val result = clusterStations(listOf(transitous, db), priority = priority)
        assertEquals(1, result.size)
        assertEquals("db", result.single().backendId)
        assertEquals(mapOf("transitous" to "de-DELFI_fr:24067:1284"), result.single().alternateIds)
    }

    @Test fun `rail stations with different names but close together are the same`() {
        // DB calls it "Straßburg", Transitous "Strasbourg (FR)", 150 m apart
        val db = station("db", "1", "Straßburg Bahnhof", 48.5850, 7.7340, rail = true)
        val transitous = station("transitous", "x", "Strasbourg (FR)", 48.5863, 7.7345, rail = true)
        assertTrue(isSameStation(db, transitous))
    }

    @Test fun `different stops of different backends stay apart`() {
        val db = station("db", "1", "Schlossplatz", 49.8730, 8.6510)
        val transitous = station("transitous", "x", "Luisenplatz", 49.8736, 8.6517)
        assertFalse(isSameStation(db, transitous))
        assertEquals(2, clusterStations(listOf(db, transitous), priority = priority).size)
    }

    @Test fun `same name far apart is not the same station`() {
        val db = station("db", "1", "Neustadt", 49.35, 8.14, rail = true)
        val transitous = station("transitous", "x", "Neustadt", 49.36, 8.14, rail = true)
        assertFalse(isSameStation(db, transitous))
    }

    @Test fun `names match without diacritics and punctuation`() {
        assertEquals(normalizeStationName("Lyon Part-Dieu"), normalizeStationName("lyon part dieu"))
        assertEquals(normalizeStationName("Zürich HB"), normalizeStationName("Zurich HB"))
        val db = station("db", "1", "Zürich HB", 47.3779, 8.5403)
        val transitous = station("transitous", "x", "Zurich HB", 47.3785, 8.5403)
        assertTrue(isSameStation(db, transitous))
    }

    @Test fun `known alternate id decides`() {
        val merged = station("db", "1", "Strasbourg", 48.5853, 7.7341, rail = true)
            .copy(alternateIds = mapOf("transitous" to "x"))
        assertTrue(isSameStation(merged, station("transitous", "x", "Somewhere", 48.59, 7.74)))
        // A different Transitous stop right next to it is not the merged one
        assertFalse(isSameStation(merged, station("transitous", "y", "Gare Centrale", 48.5854, 7.7342, rail = true)))
        // ...unless it's a sibling by name
        assertTrue(isSameStation(merged, station("transitous", "z", "Strasbourg Gare", 48.5854, 7.7342)))
    }

    @Test fun `same-backend clustering is unchanged`() {
        val hbf = station("db", "m-1", "Mannheim Hbf", 49.4793, 8.4693)
        val platform = station("db", "m-2", "Mannheim Hbf Gleis 3", 49.4794, 8.4694)
        val nearbyOther = station("db", "m-3", "Mannheim ZOB", 49.4794, 8.4694)
        val result = clusterStations(listOf(hbf, platform, nearbyOther))
        assertEquals(listOf("m-1", "m-3"), result.map { it.id })
        assertTrue(result.first().alternateIds.isEmpty())
    }

    @Test fun `duplicate connections are merged, the one with delays wins`() {
        val db = connection("db", "ICE 592", "2026-09-18T09:00:00Z", "2026-09-18T10:00:00Z", delay = 4)
        val transitous = connection("transitous", "ICE592", "2026-09-18T09:00:30Z", "2026-09-18T10:00:00Z")
        val other = connection("transitous", "RE 4", "2026-09-18T09:05:00Z", "2026-09-18T10:30:00Z")
        val merged = mergeConnections(listOf(listOf(db), listOf(transitous, other)), priority)
        assertEquals(listOf("ICE 592", "RE 4"), merged.map { it.line })
        assertEquals("db", merged.first().backendId)

        val mergedOtherOrder = mergeConnections(listOf(listOf(transitous), listOf(db)), priority)
        assertEquals(listOf("db"), mergedOtherOrder.map { it.backendId })
    }

    @Test fun `connections with different trains at the same time stay apart`() {
        val a = connection("db", "ICE 592", "2026-09-18T09:00:00Z", "2026-09-18T10:00:00Z")
        val b = connection("transitous", "TGV 9577", "2026-09-18T09:00:00Z", "2026-09-18T10:00:00Z", fromStation = "Karlsruhe")
        assertFalse(isSameConnection(a, b))
        assertEquals(2, mergeConnections(listOf(listOf(a), listOf(b)), priority).size)
    }

    @Test fun `transit modes from persisted names`() {
        assertEquals(setOf(TransitMode.BUS, TransitMode.TRAM), TransitMode.fromNames(listOf("BUS", "TRAM", "ROCKET")))
        assertEquals(setOf(TransitMode.REGIONAL_TRAIN, TransitMode.SUBURBAN_TRAIN), TransitMode.fromNames(emptyList()))
    }

    private fun station(
        backend: String,
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        rail: Boolean = false,
    ) = StationCandidate(
        id = id,
        name = name,
        lat = lat,
        lon = lon,
        distanceAlongRouteMeters = 0.0,
        distanceFromRouteMeters = 0.0,
        products = if (rail) setOf("REGIONAL_TRAIN") else setOf("BUS"),
        backendId = backend,
    )

    private fun connection(
        backend: String,
        line: String,
        departure: String,
        arrival: String,
        delay: Int? = null,
        fromStation: String = "Mannheim Hbf",
    ): TrainConnection {
        val dep = Instant.parse(departure)
        val arr = Instant.parse(arrival)
        return TrainConnection(
            departureTime = dep,
            arrivalTime = arr,
            line = line,
            numChanges = 0,
            duration = Duration.between(dep, arr),
            legs = listOf(
                TripLeg(
                    line = line,
                    direction = null,
                    departureStation = fromStation,
                    departureTime = dep,
                    arrivalStation = "Frankfurt(Main)Hbf",
                    arrivalTime = arr,
                    departureDelayMinutes = delay,
                )
            ),
            backendId = backend,
        )
    }
}
