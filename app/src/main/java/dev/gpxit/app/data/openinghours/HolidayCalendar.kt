package dev.gpxit.app.data.openinghours

import java.time.LocalDate

enum class HolidayKind { PUBLIC, SCHOOL }

/** Which days are holidays at a POI's location. Used to evaluate `PH` / `SH` rules. */
fun interface HolidayCalendar {
    /**
     * Whether [date] is a holiday of [kind], or null when that isn't known
     * (no data for that day, or a holiday in only part of the region).
     */
    fun isHoliday(kind: HolidayKind, date: LocalDate): Boolean?

    /** Name of the public holiday on [date], if any. */
    fun publicHolidayName(date: LocalDate): String? = null

    companion object {
        /** A calendar that knows nothing, e.g. for POIs without a holiday region. */
        val UNKNOWN = HolidayCalendar { _, _ -> null }
    }
}
