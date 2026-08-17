package com.watchnav.sync.transport

import android.content.Context
import android.os.StatFs
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.watchnav.sync.protocol.Hashing
import com.watchnav.sync.protocol.SyncWireCodec
import com.watchnav.sync.protocol.TransferCheckpoint
import com.watchnav.sync.protocol.TransferManifest
import com.watchnav.sync.protocol.TransferState
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class ReceivedTransfer(
    val sourceNodeId: String,
    val manifest: TransferManifest,
    val file: File,
)

class IncomingTransferStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val root = File(applicationContext.filesDir, "sync")
    private val manifests = File(root, "manifests")
    private val staging = File(root, "incoming")
    private val received = File(root, "received")
    private val channelClient = Wearable.getChannelClient(applicationContext)

    @Synchronized
    fun saveManifest(manifest: TransferManifest): Long {
        cleanupStaleTransfers()
        require(manifest.offlineAllowed) { "This layer cannot be stored offline" }
        manifests.mkdirs()
        val target = File(manifests, "${manifest.transferId}.bin")
        atomicWrite(target, SyncWireCodec.encodeTransferManifest(manifest))
        staging.mkdirs()
        val part = partialFile(manifest)
        require(part.length() <= manifest.totalBytes) { "Partial transfer exceeds declared size" }
        val available = StatFs(applicationContext.filesDir.absolutePath).availableBytes
        require(available >= (manifest.totalBytes - part.length()) + STORAGE_RESERVE_BYTES) {
            "Not enough watch storage for transfer"
        }
        return part.length()
    }

    @Synchronized
    fun manifest(transferId: String): TransferManifest? {
        val file = File(manifests, "$transferId.bin")
        return if (file.isFile) runCatching { SyncWireCodec.decodeTransferManifest(file.readBytes()) }.getOrNull() else null
    }

    fun receive(
        channel: ChannelClient.Channel,
        onCheckpoint: (TransferCheckpoint) -> Unit,
    ): Result<ReceivedTransfer?> = runCatching {
        val (transferId, requestedOffset) = WearSyncPaths.parseChannel(channel.path)
            ?: error("Invalid transfer channel")
        val manifest = manifest(transferId) ?: error("Transfer manifest is missing")
        val part = partialFile(manifest)
        part.parentFile?.mkdirs()
        val actualOffset = part.length()
        require(actualOffset == requestedOffset) {
            "Resume offset mismatch: sender=$requestedOffset receiver=$actualOffset"
        }
        var receivedBytes = actualOffset
        onCheckpoint(manifest.checkpoint(TransferState.TRANSFERRING, receivedBytes))
        val input = Tasks.await(channelClient.getInputStream(channel))
        input.buffered().use { source ->
            FileOutputStream(part, true).buffered().use { sink ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var lastAck = receivedBytes
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    receivedBytes += count
                    require(receivedBytes <= manifest.totalBytes) { "Transfer exceeds declared size" }
                    sink.write(buffer, 0, count)
                    if (receivedBytes - lastAck >= ACK_INTERVAL_BYTES) {
                        sink.flush()
                        lastAck = receivedBytes
                        onCheckpoint(manifest.checkpoint(TransferState.TRANSFERRING, receivedBytes))
                    }
                }
            }
        }
        if (receivedBytes < manifest.totalBytes) {
            onCheckpoint(manifest.checkpoint(TransferState.PAUSED, receivedBytes, "Connection interrupted"))
            null
        } else {
            onCheckpoint(manifest.checkpoint(TransferState.VERIFYING, receivedBytes))
            val actualHash = part.inputStream().buffered().use(Hashing::sha256)
            require(actualHash == manifest.sha256) { "SHA-256 verification failed" }
            val destination = File(
                File(received, manifest.kind.name.lowercase()),
                receivedFileName(manifest),
            )
            destination.parentFile?.mkdirs()
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                check(part.delete()) { "Unable to finalize received transfer" }
            }
            File(manifests, "${manifest.transferId}.bin").delete()
            onCheckpoint(manifest.checkpoint(TransferState.RECEIVED, manifest.totalBytes))
            ReceivedTransfer(channel.nodeId, manifest, destination)
        }
    }

    /** Receives the same resumable payload over a local TCP socket instead of a Wear channel. */
    fun receiveLan(
        sourceId: String,
        manifest: TransferManifest,
        input: InputStream,
        requestedOffset: Long,
    ): Result<ReceivedTransfer?> = runCatching {
        val part = partialFile(manifest)
        part.parentFile?.mkdirs()
        require(part.length() == requestedOffset) {
            "Resume offset mismatch: sender=$requestedOffset receiver=${part.length()}"
        }
        var receivedBytes = requestedOffset
        FileOutputStream(part, true).buffered().use { sink ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (receivedBytes < manifest.totalBytes) {
                val wanted = minOf(buffer.size.toLong(), manifest.totalBytes - receivedBytes).toInt()
                val count = input.read(buffer, 0, wanted)
                require(count >= 0) { "Local Wi-Fi transfer was interrupted" }
                sink.write(buffer, 0, count)
                receivedBytes += count
            }
        }
        val actualHash = part.inputStream().buffered().use(Hashing::sha256)
        require(actualHash == manifest.sha256) { "SHA-256 verification failed" }
        val destination = File(File(received, manifest.kind.name.lowercase()), receivedFileName(manifest))
        destination.parentFile?.mkdirs()
        if (!part.renameTo(destination)) {
            part.copyTo(destination, overwrite = true)
            check(part.delete()) { "Unable to finalize received transfer" }
        }
        File(manifests, "${manifest.transferId}.bin").delete()
        ReceivedTransfer(sourceId, manifest, destination)
    }

    fun checkpointFor(manifest: TransferManifest, state: TransferState, message: String = "") =
        manifest.checkpoint(state, partialFile(manifest).length(), message)

    private fun partialFile(manifest: TransferManifest) = File(staging, "${manifest.transferId}.part")

    @Synchronized
    fun cleanupStaleTransfers(
        nowMillis: Long = System.currentTimeMillis(),
        maximumAgeMillis: Long = STALE_TRANSFER_AGE_MILLIS,
    ): Int {
        var removed = 0
        staging.listFiles()?.filter { file ->
            file.isFile && file.name.endsWith(".part") && nowMillis - file.lastModified() >= maximumAgeMillis
        }?.forEach { file ->
            val transferId = file.name.removeSuffix(".part")
            if (file.delete()) removed++
            File(manifests, "$transferId.bin").delete()
        }
        manifests.listFiles()?.filter { file ->
            file.isFile && file.name.endsWith(".bin") && nowMillis - file.lastModified() >= maximumAgeMillis &&
                !File(staging, file.name.removeSuffix(".bin") + ".part").exists()
        }?.forEach { if (it.delete()) removed++ }
        return removed
    }

    private fun receivedFileName(manifest: TransferManifest): String {
        val extension = manifest.fileName.substringAfterLast('.', "").take(16)
        val suffix = extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        val originalStem = manifest.fileName.removeSuffix(suffix)
        val stem = "${manifest.packageId}-$originalStem".take(220 - suffix.length)
        return stem + suffix
    }

    private fun TransferManifest.checkpoint(state: TransferState, bytes: Long, message: String = "") =
        TransferCheckpoint(
            transferId = transferId,
            state = state,
            confirmedBytes = bytes.coerceIn(0, totalBytes),
            totalBytes = totalBytes,
            updatedAtUtcMillis = System.currentTimeMillis(),
            message = message,
        )

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            check(temporary.delete()) { "Unable to save transfer manifest" }
        }
    }

    private companion object {
        const val ACK_INTERVAL_BYTES = 16L * 1024 * 1024
        const val STORAGE_RESERVE_BYTES = 128L * 1024 * 1024
        const val STALE_TRANSFER_AGE_MILLIS = 7L * 24 * 60 * 60 * 1_000
    }
}
