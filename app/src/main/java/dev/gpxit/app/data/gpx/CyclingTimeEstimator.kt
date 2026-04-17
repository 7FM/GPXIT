package dev.gpxit.app.data.gpx

import dev.gpxit.app.domain.RoutePoint
import kotlin.math.max
import kotlin.math.min

/**
 * Estimates cycling time along a route considering elevation changes.
 *
 * Speed adjustment model:
 * - Flat (-2% to 2% gradient): base speed
 * - Uphill: speed drops ~12% per 1% gradient above 2% (min 30% of base)
 * - Downhill: speed increases ~8% per 1% gradient below -2% (max 150% of base)
 */
object CyclingTimeEstimator {

    /**
     * Estimate cycling time in minutes between two points on the route.
     * Falls back to flat speed if elevation data is missing.
     *
     * @param points route points with elevation data
     * @param fromIndex start index in the points list
     * @param toIndex end index in the points list
     * @param avgFlatSpeedKmh average speed on flat terrain in km/h
     * @return estimated time in minutes
     */
    fun estimateMinutes(
        points: List<RoutePoint>,
        fromIndex: Int,
        toIndex: Int,
        avgFlatSpeedKmh: Double
    ): Int {
        if (fromIndex >= toIndex || points.isEmpty()) return 0

        val from = fromIndex.coerceIn(0, points.size - 1)
        val to = toIndex.coerceIn(0, points.size - 1)
        val baseSpeedMs = avgFlatSpeedKmh * 1000.0 / 3600.0 // m/s

        // Check if we have elevation data
        val hasElevation = points.subList(from, min(to + 1, points.size))
            .count { it.elevation != null } > points.subList(from, min(to + 1, points.size)).size / 2

        if (!hasElevation) {
            // Fall back to flat speed
            val distance = points[to].distanceFromStart - points[from].distanceFromStart
            return (distance / baseSpeedMs / 60.0).toInt()
        }

        var totalTimeSeconds = 0.0

        for (i in from until to) {
            if (i + 1 >= points.size) break

            val p1 = points[i]
            val p2 = points[i + 1]

            val segmentDist = p2.distanceFromStart - p1.distanceFromStart
            if (segmentDist <= 0) continue

            val ele1 = p1.elevation ?: 0.0
            val ele2 = p2.elevation ?: 0.0
            val elevDiff = ele2 - ele1

            // Gradient as percentage
            val gradient = (elevDiff / segmentDist) * 100.0

            // Speed adjustment factor
            val speedFactor = when {
                gradient > 2.0 -> {
                    // Uphill: reduce speed 12% per 1% gradient above 2%
                    val reduction = (gradient - 2.0) * 0.12
                    max(0.30, 1.0 - reduction) // minimum 30% of base speed
                }
                gradient < -2.0 -> {
                    // Downhill: increase speed 8% per 1% gradient below -2%
                    val boost = (-gradient - 2.0) * 0.08
                    min(1.50, 1.0 + boost) // maximum 150% of base speed
                }
                else -> 1.0 // flat
            }

            val segmentSpeed = baseSpeedMs * speedFactor
            totalTimeSeconds += segmentDist / segmentSpeed
        }

        return (totalTimeSeconds / 60.0).toInt()
    }

    /**
     * Estimate cycling time from a distance-along-route position to a station.
     * Finds the nearest indices and delegates to the index-based method.
     */
    fun estimateMinutesAlongRoute(
        points: List<RoutePoint>,
        fromDistanceAlongRoute: Double,
        toDistanceAlongRoute: Double,
        avgFlatSpeedKmh: Double
    ): Int {
        if (points.isEmpty()) return 0

        val fromIdx = points.indexOfFirst { it.distanceFromStart >= fromDistanceAlongRoute }
            .coerceAtLeast(0)
        val toIdx = points.indexOfLast { it.distanceFromStart <= toDistanceAlongRoute }
            .coerceAtLeast(fromIdx)

        return estimateMinutes(points, fromIdx, toIdx, avgFlatSpeedKmh)
    }
}
