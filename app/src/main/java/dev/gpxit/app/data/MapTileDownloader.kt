package dev.gpxit.app.data

import android.content.Context
import android.util.Log
import dev.gpxit.app.domain.RouteInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.*

class MapTileDownloader(context: Context) {

    init {
        val config = Configuration.getInstance()
        config.userAgentValue = "GPXIT/1.0 (Android cycling app)"
        val cacheDir = File(context.filesDir, "osmdroid")
        cacheDir.mkdirs()
        config.osmdroidBasePath = cacheDir
        config.osmdroidTileCache = File(cacheDir, "tiles")
    }

    data class DownloadProgress(
        val totalTiles: Int = 0,
        val downloadedTiles: Int = 0,
        val isDownloading: Boolean = false,
        val isComplete: Boolean = false,
        val failed: Boolean = false,
        val failedCount: Int = 0
    )

    private fun computeRouteTiles(routeInfo: RouteInfo, minZoom: Int, maxZoom: Int): List<Triple<Int, Int, Int>> {
        val tileSet = mutableSetOf<Triple<Int, Int, Int>>()

        // Sample every ~500m
        val sampledPoints = mutableListOf<Pair<Double, Double>>()
        var lastDist = -500.0
        for (pt in routeInfo.points) {
            if (pt.distanceFromStart - lastDist >= 500.0) {
                sampledPoints.add(pt.lat to pt.lon)
                lastDist = pt.distanceFromStart
            }
        }
        routeInfo.points.lastOrNull()?.let { sampledPoints.add(it.lat to it.lon) }

        for (zoom in minZoom..maxZoom) {
            for ((lat, lon) in sampledPoints) {
                val tileX = lonToTileX(lon, zoom)
                val tileY = latToTileY(lat, zoom)
                // 1-tile buffer around each point
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        tileSet.add(Triple(zoom, tileX + dx, tileY + dy))
                    }
                }
            }
        }

        return tileSet.toList()
    }

    private fun lonToTileX(lon: Double, zoom: Int): Int =
        ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        return ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom)).toInt()
    }

    suspend fun downloadRoute(
        routeInfo: RouteInfo,
        minZoom: Int = 10,
        maxZoom: Int = 16,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val tiles = computeRouteTiles(routeInfo, minZoom, maxZoom)
        val total = tiles.size
        Log.d("TileDownload", "Route corridor: $total tiles, zoom $minZoom-$maxZoom")

        withContext(Dispatchers.Main) {
            onProgress(DownloadProgress(totalTiles = total, isDownloading = true))
        }

        val servers = arrayOf(
            "https://a.tile.openstreetmap.org",
            "https://b.tile.openstreetmap.org",
            "https://c.tile.openstreetmap.org"
        )

        val tileWriter = SqlTileWriter()
        var downloaded = 0
        var skipped = 0
        var failed = 0
        // Expiry: 30 days from now
        val expiry = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000

        withContext(Dispatchers.IO) {
            for ((zoom, x, y) in tiles) {
                if (!coroutineContext.isActive) break

                val tileIndex = MapTileIndex.getTileIndex(zoom, x, y)

                // Skip if already cached
                if (tileWriter.getExpirationTimestamp(OsmTileSource, tileIndex) != null) {
                    skipped++
                    downloaded++
                    if ((downloaded + failed) % 10 == 0) {
                        val d = downloaded; val t = total
                        withContext(Dispatchers.Main) {
                            onProgress(DownloadProgress(totalTiles = t, downloadedTiles = d, isDownloading = true))
                        }
                    }
                    continue
                }

                try {
                    val server = servers[(x + y) % servers.size]
                    val url = URL("$server/$zoom/$x/$y.png")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "GPXIT/1.0 (Android cycling app)")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000

                    if (conn.responseCode == 200) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        tileWriter.saveFile(OsmTileSource, tileIndex, ByteArrayInputStream(bytes), expiry)
                        downloaded++
                    } else {
                        failed++
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    failed++
                    if (failed <= 3) Log.w("TileDownload", "Failed z=$zoom x=$x y=$y: ${e.message}")
                }

                if ((downloaded + failed) % 5 == 0) {
                    val d = downloaded; val t = total
                    withContext(Dispatchers.Main) {
                        onProgress(DownloadProgress(totalTiles = t, downloadedTiles = d, isDownloading = true))
                    }
                }

                // Rate limit: ~20 tiles/sec max
                kotlinx.coroutines.delay(50)
            }
        }

        tileWriter.onDetach()
        Log.d("TileDownload", "Done! downloaded=$downloaded skipped=$skipped failed=$failed total=$total")

        withContext(Dispatchers.Main) {
            onProgress(DownloadProgress(
                totalTiles = total, downloadedTiles = downloaded,
                isDownloading = false,
                isComplete = true,
                failed = failed > 0,
                failedCount = failed
            ))
        }
    }
}
