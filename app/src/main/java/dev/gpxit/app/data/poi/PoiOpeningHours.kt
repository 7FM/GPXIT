package dev.gpxit.app.data.poi

import de.westnordost.osm_opening_hours.model.OpeningHours
import de.westnordost.osm_opening_hours.parser.toOpeningHoursOrNull
import dev.gpxit.app.data.openinghours.HolidayCalendar
import dev.gpxit.app.data.openinghours.HolidayKind
import dev.gpxit.app.data.openinghours.OpeningHoursEvaluator
import dev.gpxit.app.data.openinghours.OpeningStatus
import dev.gpxit.app.data.openinghours.SunTimes
import dev.gpxit.app.data.openinghours.UnsupportedOpeningHoursException
import dev.gpxit.app.domain.Poi
import java.time.LocalDateTime
import java.time.ZoneId

/** What we can tell about a POI's opening hours at some point in time. */
data class PoiHoursInfo(
    /** The raw OSM value, for display. */
    val raw: String,
    /** Null when [raw] can't be parsed or evaluated. */
    val status: OpeningStatus?,
    /** Name of today's public holiday, if [raw] has rules for public holidays. */
    val publicHoliday: String?,
)

/**
 * Evaluates POI opening hours against the holidays of each POI's region.
 * Parsed values are cached: POIs share a handful of distinct strings
 * ("Mo-Sa 07:00-21:00" and friends).
 */
class PoiOpeningHours(
    private val holidayCalendar: (region: String?) -> HolidayCalendar,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val parsed = object : LinkedHashMap<String, OpeningHours?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OpeningHours?>) =
            size > PARSED_CACHE_SIZE
    }

    /** Null if [poi] has no opening hours at all. */
    fun info(poi: Poi, at: LocalDateTime): PoiHoursInfo? {
        val raw = poi.openingHours ?: return null
        val hours = parse(raw) ?: return PoiHoursInfo(raw, null, null)
        val calendar = holidayCalendar(poi.holidayRegion)
        val status = try {
            OpeningHoursEvaluator(hours, calendar, SunTimes(poi.lat, poi.lon, zone()))
                .status(at)
        } catch (_: UnsupportedOpeningHoursException) {
            null
        } catch (_: RuntimeException) {
            // Mapper-written data: never let an odd value take the map down.
            null
        }
        val publicHoliday = if (PH_RULE.containsMatchIn(raw) &&
            calendar.isHoliday(HolidayKind.PUBLIC, at.toLocalDate()) == true
        ) {
            calendar.publicHolidayName(at.toLocalDate())
        } else {
            null
        }
        return PoiHoursInfo(raw, status, publicHoliday)
    }

    private fun parse(raw: String): OpeningHours? = synchronized(parsed) {
        if (parsed.containsKey(raw)) return parsed[raw]
        val result = raw.toOpeningHoursOrNull(lenient = true)
        parsed[raw] = result
        result
    }

    private companion object {
        const val PARSED_CACHE_SIZE = 2000
        val PH_RULE = Regex("""\bPH\b""")
    }
}
