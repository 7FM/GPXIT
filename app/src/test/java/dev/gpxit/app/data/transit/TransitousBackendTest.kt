package dev.gpxit.app.data.transit

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.URLDecoder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parsing of recorded Transitous responses (src/test/resources/transitous/). */
class TransitousBackendTest {

    private val requests = mutableListOf<Pair<String, String>>()

    private val backend = TransitousBackend(
        userAgent = "GPXIT/test (https://github.com/7FM/GPXIT)",
        httpGet = { url, userAgent ->
            requests += url to userAgent
            val file = when {
                "/api/v6/map/stops" in url -> "map_stops.json"
                "/api/v6/plan" in url -> "plan.json"
                "/api/v1/geocode" in url -> "geocode.json"
                else -> error("unexpected request $url")
            }
            javaClass.getResource("/transitous/$file")!!.readText()
        },
    )

    @Test fun `nearby stations`() = runBlocking {
        val stations = backend.findNearbyStations(48.5850, 7.7355, radiusMeters = 300, maxResults = 50)
        assertTrue(stations.isNotEmpty())
        assertTrue(stations.all { it.backendId == "transitous" && it.distanceFromRouteMeters <= 300 })
        assertEquals(stations.sortedBy { it.distanceFromRouteMeters }, stations)

        val sncb = stations.first { it.id == "be-sncb_8721202" }
        assertEquals("Strasbourg (FR)", sncb.name)
        assertEquals(setOf("REGIONAL_TRAIN"), sncb.products)
        assertTrue(stations.any { "TRAM" in it.products })

        val (url, userAgent) = requests.single()
        assertEquals("GPXIT/test (https://github.com/7FM/GPXIT)", userAgent)
        // min = lower right, max = upper left
        assertTrue(url.contains("min=48.582305,7.739574&max=48.587695,7.731426"), url)
    }

    @Test fun `nearby stations are limited`() = runBlocking {
        assertEquals(2, backend.findNearbyStations(48.5850, 7.7355, radiusMeters = 2000, maxResults = 2).size)
    }

    @Test fun `connections from coordinates`() = runBlocking {
        val from = TransitPlace.Coordinate(44.7531, 5.3703, "Die")
        val to = TransitPlace.Station("be-sncb_8721202", "Strasbourg")
        val departure = Instant.parse("2026-09-18T07:00:00Z")
        val connections = backend.queryConnections(
            from, to, departure, setOf(TransitMode.REGIONAL_TRAIN, TransitMode.HIGH_SPEED_TRAIN, TransitMode.BUS)
        )
        assertEquals(2, connections.size)

        val first = connections.first()
        assertEquals("transitous", first.backendId)
        assertEquals(Instant.parse("2026-09-18T07:42:00Z"), first.departureTime)
        assertEquals(Instant.parse("2026-09-18T17:36:00Z"), first.arrivalTime)
        assertEquals(3, first.numChanges)
        // Starts with a walk to the stop; MOTIS calls the start "START"
        assertEquals("Walk", first.line)
        assertTrue(first.legs.first().isWalk)
        assertEquals("Die", first.legs.first().departureStation)

        val bus = first.legs[1]
        assertEquals("TER 33158", bus.line)
        assertEquals("Die", bus.departureStation)
        assertEquals("Valence Gare Routière", bus.arrivalStation)
        assertEquals("Valence Gare Routière", bus.direction)
        assertEquals(2, bus.intermediateStops.size)
        assertNull(bus.departureDelayMinutes)

        // The fixture marks the last leg as 3 minutes late
        val tgv = first.legs.last()
        assertEquals("TGV INOUI 5480", tgv.line)
        assertEquals(Instant.parse("2026-09-18T15:29:00Z"), tgv.departureTime)
        assertEquals(3, tgv.departureDelayMinutes)
        assertEquals(3, tgv.arrivalDelayMinutes)

        val url = URLDecoder.decode(requests.single().first, "UTF-8")
        assertTrue(url.contains("fromPlace=44.753100,5.370300"), url)
        assertTrue(url.contains("toPlace=be-sncb_8721202"), url)
        assertTrue(url.contains("time=2026-09-18T07:00:00Z"), url)
        assertTrue(url.contains("transitModes=REGIONAL_RAIL,HIGHSPEED_RAIL,LONG_DISTANCE,NIGHT_RAIL,BUS,COACH"), url)
    }

    @Test fun `request time is sent in whole seconds`() = runBlocking {
        // MOTIS shifts the date when given microseconds
        backend.queryConnections(
            TransitPlace.Coordinate(44.7531, 5.3703, "Die"),
            TransitPlace.Station("be-sncb_8721202", "Strasbourg"),
            Instant.parse("2026-09-16T22:40:05.123456Z"),
            setOf(TransitMode.REGIONAL_TRAIN),
        )
        val url = URLDecoder.decode(requests.single().first, "UTF-8")
        assertTrue(url.contains("time=2026-09-16T22:40:05Z&"), url)
    }

    @Test fun `station search`() = runBlocking {
        val suggestions = backend.suggestStations("Strasbourg")
        assertEquals(5, suggestions.size)
        assertTrue(suggestions.all { it.backendId == "transitous" && it.backendName == "Transitous" })
        assertEquals("Strasbourg", suggestions.first().name)
        assertTrue(suggestions.first().lat != null && suggestions.first().lon != null)
        assertTrue(requests.single().first.contains("text=Strasbourg&type=STOP"))
    }

    @Test fun `mode mapping`() {
        assertEquals(TransitMode.HIGH_SPEED_TRAIN, TransitousBackend.motisModeToTransitMode("LONG_DISTANCE"))
        assertEquals(TransitMode.SUBWAY, TransitousBackend.motisModeToTransitMode("METRO"))
        assertNull(TransitousBackend.motisModeToTransitMode("WALK"))
        assertEquals(listOf("SUBURBAN"), TransitousBackend.motisModesFor(TransitMode.SUBURBAN_TRAIN))
        // MOTIS' RAIL includes subways, so it must never be sent for trains
        assertTrue(TransitMode.entries.none { "RAIL" in TransitousBackend.motisModesFor(it) })
    }
}
