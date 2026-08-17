package com.watchnav.mobile.sync

import android.util.Log
import com.watchnav.brouter.BRouterOfflineRouter
import com.watchnav.mobile.data.MobileSyncRepository
import com.watchnav.mobile.data.OfflineLayerKind
import com.watchnav.mobile.data.OfflineLayerStore
import com.watchnav.mobile.data.MobileFishingLogStore
import com.watchnav.mobile.speech.FishingAudioTranscriptionManager
import com.watchnav.session.CourseFileStore
import com.watchnav.session.SavedWaypoint
import com.watchnav.session.WaypointStore
import com.watchnav.session.FavoriteTargetStore
import com.watchnav.core.GeoPoint
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
import com.watchnav.sync.transport.WearSyncTransport
import com.watchnav.sync.transport.TransferredArtifactRegistry
import org.json.JSONObject
import java.io.File

class MobileWearSyncService : BaseWearSyncListenerService() {
    private val repository by lazy { MobileSyncRepository(this) }
    private val outgoing by lazy { OutgoingTransferManager(this) }
    private val transport by lazy { WearSyncTransport(this) }
    private val artifacts by lazy { TransferredArtifactRegistry(this) }

    override fun onHello(value: DeviceHello) {
        if (value.role == DeviceRole.WATCH) repository.saveDevice(value)
        CompanionStateBus.emit()
    }

    override fun onInventory(value: InventoryPage) {
        // The durable record stream is authoritative; the inventory is used to request missing revisions.
        CompanionStateBus.emit()
    }

    override fun onRecord(value: SyncRecord) {
        val result = repository.merge(value)
        if (result == MergeResult.INSERTED || result == MergeResult.UPDATED ||
            (value.type == SyncEntityType.FISHING_CATCH && !value.deleted)
        ) applyRecord(value)
        CompanionStateBus.emit()
    }

    override fun onSyncRequested(sourceNodeId: String) {
        val coordinator = MobileSyncCoordinator(this)
        coordinator.announce { coordinator.close() }
    }

    override fun onTransferOffered(manifest: TransferManifest, checkpoint: TransferCheckpoint) {
        repository.saveCheckpoint(checkpoint)
        CompanionStateBus.emit()
    }

    override fun existingTransferCheckpoint(manifest: TransferManifest): TransferCheckpoint? =
        artifacts.checkpointIfAvailable(manifest)

    override fun onTransferCheckpoint(value: TransferCheckpoint, sourceNodeId: String?) {
        repository.saveCheckpoint(value)
        outgoing.handleCheckpoint(sourceNodeId, value)
        outgoing.markTerminal(value)
        CompanionStateBus.emit()
    }

    override fun onTransferReady(value: ReceivedTransfer) {
        runCatching {
            val installedFile = when (value.manifest.kind) {
                TransferKind.MAPSFORGE,
                TransferKind.CONTOUR_MAP,
                -> {
                    val kind = if (value.manifest.kind == TransferKind.CONTOUR_MAP) {
                        OfflineLayerKind.CONTOUR
                    } else {
                        OfflineLayerKind.BASE_MAP
                    }
                    val layer = OfflineLayerStore(this).register(
                        value.file,
                        kind,
                        value.manifest.displayName,
                        value.manifest.attribution,
                        value.manifest.license,
                        activate = false,
                    )
                    layer.file
                }
                TransferKind.BROUTER_RD5 -> value.file.inputStream().buffered().use { input ->
                    BRouterOfflineRouter(this).importRegion(value.manifest.packageId, input)
                }
                TransferKind.COURSE_FILE -> value.file.inputStream().buffered().use { input ->
                    CourseFileStore(this).import(input, value.manifest.displayName)
                    value.file
                }
                TransferKind.HISTORICAL_RASTER,
                TransferKind.ENC_CHART,
                TransferKind.BATHYMETRY,
                TransferKind.WATER_BOUNDARY,
                TransferKind.POI_INDEX,
                -> OfflineLayerStore(this).register(
                    value.file,
                    value.manifest.kind.toOfflineLayerKind(),
                    value.manifest.displayName,
                    value.manifest.attribution,
                    value.manifest.license,
                ).file
                TransferKind.FISHING_AUDIO -> MobileFishingLogStore(this).storeAudio(value.manifest.packageId, value.file).also {
                    FishingAudioTranscriptionManager.enqueuePending(this)
                }
                else -> value.file
            }
            val installed = value.manifest.kind in setOf(
                TransferKind.MAPSFORGE, TransferKind.CONTOUR_MAP,
                TransferKind.BROUTER_RD5, TransferKind.COURSE_FILE,
            )
            val state = if (installed) TransferState.INSTALLED else TransferState.RECEIVED
            artifacts.record(value.manifest, installedFile, state)
            state
        }.onSuccess {
            finishTransfer(
                value,
                it,
                if (it == TransferState.INSTALLED) "Installed on phone" else "Stored on phone",
            )
        }.onFailure {
            finishTransfer(value, TransferState.FAILED, it.message.orEmpty())
            onSyncError(value.manifest.transferId, it)
        }
        CompanionStateBus.emit()
    }

