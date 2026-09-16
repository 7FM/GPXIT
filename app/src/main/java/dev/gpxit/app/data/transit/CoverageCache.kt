package dev.gpxit.app.data.transit

import android.content.Context
import org.json.JSONArray
import java.io.File
import kotlin.math.floor

/**
 * Remembers, per small grid cell, which transit backends returned stations
 * during route discovery, so repeat routes don't ask a backend again where it
 * had nothing while another backend did (e.g. Transitous in a French valley
 * where only DB knows the stations, or the other way round).
 *
 * Deliberately conservative: a backend is only skipped when at least one
 * other candidate is known to have stations in the same cell. Places with a
 * single candidate backend (all of Germany) are never pruned, so an empty
 * answer from a backend glitch can't hide stations there.
 */
class CoverageCache(
    private val file: File?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val cellLat: Int, val cellLon: Int, val backendId: String)

    private class Entry(val hadStations: Boolean, val radiusMeters: Int, val timestampMs: Long)

    private val entries = LinkedHashMap<Key, Entry>()
    private var loaded = false
    private var dirty = false

    /** Result of one successful station query of [backendId] at a point. */
    @Synchronized
    fun record(lat: Double, lon: Double, radiusMeters: Int, backendId: String, stationCount: Int) {
        ensureLoaded()
        val key = key(lat, lon, backendId)
        entries.remove(key)
        entries[key] = Entry(stationCount > 0, radiusMeters, now())
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
        dirty = true
    }

    /**
     * [candidates] minus the backends known to have nothing around the point
     * while another candidate is known to have stations there.
     */
    @Synchronized
    fun prune(lat: Double, lon: Double, radiusMeters: Int, candidates: List<String>): List<String> {
        if (candidates.size < 2) return candidates
        ensureLoaded()
        val known = candidates.associateWith { lookup(lat, lon, radiusMeters, it) }
        if (known.values.none { it == true }) return candidates
        return candidates.filter { known[it] != false }
    }

    /** true / false if known, null if not (or too old, or checked with a smaller radius). */
    private fun lookup(lat: Double, lon: Double, radiusMeters: Int, backendId: String): Boolean? {
        val entry = entries[key(lat, lon, backendId)] ?: return null
        if (now() - entry.timestampMs > MAX_AGE_MS) return null
        // Nothing within a smaller radius says nothing about a larger one.
        if (!entry.hadStations && entry.radiusMeters < radiusMeters) return null
        return entry.hadStations
    }

    @Synchronized
    fun save() {
        val target = file ?: return
        if (!dirty) return
        val cutoff = now() - MAX_AGE_MS
        val json = JSONArray()
        for ((key, entry) in entries) {
            if (entry.timestampMs < cutoff) continue
            json.put(
                JSONArray()
                    .put(key.cellLat)
                    .put(key.cellLon)
                    .put(key.backendId)
                    .put(if (entry.hadStations) 1 else 0)
                    .put(entry.radiusMeters)
                    .put(entry.timestampMs)
            )
        }
        runCatching {
            val tmp = File(target.path + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(json.toString())
                tmp.delete()
            }
            dirty = false
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val source = file?.takeIf { it.exists() } ?: return
        runCatching {
            val json = JSONArray(source.readText())
            for (i in 0 until json.length()) {
                val row = json.getJSONArray(i)
                entries[Key(row.getInt(0), row.getInt(1), row.getString(2))] =
                    Entry(row.getInt(3) == 1, row.getInt(4), row.getLong(5))
            }
        }
    }

    private fun key(lat: Double, lon: Double, backendId: String) =
        Key(floor(lat / CELL_DEGREES).toInt(), floor(lon / CELL_DEGREES).toInt(), backendId)

    companion object {
        /** ~500 m; repeat routes sample the same points, so they hit the same cells. */
        private const val CELL_DEGREES = 0.005

        /** Coverage changes (Transitous adds feeds), so don't trust old answers for long. */
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        private const val MAX_ENTRIES = 20_000

        @Volatile
        private var instance: CoverageCache? = null

        fun get(context: Context): CoverageCache =
            instance ?: synchronized(this) {
                instance ?: CoverageCache(
                    File(context.applicationContext.filesDir, "transit_coverage_cache.json")
                ).also { instance = it }
            }
    }
}
