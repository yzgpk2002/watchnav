package com.watchnav.app.sync

import android.util.Log
import android.os.Handler
import android.os.Looper
import com.watchnav.app.FishingEnvironmentStore
import com.watchnav.app.TideForecastRepository
import com.watchnav.brouter.BRouterOfflineRouter
import com.watchnav.mapsforge.MapsforgeMapRepository
import com.watchnav.mapsforge.VectorMapLayerKind
import com.watchnav.session.CourseFileStore
import com.watchnav.session.NavigationPowerMode
import com.watchnav.session.NavigationSessionService
import com.watchnav.session.NavigationStateHub
import com.watchnav.session.WaypointStore
import com.watchnav.session.FavoriteTargetStore
import com.watchnav.sync.protocol.DeviceHello
import com.watchnav.sync.protocol.DeviceRole
import com.watchnav.sync.protocol.InventoryPage
import com.watchnav.sync.protocol.SyncEntityType
import com.watchnav.sync.protocol.SyncRecord
import com.watchnav.sync.protocol.TransferCheckpoint
import com.watchnav.sync.protocol.TransferKind
import com.watchnav.sync.protocol.TransferManifest
import com.watchnav.sync.protocol.TransferState
import com.watchnav.sync.transport.BaseWearSyncListenerService
import com.watchnav.sync.transport.MergeResult
import com.watchnav.sync.transport.OutgoingTransferManager
import com.watchnav.sync.transport.ReceivedTransfer
import com.watchnav.sync.transport.SyncRecordStore
import com.watchnav.sync.transport.TransferredArtifactRegistry
import com.watchnav.sync.transport.WearSyncTransport
import org.json.JSONObject
import java.io.File

class WatchWearSyncService : BaseWearSyncListenerService() {
    private val records by lazy { SyncRecordStore(this, "watchnav-watch-sync.db") }
    private val coordinator by lazy { WatchSyncCoordinator(this) }
    private val outgoing by lazy { OutgoingTransferManager(this) }
    private val transport by lazy { WearSyncTransport(this) }
    private val artifacts by lazy { TransferredArtifactRegistry(this) }

    override fun onHello(value: DeviceHello) {
        if (value.role == DeviceRole.PHONE) records.saveDevice(value)
    }

    override fun onInventory(value: InventoryPage) {
        coordinator.announce()
    }

    override fun onRecord(value: SyncRecord) {
        when (records.merge(value)) {
            MergeResult.INSERTED, MergeResult.UPDATED -> applyRecord(value)
            MergeResult.LOCAL_NEWER -> records.find(value.type, value.id)?.let { transport.publishRecord(it, urgent = true) }
            else -> Unit
        }
    }

    override fun onSyncRequested(sourceNodeId: String) = coordinator.announce()

    override fun onTransferOffered(manifest: TransferManifest, checkpoint: TransferCheckpoint) {
        records.saveCheckpoint(checkpoint)
    }

    override fun existingTransferCheckpoint(manifest: TransferManifest): TransferCheckpoint? {
        // "Send to watch" is an explicit replacement of the active route. The same source GPX
        // may already exist in the artifact registry while active.gpx has since been augmented
        // with an automatically planned approach. Reusing the old INSTALLED checkpoint would
        // silently ignore the user's replacement and leave stale remaining-distance data.
        if (manifest.kind == TransferKind.COURSE_FILE &&
            manifest.packageId == WatchSyncCoordinator.ACTIVE_COURSE_ID
        ) return null
        return artifacts.checkpointIfAvailable(manifest)
    }

    override fun onTransferCheckpoint(value: TransferCheckpoint, sourceNodeId: String?) {
        records.saveCheckpoint(value)
        outgoing.handleCheckpoint(sourceNodeId, value)
        outgoing.markTerminal(value)
    }

    override fun onTransferReady(value: ReceivedTransfer) {
        runCatching {
            val installedFile = when (value.manifest.kind) {
                TransferKind.MAPSFORGE, TransferKind.CONTOUR_MAP -> {
                    val kind = if (value.manifest.kind == TransferKind.CONTOUR_MAP) {
                        VectorMapLayerKind.CONTOUR
                    } else {
                        VectorMapLayerKind.BASE
                    }
                    MapsforgeMapRepository(this).registerVerified(
                        value.file,
                        kind,
                        value.manifest.displayName,
                        activate = false,
                    ).file
                }
                TransferKind.BROUTER_RD5 -> value.file.inputStream().buffered().use { input ->
                    BRouterOfflineRouter(this).importRegion(value.manifest.packageId, input)
                }
                TransferKind.COURSE_FILE -> value.file.inputStream().buffered().use { input ->
                    val bytes = input.readBytes()
                    if (value.manifest.packageId == WatchSyncCoordinator.ACTIVE_COURSE_ID) {
                        CourseFileStore(this).save(bytes, value.manifest.displayName)
                    } else {
                        CourseFileStore(this).saveToLibraryOnly(bytes, value.manifest.displayName)
                    }
                    value.file
                }
                TransferKind.TIDE_CACHE -> {
                    TideForecastRepository(this).importCache(value.file.readBytes())
                    value.file
                }
                TransferKind.HISTORICAL_RASTER -> installHistoricalRaster(value)
                TransferKind.ACTIVITY_FILE -> installActivity(value)
                else -> value.file // Retained in sync/received for a compatible offline layer reader.
            }
            val state = if (value.manifest.kind in INSTALLABLE_KINDS) TransferState.INSTALLED else TransferState.RECEIVED
            artifacts.record(value.manifest, installedFile, state)
            state
        }.onSuccess {
            val state = it
            finish(value, state, if (state == TransferState.INSTALLED) "Installed on watch" else "Stored on watch")
            if (value.manifest.kind == TransferKind.COURSE_FILE &&
                value.manifest.packageId == WatchSyncCoordinator.ACTIVE_COURSE_ID
            ) startReceivedCourse()
            coordinator.announce()
        }.onFailure { error ->
            finish(value, TransferState.FAILED, error.message.orEmpty())
            onSyncError(value.manifest.transferId, error)
        }
    }

