package dev.gpxit.app.data.openinghours

import de.westnordost.osm_opening_hours.parser.toOpeningHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Expected states were cross-checked against opening_hours.js, the reference
 * implementation. 2026-09-16 is a Wednesday.
 */
class OpeningHoursEvaluatorTest {

    /** Hessen 2026, as far as these tests need it. */
    private val hessen = object : HolidayCalendar {
        val public = mapOf(
            LocalDate.parse("2026-04-03") to "Good Friday",
            LocalDate.parse("2026-06-04") to "Corpus Christi",
            LocalDate.parse("2026-10-03") to "German Unity Day",
            LocalDate.parse("2026-12-25") to "Christmas Day",
            LocalDate.parse("2026-12-26") to "Second Day of Christmas",
        )
        val autumnBreak = LocalDate.parse("2026-10-05")..LocalDate.parse("2026-10-17")

        override fun isHoliday(kind: HolidayKind, date: LocalDate): Boolean = when (kind) {
            HolidayKind.PUBLIC -> date in public
            HolidayKind.SCHOOL -> date in autumnBreak
        }

        override fun publicHolidayName(date: LocalDate): String? = public[date]
    }

    private fun status(
        hours: String,
        at: String,
        calendar: HolidayCalendar = hessen,
        sunTimes: SunTimes? = null,
    ): OpeningStatus =
        OpeningHoursEvaluator(hours.toOpeningHours(lenient = true), calendar, sunTimes)
            .status(LocalDateTime.parse(at))

    private fun state(hours: String, at: String, calendar: HolidayCalendar = hessen): OpenState =
        status(hours, at, calendar).state

    @Test
    fun `weekday hours with closed Sundays and public holidays`() {
        val hours = "Mo-Sa 07:00-21:00; Su,PH off"
        val open = status(hours, "2026-09-16T10:00")
        assertEquals(OpenState.OPEN, open.state)
        assertEquals(LocalDateTime.parse("2026-09-16T21:00"), open.nextChange)
        assertEquals(OpenState.CLOSED, open.nextState)

        val evening = status(hours, "2026-09-16T22:00")
        assertEquals(OpenState.CLOSED, evening.state)
        assertEquals(LocalDateTime.parse("2026-09-17T07:00"), evening.nextChange)

        assertEquals(OpenState.CLOSED, state(hours, "2026-09-20T10:00"))
        // German Unity Day, a Saturday
        assertEquals(OpenState.CLOSED, state(hours, "2026-10-03T10:00"))
        assertEquals(OpenState.CLOSED, state(hours, "2026-06-04T10:00"))
    }

    @Test
    fun `closed Saturday evening opens Monday`() {
        val status = status("Mo-Sa 07:00-21:00; Su,PH off", "2026-09-19T22:00")
        assertEquals(OpenState.CLOSED, status.state)
        assertEquals(LocalDateTime.parse("2026-09-21T07:00"), status.nextChange)
    }

    @Test
    fun `weekdays or holidays`() {
        val hours = "Mo-Fr 06:00-18:00; Sa 06:00-13:00; Su,PH 07:00-11:00"
        assertEquals(OpenState.OPEN, state(hours, "2026-10-03T08:00"))
        assertEquals(OpenState.CLOSED, state(hours, "2026-10-03T12:00"))
        assertEquals(OpenState.OPEN, state(hours, "2026-09-20T08:00"))
    }

    @Test
    fun `weekdays that are holidays`() {
        // Saturday, public holiday, but not Mo-Fr
        assertEquals(OpenState.CLOSED, state("PH Mo-Fr 10:00-12:00", "2026-10-03T11:00"))
        assertEquals(OpenState.OPEN, state("PH Mo-Fr 10:00-12:00", "2026-12-25T11:00"))
    }

