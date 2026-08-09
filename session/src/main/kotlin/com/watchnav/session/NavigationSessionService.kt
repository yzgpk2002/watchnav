package com.watchnav.session

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.watchnav.core.ActivityMode
import com.watchnav.core.CourseAdherence
import com.watchnav.core.ElevationProfile
import com.watchnav.core.GeoPoint
import com.watchnav.core.LocationFix
import com.watchnav.core.ManeuverKind
import com.watchnav.core.NavigationEngine
import com.watchnav.core.NavigationUpdate
import com.watchnav.brouter.BRouterOfflineRouter
import com.watchnav.routing.RouteRequest
import com.watchnav.routing.RouteResult
import com.watchnav.routing.RoutingProfile
import com.watchnav.core.Course
import kotlin.math.max
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

class NavigationSessionService : Service(), LocationListener {
    private val binder = LocalBinder()
    private lateinit var store: SessionStore
    private lateinit var locationManager: LocationManager
    private lateinit var trackStore: TrackStore
    private lateinit var sensorFusion: OutdoorSensorFusion
    private lateinit var healthExercise: HealthExerciseController
    private var engine: NavigationEngine? = null
    private var elevationProfile: ElevationProfile? = null
    private var courseName: String = "Offline course"
    private var currentMode: ActivityMode = ActivityMode.HIKE
    private var powerMode: NavigationPowerMode = NavigationPowerMode.BALANCED
    private var lastNotificationMillis = 0L
    private var lastAlertKey: String? = null
    private var lastClimbAlertKey: String? = null
    private var explicitlyEndingExercise = false
    private var stopping = false
    private val archiveExecutor = Executors.newSingleThreadExecutor()
    private val routingExecutor = Executors.newSingleThreadExecutor()
    private var rerouteInProgress = false

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        trackStore = TrackStore(this)
        sensorFusion = OutdoorSensorFusion(this).also(OutdoorSensorFusion::start)
        healthExercise = HealthExerciseController(this)
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_TRACBACK -> startTracBack()
            ACTION_MARK_LAP -> healthExercise.markLap()
            ACTION_PAUSE_EXERCISE -> healthExercise.pause()
            ACTION_RESUME_EXERCISE -> healthExercise.resume()
            else -> startOrRestoreSession(
                mode = intent?.getStringExtra(EXTRA_ACTIVITY_MODE)
                    ?.let { runCatching { ActivityMode.valueOf(it) }.getOrNull() }
                    ?: ActivityMode.HIKE,
                requestedPowerMode = intent?.getStringExtra(EXTRA_POWER_MODE)
                    ?.let { runCatching { NavigationPowerMode.valueOf(it) }.getOrNull() }
                    ?: powerMode,
                resetRecording = intent?.action == ACTION_START,
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onLocationChanged(location: Location) {
        val navigationEngine = engine ?: return
        val fusedElevation = sensorFusion.updateGnssAltitude(
            location.altitude.takeIf { location.hasAltitude() },
        )
        val fix = LocationFix(
            position = GeoPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                elevationMeters = fusedElevation,
            ),
            accuracyMeters = location.accuracy.toDouble().takeIf { location.hasAccuracy() } ?: 50.0,
            timestampMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            bearingDegrees = location.bearing.toDouble()
                .takeIf { location.hasBearing() && (!location.hasSpeed() || location.speed >= 0.8f) }
                ?: sensorFusion.compassBearingDegrees,
            speedMetersPerSecond = location.speed.toDouble().takeIf { location.hasSpeed() },
        )
        val health = healthExercise.metrics
        if (!health.paused) trackStore.record(fix)
        val update = navigationEngine.update(fix)
        val profile = elevationProfile
        val climbProgress = profile?.nextClimb(update.match.stableProgressMeters)
        val climbNumber = climbProgress?.let { profile.climbs.indexOf(it.climb) + 1 }
        val phase = when {
            update.match.remainingMeters <= COMPLETION_DISTANCE_METERS &&
                update.match.crossTrackDistanceMeters <= update.adherence.thresholdMeters -> SessionPhase.COMPLETED
            update.adherence.state == CourseAdherence.OFF_COURSE -> SessionPhase.OFF_COURSE
            else -> SessionPhase.NAVIGATING
        }
        val telemetry = NavigationTelemetry(
            phase = phase,
            courseName = courseName,
            position = fix.position,
            progressMeters = update.match.stableProgressMeters,
            remainingMeters = update.match.remainingMeters,
            crossTrackMeters = update.match.crossTrackDistanceMeters,
            horizontalAccuracyMeters = fix.accuracyMeters,
            speedMetersPerSecond = fix.speedMetersPerSecond,
            bearingDegrees = fix.bearingDegrees,
            currentElevationMeters = fusedElevation ?: profile?.elevationAt(
                update.match.stableProgressMeters,
            ),
            gradePercent = profile?.gradePercentAt(update.match.stableProgressMeters),
            verticalSpeedMetersPerHour = sensorFusion.verticalSpeedMetersPerHour,
            nextManeuver = update.nextCue?.kind,
            distanceToNextManeuverMeters = update.distanceToNextCueMeters,
            adherence = update.adherence.state,
            totalAscentMeters = profile?.totalAscentMeters ?: 0.0,
            remainingAscentMeters = profile?.remainingAscentMeters(
                update.match.stableProgressMeters,
            ) ?: 0.0,
            climbNumber = climbNumber,
            climbActive = climbProgress?.active == true,
            distanceToClimbMeters = climbProgress?.distanceToStartMeters,
            climbRemainingDistanceMeters = climbProgress?.remainingDistanceMeters,
            climbRemainingAscentMeters = climbProgress?.remainingAscentMeters,
            climbAverageGradePercent = climbProgress?.climb?.averageGradePercent,
            recordedPointCount = trackStore.pointCount(),
            heartRateBpm = health.heartRateBpm,
            averageHeartRateBpm = health.averageHeartRateBpm,
            exerciseDistanceMeters = health.distanceMeters,
            calories = health.calories,
            lapCount = health.lapCount,
            exercisePaused = health.paused,
            updatedAtMillis = fix.timestampMillis,
        )
        store.save(courseName, telemetry.progressMeters, telemetry.remainingMeters)
        NavigationStateHub.publish(telemetry)
        dispatchHapticAlert(update, fix.speedMetersPerSecond)
        dispatchClimbAlert(climbNumber, climbProgress?.active, climbProgress?.distanceToStartMeters)
        if (update.adherence.changed && update.adherence.state == CourseAdherence.OFF_COURSE) {
            calculateRejoinRoute(fix, update)
        }
        updateNotificationIfNeeded(telemetry, update.adherence.changed)
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            NavigationStateHub.publish(
                NavigationStateHub.current.copy(
                    phase = SessionPhase.ACQUIRING_LOCATION,
                    message = "GPS is disabled",
                ),
            )
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        if (::trackStore.isInitialized) trackStore.flush()
        if (::sensorFusion.isInitialized) sensorFusion.stop()
        if (::healthExercise.isInitialized && !explicitlyEndingExercise) healthExercise.detach()
        archiveExecutor.shutdown()
        routingExecutor.shutdown()
        engine = null
        elevationProfile = null
        super.onDestroy()
    }

