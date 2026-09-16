package dev.gpxit.app.data.transit

import android.content.Context
import de.schildbach.pte.DbProvider
import de.schildbach.pte.DsbProvider
import de.schildbach.pte.SeProvider
import de.schildbach.pte.TlemProvider

/**
 * The transit backends GPXIT can ask, with their coverage areas, and the rules
 * for picking which ones to ask for a place or a trip. The rules follow
 * KPublicTransport's `Manager` (see MULTI_PROVIDER_PLAN.md, *Prior art*).
 *
 * Entries are in priority order: when two backends return the same station
 * or trip, the earlier one's copy is kept.
 */
class TransitBackendRegistry(
    private val entries: List<Entry>,
) {
    class Entry(
        val id: String,
        val coverage: Map<CoverageTier, CoverageArea>,
        factory: () -> TransitBackend,
    ) {
        val backend: TransitBackend by lazy(factory)

        /** Covered at [tier] or better. */
        fun covers(lat: Double, lon: Double, tier: CoverageTier): Boolean =
            CoverageTier.entries
                .takeWhile { it <= tier }
                .any { coverage[it]?.covers(lat, lon) == true }
    }

    fun byId(id: String): TransitBackend? = entries.firstOrNull { it.id == id }?.backend

    /** Lower is preferred. */
    fun priority(backendId: String): Int =
        entries.indexOfFirst { it.id == backendId }.let { if (it < 0) entries.size else it }

    /**
     * Backends to ask for stations near a point: every enabled backend whose
     * area contains the point in the best tier that has any.
     */
    fun stationBackendIds(lat: Double, lon: Double, enabled: (String) -> Boolean = { true }): List<String> {
        for (tier in CoverageTier.entries) {
            val ids = entries
                .filter { enabled(it.id) && it.coverage[tier]?.covers(lat, lon) == true }
                .map { it.id }
            if (ids.isNotEmpty()) return ids
        }
        return emptyList()
    }

    /**
     * Backends to ask for a trip. Per tier (a backend counts for a tier if it
     * has an area of that tier and covers an endpoint at that tier or
     * better): first those covering both endpoints, and if one of them isn't
     * global, stop there; otherwise also those covering one endpoint, then
     * continue with the next tier.
     */
    fun journeyBackendIds(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        enabled: (String) -> Boolean = { true },
    ): List<String> {
        val tried = LinkedHashSet<String>()
        var foundSymmetricNonGlobal = false
        for (tier in CoverageTier.entries) {
            for (bothEndpoints in listOf(true, false)) {
                for (entry in entries) {
                    if (entry.id in tried || !enabled(entry.id)) continue
                    val area = entry.coverage[tier] ?: continue
                    val coversFrom = entry.covers(fromLat, fromLon, tier)
                    val coversTo = entry.covers(toLat, toLon, tier)
                    val matches = if (bothEndpoints) coversFrom && coversTo else coversFrom || coversTo
                    if (!matches) continue
                    tried += entry.id
                    if (bothEndpoints && !area.isGlobal) foundSymmetricNonGlobal = true
                }
                if (tried.isNotEmpty() && foundSymmetricNonGlobal) return tried.toList()
            }
        }
        return tried.toList()
    }

    companion object {
        const val DB = "db"
        const val DSB = "dsb"
        const val SE = "se"
        const val TLEM = "tlem"
        const val TRANSITOUS = "transitous"

        // Same client ids as Öffi and Transportr use for these HAFAS endpoints.
        private const val DSB_AUTHORIZATION = """{"type":"AID","aid":"irkmpm9mdznstenr-android"}"""
        private const val SE_AUTHORIZATION = """{"type":"AID","aid":"h5o3n7f4t2m8l9x1"}"""

        @Volatile
        private var instance: TransitBackendRegistry? = null

        fun get(context: Context): TransitBackendRegistry =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): TransitBackendRegistry {
            val coverage = context.assets.open("transit/coverage.json").bufferedReader().use {
                CoverageArea.parseAll(it.readText())
            }
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "unknown"
            val userAgent = "GPXIT/$version (https://github.com/7FM/GPXIT)"
            return fromCoverage(coverage, backendFactories(userAgent))
        }

        /** Backends in priority order. */
        fun backendFactories(userAgent: String): List<Pair<String, () -> TransitBackend>> = listOf(
            DB to { PteBackend(DB, "Deutsche Bahn", canRouteFromCoordinates = true) { DbProvider() } },
            DSB to {
                PteBackend(DSB, "Rejseplanen", canRouteFromCoordinates = true) {
                    DsbProvider(DSB_AUTHORIZATION)
                }
            },
            SE to {
                PteBackend(SE, "Resrobot", canRouteFromCoordinates = true) {
                    SeProvider(SE_AUTHORIZATION)
                }
            },
            // Traveline's EFA only plans trips between its own station ids.
            TLEM to { PteBackend(TLEM, "Traveline", canRouteFromCoordinates = false) { TlemProvider() } },
            TRANSITOUS to { TransitousBackend(userAgent) },
        )

        fun fromCoverage(
            coverage: Map<String, Map<CoverageTier, CoverageArea>>,
            factories: List<Pair<String, () -> TransitBackend>>,
        ): TransitBackendRegistry = TransitBackendRegistry(
            factories.map { (id, factory) ->
                Entry(
                    id,
                    coverage[id] ?: error("no coverage for transit backend $id in coverage.json"),
                    factory,
                )
            }
        )
    }
}
