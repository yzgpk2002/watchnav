package com.watchnav.session

import android.Manifest
import android.annotation.SuppressLint
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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.watchnav.core.ActivityMode
import com.watchnav.core.CourseAdherence
import com.watchnav.core.ElevationProfile
import com.watchnav.core.GeoPoint
import com.watchnav.core.GeoMath
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
    private val navigationFilter = AdaptiveNavigationFilter()
    private val pedestrianDirectionEstimator = PedestrianRouteDirectionEstimator()
    private var healthExercise: HealthExerciseController? = null
    private lateinit var lastFixStore: LastNavigationFixStore
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
    private var lastAutomaticRerouteMillis = 0L
    private var lastAdherenceAlertMillis = 0L
    private var explorationMode = false
    private var explorationName = "Free exploration"
    private var finishArrivalDetector: FinishArrivalDetector? = null

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        trackStore = TrackStore(this)
        sensorFusion = OutdoorSensorFusion(this).also(OutdoorSensorFusion::start)
        healthExercise = runCatching { HealthExerciseController(this) }.getOrNull()
        lastFixStore = LastNavigationFixStore(this)
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_START_EXPLORATION -> startExplorationSession(
                intent.getStringExtra(EXTRA_EXPLORATION_NAME).orEmpty().ifBlank { "Free exploration" },
                resetRecording = true,
            )
            ACTION_FINISH_EXPLORATION -> finishExplorationSession()
            ACTION_TRACBACK -> startTracBack()
            ACTION_MARK_LAP -> healthExercise?.markLap()
            ACTION_PAUSE_EXERCISE -> healthExercise?.pause()
            ACTION_RESUME_EXERCISE -> healthExercise?.resume()
            else -> if (intent == null && explorationPreferences().getBoolean(KEY_EXPLORATION_ACTIVE, false)) {
                startExplorationSession(
                    explorationPreferences().getString(KEY_EXPLORATION_NAME, null).orEmpty()
                        .ifBlank { "Free exploration" },
                    resetRecording = false,
                )
            } else startOrRestoreSession(
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
        runCatching {
            val fusedElevation = sensorFusion.updateGnssAltitude(
                location.altitude.takeIf { location.hasAltitude() },
            )
            sensorFusion.updateGeomagneticReference(
                location.latitude,
                location.longitude,
                fusedElevation,
                location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
            val rawFix = LocationFix(
                position = GeoPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    elevationMeters = fusedElevation,
                ),
                accuracyMeters = location.accuracy.toDouble().takeIf { location.hasAccuracy() } ?: 50.0,
                timestampMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                bearingDegrees = location.bearing.toDouble().takeIf { location.hasBearing() },
                speedMetersPerSecond = location.speed.toDouble().takeIf { location.hasSpeed() },
            )
            val fix = navigationFilter.update(
                rawFix,
                sensorFusion.compassBearingDegrees,
                sensorFusion.compassReliable,
            )
            lastFixStore.save(fix)
            if (explorationMode) {
                processExplorationFix(fix)
            } else {
                processFix(fix, recordTrack = true, fallbackMessage = null)
            }
        }.onFailure { error ->
            publishError(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun processFix(
        fix: LocationFix,
        recordTrack: Boolean,
        fallbackMessage: String?,
    ) {
        val navigationEngine = engine ?: return
        val fusedElevation = sensorFusion.updateGnssAltitude(
            fix.position.elevationMeters,
        )
        val normalizedFix = fix.copy(
            position = fix.position.copy(elevationMeters = fusedElevation ?: fix.position.elevationMeters),
            bearingDegrees = fix.bearingDegrees,
        )
        val health = healthExercise?.metrics ?: HealthExerciseMetrics()
        if (recordTrack && !health.paused) trackStore.record(normalizedFix)
        val update = navigationEngine.update(normalizedFix)
        val approachThreshold = max(
            APPROACH_GUIDANCE_MINIMUM_DISTANCE_METERS,
            normalizedFix.accuracyMeters * APPROACH_GUIDANCE_ACCURACY_FACTOR,
        )
        val approachActive = update.match.crossTrackDistanceMeters > approachThreshold
        val arrivedAtFinish = finishArrivalDetector?.update(normalizedFix) == true
        val displayedRemainingMeters = update.match.remainingMeters +
            update.match.crossTrackDistanceMeters.takeIf { approachActive }.orZero()
        val effectiveRemainingMeters = if (arrivedAtFinish) 0.0 else displayedRemainingMeters
        val pathHeading = if (approachActive) {
            GeoMath.initialBearingDegrees(normalizedFix.position, update.match.snappedPosition)
        } else {
            navigationEngine.segmentBearingDegrees(update.match)
        }
        val directionEstimate = if (!approachActive && pathHeading != null) {
            pedestrianDirectionEstimator.update(
                rawProgressMeters = update.match.rawProgressMeters,
                routeForwardBearingDegrees = pathHeading,
                compassBearingDegrees = sensorFusion.compassBearingDegrees,
                compassReliable = sensorFusion.compassReliable,
                compassStable = sensorFusion.compassStable,
                detectedStepCount = sensorFusion.detectedStepCount,
                horizontalAccuracyMeters = normalizedFix.accuracyMeters,
            )
        } else {
            null
        }
        if (directionEstimate?.changed == true) {
            Log.i(TAG, "Pedestrian route direction=${directionEstimate.direction}")
        }
        val directedPathHeading = when (directionEstimate?.direction) {
            AlongRouteDirection.REVERSE -> pathHeading?.let { (it + 180.0) % 360.0 }
            else -> pathHeading
        }
        val guidanceHeading = GuidanceHeadingPolicy.resolve(
            normalizedFix.bearingDegrees,
            normalizedFix.speedMetersPerSecond,
            directedPathHeading,
        )
        val approachManeuver = if (approachActive) {
            approachManeuver(normalizedFix.copy(bearingDegrees = guidanceHeading), update.match.snappedPosition)
        } else {
            null
        }
        val profile = elevationProfile
        val climbProgress = profile?.nextClimb(update.match.stableProgressMeters)
        val climbNumber = climbProgress?.let { profile.climbs.indexOf(it.climb) + 1 }
        val phase = when {
            arrivedAtFinish -> SessionPhase.COMPLETED
            effectiveRemainingMeters <= COMPLETION_DISTANCE_METERS &&
                update.match.crossTrackDistanceMeters <= update.adherence.thresholdMeters -> SessionPhase.COMPLETED
            update.adherence.state == CourseAdherence.OFF_COURSE -> SessionPhase.OFF_COURSE
            else -> SessionPhase.NAVIGATING
        }
        val telemetry = NavigationTelemetry(
            phase = phase,
            courseName = courseName,
            position = normalizedFix.position,
            routeJoinPosition = update.match.snappedPosition,
            breadcrumbTrail = trackStore.recentPositions(),
            progressMeters = update.match.stableProgressMeters,
            remainingMeters = effectiveRemainingMeters,
            crossTrackMeters = update.match.crossTrackDistanceMeters,
            horizontalAccuracyMeters = normalizedFix.accuracyMeters,
            speedMetersPerSecond = normalizedFix.speedMetersPerSecond,
            bearingDegrees = guidanceHeading,
            compassCalibrationNeeded = sensorFusion.compassNeedsCalibration,
            currentElevationMeters = fusedElevation ?: profile?.elevationAt(
                update.match.stableProgressMeters,
            ),
            gradePercent = profile?.gradePercentAt(update.match.stableProgressMeters),
            verticalSpeedMetersPerHour = sensorFusion.verticalSpeedMetersPerHour,
            nextManeuver = if (arrivedAtFinish) ManeuverKind.FINISH else TurnDisplayPolicy.resolve(
                approachManeuver,
                update.nextCue?.kind,
                update.distanceToNextCueMeters,
            ),
            distanceToNextManeuverMeters = if (arrivedAtFinish) {
                0.0
            } else if (approachActive) {
                update.match.crossTrackDistanceMeters
            } else {
                update.distanceToNextCueMeters
            },
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
            updatedAtMillis = normalizedFix.timestampMillis,
            message = fallbackMessage
                ?: "Compass interference · move away from metal and calibrate"
                    .takeIf { sensorFusion.compassNeedsCalibration }
                ?: health.error,
        )
        store.save(courseName, telemetry.progressMeters, telemetry.remainingMeters)
        NavigationStateHub.publish(telemetry)
        if (phase == SessionPhase.COMPLETED) {
            updateNotificationIfNeeded(telemetry, force = true)
            completeSession(telemetry)
            return
        }
        dispatchHapticAlert(update, normalizedFix.speedMetersPerSecond)
        dispatchClimbAlert(climbNumber, climbProgress?.active, climbProgress?.distanceToStartMeters)
        val automaticRejoinThreshold = max(
            AUTOMATIC_REROUTE_MINIMUM_DISTANCE_METERS,
            normalizedFix.accuracyMeters * AUTOMATIC_REROUTE_ACCURACY_FACTOR,
        )
        if (recordTrack && !arrivedAtFinish &&
            finishArrivalDetector?.isInsideRerouteProtection(normalizedFix) != true &&
            update.match.crossTrackDistanceMeters >= automaticRejoinThreshold &&
            normalizedFix.timestampMillis - lastAutomaticRerouteMillis >= AUTOMATIC_REROUTE_COOLDOWN_MILLIS &&
            !rerouteInProgress
        ) {
            lastAutomaticRerouteMillis = normalizedFix.timestampMillis
            calculateRejoinRoute(normalizedFix, update)
        }
        updateNotificationIfNeeded(telemetry, update.adherence.changed)
    }

    /**
     * Arrival is terminal for the active navigation session. Persist the activity, then release
     * GNSS and sensor resources while leaving the completed telemetry visible in the foreground UI.
     */
    private fun completeSession(finalTelemetry: NavigationTelemetry) {
        if (stopping) return
        stopping = true
        locationManager.removeUpdates(this)
        trackStore.flush()
        explicitlyEndingExercise = true
        healthExercise?.end()
        val recordedCourse = trackStore.course()
        if (recordedCourse != null) {
            archiveExecutor.execute {
                runCatching {
                    ActivityArchive(this).archive(recordedCourse, currentMode, finalTelemetry)
                }
                android.os.Handler(Looper.getMainLooper()).post(::finishCompletedSession)
            }
        } else {
            finishCompletedSession()
        }
    }

    private fun finishCompletedSession() {
        engine = null
        elevationProfile = null
        store.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun processExplorationFix(fix: LocationFix) {
        trackStore.record(fix)
        val telemetry = NavigationTelemetry(
            phase = SessionPhase.NAVIGATING,
            courseName = explorationName,
            position = fix.position,
            breadcrumbTrail = trackStore.recentPositions(),
            horizontalAccuracyMeters = fix.accuracyMeters,
            speedMetersPerSecond = fix.speedMetersPerSecond,
            bearingDegrees = fix.bearingDegrees,
            compassCalibrationNeeded = sensorFusion.compassNeedsCalibration,
            recordedPointCount = trackStore.pointCount(),
            updatedAtMillis = fix.timestampMillis,
            message = "Free exploration",
        )
        NavigationStateHub.publish(telemetry)
        updateNotificationIfNeeded(telemetry, force = false)
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            publishFallbackFix("GPS unavailable · using last position")
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        if (::trackStore.isInitialized) trackStore.flush()
        if (::sensorFusion.isInitialized) sensorFusion.stop()
        if (!explicitlyEndingExercise) healthExercise?.detach()
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
        explorationMode = false
        explorationPreferences().edit().clear().apply()
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
            val restoredSnapshot = store.load()
                ?.takeIf { !resetRecording && it.courseName == courseName }
            if (resetRecording) store.clear()
            engine = NavigationEngine(storedCourse.course, mode).also { navigationEngine ->
                restoredSnapshot?.let { navigationEngine.restoreProgress(it.progressMeters) }
            }
            elevationProfile = ElevationProfile.prepare(requireNotNull(engine).preparedCourse)
            if (resetRecording) trackStore.startNew()
            if (resetRecording) navigationFilter.reset()
            if (resetRecording) pedestrianDirectionEstimator.reset()
            finishArrivalDetector = FinishArrivalDetector(storedCourse.course.points.last().position).also { detector ->
                if (!resetRecording) {
                    trackStore.recentPositions().forEach(detector::observeHistoricalPosition)
                }
            }
            // Navigation already owns the GNSS stream. A parallel Health Services exercise kept
            // heart-rate/GNSS hardware active for hours and provided no guidance benefit.
            healthExercise?.detach()
            healthExercise = null
            val snapshot = restoredSnapshot
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
            publishFallbackFix("Using last position · waiting for GPS")
            requestLocationUpdates()
        } catch (error: Exception) {
            publishError(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun startTracBack() {
        locationManager.removeUpdates(this)
        trackStore.flush()
        val returnCourse = trackStore.course(
            name = "TracBack",
            reverse = true,
            simplifyToleranceMeters = TRACKBACK_SIMPLIFICATION_METERS,
        )
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

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (!publishFallbackFix("Location permission missing · using last position")) {
                publishError("Location permission is required")
            }
            return
        }
        locationManager.removeUpdates(this)
        bestLastKnownLocation()?.let { location ->
            val fusedElevation = sensorFusion.updateGnssAltitude(
                location.altitude.takeIf { location.hasAltitude() },
            )
            val fix = LocationFix(
                GeoPoint(location.latitude, location.longitude, fusedElevation),
                location.accuracy.toDouble().takeIf { location.hasAccuracy() } ?: 100.0,
                location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                location.bearing.toDouble().takeIf { location.hasBearing() },
                location.speed.toDouble().takeIf { location.hasSpeed() },
            )
            lastFixStore.save(fix)
            processFix(fix, recordTrack = false, fallbackMessage = "Using last position · waiting for GPS")
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            publishFallbackFix("GPS disabled · using last position")
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

    private fun approachManeuver(fix: LocationFix, target: GeoPoint): ManeuverKind {
        val heading = fix.bearingDegrees ?: return ManeuverKind.START
        val targetBearing = GeoMath.initialBearingDegrees(fix.position, target)
        val turn = GeoMath.signedTurnDegrees(heading, targetBearing)
        return when {
            turn <= -APPROACH_TURN_THRESHOLD_DEGREES -> ManeuverKind.LEFT
            turn >= APPROACH_TURN_THRESHOLD_DEGREES -> ManeuverKind.RIGHT
            else -> ManeuverKind.START
        }
    }

    private fun startExplorationSession(name: String, resetRecording: Boolean) {
        stopping = false
        explorationMode = true
        explorationName = name
        finishArrivalDetector = null
        engine = null
        elevationProfile = null
        courseName = name
        store.clear()
        if (resetRecording) {
            trackStore.startNew()
            navigationFilter.reset()
        }
        healthExercise?.detach()
        healthExercise = null
        explorationPreferences().edit()
            .putBoolean(KEY_EXPLORATION_ACTIVE, true)
            .putString(KEY_EXPLORATION_NAME, name)
            .apply()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(name, 0.0, null, SessionPhase.ACQUIRING_LOCATION),
        )
        val fallback = lastFixStore.load()
        NavigationStateHub.publish(
            NavigationTelemetry(
                phase = SessionPhase.ACQUIRING_LOCATION,
                courseName = name,
                position = fallback?.position,
                breadcrumbTrail = trackStore.recentPositions(),
                horizontalAccuracyMeters = fallback?.accuracyMeters,
                speedMetersPerSecond = fallback?.speedMetersPerSecond,
                bearingDegrees = fallback?.bearingDegrees,
                recordedPointCount = trackStore.pointCount(),
                updatedAtMillis = fallback?.timestampMillis ?: 0L,
                message = "Free exploration · waiting for GPS",
            ),
        )
        requestLocationUpdates()
    }

    private fun Double?.orZero(): Double = this ?: 0.0

    @SuppressLint("MissingPermission")
    private fun bestLastKnownLocation(): Location? = runCatching {
        locationManager.allProviders.mapNotNull(locationManager::getLastKnownLocation)
            .maxByOrNull { it.time }
    }.getOrNull()

    private fun publishFallbackFix(message: String): Boolean {
        val fix = lastFixStore.load() ?: return false
        return runCatching {
            processFix(fix, recordTrack = false, fallbackMessage = message)
            true
        }.getOrDefault(false)
    }

    private fun calculateRejoinRoute(fix: LocationFix, update: NavigationUpdate) {
        val currentEngine = engine ?: return
        if (rerouteInProgress || stopping) return
        val prepared = currentEngine.preparedCourse
        val targetDistance = (update.match.rawProgressMeters + REJOIN_LOOKAHEAD_METERS)
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
                        // A rejoin is a transient navigation plan. Persisting it as the active course
                        // polluted the route library and recursively prefixed the name on every reroute.
                        val joined = TransientRejoinPlan.build(
                            courseName,
                            result.course.points,
                            originalCourse.points,
                            targetIndex,
                        )
                        android.os.Handler(Looper.getMainLooper()).post {
                            rerouteInProgress = false
                            if (!stopping) {
                                engine = NavigationEngine(joined, currentMode)
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
        val now = System.currentTimeMillis()
        if (update.adherence.changed && now - lastAdherenceAlertMillis >= ADHERENCE_ALERT_COOLDOWN_MILLIS) {
            lastAdherenceAlertMillis = now
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
        if (explorationMode) {
            finishExplorationSession()
            return
        }
        stopping = true
        locationManager.removeUpdates(this)
        trackStore.flush()
        explicitlyEndingExercise = true
        healthExercise?.end()
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

    private fun finishExplorationSession() {
        if (stopping) return
        stopping = true
        locationManager.removeUpdates(this)
        trackStore.flush()
        runCatching {
            trackStore.course(
                explorationName,
                simplifyToleranceMeters = EXPLORATION_SIMPLIFICATION_METERS,
            )?.let { CourseFileStore(this).saveCourse(it, CourseLibraryOrigin.IMPORTED) }
        }
        explorationPreferences().edit().clear().apply()
        explorationMode = false
        engine = null
        elevationProfile = null
        store.clear()
        NavigationStateHub.publish(NavigationTelemetry())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun explorationPreferences() =
        getSharedPreferences(EXPLORATION_PREFERENCES, Context.MODE_PRIVATE)

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
        private const val TAG = "WatchNavSession"
        private const val CHANNEL_ID = "watchnav_navigation"
        private const val NOTIFICATION_ID = 1101
        private const val ACTION_START = "com.watchnav.session.START"
        private const val ACTION_STOP = "com.watchnav.session.STOP"
        private const val ACTION_START_EXPLORATION = "com.watchnav.session.START_EXPLORATION"
        private const val ACTION_FINISH_EXPLORATION = "com.watchnav.session.FINISH_EXPLORATION"
        private const val ACTION_TRACBACK = "com.watchnav.session.TRACBACK"
        private const val ACTION_MARK_LAP = "com.watchnav.session.MARK_LAP"
        private const val ACTION_PAUSE_EXERCISE = "com.watchnav.session.PAUSE_EXERCISE"
        private const val ACTION_RESUME_EXERCISE = "com.watchnav.session.RESUME_EXERCISE"
        private const val EXTRA_ACTIVITY_MODE = "activity_mode"
        private const val EXTRA_POWER_MODE = "power_mode"
        private const val EXTRA_EXPLORATION_NAME = "exploration_name"
        private const val NOTIFICATION_INTERVAL_MILLIS = 30_000L
        private const val COMPLETION_DISTANCE_METERS = 15.0
        private const val REJOIN_LOOKAHEAD_METERS = 60.0
        private const val AUTOMATIC_REROUTE_MINIMUM_DISTANCE_METERS = 100.0
        private const val AUTOMATIC_REROUTE_ACCURACY_FACTOR = 1.5
        private const val AUTOMATIC_REROUTE_COOLDOWN_MILLIS = 5 * 60_000L
        private const val ADHERENCE_ALERT_COOLDOWN_MILLIS = 2 * 60_000L
        private const val EXPLORATION_SIMPLIFICATION_METERS = 3.0
        private const val EXPLORATION_PREFERENCES = "free_exploration"
        private const val KEY_EXPLORATION_ACTIVE = "active"
        private const val KEY_EXPLORATION_NAME = "name"
        private const val TRACKBACK_SIMPLIFICATION_METERS = 5.0
        private const val APPROACH_GUIDANCE_MINIMUM_DISTANCE_METERS = 8.0
        private const val APPROACH_GUIDANCE_ACCURACY_FACTOR = 0.5
        private const val APPROACH_TURN_THRESHOLD_DEGREES = 25.0

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

        fun startExploration(context: Context, name: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NavigationSessionService::class.java)
                    .setAction(ACTION_START_EXPLORATION)
                    .putExtra(EXTRA_EXPLORATION_NAME, name),
            )
        }

        fun finishExploration(context: Context) {
            context.startService(
                Intent(context, NavigationSessionService::class.java)
                    .setAction(ACTION_FINISH_EXPLORATION),
            )
        }

        fun isExploring(context: Context): Boolean = context
            .getSharedPreferences(EXPLORATION_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXPLORATION_ACTIVE, false)

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
