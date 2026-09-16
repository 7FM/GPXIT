package dev.gpxit.app.data.openinghours

import de.westnordost.osm_opening_hours.model.AnnualEvent
import de.westnordost.osm_opening_hours.model.CalendarDate
import de.westnordost.osm_opening_hours.model.ClockTime
import de.westnordost.osm_opening_hours.model.Date
import de.westnordost.osm_opening_hours.model.DateRange
import de.westnordost.osm_opening_hours.model.DatesInMonth
import de.westnordost.osm_opening_hours.model.ExtendedClockTime
import de.westnordost.osm_opening_hours.model.ExtendedTime
import de.westnordost.osm_opening_hours.model.Holiday
import de.westnordost.osm_opening_hours.model.HolidaySelector
import de.westnordost.osm_opening_hours.model.HolidayWithOffset
import de.westnordost.osm_opening_hours.model.LastNth
import de.westnordost.osm_opening_hours.model.MonthDay
import de.westnordost.osm_opening_hours.model.MonthDayRange
import de.westnordost.osm_opening_hours.model.MonthRange
import de.westnordost.osm_opening_hours.model.MonthsOrDateSelector
import de.westnordost.osm_opening_hours.model.NextWeekday
import de.westnordost.osm_opening_hours.model.Nth
import de.westnordost.osm_opening_hours.model.NthRange
import de.westnordost.osm_opening_hours.model.NthSelector
import de.westnordost.osm_opening_hours.model.OffsetOp
import de.westnordost.osm_opening_hours.model.OpeningHours
import de.westnordost.osm_opening_hours.model.PreviousWeekday
import de.westnordost.osm_opening_hours.model.Range
import de.westnordost.osm_opening_hours.model.Rule
import de.westnordost.osm_opening_hours.model.RuleOperator
import de.westnordost.osm_opening_hours.model.RuleType
import de.westnordost.osm_opening_hours.model.Selector
import de.westnordost.osm_opening_hours.model.SingleMonth
import de.westnordost.osm_opening_hours.model.SpecificWeekdayDate
import de.westnordost.osm_opening_hours.model.SpecificWeekdays
import de.westnordost.osm_opening_hours.model.StartingAtDate
import de.westnordost.osm_opening_hours.model.StartingAtTime
import de.westnordost.osm_opening_hours.model.StartingAtYear
import de.westnordost.osm_opening_hours.model.TimePointsSelector
import de.westnordost.osm_opening_hours.model.TimeSpan
import de.westnordost.osm_opening_hours.model.TwentyFourSeven
import de.westnordost.osm_opening_hours.model.VariableDate
import de.westnordost.osm_opening_hours.model.VariableTime
import de.westnordost.osm_opening_hours.model.Week
import de.westnordost.osm_opening_hours.model.WeekRange
import de.westnordost.osm_opening_hours.model.Weekday
import de.westnordost.osm_opening_hours.model.WeekdayOffset
import de.westnordost.osm_opening_hours.model.WeekdayRange
import de.westnordost.osm_opening_hours.model.WeekdaysSelector
import de.westnordost.osm_opening_hours.model.WeeksSelector
import de.westnordost.osm_opening_hours.model.Year
import de.westnordost.osm_opening_hours.model.YearRange
import de.westnordost.osm_opening_hours.model.YearsSelector
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min

enum class OpenState { OPEN, CLOSED, UNKNOWN }

/** Part of a day, in minutes after midnight: [start] inclusive to [end] exclusive. */
data class DaySegment(
    val start: Int,
    val end: Int,
    val state: OpenState,
    val comment: String? = null,
    /** [state] is UNKNOWN only because it depends on holidays we have no data for. */
    val holidayDependent: Boolean = false,
)

data class OpeningStatus(
    val state: OpenState,
    val comment: String?,
    /** When [state] changes next, or null if it doesn't within the look-ahead window. */
    val nextChange: LocalDateTime?,
    val nextState: OpenState?,
    val holidayDependent: Boolean,
)

/** [OpeningStatus.comment] of the unknown stretch after an open end (`18:00+`). */
const val OPEN_END_COMMENT = "open end"

