package com.watchnav.sync.transport

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.watchnav.sync.protocol.ConflictDecision
import com.watchnav.sync.protocol.DeviceHello
import com.watchnav.sync.protocol.DeviceRole
import com.watchnav.sync.protocol.InventoryEntry
import com.watchnav.sync.protocol.InventoryPage
import com.watchnav.sync.protocol.SyncConflictResolver
import com.watchnav.sync.protocol.SyncEntityType
import com.watchnav.sync.protocol.SyncRecord
import com.watchnav.sync.protocol.SyncWireCodec
import com.watchnav.sync.protocol.TransferCheckpoint

enum class MergeResult { INSERTED, UPDATED, UNCHANGED, LOCAL_NEWER, CONFLICT }

open class SyncRecordStore(
    context: Context,
    databaseName: String = "watchnav-sync.db",
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE records(
                type TEXT NOT NULL,
                id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                updated_by TEXT NOT NULL,
                deleted INTEGER NOT NULL,
                content_type TEXT NOT NULL,
                payload BLOB NOT NULL,
                content_hash TEXT NOT NULL,
                PRIMARY KEY(type,id)
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX records_updated ON records(updated_at,type,id)")
        db.execSQL(
            """CREATE TABLE conflicts(
                conflict_id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                id TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                record BLOB NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE devices(
                device_id TEXT PRIMARY KEY,
                role TEXT NOT NULL,
                last_seen INTEGER NOT NULL,
                hello BLOB NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE checkpoints(
                transfer_id TEXT PRIMARY KEY,
                updated_at INTEGER NOT NULL,
                checkpoint BLOB NOT NULL
            )""".trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun upsertLocal(
        type: SyncEntityType,
        id: String,
        role: DeviceRole,
        contentType: String,
        payload: ByteArray,
        deleted: Boolean = false,
    ): SyncRecord {
        val current = find(type, id)
        val hash = com.watchnav.sync.protocol.Hashing.sha256(payload)
        if (current != null && current.contentHash == hash && current.deleted == deleted) return current
        val record = SyncRecord.create(
            id = id,
            type = type,
            revision = (current?.revision ?: 0L) + 1L,
            updatedBy = role,
            deleted = deleted,
            contentType = contentType,
            payload = payload,
        )
        write(record)
        return record
    }

    @Synchronized
    fun merge(remote: SyncRecord): MergeResult {
        val local = find(remote.type, remote.id)
        if (local == null) {
            write(remote)
            return MergeResult.INSERTED
        }
        return when (val decision = SyncConflictResolver.resolve(local, remote)) {
            is ConflictDecision.Identical -> MergeResult.UNCHANGED
            is ConflictDecision.UseLocal -> MergeResult.LOCAL_NEWER
            is ConflictDecision.UseRemote -> {
                write(decision.record)
                MergeResult.UPDATED
            }
            is ConflictDecision.KeepBoth -> {
                writableDatabase.insertOrThrow(
                    "conflicts",
                    null,
                    ContentValues().apply {
                        put("type", remote.type.name)
                        put("id", remote.id)
                        put("received_at", System.currentTimeMillis())
                        put("record", SyncWireCodec.encodeRecord(remote))
                    },
                )
                MergeResult.CONFLICT
            }
        }
    }

    fun find(type: SyncEntityType, id: String): SyncRecord? = readableDatabase.query(
        "records",
        RECORD_COLUMNS,
        "type=? AND id=?",
        arrayOf(type.name, id),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    fun records(limit: Int = 512): List<SyncRecord> = readableDatabase.query(
        "records",
        RECORD_COLUMNS,
        null,
        null,
        null,
        null,
        "updated_at ASC,type ASC,id ASC",
        limit.coerceIn(1, 512).toString(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecord()) } }

    fun records(type: SyncEntityType, limit: Int = 512): List<SyncRecord> = readableDatabase.query(
        "records",
        RECORD_COLUMNS,
        "type=?",
        arrayOf(type.name),
        null,
        null,
        "updated_at DESC,id ASC",
        limit.coerceIn(1, 512).toString(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecord()) } }

    /**
     * Records to republish during peer recovery. Inventory pages intentionally stay at 512,
     * but the durable Data Layer stream must not silently drop routes/settings merely because
     * a public POI collection is larger than one inventory page.
     */
    fun recordsForSync(limit: Int = MAXIMUM_SYNC_RECORDS): List<SyncRecord> = readableDatabase.query(
        "records",
        RECORD_COLUMNS,
        null,
        null,
        null,
        null,
        "updated_at ASC,type ASC,id ASC",
        limit.coerceIn(1, MAXIMUM_SYNC_RECORDS).toString(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecord()) } }

    fun inventory(cursor: Long = 0L): InventoryPage {
        val values = readableDatabase.query(
            "records",
            RECORD_COLUMNS,
            "updated_at>?",
            arrayOf(cursor.toString()),
            null,
            null,
            "updated_at ASC,type ASC,id ASC",
            "512",
        ).use { result -> buildList { while (result.moveToNext()) add(result.toRecord()) } }
        val next = values.lastOrNull()?.updatedAtUtcMillis?.takeIf { values.size == 512 }
        return InventoryPage(
            cursor,
            next,
            values.map { record ->
                InventoryEntry(
                    record.id,
                    record.type,
                    record.revision,
                    record.updatedAtUtcMillis,
                    record.deleted,
                    record.contentHash,
                )
            },
        )
    }

    fun saveDevice(hello: DeviceHello) {
        writableDatabase.insertWithOnConflict(
            "devices",
            null,
            ContentValues().apply {
                put("device_id", hello.deviceId)
                put("role", hello.role.name)
                put("last_seen", System.currentTimeMillis())
                put("hello", SyncWireCodec.encodeHello(hello))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun latestDevice(role: DeviceRole): DeviceHello? = readableDatabase.query(
        "devices",
        arrayOf("hello"),
        "role=?",
        arrayOf(role.name),
        null,
        null,
        "last_seen DESC",
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) runCatching { SyncWireCodec.decodeHello(cursor.getBlob(0)) }.getOrNull() else null
    }

    fun saveCheckpoint(value: TransferCheckpoint) {
        writableDatabase.insertWithOnConflict(
            "checkpoints",
            null,
            ContentValues().apply {
                put("transfer_id", value.transferId)
                put("updated_at", value.updatedAtUtcMillis)
                put("checkpoint", SyncWireCodec.encodeTransferCheckpoint(value))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun latestCheckpoint(transferId: String): TransferCheckpoint? = readableDatabase.query(
        "checkpoints",
        arrayOf("checkpoint"),
        "transfer_id=?",
        arrayOf(transferId),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) runCatching {
            SyncWireCodec.decodeTransferCheckpoint(cursor.getBlob(0))
        }.getOrNull() else null
    }

    private fun write(record: SyncRecord) {
        writableDatabase.insertWithOnConflict("records", null, record.values(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun SyncRecord.values() = ContentValues().apply {
        put("type", type.name)
        put("id", id)
        put("revision", revision)
        put("updated_at", updatedAtUtcMillis)
        put("updated_by", updatedBy.name)
        put("deleted", if (deleted) 1 else 0)
        put("content_type", contentType)
        put("payload", payload)
        put("content_hash", contentHash)
    }

    private fun android.database.Cursor.toRecord() = SyncRecord(
        type = SyncEntityType.valueOf(getString(0)),
        id = getString(1),
        revision = getLong(2),
        updatedAtUtcMillis = getLong(3),
        updatedBy = DeviceRole.valueOf(getString(4)),
        deleted = getInt(5) != 0,
        contentType = getString(6),
        payload = getBlob(7),
        contentHash = getString(8),
    )

    private companion object {
        const val DATABASE_VERSION = 1
        const val MAXIMUM_SYNC_RECORDS = 4_096
        val RECORD_COLUMNS = arrayOf(
            "type", "id", "revision", "updated_at", "updated_by", "deleted",
            "content_type", "payload", "content_hash",
        )
    }
}
