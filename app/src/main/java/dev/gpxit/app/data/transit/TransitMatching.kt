package dev.gpxit.app.data.transit

import dev.gpxit.app.data.gpx.haversineMeters
import dev.gpxit.app.domain.StationCandidate
import dev.gpxit.app.domain.TrainConnection
import java.text.Normalizer
import kotlin.math.abs

/**
 * Collapse duplicate stations so the map isn't cluttered.
 *
 * Within one backend: near major hubs the DB API returns the Hbf plus many
 * siblings ("… Gleis 3", "… Vorplatz", bus islands under the same product set)
 * that mean the same stop to a cyclist. Two stations are treated as the same
 * cluster when their names have a word-boundary prefix relationship AND they
 * sit within [proximityMeters] of each other; the one with the shortest name
 * wins.
 *
 * Across backends, [isSameStation] decides. The copy from the backend with the
 * lower [priority] wins and remembers the other ids in
 * [StationCandidate.alternateIds].
 */
internal fun clusterStations(
    stations: List<StationCandidate>,
    proximityMeters: Double = 400.0,
    priority: (String) -> Int = { 0 },
): List<StationCandidate> {
    if (stations.size <= 1) return stations
    val sorted = stations.sortedWith(compareBy({ priority(it.backendId) }, { it.name.length }))
    val kept = ArrayList<StationCandidate>(sorted.size)
    for (st in sorted) {
        val index = kept.indexOfFirst { existing -> isSameStation(existing, st, proximityMeters) }
        if (index < 0) {
            kept += st
        } else {
            kept[index] = absorb(kept[index], st)
        }
    }
    return kept
}

/**
 * Whether two stations are the same place to a cyclist. Rules for stations
 * from different backends follow KPublicTransport's `Location::isSame`.
 */
internal fun isSameStation(
    a: StationCandidate,
    b: StationCandidate,
    proximityMeters: Double = 400.0,
): Boolean {
    val distance = haversineMeters(a.lat, a.lon, b.lat, b.lon)
    if (a.backendId == b.backendId) {
        return a.id == b.id || namesRelatedWithin(a, b, distance, proximityMeters)
    }
    // Ids at the other's backend, known from an earlier merge, decide — unless
    // they differ, then it's at most a sibling (platform, bus island) of the
    // merged station, which the same-backend name rule handles.
    val idOfBAtA = a.alternateIds[b.backendId]
    val idOfAAtB = b.alternateIds[a.backendId]
    if (idOfBAtA != null || idOfAAtB != null) {
        val conflict = (idOfBAtA != null && idOfBAtA != b.id) || (idOfAAtB != null && idOfAAtB != a.id)
        return !conflict || namesRelatedWithin(a, b, distance, proximityMeters)
    }
    if (distance > MAX_SAME_STATION_DISTANCE) return false
    if (normalizeStationName(a.name) == normalizeStationName(b.name)) return true
    if (namesRelatedWithin(a, b, distance, proximityMeters)) return true
    val threshold = if (a.isRail() && b.isRail()) RAIL_SAME_STATION_DISTANCE else OTHER_SAME_STATION_DISTANCE
    return distance < threshold
}

private fun namesRelatedWithin(
    a: StationCandidate,
    b: StationCandidate,
    distance: Double,
    proximityMeters: Double,
): Boolean {
    val baseA = a.name.stationNameBase()
    val baseB = b.name.stationNameBase()
    if (baseA.isEmpty() || baseB.isEmpty()) return false
    return namesRelated(baseA, baseB) && distance < proximityMeters
}

private fun absorb(survivor: StationCandidate, other: StationCandidate): StationCandidate {
    if (other.backendId == survivor.backendId) return survivor
    val ids = LinkedHashMap(survivor.alternateIds)
    ids.putIfAbsent(other.backendId, other.id)
    for ((backend, id) in other.alternateIds) {
        if (backend != survivor.backendId) ids.putIfAbsent(backend, id)
    }
    return survivor.copy(
        alternateIds = ids,
        products = survivor.products + other.products,
    )
}

private fun StationCandidate.isRail(): Boolean =
    products.any { name -> TransitMode.TRAINS.any { it.name == name } }

