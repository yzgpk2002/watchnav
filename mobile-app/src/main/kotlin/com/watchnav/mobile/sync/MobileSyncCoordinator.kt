package com.watchnav.mobile.sync

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import android.util.Log
import com.google.android.gms.wearable.Node
import com.watchnav.mobile.BuildConfig
import com.watchnav.mobile.data.MobileSyncRepository
import com.watchnav.mobile.data.OfflineLayerKind
import com.watchnav.mobile.data.OfflineLayerStore
import com.watchnav.brouter.BRouterOfflineRouter
import com.watchnav.mapsforge.MapsforgeMapRepository
import com.watchnav.sync.protocol.DeviceHello
import com.watchnav.sync.protocol.DeviceRole
import com.watchnav.sync.protocol.SyncCapability
import com.watchnav.sync.protocol.SyncEntityType
import com.watchnav.sync.protocol.SyncProtocol
import com.watchnav.sync.protocol.SyncRecord
import com.watchnav.sync.protocol.TransferKind
import com.watchnav.sync.transport.OutgoingTransferManager
import com.watchnav.sync.transport.WearSyncTransport
import com.watchnav.sync.transport.TransferredArtifactRegistry
import com.watchnav.sync.transport.LanSyncPeer
import com.watchnav.sync.transport.LanSyncSnapshot
import com.watchnav.sync.transport.LanSyncClient
import com.watchnav.sync.transport.LanSyncResult
import com.watchnav.sync.transport.ReceivedTransfer
import com.watchnav.sync.transport.MergeResult
import com.watchnav.mobile.speech.FishingAudioTranscriptionManager
import com.watchnav.session.WaypointStore
import com.watchnav.core.GeoPoint
import java.io.File
import java.util.UUID
import com.watchnav.session.SavedWaypoint
import com.watchnav.session.FavoriteTargetStore
import com.watchnav.session.CourseFileStore
import com.watchnav.mobile.data.MobileFishingEntry
import com.watchnav.mobile.data.MobileFishingLogStore
import org.json.JSONObject
import java.util.concurrent.Executors

