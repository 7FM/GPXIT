package dev.gpxit.app.data.komoot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Top-level Komoot error hierarchy. ViewModels match on this to render user-facing strings. */
sealed class KomootError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No credentials saved — user must sign in first. */
    object MissingCredentials : KomootError("Komoot credentials not configured")
    /** 401 — wrong email/password. */
    object Unauthorized : KomootError("Komoot rejected the credentials")
    /** 403 — Komoot denied access. Tour may be private, or the request was rejected. */
    object Forbidden : KomootError("Komoot denied access — the tour may be private, or your account doesn't have permission")
    /** 404 — tour ID not found. */
    object NotFound : KomootError("Tour not found")
    /** 429 — rate limited even after one retry. */
    object RateLimited : KomootError("Komoot is rate-limiting requests; try again in a minute")
    /** Network / parse failure. */
    class Io(message: String, cause: Throwable? = null) : KomootError(message, cause)
}

/** Single point on a Komoot tour polyline. */
data class KomootCoord(val lat: Double, val lon: Double, val ele: Double?)

/** Stripped tour view used by the GPX builder. */
data class KomootTour(
    val id: Long,
    val name: String?,
    val coords: List<KomootCoord>,
)

/** Single tour row in the list endpoint. */
data class KomootTourSummary(
    val id: Long,
    val name: String,
    val sport: String?,
    val distanceMeters: Double,
    val durationSeconds: Long,
)

/** Result of a Komoot connectivity probe. */
data class PingResult(val message: String, val detectedUserId: String?)

/** Page of tour summaries from the list endpoint. */
data class KomootTourPage(
    val tours: List<KomootTourSummary>,
    val pageNumber: Int,
    val totalPages: Int,
) {
    val hasMore: Boolean get() = pageNumber < totalPages - 1
}

/**
 * Thin Komoot v007 HTTP client — Basic auth only. No external dependency.
 *
 * Public API:
 *  - [ping] — confirms creds and returns a display name.
 *  - [listPlannedTours] — paginated list of the user's planned tours.
 *  - [fetchTour] — one tour with embedded polyline + cues.
 *  - [resolveTourId] — fallback when [KomootUrlParser] doesn't recognise the URL.
 */
class KomootApi(private val userAgent: String) {

    /**
     * Verify the saved Basic-auth credentials and try to surface the
     * numeric user ID at the same time.
     *
     * - If [creds] already carries a userId, hit the tour-list with
     *   limit=1 to confirm both creds and user-ID are good.
     * - Otherwise call [resolveUserIdFromAccount]; if Komoot returns
     *   one we let the caller persist it via [KomootCredentialStore].
     *
     * Returns a tuple of (display message, detected user ID). The
     * detected user ID is non-null only when the call discovered a
     * fresh value the caller hasn't seen yet.
     */
    suspend fun ping(creds: KomootCredentials): PingResult = withContext(Dispatchers.IO) {
        if (!creds.userId.isNullOrBlank()) {
            val url = "$BASE/users/${creds.userId}/tours/?type=tour_planned&limit=1&page=0"
            getJson(url, creds, shareToken = null) // throws on non-2xx
            PingResult(message = "Connected as ${creds.email}", detectedUserId = null)
        } else {
            val detected = resolveUserIdFromAccount(creds)
            if (detected != null) {
                PingResult(
                    message = "Connected — user ID auto-detected ($detected)",
                    detectedUserId = detected,
                )
            } else {
                PingResult(
                    message = "Password OK, but Komoot didn't return a user ID. " +
                        "Import any of your own tours via share-link to auto-detect it.",
                    detectedUserId = null,
                )
            }
        }
    }

