package com.watchnav.mobile

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Build
import android.media.MediaPlayer
import android.location.LocationManager
import android.location.Geocoder
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.watchnav.brouter.BRouterOfflineRouter
import com.watchnav.core.CoordinateSystem
import com.watchnav.core.CoordinateConverter
import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.GeoMath
import com.watchnav.core.GeoPoint
import com.watchnav.core.gpx.GpxWriter
import com.watchnav.mapsforge.MapsforgeMapRepository
import com.watchnav.mapsforge.VectorMapLayerKind
import com.watchnav.mobile.sync.CompanionStateBus
import com.watchnav.mobile.sync.MobileSyncCoordinator
import com.watchnav.mobile.data.AuthorizedLayerDownloader
import com.watchnav.mobile.data.CurrentSatelliteDownloader
import com.watchnav.mobile.data.OfflineLayer
import com.watchnav.mobile.data.OfflineLayerKind
import com.watchnav.mobile.data.OfflineLayerStore
import com.watchnav.mobile.data.PublicPoiRepository
import com.watchnav.mobile.data.MobileFishingLogStore
import com.watchnav.mobile.data.WaypointAttachmentStore
import com.watchnav.mobile.data.WaypointProfileStore
import com.watchnav.mobile.speech.FishingAudioTranscriptionManager
import com.watchnav.mobile.speech.FishingTranscriptionResult
import com.watchnav.mobile.ui.FullScreenPanel
import com.watchnav.mobile.ui.HistoricalComparePanel
import com.watchnav.mobile.ui.PanelAction
import com.watchnav.mobile.ui.RouteWorkshopView
import com.watchnav.session.CourseFileStore
import com.watchnav.session.CourseLibraryEntry
import com.watchnav.session.CourseLibraryOrigin
import com.watchnav.session.SavedWaypoint
import com.watchnav.session.WaypointStore
import com.watchnav.session.FavoriteTargetStore
import com.watchnav.packages.ContourMapCatalog
import com.watchnav.packages.BbbikeExtractInstaller
import com.watchnav.packages.DirectMapPackageInstaller
import com.watchnav.packages.RegionBounds
import com.watchnav.packages.RoutingRegionDownloader
import com.watchnav.sync.protocol.SyncEntityType
import com.watchnav.routing.RouteRequest
import com.watchnav.routing.RouteResult
import com.watchnav.routing.RoutingProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var workshop: RouteWorkshopView
    private val courseStore by lazy { CourseFileStore(this) }
    private val coordinator by lazy { MobileSyncCoordinator(this) }
    private val layerStore by lazy { OfflineLayerStore(this) }
    private val waypointStore by lazy { WaypointStore(this) }
    private val favoriteTargetStore by lazy { FavoriteTargetStore(this) }
    private val fishingLogStore by lazy { MobileFishingLogStore(this) }
    private val waypointAttachments by lazy { WaypointAttachmentStore(this) }
    private val waypointProfiles by lazy { WaypointProfileStore(this) }
    private var activeCourse: Course? = null
    private var pendingImport: Uri? = null
    private var pendingLayerKind: OfflineLayerKind? = null
    private var activePanel: android.view.View? = null
    private var routePlanningStart: SavedWaypoint? = null
    private var connectedWatchNodeCount = 0
    private var pendingFishingPhotoId: String? = null
    private var pendingWaypointPhotoId: String? = null
    private var pendingFavoriteSlot: Int? = null
    private var renderedMapSignature: String? = null
    private val stateListener: () -> Unit = { runOnUiThread {
        refreshWatchStatus()
        refreshMapIfChanged()
        renderCourse()
    } }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDetectedFile(uri)
    }

    private val layerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = pendingLayerKind
        pendingLayerKind = null
        if (uri != null && kind != null) {
            if (kind == OfflineLayerKind.HISTORICAL_IMAGERY) askHistoricalDate(uri, kind)
            else importLayer(uri, kind)
        }
    }

    private val fishingPhotoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingFishingPhotoId
        pendingFishingPhotoId = null
        if (uri != null && id != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val extension = displayName(uri)?.substringAfterLast('.', "jpg") ?: "jpg"
                    contentResolver.openInputStream(uri)?.let { fishingLogStore.attachPhoto(id, it, extension) }
                        ?: error("无法读取图片")
                }
            }
            result.onSuccess { toast("图片已添加"); showFishingEntry(id) }
                .onFailure { toast("图片添加失败：${it.message}") }
        }
    }

    private val waypointPhotoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingWaypointPhotoId.also { pendingWaypointPhotoId = null }
        if (uri != null && id != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val extension = displayName(uri)?.substringAfterLast('.', "jpg") ?: "jpg"
                contentResolver.openInputStream(uri)?.let { waypointAttachments.attach(id, it, extension) }
                    ?: error("无法读取图片")
            } }
            result.onSuccess { toast("图片已添加"); showMarkProfile(id) }
                .onFailure { toast("图片添加失败：${it.message}") }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val slot = pendingFavoriteSlot.also { pendingFavoriteSlot = null }
        if (granted && slot != null) setFavoriteFromCurrentLocation(slot) else if (slot != null) toast("需要定位权限")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(4, 7, 8)
        window.navigationBarColor = android.graphics.Color.rgb(4, 7, 8)
        workshop = RouteWorkshopView(this)
        setContentView(workshop)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activePanel != null) {
                    closePanel()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        workshop.importButton.setOnClickListener { showReceiveRouteMenu() }
        workshop.sendButton.setOnClickListener { sendActiveCourse() }
        workshop.shareButton.setOnClickListener { chooseShareFormat() }
        workshop.onRoutes = { showRouteLibrary() }
        workshop.onLayers = { showLayerCenter() }
        workshop.onMarks = { showMarksCenter() }
        workshop.onMeasure = { showDeviceCenter() }
        workshop.onWatchData = { showWatchDataCenter() }
        workshop.onCenter = { activeCourse?.let(workshop.mapHost::setCourse) }
        workshop.watchStatus.setOnClickListener { showWatchDataCenter() }
        workshop.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                searchLocal(workshop.searchInput.text.toString())
                true
            } else false
        }
        restorePhoneMapSelection()
        loadCourse()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        CompanionStateBus.add(stateListener)
        coordinator.announce { runOnUiThread(::refreshWatchStatus) }
        startAutomaticFishingTranscription()
    }

    override fun onStop() {
        CompanionStateBus.remove(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        workshop.mapHost.destroy()
        coordinator.close()
        super.onDestroy()
    }

    private fun loadCourse() {
        lifecycleScope.launch {
            activeCourse = withContext(Dispatchers.IO) {
                runCatching {
                    val stored = courseStore.load() ?: return@runCatching null
                    if (stored.course.isBundledLongtanDemo()) {
                        // Early builds silently installed this UI demonstration route as user
                        // data. Its made-up Hangzhou coordinates do not describe the real Longtan
                        // Reservoir and must never be overlaid on a downloaded Hangzhou map.
                        courseStore.listLibrary()
                            .filter { it.name == LEGACY_DEMO_COURSE_NAME }
                            .forEach(courseStore::delete)
                        null
                    } else {
                        stored.course
                    }
                }.getOrNull()
            }
            renderCourse()
        }
    }

    private fun renderCourse() {
        val course = activeCourse
        val ready = if (course == null) false else {
            val mapReady = !MapsforgeMapRepository(this).activeStack().isEmpty
            val routingReady = course.points.firstOrNull()?.position?.let(BRouterOfflineRouter::regionId)
                ?.let { BRouterOfflineRouter(this).regionFile(it).isFile } == true
            mapReady && routingReady
        }
        val displayCourse = course?.copy(
            landmarks = (course.landmarks + mapWaypoints(course, waypointStore.list()).map { mark ->
                CoursePoint(
                    mark.point,
                    name = mark.name,
                    type = if (mark.id.startsWith("osm-")) "synced-public" else "synced-mark",
                )
            }).distinctBy { "${it.name}:${it.position.latitude}:${it.position.longitude}" },
        )
        workshop.renderCourse(displayCourse, ready)
    }

    /**
     * Public POI downloads can contain thousands of parking/toilet/access nodes. Rendering every
     * one as an Android map layer makes panning and layer switching unnecessarily expensive. Keep
     * all personal marks, then retain the public facilities nearest the active route's centre.
     * The complete collection remains searchable, shareable and explicitly syncable.
     */
    private fun mapWaypoints(course: Course, values: List<SavedWaypoint>): List<SavedWaypoint> =
        selectRelevantWaypoints(course, values, MAXIMUM_MAP_WAYPOINTS)

    private fun marksForPanel(course: Course?, values: List<SavedWaypoint>): List<SavedWaypoint> =
        course?.let { selectRelevantWaypoints(it, values, MAXIMUM_PANEL_WAYPOINTS) }
            ?: values.filterNot { it.id.startsWith("osm-") } +
                values.filter { it.id.startsWith("osm-") }.take(MAXIMUM_PANEL_WAYPOINTS)

    private fun selectRelevantWaypoints(
        course: Course,
        values: List<SavedWaypoint>,
        maximumPublicPoints: Int,
    ): List<SavedWaypoint> {
        val privateMarks = values.filterNot { it.id.startsWith("osm-") }
        val publicMarks = values.filter { it.id.startsWith("osm-") }
        if (course.points.isEmpty()) return privateMarks + publicMarks.take(maximumPublicPoints)
        val center = GeoPoint(
            (course.points.minOf { it.position.latitude } + course.points.maxOf { it.position.latitude }) / 2.0,
            (course.points.minOf { it.position.longitude } + course.points.maxOf { it.position.longitude }) / 2.0,
        )
        return privateMarks + publicMarks.asSequence()
            .sortedBy { GeoMath.distanceMeters(center, it.point) }
            .take(maximumPublicPoints)
            .toList()
    }

    private fun chooseCoordinateSystem(uri: Uri) {
        pendingImport = uri
        val systems = arrayOf("WGS-84", "GCJ-02", "BD-09")
        AlertDialog.Builder(this)
            .setTitle(R.string.coordinate_system)
            .setItems(systems) { _, which -> importCourse(uri, CoordinateSystem.entries[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importCourse(uri: Uri, coordinateSystem: CoordinateSystem) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(uri) ?: "Imported route"
                    val bytes = contentResolver.openInputStream(uri)?.use { input -> readBounded(input, MAX_IMPORT_BYTES) }
                        ?: error("Unable to open file")
                    val stored = when {
                        name.endsWith(".kmz", true) -> {
                            val kml = extractKml(bytes)
                            courseStore.importKml(ByteArrayInputStream(kml), name.substringBeforeLast('.'), coordinateSystem)
                        }
                        name.endsWith(".kml", true) || contentResolver.getType(uri)?.contains("kml") == true ->
                            courseStore.importKml(ByteArrayInputStream(bytes), name.substringBeforeLast('.'), coordinateSystem)
                        else -> courseStore.import(ByteArrayInputStream(bytes), name.substringBeforeLast('.'), coordinateSystem)
                    }
                    stored.course
                }
            }
            result.onSuccess { course ->
                activeCourse = course
                course.landmarks.forEachIndexed { index, landmark ->
                    val waypoint = waypointStore.save(landmark.name ?: "路线标点 ${index + 1}", landmark.position)
                    coordinator.publishWaypoint(waypoint)
                }
                renderCourse()
                workshop.statusText.text = getString(R.string.import_ready, course.name)
            }.onFailure { error ->
                workshop.statusText.text = getString(R.string.import_failed, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun sendActiveCourse() {
        val file = courseStore.activeFile() ?: return
        workshop.statusText.setText(R.string.syncing)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                coordinator.publishCourse(file) { delivered ->
                    runOnUiThread {
                        workshop.statusText.setText(if (delivered) R.string.sync_sent else R.string.sync_no_watch)
                        refreshWatchStatus()
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    workshop.statusText.text = getString(R.string.import_failed, error.message.orEmpty())
                }
            }
        }
    }

    private fun chooseShareFormat() {
        AlertDialog.Builder(this)
            .setTitle(R.string.share_route)
            .setItems(arrayOf("GPX", "KML")) { _, which -> shareActiveCourse(if (which == 0) "gpx" else "kml") }
            .show()
    }

    private fun showReceiveRouteMenu() {
        AlertDialog.Builder(this)
            .setTitle("接收路线")
            .setItems(arrayOf("从文件接收 GPX / KML / KMZ", "等待其他手机分享")) { _, which ->
                if (which == 0) importLauncher.launch(arrayOf(
                    "application/gpx+xml",
                    "application/vnd.google-earth.kml+xml",
                    "application/vnd.google-earth.kmz",
                    "application/octet-stream",
                    "*/*",
                )) else toast("在其他手机选择路线并分享给 WatchNav，即可自动接收")
            }
            .show()
    }

    private fun shareActiveCourse(format: String) {
        val course = activeCourse ?: return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val directory = File(cacheDir, "shared").apply { mkdirs() }
                File(directory, safeName(course.name) + ".$format").also { output ->
                    output.outputStream().use { stream ->
                        if (format == "gpx") GpxWriter.write(course, stream)
                        else stream.bufferedWriter().use { it.write(courseToKml(course)) }
                    }
                }
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = if (format == "gpx") "application/gpx+xml" else "application/vnd.google-earth.kml+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, getString(R.string.share_route)))
        }
    }

    private fun refreshWatchStatus() {
        coordinator.connectedNodes { nodes ->
            runOnUiThread {
                val lanPeer = coordinator.recentLanPeer()
                connectedWatchNodeCount = if (nodes.isNotEmpty() || lanPeer != null) 1 else 0
                workshop.watchStatus.text = nodes.firstOrNull()?.displayName?.let {
                    getString(R.string.watch_connected, it)
                } ?: lanPeer?.let { "局域网直连 · $it" } ?: getString(R.string.watch_disconnected)
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) importDetectedFile(uri)
    }

    private fun importDetectedFile(uri: Uri) {
        val name = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        when (extension) {
            "gpx", "kml", "kmz" -> chooseCoordinateSystem(uri)
            "rd5" -> importRoutingRegion(uri, name)
            else -> inferLayerKind(name, extension)?.let { kind ->
                if (kind == OfflineLayerKind.HISTORICAL_IMAGERY) askHistoricalDate(uri, kind) else importLayer(uri, kind)
            } ?: toast("无法识别 .$extension 文件；支持 GPX/KML/KMZ/MAP/RD5/MBTiles/GeoTIFF/GeoJSON/GeoPackage")
        }
    }

    private fun inferLayerKind(name: String, extension: String): OfflineLayerKind? = when {
        extension == "map" && Regex("contour|elevation|dem|等高|高程", RegexOption.IGNORE_CASE).containsMatchIn(name) -> OfflineLayerKind.CONTOUR
        extension == "map" -> OfflineLayerKind.BASE_MAP
        extension == "mbtiles" && Regex("depth|bath|深|海图|enc", RegexOption.IGNORE_CASE).containsMatchIn(name) -> OfflineLayerKind.BATHYMETRY
        extension in setOf("mbtiles", "tif", "tiff", "geotiff") -> OfflineLayerKind.HISTORICAL_IMAGERY
        extension in setOf("000", "s57") -> OfflineLayerKind.ENC_CHART
        extension == "gpkg" && Regex("depth|bath|深", RegexOption.IGNORE_CASE).containsMatchIn(name) -> OfflineLayerKind.BATHYMETRY
        extension == "gpkg" -> OfflineLayerKind.ENC_CHART
        extension in setOf("geojson", "json", "pbf") -> OfflineLayerKind.WATER_BOUNDARY
        else -> null
    }

    private fun importRoutingRegion(uri: Uri, name: String) = lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            contentResolver.openInputStream(uri)?.use { BRouterOfflineRouter(this@MainActivity).importRegion(name.substringBeforeLast('.'), it) }
                ?: error("无法读取路网")
        } }
        result.onSuccess { toast("路网已导入：${it.name}") }.onFailure { toast("导入失败：${it.message}") }
    }

    private fun displayName(uri: Uri): String? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor: Cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun showRouteLibrary(filter: String = "") {
        val entries = courseStore.listLibrary().filter { it.name.contains(filter, ignoreCase = true) }
        val destinations = waypointStore.list().filter { it.name.contains(filter, ignoreCase = true) }.take(12)
        val actions = buildList {
            add(PanelAction("一、导入路线", "GPX / KML / KMZ 路线；点击已有路线可启用、重命名或删除", ORANGE, false) {})
            add(PanelAction("接收路线", "从文件或其他手机接收 GPX / KML / KMZ") {
                closePanel(); workshop.importButton.performClick()
            })
            entries.forEach { entry ->
                val detail = if (entry.pointCount >= 0) "${entry.pointCount} 个轨迹点" else "轨迹点将在启用时统计"
                add(PanelAction(entry.name, "$detail · 点击管理", PURPLE) { showRouteActions(entry) })
            }
            add(PanelAction("二、搜索目标点", if (filter.isBlank()) "在首页搜索地点、标记或坐标后选择" else "搜索：$filter", ORANGE, false) {})
            destinations.forEach { mark ->
                add(PanelAction(mark.name, "目标点 · ${formatPoint(mark.point)}", CYAN) { chooseRouteStartForDestination(mark) })
            }
            add(PanelAction("三、自由探索", "手表导航界面右侧按钮：第一次确定起点，第二次确定终点并保存路线", ORANGE) {
                closePanel(); toast("请在手表导航界面确定自由探索起点和终点")
            })
        }
        showPanel("路线", "导入路线、目标点导航、自由探索三种方式", actions)
    }

    private fun showRouteActions(entry: CourseLibraryEntry) {
        AlertDialog.Builder(this).setTitle(entry.name)
            .setItems(arrayOf("设为当前路线", "重命名", "删除")) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch {
                        activeCourse = withContext(Dispatchers.IO) { courseStore.activate(entry).course }
                        closePanel(); renderCourse()
                    }
                    1 -> {
                        val input = EditText(this).apply { setText(entry.name); selectAll() }
                        AlertDialog.Builder(this).setTitle("重命名路线").setView(input)
                            .setPositiveButton("保存") { _, _ -> lifecycleScope.launch {
                                runCatching { withContext(Dispatchers.IO) { courseStore.rename(entry, input.text.toString()) } }
                                    .onSuccess { renamed ->
                                        activeCourse = withContext(Dispatchers.IO) { courseStore.activate(renamed).course }
                                        renderCourse(); showRouteLibrary()
                                    }.onFailure { toast("重命名失败：${it.message}") }
                            } }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                    2 -> AlertDialog.Builder(this).setTitle("删除路线")
                        .setMessage("确定删除“${entry.name}”？")
                        .setPositiveButton("删除") { _, _ ->
                            courseStore.delete(entry)
                            if (activeCourse?.name == entry.name) activeCourse = null
                            renderCourse(); showRouteLibrary()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            }.show()
    }

    private fun chooseRouteStartForDestination(destination: SavedWaypoint) {
        val current = currentPhoneLocation()?.let { SavedWaypoint("phone-current", "当前位置", it, System.currentTimeMillis()) }
        val starts = listOfNotNull(current) + waypointStore.list().filterNot { it.id == destination.id }.take(20)
        if (starts.isEmpty()) { toast("暂无可用起点"); return }
        AlertDialog.Builder(this).setTitle("选择起点")
            .setItems(starts.map { it.name }.toTypedArray()) { _, index -> planRoute(starts[index], destination) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showLayerCenter() {
        val mapRepository = MapsforgeMapRepository(this)
        val baseLayers = layerStore.list().filter { it.kind == OfflineLayerKind.BASE_MAP }
        val actions = buildList {
            add(PanelAction("下载设置", "设置自动下载半径、断点续传与仅 Wi-Fi 下载", ORANGE) { showMapDownloadSettings() })
            add(PanelAction("普通地图（${baseLayers.size}）", "逐张列出本机已有矢量底图；当前地图标有“使用中”", CYAN, false) {})
            if (baseLayers.isEmpty()) {
                add(PanelAction("下载普通地图", "按当前定位或路线区域自动下载", CYAN) {
                    closePanel()
                    downloadRouteBaseMap()
                })
            } else {
                baseLayers.forEach { layer ->
                    add(PanelAction(
                        layer.name,
                        "${formatBytes(layer.bytes)}${if (layer.enabled) " · 使用中" else " · 点击切换"}",
                        if (layer.enabled) PURPLE else CYAN,
                    ) { switchBaseLayer(layer) })
                }
            }
            add(PanelAction(
                if (mapRepository.isContourEnabled()) "隐藏等高线" else "显示等高线",
                "等高线作为普通地图的独立覆盖层；保持当前中心和路线",
                ORANGE,
            ) {
                switchPrimaryLayer(OfflineLayerKind.CONTOUR)
            })
            add(PanelAction("切换卫星地图", "优先显示当前 Sentinel-2 卫星底图；没有时自动下载，再叠加历史影像", PURPLE) {
                switchPrimaryLayer(OfflineLayerKind.HISTORICAL_IMAGERY)
            })
        }
        showPanel("图层", "只负责下载范围设置与地图切换；缺失资料会自动下载", actions)
    }

    private fun switchBaseLayer(layer: OfflineLayer) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    layerStore.setEnabled(layer.id, true, OfflineLayerKind.BASE_MAP, layer.bytes)
                    MapsforgeMapRepository(this@MainActivity).setContourEnabled(false)
                }
            }.onSuccess {
                workshop.mapHost.showVector()
                workshop.mapHost.reloadOfflineMap()
                getSharedPreferences("phone_map_selection", MODE_PRIVATE).edit()
                    .putString("mode", "VECTOR")
                    .putString("layer_id", layer.id)
                    .apply()
                closePanel()
                renderCourse()
                toast("已切换普通地图：${layer.name}")
            }.onFailure { toast("地图切换失败：${it.message}") }
        }
    }

    private fun showMapDownloadSettings() {
        val prefs = getSharedPreferences("map_download_settings", MODE_PRIVATE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 8, 36, 0) }
        val radius = EditText(this).apply {
            hint = "自动下载半径（公里）"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getFloat("radius_km", 15f).toString())
        }
        layout.addView(radius)
        AlertDialog.Builder(this).setTitle("下载设置").setView(layout)
            .setMultiChoiceItems(arrayOf("仅 Wi-Fi 下载"), booleanArrayOf(prefs.getBoolean("wifi_only", true))) { _, _, checked ->
                prefs.edit().putBoolean("wifi_only", checked).apply()
            }
            .setPositiveButton("保存") { _, _ ->
                prefs.edit().putFloat("radius_km", radius.text.toString().toFloatOrNull()?.coerceIn(1f, 100f) ?: 15f).apply()
                toast("下载设置已保存")
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun switchPrimaryLayer(kind: OfflineLayerKind) {
        val layer = layerStore.list().firstOrNull { candidate ->
            candidate.kind == kind &&
                (kind != OfflineLayerKind.HISTORICAL_IMAGERY || candidate.file.extension.equals("mbtiles", true))
        }
        if (layer == null) {
            closePanel()
            when (kind) {
                OfflineLayerKind.BASE_MAP -> downloadRouteBaseMap()
                OfflineLayerKind.CONTOUR -> downloadContourMap()
                OfflineLayerKind.HISTORICAL_IMAGERY -> downloadCurrentSatelliteMap()
                else -> Unit
            }
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (kind) {
                        OfflineLayerKind.HISTORICAL_IMAGERY -> layer.file
                        OfflineLayerKind.BASE_MAP -> MapsforgeMapRepository(this@MainActivity).run {
                            val target = layers().firstOrNull { it.id == layer.id }
                                ?: layers().filter { it.kind == VectorMapLayerKind.BASE && it.bytes == layer.bytes }
                                    .maxByOrNull { it.importedAtMillis }
                            select(VectorMapLayerKind.BASE, requireNotNull(target).id)
                            setContourEnabled(false)
                            activeStack()
                        }
                        OfflineLayerKind.CONTOUR -> MapsforgeMapRepository(this@MainActivity).run {
                            val target = layers().firstOrNull { it.id == layer.id }
                                ?: layers().filter { it.kind == VectorMapLayerKind.CONTOUR && it.bytes == layer.bytes }
                                    .maxByOrNull { it.importedAtMillis }
                            val enable = selected(VectorMapLayerKind.CONTOUR)?.id != target?.id || !isContourEnabled()
                            select(VectorMapLayerKind.CONTOUR, requireNotNull(target).id)
                            setContourEnabled(enable)
                            activeStack()
                        }
                        else -> error("Unsupported primary layer: $kind")
                    }
                }
            }.onSuccess {
                if (kind == OfflineLayerKind.HISTORICAL_IMAGERY) workshop.mapHost.showRaster(layer.file)
                else {
                    workshop.mapHost.showVector()
                    workshop.mapHost.reloadOfflineMap()
                }
                getSharedPreferences("phone_map_selection", MODE_PRIVATE).edit()
                    .putString("mode", if (kind == OfflineLayerKind.HISTORICAL_IMAGERY) "RASTER" else "VECTOR")
                    .putString("layer_id", layer.id).apply()
                closePanel()
                renderCourse()
                toast("已切换：${layer.name}")
            }.onFailure { toast("地图切换失败：${it.message}") }
        }
    }

    private fun restorePhoneMapSelection() {
        val preferences = getSharedPreferences("phone_map_selection", MODE_PRIVATE)
        if (preferences.getString("mode", "VECTOR") != "RASTER") {
            workshop.mapHost.showVector()
            return
        }
        val id = preferences.getString("layer_id", null) ?: return
        val layer = layerStore.list().firstOrNull { it.id == id && it.enabled } ?: return
        if (layer.kind == OfflineLayerKind.HISTORICAL_IMAGERY && layer.file.extension.equals("mbtiles", true)) {
            workshop.mapHost.showRaster(layer.file)
        } else {
            workshop.mapHost.showVector()
        }
    }

    private fun refreshMapIfChanged() {
        val signature = MapsforgeMapRepository(this).activeStack().takeIf { !it.isEmpty }?.cacheIdentity
        if (signature == renderedMapSignature) return
        renderedMapSignature = signature
        workshop.mapHost.reloadOfflineMap()
        activeCourse?.let(workshop.mapHost::setCourse)
    }

    private fun showMarksCenter() {
        coordinator.requestSync()
        val allMarks = waypointStore.list()
        val marks = marksForPanel(activeCourse, allMarks)
        val actions = buildList {
            add(PanelAction("新增钓点 / 备注", "手动输入名称和坐标，可选择 WGS-84、GCJ-02 或 BD-09") { showAddMarkDialog() })
            add(PanelAction("下载公共下钓点与设施", "路线周边 OSM：亲水入口、下水坡道、停车、厕所、饮水点", ORANGE, activeCourse != null) {
                downloadPublicPoi()
            })
            add(PanelAction("同步全部标点到手表", "${allMarks.size} 个标点；公共点与私有钓点分别保存", PURPLE, allMarks.isNotEmpty()) {
                allMarks.forEach { coordinator.publishWaypoint(it, if (it.id.startsWith("osm-")) SyncEntityType.PUBLIC_POI else SyncEntityType.WAYPOINT) }
                toast("标点已加入同步队列")
            })
            add(PanelAction("分享全部标点", "导出为 GPX，供奥维、两步路及其他 GIS 工具使用", PURPLE, allMarks.isNotEmpty()) {
                shareWaypoints(allMarks)
            })
            if (marks.size < allMarks.size) add(PanelAction(
                "公共设施已精简显示",
                "共 ${allMarks.size} 个；当前按路线附近显示 ${marks.size} 个，可用首页搜索查找其余标点",
                ORANGE,
                false,
            ) {})
            marks.forEach { mark ->
                val photoCount = waypointAttachments.photos(mark.id).size
                val recordings = fishingEntriesFor(mark)
                add(PanelAction(mark.name, "${formatPoint(mark.point)} · 录音 ${recordings.count { fishingLogStore.audioFile(it) != null }} · 图片 $photoCount", CYAN) {
                    showMarkProfile(mark.id)
                })
            }
            fishingLogStore.list().filter { entry -> marks.none { mark -> entryMatchesWaypoint(entry, mark) } }.forEach { entry ->
                val length = entry.lengthCm?.let { " · ${it}cm" }.orEmpty()
                add(PanelAction("${entry.species ?: "垂钓录音"}$length", entry.transcript.ifBlank { "保留的手表原音频" }, ORANGE) {
                    showFishingEntry(entry.id)
                })
            }
        }
        showPanel("钓点与公共设施", "手机完整编辑信息，手表保留精简的离线标点", actions)
    }

    private fun showDeviceCenter() {
        val settings = getSharedPreferences("companion_settings", MODE_PRIVATE)
        val alwaysOn = settings.getBoolean("always_on", false)
        val powerMode = settings.getString("power_mode", "BALANCED") ?: "BALANCED"
        val hello = coordinator.latestWatch()
        val watchDetail = hello?.let {
            val prefix = if (connectedWatchNodeCount > 0) "已连接" else "最近同步"
            "$prefix · v${it.appVersionName} · 电量 ${it.batteryPercent ?: 0}% · 可用 ${formatBytes(it.freeBytes)}"
        }
            ?: "等待手表通过 Wear OS Data Layer 连接"
        val targets = favoriteTargetStore.list().associateBy { it.slot }
        val actions = buildList {
            add(
            PanelAction("立即双向同步", watchDetail, PURPLE) {
                coordinator.requestSync { count -> runOnUiThread { toast(if (count > 0) "同步请求已发送" else "手表未连接") } }
            })
            add(PanelAction("常亮显示：${if (alwaysOn) "开" else "关"}", "切换后同步到手表") {
                val next = !alwaysOn
                settings.edit().putBoolean("always_on", next).apply()
                coordinator.publishSettings(next, powerMode)
                showDeviceCenter()
            })
            add(PanelAction("定位与续航：${powerModeLabel(powerMode)}", "准确 / 均衡 / 远征，点击轮换", ORANGE) {
                val modes = listOf("ACCURATE", "BALANCED", "EXPEDITION")
                val next = modes[(modes.indexOf(powerMode).coerceAtLeast(0) + 1) % modes.size]
                settings.edit().putString("power_mode", next).apply()
                coordinator.publishSettings(alwaysOn, next)
                showDeviceCenter()
            })
            (0..5).forEach { slot ->
                val label = if (slot == 0) "家" else "常用目标 $slot"
                add(PanelAction("$label：${targets[slot]?.name ?: "未设置"}", "使用手机当前定位坐标，可填写独立备注", if (slot == 0) ORANGE else CYAN) {
                    setFavoriteFromCurrentLocation(slot)
                })
            }
            add(PanelAction("偏航自动重规划", "固定阈值 100 米；配置与手表同步", PURPLE) {})
            add(PanelAction("手机伴侣版本", com.watchnav.mobile.BuildConfig.VERSION_NAME, CYAN) {})
        }
        showPanel("手表设置与测量", "在手机完成设置并同步；手表仍可独立离线使用", actions)
    }

    private fun setFavoriteFromCurrentLocation(slot: Int) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingFavoriteSlot = slot
            locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val point = currentPhoneLocation()
        if (point == null) { toast("暂时无法取得当前位置，请开启手机定位"); return }
        val label = if (slot == 0) "家" else "常用目标 $slot"
        val note = EditText(this).apply { hint = "名称或备注"; setText(label) }
        AlertDialog.Builder(this).setTitle("$label · ${formatPoint(point)}").setView(note)
            .setPositiveButton("保存并同步") { _, _ ->
                favoriteTargetStore.set(slot, note.text.toString().trim().ifBlank { label }, point)
                val settings = getSharedPreferences("companion_settings", MODE_PRIVATE)
                coordinator.publishSettings(settings.getBoolean("always_on", false), settings.getString("power_mode", "BALANCED") ?: "BALANCED")
                showDeviceCenter()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showWatchDataCenter() {
        val hello = coordinator.latestWatch()
        val artifacts = coordinator.artifactEntries()
        val maps = artifacts.filter { it.kind in setOf(
            com.watchnav.sync.protocol.TransferKind.MAPSFORGE,
            com.watchnav.sync.protocol.TransferKind.CONTOUR_MAP,
            com.watchnav.sync.protocol.TransferKind.HISTORICAL_RASTER,
            com.watchnav.sync.protocol.TransferKind.ENC_CHART,
            com.watchnav.sync.protocol.TransferKind.BATHYMETRY,
        ) }
        val routeArtifacts = artifacts.filter { it.kind == com.watchnav.sync.protocol.TransferKind.COURSE_FILE }
        val routes = maxOf(
            coordinator.records(SyncEntityType.COURSE).count { !it.deleted },
            routeArtifacts.size,
            courseStore.listLibrary().size,
        )
        val marks = maxOf(
            coordinator.records(SyncEntityType.WAYPOINT).count { !it.deleted },
            waypointStore.list().size,
        )
        val audioArtifacts = artifacts.filter { it.kind == com.watchnav.sync.protocol.TransferKind.FISHING_AUDIO }
        val fishingEntries = fishingLogStore.list()
        val receivedAudio = maxOf(
            fishingEntries.count { fishingLogStore.audioFile(it) != null },
            audioArtifacts.size,
        )
        val expectedAudio = maxOf(receivedAudio, fishingEntries.count { it.audioFileName != null })
        val actions = buildList {
            add(PanelAction("局域网直连同步", "同一 Wi-Fi 下直接双向同步，不依赖 Google 服务", CYAN) {
                toast("正在搜索同一 Wi-Fi 下的手表…")
                coordinator.syncLan { result ->
                    runOnUiThread {
                        result.fold(
                            onSuccess = { value ->
                                toast("局域网同步完成：${value.peerAddress}")
                                showWatchDataCenter()
                            },
                            onFailure = { toast("未发现手表，请先在手表路线库点击“接收路线”") },
                        )
                    }
                }
            })
            add(PanelAction("从手表收取并长期保存", "地图 ${maps.size} · 路线 $routes · 标记 $marks · 录音 $receivedAudio/$expectedAudio", PURPLE) {
                coordinator.requestSync { count -> runOnUiThread { toast(if (count > 0) "已请求手表发送全部资料" else "手表未连接") } }
            })
            add(PanelAction("手表状态", hello?.let {
                val prefix = if (connectedWatchNodeCount > 0) "已连接" else "最近同步（当前未连接）"
                "$prefix · v${it.appVersionName} · 电量 ${it.batteryPercent ?: 0}% · 可用 ${formatBytes(it.freeBytes)}"
            } ?: "未连接", CYAN) {})
            maps.forEach { item ->
                add(PanelAction(item.displayName, "${item.kind.name} · ${formatBytes(item.totalBytes)} · 已保存手机", CYAN) {})
            }
            coordinator.records(SyncEntityType.COURSE).filterNot { it.deleted }.forEach { record ->
                add(PanelAction("手表路线：${record.id}", "${formatBytes(record.payload.size.toLong())} · 已持久化", PURPLE) {})
            }
            routeArtifacts.forEach { item ->
                add(PanelAction("手表路线：${item.displayName}", "${formatBytes(item.totalBytes)} · 文件已校验保存", PURPLE) {})
            }
            waypointStore.list().filter { mark -> fishingEntriesFor(mark).isNotEmpty() }.forEach { mark ->
                val recordings = fishingEntriesFor(mark)
                add(PanelAction("标记资料：${mark.name}", "${formatPoint(mark.point)} · 录音 ${recordings.size} · 图片 ${waypointAttachments.photos(mark.id).size}", ORANGE) {
                    showMarkProfile(mark.id)
                })
            }
        }
        showPanel("手表资料库", "手表中的地图、路线、语音和标记自动回传手机长期保存", actions)
    }

    private fun showFishingEntry(id: String) {
        val entry = fishingLogStore.find(id) ?: return
        val audio = fishingLogStore.audioFile(entry)
        val options = buildList {
            if (audio != null) add("播放原音频")
            if (audio != null) add("手机语音识别")
            add("编辑文字与渔获信息")
            add("添加图片（${fishingLogStore.photoFiles(entry).size}）")
        }
        val transcriptionDetail = entry.transcript.ifBlank {
            entry.transcriptionError?.let { "原音频已保留 · 自动转写待重试：$it" }
                ?: "原音频保留在手机，正在等待自动转写"
        }
        AlertDialog.Builder(this).setTitle(entry.species ?: "垂钓原音频")
            .setMessage(transcriptionDetail)
            .setItems(options.toTypedArray()) { _, index ->
                when (options[index].substringBefore('（')) {
                    "播放原音频" -> playFishingAudio(requireNotNull(audio))
                    "手机语音识别" -> transcribeFishingAudio(entry.id)
                    "编辑文字与渔获信息" -> editFishingEntry(entry.id)
                    "添加图片" -> {
                        pendingFishingPhotoId = entry.id
                        fishingPhotoLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                    }
                }
            }.setNegativeButton("关闭", null).show()
    }

    private fun playFishingAudio(file: File) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener(MediaPlayer::release)
                setOnErrorListener { player, _, _ -> player.release(); true }
                prepare()
                start()
            }
        }.onFailure { toast("音频播放失败：${it.message}") }
    }

    private fun transcribeFishingAudio(id: String) {
        FishingAudioTranscriptionManager.transcribeNow(this, id) { result ->
            when (result) {
                is FishingTranscriptionResult.Success -> {
                    toast(if (result.waypointId != null) "已转写到标记的文字备注" else "已转写；等待匹配对应标记")
                    showFishingEntry(result.entryId)
                }
                is FishingTranscriptionResult.Failure -> toast("语音识别失败：${result.message}；原音频已保留")
            }
        }
        toast("手机正在识别原音频…")
    }

    private fun startAutomaticFishingTranscription() {
        FishingAudioTranscriptionManager.linkCompletedTranscripts(this)
        FishingAudioTranscriptionManager.enqueuePending(this)
    }

    private fun editFishingEntry(id: String) {
        val entry = fishingLogStore.find(id) ?: return
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 8, 36, 0) }
        val transcript = EditText(this).apply { hint = "识别文字 / 备注"; setText(entry.transcript); minLines = 3 }
        val species = EditText(this).apply { hint = "鱼种"; setText(entry.species.orEmpty()) }
        val length = EditText(this).apply { hint = "长度 cm"; inputType = InputType.TYPE_CLASS_NUMBER; setText(entry.lengthCm?.toString().orEmpty()) }
        layout.addView(transcript); layout.addView(species); layout.addView(length)
        AlertDialog.Builder(this).setTitle("编辑垂钓记录").setView(layout)
            .setPositiveButton("保存并同步") { _, _ ->
                fishingLogStore.updateText(id, transcript.text.toString(), species.text.toString(), length.text.toString().toIntOrNull())
                    ?.let {
                        FishingAudioTranscriptionManager.linkCompletedTranscripts(this)
                        coordinator.publishFishingEntry(it)
                    }
                toast("垂钓记录已保存")
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showPanel(title: String, subtitle: String, actions: List<PanelAction>) {
        closePanel()
        activePanel = FullScreenPanel(this, title, subtitle, actions, ::closePanel).also { panel ->
            // Buttons in the map workspace have their own elevation. Give modal panels an explicit
            // higher Z level so the collapsible menu handle cannot bleed through the panel.
            panel.elevation = 100f * resources.displayMetrics.density
            workshop.addView(panel, android.widget.FrameLayout.LayoutParams(-1, -1))
            panel.bringToFront()
        }
    }

    private fun closePanel() {
        (activePanel as? AutoCloseable)?.close()
        activePanel?.let(workshop::removeView)
        activePanel = null
    }

    private fun launchLayerImport(kind: OfflineLayerKind) {
        pendingLayerKind = kind
        layerLauncher.launch(arrayOf("*/*"))
    }

    private fun askHistoricalDate(uri: Uri, kind: OfflineLayerKind) {
        val date = EditText(this).apply { hint = "拍摄日期或时期，例如 2020-01 枯水期" }
        AlertDialog.Builder(this).setTitle("影像时期").setView(date)
            .setPositiveButton("导入") { _, _ -> importLayer(uri, kind, date.text.toString().trim().ifBlank { null }) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun importLayer(uri: Uri, kind: OfflineLayerKind, sourceDate: String? = null) {
        val name = displayName(uri) ?: "offline-layer.${kind.extensions.first()}"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        layerStore.import(input, name, kind, sourceDate = sourceDate)
                    } ?: error("Unable to open layer")
                }
            }
            result.onSuccess {
                toast("图层已导入：${it.name}"); workshop.mapHost.reloadOfflineMap()
                closePanel(); showLayerCenter(); renderCourse()
            }
                .onFailure { toast("导入失败：${it.message}") }
        }
    }

    private fun showLayerActions(layer: com.watchnav.mobile.data.OfflineLayer) {
        val canView = layer.kind == OfflineLayerKind.HISTORICAL_IMAGERY && layer.file.extension.equals("mbtiles", true)
        val options = buildList {
            if (canView) add("查看 / 切换历史影像")
            add(if (layer.enabled) "暂时隐藏" else "启用")
            add("发送到手表")
        }
        AlertDialog.Builder(this)
            .setTitle(layer.name)
            .setItems(options.toTypedArray()) { _, which ->
                val selected = options[which]
                if (selected.startsWith("查看")) {
                    showHistoricalCompare(layer)
                } else if (selected == "暂时隐藏" || selected == "启用") {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            layerStore.setEnabled(layer.id, !layer.enabled, layer.kind, layer.bytes)
                        }
                        workshop.mapHost.reloadOfflineMap()
                        renderCourse()
                        showLayerCenter()
                    }
                } else {
                    coordinator.sendPackage(layer.file, layer.kind.transferKind, layer.id, layer.name, layer.license, layer.attribution)
                    toast("${layer.name} 已加入发送队列")
                }
            }.show()
    }

    private fun showHistoricalCompare(selected: com.watchnav.mobile.data.OfflineLayer) {
        closePanel()
        val candidates = layerStore.list().filter {
            it.enabled && it.kind == OfflineLayerKind.HISTORICAL_IMAGERY && it.file.extension.equals("mbtiles", true)
        }.let { values -> listOf(selected) + values.filterNot { it.id == selected.id } }
        activePanel = HistoricalComparePanel(this, candidates, activeCourse, ::closePanel).also { panel ->
            panel.elevation = 100f * resources.displayMetrics.density
            workshop.addView(panel, android.widget.FrameLayout.LayoutParams(-1, -1))
            panel.bringToFront()
        }
    }

    private fun downloadRoutingData() {
        val bounds = routeBounds() ?: return
        closePanel(); workshop.statusText.text = "准备下载路网…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    RoutingRegionDownloader(this@MainActivity).install(bounds) { progress ->
                        runOnUiThread { workshop.statusText.text = "路网 ${progress.regionId} ${formatBytes(progress.downloadedBytes)}" }
                    }
                }
            }
            result.onSuccess { files ->
                workshop.statusText.text = "路网已就绪：${files.size} 个区域"
                files.forEach { file -> coordinator.sendPackage(file, com.watchnav.sync.protocol.TransferKind.BROUTER_RD5, file.nameWithoutExtension, file.name) }
                renderCourse()
            }.onFailure { toast("路网下载失败：${it.message}") }
        }
    }

    private fun downloadRouteBaseMap() {
        val radiusKm = getSharedPreferences("map_download_settings", MODE_PRIVATE).getFloat("radius_km", 15f).toDouble()
        val bounds = routeBounds(radiusKm / 111.32) ?: run { toast("无法取得路线或当前定位"); return }
        closePanel()
        workshop.statusText.text = "正在提交路线区域地图…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BbbikeExtractInstaller(this@MainActivity).requestAndInstall(bounds) { progress ->
                        runOnUiThread {
                            workshop.statusText.text = when (progress.stage) {
                                com.watchnav.packages.ExtractStage.SUBMITTING -> "正在提交地图任务…"
                                com.watchnav.packages.ExtractStage.WAITING -> "地图生成中…"
                                com.watchnav.packages.ExtractStage.RETRYING -> "地图服务重试 ${progress.retryAttempt}…"
                                com.watchnav.packages.ExtractStage.DOWNLOADING -> "地图下载 ${formatBytes(progress.downloadedBytes)}"
                                com.watchnav.packages.ExtractStage.VERIFYING -> "正在校验离线地图…"
                            }
                        }
                    }
                }
            }
            result.onSuccess { activeMap ->
                val layer = withContext(Dispatchers.IO) {
                    layerStore.register(
                        activeMap,
                        OfflineLayerKind.BASE_MAP,
                        activeCourse?.name?.let { "$it 离线地图" } ?: "路线离线地图",
                        "© OpenStreetMap contributors · BBBike",
                        "OpenStreetMap / source package terms",
                    )
                }
                coordinator.sendPackage(
                    layer.file,
                    layer.kind.transferKind,
                    layer.id,
                    layer.name,
                    layer.license,
                    layer.attribution,
                )
                workshop.mapHost.reloadOfflineMap()
                activeCourse?.let(workshop.mapHost::setCourse)
                workshop.statusText.text = "路线区域地图已安装并与手表同步"
                renderCourse()
            }.onFailure { toast("离线地图下载失败：${it.message}") }
        }
    }

    private fun downloadContourMap() {
        val point = activeCourse?.points?.firstOrNull()?.position ?: currentPhoneLocation()
            ?: run { toast("无法取得路线或当前定位"); return }
        val item = ContourMapCatalog.covering(point.latitude, point.longitude)
        if (item == null) { toast("当前区域暂无内置等高线目录"); return }
        closePanel(); workshop.statusText.text = "准备下载 ${item.displayName}…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    DirectMapPackageInstaller(this@MainActivity).install(item) { current, total ->
                        runOnUiThread { workshop.statusText.text = "等高线 ${formatBytes(current)} / ${formatBytes(total)}" }
                    }
                }
            }
            result.onSuccess { file ->
                val layer = withContext(Dispatchers.IO) {
                    layerStore.register(file, OfflineLayerKind.CONTOUR, item.displayName, "OpenAndroMaps / OSM contributors", "OpenAndroMaps terms")
                }
                coordinator.sendPackage(layer.file, layer.kind.transferKind, layer.id, layer.name, layer.license, layer.attribution)
                workshop.statusText.text = "等高线已安装并加入手表发送队列"
                workshop.mapHost.reloadOfflineMap()
                renderCourse()
            }.onFailure { toast("等高线下载失败：${it.message}") }
        }
    }

    private fun showAuthorizedDownloadDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 8, 36, 0) }
        val url = EditText(this).apply { hint = "授权 HTTPS 下载地址"; inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val name = EditText(this).apply { hint = "文件名，例如 lake-2020.mbtiles" }
        val date = EditText(this).apply { hint = "拍摄日期或时期" }
        layout.addView(url); layout.addView(name); layout.addView(date)
        AlertDialog.Builder(this).setTitle("历史影像断点下载").setView(layout)
            .setPositiveButton("开始") { _, _ -> downloadAuthorizedLayer(url.text.toString(), name.text.toString(), date.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun downloadCurrentSatelliteMap() {
        val center = activeCourse?.points?.takeIf { it.isNotEmpty() }?.let { points ->
            GeoPoint(
                points.map { it.position.latitude }.average(),
                points.map { it.position.longitude }.average(),
            )
        } ?: currentPhoneLocation()
        if (center == null) {
            toast("暂无路线或定位，无法确定卫星地图下载区域")
            return
        }
        val settings = getSharedPreferences("map_download_settings", MODE_PRIVATE)
        if (settings.getBoolean("wifi_only", true) && !isWifiConnected()) {
            toast("下载设置为仅 Wi-Fi；连接 Wi-Fi 后再试")
            return
        }
        val radiusKm = settings.getFloat("radius_km", 15f).toDouble().coerceIn(1.0, 15.0)
        closePanel()
        workshop.statusText.text = "准备下载 Sentinel-2 2025 卫星底图…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val downloaded = CurrentSatelliteDownloader(this@MainActivity).download(center, radiusKm) { progress ->
                        runOnUiThread {
                            workshop.statusText.text =
                                "卫星底图 ${progress.completedTiles}/${progress.totalTiles} · ${formatBytes(progress.downloadedBytes)}"
                        }
                    }
                    try {
                        downloaded.inputStream().buffered().use { input ->
                            layerStore.import(
                                input = input,
                                originalName = downloaded.name,
                                kind = OfflineLayerKind.HISTORICAL_IMAGERY,
                                displayName = "当前卫星底图 · Sentinel-2 2025",
                                sourceDate = "2025 年度无云合成",
                                attribution = CurrentSatelliteDownloader.FULL_ATTRIBUTION,
                                license = CurrentSatelliteDownloader.LICENSE,
                            )
                        }
                    } finally {
                        // The registered layer is a verified private copy; avoid retaining a second MBTiles file.
                        downloaded.delete()
                    }
                }
            }
            result.onSuccess { layer ->
                coordinator.sendPackage(
                    layer.file,
                    layer.kind.transferKind,
                    layer.id,
                    layer.name,
                    layer.license,
                    layer.attribution,
                )
                workshop.mapHost.showRaster(layer.file)
                getSharedPreferences("phone_map_selection", MODE_PRIVATE).edit()
                    .putString("mode", "RASTER")
                    .putString("layer_id", layer.id)
                    .apply()
                workshop.statusText.text = "卫星底图已就绪并发送手表 · 半径 ${radiusKm.toInt()} km"
                renderCourse()
                toast("当前卫星底图已下载；历史影像可继续导入对比")
            }.onFailure { error ->
                toast("卫星底图下载暂停：${error.message}；再次点击将断点续传")
            }
        }
    }

    private fun isWifiConnected(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun downloadAuthorizedLayer(url: String, fileName: String, sourceDate: String) {
        closePanel(); workshop.statusText.text = "历史影像下载中…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = AuthorizedLayerDownloader(this@MainActivity).download(url, fileName) { current, total ->
                        runOnUiThread { workshop.statusText.text = "历史影像 ${formatBytes(current)} / ${formatBytes(total)}" }
                    }
                    file.inputStream().buffered().use {
                        layerStore.import(
                            it, file.name, OfflineLayerKind.HISTORICAL_IMAGERY,
                            sourceDate = sourceDate.ifBlank { null }, attribution = "Authorized user source",
                        )
                    }
                }
            }
            result.onSuccess { workshop.statusText.text = "历史影像已下载：${it.name}" }
                .onFailure { toast("下载失败：${it.message}") }
        }
    }

    private fun downloadPublicPoi() {
        val bounds = routeBounds(.08) ?: return
        closePanel(); workshop.statusText.text = "下载公共下钓点与设施…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PublicPoiRepository().download(PublicPoiRepository.Bounds(bounds.south, bounds.west, bounds.north, bounds.east))
                }
            }
            result.onSuccess { values ->
                values.forEach { waypointStore.upsert(it); coordinator.publishWaypoint(it, SyncEntityType.PUBLIC_POI) }
                workshop.statusText.text = "已保存并同步 ${values.size} 个公共标点"
            }.onFailure { toast("公共标点下载失败：${it.message}") }
        }
    }

    private fun showAddMarkDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 8, 36, 0) }
        val name = EditText(this).apply { hint = "名称或备注，例如：枯水期石坎" }
        val lat = EditText(this).apply { hint = "纬度"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }
        val lon = EditText(this).apply { hint = "经度"; inputType = lat.inputType }
        layout.addView(name); layout.addView(lat); layout.addView(lon)
        val systems = arrayOf("WGS-84", "GCJ-02", "BD-09")
        var selected = CoordinateSystem.WGS84
        AlertDialog.Builder(this).setTitle("新增钓点 / 备注").setView(layout)
            .setSingleChoiceItems(systems, 0) { _, which -> selected = CoordinateSystem.entries[which] }
            .setPositiveButton("保存") { _, _ ->
                runCatching {
                    val point = CoordinateConverter.toWgs84(GeoPoint(lat.text.toString().toDouble(), lon.text.toString().toDouble()), selected)
                    waypointStore.save(name.text.toString().ifBlank { "未命名钓点" }, point).also(coordinator::publishWaypoint)
                }.onSuccess { toast("钓点已保存并同步") }.onFailure { toast("坐标无效") }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showMarkActions(mark: SavedWaypoint) {
        AlertDialog.Builder(this).setTitle(mark.name)
            .setMessage("WGS-84\n${formatPoint(mark.point)}\n图片 ${waypointAttachments.photos(mark.id).size}")
            .setItems(arrayOf("修改文字", "添加图片", "设为路线起点", "规划到这里", "删除标记")) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply { setText(mark.name); selectAll() }
                        AlertDialog.Builder(this).setTitle("修改钓点 / 备注").setView(input)
                            .setPositiveButton("保存并同步") { _, _ ->
                                waypointStore.upsert(mark.copy(name = input.text.toString().trim().ifBlank { "未命名钓点" })).also(coordinator::publishWaypoint)
                                showMarksCenter()
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                    1 -> {
                        pendingWaypointPhotoId = mark.id
                        waypointPhotoLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                    }
                    2 -> { routePlanningStart = mark; toast("已设起点：${mark.name}") }
                    3 -> routePlanningStart?.let { planRoute(it, mark) } ?: chooseRouteStartForDestination(mark)
                    4 -> AlertDialog.Builder(this).setTitle("删除标记")
                        .setMessage("确定删除“${mark.name}”及其图片？")
                        .setPositiveButton("删除") { _, _ ->
                            waypointStore.delete(mark.id); waypointAttachments.delete(mark.id)
                            coordinator.deleteRecord(if (mark.id.startsWith("osm-")) SyncEntityType.PUBLIC_POI else SyncEntityType.WAYPOINT, mark.id)
                            showMarksCenter()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            }.setNegativeButton("关闭", null).show()
    }

    private fun showMarkProfile(waypointId: String) {
        val mark = waypointStore.list().firstOrNull { it.id == waypointId } ?: return
        val profile = waypointProfiles.find(mark.id)
        val recordings = fishingEntriesFor(mark)
        val photos = waypointAttachments.photos(mark.id)
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date(mark.createdAtMillis))
        val message = buildString {
            append("坐标：").append(formatPoint(mark.point)).append('\n')
            append("时间：").append(time).append('\n')
            append("垂钓录音：").append(recordings.size).append(" 条\n")
            append("图片：").append(photos.size).append(" 张\n")
            append("文字备注：").append(profile.displayedNotes.ifBlank { "暂无" })
        }
        val options = buildList {
            add("修改标点名字")
            add("编辑文字备注")
            add("添加图片")
            recordings.forEachIndexed { index, _ -> add("查看垂钓录音 ${index + 1}") }
            add("设为路线起点")
            add("规划到这里")
            add("删除标记")
        }
        val handleOption: (Int) -> Unit = { which ->
                when {
                    which == 0 -> renameWaypoint(mark)
                    which == 1 -> editWaypointNotes(mark)
                    which == 2 -> {
                        pendingWaypointPhotoId = mark.id
                        waypointPhotoLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                    }
                    which in 3 until 3 + recordings.size -> showFishingEntry(recordings[which - 3].id)
                    which == 3 + recordings.size -> { routePlanningStart = mark; toast("已设起点：${mark.name}") }
                    which == 4 + recordings.size -> routePlanningStart?.let { planRoute(it, mark) } ?: chooseRouteStartForDestination(mark)
                    else -> confirmDeleteWaypoint(mark)
                }
        }
        // Samsung's AlertDialog implementation drops setItems() when setMessage() is also used.
        // Build the profile actions explicitly so coordinates and every edit action remain visible.
        lateinit var dialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = message
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(12, 8, 12, 20)
            })
            options.forEachIndexed { index, label ->
                addView(android.widget.Button(this@MainActivity).apply {
                    text = label
                    isAllCaps = false
                    setTextColor(if (label == "删除标记") Color.rgb(255, 96, 72) else Color.WHITE)
                    setOnClickListener {
                        dialog.dismiss()
                        handleOption(index)
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(mark.name)
            .setView(android.widget.ScrollView(this).apply { addView(content) })
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
    }

    private fun renameWaypoint(mark: SavedWaypoint) {
        val input = EditText(this).apply { setText(mark.name); selectAll() }
        AlertDialog.Builder(this).setTitle("修改标点名字").setView(input)
            .setPositiveButton("保存并同步") { _, _ ->
                waypointStore.upsert(mark.copy(name = input.text.toString().trim().ifBlank { "未命名钓点" }))
                    .also(coordinator::publishWaypoint)
                showMarkProfile(mark.id)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun editWaypointNotes(mark: SavedWaypoint) {
        val input = EditText(this).apply {
            hint = "文字备注"; minLines = 4; gravity = Gravity.TOP
            setText(waypointProfiles.find(mark.id).notes)
        }
        AlertDialog.Builder(this).setTitle("${mark.name} · 文字备注").setView(input)
            .setPositiveButton("保存") { _, _ -> waypointProfiles.save(mark.id, input.text.toString()); showMarkProfile(mark.id) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirmDeleteWaypoint(mark: SavedWaypoint) {
        AlertDialog.Builder(this).setTitle("删除标记").setMessage("确定删除“${mark.name}”及其图片和备注？")
            .setPositiveButton("删除") { _, _ ->
                waypointStore.delete(mark.id); waypointAttachments.delete(mark.id); waypointProfiles.delete(mark.id)
                coordinator.deleteRecord(if (mark.id.startsWith("osm-")) SyncEntityType.PUBLIC_POI else SyncEntityType.WAYPOINT, mark.id)
                showMarksCenter()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun fishingEntriesFor(mark: SavedWaypoint) = fishingLogStore.list().filter { entryMatchesWaypoint(it, mark) }

    private fun entryMatchesWaypoint(entry: com.watchnav.mobile.data.MobileFishingEntry, mark: SavedWaypoint): Boolean {
        if (entry.id == mark.id) return true
        val point = entry.point ?: return false
        return kotlin.math.abs(entry.createdAtMillis - mark.createdAtMillis) <= 5 * 60_000L &&
            com.watchnav.core.GeoMath.distanceMeters(point, mark.point) <= 25.0
    }

    private fun currentPhoneLocation(): GeoPoint? {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = getSystemService(LocationManager::class.java)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude, it.altitude.takeIf { _ -> it.hasAltitude() }) }
    }

    private fun formatPoint(point: GeoPoint) = String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)

    private fun shareWaypoints(values: List<SavedWaypoint>) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                File(File(cacheDir, "shared").apply { mkdirs() }, "watchnav-waypoints.gpx").apply {
                    writeText(buildString {
                        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                        appendLine("<gpx version=\"1.1\" creator=\"WatchNav\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
                        values.forEach { value -> appendLine("  <wpt lat=\"${value.point.latitude}\" lon=\"${value.point.longitude}\"><name>${xml(value.name)}</name></wpt>") }
                        appendLine("</gpx>")
                    })
                }
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享标点"))
        }
    }

    private fun searchLocal(query: String) {
        val value = query.trim()
        val coordinate = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[,， ]\\s*(-?\\d+(?:\\.\\d+)?)\\s*$").matchEntire(value)
        if (coordinate != null) {
            val point = runCatching { GeoPoint(coordinate.groupValues[1].toDouble(), coordinate.groupValues[2].toDouble()) }.getOrNull()
            if (point == null) toast("坐标无效") else {
                val waypoint = waypointStore.save("搜索坐标", point)
                coordinator.publishWaypoint(waypoint)
                toast("坐标已保存为标点，可在标点页设为起点或终点")
                showMarksCenter()
            }
        } else {
            val localMatch = waypointStore.list().any { it.name.contains(value, ignoreCase = true) }
            val routeMatch = courseStore.listLibrary().any { it.name.contains(value, ignoreCase = true) }
            if (localMatch || routeMatch || value.isBlank()) showRouteLibrary(value) else searchDestination(value)
        }
    }

    @Suppress("DEPRECATION")
    private fun searchDestination(query: String) {
        workshop.statusText.text = "正在搜索目标点…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                Geocoder(this@MainActivity, Locale.getDefault()).getFromLocationName(query, 5).orEmpty()
                    .mapIndexed { index, address -> SavedWaypoint(
                        id = "search-${System.currentTimeMillis()}-$index",
                        name = address.featureName ?: address.getAddressLine(0) ?: query,
                        point = GeoPoint(address.latitude, address.longitude),
                        createdAtMillis = System.currentTimeMillis(),
                    ) }
            } }
            result.onSuccess { results ->
                if (results.isEmpty()) toast("未找到目标点：$query")
                else AlertDialog.Builder(this@MainActivity).setTitle("搜索目标点")
                    .setItems(results.map { "${it.name}\n${formatPoint(it.point)}" }.toTypedArray()) { _, index ->
                        val target = waypointStore.upsert(results[index]).also(coordinator::publishWaypoint)
                        chooseRouteStartForDestination(target)
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }.onFailure { toast("搜索失败：${it.message}") }
        }
    }

    private fun planRoute(start: SavedWaypoint, destination: SavedWaypoint) {
        if (start.id == destination.id) { toast("路线起点和终点不能相同"); return }
        closePanel(); workshop.statusText.text = "离线路线规划中…"
        lifecycleScope.launch {
            val request = RouteRequest(start.point, destination.point, profile = RoutingProfile.HIKE)
            when (val result = withContext(Dispatchers.IO) { BRouterOfflineRouter(this@MainActivity).route(request) }) {
                is RouteResult.Success -> {
                    val named = result.course.copy(name = "${start.name} → ${destination.name}")
                    withContext(Dispatchers.IO) { courseStore.saveCourse(named) }
                    activeCourse = named
                    renderCourse()
                    workshop.statusText.text = "路线规划完成 · ${result.calculationMillis} ms"
                }
                is RouteResult.MissingRegion -> AlertDialog.Builder(this@MainActivity)
                    .setTitle("需要下载路网")
                    .setMessage("缺少 ${result.regionIds.joinToString()}，是否由手机下载后重试？")
                    .setPositiveButton("下载并重试") { _, _ -> downloadMissingAndRetry(result.regionIds, start, destination) }
                    .setNegativeButton(android.R.string.cancel, null).show()
                is RouteResult.Failure -> toast("规划失败：${result.reason}")
            }
        }
    }

    private fun downloadMissingAndRetry(regions: Set<String>, start: SavedWaypoint, destination: SavedWaypoint) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { RoutingRegionDownloader(this@MainActivity).install(regions) { progress ->
                    runOnUiThread { workshop.statusText.text = "路网 ${progress.regionId} ${formatBytes(progress.downloadedBytes)}" }
                } }
            }
            result.onSuccess { files ->
                files.forEach { coordinator.sendPackage(it, com.watchnav.sync.protocol.TransferKind.BROUTER_RD5, it.nameWithoutExtension, it.name) }
                planRoute(start, destination)
            }.onFailure { toast("路网下载失败：${it.message}") }
        }
    }

    private fun routeBounds(margin: Double = .02): RegionBounds? {
        val points = activeCourse?.points.orEmpty()
        if (points.isEmpty()) return currentPhoneLocation()?.let { point ->
            val longitudeMargin = margin / kotlin.math.cos(Math.toRadians(point.latitude)).coerceAtLeast(.2)
            RegionBounds(
                (point.latitude - margin).coerceAtLeast(-89.9),
                (point.longitude - longitudeMargin).coerceAtLeast(-179.9),
                (point.latitude + margin).coerceAtMost(89.9),
                (point.longitude + longitudeMargin).coerceAtMost(179.9),
            )
        }
        return RegionBounds(
            (points.minOf { it.position.latitude } - margin).coerceAtLeast(-89.9),
            (points.minOf { it.position.longitude } - margin).coerceAtLeast(-179.9),
            (points.maxOf { it.position.latitude } + margin).coerceAtMost(89.9),
            (points.maxOf { it.position.longitude } + margin).coerceAtMost(179.9),
        )
    }

    private fun courseToKml(course: Course) = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>${xml(course.name)}</name>")
        course.landmarks.forEach { point ->
            appendLine("<Placemark><name>${xml(point.name ?: "标点")}</name><Point><coordinates>${point.position.longitude},${point.position.latitude},${point.position.elevationMeters ?: 0.0}</coordinates></Point></Placemark>")
        }
        append("<Placemark><name>${xml(course.name)}</name><LineString><coordinates>")
        course.points.forEach { point -> append("${point.position.longitude},${point.position.latitude},${point.position.elevationMeters ?: 0.0} ") }
        appendLine("</coordinates></LineString></Placemark></Document></kml>")
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun formatBytes(value: Long): String = when {
        value < 0 -> "未知"
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        value < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", value / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.2f GB", value / 1024.0 / 1024.0 / 1024.0)
    }

    private fun Course.isBundledLongtanDemo(): Boolean {
        if (name != LEGACY_DEMO_COURSE_NAME || points.size != 20) return false
        val first = points.first().position
        val last = points.last().position
        return kotlin.math.abs(first.latitude - 30.245) < 0.000001 &&
            kotlin.math.abs(first.longitude - 120.135) < 0.000001 &&
            kotlin.math.abs(last.latitude - 30.245) < 0.000001 &&
            kotlin.math.abs(last.longitude - 120.135) < 0.000001
    }

    private fun com.watchnav.mobile.data.OfflineLayer.kindLabel() = when (kind) {
        OfflineLayerKind.BASE_MAP -> "基础地图"; OfflineLayerKind.CONTOUR -> "等高线"
        OfflineLayerKind.HISTORICAL_IMAGERY -> "历史卫星影像"; OfflineLayerKind.ENC_CHART -> "离线海图"
        OfflineLayerKind.BATHYMETRY -> "等深线 / 水深"; OfflineLayerKind.WATER_BOUNDARY -> "水体边界"
        OfflineLayerKind.PUBLIC_POI -> "公共设施"
    }

    private fun powerModeLabel(value: String) = when (value) { "ACCURATE" -> "准确"; "EXPEDITION" -> "远征"; else -> "均衡" }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximum) { "File is larger than ${maximum / 1024 / 1024} MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun extractKml(bytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".kml", true)) return readBounded(zip, MAX_IMPORT_BYTES)
            }
        }
        error("KMZ contains no KML document")
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-').take(60).ifBlank { "watchnav-route" }

    private companion object {
        const val LEGACY_DEMO_COURSE_NAME = "龙潭水库环线"
        const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
        const val MAXIMUM_MAP_WAYPOINTS = 300
        const val MAXIMUM_PANEL_WAYPOINTS = 200
        val CYAN = Color.rgb(0, 220, 236)
        val ORANGE = Color.rgb(255, 98, 0)
        val PURPLE = Color.rgb(159, 72, 255)
    }
}
