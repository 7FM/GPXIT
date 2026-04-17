package dev.gpxit.app.data.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.gpxit.app.MainActivity
import dev.gpxit.app.data.RouteStorage
import dev.gpxit.app.data.gpx.GpxParser
import dev.gpxit.app.data.gpx.findClosestPointIndex
import dev.gpxit.app.data.location.LocationService
import dev.gpxit.app.data.prefs.PrefsRepository
import dev.gpxit.app.domain.RouteInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that keeps a GPS fix running while the user rides,
 * posts a progress notification, and exposes live progress to the UI
 * via [state]. The app launches it explicitly from the map; the user
 * can stop it either from the in-app button or the notification action.
 *
 * Lifecycle is user-driven — there is no auto-start on boot, no
 * auto-resume on reimport. The service dies when `stopService()` (or
 * the notification's Stop action) is called, or when the system kills
 * the process.
 */
class TripTrackingService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var locationJob: Job? = null
    private var route: RouteInfo? = null
    private var avgSpeedKmh: Double = 18.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (locationJob?.isActive == true) return
        createChannel()
        startInForeground(buildNotification(null))
        _state.value = TripState(isActive = true, snapshot = null)

        locationJob = scope.launch {
            // Load persisted route + avg speed off the main thread.
            route = withContext(Dispatchers.IO) {
                val rs = RouteStorage(applicationContext)
                if (rs.hasRoute()) {
                    try {
                        rs.loadGpxStream().use { GpxParser.parse(it) }.let { loaded ->
                            loaded.copy(stations = rs.loadStations())
                        }
                    } catch (_: Exception) {
                        null
                    }
                } else null
            }
            avgSpeedKmh = withContext(Dispatchers.IO) {
                PrefsRepository(applicationContext).preferences.first().avgSpeedKmh
            }

            LocationService(applicationContext)
                .locationUpdates(intervalMs = 5_000)
                .collect { loc -> handleLocation(loc) }
        }
    }

    private fun handleLocation(loc: Location) {
        val snap = computeSnapshot(route, loc, avgSpeedKmh)
        _state.value = TripState(isActive = true, snapshot = snap)
        updateNotification(snap)
    }

    private fun stopTracking() {
        locationJob?.cancel()
        locationJob = null
        _state.value = TripState(isActive = false, snapshot = null)
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: must declare the specific foregroundServiceType at runtime.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(snap: TripSnapshot?): Notification {
        val stopIntent = Intent(this, TripTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = snap?.routeName?.takeIf { it.isNotBlank() } ?: "Trip tracking"
        val text = snapshotLine(snap)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun snapshotLine(snap: TripSnapshot?): String {
        if (snap == null) return "Waiting for GPS\u2026"
        val dist = "%.1f / %.1f km".format(
            snap.currentDistanceMeters / 1000.0,
            snap.totalDistanceMeters / 1000.0
        )
        val station = snap.nextStationLabel?.let { " \u2022 $it" }.orEmpty()
        val offRoute = if (snap.distanceFromRouteMeters > 300.0)
            " \u2022 off-route"
        else ""
        return dist + station + offRoute
    }

    private fun updateNotification(snap: TripSnapshot?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Gate on notification permission — on API 33+ the app needs it and
        // the user may have denied; without it, notify() silently no-ops.
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (!canPost) return
        nm.notify(NOTIFICATION_ID, buildNotification(snap))
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Trip tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Live progress while a ride is being tracked"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopTracking()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.gpxit.app.tracking.ACTION_START"
        const val ACTION_STOP = "dev.gpxit.app.tracking.ACTION_STOP"
        const val CHANNEL_ID = "trip_tracking"
        const val NOTIFICATION_ID = 42

        private val _state = MutableStateFlow(TripState())
        /** Live service state — safe to observe from the UI process. */
        val state: StateFlow<TripState> = _state

        /** Kick off tracking from anywhere in the app. */
        fun start(context: Context) {
            val i = Intent(context, TripTrackingService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, i)
        }

        /** Cancel tracking. Safe to call when the service is already stopped. */
        fun stop(context: Context) {
            val i = Intent(context, TripTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            // startService is fine here — the service handles ACTION_STOP and
            // calls stopSelf(). Avoids the "not started" exception that
            // stopService() throws when nothing is running.
            try {
                context.startService(i)
            } catch (_: Exception) {
            }
        }
    }
}

data class TripState(
    val isActive: Boolean = false,
    val snapshot: TripSnapshot? = null,
)

data class TripSnapshot(
    val routeName: String?,
    val currentDistanceMeters: Double,
    val totalDistanceMeters: Double,
    val remainingMeters: Double,
    val distanceFromRouteMeters: Double,
    val nextStationLabel: String?,
)

/** Turn a live location + route into a human-readable progress snapshot. */
internal fun computeSnapshot(
    route: RouteInfo?,
    location: Location,
    avgSpeedKmh: Double
): TripSnapshot {
    if (route == null || route.points.isEmpty()) {
        return TripSnapshot(
            routeName = route?.name,
            currentDistanceMeters = 0.0,
            totalDistanceMeters = 0.0,
            remainingMeters = 0.0,
            distanceFromRouteMeters = 0.0,
            nextStationLabel = null,
        )
    }
    val (idx, perpDist) = findClosestPointIndex(
        route.points, location.latitude, location.longitude
    )
    val current = route.points[idx].distanceFromStart
    val total = route.totalDistanceMeters
    val remaining = (total - current).coerceAtLeast(0.0)

    val speedMs = (avgSpeedKmh * 1000.0 / 3600.0).coerceAtLeast(0.1)
    val nextStation = route.stations.firstOrNull {
        it.distanceAlongRouteMeters > current + 50.0
    }
    val nextLabel = nextStation?.let {
        val distTo = (it.distanceAlongRouteMeters - current).coerceAtLeast(0.0)
        val etaMin = (distTo / speedMs / 60.0).toInt()
        "next ${it.name} ~${etaMin}min"
    }

    return TripSnapshot(
        routeName = route.name,
        currentDistanceMeters = current,
        totalDistanceMeters = total,
        remainingMeters = remaining,
        distanceFromRouteMeters = perpDist,
        nextStationLabel = nextLabel,
    )
}