    private fun applyRecord(record: SyncRecord) {
        when (record.type) {
            SyncEntityType.COURSE -> if (!record.deleted) {
                if (record.id == WatchSyncCoordinator.ACTIVE_COURSE_ID) {
                    CourseFileStore(this).save(record.payload, "Phone route")
                    startReceivedCourse()
                } else {
                    CourseFileStore(this).saveToLibraryOnly(record.payload, "Phone route")
                }
            }
            SyncEntityType.WAYPOINT, SyncEntityType.PUBLIC_POI -> {
                if (record.deleted) WaypointStore(this).delete(record.id)
                else WaypointStore(this).upsert(WatchSyncPayloads.decodeWaypoint(record.payload))
            }
            SyncEntityType.FISHING_CATCH -> {
                if (record.deleted) FishingEnvironmentStore(this).deleteCatch(record.id)
                else FishingEnvironmentStore(this).upsertCatch(WatchSyncPayloads.decodeFishingCatch(record.payload))
            }
            SyncEntityType.SETTING -> if (!record.deleted) applySettings(record.payload)
            SyncEntityType.TIDE_FORECAST -> if (!record.deleted) TideForecastRepository(this).importCache(record.payload)
            SyncEntityType.ACTIVITY -> if (!record.deleted) installActivity(record.id, record.payload)
            SyncEntityType.LAYER_SOURCE -> Unit
        }
    }

    private fun applySettings(bytes: ByteArray) {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val powerMode = json.optString("powerMode", "BALANCED").takeIf { it in POWER_MODES } ?: "BALANCED"
        getSharedPreferences(DISPLAY_PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean(KEY_ALWAYS_ON, json.optBoolean("alwaysOn", false))
            .putString(KEY_POWER_MODE, powerMode)
            .apply()
        json.optJSONArray("favoriteTargets")?.let { FavoriteTargetStore(this).decodeAndReplace(it) }
    }

    private fun startReceivedCourse() {
        // A Data Layer event is already push-based. Reloading the active course here gives the
        // explicit phone action immediate semantics without any background polling on the watch.
        NavigationStateHub.resetToIdle()
        Handler(Looper.getMainLooper()).post {
            runCatching {
                NavigationSessionService.start(this, powerMode = NavigationPowerMode.BALANCED)
            }.onFailure { Log.e(TAG, "Unable to start received course", it) }
        }
    }

    private fun installActivity(value: ReceivedTransfer) = installActivity(value.manifest.packageId, value.file.readBytes())

    private fun installHistoricalRaster(value: ReceivedTransfer): File {
        require(value.file.extension.equals("mbtiles", ignoreCase = true)) {
            "Watch satellite maps must use MBTiles"
        }
        val signature = value.file.inputStream().buffered().use { input ->
            ByteArray(SQLITE_SIGNATURE.size).also { bytes ->
                require(input.read(bytes) == bytes.size) { "Satellite MBTiles is truncated" }
            }
        }
        require(signature.contentEquals(SQLITE_SIGNATURE)) { "Satellite package is not SQLite MBTiles" }
        return value.file
    }

    private fun installActivity(id: String, bytes: ByteArray): File {
        require(bytes.isNotEmpty() && bytes.size <= CourseFileStore.MAXIMUM_GPX_BYTES)
        val directory = File(filesDir, "activities/imported").apply { mkdirs() }
        val target = File(directory, "${id.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(120)}.gpx")
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            check(temporary.delete())
        }
        return target
    }

    private fun finish(value: ReceivedTransfer, state: TransferState, message: String) {
        val checkpoint = TransferCheckpoint(
            value.manifest.transferId,
            state,
            value.manifest.totalBytes,
            value.manifest.totalBytes,
            System.currentTimeMillis(),
            message.take(1_024),
        )
        records.saveCheckpoint(checkpoint)
        transport.sendCheckpoint(value.sourceNodeId, checkpoint)
    }

    override fun onSyncError(path: String, error: Throwable) {
        Log.e(TAG, "Sync error at $path", error)
    }

    override fun onDestroy() {
        coordinator.close()
        outgoing.close()
        transport.close()
        records.close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WatchNavWearSync"
        const val DISPLAY_PREFERENCES = "display_preferences"
        const val KEY_ALWAYS_ON = "always_on"
        const val KEY_POWER_MODE = "power_mode"
        val POWER_MODES = setOf("ACCURATE", "BALANCED", "EXPEDITION")
        val INSTALLABLE_KINDS = setOf(
            TransferKind.MAPSFORGE, TransferKind.CONTOUR_MAP, TransferKind.BROUTER_RD5,
            TransferKind.COURSE_FILE, TransferKind.HISTORICAL_RASTER,
            TransferKind.TIDE_CACHE, TransferKind.ACTIVITY_FILE,
        )
        val SQLITE_SIGNATURE = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
