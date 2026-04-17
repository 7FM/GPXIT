package dev.gpxit.app.data.gpx

import dev.gpxit.app.domain.RoutePoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CyclingTimeEstimatorTest {

    private fun flat(n: Int, spacingMeters: Double = 100.0, elevation: Double? = null) =
        (0..n).map { i ->
            RoutePoint(
                lat = 49.0,
                lon = 8.0 + i * 0.001,
                distanceFromStart = i * spacingMeters,
                elevation = elevation
            )
        }

    @Test fun `flat 10km at 20kmh takes roughly 30 minutes`() {
        // 10_000 m / (20 km/h) = 30 min
        val pts = flat(n = 100, spacingMeters = 100.0)
        val minutes = CyclingTimeEstimator.estimateMinutes(
            pts, fromIndex = 0, toIndex = pts.lastIndex, avgFlatSpeedKmh = 20.0
        )
        assertEquals(30, minutes)
    }

    @Test fun `uphill takes longer than flat`() {
        val flat = flat(n = 100, spacingMeters = 100.0, elevation = 0.0)
        // 5% continuous climb
        val climb = (0..100).map { i ->
            RoutePoint(
                lat = 49.0,
                lon = 8.0 + i * 0.001,
                distanceFromStart = i * 100.0,
                elevation = i * 5.0  // +5 m per 100 m distance = 5% grade
            )
        }
        val flatMin = CyclingTimeEstimator.estimateMinutes(flat, 0, flat.lastIndex, 20.0)
        val climbMin = CyclingTimeEstimator.estimateMinutes(climb, 0, climb.lastIndex, 20.0)
        assertTrue(
            climbMin > flatMin + 5,
            "5% climb should add well over 5 min to a 30-min segment (flat=$flatMin, climb=$climbMin)"
        )
    }

    @Test fun `downhill is faster than flat but capped`() {
        // 5% descent for 10km.
        val descent = (0..100).map { i ->
            RoutePoint(
                lat = 49.0,
                lon = 8.0 + i * 0.001,
                distanceFromStart = i * 100.0,
                elevation = 500.0 - i * 5.0
            )
        }
        val flat = flat(n = 100, elevation = 0.0)
        val flatMin = CyclingTimeEstimator.estimateMinutes(flat, 0, flat.lastIndex, 20.0)
        val descentMin =
            CyclingTimeEstimator.estimateMinutes(descent, 0, descent.lastIndex, 20.0)

        assertTrue(descentMin < flatMin, "descent should be faster than flat")
        // Cap at 150% of base = 30/1.5 = 20 min floor
        assertTrue(descentMin >= 20, "descent cap should keep it ≥ 20 min, got $descentMin")
    }

    @Test fun `missing elevation falls back to flat speed`() {
        val pts = flat(n = 100, elevation = null)
        val minutes = CyclingTimeEstimator.estimateMinutes(pts, 0, pts.lastIndex, 20.0)
        // 10 km at 20 km/h = 30 min
        assertEquals(30, minutes)
    }

    @Test fun `empty route returns zero`() {
        assertEquals(
            0,
            CyclingTimeEstimator.estimateMinutes(emptyList(), 0, 0, 20.0)
        )
    }

    @Test fun `from equals to returns zero`() {
        val pts = flat(n = 10)
        assertEquals(0, CyclingTimeEstimator.estimateMinutes(pts, 5, 5, 20.0))
    }

    @Test fun `estimateMinutesAlongRoute matches index-based call on flat terrain`() {
        val pts = flat(n = 100, elevation = 0.0)
        val byIdx = CyclingTimeEstimator.estimateMinutes(pts, 0, pts.lastIndex, 20.0)
        val byDist = CyclingTimeEstimator.estimateMinutesAlongRoute(
            pts,
            fromDistanceAlongRoute = 0.0,
            toDistanceAlongRoute = pts.last().distanceFromStart,
            avgFlatSpeedKmh = 20.0
        )
        assertEquals(byIdx, byDist)
    }
}
