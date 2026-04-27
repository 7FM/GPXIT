package dev.gpxit.app.data.komoot

/**
 * Build a minimal GPX 1.1 document from a [KomootTour].
 *
 * Output is a single `<trk>` with one `<trkseg>` containing one
 * `<trkpt>` per coordinate. Elevation is emitted as `<ele>` when
 * Komoot supplied it. The downstream [GpxParser] consumes track
 * points and ignores anything else, so directions / cues are
 * deliberately omitted (they'd become `<wpt>` markers — fine to add
 * once a "show turn cues" feature lands).
 */
object KomootGpxBuilder {

    fun build(tour: KomootTour): ByteArray {
        val sb = StringBuilder(64 + tour.coords.size * 80)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<gpx version=\"1.1\" creator=\"GPXIT\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
        )
        sb.append("  <metadata><name>")
        sb.append(escape(tour.name ?: "Komoot tour ${tour.id}"))
        sb.append("</name></metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>")
        sb.append(escape(tour.name ?: "Komoot tour ${tour.id}"))
        sb.append("</name>\n")
        sb.append("    <trkseg>\n")
        for (c in tour.coords) {
            sb.append("      <trkpt lat=\"")
            sb.append(c.lat)
            sb.append("\" lon=\"")
            sb.append(c.lon)
            sb.append("\">")
            if (c.ele != null) {
                sb.append("<ele>")
                sb.append(c.ele)
                sb.append("</ele>")
            }
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escape(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
