package dev.gpxit.app.data

import android.content.Context
import dev.gpxit.app.domain.StationCandidate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the current route GPX and discovered stations to internal storage.
 */
class RouteStorage(private val context: Context) {

    private val gpxFile get() = File(context.filesDir, "current_route.gpx")
    private val stationsFile get() = File(context.filesDir, "current_stations.json")
    private val nearbyFile get() = File(context.filesDir, "nearby_stations.json")

    fun hasRoute(): Boolean = gpxFile.exists()

    fun saveGpx(bytes: ByteArray) {
        gpxFile.writeBytes(bytes)
    }

    fun loadGpxStream() = gpxFile.inputStream()

    fun saveStations(stations: List<StationCandidate>) = saveStationsToFile(stationsFile, stations)

    fun loadStations(): List<StationCandidate> = loadStationsFromFile(stationsFile)

    fun saveNearbyStations(stations: List<StationCandidate>) {
        saveStationsToFile(nearbyFile, stations)
    }

    fun loadNearbyStations(): List<StationCandidate> = loadStationsFromFile(nearbyFile)

    fun clearNearbyStations() {
        nearbyFile.delete()
    }

    fun clear() {
        gpxFile.delete()
        stationsFile.delete()
        nearbyFile.delete()
    }

    private fun saveStationsToFile(file: File, stations: List<StationCandidate>) {
        val arr = JSONArray()
        for (s in stations) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("lat", s.lat)
                put("lon", s.lon)
                put("distanceAlongRoute", s.distanceAlongRouteMeters)
                put("distanceFromRoute", s.distanceFromRouteMeters)
                put("products", JSONArray(s.products.toList()))
            })
        }
        file.writeText(arr.toString())
    }

    private fun loadStationsFromFile(file: File): List<StationCandidate> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val productsArr = obj.optJSONArray("products")
                val products = if (productsArr != null) {
                    (0 until productsArr.length()).map { productsArr.getString(it) }.toSet()
                } else emptySet()
                StationCandidate(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    lat = obj.getDouble("lat"),
                    lon = obj.getDouble("lon"),
                    distanceAlongRouteMeters = obj.getDouble("distanceAlongRoute"),
                    distanceFromRouteMeters = obj.getDouble("distanceFromRoute"),
                    products = products
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
