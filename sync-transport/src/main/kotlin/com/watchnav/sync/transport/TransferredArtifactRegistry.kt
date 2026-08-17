package com.watchnav.sync.transport

import android.content.Context
import com.watchnav.sync.protocol.TransferCheckpoint
import com.watchnav.sync.protocol.TransferKind
import com.watchnav.sync.protocol.TransferManifest
import com.watchnav.sync.protocol.TransferState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Fast local file inventory used to avoid retransmitting layers already present on the peer. */
class TransferredArtifactRegistry(context: Context) {
    private val applicationContext = context.applicationContext
    private val catalog = File(applicationContext.filesDir, "sync/artifacts.json")

    @Synchronized
    fun record(manifest: TransferManifest, file: File, state: TransferState) {
        require(state == TransferState.INSTALLED || state == TransferState.RECEIVED)
        requireOwnedFile(file)
        val entry = ArtifactEntry(
            manifest.transferId,
            manifest.packageId,
            manifest.kind,
            manifest.displayName,
            manifest.sha256,
            manifest.totalBytes,
            file.canonicalPath,
            state,
            System.currentTimeMillis(),
        )
        write(read().filterNot { it.kind == entry.kind && it.sha256 == entry.sha256 } + entry)
    }

    @Synchronized
    fun checkpointIfAvailable(manifest: TransferManifest): TransferCheckpoint? {
        val values = read()
        val match = values.firstOrNull {
            it.kind == manifest.kind && it.sha256 == manifest.sha256 && it.totalBytes == manifest.totalBytes
        } ?: return null
        val file = File(match.filePath)
        if (!file.isFile || file.length() != manifest.totalBytes || !isOwnedFile(file)) {
            write(values - match)
            return null
        }
        return TransferCheckpoint(
            manifest.transferId,
            match.state,
            manifest.totalBytes,
            manifest.totalBytes,
            System.currentTimeMillis(),
            if (match.state == TransferState.INSTALLED) "Already installed" else "Stored locally",
        )
    }

    @Synchronized
    fun entries(): List<ArtifactEntry> = read().filter { entry ->
        File(entry.filePath).let { it.isFile && it.length() == entry.totalBytes && isOwnedFile(it) }
    }

    private fun requireOwnedFile(file: File) {
        require(file.isFile && file.length() > 0 && isOwnedFile(file)) { "Artifact must be app-private" }
    }

    private fun isOwnedFile(file: File): Boolean = runCatching {
        val source = file.canonicalFile
        listOf(applicationContext.filesDir, applicationContext.cacheDir).map(File::getCanonicalFile)
            .any { source.path == it.path || source.path.startsWith(it.path + File.separator) }
    }.getOrDefault(false)

    private fun read(): List<ArtifactEntry> = runCatching {
        if (!catalog.isFile) return@runCatching emptyList()
        val array = JSONArray(catalog.readText())
        (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            ArtifactEntry(
                json.getString("transferId"),
                json.getString("packageId"),
                TransferKind.valueOf(json.getString("kind")),
                json.getString("displayName"),
                json.getString("sha256"),
                json.getLong("totalBytes"),
                json.getString("filePath"),
                TransferState.valueOf(json.getString("state")),
                json.getLong("updatedAtMillis"),
            )
        }
    }.getOrDefault(emptyList())

    private fun write(values: List<ArtifactEntry>) {
        catalog.parentFile?.mkdirs()
        val array = JSONArray(values.takeLast(MAXIMUM_ENTRIES).map { entry -> JSONObject().apply {
            put("transferId", entry.transferId)
            put("packageId", entry.packageId)
            put("kind", entry.kind.name)
            put("displayName", entry.displayName)
            put("sha256", entry.sha256)
            put("totalBytes", entry.totalBytes)
            put("filePath", entry.filePath)
            put("state", entry.state.name)
            put("updatedAtMillis", entry.updatedAtMillis)
        } })
        val temporary = File(catalog.parentFile, catalog.name + ".tmp").apply { writeText(array.toString()) }
        if (!temporary.renameTo(catalog)) {
            temporary.copyTo(catalog, overwrite = true)
            check(temporary.delete())
        }
    }

    data class ArtifactEntry(
        val transferId: String,
        val packageId: String,
        val kind: TransferKind,
        val displayName: String,
        val sha256: String,
        val totalBytes: Long,
        val filePath: String,
        val state: TransferState,
        val updatedAtMillis: Long,
    )

    private companion object { const val MAXIMUM_ENTRIES = 512 }
}