    @Test
    fun `later rule with a day selector replaces the day`() {
        assertEquals(OpenState.CLOSED, state("Mo-Sa 07:00-18:00; We 07:00-12:00", "2026-09-16T15:00"))
        assertEquals(OpenState.OPEN, state("Mo-Sa 07:00-18:00; We 07:00-12:00", "2026-09-17T15:00"))
        assertEquals(OpenState.CLOSED, state("Mo-Fr 08:00-18:00; Jan-Dec 12:00-14:00", "2026-09-16T10:00"))
        assertEquals(OpenState.CLOSED, state("Mo-Fr 08:00-18:00; We 12:00-14:00 \"x\"", "2026-09-16T10:00"))
        assertEquals(OpenState.OPEN, state("PH off; Mo-Fr 08:00-18:00", "2026-12-25T10:00"))
    }

    @Test
    fun `closed and time-only rules only overlay`() {
        val hours = "Mo-Fr 08:00-18:00; We 12:00-14:00 off"
        assertEquals(OpenState.OPEN, state(hours, "2026-09-16T10:00"))
        assertEquals(OpenState.CLOSED, state(hours, "2026-09-16T13:00"))
        assertEquals(OpenState.OPEN, state(hours, "2026-09-16T15:00"))

        assertEquals(OpenState.OPEN, state("Mo-Fr 08:00-18:00; 12:00-14:00", "2026-09-16T10:00"))
        assertEquals(OpenState.OPEN, state("PH,Sa off; 10:00-12:00", "2026-10-03T11:00"))
    }

    @Test
    fun `additional rules overlay`() {
        val hours = "Mo-Fr 08:00-18:00, We 12:00-14:00 off"
        assertEquals(OpenState.CLOSED, state(hours, "2026-09-16T13:00"))
        assertEquals(OpenState.OPEN, state(hours, "2026-09-16T15:00"))
    }

    @Test
    fun `spans past midnight`() {
        assertEquals(OpenState.OPEN, state("Fr-Sa 18:00-02:00", "2026-09-19T01:00"))
        assertEquals(OpenState.OPEN, state("Fr-Sa 18:00-02:00", "2026-09-20T01:00"))
        assertEquals(OpenState.CLOSED, state("Fr-Sa 18:00-02:00", "2026-09-18T01:00"))
        assertEquals(OpenState.OPEN, state("Mo-Fr 08:00-26:00", "2026-09-19T01:00"))

        // A rule for the next day replaces the spill-over, one for the previous day doesn't.
        assertEquals(OpenState.CLOSED, state("Mo-Su 22:00-02:00; Tu 10:00-12:00", "2026-09-15T01:00"))
        assertEquals(OpenState.OPEN, state("Mo-Su 22:00-02:00; Mo off", "2026-09-15T01:00"))
        assertEquals(OpenState.CLOSED, state("Mo-Sa 18:00-02:00; Su off", "2026-09-20T01:00"))
    }

    @Test
    fun `status changes across midnight are found`() {
        val status = status("Fr-Sa 18:00-02:00", "2026-09-18T23:00")
        assertEquals(OpenState.OPEN, status.state)
        assertEquals(LocalDateTime.parse("2026-09-19T02:00"), status.nextChange)
    }

    @Test
    fun `comments without explicit state are unknown`() {
        val unknown = status("Mo-Fr 10:00-18:00 \"Winter\"", "2026-09-16T11:00")
        assertEquals(OpenState.UNKNOWN, unknown.state)
        assertEquals("Winter", unknown.comment)
        assertFalse(unknown.holidayDependent)
        assertEquals(OpenState.OPEN, state("Mo-Fr 10:00-18:00 open \"Winter\"", "2026-09-16T11:00"))
        assertEquals(OpenState.CLOSED, state("Mo-Fr 10:00-18:00 \"Winter\"", "2026-09-16T19:00"))
        assertEquals(OpenState.UNKNOWN, state("\"seasonal\": Mo-Fr 10:00-12:00", "2026-09-16T11:00"))
    }

    @Test
    fun `fallback rules fill in where it would be closed`() {
        val hours = "Mo-Sa 08:00-20:00 || \"nach Vereinbarung\""
        assertEquals(OpenState.OPEN, state(hours, "2026-09-16T10:00"))
        val sunday = status(hours, "2026-09-20T10:00")
        assertEquals(OpenState.UNKNOWN, sunday.state)
        assertEquals("nach Vereinbarung", sunday.comment)
        assertEquals(OpenState.UNKNOWN, state("Mo-Sa 08:00-20:00; Su off || \"x\"", "2026-09-20T10:00"))

        val chain = "Mo 10:00-12:00 || Tu 10:00-12:00 closed || Tu 11:00-13:00 unknown"
        assertEquals(OpenState.UNKNOWN, state(chain, "2026-09-15T11:30"))
        assertEquals(OpenState.CLOSED, state(chain, "2026-09-15T10:30"))
    }

