package dev.gpxit.app.ui.map

import dev.gpxit.app.data.openinghours.OPEN_END_COMMENT
import dev.gpxit.app.data.openinghours.OpenState
import dev.gpxit.app.data.openinghours.OpeningStatus
import dev.gpxit.app.data.poi.PoiHoursInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class PoiHoursTextTest {
    // A Wednesday
    private val now = LocalDateTime.parse("2026-09-16T10:00")

    private fun at(time: String) = LocalDateTime.parse(time)

    private fun status(
        state: OpenState,
        next: String? = null,
        nextState: OpenState? = null,
        comment: String? = null,
        holidayDependent: Boolean = false,
    ) = OpeningStatus(state, comment, next?.let(::at), nextState, holidayDependent)

    @Test
    fun open() {
        assertEquals(
            "Open · closes 18:00",
            describeOpeningStatus(status(OpenState.OPEN, "2026-09-16T18:00", OpenState.CLOSED), now)
        )
        assertEquals(
            "Open · closes midnight",
            describeOpeningStatus(status(OpenState.OPEN, "2026-09-17T00:00", OpenState.CLOSED), now)
        )
        assertEquals("Open", describeOpeningStatus(status(OpenState.OPEN), now))
        assertEquals(
            "Open until 18:00",
            describeOpeningStatus(status(OpenState.OPEN, "2026-09-16T18:00", OpenState.UNKNOWN), now)
        )
    }

    @Test
    fun closed() {
        assertEquals(
            "Closed · opens tomorrow 07:00",
            describeOpeningStatus(status(OpenState.CLOSED, "2026-09-17T07:00", OpenState.OPEN), now)
        )
        assertEquals(
            "Closed · opens Mon 07:00",
            describeOpeningStatus(status(OpenState.CLOSED, "2026-09-21T07:00", OpenState.OPEN), now)
        )
        assertEquals("Closed", describeOpeningStatus(status(OpenState.CLOSED), now))
    }

    @Test
    fun unknown() {
        assertEquals(
            "Hours uncertain (holiday) · until 21:00",
            describeOpeningStatus(
                status(OpenState.UNKNOWN, "2026-09-16T21:00", OpenState.CLOSED, holidayDependent = true),
                now
            )
        )
        assertEquals(
            "Hours uncertain: nach Vereinbarung",
            describeOpeningStatus(status(OpenState.UNKNOWN, comment = "nach Vereinbarung"), now)
        )
        assertEquals(
            "Open, closing time unknown · until tomorrow 06:00",
            describeOpeningStatus(
                status(OpenState.UNKNOWN, "2026-09-17T06:00", OpenState.CLOSED, OPEN_END_COMMENT),
                now
            )
        )
    }

    @Test
    fun details() {
        val info = PoiHoursInfo(
            raw = "Mo-Fr 08:00-18:00; Sa 08:00-12:00 \"<Markt>\"; PH off",
            status = null,
            publicHoliday = "Christmas Day",
        )
        assertEquals(
            "Today: Christmas Day<br>Mo-Fr 08:00-18:00<br>Sa 08:00-12:00 &quot;&lt;Markt&gt;&quot;<br>PH off",
            poiHoursDetails(info)
        )
        assertEquals("Opening hours:", poiHoursSnippet(info, now))
    }
}