    /**
     * "Who am I" lookup over Basic auth. Confirmed live as of 2026-04
     * via the `KomootProbe` diagnostic against this account: only the
     * `v006/account/email/{email}/` family answers; v007 returns 404
     * Jetty for every shape; `users/me` returns a placeholder; and
     * `account/v1/account` doesn't exist. KomootGPX (apr 2026) and
     * kompy (apr 2026) both use the same v006 path.
     *
     * The trailing slash is mandatory — without it the server 404s.
     *
     * Response shape: `{"username":"<numeric>","password":"<token>","user":{…}}`.
     * `username` is the numeric user-ID despite the misleading key
     * name. `password` is a reusable session token; we ignore it
     * because the user's own password keeps working for Basic auth.
     */
    suspend fun resolveUserIdFromAccount(creds: KomootCredentials): String? =
        withContext(Dispatchers.IO) {
            val emailEnc = URLEncoder.encode(creds.email.trim().lowercase(), "UTF-8")
            val url = "https://api.komoot.de/v006/account/email/$emailEnc/"
            try {
                val json = getJson(url, creds, shareToken = null)
                val id = extractUserIdFromAccountJson(json)
                if (id == null) {
                    Log.w(
                        TAG,
                        "resolveUserIdFromAccount: 200 but no user-ID at $url " +
                            "(keys=${json.keys().asSequence().toList()})",
                    )
                }
                id
            } catch (e: KomootError.Unauthorized) {
                throw e
            } catch (e: KomootError) {
                Log.w(TAG, "resolveUserIdFromAccount: ${e.message} at $url")
                null
            }
        }

    /**
     * Try several common shapes for the user object in the response of
     * `/account/v1/account`. Komoot's HAL responses sometimes wrap the
     * user under `_embedded.user`, sometimes flatten it as `username`
     * or `id` directly. Returns the first numeric value found.
     */
    private fun extractUserIdFromAccountJson(json: JSONObject): String? {
        val direct = json.optString("username").takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        if (direct != null) return direct
        val flatId = json.optString("id").takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        if (flatId != null) return flatId
        val embeddedUser = json.optJSONObject("_embedded")?.optJSONObject("user")
        if (embeddedUser != null) {
            val u = embeddedUser.optString("username")
                .takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            if (u != null) return u
        }
        val userObj = json.optJSONObject("user")
        if (userObj != null) {
            val u = userObj.optString("username")
                .takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            if (u != null) return u
        }
        return null
    }

    suspend fun listPlannedTours(
        creds: KomootCredentials,
        page: Int,
        pageSize: Int = 50,
    ): KomootTourPage = withContext(Dispatchers.IO) {
        val userId = creds.userId?.takeIf { it.isNotBlank() }
            ?: throw KomootError.Io(
                "Add your Komoot user ID in Settings (find it in your profile URL: " +
                    "komoot.com/user/<id> when signed in)."
            )
        val url = "$BASE/users/$userId/tours/?type=tour_planned" +
            "&limit=$pageSize&page=$page&only_unlocked=true"
        val json = getJson(url, creds, shareToken = null)
        val toursArr = json.optJSONObject("_embedded")?.optJSONArray("tours")
        val tours = mutableListOf<KomootTourSummary>()
        if (toursArr != null) {
            for (i in 0 until toursArr.length()) {
                val t = toursArr.optJSONObject(i) ?: continue
                tours += KomootTourSummary(
                    id = t.optLong("id"),
                    name = t.optString("name").ifBlank { "Unnamed tour" },
                    sport = t.optString("sport").takeIf { it.isNotBlank() },
                    distanceMeters = t.optDouble("distance", 0.0),
                    durationSeconds = t.optLong("duration", 0L),
                )
            }
        }
        val pageObj = json.optJSONObject("page")
        KomootTourPage(
            tours = tours,
            pageNumber = pageObj?.optInt("number", page) ?: page,
            totalPages = pageObj?.optInt("totalPages", page + 1) ?: (page + 1),
        )
    }

