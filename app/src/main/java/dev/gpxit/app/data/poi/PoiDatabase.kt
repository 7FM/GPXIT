package dev.gpxit.app.data.poi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dev.gpxit.app.data.gpx.haversineMeters
import dev.gpxit.app.data.openinghours.HolidayCalendar
import dev.gpxit.app.data.openinghours.HolidayKind
import dev.gpxit.app.domain.Poi
import dev.gpxit.app.domain.PoiType
import dev.gpxit.app.domain.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos

/**
 * Reads POIs from the local SQLite datasets that ship via the
 * build-poi-dataset GitHub Action — one file per country (Geofabrik
 * extract) at `filesDir/pois/<id>.db`, installed by [PoiDatasetManager].
 *
 * Queries go to every installed file whose POIs can lie in the queried
 * area, and the results are merged ([mergeDatasetPois]). Each file carries
 * the holidays of its own regions. Every query is a local disk hit, no
 * network.
 */
class PoiDatabase private constructor(context: Context) {

    private val dir = File(context.filesDir, DIR)

    /** Build information of an installed dataset file. */
    data class DatasetInfo(
        val builtAt: String?,
        /** Null for the Germany-only file of app versions before per-country datasets. */
        val datasetId: String?,
        val sizeBytes: Long,
    )

    private class OpenDataset(
        val db: SQLiteDatabase,
        val info: DatasetInfo,
        /** Whether the file has opening hours + holiday regions (schema 2+). */
        val hasOpeningHours: Boolean,
        /** min lat, min lon, max lat, max lon of the POIs, if recorded. */
        val bounds: DoubleArray?,
        val regions: Set<String>,
    )

    private val open = HashMap<String, OpenDataset>()
    private val holidayCalendars = HashMap<String, HolidayCalendar>()

    private val _version = MutableStateFlow(0)

    /** Changes whenever a dataset is installed or removed. */
    val version: StateFlow<Int> = _version

    init {
        migrateLegacyFile(context.filesDir)
    }

    /** Ids of the installed datasets. */
    fun installedIds(): List<String> =
        dir.list()?.filter { it.endsWith(DB_SUFFIX) }?.map { it.removeSuffix(DB_SUFFIX) }?.sorted()
            ?: emptyList()

    /** True iff at least one dataset is installed. */
    fun isAvailable(): Boolean = installedIds().isNotEmpty()

    /** Build information of an installed dataset, or null if it isn't installed or can't be read. */
    fun info(id: String): DatasetInfo? = openOrNull(id)?.info

    /** Swaps [newDbFile] in as dataset [id] — closes the old file first. */
    @Synchronized
    fun install(id: String, newDbFile: File) {
        close(id)
        dir.mkdirs()
        val target = fileOf(id)
        if (target.exists()) target.delete()
        if (!newDbFile.renameTo(target)) {
            // Fall back to copy if rename crosses filesystems.
            newDbFile.copyTo(target, overwrite = true)
            newDbFile.delete()
        }
        _version.update { it + 1 }
    }

    @Synchronized
    fun remove(id: String) {
        close(id)
        fileOf(id).delete()
        _version.update { it + 1 }
    }

    private fun fileOf(id: String) = File(dir, id + DB_SUFFIX)

    @Synchronized
    private fun close(id: String) {
        open.remove(id)?.db?.close()
        holidayCalendars.clear()
    }

