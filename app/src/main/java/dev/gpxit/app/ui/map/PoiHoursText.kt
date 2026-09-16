package dev.gpxit.app.ui.map

import dev.gpxit.app.data.openinghours.OPEN_END_COMMENT
import dev.gpxit.app.data.openinghours.OpenState
import dev.gpxit.app.data.openinghours.OpeningStatus
import dev.gpxit.app.data.poi.PoiHoursInfo
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dayTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

/** One-line status for a POI popup, e.g. "Open · closes 18:00". */
fun describeOpeningStatus(status: OpeningStatus, now: LocalDateTime): String {
    val next = status.nextChange?.let { formatWhen(it, now) }
    return when (status.state) {
        OpenState.OPEN -> when {
            next == null -> "Open"
            status.nextState == OpenState.CLOSED -> "Open · closes $next"
            else -> "Open until $next"
        }
        OpenState.CLOSED -> when {
            next == null -> "Closed"
            status.nextState == OpenState.OPEN -> "Closed · opens $next"
            else -> "Closed until $next"
        }
        OpenState.UNKNOWN -> {
            val what = when {
                status.holidayDependent -> "Hours uncertain (holiday)"
                status.comment == OPEN_END_COMMENT -> "Open, closing time unknown"
                status.comment != null -> "Hours uncertain: ${status.comment}"
                else -> "Hours uncertain"
            }
            if (next == null) what else "$what · until $next"
        }
    }
}

/** When [time] is, relative to [now]: "18:00", "midnight", "tomorrow 07:00", "Mon 07:00". */
fun formatWhen(time: LocalDateTime, now: LocalDateTime): String {
    val days = time.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay()
    return when {
        days == 0L -> time.format(timeFormatter)
        days == 1L && time.toLocalTime() == LocalTime.MIDNIGHT -> "midnight"
        days == 1L -> "tomorrow ${time.format(timeFormatter)}"
        else -> time.format(dayTimeFormatter)
    }
}

/** Popup description line (osmdroid renders it as HTML). */
fun poiHoursSnippet(info: PoiHoursInfo, now: LocalDateTime): String {
    val status = info.status ?: return "Opening hours:"
    return htmlEncode(describeOpeningStatus(status, now))
}

/** Popup detail lines (HTML): today's holiday plus the raw hours, one rule per line. */
fun poiHoursDetails(info: PoiHoursInfo): String {
    val lines = buildList {
        info.publicHoliday?.let { add("Today: $it") }
        addAll(info.raw.split(';').map { it.trim() }.filter { it.isNotEmpty() })
    }
    return lines.joinToString("<br>") { htmlEncode(it) }
}

private fun htmlEncode(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}
