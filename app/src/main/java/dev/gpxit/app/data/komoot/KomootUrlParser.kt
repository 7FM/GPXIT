package dev.gpxit.app.data.komoot

import java.net.URI

/**
 * Reference to a Komoot tour, derived from a public URL.
 *
 * `shareToken` is the opaque token Komoot embeds in share links to grant
 * access to private-but-shared tours. It is undocumented on the
 * `external-api.komoot.de/v007/` endpoints but is preserved here and
 * passed through as a query parameter on tour fetches — if Komoot ignores
 * it, the request still works for public tours; if Komoot honours it,
 * the user can import a friend's privately-shared tour.
 */
data class KomootRef(val tourId: Long, val shareToken: String? = null)

/**
 * Offline parser for Komoot tour URLs.
 *
 * Recognised forms (case-insensitive, with or without `https://`,
 * with or without `www.`, with optional locale segment like `/de-de`):
 *   - `komoot.com/tour/<id>`
 *   - `komoot.de/tour/<id>`
 *   - `.../invite-tour/<id>`
 *   - `.../smarttour/<id>`
 *
 * Trailing query string is parsed for `share_token`.
 *
 * URLs that don't match (e.g. future share-format variations) return
 * null — `KomootApi.resolveTourId` is the redirect-follow fallback.
 */
object KomootUrlParser {

    private val PATH_REGEX = Regex(
        "^(?:/[a-z]{2}-[a-z]{2})?/(tour|invite-tour|smarttour)/(\\d+)/?$",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String?): KomootRef? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()

        // Tolerate plain "komoot.com/tour/123" without a scheme — pasted
        // from a chat client that strips the protocol.
        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        if (!isKomootHost(host)) return null

        val path = uri.rawPath ?: return null
        val match = PATH_REGEX.matchEntire(path) ?: return null
        val tourId = match.groupValues[2].toLongOrNull() ?: return null

        val shareToken = uri.rawQuery?.let { parseShareToken(it) }
        return KomootRef(tourId, shareToken)
    }

    fun isKomootHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "komoot.com" || h == "www.komoot.com" ||
            h == "komoot.de" || h == "www.komoot.de"
    }

    /**
     * Extract a numeric Komoot user ID from either a bare digit string
     * or a `komoot.com/user/<id>` profile URL. Returns null on garbage.
     */
    fun parseUserId(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        if (trimmed.all { it.isDigit() }) return trimmed
        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) trimmed else "https://$trimmed"
        val uri = try { URI(withScheme) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        if (!isKomootHost(host)) return null
        val path = uri.rawPath ?: return null
        val match = USER_PATH_REGEX.matchEntire(path) ?: return null
        return match.groupValues[1]
    }

    private val USER_PATH_REGEX = Regex(
        "^(?:/[a-z]{2}-[a-z]{2})?/user/(\\d+)/?$",
        RegexOption.IGNORE_CASE,
    )

    private fun parseShareToken(rawQuery: String): String? {
        for (pair in rawQuery.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = pair.substring(0, eq)
            if (k.equals("share_token", ignoreCase = true)) {
                val v = pair.substring(eq + 1)
                if (v.isBlank()) return null
                return java.net.URLDecoder.decode(v, "UTF-8")
            }
        }
        return null
    }
}
