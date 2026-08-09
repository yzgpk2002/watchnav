package com.watchnav.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.watchnav.core.ActivityMode
import com.watchnav.core.Course
import com.watchnav.core.PreparedCourse
import com.watchnav.core.reversed
import com.watchnav.brouter.BRouterOfflineRouter
import com.watchnav.mapsforge.MapsforgeMapRepository
import com.watchnav.mapsforge.OfflineMapsforgeView
import com.watchnav.session.CourseFileStore
import com.watchnav.session.ActivityArchive
import com.watchnav.session.NavigationSessionService
import com.watchnav.session.NavigationPowerMode
import com.watchnav.session.NavigationStateHub
import com.watchnav.session.NavigationTelemetry
import com.watchnav.session.SessionPhase
import com.watchnav.session.TrackStore
import com.watchnav.session.WaypointStore
import com.watchnav.routing.RouteRequest
import com.watchnav.routing.RouteResult
import com.watchnav.routing.RoutingProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var touchLocked = false
    private var activeCourse: Course? = null
    private lateinit var statusText: TextView
    private lateinit var courseText: TextView
    private lateinit var lockText: TextView
    private lateinit var gpxUrlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var alwaysOnButton: Button
    private lateinit var followMapButton: Button
    private lateinit var exercisePauseButton: Button
    private lateinit var powerModeButton: Button
    private lateinit var courseMapView: CourseMapView
    private lateinit var elevationProfileView: ElevationProfileView
    private lateinit var mapContainer: FrameLayout
    private var offlineMapView: OfflineMapsforgeView? = null
    private val courseStore by lazy { CourseFileStore(this) }
    private val mapRepository by lazy { MapsforgeMapRepository(this) }
    private val offlineRouter by lazy { BRouterOfflineRouter(this) }
    private val waypointStore by lazy { WaypointStore(this) }
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
                            courseStore.importKml(input, fileName.substringBeforeLast('.'))
                        } else {
                            courseStore.import(input, fileName.substringBeforeLast('.'))
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
                showOfflineMap(mapFile)
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
        setContentView(buildContent())
        applyAlwaysOnPreference()
        importBundledCoursesThenLoad()
    }

    override fun onResume() {
        super.onResume()
        mapRepository.activeMap()?.let(::showOfflineMap)
    }

    override fun onStart() {
        super.onStart()
        NavigationStateHub.addListener(telemetryListener)
    }

    override fun onStop() {
        NavigationStateHub.removeListener(telemetryListener)
        super.onStop()
    }

    override fun onDestroy() {
        offlineMapView?.destroy()
        offlineMapView = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (touchLocked) true else super.dispatchTouchEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.source and InputDevice.SOURCE_ROTARY_ENCODER == InputDevice.SOURCE_ROTARY_ENCODER &&
            offlineMapView != null
        ) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (scroll != 0f) {
                offlineMapView?.zoomBy(if (scroll > 0f) 1 else -1)
                offlineMapView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STEM_1) {
            NavigationSessionService.markLap(this)
            statusText.setText(R.string.lap_marked)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_STEM_2) {
            val enabled = offlineMapView?.toggleFollow() ?: true
            followMapButton.setText(if (enabled) R.string.map_follow_on else R.string.map_follow_off)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            touchLocked = !touchLocked
            lockText.setText(if (touchLocked) R.string.touch_locked else R.string.touch_enabled_hint)
            lockText.setTextColor(if (touchLocked) Color.rgb(255, 184, 77) else Color.LTGRAY)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(18), dp(22), dp(28))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            setText(R.string.title)
            textSize = 20f
            setTextColor(Color.rgb(85, 226, 141))
            gravity = Gravity.CENTER
        }, matchWrap())
        courseText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(4, dp(5), 4, dp(5))
        }
        root.addView(courseText, matchWrap())
        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(6, dp(8), 6, dp(8))
        }
        root.addView(statusText, matchWrap())
        courseMapView = CourseMapView(this).apply {
            minimumHeight = dp(180)
        }
        mapContainer = FrameLayout(this).apply {
            addView(
                courseMapView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        root.addView(
            mapContainer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)),
        )
        elevationProfileView = ElevationProfileView(this).apply {
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        root.addView(
            elevationProfileView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(105)),
        )
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
                val enabled = offlineMapView?.toggleFollow() ?: true
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
            setText(R.string.route_to_course_start)
            setOnClickListener { routeToCourseStart() }
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
        activeCourse = course
        courseReloadName = course.name
        courseMapView.setCourse(course)
        elevationProfileView.setCourse(course)
        offlineMapView?.setCourse(course)
        val prepared = PreparedCourse.prepare(course)
        val ascent = course.points.zipWithNext().sumOf { (first, second) ->
            val a = first.position.elevationMeters
            val b = second.position.elevationMeters
            if (a != null && b != null) (b - a).coerceAtLeast(0.0) else 0.0
        }
        courseText.text = getString(
            R.string.course_summary,
            course.name,
            prepared.totalDistanceMeters / 1_000,
            ascent,
        )
    }

    private fun renderTelemetry(telemetry: NavigationTelemetry) {
        courseMapView.setTelemetry(telemetry)
        elevationProfileView.setTelemetry(telemetry)
        offlineMapView?.setTelemetry(telemetry)
        exercisePauseButton.setText(
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
            -> buildString {
                append(
                    getString(
                        R.string.live_navigation_status,
                        telemetry.remainingMeters / 1_000,
                        telemetry.nextManeuver?.name?.replace('_', ' ') ?: "FINISH",
                        telemetry.distanceToNextManeuverMeters ?: 0.0,
                        telemetry.crossTrackMeters ?: 0.0,
                        telemetry.horizontalAccuracyMeters ?: 0.0,
                    ),
                )
                append(buildClimbStatus(telemetry))
                append(buildOutdoorMetrics(telemetry))
                append(buildHealthMetrics(telemetry))
            }
            SessionPhase.IDLE -> getString(R.string.course_ready)
        }
        statusText.setTextColor(
            if (telemetry.phase == SessionPhase.OFF_COURSE) Color.rgb(255, 92, 92) else Color.WHITE,
        )
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
                activateCourse(stored.course)
                statusText.text = getString(R.string.library_course_active, stored.course.name)
            }.onFailure(::showImportError)
        }
    }

    private fun routeToCourseStart() {
        val origin = NavigationStateHub.current.position ?: run {
            statusText.setText(R.string.routing_needs_location)
            return
        }
        val destination = activeCourse?.points?.firstOrNull()?.position ?: run {
            statusText.setText(R.string.select_course_hint)
            return
        }
        statusText.setText(R.string.calculating_offline_route)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                offlineRouter.route(
                    RouteRequest(
                        origin = origin,
                        destination = destination,
                        profile = RoutingProfile.HIKE,
                    ),
                )
            }
            when (result) {
                is RouteResult.Success -> {
                    val stored = withContext(Dispatchers.IO) { courseStore.saveCourse(result.course) }
                    activateCourse(stored.course)
                    statusText.text = getString(
                        R.string.offline_route_ready,
                        result.course.points.size,
                        result.calculationMillis,
                    )
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

    private suspend fun calculateRouteTo(destination: com.watchnav.core.GeoPoint, name: String) {
        val origin = NavigationStateHub.current.position ?: run {
            statusText.setText(R.string.routing_needs_location)
            return
        }
        statusText.setText(R.string.calculating_offline_route)
        when (val result = withContext(Dispatchers.IO) {
            offlineRouter.route(RouteRequest(origin, destination, profile = RoutingProfile.HIKE))
        }) {
            is RouteResult.Success -> {
                val namedCourse = result.course.copy(name = name)
                val stored = withContext(Dispatchers.IO) { courseStore.saveCourse(namedCourse) }
                activateCourse(stored.course)
                statusText.text = getString(
                    R.string.offline_route_ready,
                    namedCourse.points.size,
                    result.calculationMillis,
                )
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
            statusText.text = if (activities.isEmpty()) {
                getString(R.string.no_activity_history)
            } else {
                activities.take(3).joinToString("\n") { activity ->
                    getString(
                        R.string.activity_history_line,
                        activity.id,
                        activity.distanceMeters / 1_000.0,
                        activity.ascentMeters,
                    )
                }
            }
        }
    }

    private fun showOfflineMap(mapFile: java.io.File) {
        val newMapView = runCatching { OfflineMapsforgeView(this, mapFile) }
            .getOrElse { error ->
                statusText.text = getString(
                    R.string.map_import_failed,
                    error.message ?: error.javaClass.simpleName,
                )
                return
            }
        offlineMapView?.destroy()
        offlineMapView = newMapView
        mapContainer.removeAllViews()
        mapContainer.addView(
            newMapView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        activeCourse?.let(newMapView::setCourse)
        newMapView.setTelemetry(NavigationStateHub.current)
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
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 36) {
            add("android.permission.health.READ_HEART_RATE")
        } else {
            add(Manifest.permission.BODY_SENSORS)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startSession() {
        NavigationSessionService.start(this, ActivityMode.HIKE, selectedPowerMode())
        statusText.setText(R.string.waiting_for_gps)
    }

    private fun toggleAlwaysOn() {
        val preferences = getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE)
        val enabled = !preferences.getBoolean(KEY_ALWAYS_ON, false)
        preferences.edit { putBoolean(KEY_ALWAYS_ON, enabled) }
        applyAlwaysOn(enabled)
    }

    private fun applyAlwaysOnPreference() {
        applyAlwaysOn(
            getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE)
                .getBoolean(KEY_ALWAYS_ON, false),
        )
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

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun wrapWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val DISPLAY_PREFERENCES = "display_preferences"
        const val KEY_ALWAYS_ON = "always_on"
        const val KEY_POWER_MODE = "power_mode"
        const val DOWNLOAD_PREFERENCES = "downloads"
        const val KEY_LAST_GPX_URL = "last_gpx_url"
        const val COURSE_IMPORT_PREFERENCES = "course_imports"
        const val KEY_HANGZHOU_2025_IMPORTED = "hangzhou_2025_gpx_kml"
        const val BUNDLED_COURSE_DIRECTORY = "seed-courses"
        const val DEFAULT_GPX_URL =
            "https://gist.githubusercontent.com/cly/bab1a4f982d43bcc53ff32d4708b8a77/raw/sample.gpx"
    }
}