/** Further apart than this is never the same station. */
private const val MAX_SAME_STATION_DISTANCE = 1000.0

/** Two rail stations this close are the same (there is no room for another one). */
private const val RAIL_SAME_STATION_DISTANCE = 300.0

private const val OTHER_SAME_STATION_DISTANCE = 25.0

/** True if `a` equals `b`, or one is a prefix of the other up to a word boundary. */
internal fun namesRelated(a: String, b: String): Boolean {
    if (a == b) return true
    val (shorter, longer) = if (a.length < b.length) a to b else b to a
    if (!longer.startsWith(shorter)) return false
    val nextCh = longer.getOrNull(shorter.length) ?: return true
    return nextCh == ' ' || nextCh == ',' || nextCh == '(' || nextCh == '-' || nextCh == '/'
}

/** Normalize a station name to its comparable base form. */
internal fun String.stationNameBase(): String =
    this.replace(Regex("\\s*\\([^)]*\\)"), "")   // drop parentheticals: " (Vorplatz)"
        .replace(Regex(",.*"), "")                 // drop trailing ", City"
        .trim()
        .lowercase()

/**
 * Name as a sorted set of lower-case words without diacritics, so "Lyon Part-Dieu"
 * matches "Lyon Part Dieu" and "Zürich HB" matches "Zurich HB".
 */
internal fun normalizeStationName(name: String): String {
    val decomposed = Normalizer.normalize(name.lowercase().replace("ß", "ss"), Normalizer.Form.NFD)
    return decomposed
        .replace(Regex("\\p{M}+"), "")
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() }
        .toSortedSet()
        .joinToString(" ")
}

/**
 * Merge trip results from several backends, dropping duplicates. Two trips are
 * the same when they have the same number of vehicle legs and each pair of legs
 * departs and arrives within a minute of the other and is the same line or
 * leaves from the same stop (after KPublicTransport's `JourneySection::isSame`).
 * Of duplicates, the one with delay data wins, then the higher-priority backend.
 */
internal fun mergeConnections(
    results: List<List<TrainConnection>>,
    priority: (String) -> Int = { 0 },
): List<TrainConnection> {
    val all = results.flatten().sortedWith(
        compareBy<TrainConnection>({ it.departureTime }, { if (it.hasDelayData()) 0 else 1 }, { priority(it.backendId) })
    )
    val kept = ArrayList<TrainConnection>(all.size)
    for (connection in all) {
        val duplicate = kept.indexOfFirst { isSameConnection(it, connection) }
        if (duplicate < 0) {
            kept += connection
        } else if (!kept[duplicate].hasDelayData() && connection.hasDelayData()) {
            kept[duplicate] = connection
        }
    }
    return kept.sortedBy { it.departureTime }
}

internal fun isSameConnection(a: TrainConnection, b: TrainConnection): Boolean {
    val legsA = a.legs.filterNot { it.isWalk }
    val legsB = b.legs.filterNot { it.isWalk }
    if (legsA.isEmpty() || legsA.size != legsB.size) return false
    return legsA.zip(legsB).all { (x, y) ->
        abs(x.departureTime.epochSecond - y.departureTime.epochSecond) <= SAME_TIME_SECONDS &&
            abs(x.arrivalTime.epochSecond - y.arrivalTime.epochSecond) <= SAME_TIME_SECONDS &&
            (sameLine(x.line, y.line) ||
                normalizeStationName(x.departureStation) == normalizeStationName(y.departureStation))
    }
}

private const val SAME_TIME_SECONDS = 60L

/** Same train/bus number, e.g. "ICE 592" vs "ICE592", "TER 886118" vs "886118". */
private fun sameLine(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    if (a.equals(b, ignoreCase = true)) return true
    val numberA = LINE_NUMBER.findAll(a).lastOrNull()?.value ?: return false
    val numberB = LINE_NUMBER.findAll(b).lastOrNull()?.value ?: return false
    return numberA == numberB
}

private val LINE_NUMBER = Regex("\\d+")

private fun TrainConnection.hasDelayData(): Boolean =
    legs.any { it.departureDelayMinutes != null || it.arrivalDelayMinutes != null }