    private fun startOrRestoreSession(
        mode: ActivityMode,
        requestedPowerMode: NavigationPowerMode,
        resetRecording: Boolean,
    ) {
        currentMode = mode
        powerMode = requestedPowerMode
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Loading course", 0.0, null, SessionPhase.LOADING_COURSE),
        )
        NavigationStateHub.publish(NavigationTelemetry(phase = SessionPhase.LOADING_COURSE))
        try {
            val storedCourse = CourseFileStore(this).load()
                ?: error("Import or download a GPX course first")
            courseName = storedCourse.course.name
            engine = NavigationEngine(storedCourse.course, mode).also { navigationEngine ->
                store.load()
                    ?.takeIf { it.courseName == courseName }
                    ?.let { navigationEngine.restoreProgress(it.progressMeters) }
            }
            elevationProfile = ElevationProfile.prepare(requireNotNull(engine).preparedCourse)
            if (resetRecording) trackStore.startNew()
            healthExercise.start(mode, powerMode.healthServicesGpsEnabled)
            val snapshot = store.load()?.takeIf { it.courseName == courseName }
            val remaining = snapshot?.remainingMeters
                ?: engine?.preparedCourse?.totalDistanceMeters
                ?: 0.0
            store.save(courseName, snapshot?.progressMeters ?: 0.0, remaining)
            NavigationStateHub.publish(
                NavigationTelemetry(
                    phase = SessionPhase.ACQUIRING_LOCATION,
                    courseName = courseName,
                    progressMeters = snapshot?.progressMeters ?: 0.0,
                    remainingMeters = remaining,
                    totalAscentMeters = elevationProfile?.totalAscentMeters ?: 0.0,
                    remainingAscentMeters = elevationProfile?.remainingAscentMeters(
                        snapshot?.progressMeters ?: 0.0,
                    ) ?: 0.0,
                    recordedPointCount = trackStore.pointCount(),
                    message = "Waiting for GPS",
                ),
            )
            startForeground(
                NOTIFICATION_ID,
                buildNotification(courseName, remaining, null, SessionPhase.ACQUIRING_LOCATION),
            )
            requestLocationUpdates()
        } catch (error: Exception) {
            publishError(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun startTracBack() {
        locationManager.removeUpdates(this)
        trackStore.flush()
        val returnCourse = trackStore.course(name = "TracBack", reverse = true)
        if (returnCourse == null) {
            publishError("Record at least two track points before TracBack")
            return
        }
        CourseFileStore(this).saveCourse(returnCourse)
        store.clear()
        lastAlertKey = null
        lastClimbAlertKey = null
        startOrRestoreSession(ActivityMode.HIKE, powerMode, resetRecording = false)
    }

    private fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            publishError("Location permission is required")
            return
        }
        locationManager.removeUpdates(this)
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            NavigationStateHub.publish(
                NavigationStateHub.current.copy(message = "Enable GPS to start navigation"),
            )
            return
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            powerMode.locationIntervalMillis,
            powerMode.minimumDistanceMeters,
            this,
            Looper.getMainLooper(),
        )
    }

    private fun calculateRejoinRoute(fix: LocationFix, update: NavigationUpdate) {
        val currentEngine = engine ?: return
        if (rerouteInProgress || stopping) return
        val prepared = currentEngine.preparedCourse
        val targetDistance = (update.match.stableProgressMeters + REJOIN_LOOKAHEAD_METERS)
            .coerceAtMost(prepared.totalDistanceMeters)
        val targetIndex = prepared.cumulativeDistanceMeters.indexOfFirst { it >= targetDistance }
            .takeIf { it >= 0 } ?: prepared.course.points.lastIndex
        val target = prepared.course.points[targetIndex].position
        val originalCourse = prepared.course
        val profile = when (currentMode) {
            ActivityMode.HIKE -> RoutingProfile.HIKE
            ActivityMode.RUN -> RoutingProfile.RUN
            ActivityMode.BIKE -> RoutingProfile.BIKE
        }
        rerouteInProgress = true
        routingExecutor.execute {
            val result = runBlocking {
                BRouterOfflineRouter(this@NavigationSessionService).route(
                    RouteRequest(fix.position, target, profile = profile),
                )
            }
            when (result) {
                is RouteResult.Success -> {
                    val joinedPoints = result.course.points + originalCourse.points.drop(targetIndex + 1)
                    if (joinedPoints.size >= 2) {
                        val joined = Course("Rejoin · ${originalCourse.name}", joinedPoints)
                        val stored = runCatching { CourseFileStore(this).saveCourse(joined) }.getOrNull()
                        android.os.Handler(Looper.getMainLooper()).post {
                            rerouteInProgress = false
                            if (stored != null && !stopping) {
                                courseName = stored.course.name
                                engine = NavigationEngine(stored.course, currentMode)
                                elevationProfile = ElevationProfile.prepare(requireNotNull(engine).preparedCourse)
                                store.clear()
                                vibrate(longArrayOf(0, 120, 70, 120, 70, 220))
                                NavigationStateHub.publish(
                                    NavigationStateHub.current.copy(
                                        courseName = courseName,
                                        progressMeters = 0.0,
                                        remainingMeters = requireNotNull(engine).preparedCourse.totalDistanceMeters,
                                        message = "Offline rejoin route ready",
                                    ),
                                )
                            }
                        }
                    } else {
                        postRerouteMessage("Unable to build a rejoin route")
                    }
                }
                is RouteResult.MissingRegion -> postRerouteMessage(
                    "Missing routing region: ${result.regionIds.joinToString()}",
                )
                is RouteResult.Failure -> postRerouteMessage("Rejoin failed: ${result.reason}")
            }
        }
    }

    private fun postRerouteMessage(message: String) {
        android.os.Handler(Looper.getMainLooper()).post {
            rerouteInProgress = false
            if (!stopping) NavigationStateHub.publish(NavigationStateHub.current.copy(message = message))
        }
    }

    private fun dispatchHapticAlert(update: NavigationUpdate, speedMetersPerSecond: Double?) {
        if (update.adherence.changed) {
            when (update.adherence.state) {
                CourseAdherence.OFF_COURSE -> vibrate(longArrayOf(0, 250, 140, 250, 140, 400))
                CourseAdherence.ON_COURSE -> vibrate(longArrayOf(0, 100, 90, 100))
                else -> Unit
            }
        }

        val cue = update.nextCue ?: return
        val distance = update.distanceToNextCueMeters ?: return
        val speed = max(speedMetersPerSecond ?: 1.4, 0.5)
        val stage = when {
            distance <= max(25.0, speed * 5.0) -> "now"
            distance <= max(80.0, speed * 20.0) -> "near"
            distance <= max(250.0, speed * 60.0) -> "preview"
            else -> return
        }
        val key = "${cue.pointIndex}:$stage"
        if (key == lastAlertKey) return
        lastAlertKey = key
        when (stage) {
            "now" -> vibrate(longArrayOf(0, 220, 100, 220))
            "near" -> vibrate(longArrayOf(0, 150, 100, 150))
            else -> vibrate(longArrayOf(0, 120))
        }
    }

    private fun dispatchClimbAlert(number: Int?, active: Boolean?, distanceToStartMeters: Double?) {
        if (number == null) return
        val stage = when {
            active == true -> "start"
            distanceToStartMeters != null && distanceToStartMeters <= 200.0 -> "preview"
            else -> return
        }
        val key = "$number:$stage"
        if (key == lastClimbAlertKey) return
        lastClimbAlertKey = key
        if (stage == "start") {
            vibrate(longArrayOf(0, 180, 90, 180, 90, 180))
        } else {
            vibrate(longArrayOf(0, 100, 70, 100))
        }
    }

    private fun updateNotificationIfNeeded(telemetry: NavigationTelemetry, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationMillis < NOTIFICATION_INTERVAL_MILLIS) return
        lastNotificationMillis = now
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                telemetry.courseName ?: courseName,
                telemetry.remainingMeters,
                telemetry.nextManeuver,
                telemetry.phase,
            ),
        )
    }

    private fun stopSession() {
        if (stopping) return
        stopping = true
        locationManager.removeUpdates(this)
        trackStore.flush()
        explicitlyEndingExercise = true
        healthExercise.end()
        val recordedCourse = trackStore.course()
        val finalTelemetry = NavigationStateHub.current
        if (recordedCourse != null) {
            NavigationStateHub.publish(finalTelemetry.copy(message = "Saving activity"))
            archiveExecutor.execute {
                runCatching {
                    ActivityArchive(this).archive(recordedCourse, currentMode, finalTelemetry)
                }
                android.os.Handler(Looper.getMainLooper()).post(::finishStopSession)
            }
        } else {
            finishStopSession()
        }
    }

    private fun finishStopSession() {
        engine = null
        elevationProfile = null
        lastAlertKey = null
        lastClimbAlertKey = null
        store.clear()
        NavigationStateHub.publish(NavigationTelemetry())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishError(message: String) {
        val telemetry = NavigationTelemetry(
            phase = SessionPhase.ERROR,
            courseName = courseName,
            message = message,
        )
        NavigationStateHub.publish(telemetry)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(courseName, 0.0, null, SessionPhase.ERROR, message),
        )
    }

    private fun buildNotification(
        courseName: String,
        remainingMeters: Double,
        maneuver: ManeuverKind?,
        phase: SessionPhase,
        errorMessage: String? = null,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_navigation)
        .setContentTitle(courseName)
        .setContentText(
            when (phase) {
                SessionPhase.ERROR -> errorMessage ?: "Navigation error"
                SessionPhase.ACQUIRING_LOCATION -> "Waiting for GPS"
                SessionPhase.OFF_COURSE -> "Off course · return to track"
                SessionPhase.COMPLETED -> "Course complete"
                else -> buildString {
                    if (maneuver != null) append(maneuver.name.replace('_', ' ')).append(" · ")
                    append("%.1f km remaining".format(remainingMeters / 1_000))
                }
            },
        )
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setOnlyAlertOnce(true)
        .setOngoing(phase != SessionPhase.COMPLETED && phase != SessionPhase.ERROR)
        .setContentIntent(launchPendingIntent())
        .addAction(0, "Stop", stopPendingIntent())
        .also { builder ->
            OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_navigation)
                .setTouchIntent(launchPendingIntent())
                .setStatus(
                    Status.Builder()
                        .addTemplate("#course#")
                        .addPart("course", Status.TextPart(courseName))
                        .build(),
                )
                .build()
                .apply(this)
        }
        .build()

    private fun launchPendingIntent(): PendingIntent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, NavigationSessionService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active navigation",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    inner class LocalBinder : Binder() {
        fun service(): NavigationSessionService = this@NavigationSessionService
    }

    companion object {
        private const val CHANNEL_ID = "watchnav_navigation"
        private const val NOTIFICATION_ID = 1101
        private const val ACTION_START = "com.watchnav.session.START"
        private const val ACTION_STOP = "com.watchnav.session.STOP"
        private const val ACTION_TRACBACK = "com.watchnav.session.TRACBACK"
        private const val ACTION_MARK_LAP = "com.watchnav.session.MARK_LAP"
        private const val ACTION_PAUSE_EXERCISE = "com.watchnav.session.PAUSE_EXERCISE"
        private const val ACTION_RESUME_EXERCISE = "com.watchnav.session.RESUME_EXERCISE"
        private const val EXTRA_ACTIVITY_MODE = "activity_mode"
        private const val EXTRA_POWER_MODE = "power_mode"
        private const val NOTIFICATION_INTERVAL_MILLIS = 5_000L
        private const val COMPLETION_DISTANCE_METERS = 15.0
        private const val REJOIN_LOOKAHEAD_METERS = 300.0

        fun start(
            context: Context,
            mode: ActivityMode = ActivityMode.HIKE,
            powerMode: NavigationPowerMode = NavigationPowerMode.BALANCED,
        ) {
            val intent = Intent(context, NavigationSessionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ACTIVITY_MODE, mode.name)
                .putExtra(EXTRA_POWER_MODE, powerMode.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NavigationSessionService::class.java).setAction(ACTION_STOP),
            )
        }

        fun tracBack(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NavigationSessionService::class.java).setAction(ACTION_TRACBACK),
            )
        }

        fun markLap(context: Context) = sendCommand(context, ACTION_MARK_LAP)

        fun pauseExercise(context: Context) = sendCommand(context, ACTION_PAUSE_EXERCISE)

        fun resumeExercise(context: Context) = sendCommand(context, ACTION_RESUME_EXERCISE)

        private fun sendCommand(context: Context, action: String) {
            context.startService(Intent(context, NavigationSessionService::class.java).setAction(action))
        }
    }
}
