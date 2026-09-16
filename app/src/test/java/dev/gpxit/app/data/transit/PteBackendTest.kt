package dev.gpxit.app.data.transit

import de.schildbach.pte.NetworkId
import de.schildbach.pte.NetworkProvider
import de.schildbach.pte.dto.Line
import de.schildbach.pte.dto.Location
import de.schildbach.pte.dto.LocationType
import de.schildbach.pte.dto.Point
import de.schildbach.pte.dto.Product
import de.schildbach.pte.dto.QueryTripsContext
import de.schildbach.pte.dto.QueryTripsResult
import de.schildbach.pte.dto.ResultHeader
import de.schildbach.pte.dto.Stop
import de.schildbach.pte.dto.Trip
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals

class PteBackendTest {

    private val departure = Instant.parse("2026-09-18T07:00:00Z")

    /** A provider that answers every trip query with [trip]. */
    private fun providerReturning(trip: Trip): NetworkProvider =
        Proxy.newProxyInstance(
            NetworkProvider::class.java.classLoader,
            arrayOf(NetworkProvider::class.java),
        ) { _, method, _ ->
            check(method.name == "queryTrips") { "unexpected call ${method.name}" }
            QueryTripsResult(
                ResultHeader(NetworkId.DB, "test"), null, trip.from, null, trip.to, NoMoreTrips, listOf(trip)
            )
        } as NetworkProvider

    private object NoMoreTrips : QueryTripsContext {
        override fun canQueryLater() = false
        override fun canQueryEarlier() = false
    }

    @Test fun `trip mapping from a coordinate`() = runBlocking {
        // DB echoes a coordinate endpoint as an address named after the coordinate
        val start = Location(LocationType.ADDRESS, null, Point.fromDouble(44.756, 5.365), "44.756000", "5.365000")
        val die = Location(LocationType.STATION, "8702522", null, "Die")
        val valence = Location(LocationType.STATION, "8700056", null, "Valence Ville")
        val walk = Trip.Individual(
            Trip.Individual.Type.WALK,
            start, Date.from(departure.plusSeconds(600)),
            die, Date.from(departure.plusSeconds(900)),
            null, 300,
        )
        val train = Trip.Public(
            Line("ter", "sncf", Product.REGIONAL_TRAIN, "TER 88920"),
            valence,
            Stop(die, null, null, null, null,
                Date.from(departure.plusSeconds(1200)), Date.from(departure.plusSeconds(1380)), null, null),
            Stop(valence, Date.from(departure.plusSeconds(4800)), Date.from(departure.plusSeconds(4920)),
                null, null, null, null, null, null),
            emptyList(), null, null,
        )
        val trip = Trip("¶HKI¶trip-context", start, valence, listOf(walk, train), null, null, 0)
        val backend = PteBackend("db", "Deutsche Bahn", canRouteFromCoordinates = true) {
            providerReturning(trip)
        }

        val connections = backend.queryConnections(
            TransitPlace.Coordinate(44.756, 5.365, "Field near Die"),
            TransitPlace.Station("8700056", "Valence Ville"),
            departure,
            TransitMode.TRAINS,
        )

        val connection = connections.single()
        // not the Trip's own id
        assertEquals("db", connection.backendId)
        assertEquals("Walk", connection.line)
        assertEquals("Field near Die", connection.legs[0].departureStation)
        assertEquals("Die", connection.legs[0].arrivalStation)
        assertEquals("TER 88920", connection.legs[1].line)
        assertEquals(departure.plusSeconds(1200), connection.legs[1].departureTime)
        // 3 and 2 minutes late
        assertEquals(3, connection.legs[1].departureDelayMinutes)
        assertEquals(2, connection.legs[1].arrivalDelayMinutes)
    }
}