/** Thrown for opening hours that are valid but can't be evaluated (e.g. points in time). */
class UnsupportedOpeningHoursException(message: String) : Exception(message)

/**
 * Evaluates parsed OSM opening hours
 * (https://wiki.openstreetmap.org/wiki/Key:opening_hours/specification).
 *
 * Rule precedence follows the reference implementation opening_hours.js:
 * - A normal (`;`) rule that is not "closed" and has a day selector (weekday,
 *   holiday, week, month, year) replaces everything earlier rules said about
 *   the days it matches. Time-only and closed rules only overlay.
 * - An additional (`,`) rule overlays.
 * - A fallback (`||`) rule applies only where the state so far is closed.
 * - A time span past midnight (`22:00-02:00`, `18:00-26:00`) continues into
 *   the next day as part of the rule; a later rule matching that next day
 *   replaces it like anything else on that day.
 * - Without an explicit `open`/`closed`/`unknown`, a rule with a comment is
 *   unknown, otherwise open. Times not covered by any rule are closed.
 * - An open end (`18:00+`) is unknown until midnight, or for 10 hours if it
 *   starts at 17:00 or later, or 8 hours from 22:00 on.
 *
 * Holiday rules are evaluated against [holidays]. Where a holiday isn't
 * known, the day is evaluated both ways and times where the results differ
 * come out UNKNOWN with [DaySegment.holidayDependent] set.
 */
