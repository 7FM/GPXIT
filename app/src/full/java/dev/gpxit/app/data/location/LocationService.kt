package dev.gpxit.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocationService(context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Emits location updates. Caller must ensure ACCESS_FINE_LOCATION permission is granted.
     *
     * Initial-fix strategy: as soon as the flow is collected we
     *   1) emit the cached `lastLocation` (if fresh) so the UI has
     *      something to show instantly,
     *   2) kick `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` which
     *      forces the fused provider to wake the GPS and deliver a
     *      fresh single fix — much faster than waiting for the first
     *      periodic sample from `requestLocationUpdates`, and
     *   3) start the regular update stream.
     *
     * All three paths feed the same newer-wins filter so a late
     * last-known or current-location callback can't overwrite a fix
     * that already arrived from the main stream.
     */
    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMs: Long = 5000): Flow<Location> = callbackFlow {
        var lastEmittedElapsedNanos = 0L

        fun emitIfNewer(loc: Location) {
            val t = loc.elapsedRealtimeNanos
            if (t <= lastEmittedElapsedNanos) return
            lastEmittedElapsedNanos = t
            trySend(loc)
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            // Don't hold back the very first sample waiting for a
            // high-accuracy fix — a quick coarse one now beats a
            // perfect one in 30 seconds. Subsequent updates still
            // follow PRIORITY_HIGH_ACCURACY.
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { emitIfNewer(it) }
            }
        }

        fusedClient.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())

        // 1) Seed from lastLocation — zero-cost fix the system already has.
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && loc.isFresh()) emitIfNewer(loc)
        }

        // 2) Force a fresh single fix. The fused provider interprets
        // this as "turn the GPS on right now" instead of amortising
        // the cold-start cost across the interval cadence.
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) emitIfNewer(loc)
            }

        awaitClose {
            fusedClient.removeLocationUpdates(callback)
        }
    }

    /**
     * Get a single last-known location. May return null if no location is cached.
     */
    @SuppressLint("MissingPermission")
    fun getLastLocation(onResult: (Location?) -> Unit) {
        fusedClient.lastLocation.addOnSuccessListener { onResult(it) }
    }
}

private const val MAX_LAST_KNOWN_AGE_MS = 2 * 60 * 1000L

private fun Location.isFresh(): Boolean {
    val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L
    return ageMs in 0..MAX_LAST_KNOWN_AGE_MS
}
