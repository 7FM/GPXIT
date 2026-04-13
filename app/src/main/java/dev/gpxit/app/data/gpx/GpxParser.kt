package dev.gpxit.app.data.gpx

import dev.gpxit.app.domain.RouteInfo
import dev.gpxit.app.domain.RoutePoint
import io.ticofab.androidgpxparser.parser.GPXParser
import io.ticofab.androidgpxparser.parser.domain.Gpx
import java.io.InputStream
import kotlin.math.*

object GpxParser {

    fun parse(inputStream: InputStream): RouteInfo {
        val parser = GPXParser()
        val gpx: Gpx = parser.parse(inputStream)

        // Prefer tracks over routes (tracks are more common from Komoot)
        val rawPoints = mutableListOf<Pair<Double, Double>>() // lat, lon pairs
        val elevations = mutableListOf<Double?>()
        var name: String? = null

        val tracks = gpx.tracks
        if (tracks.isNotEmpty()) {
            val track = tracks[0]
            name = track.trackName
            for (segment in track.trackSegments) {
                for (pt in segment.trackPoints) {
                    rawPoints.add(pt.latitude to pt.longitude)
                    elevations.add(pt.elevation)
                }
            }
        } else {
            val routes = gpx.routes
            if (routes.isNotEmpty()) {
                val route = routes[0]
                name = route.routeName
                for (pt in route.routePoints) {
                    rawPoints.add(pt.latitude to pt.longitude)
                    elevations.add(pt.elevation)
                }
            }
        }

        if (rawPoints.isEmpty()) {
            return RouteInfo(name = name, points = emptyList(), totalDistanceMeters = 0.0)
        }

        // Build RoutePoints with cumulative distance
        val points = mutableListOf<RoutePoint>()
        var cumulativeDistance = 0.0

        for (i in rawPoints.indices) {
            if (i > 0) {
                cumulativeDistance += haversineMeters(
                    rawPoints[i - 1].first, rawPoints[i - 1].second,
                    rawPoints[i].first, rawPoints[i].second
                )
            }
            points.add(
                RoutePoint(
                    lat = rawPoints[i].first,
                    lon = rawPoints[i].second,
                    elevation = elevations[i],
                    distanceFromStart = cumulativeDistance
                )
            )
        }

        return RouteInfo(
            name = name,
            points = points,
            totalDistanceMeters = cumulativeDistance
        )
    }
}

/** Haversine distance in meters between two lat/lon points. */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6_371_000.0 // Earth radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}

/**
 * Find the index of the closest point on the route to the given lat/lon.
 * Returns the index and the distance in meters.
 */
fun findClosestPointIndex(points: List<RoutePoint>, lat: Double, lon: Double): Pair<Int, Double> {
    var bestIndex = 0
    var bestDist = Double.MAX_VALUE
    for (i in points.indices) {
        val d = haversineMeters(lat, lon, points[i].lat, points[i].lon)
        if (d < bestDist) {
            bestDist = d
            bestIndex = i
        }
    }
    return bestIndex to bestDist
}
