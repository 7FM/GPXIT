package dev.gpxit.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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
     */
    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMs: Long = 5000): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }

        fusedClient.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())

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
