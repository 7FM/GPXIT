package dev.gpxit.app.data.openinghours

import de.westnordost.osm_opening_hours.model.EventTime
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/** Reference times from suncalc, which opening_hours.js uses. */
class SunTimesTest {
    private val berlinZone = ZoneId.of("Europe/Berlin")

    private fun assertAbout(expected: String, actual: Int?) {
        val expectedMinutes = LocalTime.parse(expected).toSecondOfDay() / 60
        assertTrue(
            "expected about $expected but was $actual",
            actual != null && abs(actual - expectedMinutes) <= 2
        )
    }

    @Test
    fun berlinMidsummer() {
        val sun = SunTimes(52.52, 13.405, berlinZone)
        val date = LocalDate.parse("2026-06-21")
        assertAbout("03:52", sun.minutes(EventTime.Dawn, date))
        assertAbout("04:43", sun.minutes(EventTime.Sunrise, date))
        assertAbout("21:33", sun.minutes(EventTime.Sunset, date))
        assertAbout("22:23", sun.minutes(EventTime.Dusk, date))
    }

    @Test
    fun berlinMidwinter() {
        val sun = SunTimes(52.52, 13.405, berlinZone)
        val date = LocalDate.parse("2026-12-21")
        assertAbout("08:14", sun.minutes(EventTime.Sunrise, date))
        assertAbout("15:53", sun.minutes(EventTime.Sunset, date))
    }

    @Test
    fun westOfTheTimeZoneMeridian() {
        val sun = SunTimes(40.4168, -3.7038, ZoneId.of("Europe/Madrid"))
        val date = LocalDate.parse("2026-03-20")
        assertAbout("06:51", sun.minutes(EventTime.Dawn, date))
        assertAbout("07:18", sun.minutes(EventTime.Sunrise, date))
        assertAbout("19:26", sun.minutes(EventTime.Sunset, date))
        assertAbout("19:54", sun.minutes(EventTime.Dusk, date))
    }

    @Test
    fun polarDay() {
        val sun = SunTimes(69.65, 18.96, ZoneId.of("Europe/Oslo"))
        assertNull(sun.minutes(EventTime.Sunset, LocalDate.parse("2026-06-21")))
    }
}