    private fun finishTransfer(value: ReceivedTransfer, state: TransferState, message: String) {
        val checkpoint = TransferCheckpoint(
            value.manifest.transferId,
            state,
            value.manifest.totalBytes,
            value.manifest.totalBytes,
            System.currentTimeMillis(),
            message.take(1_024),
        )
        repository.saveCheckpoint(checkpoint)
        transport.sendCheckpoint(value.sourceNodeId, checkpoint)
    }

    private fun TransferKind.toOfflineLayerKind(): OfflineLayerKind = when (this) {
        TransferKind.HISTORICAL_RASTER -> OfflineLayerKind.HISTORICAL_IMAGERY
        TransferKind.ENC_CHART -> OfflineLayerKind.ENC_CHART
        TransferKind.BATHYMETRY -> OfflineLayerKind.BATHYMETRY
        TransferKind.WATER_BOUNDARY -> OfflineLayerKind.WATER_BOUNDARY
        TransferKind.POI_INDEX -> OfflineLayerKind.PUBLIC_POI
        else -> error("$this is not an offline layer")
    }

    override fun onSyncError(path: String, error: Throwable) {
        Log.e(TAG, "Sync error at $path", error)
    }

    private fun applyRecord(record: SyncRecord) {
        when (record.type) {
            SyncEntityType.COURSE -> if (!record.deleted) CourseFileStore(this).save(record.payload, "Watch route")
            SyncEntityType.WAYPOINT, SyncEntityType.PUBLIC_POI -> {
                if (record.deleted) WaypointStore(this).delete(record.id)
                else {
                    val json = JSONObject(record.payload.toString(Charsets.UTF_8))
                    WaypointStore(this).upsert(SavedWaypoint(
                        json.getString("id"), json.getString("name"),
                        GeoPoint(
                            json.getDouble("latitude"), json.getDouble("longitude"),
                            json.optDouble("elevation").takeIf { !it.isNaN() },
                        ),
                        json.getLong("createdAtMillis"),
                    ))
                }
                FishingAudioTranscriptionManager.linkCompletedTranscripts(this)
            }
            SyncEntityType.SETTING -> if (!record.deleted) {
                val json = JSONObject(record.payload.toString(Charsets.UTF_8))
                getSharedPreferences("companion_settings", MODE_PRIVATE).edit()
                    .putBoolean("always_on", json.optBoolean("alwaysOn", false))
                    .putString("power_mode", json.optString("powerMode", "BALANCED"))
                    .apply()
                json.optJSONArray("favoriteTargets")?.let { FavoriteTargetStore(this).decodeAndReplace(it) }
            }
            SyncEntityType.ACTIVITY -> if (!record.deleted) {
                val directory = File(filesDir, "activities/from-watch").apply { mkdirs() }
                File(directory, "${record.id.replace(Regex("[^A-Za-z0-9._-]+"), "-")}.gpx").writeBytes(record.payload)
            }
            SyncEntityType.FISHING_CATCH -> if (record.deleted) {
                MobileFishingLogStore(this).delete(record.id)
            } else {
                MobileFishingLogStore(this).mergeWatchRecord(record)
                FishingAudioTranscriptionManager.linkCompletedTranscripts(this)
                FishingAudioTranscriptionManager.enqueuePending(this)
            }
            else -> Unit
        }
    }

    override fun onDestroy() {
        outgoing.close()
        transport.close()
        repository.close()
        super.onDestroy()
    }

    private companion object { const val TAG = "WatchNavMobileSync" }
}
