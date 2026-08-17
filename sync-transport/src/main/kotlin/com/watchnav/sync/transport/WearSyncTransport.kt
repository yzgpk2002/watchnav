package com.watchnav.sync.transport

import android.content.Context
import androidx.annotation.WorkerThread
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.watchnav.sync.protocol.DeviceHello
import com.watchnav.sync.protocol.Hashing
import com.watchnav.sync.protocol.InventoryPage
import com.watchnav.sync.protocol.SyncRecord
import com.watchnav.sync.protocol.SyncWireCodec
import com.watchnav.sync.protocol.TransferCheckpoint
import com.watchnav.sync.protocol.TransferManifest
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WearSyncTransport(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val dataClient = Wearable.getDataClient(applicationContext)
    private val messageClient = Wearable.getMessageClient(applicationContext)
    private val nodeClient = Wearable.getNodeClient(applicationContext)
    private val channelClient = Wearable.getChannelClient(applicationContext)
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    fun connectedNodes(): Task<List<Node>> = nodeClient.connectedNodes

    fun publishHello(value: DeviceHello, urgent: Boolean = true): Task<*> =
        putData(WearSyncPaths.hello(value.deviceId), SyncWireCodec.encodeHello(value), urgent)

    fun publishInventory(deviceId: String, value: InventoryPage, urgent: Boolean = false): Task<*> =
        putData(WearSyncPaths.inventory(deviceId, value.cursor), SyncWireCodec.encodeInventory(value), urgent)

    fun publishRecord(value: SyncRecord, urgent: Boolean = false): Task<*> =
        putData(WearSyncPaths.record(value.type.name, value.id), SyncWireCodec.encodeRecord(value), urgent)

    fun publishCheckpoint(value: TransferCheckpoint, urgent: Boolean = true): Task<*> =
        putData(
            WearSyncPaths.transferCheckpoint(value.transferId),
            SyncWireCodec.encodeTransferCheckpoint(value),
            urgent,
        )

    fun requestSync(nodeId: String): Task<Int> =
        messageClient.sendMessage(nodeId, WearSyncPaths.MESSAGE_SYNC_REQUEST, byteArrayOf())

    fun offerTransfer(nodeId: String, manifest: TransferManifest): Task<Int> {
        require(manifest.offlineAllowed) { "This layer license does not allow offline transfer" }
        val payload = SyncWireCodec.encodeTransferManifest(manifest)
        putData(WearSyncPaths.transferManifest(manifest.transferId), payload, urgent = true)
        return messageClient.sendMessage(nodeId, WearSyncPaths.MESSAGE_TRANSFER_OFFER, payload)
    }

    fun sendCheckpoint(nodeId: String, checkpoint: TransferCheckpoint): Task<Int> =
        messageClient.sendMessage(
            nodeId,
            WearSyncPaths.MESSAGE_TRANSFER_CHECKPOINT,
            SyncWireCodec.encodeTransferCheckpoint(checkpoint),
        )

    fun sendFile(
        nodeId: String,
        manifest: TransferManifest,
        file: File,
        offset: Long,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Task<Void> {
        val result = TaskCompletionSource<Void>()
        io.execute {
            runCatching {
                validateSource(file, manifest, offset)
                val channel = Tasks.await(channelClient.openChannel(nodeId, WearSyncPaths.channel(manifest.transferId, offset)))
                try {
                    val output = Tasks.await(channelClient.getOutputStream(channel))
                    FileInputStream(file).buffered().use { input ->
                        skipFully(input, offset)
                        output.buffered().use { sink ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var sent = offset
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                sink.write(buffer, 0, count)
                                sent += count
                                onProgress(sent, manifest.totalBytes)
                            }
                        }
                    }
                } finally {
                    runCatching { Tasks.await(channelClient.close(channel)) }
                }
            }.onSuccess { result.setResult(null) }
                .onFailure { error ->
                    result.setException(error as? Exception ?: RuntimeException(error))
                }
        }
        return result.task
    }

    private fun putData(path: String, bytes: ByteArray, urgent: Boolean): Task<*> {
        val request = PutDataRequest.create(path).setData(bytes).apply { if (urgent) setUrgent() }
        return dataClient.putDataItem(request)
    }

    @WorkerThread
    private fun validateSource(file: File, manifest: TransferManifest, offset: Long) {
        require(file.isFile) { "Transfer source is missing" }
        require(file.length() == manifest.totalBytes) { "Transfer source size changed" }
        require(offset in 0..manifest.totalBytes) { "Invalid resume offset" }
        require(file.inputStream().buffered().use(Hashing::sha256) == manifest.sha256) {
            "Transfer source hash changed"
        }
    }

    private fun skipFully(input: InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) error("Unable to resume transfer at byte $offset")
            remaining -= skipped
        }
    }

    override fun close() {
        io.shutdownNow()
    }
}