    @Test
    fun `open ends are unknown for a while`() {
        val late = status("Mo-Fr 20:00+", "2026-09-16T23:00")
        assertEquals(OpenState.UNKNOWN, late.state)
        assertEquals(OPEN_END_COMMENT, late.comment)
        assertEquals(LocalDateTime.parse("2026-09-17T06:00"), late.nextChange)
        assertEquals(OpenState.UNKNOWN, state("Mo-Fr 23:30+", "2026-09-17T03:30"))
        assertEquals(OpenState.CLOSED, state("Mo-Fr 23:30+", "2026-09-17T08:00"))

        val early = status("Mo-Fr 10:00+", "2026-09-16T20:00")
        assertEquals(OpenState.UNKNOWN, early.state)
        assertEquals(LocalDateTime.parse("2026-09-17T00:00"), early.nextChange)

        val withEnd = "Mo-Fr 10:00-18:00+"
        assertEquals(OpenState.OPEN, state(withEnd, "2026-09-16T17:00"))
        assertEquals(OpenState.UNKNOWN, state(withEnd, "2026-09-17T01:00"))
    }

    @Test
    fun `holiday offsets`() {
        val hours = "Mo-Fr 08:00-18:00; PH -1 day 08:00-12:00"
        assertEquals(OpenState.CLOSED, state(hours, "2026-12-24T14:00"))
        assertEquals(OpenState.OPEN, state(hours, "2026-12-23T14:00"))
    }

    @Test
    fun `school holidays`() {
        val hours = "Mo-Fr 10:00-12:00; SH Mo-Fr off"
        assertEquals(OpenState.CLOSED, state(hours, "2026-10-16T11:00"))
        assertEquals(OpenState.OPEN, state(hours, "2026-10-19T11:00"))
    }

    @Test
    fun `date and month ranges`() {
        assertEquals(OpenState.OPEN, state("Oct 15-Mar 15 10:00-12:00", "2027-03-15T11:00"))
        assertEquals(OpenState.CLOSED, state("Oct 15-Mar 15 10:00-12:00", "2026-06-15T11:00"))
        assertEquals(OpenState.OPEN, state("Mar 15-Oct 15 10:00-12:00", "2026-10-15T11:00"))
        assertEquals(OpenState.CLOSED, state("Mar 15-Oct 15 10:00-12:00", "2026-10-16T11:00"))
        assertEquals(OpenState.OPEN, state("Nov-Jan 10:00-12:00", "2027-01-25T11:00"))
        assertEquals(OpenState.CLOSED, state("Nov-Jan 10:00-12:00", "2027-02-01T11:00"))
        assertEquals(OpenState.OPEN, state("2026 Oct 15-2027 Mar 15 10:00-12:00", "2027-03-01T11:00"))
        assertEquals(OpenState.OPEN, state("2026 Oct 15-Mar 15 10:00-12:00", "2027-03-01T11:00"))
        assertEquals(OpenState.CLOSED, state("Mo-Su 10:00-12:00; Dec 24-Jan 06 off", "2027-01-05T11:00"))
        assertEquals(OpenState.CLOSED, state("Apr-Oct: 24/7", "2026-11-16T10:00"))
        assertEquals(OpenState.OPEN, state("Apr-Oct: 24/7", "2026-10-16T10:00"))
        // Good Friday
        assertEquals(OpenState.CLOSED, state("Mo-Su 10:00-12:00; easter -2 days off", "2026-04-03T11:00"))
        assertEquals(OpenState.OPEN, state("Mo-Su 10:00-12:00; easter -2 days off", "2026-04-02T11:00"))
    }

