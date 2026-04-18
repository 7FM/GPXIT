package dev.gpxit.app.data.routing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import btools.routingapp.IBRouterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.util.GeoPoint
import kotlin.coroutines.resume

/**
 * Thin wrapper around BRouter's AIDL service (github.com/abrensch/brouter).
 *
 * BRouter is a fully-offline, bike-aware OSM router shipped as a
 * separate app. This client binds to its service, calls
 * [IBRouterService.getTrackFromParams] with a list of waypoints, and
 * parses the returned GPX into a polyline of [GeoPoint]s.
 *
 * If the BRouter app isn't installed (or isn't new enough to expose
 * the service) the client returns null and the caller should fall
 * back to a straight line.
 */
class BRouterClient(private val context: Context) {

    /**
     * Route from [start] to [end] using BRouter's bike-safe ("trekking")
     * profile. Returns the list of GeoPoints along the computed track,
     * or null if BRouter isn't available / routing fails.
     *
     * Call from a coroutine on [Dispatchers.IO] — binding + routing can
     * take several seconds on a cold BRouter process.
     */
    suspend fun routeBike(
        start: GeoPoint,
        end: GeoPoint,
    ): List<GeoPoint>? = withContext(Dispatchers.IO) {
        val binder = bindWithTimeout() ?: run {
            Log.w(TAG, "could not bind to BRouter service")
            return@withContext null
        }
        try {
            // BRouter's simple-param API: v picks the vehicle class and
            // fast toggles fast vs safe. "bicycle" + "0" resolves to the
            // trekking profile internally. v = "trekking" would have
            // been wrong — `v` only accepts motorcar|bicycle|foot, and
            // anything else leaves BRouter with no profile to load.
            val params = Bundle().apply {
                putDoubleArray("lats", doubleArrayOf(start.latitude, end.latitude))
                putDoubleArray("lons", doubleArrayOf(start.longitude, end.longitude))
                putString("trackFormat", "gpx")
                putString("v", "bicycle")
                putString("fast", "0")
                putString("maxRunningTime", "30")
            }
            Log.i(TAG, "requesting BRouter route ($start -> $end)")
            val track = try {
                withTimeoutOrNull(45_000) {
                    binder.getTrackFromParams(params)
                }
            } catch (e: Exception) {
                Log.w(TAG, "BRouter call failed: ${e.message}")
                null
            }
            if (track.isNullOrBlank() || !track.contains("<trkpt", ignoreCase = true)) {
                if (track.isNullOrBlank()) {
                    Log.w(TAG, "BRouter returned no response")
                } else {
                    // BRouter returns an error string here when routing
                    // can't happen — typically "no segment data for
                    // this region, download the area in BRouter first".
                    Log.w(TAG, "BRouter error: ${track.take(300)}")
                }
                return@withContext null
            }
            val points = parseGpxPolyline(track)
            Log.i(TAG, "BRouter returned ${points.size} points")
            points
        } finally {
            unbind()
        }
    }

    /** True if the BRouter app is installed. Cheap package-manager lookup. */
    fun isInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(BROUTER_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    // ---- internals --------------------------------------------------

    @Volatile private var boundService: IBRouterService? = null
    private var connection: ServiceConnection? = null

    private suspend fun bindWithTimeout(): IBRouterService? {
        if (!isInstalled()) return null
        return withTimeoutOrNull(10_000) { bind() }
    }

    private suspend fun bind(): IBRouterService? = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val svc = IBRouterService.Stub.asInterface(service)
                boundService = svc
                if (cont.isActive) cont.resume(svc)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                boundService = null
            }
        }
        connection = conn

        val intent = Intent("btools.routingapp.IBRouterService").apply {
            setPackage(BROUTER_PACKAGE)
        }
        val started = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "bindService failed: ${e.message}")
            false
        }
        if (!started) {
            context.unbindService(conn)
            connection = null
            if (cont.isActive) cont.resume(null)
        }
        cont.invokeOnCancellation { unbind() }
    }

    private fun unbind() {
        val c = connection ?: return
        try {
            context.unbindService(c)
        } catch (_: Exception) {
        }
        connection = null
        boundService = null
    }

    private fun parseGpxPolyline(gpx: String): List<GeoPoint> {
        // Simpler to pull <trkpt> attributes with a regex than to stand
        // up the full GPX parser — we only care about the raw polyline
        // geometry, not names, times, or elevation.
        val regex = Regex(
            """<trkpt[^>]*lat=["']([-\d.]+)["'][^>]*lon=["']([-\d.]+)["']""",
            RegexOption.IGNORE_CASE
        )
        val out = ArrayList<GeoPoint>()
        for (m in regex.findAll(gpx)) {
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            out += GeoPoint(lat, lon)
        }
        return out
    }

    companion object {
        private const val TAG = "BRouterClient"
        private const val BROUTER_PACKAGE = "btools.routingapp"
    }
}
