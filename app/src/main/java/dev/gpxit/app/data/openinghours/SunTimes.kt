package dev.gpxit.app.data.openinghours

import de.westnordost.osm_opening_hours.model.EventTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Sun event times (`dawn`, `sunrise`, `sunset`, `dusk`) at a location,
 * via the sunrise equation (https://en.wikipedia.org/wiki/Sunrise_equation).
 * Accurate to a minute or two, which is plenty for opening hours.
 */
class SunTimes(
    private val lat: Double,
    private val lon: Double,
    private val zone: ZoneId,
) {
    /**
     * Minutes after local midnight of [date] at which [event] happens, or
     * null if it doesn't happen that day (polar day / night).
     */
    fun minutes(event: EventTime, date: LocalDate): Int? {
        val elevation = when (event) {
            EventTime.Sunrise, EventTime.Sunset -> -0.833
            EventTime.Dawn, EventTime.Dusk -> -6.0
        }
        val rising = event == EventTime.Sunrise || event == EventTime.Dawn

        // Days since J2000.0 (2000-01-01 12:00 TT) for the date at hand.
        val n = ceil(date.toEpochDay() + UNIX_EPOCH_JULIAN_DATE - J2000 + 0.0008)
        val meanSolarTime = n - lon / 360.0
        val meanAnomaly = (357.5291 + 0.98560028 * meanSolarTime).mod(360.0)
        val m = meanAnomaly.toRadians()
        val center = 1.9148 * sin(m) + 0.0200 * sin(2 * m) + 0.0003 * sin(3 * m)
        val eclipticLongitude = (meanAnomaly + center + 180.0 + 102.9372).mod(360.0).toRadians()
        val transit = J2000 + meanSolarTime + 0.0053 * sin(m) - 0.0069 * sin(2 * eclipticLongitude)
        val declination = asin(sin(eclipticLongitude) * sin(23.4397.toRadians()))

        val phi = lat.toRadians()
        val cosHourAngle = (sin(elevation.toRadians()) - sin(phi) * sin(declination)) /
            (cos(phi) * cos(declination))
        if (abs(cosHourAngle) > 1.0) return null
        val hourAngle = acos(cosHourAngle) * 180.0 / PI

        val julian = if (rising) transit - hourAngle / 360.0 else transit + hourAngle / 360.0
        val instant = Instant.ofEpochMilli(
            ((julian - UNIX_EPOCH_JULIAN_DATE) * MILLIS_PER_DAY).roundToLong()
        )
        val midnight = date.atStartOfDay(zone).toInstant()
        return ChronoUnit.MINUTES.between(midnight, instant).toInt()
    }

    private companion object {
        const val J2000 = 2451545.0
        const val UNIX_EPOCH_JULIAN_DATE = 2440587.5
        const val MILLIS_PER_DAY = 86_400_000.0

        fun Double.toRadians() = this * PI / 180.0
    }
}