class MobileSyncCoordinator(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val repository = MobileSyncRepository(applicationContext)
    private val transport = WearSyncTransport(applicationContext)
    private val outgoing = OutgoingTransferManager(applicationContext)
    private val artifacts = TransferredArtifactRegistry(applicationContext)
    private val io = Executors.newSingleThreadExecutor()
    private val preferences = applicationContext.getSharedPreferences("companion_identity", Context.MODE_PRIVATE)

    fun announce(onResult: (List<Node>) -> Unit = {}) {
        io.execute {
            runCatching { snapshotOfflineArtifacts() }
            transport.connectedNodes()
                .addOnSuccessListener { nodes ->
                    transport.publishHello(hello())
                    transport.publishInventory(deviceId(), repository.inventory())
                    repository.recordsForSync().forEach { transport.publishRecord(it) }
                    outgoing.pendingManifests().forEach { manifest ->
                        nodes.forEach { node -> outgoing.offer(node, manifest) }
                    }
                    onResult(nodes)
                }
                .addOnFailureListener { onResult(emptyList()) }
        }
    }

    fun publishCourse(file: File, onDelivery: ((Boolean) -> Unit)? = null) {
        require(file.isFile)
        // This entry point is used by the explicit "Send to watch" action.  Mark it as the
        // active course so the watch can distinguish it from passive library synchronisation.
        val id = ACTIVE_COURSE_ID
        // Explicit user intent must win over sync-conflict timestamps.  Always use the verified
        // transfer channel here; an inline SyncRecord can be rejected as LOCAL_NEWER when the
        // watch recently recorded or activated a route with the same logical id.
        val manifest = outgoing.enqueue(
            file,
            TransferKind.COURSE_FILE,
            id,
            file.nameWithoutExtension,
            forceNewTransfer = true,
        )
        outgoing.offerToAll(manifest) { count ->
            if (count > 0) onDelivery?.invoke(true)
            else syncLan { result -> onDelivery?.invoke(result.isSuccess) }
        }
    }

    fun publishWaypoint(value: SavedWaypoint, type: SyncEntityType = SyncEntityType.WAYPOINT) {
        require(type == SyncEntityType.WAYPOINT || type == SyncEntityType.PUBLIC_POI)
        val payload = JSONObject().apply {
            put("id", value.id); put("name", value.name)
            put("latitude", value.point.latitude); put("longitude", value.point.longitude)
            put("elevation", value.point.elevationMeters ?: JSONObject.NULL)
            put("createdAtMillis", value.createdAtMillis)
        }.toString().toByteArray()
        transport.publishRecord(
            repository.upsertLocal(type, value.id, "application/vnd.watchnav.waypoint+json", payload),
            urgent = true,
        )
    }

    fun publishSettings(alwaysOn: Boolean, powerMode: String) {
        val payload = JSONObject().apply {
            put("alwaysOn", alwaysOn)
            put("powerMode", powerMode.takeIf { it in setOf("ACCURATE", "BALANCED", "EXPEDITION") } ?: "BALANCED")
            put("offRouteRerouteMeters", 100)
            put("favoriteTargets", FavoriteTargetStore(applicationContext).encode())
        }.toString().toByteArray()
        transport.publishRecord(
            repository.upsertLocal(SyncEntityType.SETTING, "display-settings", "application/json", payload),
            urgent = true,
        )
    }

    fun publishFishingEntry(value: MobileFishingEntry) {
        val payload = MobileFishingLogStore(applicationContext).payload(value)
        transport.publishRecord(
            repository.upsertLocal(SyncEntityType.FISHING_CATCH, value.id, "application/vnd.watchnav.fishing-catch+json", payload),
            urgent = true,
        )
    }

    fun artifactEntries() = artifacts.entries()

    fun sendPackage(
        file: File,
        kind: TransferKind,
        packageId: String,
        displayName: String,
        license: String = "",
        attribution: String = "",
    ) {
        val manifest = outgoing.enqueue(file, kind, packageId, displayName, license, attribution)
        artifacts.record(manifest, file, localState(kind))
        outgoing.offerToAll(manifest) { count -> if (count == 0) syncLan() }
    }

    fun requestSync(onResult: (Int) -> Unit = {}) {
        announce { nodes ->
            if (nodes.isNotEmpty()) {
                nodes.forEach { transport.requestSync(it.id) }
                onResult(nodes.size)
            } else {
                syncLan { result -> onResult(if (result.isSuccess) 1 else 0) }
            }
        }
    }

    fun syncLan(onResult: (Result<LanSyncResult>) -> Unit = {}) {
        io.execute {
            val result = runCatching { LanSyncClient(applicationContext, lanPeer()).sync() }
            result.onSuccess { value ->
                Log.i(TAG, "Local Wi-Fi sync complete: $value")
                preferences.edit()
                    .putLong(KEY_LAST_LAN_SYNC, System.currentTimeMillis())
                    .putString(KEY_LAST_LAN_PEER, value.peerAddress)
                    .apply()
            }
            result.onFailure { Log.w(TAG, "Local Wi-Fi sync unavailable", it) }
            onResult(result)
        }
    }

    fun recentLanPeer(maximumAgeMillis: Long = 5 * 60 * 1_000L): String? {
        val at = preferences.getLong(KEY_LAST_LAN_SYNC, 0L)
        if (System.currentTimeMillis() - at !in 0..maximumAgeMillis) return null
        return preferences.getString(KEY_LAST_LAN_PEER, null)
    }

    fun lanPeer(): LanSyncPeer = object : LanSyncPeer {
        override fun snapshot(): LanSyncSnapshot {
            snapshotOfflineArtifacts()
            return LanSyncSnapshot(hello(), repository.recordsForSync(), outgoing.pendingFiles())
        }

        override fun receiveHello(value: DeviceHello) {
            if (value.role == DeviceRole.WATCH) repository.saveDevice(value)
        }

        override fun receiveRecord(value: SyncRecord) {
            val result = repository.merge(value)
            if (result == MergeResult.INSERTED || result == MergeResult.UPDATED ||
                (value.type == SyncEntityType.FISHING_CATCH && !value.deleted)
            ) applyLanRecord(value)
            CompanionStateBus.emit()
        }

        override fun alreadyHas(manifest: com.watchnav.sync.protocol.TransferManifest): Boolean =
            artifacts.checkpointIfAvailable(manifest) != null

        override fun receiveArtifact(value: ReceivedTransfer) {
            installLanArtifact(value)
            CompanionStateBus.emit()
        }
    }

    fun connectedNodes(onResult: (List<Node>) -> Unit) {
        transport.connectedNodes()
            .addOnSuccessListener(onResult)
            .addOnFailureListener { error ->
                Log.w(TAG, "Wear node query failed", error)
                onResult(emptyList())
            }
    }

    fun latestWatch() = repository.latestDevice(DeviceRole.WATCH)

    fun records(type: SyncEntityType) = repository.records(type)

    fun deleteRecord(type: SyncEntityType, id: String, contentType: String = "application/octet-stream") {
        transport.publishRecord(repository.upsertLocal(type, id, contentType, byteArrayOf(), deleted = true), urgent = true)
    }

    fun hello(): DeviceHello {
        val battery = applicationContext.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        return DeviceHello(
            deviceId = deviceId(),
            role = DeviceRole.PHONE,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            freeBytes = StatFs(applicationContext.filesDir.absolutePath).availableBytes,
            batteryPercent = battery,
            capabilities = setOf(
                SyncCapability.ENTITY_SYNC,
                SyncCapability.SETTINGS_SYNC,
                SyncCapability.COURSE_SYNC,
                SyncCapability.FISHING_SYNC,
                SyncCapability.ACTIVITY_SYNC,
                SyncCapability.TIDE_SYNC,
                SyncCapability.LARGE_FILE_TRANSFER,
                SyncCapability.RESUMABLE_TRANSFER,
                SyncCapability.PACKAGE_INSTALL,
                SyncCapability.HISTORICAL_RASTER,
                SyncCapability.HYDRO_LAYERS,
                SyncCapability.POI_INDEX,
            ),
        )
    }

    private fun deviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        return "phone-${UUID.randomUUID()}".also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    override fun close() {
        io.shutdownNow()
        outgoing.close()
        repository.close()
        transport.close()
    }

    private fun snapshotOfflineArtifacts() {
        snapshotCourseLibrary()
        val layers = OfflineLayerStore(applicationContext).list()
        layers.forEach { layer ->
            registerArtifact(
                layer.file,
                layer.kind.transferKind,
                layer.id,
                layer.name,
                layer.license,
                layer.attribution,
            )
        }
        // A contour overlay is not a base map. Secure Folder may have only a registered
        // contour layer plus a separately installed active Mapsforge base map.
        if (layers.none { it.kind == OfflineLayerKind.BASE_MAP }) {
            MapsforgeMapRepository(applicationContext).activeMap()?.let { file ->
                registerArtifact(file, TransferKind.MAPSFORGE, "active-map", "Phone offline map")
            }
        }
        val router = BRouterOfflineRouter(applicationContext)
        router.installedRegionIds().forEach { id ->
            registerArtifact(router.regionFile(id), TransferKind.BROUTER_RD5, id, "$id routing")
        }
    }

    private fun snapshotCourseLibrary() {
        CourseFileStore(applicationContext).listLibrary().forEach { entry ->
            val id = "library-${entry.id}"
            if (entry.file.length() <= MAX_INLINE_PAYLOAD) {
                repository.upsertLocal(
                    SyncEntityType.COURSE,
                    id,
                    "application/gpx+xml",
                    entry.file.readBytes(),
                )
            } else {
                registerArtifact(entry.file, TransferKind.COURSE_FILE, id, entry.name)
            }
        }
    }

    private fun registerArtifact(
        file: File,
        kind: TransferKind,
        packageId: String,
        displayName: String,
        license: String = "",
        attribution: String = "",
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
                license,
                attribution,
            )
        } else {
            outgoing.enqueue(file, kind, packageId, displayName, license, attribution)
        }
        artifacts.record(manifest, file, localState(kind))
    }

    private fun localState(kind: TransferKind): com.watchnav.sync.protocol.TransferState =
        if (kind in setOf(
                TransferKind.MAPSFORGE,
                TransferKind.CONTOUR_MAP,
                TransferKind.HISTORICAL_RASTER,
                TransferKind.ENC_CHART,
                TransferKind.BATHYMETRY,
                TransferKind.WATER_BOUNDARY,
                TransferKind.POI_INDEX,
                TransferKind.BROUTER_RD5,
                TransferKind.COURSE_FILE,
            )
        ) com.watchnav.sync.protocol.TransferState.INSTALLED
        else com.watchnav.sync.protocol.TransferState.RECEIVED

    private fun applyLanRecord(record: SyncRecord) {
        when (record.type) {
            SyncEntityType.COURSE -> if (!record.deleted) CourseFileStore(applicationContext)
                .save(record.payload, "Watch route")
            SyncEntityType.WAYPOINT, SyncEntityType.PUBLIC_POI -> {
                if (record.deleted) WaypointStore(applicationContext).delete(record.id) else {
                    val json = JSONObject(record.payload.toString(Charsets.UTF_8))
                    WaypointStore(applicationContext).upsert(SavedWaypoint(
                        json.getString("id"), json.getString("name"),
                        GeoPoint(
                            json.getDouble("latitude"), json.getDouble("longitude"),
                            json.optDouble("elevation").takeIf { !it.isNaN() },
                        ),
                        json.getLong("createdAtMillis"),
                    ))
                }
                FishingAudioTranscriptionManager.linkCompletedTranscripts(applicationContext)
            }
            SyncEntityType.SETTING -> if (!record.deleted) {
                val json = JSONObject(record.payload.toString(Charsets.UTF_8))
                applicationContext.getSharedPreferences("companion_settings", Context.MODE_PRIVATE).edit()
                    .putBoolean("always_on", json.optBoolean("alwaysOn", false))
                    .putString("power_mode", json.optString("powerMode", "BALANCED"))
                    .apply()
                json.optJSONArray("favoriteTargets")?.let {
                    FavoriteTargetStore(applicationContext).decodeAndReplace(it)
                }
            }
            SyncEntityType.ACTIVITY -> if (!record.deleted) {
                val directory = File(applicationContext.filesDir, "activities/from-watch").apply { mkdirs() }
                File(directory, "${record.id.replace(Regex("[^A-Za-z0-9._-]+"), "-")}.gpx")
                    .writeBytes(record.payload)
            }
            SyncEntityType.FISHING_CATCH -> if (record.deleted) {
                MobileFishingLogStore(applicationContext).delete(record.id)
            } else {
                MobileFishingLogStore(applicationContext).mergeWatchRecord(record)
                FishingAudioTranscriptionManager.linkCompletedTranscripts(applicationContext)
                FishingAudioTranscriptionManager.enqueuePending(applicationContext)
            }
            else -> Unit
        }
    }

    private fun installLanArtifact(value: ReceivedTransfer) {
        val installedFile = when (value.manifest.kind) {
            TransferKind.MAPSFORGE, TransferKind.CONTOUR_MAP -> {
                val kind = if (value.manifest.kind == TransferKind.CONTOUR_MAP) {
                    OfflineLayerKind.CONTOUR
                } else OfflineLayerKind.BASE_MAP
                OfflineLayerStore(applicationContext).register(
                    value.file, kind, value.manifest.displayName,
                    value.manifest.attribution, value.manifest.license,
                    // A base map explicitly collected from the watch becomes the phone's base;
                    // contour remains an optional overlay and is not forced on.
                    activate = kind == OfflineLayerKind.BASE_MAP,
                ).file
            }
            TransferKind.BROUTER_RD5 -> value.file.inputStream().buffered().use {
                BRouterOfflineRouter(applicationContext).importRegion(value.manifest.packageId, it)
            }
            TransferKind.COURSE_FILE -> value.file.inputStream().buffered().use {
                CourseFileStore(applicationContext).import(it, value.manifest.displayName)
                value.file
            }
            TransferKind.HISTORICAL_RASTER, TransferKind.ENC_CHART, TransferKind.BATHYMETRY,
            TransferKind.WATER_BOUNDARY, TransferKind.POI_INDEX -> OfflineLayerStore(applicationContext)
                .register(
                    value.file,
                    when (value.manifest.kind) {
                        TransferKind.HISTORICAL_RASTER -> OfflineLayerKind.HISTORICAL_IMAGERY
                        TransferKind.ENC_CHART -> OfflineLayerKind.ENC_CHART
                        TransferKind.BATHYMETRY -> OfflineLayerKind.BATHYMETRY
                        TransferKind.WATER_BOUNDARY -> OfflineLayerKind.WATER_BOUNDARY
                        else -> OfflineLayerKind.PUBLIC_POI
                    },
                    value.manifest.displayName, value.manifest.attribution, value.manifest.license,
                ).file
            TransferKind.FISHING_AUDIO -> MobileFishingLogStore(applicationContext)
                .storeAudio(value.manifest.packageId, value.file).also {
                    FishingAudioTranscriptionManager.enqueuePending(applicationContext)
                }
            else -> value.file
        }
        artifacts.record(value.manifest, installedFile, localState(value.manifest.kind))
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val MAX_INLINE_PAYLOAD = SyncProtocol.MAX_DATA_ITEM_BYTES - 8 * 1024
        const val ACTIVE_COURSE_ID = "active-course"
        const val TAG = "WatchNavMobileSync"
        const val KEY_LAST_LAN_SYNC = "last_lan_sync"
        const val KEY_LAST_LAN_PEER = "last_lan_peer"
    }
}