    @Test
    fun `nth weekdays, weeks and years`() {
        assertEquals(OpenState.OPEN, state("Mo[1] 10:00-12:00", "2026-09-07T11:00"))
        assertEquals(OpenState.CLOSED, state("Mo[1] 10:00-12:00", "2026-09-14T11:00"))
        assertEquals(OpenState.OPEN, state("Mo[-1] 10:00-12:00", "2026-09-28T11:00"))
        assertEquals(OpenState.OPEN, state("Mo[1] -2 days 10:00-12:00", "2026-09-05T11:00"))
        assertEquals(OpenState.OPEN, state("Sep Su[-1] 10:00-12:00", "2026-09-27T11:00"))
        assertEquals(OpenState.OPEN, state("Sa-Mo 10:00-12:00", "2026-09-14T11:00"))
        assertEquals(OpenState.CLOSED, state("Sa-Mo 10:00-12:00", "2026-09-15T11:00"))
        assertEquals(OpenState.OPEN, state("week 02-10/2 10:00-12:00", "2027-01-12T11:00"))
        assertEquals(OpenState.CLOSED, state("week 02-10/2 10:00-12:00", "2027-01-19T11:00"))
        assertEquals(OpenState.OPEN, state("2024-2030/2 10:00-12:00", "2026-09-16T11:00"))
        assertEquals(OpenState.CLOSED, state("2024-2030/2 10:00-12:00", "2025-09-16T11:00"))
    }

    @Test
    fun `always open`() {
        val status = status("24/7", "2026-09-16T10:00")
        assertEquals(OpenState.OPEN, status.state)
        assertNull(status.nextChange)
        assertEquals(OpenState.CLOSED, state("24/7; PH off", "2026-10-03T10:00"))
    }

    @Test
    fun `unknown holidays make only holiday-dependent times unknown`() {
        val calendar = HolidayCalendar.UNKNOWN
        val status = status("Mo-Sa 07:00-21:00; PH off", "2026-09-16T10:00", calendar)
        assertEquals(OpenState.UNKNOWN, status.state)
        assertTrue(status.holidayDependent)
        // Closed either way.
        assertEquals(OpenState.CLOSED, state("Mo-Sa 07:00-21:00; PH off", "2026-09-16T22:00", calendar))
        // No holiday rules at all.
        assertEquals(OpenState.OPEN, state("Mo-Sa 07:00-21:00", "2026-09-16T10:00", calendar))
    }

    @Test
    fun `a holiday in only part of the region is unknown on that day only`() {
        val assumption = LocalDate.parse("2026-08-15")
        val calendar = HolidayCalendar { _, date -> if (date == assumption) null else false }
        val hours = "Mo-Sa 07:00-21:00; PH off"
        assertEquals(OpenState.UNKNOWN, state(hours, "2026-08-15T10:00", calendar))
        assertEquals(OpenState.OPEN, state(hours, "2026-08-14T10:00", calendar))

        val friday = status(hours, "2026-08-14T22:00", calendar)
        assertEquals(OpenState.CLOSED, friday.state)
        assertEquals(LocalDateTime.parse("2026-08-15T07:00"), friday.nextChange)
        assertEquals(OpenState.UNKNOWN, friday.nextState)
    }

    @Test
    fun `sun events`() {
        val darmstadt = SunTimes(49.87, 8.65, ZoneId.of("Europe/Berlin"))
        val hours = "Mo-Su sunrise-sunset"
        assertEquals(OpenState.OPEN, status(hours, "2026-09-16T12:00", sunTimes = darmstadt).state)
        assertEquals(OpenState.CLOSED, status(hours, "2026-09-16T06:00", sunTimes = darmstadt).state)
        assertEquals(OpenState.CLOSED, status(hours, "2026-09-16T20:00", sunTimes = darmstadt).state)
        assertEquals(
            OpenState.CLOSED,
            status("Mo-Su sunrise-(sunset-01:00)", "2026-09-16T19:00", sunTimes = darmstadt).state
        )
    }

    @Test(expected = UnsupportedOpeningHoursException::class)
    fun `sun events need a location`() {
        status("Mo-Su sunrise-sunset", "2026-09-16T12:00")
    }

    @Test(expected = UnsupportedOpeningHoursException::class)
    fun `points in time are not opening hours`() {
        status("Mo-Fr 08:00,12:00", "2026-09-16T12:00")
    }
}