    @Synchronized
    private fun openOrNull(id: String): OpenDataset? {
        open[id]?.let { return it }
        val file = fileOf(id)
        if (!file.exists() || file.length() == 0L) return null
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            val meta = HashMap<String, String>()
            db.rawQuery("SELECT key, value FROM meta", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
            }
            val hasOpeningHours = db.hasColumn("pois", "opening_hours")
            val regions = HashSet<String>()
            if (hasOpeningHours) {
                db.rawQuery("SELECT code FROM regions", null).use { c ->
                    while (c.moveToNext()) regions += c.getString(0)
                }
            }
            val bounds = meta["bounds"]?.split(",")?.mapNotNull { it.toDoubleOrNull() }
                ?.takeIf { it.size == 4 }?.toDoubleArray()
            OpenDataset(
                db = db,
                info = DatasetInfo(meta["built_at"], meta["dataset"], file.length()),
                hasOpeningHours = hasOpeningHours,
                bounds = bounds,
                regions = regions,
            ).also { open[id] = it }
        } catch (e: Exception) {
            Log.w(TAG, "open $id failed: ${e.message}")
            db?.close()
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
        val perDataset = installedIds().mapNotNull { id ->
            val dataset = openOrNull(id) ?: return@mapNotNull null
            val b = dataset.bounds
            if (b != null && (b[0] > latNorth || b[2] < latSouth || b[1] > lonEast || b[3] < lonWest)) {
                return@mapNotNull null
            }
            queryDataset(id, dataset, types, latSouth, latNorth, lonWest, lonEast)
        }
        mergeDatasetPois(perDataset)
    }

