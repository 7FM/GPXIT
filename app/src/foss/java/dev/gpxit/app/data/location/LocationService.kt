package dev.gpxit.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * FOSS location service using Android's built-in LocationManager.
 * No Google Play Services dependency.
 *
 * Initial-fix strategy: subscribe to GPS, NETWORK and PASSIVE at the
 * same time. GPS cold-starts can take 30–90 s outdoors and even longer
 * indoors, so while we wait for the first GPS sample we happily accept
 * coarse network / passive fixes as a seed. Once GPS starts emitting,
 * the staleness filter naturally phases the coarser sources out (GPS
 * fixes are always the newest elapsedRealtimeNanos).
 */
class LocationService(context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMs: Long = 5000): Flow<Location> = callbackFlow {
        var lastEmittedElapsedNanos = 0L

        // Only accept a location if it's newer than whatever we've
        // already shipped upstream. Without this, a stale PASSIVE fix
        // could overwrite a just-arrived GPS fix and make the blue dot
        // jitter backwards in time.
        fun emitIfNewer(loc: Location) {
            val t = loc.elapsedRealtimeNanos
            if (t <= lastEmittedElapsedNanos) return
            lastEmittedElapsedNanos = t
            trySend(loc)
        }

        val listener = LocationListener { loc -> emitIfNewer(loc) }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).filter {
            try {
                locationManager.isProviderEnabled(it)
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        // minTime=0 so the first fix from each provider isn't held back
        // by the throttle window — we want the lock as soon as the
        // hardware has it.
        providers.forEach { provider ->
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }

        // Seed immediately from whichever provider has the freshest
        // cached fix. Gate on age so we don't teleport the user to
        // yesterday's position — MAX_LAST_KNOWN_AGE_MS is tight enough
        // that anything older is almost always worse than the hollow
        // "searching" chevron.
        val seed = providers
            .mapNotNull {
                try {
                    locationManager.getLastKnownLocation(it)
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            .filter { it.isFresh() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        seed?.let { emitIfNewer(it) }

        awaitClose {
            locationManager.removeUpdates(listener)
        }
    }

    @SuppressLint("MissingPermission")
    fun getLastLocation(onResult: (Location?) -> Unit) {
        val location = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
            .mapNotNull {
                try {
                    locationManager.getLastKnownLocation(it)
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            .filter { it.isFresh() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        onResult(location)
    }
}

private const val MAX_LAST_KNOWN_AGE_MS = 2 * 60 * 1000L

private fun Location.isFresh(): Boolean {
    val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L
    return ageMs in 0..MAX_LAST_KNOWN_AGE_MS
}
