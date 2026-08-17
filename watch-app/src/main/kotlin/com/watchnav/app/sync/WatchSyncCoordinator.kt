package com.watchnav.app.sync

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import com.watchnav.app.BuildConfig
import com.watchnav.app.FishingEnvironmentStore
import com.watchnav.app.TideForecastRepository
import com.watchnav.brouter.BRouterOfflineRouter
import com.watchnav.mapsforge.MapsforgeMapRepository
import com.watchnav.mapsforge.VectorMapLayerKind
import com.watchnav.session.CourseFileStore
import com.watchnav.session.TrackStore
import com.watchnav.session.WaypointStore
import com.watchnav.session.FavoriteTargetStore
import com.watchnav.sync.protocol.DeviceHello
import com.watchnav.sync.protocol.DeviceRole
import com.watchnav.sync.protocol.SyncCapability
import com.watchnav.sync.protocol.SyncEntityType
import com.watchnav.sync.protocol.SyncProtocol
import com.watchnav.sync.protocol.TransferKind
import com.watchnav.sync.transport.OutgoingTransferManager
import com.watchnav.sync.transport.SyncRecordStore
import com.watchnav.sync.transport.WearSyncTransport
import com.watchnav.sync.transport.TransferredArtifactRegistry
import com.watchnav.sync.transport.LanSyncPeer
import com.watchnav.sync.transport.LanSyncSnapshot
import com.watchnav.sync.transport.ReceivedTransfer
import com.watchnav.sync.transport.MergeResult
import com.watchnav.sync.protocol.SyncRecord
import com.watchnav.sync.protocol.TransferManifest
import com.watchnav.sync.protocol.TransferState
import com.watchnav.session.NavigationSessionService
import com.watchnav.session.NavigationPowerMode
import com.watchnav.session.NavigationStateHub
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class WatchSyncCoordinator(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val records = SyncRecordStore(app, "watchnav-watch-sync.db")
    private val transport = WearSyncTransport(app)
    private val outgoing = OutgoingTransferManager(app)
    private val artifacts = TransferredArtifactRegistry(app)
    private val io = Executors.newSingleThreadExecutor()
    private val identity = app.getSharedPreferences("watch_identity", Context.MODE_PRIVATE)

    fun announce() {
        io.execute {
            runCatching { snapshotLocalData() }
            transport.connectedNodes().addOnSuccessListener { nodes ->
                transport.publishHello(hello())
                transport.publishInventory(deviceId(), records.inventory())
                records.recordsForSync().forEach { transport.publishRecord(it) }
                outgoing.pendingManifests().forEach { manifest ->
                    nodes.forEach { node -> outgoing.offer(node, manifest) }
                }
            }
        }
    }

    fun announceLightweight() {
        io.execute {
            runCatching { snapshotSmallData() }
            transport.connectedNodes().addOnSuccessListener { nodes ->
                transport.publishHello(hello())
                transport.publishInventory(deviceId(), records.inventory())
                records.recordsForSync().forEach { transport.publishRecord(it) }
                outgoing.pendingManifests().forEach { manifest -> nodes.forEach { node -> outgoing.offer(node, manifest) } }
            }
        }
    }

    fun requestSync() {
        announce()
        transport.connectedNodes().addOnSuccessListener { nodes -> nodes.forEach { transport.requestSync(it.id) } }
    }

    fun reconcileMapsThen(onComplete: () -> Unit) {
        io.execute {
            runCatching { reconcileTransferredVectorMaps(MapsforgeMapRepository(app)) }
            onComplete()
        }
    }

    fun lanPeer(): LanSyncPeer = object : LanSyncPeer {
        override fun snapshot(): LanSyncSnapshot {
            snapshotLocalData()
            return LanSyncSnapshot(hello(), records.recordsForSync(), outgoing.pendingFiles())
        }

        override fun receiveHello(value: DeviceHello) {
            if (value.role == DeviceRole.PHONE) records.saveDevice(value)
        }

        override fun receiveRecord(value: SyncRecord) {
            when (records.merge(value)) {
                MergeResult.INSERTED, MergeResult.UPDATED -> applyLanRecord(value)
                else -> Unit
            }
        }

        override fun alreadyHas(manifest: TransferManifest): Boolean {
            if (manifest.kind == TransferKind.COURSE_FILE && manifest.packageId == ACTIVE_COURSE_ID) return false
            return artifacts.checkpointIfAvailable(manifest) != null
        }

        override fun receiveArtifact(value: ReceivedTransfer) = installLanArtifact(value)
    }

    private fun snapshotLocalData() {
        snapshotSmallData()
        snapshotOfflineArtifacts()
    }

    private fun snapshotSmallData() {
        val courseStore = CourseFileStore(app)
        courseStore.listLibrary().forEach { entry ->
            val id = "library-${entry.id}"
            if (entry.file.length() <= MAX_INLINE_PAYLOAD) {
                upsert(SyncEntityType.COURSE, id, "application/gpx+xml", entry.file.readBytes())
            } else {
                registerArtifact(entry.file, TransferKind.COURSE_FILE, id, entry.name)
            }
        }
        courseStore.activeFile()?.takeIf { courseStore.activeLibraryOrigin() != null }?.let { file ->
            if (file.length() <= MAX_INLINE_PAYLOAD) {
                upsert(SyncEntityType.COURSE, ACTIVE_COURSE_ID, "application/gpx+xml", file.readBytes())
            } else {
                outgoing.enqueue(file, TransferKind.COURSE_FILE, ACTIVE_COURSE_ID, courseStore.load()?.course?.name ?: "Active route")
            }
        }
        replaceCollection(
            SyncEntityType.WAYPOINT,
            WaypointStore(app).list().associate { it.id to WatchSyncPayloads.waypoint(it) },
            "application/vnd.watchnav.waypoint+json",
        )
        replaceCollection(
            SyncEntityType.FISHING_CATCH,
            FishingEnvironmentStore(app).listCatches().associate { it.id to WatchSyncPayloads.fishingCatch(it) },
            "application/vnd.watchnav.fishing-catch+json",
        )
        val display = app.getSharedPreferences(DISPLAY_PREFERENCES, Context.MODE_PRIVATE)
        val settings = JSONObject().apply {
            put("alwaysOn", display.getBoolean(KEY_ALWAYS_ON, false))
            put("powerMode", display.getString(KEY_POWER_MODE, "BALANCED"))
            put("offRouteRerouteMeters", 100)
            put("favoriteTargets", FavoriteTargetStore(app).encode())
        }.toString().toByteArray()
        upsert(SyncEntityType.SETTING, DISPLAY_SETTINGS_ID, "application/json", settings)

        TideForecastRepository(app).exportCache()?.let { bytes ->
            if (bytes.size <= MAX_INLINE_PAYLOAD) upsert(SyncEntityType.TIDE_FORECAST, TIDE_ID, "application/json", bytes)
            else enqueueBytes(bytes, TransferKind.TIDE_CACHE, TIDE_ID, "Tide forecast")
        }
        runCatching {
            val output = ByteArrayOutputStream()
            TrackStore(app).exportGpx(output)
            val bytes = output.toByteArray()
            if (bytes.size <= MAX_INLINE_PAYLOAD) upsert(SyncEntityType.ACTIVITY, LATEST_ACTIVITY_ID, "application/gpx+xml", bytes)
            else enqueueBytes(bytes, TransferKind.ACTIVITY_FILE, LATEST_ACTIVITY_ID, "Latest activity")
        }
        FishingEnvironmentStore(app).listCatches().forEach { entry ->
            val audio = entry.audioFileName?.let { File(app.filesDir, "fishing/audio/$it") }
            if (audio?.isFile == true) registerArtifact(audio, TransferKind.FISHING_AUDIO, entry.id, "Fishing audio ${entry.id.take(8)}")
        }
    }

    private fun snapshotOfflineArtifacts() {
        val mapRepository = MapsforgeMapRepository(app)
        reconcileTransferredVectorMaps(mapRepository)
        mapRepository.layers().forEach { layer ->
            val transferKind = if (layer.kind == VectorMapLayerKind.CONTOUR) {
                TransferKind.CONTOUR_MAP
            } else {
                TransferKind.MAPSFORGE
            }
            registerArtifact(layer.file, transferKind, layer.id, layer.name)
        }
        val router = BRouterOfflineRouter(app)
        router.installedRegionIds().forEach { id ->
            registerArtifact(router.regionFile(id), TransferKind.BROUTER_RD5, id, "$id routing")
        }
        TransferKind.entries.filterNot {
            it in setOf(TransferKind.MAPSFORGE, TransferKind.CONTOUR_MAP, TransferKind.BROUTER_RD5)
        }.forEach { kind ->
            val directory = File(app.filesDir, "sync/received/${kind.name.lowercase()}")
            directory.listFiles()?.filter(File::isFile)?.forEach { file ->
                registerArtifact(file, kind, "watch-${kind.name.lowercase()}-${file.nameWithoutExtension}", file.nameWithoutExtension)
            }
        }
    }

    /**
     * Older builds could finish a large map transfer and record it in the artifact inventory
     * without adding it to the newer content-addressed layer catalog.  In that state the phone
     * correctly reports the package as installed, but the watch layer switcher cannot see it and
     * repeatedly asks the user to download the same file.  The coordinator already runs on its
     * private I/O executor, so repairing the catalog here also keeps hashing/copying large maps
     * away from the UI thread.
     */
    private fun reconcileTransferredVectorMaps(mapRepository: MapsforgeMapRepository) {
        val known = mapRepository.layers()
            .map { it.kind to it.sha256 }
            .toMutableSet()
        artifacts.entries()
            .asSequence()
            .filter { it.kind == TransferKind.MAPSFORGE || it.kind == TransferKind.CONTOUR_MAP }
            .sortedBy { it.updatedAtMillis }
            .forEach { entry ->
                val kind = if (entry.kind == TransferKind.CONTOUR_MAP) {
                    VectorMapLayerKind.CONTOUR
                } else {
                    VectorMapLayerKind.BASE
                }
                if ((kind to entry.sha256) in known) return@forEach
                val source = File(entry.filePath)
                runCatching {
                    mapRepository.registerVerified(source, kind, entry.displayName, activate = false)
                }.onSuccess { restored ->
                    known += restored.kind to restored.sha256
                    if (mapRepository.selected(kind) == null) {
                        mapRepository.select(kind, restored.id)
                    }
                }
            }
    }

    private fun registerArtifact(
        file: File,
        kind: TransferKind,
        packageId: String,
        displayName: String,
    ) {
        if (!file.isFile || file.length() <= 0) return
        val canonicalPath = file.canonicalPath
        val alreadyRegistered = artifacts.entries().firstOrNull { entry ->
            entry.kind == kind && entry.filePath == canonicalPath && entry.totalBytes == file.length() &&
                entry.updatedAtMillis >= file.lastModified()
        }
        val manifest = if (alreadyRegistered != null) {
            outgoing.enqueueVerified(
                file,
                kind,
                alreadyRegistered.packageId,
                alreadyRegistered.displayName,
                alreadyRegistered.sha256,
                alreadyRegistered.totalBytes,
            )
        } else {
            outgoing.enqueue(file, kind, packageId, displayName)
        }
        val state = if (kind in setOf(
                TransferKind.MAPSFORGE,
                TransferKind.CONTOUR_MAP,
                TransferKind.BROUTER_RD5,
                TransferKind.COURSE_FILE,
            )
        ) com.watchnav.sync.protocol.TransferState.INSTALLED
        else com.watchnav.sync.protocol.TransferState.RECEIVED
        artifacts.record(manifest, file, state)
    }

    private fun replaceCollection(type: SyncEntityType, current: Map<String, ByteArray>, contentType: String) {
        current.forEach { (id, payload) -> upsert(type, id, contentType, payload) }
        records.records(type).filterNot { it.deleted || current.containsKey(it.id) }.forEach { old ->
            records.upsertLocal(type, old.id, DeviceRole.WATCH, old.contentType, byteArrayOf(), deleted = true)
        }
    }

    private fun upsert(type: SyncEntityType, id: String, contentType: String, payload: ByteArray) {
        if (payload.size <= MAX_INLINE_PAYLOAD) records.upsertLocal(type, id, DeviceRole.WATCH, contentType, payload)
    }

    private fun enqueueBytes(bytes: ByteArray, kind: TransferKind, id: String, displayName: String) {
        val directory = File(app.filesDir, "sync/outgoing").apply { mkdirs() }
        val file = File(directory, "$id.bin")
        file.writeBytes(bytes)
        outgoing.enqueue(file, kind, id, displayName)
    }

    private fun applyLanRecord(record: SyncRecord) {
        when (record.type) {
            SyncEntityType.COURSE -> if (!record.deleted) {
                if (record.id == ACTIVE_COURSE_ID) {
                    CourseFileStore(app).save(record.payload, "Phone route")
                    startReceivedCourse()
                } else CourseFileStore(app).saveToLibraryOnly(record.payload, "Phone route")
            }
            SyncEntityType.WAYPOINT, SyncEntityType.PUBLIC_POI -> {
                if (record.deleted) WaypointStore(app).delete(record.id)
                else WaypointStore(app).upsert(WatchSyncPayloads.decodeWaypoint(record.payload))
            }
            SyncEntityType.FISHING_CATCH -> {
                if (record.deleted) FishingEnvironmentStore(app).deleteCatch(record.id)
                else FishingEnvironmentStore(app).upsertCatch(WatchSyncPayloads.decodeFishingCatch(record.payload))
            }
            SyncEntityType.SETTING -> if (!record.deleted) {
                val json = JSONObject(record.payload.toString(Charsets.UTF_8))
                val powerMode = json.optString("powerMode", "BALANCED")
                    .takeIf { it in setOf("ACCURATE", "BALANCED", "EXPEDITION") } ?: "BALANCED"
                app.getSharedPreferences(DISPLAY_PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_ALWAYS_ON, json.optBoolean("alwaysOn", false))
                    .putString(KEY_POWER_MODE, powerMode).apply()
                json.optJSONArray("favoriteTargets")?.let { FavoriteTargetStore(app).decodeAndReplace(it) }
            }
            SyncEntityType.TIDE_FORECAST -> if (!record.deleted) TideForecastRepository(app).importCache(record.payload)
            SyncEntityType.ACTIVITY, SyncEntityType.LAYER_SOURCE -> Unit
        }
    }

    private fun installLanArtifact(value: ReceivedTransfer) {
        val installedFile = when (value.manifest.kind) {
            TransferKind.MAPSFORGE, TransferKind.CONTOUR_MAP -> MapsforgeMapRepository(app).registerVerified(
                value.file,
                if (value.manifest.kind == TransferKind.CONTOUR_MAP) VectorMapLayerKind.CONTOUR else VectorMapLayerKind.BASE,
                value.manifest.displayName,
                activate = false,
            ).file
            TransferKind.BROUTER_RD5 -> value.file.inputStream().buffered().use {
                BRouterOfflineRouter(app).importRegion(value.manifest.packageId, it)
            }
            TransferKind.COURSE_FILE -> value.file.inputStream().buffered().use { input ->
                val bytes = input.readBytes()
                if (value.manifest.packageId == ACTIVE_COURSE_ID) {
                    CourseFileStore(app).save(bytes, value.manifest.displayName)
                } else CourseFileStore(app).saveToLibraryOnly(bytes, value.manifest.displayName)
                value.file
            }
            TransferKind.TIDE_CACHE -> TideForecastRepository(app).run {
                importCache(value.file.readBytes()); value.file
            }
            else -> value.file
        }
        val installed = value.manifest.kind in setOf(
            TransferKind.MAPSFORGE, TransferKind.CONTOUR_MAP, TransferKind.BROUTER_RD5,
            TransferKind.COURSE_FILE, TransferKind.HISTORICAL_RASTER, TransferKind.TIDE_CACHE,
            TransferKind.ACTIVITY_FILE,
        )
        artifacts.record(value.manifest, installedFile, if (installed) TransferState.INSTALLED else TransferState.RECEIVED)
        if (value.manifest.kind == TransferKind.COURSE_FILE && value.manifest.packageId == ACTIVE_COURSE_ID) {
            startReceivedCourse()
        }
    }

    private fun startReceivedCourse() {
        NavigationStateHub.resetToIdle()
        Handler(Looper.getMainLooper()).post {
            runCatching { NavigationSessionService.start(app, powerMode = NavigationPowerMode.BALANCED) }
                .onFailure { Log.e("WatchNavLanSync", "Unable to start received course", it) }
        }
    }

    fun hello(): DeviceHello {
        val battery = app.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        return DeviceHello(
            deviceId = deviceId(),
            role = DeviceRole.WATCH,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            freeBytes = StatFs(app.filesDir.absolutePath).availableBytes,
            batteryPercent = battery,
            capabilities = setOf(
                SyncCapability.ENTITY_SYNC, SyncCapability.SETTINGS_SYNC, SyncCapability.COURSE_SYNC,
                SyncCapability.FISHING_SYNC, SyncCapability.ACTIVITY_SYNC, SyncCapability.TIDE_SYNC,
                SyncCapability.LARGE_FILE_TRANSFER, SyncCapability.RESUMABLE_TRANSFER,
                SyncCapability.PACKAGE_INSTALL, SyncCapability.HYDRO_LAYERS, SyncCapability.POI_INDEX,
            ),
        )
    }

    private fun deviceId(): String = identity.getString(KEY_DEVICE_ID, null) ?: "watch-${UUID.randomUUID()}".also {
        identity.edit().putString(KEY_DEVICE_ID, it).apply()
    }

    override fun close() {
        io.shutdownNow()
        outgoing.close()
        transport.close()
        records.close()
    }

    companion object {
        internal const val ACTIVE_COURSE_ID = "active-course"
        private const val MAX_INLINE_PAYLOAD = SyncProtocol.MAX_DATA_ITEM_BYTES - 8 * 1024
        private const val DISPLAY_SETTINGS_ID = "display-settings"
        private const val TIDE_ID = "latest-tide"
        private const val LATEST_ACTIVITY_ID = "latest-activity"
        private const val DISPLAY_PREFERENCES = "display_preferences"
        private const val KEY_ALWAYS_ON = "always_on"
        private const val KEY_POWER_MODE = "power_mode"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
