package dev.gpxit.app.data.transit

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

class CoverageCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000L

    private fun cache(file: File? = null) = CoverageCache(file) { clock }

    private val both = listOf("db", "transitous")

    @Test fun `unknown places are not pruned`() {
        assertEquals(both, cache().prune(44.75, 5.37, 2000, both))
    }

    @Test fun `backend without stations is skipped where another had some`() {
        val cache = cache()
        cache.record(44.7531, 5.3703, 2000, "db", 3)
        cache.record(44.7531, 5.3703, 2000, "transitous", 0)
        assertEquals(listOf("db"), cache.prune(44.7532, 5.3704, 2000, both))
        // a different cell is unknown
        assertEquals(both, cache.prune(44.80, 5.37, 2000, both))
    }

    @Test fun `nothing is pruned when nobody had stations`() {
        val cache = cache()
        cache.record(44.7531, 5.3703, 2000, "db", 0)
        cache.record(44.7531, 5.3703, 2000, "transitous", 0)
        assertEquals(both, cache.prune(44.7531, 5.3703, 2000, both))
    }

    @Test fun `a single candidate is never pruned`() {
        val cache = cache()
        cache.record(49.87, 8.65, 2000, "db", 0)
        cache.record(49.87, 8.65, 2000, "transitous", 5)
        assertEquals(listOf("db"), cache.prune(49.87, 8.65, 2000, listOf("db")))
    }

    @Test fun `an empty answer for a smaller radius says nothing`() {
        val cache = cache()
        cache.record(44.7531, 5.3703, 1000, "transitous", 0)
        cache.record(44.7531, 5.3703, 1000, "db", 2)
        assertEquals(both, cache.prune(44.7531, 5.3703, 2000, both))
        assertEquals(listOf("db"), cache.prune(44.7531, 5.3703, 500, both))
    }

    @Test fun `old entries expire`() {
        val cache = cache()
        cache.record(44.7531, 5.3703, 2000, "db", 3)
        cache.record(44.7531, 5.3703, 2000, "transitous", 0)
        clock += CoverageCache.MAX_AGE_MS + 1
        assertEquals(both, cache.prune(44.7531, 5.3703, 2000, both))
    }

    @Test fun `newer answers replace older ones`() {
        val cache = cache()
        cache.record(44.7531, 5.3703, 2000, "db", 3)
        cache.record(44.7531, 5.3703, 2000, "transitous", 0)
        cache.record(44.7531, 5.3703, 2000, "transitous", 4)
        assertEquals(both, cache.prune(44.7531, 5.3703, 2000, both))
    }

    @Test fun `survives a restart`() {
        val file = File(tmp.root, "cache.json")
        cache(file).apply {
            record(44.7531, 5.3703, 2000, "db", 3)
            record(44.7531, 5.3703, 2000, "transitous", 0)
            save()
        }
        assertEquals(listOf("db"), cache(file).prune(44.7531, 5.3703, 2000, both))
    }

    @Test fun `a broken file is ignored`() {
        val file = File(tmp.root, "cache.json").apply { writeText("not json") }
        assertEquals(both, cache(file).prune(44.7531, 5.3703, 2000, both))
    }
}
