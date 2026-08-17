package com.watchnav.sync.protocol

import java.security.MessageDigest
import java.io.InputStream
import java.util.UUID

object SyncProtocol {
    const val VERSION = 1
    const val MAX_DATA_ITEM_BYTES = 96 * 1024
    const val DEFAULT_CHUNK_BYTES = 4 * 1024 * 1024
    const val MAX_INVENTORY_ENTRIES = 512
}

enum class DeviceRole { PHONE, WATCH }

enum class SyncCapability {
    ENTITY_SYNC,
    SETTINGS_SYNC,
    COURSE_SYNC,
    FISHING_SYNC,
    ACTIVITY_SYNC,
    TIDE_SYNC,
    LARGE_FILE_TRANSFER,
    RESUMABLE_TRANSFER,
    PACKAGE_INSTALL,
    HISTORICAL_RASTER,
    HYDRO_LAYERS,
    POI_INDEX,
}

data class DeviceHello(
    val deviceId: String,
    val role: DeviceRole,
    val appVersionName: String,
    val appVersionCode: Long,
    val protocolVersion: Int = SyncProtocol.VERSION,
    val freeBytes: Long,
    val batteryPercent: Int?,
    val capabilities: Set<SyncCapability>,
    val lastSyncCursor: Long = 0L,
) {
    init {
        require(deviceId.isNotBlank() && deviceId.length <= 128)
        require(appVersionName.length <= 64)
        require(appVersionCode >= 0)
        require(protocolVersion > 0)
        require(freeBytes >= 0)
        require(batteryPercent == null || batteryPercent in 0..100)
        require(lastSyncCursor >= 0)
    }
}

enum class SyncEntityType {
    COURSE,
    WAYPOINT,
    FISHING_CATCH,
    ACTIVITY,
    SETTING,
    TIDE_FORECAST,
    LAYER_SOURCE,
    PUBLIC_POI,
}

data class SyncRecord(
    val id: String,
    val type: SyncEntityType,
    val revision: Long,
    val updatedAtUtcMillis: Long,
    val updatedBy: DeviceRole,
    val deleted: Boolean,
    val contentType: String,
    val payload: ByteArray,
    val contentHash: String,
) {
    init {
        require(id.isNotBlank() && id.length <= 160)
        require(revision > 0)
        require(updatedAtUtcMillis >= 0)
        require(contentType.isNotBlank() && contentType.length <= 128)
        require(payload.size <= SyncProtocol.MAX_DATA_ITEM_BYTES)
        require(contentHash.matches(Regex("[0-9a-f]{64}")))
        require(Hashing.sha256(payload) == contentHash)
    }

    companion object {
        fun create(
            id: String = UUID.randomUUID().toString(),
            type: SyncEntityType,
            revision: Long = 1L,
            updatedAtUtcMillis: Long = System.currentTimeMillis(),
            updatedBy: DeviceRole,
            deleted: Boolean = false,
            contentType: String,
            payload: ByteArray,
        ) = SyncRecord(
            id = id,
            type = type,
            revision = revision,
            updatedAtUtcMillis = updatedAtUtcMillis,
            updatedBy = updatedBy,
            deleted = deleted,
            contentType = contentType,
            payload = payload,
            contentHash = Hashing.sha256(payload),
        )
    }
}

data class InventoryEntry(
    val id: String,
    val type: SyncEntityType,
    val revision: Long,
    val updatedAtUtcMillis: Long,
    val deleted: Boolean,
    val contentHash: String,
) {
    init {
        require(id.isNotBlank() && id.length <= 160)
        require(revision > 0)
        require(updatedAtUtcMillis >= 0)
        require(contentHash.matches(Regex("[0-9a-f]{64}")))
    }
}

data class InventoryPage(
    val cursor: Long,
    val nextCursor: Long?,
    val entries: List<InventoryEntry>,
) {
    init {
        require(cursor >= 0)
        require(nextCursor == null || nextCursor > cursor)
        require(entries.size <= SyncProtocol.MAX_INVENTORY_ENTRIES)
    }
}

enum class TransferKind {
    MAPSFORGE,
    CONTOUR_MAP,
    BROUTER_RD5,
    HISTORICAL_RASTER,
    ENC_CHART,
    BATHYMETRY,
    WATER_BOUNDARY,
    POI_INDEX,
    TIDE_CACHE,
    COURSE_FILE,
    ACTIVITY_FILE,
    FISHING_AUDIO,
}

data class TransferManifest(
    val transferId: String,
    val packageId: String,
    val version: Long,
    val kind: TransferKind,
    val fileName: String,
    val displayName: String,
    val totalBytes: Long,
    val sha256: String,
    val chunkBytes: Int = SyncProtocol.DEFAULT_CHUNK_BYTES,
    val license: String = "",
    val attribution: String = "",
    val offlineAllowed: Boolean = true,
    val createdAtUtcMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(transferId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(packageId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(version > 0)
        require(fileName.matches(Regex("[A-Za-z0-9._-]{1,160}")))
        require(displayName.isNotBlank() && displayName.length <= 160)
        require(totalBytes > 0)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(chunkBytes in 64 * 1024..16 * 1024 * 1024)
        require(license.length <= 512)
        require(attribution.length <= 1024)
        require(createdAtUtcMillis >= 0)
    }
}

enum class TransferState {
    OFFERED,
    ACCEPTED,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    RECEIVED,
    INSTALLED,
    FAILED,
    CANCELLED,
}

data class TransferCheckpoint(
    val transferId: String,
    val state: TransferState,
    val confirmedBytes: Long,
    val totalBytes: Long,
    val updatedAtUtcMillis: Long,
    val message: String = "",
) {
    init {
        require(transferId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(totalBytes > 0)
        require(confirmedBytes in 0..totalBytes)
        require(updatedAtUtcMillis >= 0)
        require(message.length <= 1024)
    }
}

sealed interface ConflictDecision {
    data class UseLocal(val record: SyncRecord) : ConflictDecision
    data class UseRemote(val record: SyncRecord) : ConflictDecision
    data class Identical(val record: SyncRecord) : ConflictDecision
    data class KeepBoth(val local: SyncRecord, val remote: SyncRecord) : ConflictDecision
}

object SyncConflictResolver {
    fun resolve(local: SyncRecord, remote: SyncRecord): ConflictDecision {
        require(local.id == remote.id && local.type == remote.type)
        if (local.revision > remote.revision) return ConflictDecision.UseLocal(local)
        if (remote.revision > local.revision) return ConflictDecision.UseRemote(remote)
        if (local.contentHash == remote.contentHash && local.deleted == remote.deleted) {
            return ConflictDecision.Identical(
                if (local.updatedAtUtcMillis >= remote.updatedAtUtcMillis) local else remote,
            )
        }
        return ConflictDecision.KeepBoth(local, remote)
    }
}

object Hashing {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