    suspend fun fetchTour(ref: KomootRef, creds: KomootCredentials): KomootTour =
        withContext(Dispatchers.IO) {
            val url = "$BASE/tours/${ref.tourId}?_embedded=directions,coordinates"
            val json = getJson(url, creds, ref.shareToken)
            val name = json.optString("name").takeIf { it.isNotBlank() }
            val itemsArr = json.optJSONObject("_embedded")
                ?.optJSONObject("coordinates")
                ?.optJSONArray("items")
                ?: throw KomootError.Io("Tour response is missing coordinates")
            val coords = ArrayList<KomootCoord>(itemsArr.length())
            for (i in 0 until itemsArr.length()) {
                val c = itemsArr.optJSONObject(i) ?: continue
                coords += KomootCoord(
                    lat = c.optDouble("lat"),
                    lon = c.optDouble("lng"),
                    ele = if (c.has("alt")) c.optDouble("alt") else null,
                )
            }
            if (coords.isEmpty()) throw KomootError.Io("Tour has no coordinates")
            KomootTour(id = ref.tourId, name = name, coords = coords)
        }

    /**
     * Fallback path resolver: follow up to [MAX_REDIRECTS] HTTP redirects on
     * the public komoot.* host and re-apply [KomootUrlParser] at each hop.
     * Uses HEAD requests where the server tolerates them.
     */
    suspend fun resolveTourId(url: String): KomootRef? = withContext(Dispatchers.IO) {
        var current = url
        repeat(MAX_REDIRECTS) {
            KomootUrlParser.parse(current)?.let { return@withContext it }
            val u = try { URL(current) } catch (_: Exception) { return@withContext null }
            val host = u.host?.lowercase() ?: return@withContext null
            if (!KomootUrlParser.isKomootHost(host)) return@withContext null
            val conn = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", userAgent)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return@withContext null
                    current = if (location.startsWith("http", ignoreCase = true)) {
                        location
                    } else {
                        URL(u, location).toString()
                    }
                } else {
                    return@withContext null
                }
            } catch (_: Exception) {
                return@withContext null
            } finally {
                conn.disconnect()
            }
        }
        null
    }

    private suspend fun getJson(
        urlString: String,
        creds: KomootCredentials,
        shareToken: String?,
    ): JSONObject {
        val finalUrl = if (shareToken != null) {
            val sep = if (urlString.contains('?')) '&' else '?'
            "$urlString${sep}share_token=" + URLEncoder.encode(shareToken, "UTF-8")
        } else urlString

        var attempt = 0
        while (true) {
            val (code, body, retryAfter) = openAndRead(finalUrl, creds)
            when (code) {
                200 -> {
                    return try {
                        JSONObject(body)
                    } catch (e: Exception) {
                        throw KomootError.Io("Bad JSON from Komoot: ${e.message}", e)
                    }
                }
                401 -> throw KomootError.Unauthorized
                403 -> throw KomootError.Forbidden
                404 -> throw KomootError.NotFound
                429 -> {
                    if (attempt >= 1) throw KomootError.RateLimited
                    attempt++
                    delay(retryAfter.coerceAtLeast(1) * 1000L)
                }
                else -> throw KomootError.Io("Komoot HTTP $code")
            }
        }
    }

    private data class HttpResult(val code: Int, val body: String, val retryAfter: Int)

    private fun openAndRead(urlString: String, creds: KomootCredentials): HttpResult {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/hal+json")
            setRequestProperty("Authorization", creds.toBasicAuthHeader())
        }
        return try {
            val code = conn.responseCode
            val retryAfter = conn.getHeaderField("Retry-After")?.toIntOrNull() ?: 2
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) {
                val snippet = body.take(300).replace('\n', ' ')
                Log.w(TAG, "HTTP $code on $urlString — body: $snippet")
            } else {
                Log.d(TAG, "HTTP 200 on $urlString (${body.length} bytes)")
            }
            HttpResult(code, body, retryAfter)
        } catch (e: IOException) {
            Log.w(TAG, "io error on $urlString: ${e.message}")
            throw KomootError.Io("Network error: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val BASE = "https://external-api.komoot.de/v007"
        const val MAX_REDIRECTS = 3
        const val TAG = "KomootApi"
    }
}