class OpeningHoursEvaluator(
    hours: OpeningHours,
    private val holidays: HolidayCalendar,
    /** Needed for `sunrise` etc.; without it, such hours are unsupported. */
    private val sunTimes: SunTimes?,
) {
    private val rules: List<Rule> = hours.rules.filterNot { it.isEmpty() }

    private val dayCache = LinkedHashMap<LocalDate, List<DaySegment>>()

    init {
        if (hours.containsTimePoints()) {
            throw UnsupportedOpeningHoursException("points in time are not opening hours")
        }
    }

    /** State at [at], and when it changes next within [lookAheadDays]. */
    fun status(at: LocalDateTime, lookAheadDays: Int = 8): OpeningStatus {
        val date = at.toLocalDate()
        val minute = at.hour * 60 + at.minute
        val current = day(date).first { minute < it.end }
        for (offset in 0..lookAheadDays) {
            val d = date.plusDays(offset.toLong())
            for (segment in day(d)) {
                if (offset == 0 && segment.start <= minute) continue
                if (segment.state != current.state) {
                    return OpeningStatus(
                        state = current.state,
                        comment = current.comment,
                        nextChange = d.atStartOfDay().plusMinutes(segment.start.toLong()),
                        nextState = segment.state,
                        holidayDependent = current.holidayDependent,
                    )
                }
            }
        }
        return OpeningStatus(current.state, current.comment, null, null, current.holidayDependent)
    }

    /** The whole of [date] as consecutive segments covering 00:00 to 24:00. */
    @Synchronized
    fun day(date: LocalDate): List<DaySegment> {
        dayCache[date]?.let { return it }
        val result = computeDay(date)
        if (dayCache.size >= DAY_CACHE_SIZE) dayCache.remove(dayCache.keys.first())
        dayCache[date] = result
        return result
    }

    private fun computeDay(date: LocalDate): List<DaySegment> {
        val unknown = unknownHolidays(date).toList()
        if (unknown.isEmpty()) {
            return evaluateDay(date) { kind, d -> holidays.isHoliday(kind, d) ?: false }
        }
        if (unknown.size > MAX_UNKNOWN_HOLIDAYS) {
            return listOf(DaySegment(0, DAY, OpenState.UNKNOWN, holidayDependent = true))
        }
        val variants = (0 until (1 shl unknown.size)).map { mask ->
            val assumed = unknown.filterIndexedTo(HashSet()) { i, _ -> mask and (1 shl i) != 0 }
            evaluateDay(date) { kind, d ->
                holidays.isHoliday(kind, d) ?: (HolidayDay(kind, d) in assumed)
            }
        }
        return mergeVariants(variants)
    }

    private data class HolidayDay(val kind: HolidayKind, val date: LocalDate)

    /** Holidays that evaluating [date] may depend on but [holidays] doesn't know. */
    private fun unknownHolidays(date: LocalDate): Set<HolidayDay> {
        val result = HashSet<HolidayDay>()
        for (rule in rules) {
            val selectors = (rule.selector as? Range)?.holidays ?: continue
            for (selector in selectors) {
                val (kind, offset) = selector.kindAndOffset()
                // The rule may also apply via yesterday, for spans past midnight.
                for (day in listOf(date, date.minusDays(1))) {
                    val holiday = day.minusDays(offset.toLong())
                    if (holidays.isHoliday(kind, holiday) == null) {
                        result += HolidayDay(kind, holiday)
                    }
                }
            }
        }
        return result
    }

    private fun interface HolidayLookup {
        fun isHoliday(kind: HolidayKind, date: LocalDate): Boolean
    }

    private class Entry(val start: Int, val end: Int, val state: OpenState, val comment: String?)

    private class Span(
        val start: Int,
        val end: Int,
        val state: OpenState? = null,
        val comment: String? = null,
    )

    private fun evaluateDay(date: LocalDate, lookup: HolidayLookup): List<DaySegment> {
        val yesterday = date.minusDays(1)
        val entries = ArrayList<Entry>()
        for (rule in rules) {
            val matchesToday = rule.selector.matches(date, lookup)
            val matchesYesterday = rule.selector.matches(yesterday, lookup)
            if (!matchesToday && !matchesYesterday) continue

            val state = rule.state()
            val comment = rule.comment ?: (rule.selector as? Range)?.text
            val added = ArrayList<Entry>()
            if (matchesYesterday) {
                for (span in spans(rule, yesterday)) {
                    if (span.end <= DAY) continue
                    added += Entry(
                        max(span.start - DAY, 0), min(span.end - DAY, DAY),
                        span.state ?: state, span.comment ?: comment,
                    )
                }
            }
            if (matchesToday) {
                for (span in spans(rule, date)) {
                    if (span.start >= DAY) continue
                    added += Entry(
                        span.start, min(span.end, DAY),
                        span.state ?: state, span.comment ?: comment,
                    )
                }
            }

            when (rule.ruleOperator) {
                RuleOperator.Normal -> {
                    if (matchesToday && state != OpenState.CLOSED && rule.selector.hasDaySelector()) {
                        entries.clear()
                    }
                    entries += added
                }
                RuleOperator.Additional -> entries += added
                RuleOperator.Fallback -> {
                    val closedOnly = added.flatMap { closedParts(entries, it) }
                    entries += closedOnly
                }
            }
        }
        return toSegments(entries)
    }

    private fun spans(rule: Rule, date: LocalDate): List<Span> {
        val times = (rule.selector as? Range)?.times
        if (times.isNullOrEmpty()) return WHOLE_DAY
        return times.flatMap { time ->
            when (time) {
                is TimeSpan -> {
                    val start = minutes(time.start, date)
                    var end = minutes(time.end, date)
                    if (end <= start) end += DAY
                    if (time.openEnd) {
                        listOf(
                            Span(start, end),
                            Span(end, openEndUntil(end), OpenState.UNKNOWN, OPEN_END_COMMENT),
                        )
                    } else {
                        listOf(Span(start, end))
                    }
                }
                is StartingAtTime -> {
                    val start = minutes(time.start, date)
                    listOf(Span(start, openEndUntil(start), OpenState.UNKNOWN, OPEN_END_COMMENT))
                }
                is TimePointsSelector ->
                    throw UnsupportedOpeningHoursException("points in time are not opening hours")
            }
        }
    }

    private fun minutes(time: ExtendedTime, date: LocalDate): Int = when (time) {
        is ClockTime -> time.hour * 60 + time.minutes
        is ExtendedClockTime -> time.hour * 60 + time.minutes
        is VariableTime -> {
            val sun = sunTimes ?: throw UnsupportedOpeningHoursException("no location for sun times")
            val base = sun.minutes(time.dailyEvent, date)
                ?: throw UnsupportedOpeningHoursException("no ${time.dailyEvent} on $date")
            val offset = time.timeOffset?.let { o ->
                val m = o.offset.hour * 60 + o.offset.minutes
                if (o.op == OffsetOp.Minus) -m else m
            } ?: 0
            (base + offset).coerceIn(0, DAY)
        }
    }

    private fun Selector.matches(date: LocalDate, lookup: HolidayLookup): Boolean = when (this) {
        TwentyFourSeven -> true
        is Range -> matches(date, lookup)
    }

    private fun Range.matches(date: LocalDate, lookup: HolidayLookup): Boolean {
        val inYears = years.orNullIfEmpty()?.any { it.matches(date.year) } ?: true
        val inMonths = months.orNullIfEmpty()?.any { it.matches(date) } ?: true
        val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val inWeeks = weeks.orNullIfEmpty()?.any { it.matches(week) } ?: true
        if (!inYears || !inMonths || !inWeeks) return false

        val onWeekday = weekdays.orNullIfEmpty()?.any { it.matches(date) }
        val onHoliday = holidays.orNullIfEmpty()?.any { h ->
            val (kind, offset) = h.kindAndOffset()
            lookup.isHoliday(kind, date.minusDays(offset.toLong()))
        }
        return when {
            onWeekday == null -> onHoliday ?: true
            onHoliday == null -> onWeekday
            // "SH Mo-Fr": weekdays that are also holidays. "Su,PH": either.
            isRestrictedByHolidays -> onWeekday && onHoliday
            else -> onWeekday || onHoliday
        }
    }

    private companion object {
        const val DAY = 24 * 60
        const val DAY_CACHE_SIZE = 16
        const val MAX_UNKNOWN_HOLIDAYS = 4
        val WHOLE_DAY = listOf(Span(0, DAY))

        fun <T> List<T>?.orNullIfEmpty(): List<T>? = this?.takeIf { it.isNotEmpty() }

        fun Rule.state(): OpenState = when (ruleType) {
            RuleType.Open -> OpenState.OPEN
            RuleType.Closed, RuleType.Off -> OpenState.CLOSED
            RuleType.Unknown -> OpenState.UNKNOWN
            null ->
                if (comment != null || (selector as? Range)?.text != null) OpenState.UNKNOWN
                else OpenState.OPEN
        }

        fun Selector.hasDaySelector(): Boolean = when (this) {
            TwentyFourSeven -> true
            is Range -> !years.isNullOrEmpty() || !months.isNullOrEmpty() ||
                !weeks.isNullOrEmpty() || text != null ||
                !weekdays.isNullOrEmpty() || !holidays.isNullOrEmpty()
        }

        /** How long an open end (`18:00+`) is assumed to last. */
        fun openEndUntil(start: Int): Int = when {
            start >= 22 * 60 -> start + 8 * 60
            start >= 17 * 60 -> start + 10 * 60
            else -> max(start, DAY)
        }

        fun HolidaySelector.kindAndOffset(): Pair<HolidayKind, Int> = when (this) {
            is Holiday -> kind() to 0
            is HolidayWithOffset -> holiday.kind() to dayOffset
        }

        fun Holiday.kind(): HolidayKind = when (this) {
            Holiday.PublicHoliday -> HolidayKind.PUBLIC
            Holiday.SchoolHoliday -> HolidayKind.SCHOOL
        }

        fun YearsSelector.matches(year: Int): Boolean = when (this) {
            is Year -> year == this.year
            is StartingAtYear -> year >= start
            is YearRange -> year in start..end && (year - start) % (step ?: 1) == 0
        }

        fun WeeksSelector.matches(week: Int): Boolean = when (this) {
            is Week -> week == this.week
            is WeekRange -> {
                val inRange = if (start <= end) week in start..end else week >= start || week <= end
                inRange && Math.floorMod(week - start, 53) % (step ?: 1) == 0
            }
        }

        fun WeekdaysSelector.matches(date: LocalDate): Boolean = when (this) {
            is Weekday -> date.dayOfWeek == toDayOfWeek()
            is WeekdayRange -> {
                val day = date.dayOfWeek.ordinal
                if (start.ordinal <= end.ordinal) day in start.ordinal..end.ordinal
                else day >= start.ordinal || day <= end.ordinal
            }
            is SpecificWeekdays -> {
                val base = date.minusDays(dayOffset.toLong())
                base.dayOfWeek == weekday.toDayOfWeek() && nths.any { it.matches(base) }
            }
        }

        fun NthSelector.matches(date: LocalDate): Boolean {
            val fromStart = (date.dayOfMonth - 1) / 7 + 1
            return when (this) {
                is Nth -> fromStart == nth
                is NthRange -> fromStart in start..end
                is LastNth -> (date.lengthOfMonth() - date.dayOfMonth) / 7 + 1 == nth
            }
        }

        fun Weekday.toDayOfWeek(): DayOfWeek = DayOfWeek.entries[ordinal]

        fun MonthsOrDateSelector.matches(date: LocalDate): Boolean = when (this) {
            is SingleMonth ->
                date.monthValue == month.ordinal + 1 && (year == null || year == date.year)
            is MonthRange -> {
                val year = year
                if (year == null) {
                    val m = date.monthValue - 1
                    if (start.ordinal <= end.ordinal) m in start.ordinal..end.ordinal
                    else m >= start.ordinal || m <= end.ordinal
                } else {
                    val endYear = if (end.ordinal >= start.ordinal) year else year + 1
                    val from = LocalDate.of(year, start.ordinal + 1, 1)
                    val to = YearMonth.of(endYear, end.ordinal + 1).atEndOfMonth()
                    date in from..to
                }
            }
            is DatesInMonth ->
                date.monthValue == month.ordinal + 1 &&
                    (year == null || year == date.year) &&
                    days.any { d ->
                        when (d) {
                            is MonthDay -> date.dayOfMonth == d.day
                            is MonthDayRange -> date.dayOfMonth in d.start..d.end
                        }
                    }
            is StartingAtDate -> {
                val year = start.year()
                if (year != null) {
                    val from = start.resolve(year)
                    from != null && date >= from
                } else {
                    // Without a year: from that date to the end of each year.
                    val from = start.resolve(date.year)
                    from != null && date >= from && from.year == date.year
                }
            }
            is DateRange -> {
                val startYear = start.year()
                val years = if (startYear != null) listOf(startYear) else listOf(date.year - 1, date.year)
                years.any { y -> date in (dateRange(start, end, y) ?: return@any false) }
            }
            is Date -> {
                val year = year()
                if (year != null) date == resolve(year)
                else (date.year - 1..date.year + 1).any { resolve(it) == date }
            }
        }

        /** [start] in [year] to the next [end] (inclusive), or null if either doesn't exist. */
        fun dateRange(start: Date, end: Date, year: Int): ClosedRange<LocalDate>? {
            val from = start.resolve(year) ?: return null
            val endYear = end.year()
            var to = end.resolve(endYear ?: year) ?: return null
            if (to < from && endYear == null) to = end.resolve(year + 1) ?: return null
            return from..to
        }

        fun Date.year(): Int? = when (this) {
            is CalendarDate -> year
            is SpecificWeekdayDate -> year
            is VariableDate -> year
        }

        /** This date in [year], or null if it doesn't exist that year. */
        fun Date.resolve(year: Int): LocalDate? = when (this) {
            is CalendarDate -> {
                val yearMonth = YearMonth.of(year, month.ordinal + 1)
                if (day > yearMonth.lengthOfMonth()) {
                    null
                } else {
                    yearMonth.atDay(day).offsetBy(weekdayOffset).plusDays(dayOffset.toLong())
                }
            }
            is SpecificWeekdayDate -> {
                val yearMonth = YearMonth.of(year, month.ordinal + 1)
                val dayOfWeek = weekday.toDayOfWeek()
                val date = when (val n = nthPointSelector) {
                    is Nth -> yearMonth.atDay(1)
                        .with(TemporalAdjusters.firstInMonth(dayOfWeek))
                        .plusWeeks(n.nth - 1L)
                    is LastNth -> yearMonth.atEndOfMonth()
                        .with(TemporalAdjusters.lastInMonth(dayOfWeek))
                        .minusWeeks(n.nth - 1L)
                }
                if (date.monthValue != yearMonth.monthValue) null
                else date.plusDays(dayOffset.toLong())
            }
            is VariableDate -> when (annualEvent) {
                AnnualEvent.Easter -> easterSunday(year)
            }.offsetBy(weekdayOffset).plusDays(dayOffset.toLong())
        }

        fun LocalDate.offsetBy(offset: WeekdayOffset?): LocalDate = when (offset) {
            null -> this
            is NextWeekday -> with(TemporalAdjusters.next(offset.weekday.toDayOfWeek()))
            is PreviousWeekday -> with(TemporalAdjusters.previous(offset.weekday.toDayOfWeek()))
        }

        /** Gregorian Easter Sunday (anonymous Gregorian algorithm). */
        fun easterSunday(year: Int): LocalDate {
            val a = year % 19
            val b = year / 100
            val c = year % 100
            val d = b / 4
            val e = b % 4
            val f = (b + 8) / 25
            val g = (b - f + 1) / 3
            val h = (19 * a + b - d - g + 15) % 30
            val i = c / 4
            val k = c % 4
            val l = (32 + 2 * e + 2 * i - h - k) % 7
            val m = (a + 11 * h + 22 * l) / 451
            val month = (h + l - 7 * m + 114) / 31
            val day = (h + l - 7 * m + 114) % 31 + 1
            return LocalDate.of(year, month, day)
        }

        /** Parts of [entry] during which [entries] say closed. */
        fun closedParts(entries: List<Entry>, entry: Entry): List<Entry> {
            val bounds = sortedSetOf(entry.start, entry.end)
            for (e in entries) {
                if (e.start in entry.start..entry.end) bounds += e.start
                if (e.end in entry.start..entry.end) bounds += e.end
            }
            val result = ArrayList<Entry>()
            for ((from, to) in bounds.zipWithNext()) {
                if (stateAt(entries, from).state == OpenState.CLOSED) {
                    result += Entry(from, to, entry.state, entry.comment)
                }
            }
            return result
        }

        /** The last entry covering [minute], or a plain "closed" if none does. */
        fun stateAt(entries: List<Entry>, minute: Int): Entry =
            entries.lastOrNull { minute >= it.start && minute < it.end }
                ?: Entry(minute, minute + 1, OpenState.CLOSED, null)

        fun toSegments(entries: List<Entry>): List<DaySegment> {
            val bounds = sortedSetOf(0, DAY)
            for (e in entries) {
                bounds += e.start
                bounds += e.end
            }
            val segments = bounds.zipWithNext().map { (from, to) ->
                val entry = stateAt(entries, from)
                DaySegment(from, to, entry.state, entry.comment)
            }
            return segments.mergeAdjacent()
        }

        /** Combines evaluations of one day under different holiday assumptions. */
        fun mergeVariants(variants: List<List<DaySegment>>): List<DaySegment> {
            val bounds = sortedSetOf(0, DAY)
            for (segment in variants.flatten()) {
                bounds += segment.start
                bounds += segment.end
            }
            val segments = bounds.zipWithNext().map { (from, to) ->
                val candidates = variants.map { v -> v.first { from >= it.start && from < it.end } }
                val first = candidates.first()
                if (candidates.all { it.state == first.state }) {
                    DaySegment(from, to, first.state, candidates.firstNotNullOfOrNull { it.comment })
                } else {
                    DaySegment(from, to, OpenState.UNKNOWN, holidayDependent = true)
                }
            }
            return segments.mergeAdjacent()
        }

        fun List<DaySegment>.mergeAdjacent(): List<DaySegment> {
            val result = ArrayList<DaySegment>(size)
            for (segment in this) {
                val last = result.lastOrNull()
                if (last != null &&
                    last.state == segment.state &&
                    last.comment == segment.comment &&
                    last.holidayDependent == segment.holidayDependent
                ) {
                    result[result.lastIndex] = last.copy(end = segment.end)
                } else {
                    result += segment
                }
            }
            return result
        }
    }
}
