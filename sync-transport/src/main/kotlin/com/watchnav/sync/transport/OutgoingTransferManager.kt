package com.watchnav.sync.transport

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.wearable.Node
import com.watchnav.sync.protocol.Hashing
import com.watchnav.sync.protocol.SyncWireCodec
import com.watchnav.sync.protocol.TransferCheckpoint
import com.watchnav.sync.protocol.TransferKind
import com.watchnav.sync.protocol.TransferManifest
import com.watchnav.sync.protocol.TransferState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Durable sender-side queue for resumable Data Layer channel transfers. */
class OutgoingTransferManager(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val transport = WearSyncTransport(applicationContext)
    private val activeTransfers = ConcurrentHashMap.newKeySet<String>()

    fun enqueue(
        file: File,
        kind: TransferKind,
        packageId: String,
        displayName: String,
        license: String = "",
        attribution: String = "",
        offlineAllowed: Boolean = true,
        forceNewTransfer: Boolean = false,
    ): TransferManifest {
        requireOwnedFile(file)
        val hash = file.inputStream().buffered().use(Hashing::sha256)
        val safePackage = packageId.safeIdentifier("package")
        val transferIdBase = "${kind.name.lowercase()}-$safePackage-${hash.take(16)}"
        val manifest = TransferManifest(
            transferId = if (forceNewTransfer) {
                "$transferIdBase-${System.currentTimeMillis().toString(36)}"
            } else {
                transferIdBase
            },
            packageId = safePackage,
            version = file.lastModified().coerceAtLeast(1L),
            kind = kind,
            fileName = file.name.safeFileName(),
            displayName = displayName.take(160).ifBlank { file.name },
            totalBytes = file.length(),
            sha256 = hash,
            license = license.take(512),
            attribution = attribution.take(1_024),
            offlineAllowed = offlineAllowed,
        )
        synchronized(QUEUE_LOCK) {
            save(load().filterNot {
                it.manifest.transferId == manifest.transferId ||
                    forceNewTransfer && it.manifest.kind == kind && it.manifest.packageId == safePackage
            } + PendingTransfer(manifest, file.canonicalPath))
        }
        return manifest
    }

    /**
     * Re-queues a previously verified local artifact without hashing a several-hundred-megabyte
     * map again.  A successful transfer only proves that the peer had the file at that moment;
     * it must not prevent recovery after the peer app is reinstalled or its catalog is cleared.
     */
    fun enqueueVerified(
        file: File,
        kind: TransferKind,
        packageId: String,
        displayName: String,
        sha256: String,
        totalBytes: Long,
        license: String = "",
        attribution: String = "",
        offlineAllowed: Boolean = true,
    ): TransferManifest {
        requireOwnedFile(file)
        require(totalBytes == file.length()) { "Verified transfer source size changed" }
        require(sha256.matches(SHA_256)) { "Invalid verified SHA-256" }
        val safePackage = packageId.safeIdentifier("package")
        val manifest = TransferManifest(
            transferId = "${kind.name.lowercase()}-$safePackage-${sha256.take(16)}",
            packageId = safePackage,
            version = file.lastModified().coerceAtLeast(1L),
            kind = kind,
            fileName = file.name.safeFileName(),
            displayName = displayName.take(160).ifBlank { file.name },
            totalBytes = totalBytes,
            sha256 = sha256,
            license = license.take(512),
            attribution = attribution.take(1_024),
            offlineAllowed = offlineAllowed,
        )
        synchronized(QUEUE_LOCK) {
            save(load().filterNot { it.manifest.transferId == manifest.transferId } + PendingTransfer(manifest, file.canonicalPath))
        }
        return manifest
    }

    fun offerToAll(manifest: TransferManifest, onResult: (Int) -> Unit = {}) {
        transport.connectedNodes().addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                transport.offerTransfer(node.id, manifest)
                    .addOnSuccessListener {
                        Log.i(TAG, "Offered ${manifest.transferId} to ${node.displayName}")
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Offer failed ${manifest.transferId} to ${node.displayName}", error)
                    }
            }
            onResult(nodes.size)
        }.addOnFailureListener { error ->
            Log.w(TAG, "Unable to enumerate connected Wear nodes", error)
            onResult(0)
        }
    }

    fun offer(node: Node, manifest: TransferManifest) = transport.offerTransfer(node.id, manifest)

    fun pendingManifests(): List<TransferManifest> = synchronized(QUEUE_LOCK) {
        load().map(PendingTransfer::manifest)
    }

    /** Files waiting for either Data Layer or the local Wi-Fi fallback. */
    fun pendingFiles(): List<Pair<TransferManifest, File>> = synchronized(QUEUE_LOCK) {
        load().mapNotNull { pending ->
            File(pending.filePath).takeIf { it.isFile && it.length() == pending.manifest.totalBytes }
                ?.let { pending.manifest to it }
        }
    }

    fun handleCheckpoint(sourceNodeId: String?, checkpoint: TransferCheckpoint) {
        if (sourceNodeId == null || checkpoint.state !in setOf(TransferState.ACCEPTED, TransferState.PAUSED)) return
        val pending = synchronized(QUEUE_LOCK) {
            load().firstOrNull { it.manifest.transferId == checkpoint.transferId }
        } ?: return
        val key = "$sourceNodeId:${checkpoint.transferId}"
        if (!activeTransfers.add(key)) return
        transport.sendCheckpoint(
            sourceNodeId,
            checkpoint.copy(
                state = TransferState.TRANSFERRING,
                updatedAtUtcMillis = System.currentTimeMillis(),
                message = "Streaming from byte ${checkpoint.confirmedBytes}",
            ),
        )
        transport.sendFile(sourceNodeId, pending.manifest, File(pending.filePath), checkpoint.confirmedBytes)
            .addOnCompleteListener { task ->
                activeTransfers.remove(key)
                if (!task.isSuccessful) {
                    transport.sendCheckpoint(
                        sourceNodeId,
                        checkpoint.copy(
                            state = TransferState.PAUSED,
                            updatedAtUtcMillis = System.currentTimeMillis(),
                            message = task.exception?.message.orEmpty().take(1_024),
                        ),
                    )
                }
            }
    }

    fun markTerminal(checkpoint: TransferCheckpoint) {
        val storedWithoutInstaller = checkpoint.state == TransferState.RECEIVED && checkpoint.message.startsWith("Stored")
        if (checkpoint.state !in setOf(TransferState.INSTALLED, TransferState.CANCELLED) && !storedWithoutInstaller) return
        synchronized(QUEUE_LOCK) {
            save(load().filterNot { it.manifest.transferId == checkpoint.transferId })
        }
    }

    private fun requireOwnedFile(file: File) {
        require(file.isFile && file.length() > 0) { "Transfer source is missing or empty" }
        val source = file.canonicalFile
        val roots = listOf(applicationContext.filesDir, applicationContext.cacheDir).map(File::getCanonicalFile)
        require(roots.any { source.path == it.path || source.path.startsWith(it.path + File.separator) }) {
            "Transfer source must be in app-private storage"
        }
    }

    private fun load(): List<PendingTransfer> = runCatching {
        val array = JSONArray(preferences.getString(KEY_QUEUE, "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PendingTransfer(
                SyncWireCodec.decodeTransferManifest(Base64.decode(item.getString("manifest"), Base64.NO_WRAP)),
                item.getString("filePath"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(values: List<PendingTransfer>) {
        val array = JSONArray()
        values.forEach { value ->
            array.put(JSONObject().apply {
                put("manifest", Base64.encodeToString(SyncWireCodec.encodeTransferManifest(value.manifest), Base64.NO_WRAP))
                put("filePath", value.filePath)
            })
        }
        // The listener service and the foreground coordinator own separate manager instances.
        // Commit before sending an offer so a fast ACCEPTED checkpoint can always resolve its file.
        check(preferences.edit().putString(KEY_QUEUE, array.toString()).commit()) {
            "Unable to persist outgoing transfer queue"
        }
    }

    override fun close() = transport.close()

    private data class PendingTransfer(val manifest: TransferManifest, val filePath: String)

    private fun String.safeIdentifier(fallback: String): String = replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-').take(128).ifBlank { fallback }

    private fun String.safeFileName(): String = replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-').take(160).ifBlank { "package.bin" }

    private companion object {
        const val PREFERENCES = "watchnav_outgoing_transfers"
        const val KEY_QUEUE = "queue"
        const val TAG = "WatchNavTransfer"
        val QUEUE_LOCK = Any()
        val SHA_256 = Regex("[0-9a-fA-F]{64}")
    }
}
