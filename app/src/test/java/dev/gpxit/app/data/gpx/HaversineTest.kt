package dev.gpxit.app.data.gpx

import dev.gpxit.app.domain.RoutePoint
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HaversineTest {

    // Known fixtures — checked against online great-circle calculators.
    // Tolerances are 0.5% because we use the spherical model, not WGS84.

    @Test fun `same point is zero`() {
        assertEquals(0.0, haversineMeters(49.8728, 8.6512, 49.8728, 8.6512), 0.001)
    }

    @Test fun `darmstadt to mannheim hbf is roughly 45 km`() {
        // Darmstadt Hbf (49.8728, 8.6296) → Mannheim Hbf (49.4793, 8.4693)
        // — cross-checked against the online great-circle calculator at 45.25 km.
        val d = haversineMeters(49.8728, 8.6296, 49.4793, 8.4693)
        assertTrue(abs(d - 45_250) < 500, "expected ~45.25km, got $d m")
    }

    @Test fun `symmetric in argument order`() {
        val a = haversineMeters(49.0, 8.0, 50.0, 9.0)
        val b = haversineMeters(50.0, 9.0, 49.0, 8.0)
        assertEquals(a, b, 0.001)
    }

    @Test fun `one degree of latitude is roughly 111 km`() {
        val d = haversineMeters(49.0, 8.0, 50.0, 8.0)
        assertTrue(abs(d - 111_000) < 500, "expected ~111km, got $d m")
    }
}

class RoutePointQueryTest {

    private val route = listOf(
        // A straight eastward line at 49°N: 4 points, 0 → 1000 → 2000 → 3000 m.
        rp(49.0, 8.0000, 0.0),
        rp(49.0, 8.0137, 1000.0),
        rp(49.0, 8.0274, 2000.0),
        rp(49.0, 8.0411, 3000.0),
    )

    @Test fun `routePointAtDistance interpolates linearly between samples`() {
        val (lat, lon) = routePointAtDistance(route, 500.0)!!
        assertEquals(49.0, lat, 0.0001)
        // 500m is halfway between sample 0 and 1
        assertEquals((8.0 + 8.0137) / 2, lon, 0.0001)
    }

    @Test fun `routePointAtDistance clamps negative distance to start`() {
        val (lat, lon) = routePointAtDistance(route, -123.0)!!
        assertEquals(49.0, lat, 0.0)
        assertEquals(8.0, lon, 0.0)
    }

    @Test fun `routePointAtDistance clamps overshoot to end`() {
        val (lat, lon) = routePointAtDistance(route, 999_999.0)!!
        assertEquals(49.0, lat, 0.0)
        assertEquals(8.0411, lon, 0.0001)
    }

    @Test fun `routePointAtDistance returns null for empty route`() {
        assertEquals(null, routePointAtDistance(emptyList(), 0.0))
    }

    @Test fun `findClosestPointIndex picks the nearest point`() {
        // Ask about a point very close to sample 2 (8.0274).
        val (idx, dist) = findClosestPointIndex(route, 49.0, 8.0275)
        assertEquals(2, idx)
        assertTrue(dist < 20.0, "closest-point dist should be < 20m, got $dist")
    }

    @Test fun `findClosestPointIndex returns zero for empty route without crashing`() {
        // Semantic note: empty-route result is documented to be (0, MAX_VALUE).
        val (idx, dist) = findClosestPointIndex(emptyList(), 0.0, 0.0)
        assertEquals(0, idx)
        assertTrue(dist == Double.MAX_VALUE)
    }

    @Test fun `routeElevationAtDistance interpolates when both neighbours have elevation`() {
        val elevated = listOf(
            rp(49.0, 8.0, 0.0, elevation = 100.0),
            rp(49.0, 8.01, 1000.0, elevation = 200.0),
        )
        val mid = routeElevationAtDistance(elevated, 500.0)
        assertNotNull(mid)
        assertEquals(150.0, mid, 0.1)
    }

    @Test fun `routeElevationAtDistance falls back to the neighbour that has data`() {
        val mixed = listOf(
            rp(49.0, 8.0, 0.0, elevation = 100.0),
            rp(49.0, 8.01, 1000.0, elevation = null),
        )
        val mid = routeElevationAtDistance(mixed, 500.0)
        assertEquals(100.0, mid)
    }

    private fun rp(lat: Double, lon: Double, distance: Double, elevation: Double? = null) =
        RoutePoint(lat = lat, lon = lon, distanceFromStart = distance, elevation = elevation)
}
