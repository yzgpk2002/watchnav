package com.watchnav.app

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.BlendMode
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.hardware.GeomagneticField
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.watchnav.core.ActivityMode
import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.continuationFromNearestPoint
import com.watchnav.core.CoordinateSystem
import com.watchnav.core.GeoMath
import com.watchnav.core.GeoPoint
import com.watchnav.core.LocationFix
import com.watchnav.core.PreparedCourse
import com.watchnav.core.reversed
import com.watchnav.brouter.BRouterOfflineRouter
import com.watchnav.mapsforge.MapsforgeMapRepository
import com.watchnav.mapsforge.MapViewport
import com.watchnav.mapsforge.OfflineMapStack
import com.watchnav.mapsforge.OfflineMapsforgeView
import com.watchnav.mapsforge.VectorMapLayerKind
import com.watchnav.packages.RoutingRegionDownloadProgress
import com.watchnav.packages.RoutingRegionDownloader
import com.watchnav.session.CourseFileStore
import com.watchnav.session.ActivityArchive
import com.watchnav.session.NavigationSessionService
import com.watchnav.session.NavigationPowerMode
import com.watchnav.session.NavigationStateHub
import com.watchnav.session.NavigationTelemetry
import com.watchnav.session.SessionPhase
import com.watchnav.session.TrackStore
import com.watchnav.session.WaypointStore
import com.watchnav.session.FavoriteTargetStore
import com.watchnav.session.LastNavigationFixStore
import com.watchnav.sync.protocol.TransferKind
import com.watchnav.sync.transport.TransferredArtifactRegistry
import com.watchnav.app.sync.WatchSyncCoordinator
import com.watchnav.app.sync.WatchLanSyncService
import com.watchnav.app.sync.WatchWearSyncService
import com.watchnav.routing.RouteRequest
import com.watchnav.routing.RouteResult
import com.watchnav.routing.RoutingProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageController.wrap(newBase))
    }
    private var activeCourse: Course? = null
    private lateinit var statusText: TextView
    private lateinit var courseText: TextView
    private lateinit var lockText: TextView
    private lateinit var gpxUrlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var importCoordinateButton: Button
    private lateinit var alwaysOnButton: Button
    private lateinit var followMapButton: Button
    private lateinit var exercisePauseButton: Button
    private lateinit var powerModeButton: Button
    private lateinit var courseMapView: CourseMapView
    private lateinit var elevationProfileView: ElevationProfileView
    private lateinit var mapContainer: FrameLayout
    private lateinit var mapLayerContainer: FrameLayout
    private lateinit var navigationMapLayerContainer: FrameLayout
    private lateinit var navigationCourseMapView: LightweightNavigationView
    private lateinit var overlayHost: FrameLayout
    private lateinit var pageHost: SwipePageHost
    private lateinit var elevationStatsText: TextView
    private lateinit var fishingDashboardView: FishingDashboardView
    private val pages = ArrayList<View>()
    private var currentPage = AppPage.MAP.ordinal
    private var offlineMapView: OfflineMapsforgeView? = null
    private var offlineMapStackIdentity: String? = null
    private var satelliteMapView: WatchMbtilesMapView? = null
    private var satelliteMapPath: String? = null
    private var mapRepairAttempted = false
    private var lastOrientationUpdateNanos = 0L
    private var displayedHeadingDegrees: Double? = null
    private var longPressHandled = false
    private var fishingPosition: GeoPoint? = null
    private var directLocationFix: LocationFix? = null
    private var navigationWasActive = false
    private var pendingFishingQuickPin = false
    private val fishingCastDetector = FishingCastDetector()
    private var fishingCastSensorRegistered = false
    private var activityResumed = false
    private var mapLocationPermissionRequested = false
    private var importCoordinateSystem = CoordinateSystem.WGS84
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val fishingStore by lazy { FishingEnvironmentStore(this) }
    private val fishingAudioRecorder by lazy { FishingAudioRecorder(this) }
    private var fishingAudioAutoStopJob: Job? = null
    private val tideRepository by lazy { TideForecastRepository(this) }
    private var tideRefreshJob: Job? = null
    private var routeApproachJob: Job? = null
    private var exitingApplication = false
    private val fishingLocationListener = LocationListener { location ->
        val point = GeoPoint(location.latitude, location.longitude, location.altitude.takeIf { location.hasAltitude() })
        directLocationFix = LocationFix(
            position = point,
            accuracyMeters = location.accuracy.toDouble().takeIf { location.hasAccuracy() } ?: 100.0,
            timestampMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            bearingDegrees = location.bearing.toDouble().takeIf { location.hasBearing() },
            speedMetersPerSecond = location.speed.toDouble().takeIf { location.hasSpeed() },
        )
        fishingPosition = point
        LastNavigationFixStore(this).save(requireNotNull(directLocationFix))
        renderDirectLocation(requireNotNull(directLocationFix))
        fishingDashboardView.setPosition(requireNotNull(fishingPosition))
        refreshTideForecast(requireNotNull(fishingPosition))
        if (pendingFishingQuickPin) {
            pendingFishingQuickPin = false
            saveFishingQuickPin()
        }
    }
    private val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                if (currentPage == AppPage.FISHING.ordinal && event.values.size >= 3 &&
                    fishingCastDetector.addSample(event.timestamp / 1_000_000L, event.values[0], event.values[1], event.values[2])
                ) {
                    recordFishingCast()
                }
                return
            }
            if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                val pressure = event.values.firstOrNull() ?: return
                val samples = fishingStore.recordPressure(pressure)
                if (::fishingDashboardView.isInitialized) {
                    fishingDashboardView.setPressure(pressure, samples)
                    val now = System.currentTimeMillis()
                    if (FishingMath.pressureDelta(samples, now)?.let { it < -4f } == true) triggerStormAlert(now)
                    if (fishingStore.shouldRunBarometerMaintenance(now)) {
                        if (FishingMath.barometerDriftSuspected(samples, now)) {
                            fishingDashboardView.showNotice(getString(R.string.fishing_barometer_maintenance))
                        }
                        fishingStore.markBarometerMaintenanceChecked(now)
                    }
                }
                return
            }
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR ||
                event.timestamp - lastOrientationUpdateNanos < ORIENTATION_UPDATE_INTERVAL_NANOS
            ) return
            val rotation = FloatArray(9)
            val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            val reference = NavigationStateHub.current.position ?: directLocationFix?.position
            val declination = reference?.let {
                GeomagneticField(
                    it.latitude.toFloat(),
                    it.longitude.toFloat(),
                    (it.elevationMeters ?: 0.0).toFloat(),
                    System.currentTimeMillis(),
                ).declination.toDouble()
            } ?: 0.0
            val measured = (Math.toDegrees(orientation[0].toDouble()) + declination + 360.0) % 360.0
            val previous = displayedHeadingDegrees
            val delta = previous?.let { shortestAngleDegrees(it, measured) }
            if (delta != null && kotlin.math.abs(delta) < 2.0) return
            val smoothed = previous?.let { (it + shortestAngleDegrees(it, measured) * 0.28 + 360.0) % 360.0 }
                ?: measured
            displayedHeadingDegrees = smoothed
            lastOrientationUpdateNanos = event.timestamp
            courseMapView.setHeadingDegrees(smoothed)
            navigationCourseMapView.setHeadingDegrees(smoothed)
            if (currentPage == AppPage.MAP.ordinal) {
                offlineMapView?.setHeadingDegrees(smoothed)
                satelliteMapView?.setHeadingDegrees(smoothed)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val courseStore by lazy { CourseFileStore(this) }
    private val mapRepository by lazy { MapsforgeMapRepository(this) }
    private val offlineRouter by lazy { BRouterOfflineRouter(this) }
    private val waypointStore by lazy { WaypointStore(this) }
    private val favoriteTargetStore by lazy { FavoriteTargetStore(this) }
    private val watchSyncCoordinator by lazy { WatchSyncCoordinator(this) }
    private var courseReloadName: String? = null
    private val telemetryListener: (NavigationTelemetry) -> Unit = { telemetry ->
        runOnUiThread { renderTelemetry(telemetry) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) startSession()
        else statusText.setText(R.string.location_required)
    }

    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startFishingAudioRecording()
        else fishingDashboardView.showNotice(getString(R.string.fishing_microphone_required))
    }

    private val fishingLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            updateFishingLocationUpdates()
            fishingDashboardView.showNotice(getString(R.string.fishing_locating))
        } else {
            pendingFishingQuickPin = false
            fishingDashboardView.showNotice(getString(R.string.location_required))
        }
    }

    private val mapLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            updateFishingLocationUpdates()
        } else {
            statusText.setText(R.string.location_required)
        }
    }

    private val gpxLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        statusText.setText(R.string.importing_gpx)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val fileName = selectedDocumentName(uri) ?: "Imported course"
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Unable to open selected document" }
                        if (fileName.endsWith(".kml", ignoreCase = true)) {
                            courseStore.importKml(
                                input,
                                fileName.substringBeforeLast('.'),
                                importCoordinateSystem,
                            )
                        } else {
                            courseStore.import(
                                input,
                                fileName.substringBeforeLast('.'),
                                importCoordinateSystem,
                            )
                        }
                    }
                }
            }.onSuccess { stored ->
                activateCourse(stored.course)
                statusText.text = getString(
                    R.string.course_imported,
                    stored.course.name,
                    stored.course.points.size,
                )
            }.onFailure(::showImportError)
        }
    }

    private val mapLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        statusText.setText(R.string.importing_map)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Unable to open selected map" }
                        mapRepository.import(input)
                    }
                }
            }.onSuccess { mapFile ->
                getSharedPreferences(MAP_PREFERENCES, MODE_PRIVATE).edit()
                    .putString(KEY_ACTIVE_MAP_KIND, TransferKind.MAPSFORGE.name)
                    .apply()
                showConfiguredMapLayer()
                statusText.setText(R.string.map_ready)
            }.onFailure { error ->
                statusText.text = getString(
                    R.string.map_import_failed,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private val routingRegionLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val fileName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            cursor.takeIf { it.moveToFirst() }?.getString(0)
                        }
                        ?: error("Unable to read routing filename")
                    require(fileName.endsWith(".rd5", ignoreCase = true)) { "Select a BRouter .rd5 file" }
                    contentResolver.openInputStream(uri).use { input ->
                        offlineRouter.importRegion(
                            fileName.substringBeforeLast('.'),
                            requireNotNull(input) { "Unable to open routing region" },
                        )
                    }
                }
            }.onSuccess { file ->
                statusText.text = getString(R.string.routing_region_ready, file.name)
            }.onFailure { error ->
                statusText.text = getString(
                    R.string.routing_region_failed,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private val exportTrackLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri).use { output ->
                        TrackStore(this@MainActivity).exportGpx(
                            requireNotNull(output) { "Unable to create GPX" },
                        )
                    }
                }
            }.onSuccess {
                statusText.setText(R.string.track_exported)
            }.onFailure { error ->
                statusText.text = getString(
                    R.string.track_export_failed,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private val exportFitLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri).use { output ->
                        TrackStore(this@MainActivity).exportFit(
                            requireNotNull(output) { "Unable to create FIT file" },
                        )
                    }
                }
            }.onSuccess {
                statusText.setText(R.string.track_exported)
            }.onFailure { error ->
                statusText.text = getString(
                    R.string.track_export_failed,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setWearSyncListenerEnabled(true)
        setContentView(buildContent())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    overlayHost.visibility == View.VISIBLE -> closeOverlay()
                    currentPage != AppPage.MAP.ordinal -> showPage(AppPage.MAP.ordinal)
                    NavigationStateHub.current.phase != SessionPhase.IDLE ->
                        showPage(AppPage.NAVIGATION.ordinal)
                    else -> moveTaskToBack(true)
                }
            }
        })
        applyAlwaysOnPreference()
        importBundledCoursesThenLoad()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        showConfiguredMapLayer()
        updateUiSensors()
        ensureMapLocationPermission()
    }

    override fun onPause() {
        activityResumed = false
        fishingCastSensorRegistered = false
        fishingCastDetector.reset()
        sensorManager.unregisterListener(orientationListener)
        locationManager.removeUpdates(fishingLocationListener)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        NavigationStateHub.addListener(telemetryListener)
        watchSyncCoordinator.announceLightweight()
    }

    override fun onStop() {
        NavigationStateHub.removeListener(telemetryListener)
        super.onStop()
    }

    override fun onDestroy() {
        routeApproachJob?.cancel()
        watchSyncCoordinator.close()
        offlineMapView?.destroy()
        offlineMapView = null
        offlineMapStackIdentity = null
        satelliteMapView?.close()
        satelliteMapView = null
        satelliteMapPath = null
        super.onDestroy()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.source and InputDevice.SOURCE_ROTARY_ENCODER == InputDevice.SOURCE_ROTARY_ENCODER
        ) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (scroll != 0f) {
                if (currentPage == AppPage.FISHING.ordinal) {
                    fishingDashboardView.onRotary(scroll)
                } else if (offlineMapView != null || satelliteMapView != null) {
                    offlineMapView?.zoomBy(if (scroll > 0f) 1 else -1)
                    satelliteMapView?.zoomBy(if (scroll > 0f) 1 else -1)
                    (offlineMapView ?: satelliteMapView)?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } else {
                    return super.dispatchGenericMotionEvent(event)
                }
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentPage == AppPage.FISHING.ordinal &&
            (keyCode == KeyEvent.KEYCODE_STEM_1 || keyCode == KeyEvent.KEYCODE_STEM_2)
        ) {
            if (event?.repeatCount == 0) {
                longPressHandled = false
                event.startTracking()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentPage != AppPage.FISHING.ordinal) return super.onKeyLongPress(keyCode, event)
        longPressHandled = true
        return when (keyCode) {
            KeyEvent.KEYCODE_STEM_1 -> { beginFishingVoiceLog(); true }
            KeyEvent.KEYCODE_STEM_2 -> { activateMobMode(); true }
            else -> super.onKeyLongPress(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentPage == AppPage.FISHING.ordinal &&
            (keyCode == KeyEvent.KEYCODE_STEM_1 || keyCode == KeyEvent.KEYCODE_STEM_2)
        ) {
            if (!longPressHandled) {
                if (keyCode == KeyEvent.KEYCODE_STEM_1) saveFishingQuickPin()
                else showPage(AppPage.MAP.ordinal)
            }
            longPressHandled = false
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_STEM_1) {
            showPage(currentPage + 1)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_STEM_2) {
            showFeatureHubOverlay()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (::overlayHost.isInitialized && overlayHost.visibility == View.VISIBLE) {
                closeOverlay()
                return true
            }
            if (currentPage != AppPage.MAP.ordinal) {
                showPage(AppPage.MAP.ordinal)
                return true
            }
            if (NavigationStateHub.current.phase != SessionPhase.IDLE) {
                showPage(AppPage.NAVIGATION.ordinal)
                return true
            }
            return super.onKeyUp(keyCode, event)
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun buildContent(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(2, 19, 21))
        }
        pageHost = SwipePageHost(this).apply {
            // Explicit buttons are safer than page swipes on watches whose edge swipe exits the app.
            swipeEnabled = false
        }
        root.addView(pageHost, FrameLayout.LayoutParams(-1, -1))

        courseText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(8))
        }
        statusText = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(4), dp(3), dp(4), dp(3))
            setBackgroundColor(Color.TRANSPARENT)
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        }
        root.addView(
            statusText,
            FrameLayout.LayoutParams(dp(178), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(18)
            },
        )

        pages += buildMapPage()
        pages += buildNavigationPage()
        pages += buildFishingPage()
        pages.forEachIndexed { index, page ->
            page.visibility = if (index == currentPage) View.VISIBLE else View.GONE
            pageHost.addView(page, FrameLayout.LayoutParams(-1, -1))
        }

        overlayHost = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.argb(252, 2, 20, 22))
        }
        root.addView(overlayHost, FrameLayout.LayoutParams(-1, -1))

        // Build once so settings/download controls retain their state while the overlay is closed.
        overlayHost.tag = buildFeatureHubContent()
        return root
    }

    private fun buildMapPage(): View {
        courseMapView = CourseMapView(this).apply {
            contentDescription = getString(R.string.page_map)
        }
        mapLayerContainer = FrameLayout(this).apply {
            addView(courseMapView, FrameLayout.LayoutParams(-1, -1))
        }
        mapContainer = FrameLayout(this).apply {
            addView(mapLayerContainer, FrameLayout.LayoutParams(-1, -1))
            addView(compactNavButton("↑", R.string.open_navigation_page, MAP_ICON_COLOR) { startNavigationFromMap() },
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                    leftMargin = dp(4)
                })
            addView(compactNavButton("≋", R.string.route_library, MAP_ICON_COLOR) { showRouteLibraryOverlay() },
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dp(16)
                    bottomMargin = dp(88)
                })
            addView(compactNavButton("▧", R.string.switch_map_layer, MAP_ICON_COLOR) { cycleWatchMapLayer() },
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dp(72)
                    bottomMargin = dp(24)
                })
            addView(compactNavButton("⚙", R.string.feature_hub, MAP_ICON_COLOR) { showFeatureHubOverlay() },
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(4)
                })
        }
        return mapContainer
    }

    private fun buildNavigationPage(): View = FrameLayout(this).apply {
        navigationCourseMapView = LightweightNavigationView(this@MainActivity).apply {
            contentDescription = getString(R.string.page_navigation)
        }
        navigationMapLayerContainer = FrameLayout(this@MainActivity).apply {
            addView(navigationCourseMapView, FrameLayout.LayoutParams(-1, -1))
        }
        addView(navigationMapLayerContainer, FrameLayout.LayoutParams(-1, -1))
        addView(compactNavButton("➤", R.string.north_and_return) { showPage(AppPage.MAP.ordinal) },
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                leftMargin = dp(4)
            })
        addView(compactNavButton("⚓", R.string.open_fishing_page) {
            showPage(AppPage.FISHING.ordinal)
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(4)
        })
    }

    private fun buildElevationPage(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(18), dp(36), dp(18), dp(20))
        addView(pageTitle(R.string.page_elevation), matchWrap())
        elevationProfileView = ElevationProfileView(this@MainActivity).apply {
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        addView(
            elevationProfileView,
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        elevationStatsText = TextView(this@MainActivity).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        addView(elevationStatsText, matchWrap())
        addView(actionButton(R.string.open_navigation_page) { showPage(AppPage.NAVIGATION.ordinal) }, wrapWrap())
    }

    private fun buildActionsPage(): View = scrollablePage(R.string.page_actions).apply {
        addView(courseText, matchWrap())
        addView(actionButton(R.string.mark_lap) { NavigationSessionService.markLap(this@MainActivity) }, wrapWrap())
        exercisePauseButton = actionButton(R.string.pause_activity) {
            if (NavigationStateHub.current.exercisePaused) {
                NavigationSessionService.resumeExercise(this@MainActivity)
            } else {
                NavigationSessionService.pauseExercise(this@MainActivity)
            }
        }
        addView(exercisePauseButton, wrapWrap())
        addView(actionButton(R.string.save_current_location) { saveCurrentLocation() }, wrapWrap())
        addView(actionButton(R.string.route_to_saved_location) { routeToLatestWaypoint() }, wrapWrap())
        addView(actionButton(R.string.tracback) {
            NavigationSessionService.tracBack(this@MainActivity)
            statusText.setText(R.string.tracback_starting)
        }, wrapWrap())
        addView(actionButton(R.string.reverse_course) { reverseActiveCourse() }, wrapWrap())
        addView(actionButton(R.string.next_library_course) { activateNextLibraryCourse() }, wrapWrap())
        addView(actionButton(R.string.export_track) {
            exportTrackLauncher.launch("watchnav-activity-${System.currentTimeMillis()}.gpx")
        }, wrapWrap())
        addView(actionButton(R.string.export_fit) {
            exportFitLauncher.launch("watchnav-activity-${System.currentTimeMillis()}.fit")
        }, wrapWrap())
        addView(actionButton(R.string.activity_history) { showActivityHistory() }, wrapWrap())
    }.let(::wrapScrollablePage)

    private fun buildDownloadsPage(): View = scrollablePage(R.string.page_downloads).apply {
        addView(actionButton(R.string.open_map_downloader) {
            startActivity(Intent(this@MainActivity, MapDownloadActivity::class.java))
        }, wrapWrap())
        addView(actionButton(R.string.download_contour_map) {
            startActivity(MapDownloadActivity.intent(this@MainActivity, MapLayerKind.CONTOUR))
        }, wrapWrap())
        addView(actionButton(R.string.import_map) {
            mapLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }, wrapWrap())
        addView(actionButton(R.string.import_routing_region) {
            routingRegionLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }, wrapWrap())
        addView(actionButton(R.string.import_gpx) {
            gpxLauncher.launch(arrayOf("application/gpx+xml", "text/xml", "application/xml", "*/*"))
        }, wrapWrap())
        gpxUrlInput = EditText(this@MainActivity).apply {
            setHint(R.string.gpx_url_hint)
            setText(getSharedPreferences(DOWNLOAD_PREFERENCES, MODE_PRIVATE)
                .getString(KEY_LAST_GPX_URL, DEFAULT_GPX_URL))
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { downloadGpx(); true } else false
            }
        }
        addView(gpxUrlInput, matchWrap())
        downloadButton = actionButton(R.string.download_gpx) { downloadGpx() }
        addView(downloadButton, wrapWrap())
    }.let(::wrapScrollablePage)

    private fun buildSettingsPage(): View = scrollablePage(R.string.page_settings).apply {
        addView(actionButton(R.string.app_language_setting) {
            AppLanguageController.toggle(this@MainActivity)
            recreate()
        }, wrapWrap())
        followMapButton = actionButton(R.string.map_follow_on) {
            val enabled = offlineMapView?.toggleFollow() ?: satelliteMapView?.toggleFollow() ?: true
            setText(if (enabled) R.string.map_follow_on else R.string.map_follow_off)
        }
        addView(followMapButton, wrapWrap())
        powerModeButton = actionButton(R.string.power_mode) { cyclePowerMode() }
        addView(powerModeButton, wrapWrap())
        alwaysOnButton = actionButton(R.string.always_on_disabled) { toggleAlwaysOn() }
        addView(alwaysOnButton, wrapWrap())
        lockText = TextView(this@MainActivity).apply {
            setText(R.string.touch_enabled_hint)
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }
        addView(lockText, matchWrap())
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.control_support_summary)
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }, matchWrap())
        addView(TextView(this@MainActivity).apply {
            text = getString(
                R.string.version_info,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.BUILD_TYPE,
            )
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }, matchWrap())
    }.let(::wrapScrollablePage)

    private fun buildFeatureHubContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(38), dp(42), dp(38), dp(46))
        }
        content.addView(overlayTitle(R.string.feature_hub), matchWrap())
        content.addView(courseText, matchWrap())

        content.addView(sectionTitle(R.string.page_actions), matchWrap())
        content.addView(actionButton(R.string.stop_navigation) {
            NavigationSessionService.stop(this@MainActivity)
            statusText.setText(R.string.navigation_stopped)
            closeOverlay()
            showPage(AppPage.MAP.ordinal)
        }, wrapWrap())
        content.addView(actionButton(R.string.activity_history) { showActivityHistory() }, wrapWrap())

        content.addView(sectionTitle(R.string.page_downloads), matchWrap())
        content.addView(actionButton(R.string.open_map_downloader) {
            startActivity(Intent(this@MainActivity, MapDownloadActivity::class.java))
        }, wrapWrap())
        content.addView(actionButton(R.string.download_contour_map) {
            startActivity(MapDownloadActivity.intent(this@MainActivity, MapLayerKind.CONTOUR))
        }, wrapWrap())
        content.addView(actionButton(R.string.import_map) {
            mapLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }, wrapWrap())
        content.addView(actionButton(R.string.import_routing_region) {
            routingRegionLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }, wrapWrap())
        importCoordinateButton = actionButton(R.string.import_coordinate_system) {
            val systems = CoordinateSystem.entries
            importCoordinateSystem = systems[(systems.indexOf(importCoordinateSystem) + 1) % systems.size]
            renderImportCoordinateSystem()
        }
        content.addView(importCoordinateButton, wrapWrap())
        content.addView(actionButton(R.string.import_gpx) {
            gpxLauncher.launch(arrayOf("application/gpx+xml", "text/xml", "application/xml", "*/*"))
        }, wrapWrap())

        content.addView(sectionTitle(R.string.page_settings), matchWrap())
        content.addView(actionButton(R.string.app_language_setting) {
            AppLanguageController.toggle(this@MainActivity)
            recreate()
        }, wrapWrap())
        followMapButton = actionButton(R.string.map_follow_on) {
            val enabled = offlineMapView?.toggleFollow() ?: satelliteMapView?.toggleFollow() ?: true
            setText(if (enabled) R.string.map_follow_on else R.string.map_follow_off)
        }
        content.addView(followMapButton, wrapWrap())
        powerModeButton = actionButton(R.string.power_mode) { cyclePowerMode() }
        content.addView(powerModeButton, wrapWrap())
        alwaysOnButton = actionButton(R.string.always_on_disabled) { toggleAlwaysOn() }
        content.addView(alwaysOnButton, wrapWrap())
        content.addView(actionButton(R.string.fishing_metric_settings) {
            showFishingMetricSettingsOverlay()
        }, wrapWrap())
        val tideKeyInput = EditText(this).apply {
            setHint(R.string.fishing_tide_api_key_hint)
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            // Wear OS discourages password fields; the API key stays editable for standalone use.
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        content.addView(tideKeyInput, matchWrap())
        content.addView(actionButton(R.string.fishing_save_tide_key) {
            tideRepository.saveApiKey(tideKeyInput.text.toString())
            fishingDashboardView.showNotice(getString(R.string.fishing_tide_key_saved))
            currentFishingPosition()?.let { refreshTideForecast(it, force = true) }
        }, wrapWrap())
        val waterTemperatureInput = EditText(this).apply {
            setHint(R.string.fishing_water_temperature_hint)
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            fishingStore.waterTemperatureCelsius()?.let { setText(String.format(Locale.US, "%.1f", it)) }
            setSingleLine(true)
        }
        content.addView(waterTemperatureInput, matchWrap())
        var saltWater = fishingStore.isSaltWater()
        val waterTypeButton = actionButton(
            if (saltWater) R.string.fishing_water_salt else R.string.fishing_water_fresh,
        ) {
            saltWater = !saltWater
            setText(if (saltWater) R.string.fishing_water_salt else R.string.fishing_water_fresh)
        }
        content.addView(waterTypeButton, wrapWrap())
        content.addView(actionButton(R.string.fishing_save_water_profile) {
            val temperature = waterTemperatureInput.text.toString().trim().toFloatOrNull()?.takeIf { it in 0f..40f }
            fishingStore.saveWaterProfile(temperature, saltWater)
            fishingDashboardView.setWaterProfile(temperature, saltWater)
            fishingDashboardView.showNotice(getString(R.string.fishing_water_profile_saved))
        }, wrapWrap())
        content.addView(TextView(this).apply {
            text = getString(R.string.version_info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE)
            textSize = 20f
            setTextColor(Color.rgb(235, 184, 255))
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(14), dp(4), dp(14))
        }, matchWrap())
        content.addView(actionButton(R.string.exit_application) { exitApplicationCompletely() }, wrapWrap())
        content.addView(actionButton(R.string.close) { closeOverlay() }, wrapWrap())
        renderImportCoordinateSystem()
        return ScrollView(this).apply {
            isFillViewport = true
            addView(content, matchWrap())
        }
    }

    private fun renderImportCoordinateSystem() {
        if (::importCoordinateButton.isInitialized) {
            importCoordinateButton.text = getString(
                R.string.import_coordinate_system,
                importCoordinateSystem.displayName,
            )
        }
    }

    private fun showFeatureHubOverlay() {
        showOverlay(requireNotNull(overlayHost.tag as? View))
    }

    private fun showFishingMetricSettingsOverlay() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(34), dp(30), dp(34), dp(36))
        }
        fun renderOptions() {
            content.removeAllViews()
            content.addView(overlayTitle(R.string.fishing_metric_settings), matchWrap())
            fishingDashboardView.metricOptions().forEach { option ->
                content.addView(Button(this).apply {
                    text = "${if (option.enabled) "✓" else "○"}  ${option.title}"
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(8, 58, 62))
                    setOnClickListener {
                        if (!fishingDashboardView.setMetricEnabled(option.id, !option.enabled)) {
                            fishingDashboardView.showNotice(getString(R.string.fishing_metric_at_least_one))
                        }
                        renderOptions()
                    }
                }, matchWrap())
            }
            content.addView(actionButton(R.string.close) { closeOverlay() }, wrapWrap())
        }
        renderOptions()
        showOverlay(ScrollView(this).apply { isFillViewport = true; addView(content, matchWrap()) })
    }

    private fun showRouteLibraryOverlay() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(26), dp(30), dp(34))
        }
        content.addView(overlayTitle(R.string.route_library), matchWrap())
        content.addView(actionButton(
            if (NavigationSessionService.isExploring(this)) R.string.finish_free_exploration
            else R.string.start_free_exploration,
        ) { toggleFreeExploration() }, wrapWrap())
        content.addView(actionButton(R.string.receive_route) {
            WatchLanSyncService.openReceiveWindow(this@MainActivity)
            watchSyncCoordinator.requestSync()
            statusText.setText(R.string.receiving_route)
        }, wrapWrap())
        content.addView(sectionTitle(R.string.primary_routes), matchWrap())
        val routeList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // Put the actual route choices before search. On a round 480 px display the previous
        // ordering pushed every route below the fold, making the library look empty until the
        // user searched or scrolled past a full-screen search form.
        content.addView(routeList, matchWrap())
        val search = EditText(this).apply {
            setHint(R.string.search_route_or_destination)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        content.addView(search, matchWrap())
        content.addView(actionButton(R.string.route_to_searched_destination) {
            searchDestination(search.text.toString())
        }, wrapWrap())
        content.addView(actionButton(R.string.close) { closeOverlay() }, wrapWrap())
        showOverlay(ScrollView(this).apply { isFillViewport = true; addView(content, matchWrap()) })

        var routeLoadJob: Job? = null
        fun loadRoutes(query: String) {
            routeLoadJob?.cancel()
            routeLoadJob = lifecycleScope.launch {
                val choices = withContext(Dispatchers.IO) {
                    courseStore.listLibrary()
                        .filter { it.name.contains(query, ignoreCase = true) }
                        .sortedBy { entry ->
                            NavigationStateHub.current.position?.let { position ->
                                entry.startPosition?.let { GeoMath.distanceMeters(position, it) }
                            }
                                ?: Double.MAX_VALUE
                        }
                }
                routeList.removeAllViews()
                if (choices.isEmpty()) {
                    routeList.addView(TextView(this@MainActivity).apply {
                        setText(R.string.no_matching_routes)
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    }, matchWrap())
                }
                choices.forEachIndexed { index, entry ->
                    val distance = NavigationStateHub.current.position?.let { position ->
                        entry.startPosition?.let { GeoMath.distanceMeters(position, it) / 1_000.0 }
                    }
                    val label = buildString {
                        if (index == 0 && distance != null) append(getString(R.string.recommended_route_prefix))
                        append(entry.name)
                        if (entry.pointCount >= 0) {
                            append(" · ").append(getString(R.string.route_point_count, entry.pointCount))
                        }
                        if (distance != null) append(" · ").append(getString(R.string.route_start_distance, distance))
                    }
                    routeList.addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(Button(this@MainActivity).apply {
                            text = label
                            textSize = 14f
                            isAllCaps = false
                            setOnClickListener {
                                lifecycleScope.launch {
                                    isEnabled = false
                                    if (NavigationSessionService.isExploring(this@MainActivity)) {
                                        NavigationSessionService.finishExploration(this@MainActivity)
                                        delay(600)
                                    }
                                    runCatching { withContext(Dispatchers.IO) { courseStore.activate(entry) } }
                                        .onSuccess { stored ->
                                            activateCourseWithAutomaticApproach(stored.course)
                                            closeOverlay()
                                            showPage(AppPage.MAP.ordinal)
                                            statusText.setText(R.string.course_ready)
                                        }.onFailure(::showImportError)
                                    isEnabled = true
                                }
                            }
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(Button(this@MainActivity).apply {
                            setText(R.string.delete_route_short)
                            textSize = 13f
                            isAllCaps = false
                            setOnClickListener {
                                lifecycleScope.launch {
                                    withContext(Dispatchers.IO) { courseStore.delete(entry) }
                                    if (activeCourse?.name.equals(entry.name, ignoreCase = true)) {
                                        clearDisplayedCourse()
                                    }
                                    statusText.text = getString(R.string.route_deleted, entry.name)
                                    loadRoutes(search.text.toString().trim())
                                }
                            }
                        }, LinearLayout.LayoutParams(dp(74), -2))
                    }, matchWrap())
                }
            }
        }
        search.addTextChangedListener { loadRoutes(it?.toString().orEmpty().trim()) }
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { searchDestination(search.text.toString()); true } else false
        }
        loadRoutes("")
    }

    private fun toggleFreeExploration() {
        closeOverlay()
        showPage(AppPage.MAP.ordinal)
        if (NavigationSessionService.isExploring(this)) {
            NavigationSessionService.finishExploration(this)
            statusText.setText(R.string.free_exploration_saved)
            lifecycleScope.launch {
                delay(600)
                loadStoredCourse()
                watchSyncCoordinator.announceLightweight()
            }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            statusText.setText(R.string.location_required)
            return
        }
        clearDisplayedCourseForExploration()
        val name = getString(
            R.string.free_exploration_route_name,
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date()),
        )
        lifecycleScope.launch {
            if (NavigationStateHub.current.phase != SessionPhase.IDLE) {
                NavigationSessionService.stop(this@MainActivity)
                var attempts = 0
                while (NavigationStateHub.current.phase != SessionPhase.IDLE && attempts < 20) {
                    delay(100)
                    attempts++
                }
            }
            NavigationSessionService.startExploration(this@MainActivity, name)
            statusText.setText(R.string.free_exploration_started)
        }
    }

    private fun clearDisplayedCourseForExploration() {
        clearDisplayedCourse()
        courseText.setText(R.string.start_free_exploration)
    }

    private fun clearDisplayedCourse() {
        activeCourse = null
        courseReloadName = null
        courseMapView.setCourse(null)
        navigationCourseMapView.setCourse(null)
        offlineMapView?.clearCourse()
        satelliteMapView?.clearCourse()
        courseText.setText(R.string.select_course_hint)
    }

    private fun showOverlay(view: View) {
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        overlayHost.removeAllViews()
        overlayHost.addView(view, FrameLayout.LayoutParams(-1, -1))
        overlayHost.visibility = View.VISIBLE
    }

    private fun closeOverlay() {
        overlayHost.removeAllViews()
        overlayHost.visibility = View.GONE
    }

    private fun overlayTitle(text: Int) = TextView(this).apply {
        setText(text)
        textSize = 22f
        setTextColor(Color.rgb(71, 238, 235))
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(4), dp(4), dp(10))
    }

    private fun sectionTitle(text: Int) = TextView(this).apply {
        setText(text)
        textSize = 19f
        setTextColor(Color.rgb(255, 126, 55))
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(16), dp(4), dp(6))
    }

    private fun scrollablePage(title: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(42), dp(24), dp(34))
        addView(pageTitle(title), matchWrap())
    }

    private fun wrapScrollablePage(content: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(content, matchWrap())
    }

    private fun pageTitle(text: Int) = TextView(this).apply {
        setText(text)
        textSize = 20f
        setTextColor(Color.rgb(71, 238, 235))
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(3), dp(4), dp(8))
    }

    private fun actionButton(text: Int, action: Button.() -> Unit): Button = Button(this).apply {
        setText(text)
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minHeight = dp(48)
        minWidth = roundSafeButtonWidth() - dp(24)
        maxWidth = roundSafeButtonWidth()
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.rgb(5, 46, 49))
            setStroke(dp(2), Color.rgb(52, 225, 222))
        }
        setOnClickListener { action() }
    }

    private fun actionButtonText(label: String, action: Button.() -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minHeight = dp(48)
        minWidth = roundSafeButtonWidth() - dp(24)
        maxWidth = roundSafeButtonWidth()
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.rgb(5, 46, 49))
            setStroke(dp(2), Color.rgb(52, 225, 222))
        }
        setOnClickListener { action() }
    }

    private fun compactNavButton(
        label: String,
        description: Int,
        color: Int = if (label == "■") Color.rgb(255, 112, 58) else Color.WHITE,
        action: () -> Unit,
    ) =
        TextView(this).apply {
            text = label
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(color)
            background = null
            contentDescription = getString(description)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun showPage(index: Int) {
        if (pages.isEmpty()) return
        val normalized = ((index % pages.size) + pages.size) % pages.size
        pages.forEachIndexed { pageIndex, page ->
            page.visibility = if (pageIndex == normalized) View.VISIBLE else View.GONE
        }
        currentPage = normalized
        statusText.visibility = if (currentPage == AppPage.FISHING.ordinal) View.GONE else View.VISIBLE
        statusText.paint.blendMode = if (currentPage == AppPage.MAP.ordinal) BlendMode.DIFFERENCE else null
        if (currentPage == AppPage.MAP.ordinal) statusText.setTextColor(Color.WHITE)
        updateUiSensors()
        updateFishingLocationUpdates()
        attachMapToCurrentPage()
    }

    private fun buildLegacyContentUnused(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(18), dp(22), dp(28))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(Button(this).apply {
            setText(R.string.start_navigation)
            setOnClickListener { ensurePermissionAndStart() }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.stop)
            setOnClickListener {
                NavigationSessionService.stop(this@MainActivity)
                statusText.setText(R.string.navigation_stopped)
            }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.mark_lap)
            setOnClickListener { NavigationSessionService.markLap(this@MainActivity) }
        }, wrapWrap())
        exercisePauseButton = Button(this).apply {
            setText(R.string.pause_activity)
            setOnClickListener {
                if (NavigationStateHub.current.exercisePaused) {
                    NavigationSessionService.resumeExercise(this@MainActivity)
                } else {
                    NavigationSessionService.pauseExercise(this@MainActivity)
                }
            }
        }
        root.addView(exercisePauseButton, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.tracback)
            setOnClickListener {
                NavigationSessionService.tracBack(this@MainActivity)
                statusText.setText(R.string.tracback_starting)
            }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.reverse_course)
            setOnClickListener { reverseActiveCourse() }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.next_library_course)
            setOnClickListener { activateNextLibraryCourse() }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.export_track)
            setOnClickListener {
                exportTrackLauncher.launch("watchnav-activity-${System.currentTimeMillis()}.gpx")
            }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.export_fit)
            setOnClickListener {
                exportFitLauncher.launch("watchnav-activity-${System.currentTimeMillis()}.fit")
            }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.activity_history)
            setOnClickListener { showActivityHistory() }
        }, wrapWrap())
        followMapButton = Button(this).apply {
            setText(R.string.map_follow_on)
            setOnClickListener {
                val enabled = offlineMapView?.toggleFollow() ?: satelliteMapView?.toggleFollow() ?: true
                setText(if (enabled) R.string.map_follow_on else R.string.map_follow_off)
            }
        }
        root.addView(followMapButton, wrapWrap())
        powerModeButton = Button(this).apply {
            setOnClickListener { cyclePowerMode() }
        }
        root.addView(powerModeButton, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.import_gpx)
            setOnClickListener {
                gpxLauncher.launch(arrayOf("application/gpx+xml", "text/xml", "application/xml", "*/*"))
            }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.open_map_downloader)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, MapDownloadActivity::class.java))
            }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.import_map)
            setOnClickListener { mapLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.import_routing_region)
            setOnClickListener {
                routingRegionLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.save_current_location)
            setOnClickListener { saveCurrentLocation() }
        }, wrapWrap())
        root.addView(Button(this).apply {
            setText(R.string.route_to_saved_location)
            setOnClickListener { routeToLatestWaypoint() }
        }, wrapWrap())
        gpxUrlInput = EditText(this).apply {
            setHint(R.string.gpx_url_hint)
            setText(
                getSharedPreferences(DOWNLOAD_PREFERENCES, MODE_PRIVATE)
                    .getString(KEY_LAST_GPX_URL, DEFAULT_GPX_URL),
            )
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    downloadGpx()
                    true
                } else {
                    false
                }
            }
        }
        root.addView(gpxUrlInput, matchWrap())
        downloadButton = Button(this).apply {
            setText(R.string.download_gpx)
            setOnClickListener { downloadGpx() }
        }
        root.addView(downloadButton, wrapWrap())
        alwaysOnButton = Button(this).apply {
            setOnClickListener { toggleAlwaysOn() }
        }
        root.addView(alwaysOnButton, wrapWrap())
        lockText = TextView(this).apply {
            setText(R.string.touch_enabled_hint)
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(4, dp(10), 4, 0)
        }
        root.addView(lockText, matchWrap())
        root.addView(TextView(this).apply {
            text = getString(
                R.string.version_info,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.BUILD_TYPE,
            )
            textSize = 10f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(4, dp(8), 4, 0)
        }, matchWrap())
        return ScrollView(this).apply {
            isFillViewport = true
            addView(root, matchWrap())
        }
    }

    private fun loadStoredCourse() {
        statusText.setText(R.string.loading_course)
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { courseStore.load() } }
                .onSuccess { stored ->
                    if (stored == null) {
                        courseText.setText(R.string.no_course)
                        statusText.setText(R.string.select_course_hint)
                    } else {
                        activateCourse(stored.course)
                        if (NavigationStateHub.current.phase == SessionPhase.IDLE) {
                            statusText.setText(R.string.course_ready)
                        }
                    }
                }
                .onFailure(::showImportError)
        }
    }

    private fun importBundledCoursesThenLoad() {
        val preferences = getSharedPreferences(COURSE_IMPORT_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_HANGZHOU_2025_IMPORTED, false)) {
            loadStoredCourse()
            return
        }
        statusText.setText(R.string.importing_gpx)
        lifecycleScope.launch {
            val imported = runCatching {
                withContext(Dispatchers.IO) {
                    assets.list(BUNDLED_COURSE_DIRECTORY).orEmpty().sorted().map { fileName ->
                        assets.open("$BUNDLED_COURSE_DIRECTORY/$fileName").use { input ->
                            if (fileName.endsWith(".kml", ignoreCase = true)) {
                                courseStore.importKml(input, fileName.substringBeforeLast('.'))
                            } else {
                                courseStore.import(input, fileName.substringBeforeLast('.'))
                            }
                        }
                    }
                }
            }
            imported.onSuccess { courses ->
                preferences.edit { putBoolean(KEY_HANGZHOU_2025_IMPORTED, true) }
                courses.lastOrNull()?.let { stored ->
                    activateCourse(stored.course)
                    statusText.text = getString(
                        R.string.bundled_courses_imported,
                        courses.size,
                        stored.course.name,
                    )
                }
                if (courses.isEmpty()) loadStoredCourse()
            }.onFailure(::showImportError)
        }
    }

    private fun downloadGpx() {
        val url = gpxUrlInput.text.toString()
        getSharedPreferences(DOWNLOAD_PREFERENCES, MODE_PRIVATE)
            .edit { putString(KEY_LAST_GPX_URL, url.trim()) }
        downloadButton.isEnabled = false
        statusText.setText(R.string.downloading_gpx)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val downloaded = GpxDownloader().download(url)
                    courseStore.save(downloaded.bytes, downloaded.fileName.substringBeforeLast('.'))
                }
            }.onSuccess { stored ->
                activateCourse(stored.course)
                statusText.text = getString(
                    R.string.course_downloaded,
                    stored.course.name,
                    stored.course.points.size,
                )
            }.onFailure { error ->
                statusText.text = getString(
                    R.string.gpx_download_failed,
                    error.message ?: error.javaClass.simpleName,
                )
            }
            downloadButton.isEnabled = true
        }
    }

    private fun selectedDocumentName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> cursor.takeIf { it.moveToFirst() }?.getString(0) }

    private fun activateCourse(course: Course) {
        val enrichedCourse = addImportantLandmarks(course)
        activeCourse = enrichedCourse
        courseReloadName = enrichedCourse.name
        courseMapView.setCourse(enrichedCourse)
        navigationCourseMapView.setCourse(enrichedCourse)
        if (::elevationProfileView.isInitialized) elevationProfileView.setCourse(enrichedCourse)
        offlineMapView?.apply { setExplorationTrail(emptyList()); setCourse(enrichedCourse) }
        satelliteMapView?.apply { setExplorationTrail(emptyList()); setCourse(enrichedCourse) }
        val prepared = PreparedCourse.prepare(enrichedCourse)
        val ascent = enrichedCourse.points.zipWithNext().sumOf { (first, second) ->
            val a = first.position.elevationMeters
            val b = second.position.elevationMeters
            if (a != null && b != null) (b - a).coerceAtLeast(0.0) else 0.0
        }
        courseText.text = getString(
            R.string.course_summary,
            enrichedCourse.name,
            prepared.totalDistanceMeters / 1_000,
            ascent,
        )
        if (NavigationStateHub.current.phase == SessionPhase.IDLE) {
            NavigationStateHub.previewCourse(
                enrichedCourse.name,
                prepared.totalDistanceMeters,
                ascent,
                NavigationStateHub.current.position ?: directLocationFix?.position
                    ?: LastNavigationFixStore(this).load()?.position,
            )
        }
    }

    private fun renderTelemetry(telemetry: NavigationTelemetry) {
        val exploring = NavigationSessionService.isExploring(this)
        val navigationActive = telemetry.phase != SessionPhase.IDLE
        val navigationJustStopped = navigationWasActive && !navigationActive
        navigationWasActive = navigationActive
        if (navigationActive) {
            // The foreground session service is the single GNSS owner while navigating. Keeping
            // this Activity listener registered caused two simultaneous GPS requests whenever the
            // map was visible, increasing heat without improving the fused fix.
            locationManager.removeUpdates(fishingLocationListener)
        } else if (navigationJustStopped && activityResumed) {
            updateFishingLocationUpdates()
        }
        if (exploring) {
            offlineMapView?.setExplorationTrail(telemetry.breadcrumbTrail)
            satelliteMapView?.setExplorationTrail(telemetry.breadcrumbTrail)
        }
        if (telemetry.phase != SessionPhase.IDLE) {
            // Navigation remains fully active in the foreground service while the OLED is allowed
            // to sleep. Keeping the display awake was the largest measured battery drain.
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (currentPage != AppPage.FISHING.ordinal && currentPage != AppPage.NAVIGATION.ordinal) {
                sensorManager.unregisterListener(orientationListener)
            }
        }
        when (currentPage) {
            AppPage.MAP.ordinal -> {
                courseMapView.setTelemetry(telemetry)
                offlineMapView?.setTelemetry(telemetry)
                satelliteMapView?.setTelemetry(telemetry)
            }
            AppPage.NAVIGATION.ordinal -> navigationCourseMapView.setTelemetry(telemetry)
            AppPage.FISHING.ordinal -> fishingDashboardView.setTelemetry(telemetry)
        }
        if (::elevationProfileView.isInitialized) elevationProfileView.setTelemetry(telemetry)
        if (::elevationStatsText.isInitialized) {
            elevationStatsText.text = getString(
                R.string.elevation_page_stats,
                telemetry.currentElevationMeters ?: 0.0,
                telemetry.gradePercent ?: 0.0,
                telemetry.remainingAscentMeters,
                telemetry.verticalSpeedMetersPerHour ?: 0.0,
            )
        }
        if (::exercisePauseButton.isInitialized) exercisePauseButton.setText(
            if (telemetry.exercisePaused) R.string.resume_activity else R.string.pause_activity,
        )
        if (telemetry.phase == SessionPhase.IDLE) return
        if (telemetry.courseName != null && telemetry.courseName != courseReloadName) {
            courseReloadName = telemetry.courseName
            loadStoredCourse()
        }
        statusText.text = when (telemetry.phase) {
            SessionPhase.LOADING_COURSE -> getString(R.string.loading_course)
            SessionPhase.ACQUIRING_LOCATION -> telemetry.message ?: getString(R.string.waiting_for_gps)
            SessionPhase.ERROR -> getString(R.string.navigation_error, telemetry.message ?: "Unknown error")
            SessionPhase.COMPLETED -> getString(R.string.course_complete)
            SessionPhase.NAVIGATING,
            SessionPhase.OFF_COURSE,
            -> if (exploring) {
                getString(R.string.free_exploration_active_points, telemetry.recordedPointCount)
            } else {
                getString(
                    R.string.live_navigation_compact,
                    telemetry.remainingMeters / 1_000,
                    telemetry.horizontalAccuracyMeters ?: 0.0,
                )
            }
            SessionPhase.IDLE -> getString(R.string.course_ready)
        }
        if (currentPage == AppPage.MAP.ordinal) {
            // White + DIFFERENCE produces the true inverse of every map pixel underneath it,
            // retaining contrast over pale vector maps and dark satellite tiles alike.
            statusText.setTextColor(Color.WHITE)
            statusText.paint.blendMode = BlendMode.DIFFERENCE
        } else {
            statusText.paint.blendMode = null
            statusText.setTextColor(
                if (telemetry.phase == SessionPhase.OFF_COURSE) Color.rgb(255, 92, 92) else Color.WHITE,
            )
        }
    }

    private fun buildClimbStatus(telemetry: NavigationTelemetry): String = when {
        telemetry.climbActive && telemetry.climbNumber != null -> getString(
            R.string.active_climb_status,
            telemetry.climbNumber,
            telemetry.climbRemainingDistanceMeters ?: 0.0,
            telemetry.climbRemainingAscentMeters ?: 0.0,
            telemetry.climbAverageGradePercent ?: 0.0,
        )
        telemetry.climbNumber != null -> getString(
            R.string.next_climb_status,
            telemetry.distanceToClimbMeters ?: 0.0,
            telemetry.remainingAscentMeters,
        )
        telemetry.totalAscentMeters > 0.0 -> getString(
            R.string.remaining_ascent_status,
            telemetry.remainingAscentMeters,
        )
        else -> ""
    }

    private fun buildOutdoorMetrics(telemetry: NavigationTelemetry): String {
        val elevation = telemetry.currentElevationMeters ?: return ""
        return getString(
            R.string.outdoor_metrics,
            elevation,
            telemetry.gradePercent ?: 0.0,
            telemetry.verticalSpeedMetersPerHour ?: 0.0,
        )
    }

    private fun buildHealthMetrics(telemetry: NavigationTelemetry): String {
        if (telemetry.heartRateBpm == null && telemetry.calories == null) return ""
        return getString(
            R.string.health_metrics,
            telemetry.heartRateBpm ?: 0.0,
            (telemetry.exerciseDistanceMeters ?: 0.0) / 1_000.0,
            telemetry.calories ?: 0.0,
            telemetry.lapCount,
        )
    }

    private fun reverseActiveCourse() {
        val course = activeCourse ?: run {
            statusText.setText(R.string.select_course_hint)
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    courseStore.saveCourse(course.reversed(getString(R.string.reversed_course_name, course.name)))
                }
            }.onSuccess { stored ->
                activateCourse(stored.course)
                statusText.setText(R.string.course_reversed)
            }.onFailure(::showImportError)
        }
    }

    private fun activateNextLibraryCourse() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val entries = courseStore.listLibrary()
                    require(entries.isNotEmpty()) { "Course library is empty" }
                    val currentIndex = entries.indexOfFirst { it.name == activeCourse?.name }
                    courseStore.activate(entries[(currentIndex + 1) % entries.size])
                }
            }.onSuccess { stored ->
                activateCourseWithAutomaticApproach(stored.course)
                statusText.setText(R.string.course_ready)
            }.onFailure(::showImportError)
        }
    }

    /**
     * Builds a real offline-road approach to the selected course. While it is calculating we show
     * only the selected course, never a misleading straight connector across unroutable terrain.
     */
    private fun activateCourseWithAutomaticApproach(selectedCourse: Course) {
        routeApproachJob?.cancel()
        val previousPosition = NavigationStateHub.current.position
        if (NavigationStateHub.current.phase != SessionPhase.IDLE) {
            NavigationSessionService.stop(this)
            NavigationStateHub.resetToIdle()
        }
        val origin = previousPosition
            ?: directLocationFix?.position
            ?: LastNavigationFixStore(this).load()?.position
        if (origin == null) {
            activateCourse(selectedCourse)
            return
        }
        val continuation = selectedCourse.continuationFromNearestPoint(origin)
        val destination = continuation.joinPosition
        val forwardCourse = continuation.course
        if (continuation.distanceMeters <= APPROACH_COMPLETE_DISTANCE_METERS) {
            activateCourse(forwardCourse)
            lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { courseStore.saveCourse(forwardCourse) } }
            }
            return
        }
        activateCourse(forwardCourse)
        statusText.setText(R.string.calculating_offline_route)
        routeApproachJob = lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { courseStore.saveCourse(forwardCourse) } }
            val result = calculateRoadApproach(origin, destination)
            val finalCourse = when (result) {
                is RouteResult.Success -> combineApproachWithCourse(result.course, forwardCourse)
                else -> forwardCourse
            }
            runCatching { withContext(Dispatchers.IO) { courseStore.saveCourse(finalCourse) } }
                .onSuccess { stored ->
                    val navigationAlreadyStarted = NavigationStateHub.current.phase != SessionPhase.IDLE
                    activateCourse(stored.course)
                    if (navigationAlreadyStarted) {
                        // The user may press Navigate while the offline approach is still being
                        // calculated. Reload the engine when the final path replaces the preview;
                        // otherwise it keeps reporting the previous course's remaining distance.
                        NavigationSessionService.start(
                            this@MainActivity,
                            ActivityMode.HIKE,
                            selectedPowerMode(),
                        )
                    } else if (result is RouteResult.Success) {
                        statusText.setText(R.string.course_ready)
                    } else {
                        val reason = when (result) {
                            is RouteResult.Failure -> result.reason
                            is RouteResult.MissingRegion -> result.regionIds.joinToString()
                            else -> "No road route"
                        }
                        statusText.text = getString(R.string.offline_route_failed, reason)
                    }
                }
                .onFailure(::showImportError)
        }
    }

    private suspend fun calculateRoadApproach(origin: GeoPoint, destination: GeoPoint): RouteResult {
        val baseline = routeWithAutomaticRegionDownload(
            RouteRequest(origin, destination, profile = RoutingProfile.RUN),
        )
        if (baseline !is RouteResult.Success ||
            !ApproachRoutePolicy.shouldTryCorridor(origin, destination, baseline.course)
        ) return baseline

        // The graph may snap a position inside a gated estate to a nearby but inaccessible road.
        // In that case start the graph search at a short, target-facing pedestrian egress point.
        val gate = ApproachRoutePolicy.egressGate(origin, destination)
        val fromGate = routeWithAutomaticRegionDownload(
            RouteRequest(
                origin = gate,
                destination = destination,
                profile = RoutingProfile.RUN,
            ),
        )
        if (fromGate !is RouteResult.Success ||
            !ApproachRoutePolicy.gateSnapIsUsable(gate, fromGate.course)
        ) return baseline
        val egressRoute = ApproachRoutePolicy.prependEgress(origin, gate, fromGate.course)
        return if (ApproachRoutePolicy.preferCorridor(origin, destination, baseline.course, egressRoute)) {
            RouteResult.Success(
                course = egressRoute,
                calculationMillis = baseline.calculationMillis + fromGate.calculationMillis,
            )
        } else {
            baseline
        }
    }

    private fun combineApproachWithCourse(approach: Course, selectedCourse: Course): Course {
        val prefix = approach.points.dropLast(1)
        val shift = prefix.size
        return selectedCourse.copy(
            points = prefix + selectedCourse.points,
            breakBeforePointIndices = approach.breakBeforePointIndices
                .filterTo(linkedSetOf()) { it < approach.points.lastIndex }
                .plus(selectedCourse.breakBeforePointIndices.map { it + shift }),
        )
    }

    private suspend fun routeWithAutomaticRegionDownload(request: RouteRequest): RouteResult =
        withContext(Dispatchers.IO) {
            var result = offlineRouter.route(request)
            repeat(MAXIMUM_AUTOMATIC_ROUTING_DOWNLOADS) {
                val missingRegions = when (val current = result) {
                    is RouteResult.MissingRegion -> current.regionIds
                    is RouteResult.Failure -> missingRegionIdsFromFailure(current.reason)
                    is RouteResult.Success -> return@withContext current
                }
                if (missingRegions.isEmpty()) return@withContext result
                result = runCatching {
                    RoutingRegionDownloader(this@MainActivity).install(missingRegions) { progress ->
                        runOnUiThread { renderRoutingDownloadProgress(progress) }
                    }
                    offlineRouter.route(request)
                }.getOrElse { error ->
                    return@withContext RouteResult.Failure(
                        reason = "Routing data download failed: ${error.message ?: error.javaClass.simpleName}",
                        recoverable = true,
                    )
                }
            }
            result
        }

    private fun missingRegionIdsFromFailure(reason: String): Set<String> =
        ROUTING_REGION_IN_ERROR.findAll(reason).mapTo(linkedSetOf()) { match -> match.groupValues[1] }

    private fun renderRoutingDownloadProgress(progress: RoutingRegionDownloadProgress) {
        statusText.text = getString(
            R.string.routing_region_downloading,
            progress.regionIndex,
            progress.regionCount,
            progress.regionId,
            progress.downloadedBytes / MEGABYTE,
            progress.totalBytes / MEGABYTE,
        )
    }

    private fun buildFishingPage(): View {
        val dashboard = FishingDashboardView(this).also { view ->
            fishingDashboardView = view
            view.contentDescription = getString(R.string.page_fishing)
            view.onVoiceLog = ::beginFishingVoiceLog
            view.onQuickPin = ::saveFishingQuickPin
            view.onWaterLock = { view.showNotice(getString(R.string.touch_enabled_hint)) }
            view.onMob = ::activateMobMode
            view.onReturnNavigation = { showPage(AppPage.NAVIGATION.ordinal) }
            view.onManualCast = ::recordFishingCast
            view.onCancelMob = {
                view.setMobActive(false)
                view.showNotice(getString(R.string.fishing_mob_cancelled))
            }
            val samples = fishingStore.pressureSamples()
            samples.lastOrNull()?.let { sample -> view.setPressure(sample.pressureHpa, samples) }
            view.setWaterProfile(fishingStore.waterTemperatureCelsius(), fishingStore.isSaltWater())
            view.setCastStats(fishingStore.castStats())
            tideRepository.cached()?.let(view::setTideForecast)
            if (tideRepository.apiKey().isBlank()) view.setTideMessage(getString(R.string.fishing_tide_needs_key))
            fishingPosition?.let {
                view.setPosition(it)
                refreshTideForecast(it)
            }
        }
        return FrameLayout(this).apply {
            addView(dashboard, FrameLayout.LayoutParams(-1, -1))
        }
    }

    /** Adds useful race/navigation marks even when a GPX contains only anonymous track points. */
    private fun addImportantLandmarks(course: Course): Course {
        val prepared = PreparedCourse.prepare(course)
        val landmarks = ArrayList<CoursePoint>()
        landmarks += course.landmarks
        landmarks += course.points.filter { !it.name.isNullOrBlank() || !it.type.isNullOrBlank() }

        fun add(point: CoursePoint) {
            if (landmarks.none { it.name == point.name && it.type == point.type }) landmarks += point
        }

        add(course.points.first().copy(name = getString(R.string.landmark_start), type = "start"))

        var milestoneMeters = 5_000.0
        while (milestoneMeters < prepared.totalDistanceMeters - 1_000.0) {
            val index = prepared.cumulativeDistanceMeters.indexOfFirst { it >= milestoneMeters }
            if (index >= 0) {
                add(
                    course.points[index].copy(
                        name = getString(R.string.landmark_distance, milestoneMeters / 1_000.0),
                        type = "distance-${milestoneMeters.toInt()}",
                    ),
                )
            }
            milestoneMeters += 5_000.0
        }

        val elevated = course.points.withIndex().filter { it.value.position.elevationMeters != null }
        if (elevated.size >= 2) {
            val highest = elevated.maxBy { it.value.position.elevationMeters!! }
            val lowest = elevated.minBy { it.value.position.elevationMeters!! }
            val relief = highest.value.position.elevationMeters!! - lowest.value.position.elevationMeters!!
            val distance = prepared.cumulativeDistanceMeters[highest.index]
            if (relief >= 40.0 && distance in 250.0..(prepared.totalDistanceMeters - 250.0)) {
                add(
                    highest.value.copy(
                        name = getString(
                            R.string.landmark_highest,
                            highest.value.position.elevationMeters,
                        ),
                        type = "highest-${highest.index}",
                    ),
                )
            }
        }

        add(course.points.last().copy(name = getString(R.string.landmark_finish), type = "finish"))
        return course.copy(landmarks = landmarks)
    }

    private fun saveCurrentLocation() {
        val point = NavigationStateHub.current.position ?: run {
            statusText.setText(R.string.routing_needs_location)
            return
        }
        lifecycleScope.launch {
            val waypoint = withContext(Dispatchers.IO) {
                waypointStore.save("Waypoint ${System.currentTimeMillis()}", point)
            }
            statusText.text = getString(R.string.location_saved, waypoint.name)
        }
    }

    private fun showAddRouteNoteOverlay() {
        val position = NavigationStateHub.current.position ?: run {
            statusText.setText(R.string.routing_needs_location)
            closeOverlay()
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(34), dp(52), dp(34), dp(42))
        }
        content.addView(overlayTitle(R.string.add_route_note), matchWrap())
        val input = EditText(this).apply {
            setHint(R.string.route_note_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        content.addView(input, matchWrap())
        fun save() {
            val name = input.text.toString().trim()
            if (name.isBlank()) return
            val course = activeCourse ?: return
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        courseStore.saveCourse(
                            course.copy(
                                landmarks = course.landmarks + CoursePoint(position, name = name, type = "note"),
                            ),
                        )
                    }
                }.onSuccess { stored ->
                    activateCourse(stored.course)
                    closeOverlay()
                    statusText.text = getString(R.string.route_note_saved, name)
                }.onFailure(::showImportError)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        content.addView(actionButton(R.string.save) { save() }, wrapWrap())
        content.addView(actionButton(R.string.close) { closeOverlay() }, wrapWrap())
        showOverlay(content)
    }

    private fun searchDestination(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) return
        statusText.setText(R.string.searching_destination)
        lifecycleScope.launch {
            val destination = withContext(Dispatchers.IO) {
                parseCoordinates(query) ?: runCatching {
                    if (!Geocoder.isPresent()) return@runCatching null
                    @Suppress("DEPRECATION")
                    Geocoder(this@MainActivity, Locale.getDefault())
                        .getFromLocationName(query, 1)
                        ?.firstOrNull()
                        ?.let { GeoPoint(it.latitude, it.longitude) }
                }.getOrNull()
            }
            if (destination == null) {
                statusText.text = getString(R.string.destination_not_found, query)
                return@launch
            }
            closeOverlay()
            calculateRouteTo(destination, query)
            showPage(AppPage.MAP.ordinal)
        }
    }

    private fun parseCoordinates(text: String): GeoPoint? {
        val match = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[,， ]\\s*(-?\\d+(?:\\.\\d+)?)\\s*$")
            .matchEntire(text) ?: return null
        return runCatching {
            GeoPoint(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
        }.getOrNull()
    }

    private fun routeToLatestWaypoint() {
        lifecycleScope.launch {
            val waypoint = withContext(Dispatchers.IO) { waypointStore.list().firstOrNull() }
            if (waypoint == null) {
                statusText.setText(R.string.no_saved_location)
                return@launch
            }
            calculateRouteTo(waypoint.point, waypoint.name)
        }
    }

    private fun showFavoriteTargetsOverlay() {
        val targets = favoriteTargetStore.list()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(34), dp(30), dp(40))
        }
        content.addView(overlayTitle(R.string.favorite_targets), matchWrap())
        if (targets.isEmpty()) {
            content.addView(TextView(this).apply {
                setText(R.string.no_favorite_targets)
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(6), dp(12), dp(6), dp(12))
            }, matchWrap())
        } else targets.sortedBy { it.slot }.forEach { target ->
            val prefix = if (target.slot == 0) getString(R.string.home_target)
            else getString(R.string.common_target, target.slot)
            content.addView(actionButtonText("$prefix · ${target.name}") {
                closeOverlay()
                lifecycleScope.launch { calculateRouteTo(target.point, target.name) }
            }, wrapWrap())
        }
        content.addView(actionButton(R.string.close) { closeOverlay() }, wrapWrap())
        showOverlay(content)
    }

    private suspend fun calculateRouteTo(destination: com.watchnav.core.GeoPoint, name: String) {
        val origin = NavigationStateHub.current.position ?: run {
            statusText.setText(R.string.routing_needs_location)
            return
        }
        statusText.setText(R.string.calculating_offline_route)
        when (val result = routeWithAutomaticRegionDownload(
            RouteRequest(origin, destination, profile = RoutingProfile.HIKE),
        )) {
            is RouteResult.Success -> {
                val namedCourse = result.course.copy(name = name)
                val stored = withContext(Dispatchers.IO) { courseStore.saveCourse(namedCourse) }
                activateCourse(stored.course)
                statusText.text = getString(
                    R.string.offline_route_ready,
                    namedCourse.points.size,
                    result.calculationMillis,
                )
                showPage(AppPage.NAVIGATION.ordinal)
                ensurePermissionAndStart()
            }
            is RouteResult.MissingRegion -> statusText.text = getString(
                R.string.routing_region_missing,
                result.regionIds.joinToString(),
            )
            is RouteResult.Failure -> statusText.text = getString(
                R.string.offline_route_failed,
                result.reason,
            )
        }
    }

    private fun showActivityHistory() {
        lifecycleScope.launch {
            val activities = withContext(Dispatchers.IO) { ActivityArchive(this@MainActivity).list() }
            val content = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(32), dp(45), dp(32), dp(40))
            }
            content.addView(overlayTitle(R.string.activity_history), matchWrap())
            if (activities.isEmpty()) {
                content.addView(TextView(this@MainActivity).apply {
                    setText(R.string.no_activity_history)
                    setTextColor(Color.LTGRAY)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(16), dp(6), dp(16))
                }, matchWrap())
            } else activities.forEach { activity ->
                content.addView(TextView(this@MainActivity).apply {
                    text = getString(
                        R.string.activity_history_line,
                        activity.id,
                        activity.distanceMeters / 1_000.0,
                        activity.ascentMeters,
                    )
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                }, matchWrap())
            }
            content.addView(actionButton(R.string.close) { closeOverlay() }, wrapWrap())
            showOverlay(ScrollView(this@MainActivity).apply { isFillViewport = true; addView(content, matchWrap()) })
        }
    }

    private fun showOfflineMap(stack: OfflineMapStack) {
        if (stack.isEmpty) return
        val radiusLimit = if (configuredMapKind() == TransferKind.CONTOUR_MAP) {
            LIMITED_LAYER_RADIUS_METERS
        } else null
        if (offlineMapStackIdentity == stack.cacheIdentity && offlineMapView != null) {
            offlineMapView?.setDisplayRadiusLimitMeters(radiusLimit)
            activeCourse?.let(offlineMapView!!::setCourse)
            offlineMapView?.setTelemetry(currentMapTelemetry())
            attachMapToCurrentPage()
            return
        }
        val previousViewport: MapViewport? = offlineMapView?.viewport() ?: satelliteMapView?.viewport()
        val newMapView = runCatching {
            OfflineMapsforgeView(this, stack.files, stack.cacheIdentity)
        }
            .getOrElse { error ->
                statusText.text = getString(
                    R.string.map_import_failed,
                    error.message ?: error.javaClass.simpleName,
                )
                return
            }
        offlineMapView?.destroy()
        satelliteMapView?.close()
        satelliteMapView = null
        satelliteMapPath = null
        offlineMapView = newMapView
        offlineMapStackIdentity = stack.cacheIdentity
        newMapView.setDisplayRadiusLimitMeters(radiusLimit)
        activeCourse?.let(newMapView::setCourse)
        newMapView.setTelemetry(currentMapTelemetry())
        previousViewport?.let { viewport -> newMapView.post { newMapView.restoreViewport(viewport) } }
        displayedHeadingDegrees?.let(newMapView::setHeadingDegrees)
        attachMapToCurrentPage()
    }

    private fun showSatelliteMap(mapFile: java.io.File) {
        if (satelliteMapPath == mapFile.absolutePath && satelliteMapView != null) {
            activeCourse?.let(satelliteMapView!!::setCourse)
            satelliteMapView?.setTelemetry(currentMapTelemetry())
            attachMapToCurrentPage()
            return
        }
        val previousViewport: MapViewport? = satelliteMapView?.viewport() ?: offlineMapView?.viewport()
        val view = runCatching { WatchMbtilesMapView(this, mapFile) }.getOrElse { error ->
            statusText.text = getString(R.string.map_import_failed, error.message ?: error.javaClass.simpleName)
            return
        }
        offlineMapView?.destroy()
        offlineMapView = null
        offlineMapStackIdentity = null
        satelliteMapView?.close()
        satelliteMapView = view
        satelliteMapPath = mapFile.absolutePath
        view.setDisplayRadiusLimitMeters(LIMITED_LAYER_RADIUS_METERS)
        activeCourse?.let(view::setCourse)
        view.setTelemetry(currentMapTelemetry())
        previousViewport?.let(view::restoreViewport)
        displayedHeadingDegrees?.let(view::setHeadingDegrees)
        attachMapToCurrentPage()
    }

    private fun currentMapTelemetry(): NavigationTelemetry {
        val live = NavigationStateHub.current
        if (live.position != null) return live
        val fix = directLocationFix ?: LastNavigationFixStore(this).load() ?: return live
        return live.copy(
            position = fix.position,
            horizontalAccuracyMeters = fix.accuracyMeters,
            speedMetersPerSecond = fix.speedMetersPerSecond,
            bearingDegrees = fix.bearingDegrees,
            currentElevationMeters = fix.position.elevationMeters,
            updatedAtMillis = fix.timestampMillis,
        )
    }

    private fun showConfiguredMapLayer() {
        val kind = configuredMapKind()
        if (kind == TransferKind.HISTORICAL_RASTER) {
            val satellite = availableSatelliteFile()
            if (satellite != null) {
                showSatelliteMap(satellite)
                return
            }
        }
        var stack = mapRepository.activeStack()
        if (stack.isEmpty) {
            val layers = mapRepository.layers()
            val fallbackBase = layers.filter { it.kind == VectorMapLayerKind.BASE }
                .maxByOrNull { it.importedAtMillis }
            val fallbackContour = layers.filter { it.kind == VectorMapLayerKind.CONTOUR }
                .maxByOrNull { it.importedAtMillis }
            when {
                fallbackBase != null -> {
                    mapRepository.select(VectorMapLayerKind.BASE, fallbackBase.id)
                    mapRepository.setContourEnabled(kind == TransferKind.CONTOUR_MAP && fallbackContour != null)
                }
                fallbackContour != null -> {
                    mapRepository.select(VectorMapLayerKind.CONTOUR, fallbackContour.id)
                    mapRepository.setContourEnabled(true)
                }
            }
            stack = mapRepository.activeStack()
        }
        if (!stack.isEmpty) {
            showOfflineMap(stack)
        } else {
            // Keep the route renderer in the map container even when no offline package exists.
            // This also repairs the old state where switching away detached every map view.
            restoreRouteOnlyMap()
            if (!mapRepairAttempted) {
                mapRepairAttempted = true
                watchSyncCoordinator.reconcileMapsThen { runOnUiThread(::showConfiguredMapLayer) }
            }
        }
    }

    private fun configuredMapKind(): TransferKind {
        val preferences = getSharedPreferences(MAP_PREFERENCES, MODE_PRIVATE)
        preferences.getString(KEY_ACTIVE_MAP_KIND, null)
            ?.let { runCatching { TransferKind.valueOf(it) }.getOrNull() }
            ?.takeIf {
                it == TransferKind.MAPSFORGE ||
                    it == TransferKind.CONTOUR_MAP ||
                    it == TransferKind.HISTORICAL_RASTER
            }
            ?.let { return it }
        val inferred = if (mapRepository.isContourEnabled()) TransferKind.CONTOUR_MAP else TransferKind.MAPSFORGE
        preferences.edit().putString(KEY_ACTIVE_MAP_KIND, inferred.name).apply()
        return inferred
    }

    private fun cycleWatchMapLayer() {
        val order = listOf(TransferKind.MAPSFORGE, TransferKind.CONTOUR_MAP, TransferKind.HISTORICAL_RASTER)
        val preferences = getSharedPreferences(MAP_PREFERENCES, MODE_PRIVATE)
        val current = configuredMapKind()
        val next = order[(order.indexOf(current).coerceAtLeast(0) + 1) % order.size]
        val vectorLayer = when (next) {
            TransferKind.MAPSFORGE -> mapRepository.selected(VectorMapLayerKind.BASE)
                ?: mapRepository.layers().filter { it.kind == VectorMapLayerKind.BASE }
                    .maxByOrNull { it.importedAtMillis }
            TransferKind.CONTOUR_MAP -> mapRepository.selected(VectorMapLayerKind.CONTOUR)
                ?: mapRepository.layers().filter { it.kind == VectorMapLayerKind.CONTOUR }
                    .maxByOrNull { it.importedAtMillis }
            else -> null
        }
        val satelliteFile = if (next == TransferKind.HISTORICAL_RASTER) {
            availableSatelliteFile()
        } else null
        if (next != TransferKind.HISTORICAL_RASTER && vectorLayer == null ||
            next == TransferKind.HISTORICAL_RASTER && satelliteFile == null
        ) {
            val missingLayer = when (next) {
                TransferKind.MAPSFORGE -> getString(R.string.map_layer_normal)
                TransferKind.CONTOUR_MAP -> getString(R.string.map_layer_contour)
                else -> getString(R.string.map_layer_satellite)
            }
            // Remember the attempted slot even when its package is absent. Otherwise a single
            // cycle button gets permanently stuck on the same missing layer and the user cannot
            // reach the following installed map type.
            preferences.edit().putString(KEY_ACTIVE_MAP_KIND, next.name).apply()
            statusText.text = getString(R.string.map_layer_missing_phone, missingLayer)
            if (next == TransferKind.MAPSFORGE) startActivity(Intent(this, MapDownloadActivity::class.java))
            else if (next == TransferKind.CONTOUR_MAP) startActivity(MapDownloadActivity.intent(this, MapLayerKind.CONTOUR))
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (next) {
                        TransferKind.MAPSFORGE -> {
                            val base = mapRepository.selected(VectorMapLayerKind.BASE)
                                ?: mapRepository.layers().filter { it.kind == VectorMapLayerKind.BASE }
                                    .maxByOrNull { it.importedAtMillis }
                            mapRepository.select(VectorMapLayerKind.BASE, requireNotNull(base).id)
                            mapRepository.setContourEnabled(false)
                        }
                        TransferKind.CONTOUR_MAP -> {
                            val contour = mapRepository.selected(VectorMapLayerKind.CONTOUR)
                                ?: mapRepository.layers().filter { it.kind == VectorMapLayerKind.CONTOUR }
                                    .maxByOrNull { it.importedAtMillis }
                            mapRepository.select(VectorMapLayerKind.CONTOUR, requireNotNull(contour).id)
                            mapRepository.setContourEnabled(true)
                        }
                        else -> Unit
                    }
                }
            }.onSuccess {
                preferences.edit().putString(KEY_ACTIVE_MAP_KIND, next.name).apply()
                if (next == TransferKind.HISTORICAL_RASTER) showSatelliteMap(requireNotNull(satelliteFile))
                else showOfflineMap(mapRepository.activeStack())
                statusText.text = when (next) {
                    TransferKind.MAPSFORGE -> getString(R.string.map_layer_normal)
                    TransferKind.CONTOUR_MAP -> getString(R.string.map_layer_contour)
                    else -> getString(R.string.map_layer_satellite)
                }
                NavigationStateHub.current.let(::renderTelemetry)
            }.onFailure { statusText.text = getString(R.string.map_import_failed, it.message.orEmpty()) }
        }
    }

    /**
     * Sync transfers are normally indexed in the artifact registry. Also discover an intact
     * MBTiles file in the receive directory so an interrupted index update never leaves a fully
     * transferred satellite map invisible to the layer switcher.
     */
    private fun availableSatelliteFile(): java.io.File? =
        TransferredArtifactRegistry(this).entries()
            .asSequence()
            .filter { it.kind == TransferKind.HISTORICAL_RASTER }
            .sortedByDescending { it.updatedAtMillis }
            .map { java.io.File(it.filePath) }
            .firstOrNull { it.isFile && it.extension.equals("mbtiles", true) }
            ?: java.io.File(filesDir, "sync/received/historical_raster")
                .listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension.equals("mbtiles", true) }
                ?.maxByOrNull(java.io.File::lastModified)

    private fun beginFishingVoiceLog() {
        if (fishingAudioRecorder.isRecording) {
            stopFishingAudioRecording()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startFishingAudioRecording()
        } else {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startFishingAudioRecording() {
        val started = runCatching { fishingAudioRecorder.start() }
        if (started.isFailure) {
            fishingDashboardView.showNotice(getString(R.string.fishing_voice_unavailable))
            return
        }
        fishingDashboardView.setListening(true)
        fishingDashboardView.showNotice(getString(R.string.fishing_audio_recording))
        fishingAudioAutoStopJob?.cancel()
        fishingAudioAutoStopJob = lifecycleScope.launch {
            delay(MAX_FISHING_AUDIO_MILLIS)
            stopFishingAudioRecording()
        }
    }

    private fun recordFishingCast() {
        val stats = fishingStore.recordCast()
        if (::fishingDashboardView.isInitialized) {
            fishingDashboardView.setCastStats(stats)
            if (stats.sinceLastMark == 30) {
                fishingDashboardView.showNotice(getString(R.string.fishing_metric_cast_since, stats.sinceLastMark))
            }
        }
    }

    private fun updateUiSensors() {
        if (!activityResumed) return
        sensorManager.unregisterListener(orientationListener)
        fishingCastSensorRegistered = false
        fishingCastDetector.reset()
        if (currentPage == AppPage.FISHING.ordinal) {
            sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sensor ->
                sensorManager.registerListener(
                    orientationListener,
                    sensor,
                    PRESSURE_SENSOR_INTERVAL_MICROS,
                    PRESSURE_SENSOR_BATCH_MICROS,
                )
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensor ->
                fishingCastSensorRegistered = sensorManager.registerListener(
                    orientationListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                )
            }
        } else if (NavigationStateHub.current.phase == SessionPhase.IDLE ||
            currentPage == AppPage.NAVIGATION.ordinal
        ) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
                sensorManager.registerListener(
                    orientationListener,
                    sensor,
                    ORIENTATION_SENSOR_INTERVAL_MICROS,
                    ORIENTATION_SENSOR_BATCH_MICROS,
                )
            }
        }
    }

    private fun stopFishingAudioRecording() {
        fishingAudioAutoStopJob?.cancel()
        fishingAudioAutoStopJob = null
        val recorded = fishingAudioRecorder.stop()
        fishingDashboardView.setListening(false)
        if (recorded == null) {
            fishingDashboardView.showNotice(getString(R.string.fishing_audio_too_short))
            return
        }
        val point = currentFishingPosition()
        val pressure = fishingStore.pressureSamples().lastOrNull()?.pressureHpa
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                fishingStore.saveAudioCatch(recorded.id, recorded.file.name, point, pressure).also { entry ->
                    point?.let { location ->
                        waypointStore.upsert(com.watchnav.session.SavedWaypoint(
                            entry.id,
                            getString(R.string.fishing_audio_mark_name),
                            location,
                            entry.createdAtMillis,
                        ))
                    }
                }
                fishingStore.markCastInterval()
            }
            fishingDashboardView.setCastStats(stats)
            fishingDashboardView.showNotice(getString(R.string.fishing_audio_saved, recorded.durationMillis / 1_000))
            watchSyncCoordinator.announceLightweight()
        }
    }

    private fun saveFishingQuickPin() {
        val point = currentFishingPosition()
        if (point == null) {
            pendingFishingQuickPin = true
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                updateFishingLocationUpdates()
                fishingDashboardView.showNotice(getString(R.string.fishing_waiting_gps))
            } else {
                fishingLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            return
        }
        val pressure = fishingStore.pressureSamples().lastOrNull()?.pressureHpa
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val entry = fishingStore.saveCatch(getString(R.string.fishing_quick_pin_entry), point, pressure)
                waypointStore.upsert(com.watchnav.session.SavedWaypoint(
                    entry.id,
                    getString(R.string.fishing_quick_pin_entry),
                    point,
                    entry.createdAtMillis,
                ))
                fishingStore.markCastInterval()
            }
            fishingDashboardView.setCastStats(stats)
            fishingDashboardView.showNotice(getString(R.string.fishing_quick_pin_saved))
            watchSyncCoordinator.announceLightweight()
        }
    }

    private fun activateMobMode() {
        fishingDashboardView.setMobActive(true)
        NavigationSessionService.tracBack(this)
        fishingDashboardView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun currentFishingPosition(): GeoPoint? = NavigationStateHub.current.position
        ?: fishingPosition
        ?: LastNavigationFixStore(this).load()?.position

    private fun refreshTideForecast(point: GeoPoint, force: Boolean = false) {
        fishingDashboardView.setPosition(point)
        tideRepository.cached()?.let(fishingDashboardView::setTideForecast)
        if (tideRepository.apiKey().isBlank()) {
            fishingDashboardView.setTideMessage(getString(R.string.fishing_tide_needs_key))
            return
        }
        if (!force && !tideRepository.needsRefresh(point)) return
        if (tideRefreshJob?.isActive == true) return
        fishingDashboardView.setTideMessage(getString(R.string.fishing_tide_loading))
        tideRefreshJob = lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { tideRepository.fetch(point) } }
                .onSuccess(fishingDashboardView::setTideForecast)
                .onFailure { fishingDashboardView.setTideMessage(getString(R.string.fishing_tide_failed)) }
        }
    }

    private fun triggerStormAlert(nowMillis: Long) {
        val preferences = getSharedPreferences(FISHING_ALERT_PREFERENCES, MODE_PRIVATE)
        if (nowMillis - preferences.getLong(KEY_LAST_STORM_ALERT, 0L) < STORM_ALERT_COOLDOWN_MILLIS) return
        preferences.edit().putLong(KEY_LAST_STORM_ALERT, nowMillis).apply()
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 200, 700, 200, 1_000), -1)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        vibrator?.vibrate(effect, attributes)
        fishingDashboardView.showNotice(getString(R.string.fishing_storm_warning))
    }

    private fun updateFishingLocationUpdates() {
        locationManager.removeUpdates(fishingLocationListener)
        if (!activityResumed) return
        if (directLocationFix == null) {
            LastNavigationFixStore(this).load()?.let { persisted ->
                directLocationFix = persisted
                fishingPosition = persisted.position
                renderDirectLocation(persisted)
                fishingDashboardView.setPosition(persisted.position)
            }
        }
        val navigationTelemetry = NavigationStateHub.current
        val navigationFixIsFresh = navigationTelemetry.updatedAtMillis > 0L &&
            System.currentTimeMillis() - navigationTelemetry.updatedAtMillis <= MAXIMUM_LIVE_LOCATION_AGE_MILLIS
        if (navigationTelemetry.phase != SessionPhase.IDLE && navigationFixIsFresh) {
            navigationTelemetry.position?.let {
                fishingPosition = it
                fishingDashboardView.setPosition(it)
                refreshTideForecast(it)
            }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            bestLastKnownLocation()?.let { location ->
                val point = GeoPoint(location.latitude, location.longitude, location.altitude.takeIf { location.hasAltitude() })
                directLocationFix = LocationFix(
                    position = point,
                    accuracyMeters = location.accuracy.toDouble().takeIf { location.hasAccuracy() } ?: 100.0,
                    timestampMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    bearingDegrees = location.bearing.toDouble().takeIf { location.hasBearing() },
                    speedMetersPerSecond = location.speed.toDouble().takeIf { location.hasSpeed() },
                )
                LastNavigationFixStore(this).save(requireNotNull(directLocationFix))
                fishingPosition = point
                renderDirectLocation(requireNotNull(directLocationFix))
                fishingDashboardView.setPosition(requireNotNull(fishingPosition))
                refreshTideForecast(requireNotNull(fishingPosition))
            }
            val fishingPage = currentPage == AppPage.FISHING.ordinal
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                if (fishingPage) FISHING_LOCATION_INTERVAL_MILLIS else IDLE_MAP_LOCATION_INTERVAL_MILLIS,
                if (fishingPage) FISHING_LOCATION_MIN_DISTANCE_METERS else IDLE_MAP_LOCATION_MIN_DISTANCE_METERS,
                fishingLocationListener,
            )
        }
    }

    private fun exitApplicationCompletely() {
        if (exitingApplication) return
        exitingApplication = true
        fishingAudioAutoStopJob?.cancel()
        tideRefreshJob?.cancel()
        routeApproachJob?.cancel()
        if (fishingAudioRecorder.isRecording) runCatching(::stopFishingAudioRecording)
        sensorManager.unregisterListener(orientationListener)
        locationManager.removeUpdates(fishingLocationListener)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Prevent a Wear data event from recreating the process after the user explicitly exits.
        // MainActivity re-enables this component on the next deliberate launch.
        setWearSyncListenerEnabled(false)
        NavigationSessionService.stop(this)
        WatchResidentService.stop(this)
        getSystemService(NotificationManager::class.java).cancelAll()
        finishAffinity()
        finishAndRemoveTask()

        val handler = Handler(Looper.getMainLooper())
        val deadline = SystemClock.uptimeMillis() + EXIT_ARCHIVE_TIMEOUT_MILLIS
        fun terminateWhenNavigationStops() {
            if (NavigationStateHub.current.phase == SessionPhase.IDLE || SystemClock.uptimeMillis() >= deadline) {
                // A stuck archive must not defeat an explicit full exit.
                stopService(Intent(this, NavigationSessionService::class.java))
                Process.killProcess(Process.myPid())
                return
            }
            handler.postDelayed(::terminateWhenNavigationStops, EXIT_POLL_INTERVAL_MILLIS)
        }
        handler.postDelayed(::terminateWhenNavigationStops, EXIT_INITIAL_DELAY_MILLIS)
    }

    private fun setWearSyncListenerEnabled(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, WatchWearSyncService::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun ensureMapLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine) {
            updateFishingLocationUpdates()
        } else if (!mapLocationPermissionRequested) {
            mapLocationPermissionRequested = true
            mapLocationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ))
        }
    }

    private fun renderDirectLocation(fix: LocationFix) {
        val telemetry = NavigationStateHub.current.copy(
            position = fix.position,
            horizontalAccuracyMeters = fix.accuracyMeters,
            speedMetersPerSecond = fix.speedMetersPerSecond,
            bearingDegrees = fix.bearingDegrees,
            currentElevationMeters = fix.position.elevationMeters,
            updatedAtMillis = fix.timestampMillis,
        )
        renderTelemetry(telemetry)
    }

    @Suppress("MissingPermission")
    private fun bestLastKnownLocation() = runCatching {
        locationManager.allProviders.mapNotNull(locationManager::getLastKnownLocation)
            .maxByOrNull { it.time }
    }.getOrNull()

    private fun attachMapToCurrentPage() {
        val view: View = satelliteMapView ?: offlineMapView ?: return
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        // The navigation page intentionally uses only the local route corridor renderer.
        if (currentPage != AppPage.MAP.ordinal) return
        val target = mapLayerContainer
        target.removeAllViews()
        target.addView(view, FrameLayout.LayoutParams(-1, -1))
        offlineMapView?.setNavigationMode(false)
        satelliteMapView?.setNavigationMode(false)
    }

    private fun restoreRouteOnlyMap() {
        if (currentPage != AppPage.MAP.ordinal) return
        (courseMapView.parent as? android.view.ViewGroup)?.removeView(courseMapView)
        mapLayerContainer.removeAllViews()
        mapLayerContainer.addView(courseMapView, FrameLayout.LayoutParams(-1, -1))
    }

    private fun shortestAngleDegrees(from: Double, to: Double): Double =
        ((to - from + 540.0) % 360.0) - 180.0

    private fun startNavigationFromMap() {
        showPage(AppPage.NAVIGATION.ordinal)
        if (NavigationStateHub.current.phase == SessionPhase.IDLE) ensurePermissionAndStart()
    }

    private fun stopNavigationFromDashboard() {
        NavigationSessionService.stop(this)
        statusText.setText(R.string.navigation_stopped)
        showPage(AppPage.MAP.ordinal)
        showStopSummaryOverlay()
    }

    private fun showStopSummaryOverlay() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(54), dp(36), dp(44))
        }
        content.addView(overlayTitle(R.string.activity_saved_automatically), matchWrap())
        content.addView(TextView(this).apply {
            setText(R.string.activity_saved_detail)
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(14))
        }, matchWrap())
        content.addView(actionButton(R.string.return_to_map) { closeOverlay() }, wrapWrap())
        showOverlay(content)
    }

    private fun ensurePermissionAndStart() {
        if (activeCourse == null) {
            statusText.setText(R.string.select_course_hint)
            return
        }
        val requiredPermissions = requiredRuntimePermissions()
        if (requiredPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            startSession()
        } else {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    private fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startSession() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            NavigationSessionService.start(this, ActivityMode.HIKE, selectedPowerMode())
        }.onSuccess {
            statusText.setText(R.string.waiting_for_gps)
        }.onFailure { error ->
            statusText.text = getString(R.string.navigation_error, error.message ?: error.javaClass.simpleName)
            showPage(AppPage.MAP.ordinal)
        }
    }

    private fun toggleAlwaysOn() {
        val preferences = getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE)
        val enabled = !preferences.getBoolean(KEY_ALWAYS_ON, false)
        preferences.edit { putBoolean(KEY_ALWAYS_ON, enabled) }
        applyAlwaysOn(enabled)
    }

    private fun applyAlwaysOnPreference() {
        val preferences = getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_BATTERY_MIGRATION_V2, false)) {
            preferences.edit {
                putBoolean(KEY_ALWAYS_ON, false)
                if (preferences.getString(KEY_POWER_MODE, null) == NavigationPowerMode.ACCURATE.name) {
                    putString(KEY_POWER_MODE, NavigationPowerMode.BALANCED.name)
                }
                putBoolean(KEY_BATTERY_MIGRATION_V2, true)
            }
        }
        applyAlwaysOn(preferences.getBoolean(KEY_ALWAYS_ON, false))
        renderPowerMode()
    }

    private fun selectedPowerMode(): NavigationPowerMode {
        val stored = getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE)
            .getString(KEY_POWER_MODE, NavigationPowerMode.BALANCED.name)
        return runCatching { NavigationPowerMode.valueOf(requireNotNull(stored)) }
            .getOrDefault(NavigationPowerMode.BALANCED)
    }

    private fun cyclePowerMode() {
        val modes = NavigationPowerMode.entries
        val next = modes[(modes.indexOf(selectedPowerMode()) + 1) % modes.size]
        getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE).edit {
            putString(KEY_POWER_MODE, next.name)
        }
        renderPowerMode()
    }

    private fun renderPowerMode() {
        powerModeButton.text = getString(
            R.string.power_mode,
            when (selectedPowerMode()) {
                NavigationPowerMode.ACCURATE -> getString(R.string.power_accurate)
                NavigationPowerMode.BALANCED -> getString(R.string.power_balanced)
                NavigationPowerMode.EXPEDITION -> getString(R.string.power_expedition)
            },
        )
    }

    private fun applyAlwaysOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        alwaysOnButton.setText(if (enabled) R.string.always_on_enabled else R.string.always_on_disabled)
    }

    private fun showImportError(error: Throwable) {
        statusText.text = getString(
            R.string.course_import_failed,
            error.message ?: error.javaClass.simpleName,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundSafeButtonWidth(): Int =
        (min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) - dp(88))
            .coerceAtLeast(dp(180))

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun wrapWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val MEGABYTE = 1024.0 * 1024.0
        const val DISPLAY_PREFERENCES = "display_preferences"
        const val KEY_ALWAYS_ON = "always_on"
        const val KEY_POWER_MODE = "power_mode"
        const val KEY_BATTERY_MIGRATION_V2 = "battery_migration_v2"
        const val MAP_PREFERENCES = "map_installation"
        const val KEY_ACTIVE_MAP_KIND = "active_map_kind"
        const val DOWNLOAD_PREFERENCES = "downloads"
        const val KEY_LAST_GPX_URL = "last_gpx_url"
        const val COURSE_IMPORT_PREFERENCES = "course_imports"
        const val KEY_HANGZHOU_2025_IMPORTED = "hangzhou_2025_gpx_kml"
        const val BUNDLED_COURSE_DIRECTORY = "seed-courses"
        const val MAXIMUM_AUTOMATIC_ROUTING_DOWNLOADS = 4
        const val APPROACH_COMPLETE_DISTANCE_METERS = 8.0
        const val ORIENTATION_UPDATE_INTERVAL_NANOS = 500_000_000L
        const val ORIENTATION_SENSOR_INTERVAL_MICROS = 500_000
        const val ORIENTATION_SENSOR_BATCH_MICROS = 1_000_000
        const val PRESSURE_SENSOR_INTERVAL_MICROS = 30_000_000
        const val PRESSURE_SENSOR_BATCH_MICROS = 60_000_000
        const val FISHING_LOCATION_INTERVAL_MILLIS = 8_000L
        const val FISHING_LOCATION_MIN_DISTANCE_METERS = 5f
        const val IDLE_MAP_LOCATION_INTERVAL_MILLIS = 30_000L
        const val IDLE_MAP_LOCATION_MIN_DISTANCE_METERS = 10f
        const val MAXIMUM_LIVE_LOCATION_AGE_MILLIS = 2 * 60_000L
        const val LIMITED_LAYER_RADIUS_METERS = 15_000.0
        const val MAX_FISHING_AUDIO_MILLIS = 30_000L
        const val FISHING_ALERT_PREFERENCES = "fishing_alerts"
        const val KEY_LAST_STORM_ALERT = "last_storm_alert"
        const val STORM_ALERT_COOLDOWN_MILLIS = 3 * 60 * 60 * 1_000L
        const val EXIT_INITIAL_DELAY_MILLIS = 350L
        const val EXIT_POLL_INTERVAL_MILLIS = 100L
        const val EXIT_ARCHIVE_TIMEOUT_MILLIS = 2_500L
        val MAP_ICON_COLOR: Int = Color.rgb(196, 96, 255)
        val ROUTING_REGION_IN_ERROR = Regex("([EW]\\d{1,3}_[NS]\\d{1,2})\\.rd5", RegexOption.IGNORE_CASE)
        const val DEFAULT_GPX_URL =
            "https://gist.githubusercontent.com/cly/bab1a4f982d43bcc53ff32d4708b8a77/raw/sample.gpx"
    }

    private enum class AppPage(val labelRes: Int) {
        MAP(R.string.page_map),
        NAVIGATION(R.string.page_navigation),
        FISHING(R.string.page_fishing),
    }

}