    private fun queryDataset(
        id: String,
        dataset: OpenDataset,
        types: Set<PoiType>,
        latSouth: Double,
        latNorth: Double,
        lonWest: Double,
        lonEast: Double
    ): List<DatasetPoi> {
        val typeIds = types.map { it.dbId }.joinToString(",")
        // Datasets built before opening hours were added lack those columns.
        val sql = if (dataset.hasOpeningHours) {
            "SELECT p.osm_type, p.osm_id, p.type, p.lat, p.lon, p.name, p.opening_hours, r.code " +
                "FROM pois p LEFT JOIN regions r ON r.id = p.region_id " +
                "WHERE p.type IN ($typeIds) " +
                "AND p.lat BETWEEN ? AND ? " +
                "AND p.lon BETWEEN ? AND ?"
        } else {
            "SELECT osm_type, osm_id, type, lat, lon, name, NULL, NULL FROM pois " +
                "WHERE type IN ($typeIds) " +
                "AND lat BETWEEN ? AND ? " +
                "AND lon BETWEEN ? AND ?"
        }
        val args = arrayOf(
            latSouth.toString(), latNorth.toString(),
            lonWest.toString(), lonEast.toString()
        )

        val out = ArrayList<DatasetPoi>()
        try {
            dataset.db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val type = poiTypeFromDbId(c.getInt(2)) ?: continue
                    val osmId = c.getLong(1)
                    out += DatasetPoi(
                        osmType = c.getInt(0),
                        osmId = osmId,
                        poi = Poi(
                            id = osmId,
                            type = type,
                            lat = c.getDouble(3),
                            lon = c.getDouble(4),
                            name = c.getString(5)?.takeIf { it.isNotBlank() },
                            openingHours = c.getString(6)?.takeIf { it.isNotBlank() },
                            holidayRegion = c.getString(7),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            // Also reached when the file was replaced mid-query; the
            // version change makes callers query again.
            Log.w(TAG, "bbox query on $id failed: ${e.message}")
        }
        return out
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

    /**
     * Holidays of [region] (a `regions.code` such as `DE-HE`), for
     * evaluating opening hours. Unknown regions get a calendar that
     * knows nothing, which makes holiday-dependent hours come out unknown.
     */
    fun holidayCalendar(region: String?): HolidayCalendar {
        if (region == null) return HolidayCalendar.UNKNOWN
        synchronized(this) {
            holidayCalendars[region]?.let { return it }
            // Any file listing the region has its holidays; the newest
            // build covers the most future days.
            val dataset = installedIds()
                .mapNotNull { openOrNull(it) }
                .filter { region in it.regions }
                .maxByOrNull { it.info.builtAt.orEmpty() }
            val calendar = dataset?.let {
                try {
                    loadHolidayCalendar(it.db, region)
                } catch (e: Exception) {
                    Log.w(TAG, "loading holidays for $region failed: ${e.message}")
                    null
                }
            } ?: HolidayCalendar.UNKNOWN
            holidayCalendars[region] = calendar
            return calendar
        }
    }

    private fun loadHolidayCalendar(database: SQLiteDatabase, region: String): HolidayCalendar? {
        val (regionId, publicDays, schoolDays) = database.rawQuery(
            "SELECT id, ph_first_day, ph_last_day, sh_first_day, sh_last_day " +
                "FROM regions WHERE code = ?",
            arrayOf(region)
        ).use { c ->
            if (!c.moveToFirst()) return null
            Triple(c.getLong(0), c.getLongRangeOrNull(1, 2), c.getLongRangeOrNull(3, 4))
        }
        val public = HashMap<Long, String?>()
        val partial = HashSet<Long>()
        val school = HashSet<Long>()
        database.rawQuery(
            "SELECT kind, day, name FROM holidays WHERE region_id = ?",
            arrayOf(regionId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val day = c.getLong(1)
                when (c.getInt(0)) {
                    HOLIDAY_PUBLIC -> public[day] = c.getString(2)
                    HOLIDAY_PUBLIC_PARTIAL -> partial += day
                    HOLIDAY_SCHOOL -> school += day
                }
            }
        }
        return RegionHolidayCalendar(publicDays, schoolDays, public, partial, school)
    }

    /**
     * App versions before per-country datasets kept a Germany-only file at
     * `filesDir/pois.db`; it becomes the `germany` dataset until the next
     * update replaces it.
     */
    private fun migrateLegacyFile(filesDir: File) {
        File(filesDir, "pois.db.gz.download").delete()
        File(filesDir, "pois.db.staging").delete()
        val legacy = File(filesDir, "pois.db")
        if (!legacy.exists()) return
        dir.mkdirs()
        val target = fileOf(LEGACY_DATASET)
        if (target.exists() || !legacy.renameTo(target)) legacy.delete()
    }

    private class RegionHolidayCalendar(
        private val publicDays: LongRange?,
        private val schoolDays: LongRange?,
        private val public: Map<Long, String?>,
        private val partial: Set<Long>,
        private val school: Set<Long>,
    ) : HolidayCalendar {
        override fun isHoliday(kind: HolidayKind, date: LocalDate): Boolean? {
            val day = date.toEpochDay()
            return when (kind) {
                HolidayKind.PUBLIC -> when {
                    day in partial -> null
                    day in public -> true
                    publicDays != null && day in publicDays -> false
                    else -> null
                }
                HolidayKind.SCHOOL -> when {
                    day in school -> true
                    schoolDays != null && day in schoolDays -> false
                    else -> null
                }
            }
        }

        override fun publicHolidayName(date: LocalDate): String? =
            public[date.toEpochDay()]
    }

    companion object {
        private const val TAG = "PoiDatabase"
        private const val DIR = "pois"
        private const val DB_SUFFIX = ".db"

        /** The dataset the Germany-only file of older app versions becomes. */
        const val LEGACY_DATASET = "germany"

        // holidays.kind values, shared with scripts/build_poi_db.py.
        private const val HOLIDAY_PUBLIC = 0
        private const val HOLIDAY_PUBLIC_PARTIAL = 1
        private const val HOLIDAY_SCHOOL = 2

        @Volatile
        private var instance: PoiDatabase? = null

        fun get(context: Context): PoiDatabase =
            instance ?: synchronized(this) {
                instance ?: PoiDatabase(context.applicationContext).also { instance = it }
            }

        private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
            rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIndex = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) {
                    if (c.getString(nameIndex) == column) return@use true
                }
                false
            }

        private fun android.database.Cursor.getLongRangeOrNull(first: Int, last: Int): LongRange? =
            if (isNull(first) || isNull(last)) null else getLong(first)..getLong(last)
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
