package dev.gpxit.app.data.gpx

import dev.gpxit.app.domain.RoutePoint

/**
 * Sum of positive vs negative elevation deltas along the route, in
 * whole meters. Skips segments where either endpoint has no elevation
 * sample so a missing barometer reading doesn't pollute the totals.
 */
fun routeClimbDescentMeters(points: List<RoutePoint>): Pair<Int, Int> {
    var climb = 0.0
    var descent = 0.0
    var prev: Double? = null
    for (p in points) {
        val ele = p.elevation
        if (ele != null && prev != null) {
            val diff = ele - prev
            if (diff > 0) climb += diff else descent += -diff
        }
        if (ele != null) prev = ele
    }
    return climb.toInt() to descent.toInt()
}

/**
 * Try to split a GPX track name into `(from, to)`. Recognises a few
 * common shapes Komoot / manual exports tend to produce:
 *   "From Darmstadt to Mannheim"
 *   "Darmstadt - Mannheim"
 *   "Darmstadt → Mannheim"
 *   "From_Darmstadt_to_Mannheim" (underscores → spaces first)
 * Returns `name → null` for anything we can't split, in which case
 * callers should display the cleaned name as-is.
 */
fun splitRouteName(name: String?): Pair<String, String?> {
    if (name.isNullOrBlank()) return "" to null
    val cleaned = name.replace('_', ' ').trim()
    val fromTo = Regex("""^From\s+(.+?)\s+to\s+(.+)$""", RegexOption.IGNORE_CASE)
        .matchEntire(cleaned)
    if (fromTo != null) {
        return fromTo.groupValues[1].trim() to fromTo.groupValues[2].trim()
    }
    val arrow = Regex("""^(.+?)\s*[→\-–—]\s*(.+)$""").matchEntire(cleaned)
    if (arrow != null) {
        return arrow.groupValues[1].trim() to arrow.groupValues[2].trim()
    }
    return cleaned to null
}
