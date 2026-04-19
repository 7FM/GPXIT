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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.gpxit.app.MainActivity
import dev.gpxit.app.R
import dev.gpxit.app.data.RouteStorage
import dev.gpxit.app.data.gpx.GpxParser
import dev.gpxit.app.data.gpx.findClosestPointIndex
import dev.gpxit.app.data.location.LocationService
import dev.gpxit.app.data.prefs.PrefsRepository
import dev.gpxit.app.domain.RouteInfo
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
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
    private var recJob: Job? = null
    private var route: RouteInfo? = null
    private var avgSpeedKmh: Double = 18.0
    private var currentSnapshot: TripSnapshot? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action} startId=$startId")
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
        Log.i(TAG, "startTracking: enter")
        if (locationJob?.isActive == true) {
            Log.i(TAG, "startTracking: already active, ignoring")
            return
        }
        try {
            createChannel()
            Log.i(TAG, "startTracking: channel ok")
        } catch (t: Throwable) {
            Log.e(TAG, "createChannel failed", t)
            stopSelf()
            return
        }
        // Seed the first post with whatever home recommendation is
        // already cached. Without this the very first notification is
        // always "Waiting for GPS…" even when "Take me home" ran
        // *before* tracking started, and the home line doesn't appear
        // until the user opens the peek again.
        val initialRec = activeHomeRecommendation()
        val notif = try {
            buildNotification(null, initialRec)
        } catch (t: Throwable) {
            Log.e(TAG, "buildNotification failed", t)
            stopSelf()
            return
        }
        Log.i(TAG, "startTracking: notification built")
        try {
            startInForeground(notif)
            Log.i(TAG, "startTracking: startForeground returned")
        } catch (t: Throwable) {
            // startForeground throws SecurityException on API 34+ when
            // location permission isn't granted at call-time, or
            // ForegroundServiceStartNotAllowedException when the
            // system decides the start wasn't backed by a visible
            // app state. Log the real cause so we can see the stack
            // instead of letting the service die silently with only a
            // "Cannot find enqueued record" from NotificationService.
            Log.e(TAG, "startForeground failed", t)
            stopSelf()
            return
        }
        _state.value = TripState(
            isActive = true,
            snapshot = null,
            homeRecommendation = activeHomeRecommendation()
        )
        Log.i(TAG, "startTracking: state=active")

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

        // Mirror the home-recommendation flow so the notification picks
        // up "Take me home" results without waiting for the next GPS fix.
        // drop(1) skips the StateFlow's initial replay — firing
        // updateNotification immediately after startForeground races
        // with the system's FGS notification post on Android 15 and
        // can cause the first post to silently fail ("Cannot find
        // enqueued record for key: 42"). We only want to react to
        // real changes published by GpxitApp anyway.
        recJob?.cancel()
        recJob = scope.launch {
            _homeRecommendation.drop(1).collect { rec ->
                _state.value = _state.value.copy(homeRecommendation = rec)
                updateNotification(currentSnapshot, rec)
            }
        }
    }

    private fun handleLocation(loc: Location) {
        val snap = computeSnapshot(route, loc, avgSpeedKmh)
        currentSnapshot = snap
        val rec = activeHomeRecommendation()
        _state.value = TripState(isActive = true, snapshot = snap, homeRecommendation = rec)
        updateNotification(snap, rec)
    }

    private fun activeHomeRecommendation(): HomeRecommendation? {
        val rec = _homeRecommendation.value ?: return null
        // Drop a stale recommendation once the train has already left.
        val dep = rec.departureTime ?: return rec
        return if (Instant.now().isAfter(dep.plusSeconds(60))) null else rec
    }

    private fun stopTracking() {
        Log.i(TAG, "stopTracking: enter (wasActive=${_state.value.isActive})")
        locationJob?.cancel()
        locationJob = null
        recJob?.cancel()
        recJob = null
        currentSnapshot = null
        _state.value = TripState(
            isActive = false,
            snapshot = null,
            homeRecommendation = null
        )
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

    private fun buildNotification(
        snap: TripSnapshot?,
        rec: HomeRecommendation?
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, TripTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        // getForegroundService is the canonical choice when the target
        // is itself a foreground service — it routes through
        // startForegroundService, which Android 12+ permits even when
        // the notification-shade tap happens with the app in the
        // background. getService() is silently blocked on some devices
        // under those conditions, which presents as "nothing happens"
        // or, on a few OEMs, as the action being suppressed entirely.
        val stopPending = PendingIntent.getForegroundService(
            this, REQ_STOP, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = snap?.routeName?.takeIf { it.isNotBlank() } ?: "Trip tracking"
        val primary = snapshotLine(snap, rec)
        val home = homeLine(rec)
        val contentText = primary
        // Collapsed view shows just the progress line; expanding the
        // notification adds the train-catch details when "Take me home"
        // has picked a connection.
        val bigText = if (home != null) "$primary\n$home" else primary

        // Use a platform-provided icon for the Stop action so we
        // sidestep any chance that a locally-declared vector drawable
        // is failing to render in the system-UI process (which loads
        // action icons out-of-process and has historically been picky
        // about vector parsing on certain OEM Android builds).
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.tracking_stop),
            stopPending
        ).build()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openPending)
            .addAction(stopAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Lock-screen visibility has to be set both on the channel
            // (upper bound on API 26+) and here for the actual post.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Opt out of the Android 12+ FGS notification deferral —
            // without this the system is allowed to hide the
            // notification for up to ~10 seconds after startForeground,
            // which is why tracking used to feel like it "starts late"
            // (the service is actually running, just invisible).
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        // Diagnostic — confirm the action survived into the final
        // Notification object. If this logs 0, the issue is in the
        // builder; if it logs 1 but the Stop button still doesn't
        // render, the system UI is filtering it downstream.
        Log.i(TAG, "buildNotification: actions=${notification.actions?.size ?: 0}")
        return notification
    }

    private fun snapshotLine(snap: TripSnapshot?, rec: HomeRecommendation?): String {
        if (snap == null) return "Waiting for GPS\u2026"
        val dist = "%.1f / %.1f km".format(
            snap.currentDistanceMeters / 1000.0,
            snap.totalDistanceMeters / 1000.0
        )
        // When "Take me home" has picked a specific station we swap the
        // "next <geographically-nearest>" label for a fresh ETA to the
        // recommended station — otherwise the two lines can disagree
        // (the nearest stop isn't always the one you're catching).
        val stationLabel = if (rec != null) {
            val remaining = (rec.stationDistanceAlongRouteMeters - snap.currentDistanceMeters)
                .coerceAtLeast(0.0)
            val speedMs = (avgSpeedKmh * 1000.0 / 3600.0).coerceAtLeast(0.1)
            val etaMin = (remaining / speedMs / 60.0).toInt()
            " \u2022 ${rec.stationName} in ~${etaMin}min"
        } else {
            snap.nextStationLabel?.let { " \u2022 $it" }.orEmpty()
        }
        val offRoute = if (snap.distanceFromRouteMeters > 300.0)
            " \u2022 off-route"
        else ""
        return dist + stationLabel + offRoute
    }

    /**
     * Format the one-line summary of the next connection home. The
     * station name is already named in [snapshotLine] so we don't
     * repeat it here — the second line is purely the train details.
     *
     * Examples:
     *   "Catch RE5 at 14:32, home 15:47"
     *   "Catch RE5 at 14:32 (in 12 min), home 15:47"
     *   "Catch train at 14:32"   (line/home unknown)
     */
    private fun homeLine(rec: HomeRecommendation?): String? {
        if (rec == null) return null
        val depTime = rec.departureTime
        val arrTime = rec.arrivalHomeTime
        val line = rec.line?.takeIf { it.isNotBlank() }

        val sb = StringBuilder("Catch ")
        sb.append(line ?: "train")
        if (depTime != null) {
            sb.append(" at ").append(formatClock(depTime))
            // Wait-from-now in minutes is a small nudge — tells you
            // whether to sprint or stroll without doing clock math.
            val waitMin = Duration.between(Instant.now(), depTime).toMinutes().toInt()
            if (waitMin in 1..59) sb.append(" (in ").append(waitMin).append(" min)")
        }
        if (arrTime != null) sb.append(", home ").append(formatClock(arrTime))
        return sb.toString()
    }

    private fun updateNotification(snap: TripSnapshot?, rec: HomeRecommendation?) {
        // Gate on notification permission — on API 33+ the app needs it and
        // the user may have denied; without it, notify() silently no-ops.
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (!canPost) return
        // Update the existing FGS notification via startForeground
        // rather than NotificationManager.notify(). On Android 15 the
        // two paths are NOT equivalent — notify() for an ID that was
        // started as a foreground-service notification can lose the
        // FLAG_FOREGROUND_SERVICE association, triggering validation
        // rejection and the "Cannot find enqueued record" log. The
        // startForeground() path keeps the notification flagged as
        // part of the live FGS, which is what this entire service
        // wants anyway.
        try {
            startInForeground(buildNotification(snap, rec))
        } catch (t: Throwable) {
            Log.w(TAG, "updateNotification failed", t)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Trip tracking",
            // DEFAULT (not LOW) so the notification lives in the main
            // shade section instead of the collapsed "Silent" group —
            // that way the rider can actually see it at a glance like
            // a media-playback notification.
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Live progress while a ride is being tracked"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            // DEFAULT importance normally makes a sound on first post.
            // We want prominent display but silent — explicitly null
            // the sound so first-post doesn't chime. Do NOT set
            // vibrationPattern here: Android 15+ rejects a [0]-length
            // pattern inside NotificationVibratorHelper, which takes
            // down the whole service during startForeground. Since
            // enableVibration(false) already kills vibration, leaving
            // the pattern unset is both correct and safe.
            setSound(null, null)
            // Show the full contents on the lock screen — matches the
            // per-notification VISIBILITY_PUBLIC. On API 26+ the
            // channel's setting is the upper bound, so without this
            // the per-notification flag is capped to PRIVATE.
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopTracking()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TripTrackingService"
        const val ACTION_START = "dev.gpxit.app.tracking.ACTION_START"
        const val ACTION_STOP = "dev.gpxit.app.tracking.ACTION_STOP"
        // v3 forces a fresh channel after the v2 channel was created
        // with a [0]-length vibrationPattern that Android 15+ rejects
        // at notify-time. Channel configuration is immutable after
        // creation, so existing v2 installs have to land on a new
        // channel to escape the bad pattern. v2 -> IMPORTANCE_DEFAULT,
        // same as v3; the only reason we're bumping is the vibration
        // pattern cleanup.
        const val CHANNEL_ID = "trip_tracking_v3"
        const val NOTIFICATION_ID = 42

        // Distinct request code for the Stop action PendingIntent so
        // it can't collide with the content-intent PendingIntent
        // (which uses requestCode = 0).
        private const val REQ_STOP = 1

        private val _state = MutableStateFlow(TripState())
        /** Live service state — safe to observe from the UI process. */
        val state: StateFlow<TripState> = _state

        // Published by DecisionViewModel after "Take me home" finishes.
        // Kept at companion scope so DecisionViewModel doesn't need to
        // know whether the service is running — the service reads it
        // whenever it refreshes the notification.
        private val _homeRecommendation = MutableStateFlow<HomeRecommendation?>(null)
        val homeRecommendation: StateFlow<HomeRecommendation?> = _homeRecommendation

        /** Push the recommended "get home" train into the notification. */
        fun publishHomeRecommendation(rec: HomeRecommendation?) {
            _homeRecommendation.value = rec
        }

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
    val homeRecommendation: HomeRecommendation? = null,
)

data class TripSnapshot(
    val routeName: String?,
    val currentDistanceMeters: Double,
    val totalDistanceMeters: Double,
    val remainingMeters: Double,
    val distanceFromRouteMeters: Double,
    val nextStationLabel: String?,
)

/**
 * Snapshot of the "take me home" result surfaced to the tracking
 * notification. Kept flat/primitive so it can be published from any
 * caller without dragging the full ConnectionOption graph along.
 */
data class HomeRecommendation(
    val stationName: String,
    val cyclingTimeMinutes: Int,
    val departureTime: Instant?,
    val arrivalHomeTime: Instant?,
    val line: String?,
    /**
     * Where the recommended station sits along the route (meters from
     * start). Used to recompute a fresh ETA from the rider's current
     * position instead of the stale one captured at "Take me home" time.
     */
    val stationDistanceAlongRouteMeters: Double,
)

private val clockFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun formatClock(t: Instant): String = clockFormatter.format(t)

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
