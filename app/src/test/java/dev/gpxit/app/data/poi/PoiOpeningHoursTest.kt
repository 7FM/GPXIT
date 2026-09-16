package dev.gpxit.app.data.poi

import dev.gpxit.app.data.openinghours.HolidayCalendar
import dev.gpxit.app.data.openinghours.HolidayKind
import dev.gpxit.app.data.openinghours.OpenState
import dev.gpxit.app.domain.Poi
import dev.gpxit.app.domain.PoiType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PoiOpeningHoursTest {
    private val unityDay = LocalDate.parse("2026-10-03")

    private val calendars = mapOf(
        "DE-HE" to object : HolidayCalendar {
            override fun isHoliday(kind: HolidayKind, date: LocalDate) =
                kind == HolidayKind.PUBLIC && date == unityDay

            override fun publicHolidayName(date: LocalDate) =
                if (date == unityDay) "German Unity Day" else null
        }
    )

    private val openingHours = PoiOpeningHours(
        holidayCalendar = { region -> calendars[region] ?: HolidayCalendar.UNKNOWN },
        zone = { ZoneId.of("Europe/Berlin") },
    )

    private fun poi(hours: String?, region: String? = "DE-HE") =
        Poi(1, PoiType.BAKERY, 49.87, 8.65, "Bäckerei", hours, region)

    @Test
    fun noHours() {
        assertNull(openingHours.info(poi(null), LocalDateTime.parse("2026-10-03T10:00")))
    }

    @Test
    fun usesTheRegionsHolidays() {
        val hours = "Mo-Sa 07:00-18:00; PH off"
        val holiday = openingHours.info(poi(hours), LocalDateTime.parse("2026-10-03T10:00"))!!
        assertEquals(OpenState.CLOSED, holiday.status?.state)
        assertEquals("German Unity Day", holiday.publicHoliday)

        val normalDay = openingHours.info(poi(hours), LocalDateTime.parse("2026-10-02T10:00"))!!
        assertEquals(OpenState.OPEN, normalDay.status?.state)
        assertNull(normalDay.publicHoliday)

        val unknownRegion = openingHours.info(poi(hours, region = null), LocalDateTime.parse("2026-10-03T10:00"))!!
        assertEquals(OpenState.UNKNOWN, unknownRegion.status?.state)
    }

    @Test
    fun holidayNameOnlyWhenTheHoursCareAboutHolidays() {
        val info = openingHours.info(poi("Mo-Sa 07:00-18:00"), LocalDateTime.parse("2026-10-03T10:00"))!!
        assertNull(info.publicHoliday)
    }

    @Test
    fun unparseableHoursKeepTheRawValue() {
        val info = openingHours.info(poi("nach Absprache"), LocalDateTime.parse("2026-10-03T10:00"))!!
        assertEquals("nach Absprache", info.raw)
        assertNull(info.status)
    }
}
