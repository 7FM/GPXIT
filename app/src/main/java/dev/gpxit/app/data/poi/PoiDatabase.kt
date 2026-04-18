package dev.gpxit.app.data.poi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import dev.gpxit.app.data.gpx.haversineMeters
import dev.gpxit.app.domain.Poi
import dev.gpxit.app.domain.PoiType
import dev.gpxit.app.domain.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos

/**
 * Reads POIs from the local SQLite dataset that ships via the
 * build-poi-dataset GitHub Action. The file lives at
 * `filesDir/pois.db` after [PoiDatasetDownloader] populates it.
 *
 * Same surface as the old Overpass-backed PoiRepository so callers don't
 * care where the data comes from — but every query is a local disk hit,
 * no network.
 */
class PoiDatabase(private val context: Context) {

    private val dbFile: File get() = File(context.filesDir, "pois.db")

    @Volatile
    private var db: SQLiteDatabase? = null

    /** True iff the local database file exists and is non-empty. */
    fun isAvailable(): Boolean {
        val f = dbFile
        return f.exists() && f.length() > 0L
    }

    /** Build timestamp (ISO-8601 UTC) recorded by the Python builder, or null. */
    fun builtAt(): String? {
        val database = openOrNull() ?: return null
        return try {
            database.rawQuery(
                "SELECT value FROM meta WHERE key = 'built_at' LIMIT 1", null
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    /** Swaps a freshly-downloaded DB in under lock — closes the old handle. */
    @Synchronized
    fun replaceWith(newDbFile: File) {
        db?.close()
        db = null
        if (dbFile.exists()) dbFile.delete()
        if (!newDbFile.renameTo(dbFile)) {
            // Fall back to copy if rename crosses filesystems.
            newDbFile.copyTo(dbFile, overwrite = true)
            newDbFile.delete()
        }
    }

    @Synchronized
    fun close() {
        db?.close()
        db = null
    }

    @Synchronized
    private fun openOrNull(): SQLiteDatabase? {
        if (db != null) return db
        if (!isAvailable()) return null
        return try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).also { db = it }
        } catch (e: SQLiteException) {
            Log.w(TAG, "open failed: ${e.message}")
            null
        }
    }

    /** Query all POIs of [types] inside an axis-aligned bbox. */
    suspend fun queryByBbox(
        types: Set<PoiType>,
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double
    ): List<Poi> = withContext(Dispatchers.IO) {
        if (types.isEmpty()) return@withContext emptyList()
        if (latNorth <= latSouth || lonEast <= lonWest) return@withContext emptyList()
        val database = openOrNull() ?: return@withContext emptyList()

        val typeIds = types.map { it.dbId }.joinToString(",")
        val sql =
            "SELECT osm_id, type, lat, lon, name FROM pois " +
                "WHERE type IN ($typeIds) " +
                "AND lat BETWEEN ? AND ? " +
                "AND lon BETWEEN ? AND ?"
        val args = arrayOf(
            latSouth.toString(), latNorth.toString(),
            lonWest.toString(), lonEast.toString()
        )

        val out = ArrayList<Poi>()
        try {
            database.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val type = poiTypeFromDbId(c.getInt(1)) ?: continue
                    out += Poi(
                        id = c.getLong(0),
                        type = type,
                        lat = c.getDouble(2),
                        lon = c.getDouble(3),
                        name = c.getString(4)?.takeIf { it.isNotBlank() }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "bbox query failed: ${e.message}")
        }
        out
    }

    /**
     * Query POIs within [corridorRadiusMeters] of any point on [points].
     * First pulls a bbox-expanded superset from disk, then filters to the
     * actual corridor so winding routes don't drag in everything in their
     * rectangular hull.
     */
    suspend fun queryForRoute(
        points: List<RoutePoint>,
        types: Set<PoiType>,
        corridorRadiusMeters: Int = 2000,
        sampleIntervalMeters: Int = 500
    ): List<Poi> = withContext(Dispatchers.IO) {
        if (points.isEmpty() || types.isEmpty()) return@withContext emptyList()

        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (p in points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        val latPad = corridorRadiusMeters / 111_000.0
        val avgLat = (minLat + maxLat) / 2.0
        val lonPad = corridorRadiusMeters /
            (111_000.0 * cos(avgLat * PI / 180.0).coerceAtLeast(0.01))

        val bboxPois = queryByBbox(
            types,
            minLat - latPad, maxLat + latPad,
            minLon - lonPad, maxLon + lonPad
        )
        if (bboxPois.isEmpty()) return@withContext bboxPois

        val samples = ArrayList<RoutePoint>()
        var lastDist = -sampleIntervalMeters.toDouble()
        for (p in points) {
            if (p.distanceFromStart - lastDist >= sampleIntervalMeters) {
                samples += p
                lastDist = p.distanceFromStart
            }
        }
        if (samples.lastOrNull() != points.last()) samples += points.last()

        val r = corridorRadiusMeters.toDouble()
        bboxPois.filter { poi ->
            samples.any { s -> haversineMeters(s.lat, s.lon, poi.lat, poi.lon) <= r }
        }
    }

    private companion object {
        const val TAG = "PoiDatabase"
    }
}

/** Type-code mapping shared with the Python builder. */
private val PoiType.dbId: Int
    get() = when (this) {
        PoiType.GROCERY -> 0
        PoiType.BAKERY -> 1
        PoiType.WATER -> 2
        PoiType.TOILET -> 3
        PoiType.BIKE_REPAIR -> 4
    }

private fun poiTypeFromDbId(id: Int): PoiType? = when (id) {
    0 -> PoiType.GROCERY
    1 -> PoiType.BAKERY
    2 -> PoiType.WATER
    3 -> PoiType.TOILET
    4 -> PoiType.BIKE_REPAIR
    else -> null
}
