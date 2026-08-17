package com.watchnav.sync.transport

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.watchnav.sync.protocol.DeviceHello
import com.watchnav.sync.protocol.InventoryPage
import com.watchnav.sync.protocol.SyncRecord
import com.watchnav.sync.protocol.SyncWireCodec
import com.watchnav.sync.protocol.TransferCheckpoint
import com.watchnav.sync.protocol.TransferManifest
import com.watchnav.sync.protocol.TransferState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

abstract class BaseWearSyncListenerService : WearableListenerService() {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val transport by lazy { WearSyncTransport(this) }
    private val incoming by lazy { IncomingTransferStore(this) }

    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach { event ->
            val path = event.dataItem.uri.path.orEmpty()
            val bytes = event.dataItem.data ?: return@forEach
            runCatching {
                when {
                    path.startsWith(WearSyncPaths.HELLO_PREFIX) -> onHello(SyncWireCodec.decodeHello(bytes))
                    path.startsWith(WearSyncPaths.INVENTORY_PREFIX) -> onInventory(SyncWireCodec.decodeInventory(bytes))
                    path.startsWith(WearSyncPaths.RECORD_PREFIX) -> onRecord(SyncWireCodec.decodeRecord(bytes))
                    path.startsWith(WearSyncPaths.TRANSFER_MANIFEST_PREFIX) -> {
                        val manifest = SyncWireCodec.decodeTransferManifest(bytes)
                        val checkpoint = existingTransferCheckpoint(manifest) ?: accept(manifest)
                        onTransferOffered(manifest, checkpoint)
                        event.dataItem.uri.host?.let { transport.sendCheckpoint(it, checkpoint) }
                    }
                    path.startsWith(WearSyncPaths.TRANSFER_CHECKPOINT_PREFIX) ->
                        onTransferCheckpoint(SyncWireCodec.decodeTransferCheckpoint(bytes), event.dataItem.uri.host)
                }
            }.onFailure { onSyncError(path, it) }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        runCatching {
            when (event.path) {
                WearSyncPaths.MESSAGE_SYNC_REQUEST -> onSyncRequested(event.sourceNodeId)
                WearSyncPaths.MESSAGE_TRANSFER_OFFER -> {
                    val manifest = SyncWireCodec.decodeTransferManifest(event.data)
                    val checkpoint = existingTransferCheckpoint(manifest) ?: accept(manifest)
                    onTransferOffered(manifest, checkpoint)
                    transport.sendCheckpoint(event.sourceNodeId, checkpoint)
                }
                WearSyncPaths.MESSAGE_TRANSFER_CHECKPOINT ->
                    onTransferCheckpoint(SyncWireCodec.decodeTransferCheckpoint(event.data), event.sourceNodeId)
            }
        }.onFailure { onSyncError(event.path, it) }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        io.execute {
            val sourceNode = channel.nodeId
            incoming.receive(channel) { checkpoint ->
                transport.sendCheckpoint(sourceNode, checkpoint)
                onTransferCheckpoint(checkpoint, sourceNode)
            }.onSuccess { received -> if (received != null) onTransferReady(received) }
                .onFailure { error ->
                    WearSyncPaths.parseChannel(channel.path)?.first?.let { id ->
                        incoming.manifest(id)?.let { manifest ->
                            val checkpoint = incoming.checkpointFor(manifest, TransferState.FAILED, error.message.orEmpty())
                            transport.sendCheckpoint(sourceNode, checkpoint)
                            onTransferCheckpoint(checkpoint, sourceNode)
                        }
                    }
                    onSyncError(channel.path, error)
                }
            runCatching { com.google.android.gms.tasks.Tasks.await(Wearable.getChannelClient(this).close(channel)) }
        }
    }

    protected open fun onHello(value: DeviceHello) = Unit
    protected open fun onInventory(value: InventoryPage) = Unit
    protected open fun onRecord(value: SyncRecord) = Unit
    protected open fun onSyncRequested(sourceNodeId: String) = Unit
    protected open fun onTransferOffered(manifest: TransferManifest, checkpoint: TransferCheckpoint) = Unit
    protected open fun existingTransferCheckpoint(manifest: TransferManifest): TransferCheckpoint? = null
    protected open fun onTransferCheckpoint(value: TransferCheckpoint, sourceNodeId: String? = null) = Unit
    protected open fun onTransferReady(value: ReceivedTransfer) = Unit
    protected open fun onSyncError(path: String, error: Throwable) = Unit

    override fun onDestroy() {
        transport.close()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun accept(manifest: TransferManifest): TransferCheckpoint {
        val offset = incoming.saveManifest(manifest)
        return TransferCheckpoint(
            manifest.transferId,
            TransferState.ACCEPTED,
            offset,
            manifest.totalBytes,
            System.currentTimeMillis(),
        )
    }
}
