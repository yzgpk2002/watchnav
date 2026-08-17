package com.watchnav.sync.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object SyncWireCodec {
    private const val MAGIC = 0x574E5359 // WNSY
    private const val HELLO = 1
    private const val INVENTORY = 2
    private const val RECORD = 3
    private const val TRANSFER_MANIFEST = 4
    private const val TRANSFER_CHECKPOINT = 5
    private const val MAX_STRING_BYTES = 16 * 1024

    fun encodeHello(value: DeviceHello): ByteArray = encode(HELLO) {
        writeString(value.deviceId)
        writeString(value.role.name)
        writeString(value.appVersionName)
        writeLong(value.appVersionCode)
        writeInt(value.protocolVersion)
        writeLong(value.freeBytes)
        writeInt(value.batteryPercent ?: -1)
        writeInt(value.capabilities.size)
        value.capabilities.sortedBy(Enum<*>::name).forEach { writeString(it.name) }
        writeLong(value.lastSyncCursor)
    }

    fun decodeHello(bytes: ByteArray): DeviceHello = decode(bytes, HELLO) {
        DeviceHello(
            deviceId = readString(),
            role = enumValueOf(readString()),
            appVersionName = readString(),
            appVersionCode = readLong(),
            protocolVersion = readInt(),
            freeBytes = readLong(),
            batteryPercent = readInt().takeIf { it >= 0 },
            capabilities = buildSet {
                repeat(readBoundedCount(64)) { add(enumValueOf<SyncCapability>(readString())) }
            },
            lastSyncCursor = readLong(),
        )
    }

    fun encodeInventory(value: InventoryPage): ByteArray = encode(INVENTORY) {
        writeLong(value.cursor)
        writeLong(value.nextCursor ?: -1L)
        writeInt(value.entries.size)
        value.entries.forEach { entry ->
            writeString(entry.id)
            writeString(entry.type.name)
            writeLong(entry.revision)
            writeLong(entry.updatedAtUtcMillis)
            writeBoolean(entry.deleted)
            writeString(entry.contentHash)
        }
    }

    fun decodeInventory(bytes: ByteArray): InventoryPage = decode(bytes, INVENTORY) {
        val cursor = readLong()
        val next = readLong().takeIf { it >= 0 }
        val entries = buildList {
            repeat(readBoundedCount(SyncProtocol.MAX_INVENTORY_ENTRIES)) {
                add(
                    InventoryEntry(
                        id = readString(),
                        type = enumValueOf(readString()),
                        revision = readLong(),
                        updatedAtUtcMillis = readLong(),
                        deleted = readBoolean(),
                        contentHash = readString(),
                    ),
                )
            }
        }
        InventoryPage(cursor, next, entries)
    }

    fun encodeRecord(value: SyncRecord): ByteArray = encode(RECORD) {
        writeString(value.id)
        writeString(value.type.name)
        writeLong(value.revision)
        writeLong(value.updatedAtUtcMillis)
        writeString(value.updatedBy.name)
        writeBoolean(value.deleted)
        writeString(value.contentType)
        writeString(value.contentHash)
        writeByteArray(value.payload)
    }.also { require(it.size <= SyncProtocol.MAX_DATA_ITEM_BYTES) }

    fun decodeRecord(bytes: ByteArray): SyncRecord = decode(bytes, RECORD) {
        SyncRecord(
            id = readString(),
            type = enumValueOf(readString()),
            revision = readLong(),
            updatedAtUtcMillis = readLong(),
            updatedBy = enumValueOf(readString()),
            deleted = readBoolean(),
            contentType = readString(),
            contentHash = readString(),
            payload = readByteArray(SyncProtocol.MAX_DATA_ITEM_BYTES),
        )
    }

    fun encodeTransferManifest(value: TransferManifest): ByteArray = encode(TRANSFER_MANIFEST) {
        writeString(value.transferId)
        writeString(value.packageId)
        writeLong(value.version)
        writeString(value.kind.name)
        writeString(value.fileName)
        writeString(value.displayName)
        writeLong(value.totalBytes)
        writeString(value.sha256)
        writeInt(value.chunkBytes)
        writeString(value.license)
        writeString(value.attribution)
        writeBoolean(value.offlineAllowed)
        writeLong(value.createdAtUtcMillis)
    }

    fun decodeTransferManifest(bytes: ByteArray): TransferManifest = decode(bytes, TRANSFER_MANIFEST) {
        TransferManifest(
            transferId = readString(),
            packageId = readString(),
            version = readLong(),
            kind = enumValueOf(readString()),
            fileName = readString(),
            displayName = readString(),
            totalBytes = readLong(),
            sha256 = readString(),
            chunkBytes = readInt(),
            license = readString(),
            attribution = readString(),
            offlineAllowed = readBoolean(),
            createdAtUtcMillis = readLong(),
        )
    }

    fun encodeTransferCheckpoint(value: TransferCheckpoint): ByteArray = encode(TRANSFER_CHECKPOINT) {
        writeString(value.transferId)
        writeString(value.state.name)
        writeLong(value.confirmedBytes)
        writeLong(value.totalBytes)
        writeLong(value.updatedAtUtcMillis)
        writeString(value.message)
    }

    fun decodeTransferCheckpoint(bytes: ByteArray): TransferCheckpoint = decode(bytes, TRANSFER_CHECKPOINT) {
        TransferCheckpoint(
            transferId = readString(),
            state = enumValueOf(readString()),
            confirmedBytes = readLong(),
            totalBytes = readLong(),
            updatedAtUtcMillis = readLong(),
            message = readString(),
        )
    }

    private fun encode(type: Int, block: WireOutput.() -> Unit): ByteArray {
        val sink = ByteArrayOutputStream()
        DataOutputStream(sink).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(SyncProtocol.VERSION)
            output.writeInt(type)
            WireOutput(output).block()
        }
        return sink.toByteArray()
    }

    private fun <T> decode(bytes: ByteArray, expectedType: Int, block: WireInput.() -> T): T {
        require(bytes.size <= SyncProtocol.MAX_DATA_ITEM_BYTES) { "Wire message is too large" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid sync message" }
            require(input.readInt() == SyncProtocol.VERSION) { "Unsupported sync protocol" }
            require(input.readInt() == expectedType) { "Unexpected sync message type" }
            val wire = WireInput(input)
            val value = wire.block()
            require(input.available() == 0) { "Trailing bytes in sync message" }
            value
        }
    }

    private class WireOutput(private val output: DataOutputStream) {
        fun writeInt(value: Int) = output.writeInt(value)
        fun writeLong(value: Long) = output.writeLong(value)
        fun writeBoolean(value: Boolean) = output.writeBoolean(value)

        fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_STRING_BYTES) { "String is too long" }
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        fun writeByteArray(value: ByteArray) {
            output.writeInt(value.size)
            output.write(value)
        }
    }

    private class WireInput(private val input: DataInputStream) {
        fun readInt(): Int = input.readInt()
        fun readLong(): Long = input.readLong()
        fun readBoolean(): Boolean = input.readBoolean()

        fun readString(): String {
            val length = input.readInt()
            require(length in 0..MAX_STRING_BYTES) { "Invalid string length" }
            return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
        }

        fun readByteArray(maximumBytes: Int): ByteArray {
            val length = input.readInt()
            require(length in 0..maximumBytes) { "Invalid byte array length" }
            return ByteArray(length).also(input::readFully)
        }

        fun readBoundedCount(maximum: Int): Int = input.readInt().also {
            require(it in 0..maximum) { "Invalid collection size" }
        }
    }
}
