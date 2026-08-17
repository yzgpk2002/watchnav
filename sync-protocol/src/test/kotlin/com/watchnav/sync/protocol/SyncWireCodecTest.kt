package com.watchnav.sync.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SyncWireCodecTest {
    @Test
    fun `hello round trips`() {
        val hello = DeviceHello(
            deviceId = "phone-123",
            role = DeviceRole.PHONE,
            appVersionName = "1.0.0",
            appVersionCode = 1,
            freeBytes = 4_000_000_000,
            batteryPercent = 78,
            capabilities = setOf(SyncCapability.COURSE_SYNC, SyncCapability.LARGE_FILE_TRANSFER),
            lastSyncCursor = 42,
        )
        assertEquals(hello, SyncWireCodec.decodeHello(SyncWireCodec.encodeHello(hello)))
    }

    @Test
    fun `record round trips and validates payload hash`() {
        val record = SyncRecord.create(
            type = SyncEntityType.COURSE,
            updatedBy = DeviceRole.PHONE,
            contentType = "application/gpx+xml",
            payload = "<gpx/>".toByteArray(),
        )
        val decoded = SyncWireCodec.decodeRecord(SyncWireCodec.encodeRecord(record))
        assertEquals(record.id, decoded.id)
        assertEquals(record.type, decoded.type)
        assertEquals(record.revision, decoded.revision)
        assertEquals(record.updatedAtUtcMillis, decoded.updatedAtUtcMillis)
        assertEquals(record.updatedBy, decoded.updatedBy)
        assertEquals(record.deleted, decoded.deleted)
        assertEquals(record.contentType, decoded.contentType)
        assertEquals(record.contentHash, decoded.contentHash)
        assertArrayEquals(record.payload, decoded.payload)
    }

    @Test
    fun `equal revision with different contents keeps both`() {
        val local = SyncRecord.create(
            id = "same",
            type = SyncEntityType.WAYPOINT,
            updatedBy = DeviceRole.PHONE,
            contentType = "application/json",
            payload = "local".toByteArray(),
        )
        val remote = SyncRecord.create(
            id = "same",
            type = SyncEntityType.WAYPOINT,
            updatedBy = DeviceRole.WATCH,
            contentType = "application/json",
            payload = "remote".toByteArray(),
        )
        assertInstanceOf(ConflictDecision.KeepBoth::class.java, SyncConflictResolver.resolve(local, remote))
    }

    @Test
    fun `transfer metadata round trips`() {
        val manifest = TransferManifest(
            transferId = "tx-1",
            packageId = "lake-1",
            version = 2,
            kind = TransferKind.MAPSFORGE,
            fileName = "lake.map",
            displayName = "Lake map",
            totalBytes = 200_000_000,
            sha256 = "a".repeat(64),
            license = "ODbL",
            attribution = "OpenStreetMap contributors",
        )
        assertEquals(manifest, SyncWireCodec.decodeTransferManifest(SyncWireCodec.encodeTransferManifest(manifest)))
    }
}
