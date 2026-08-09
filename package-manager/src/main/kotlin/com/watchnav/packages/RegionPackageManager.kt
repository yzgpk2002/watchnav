package com.watchnav.packages

import android.content.Context
import android.os.StatFs
import com.watchnav.brouter.BRouterOfflineRouter
import com.watchnav.mapsforge.MapsforgeMapRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class PackageInstallProgress(
    val artifactIndex: Int,
    val artifactCount: Int,
    val artifactId: String,
    val downloadedBytes: Long,
    val artifactBytes: Long,
)

class RegionPackageManager(
    context: Context,
    private val verifier: RegionManifestVerifier,
) {
    private val applicationContext = context.applicationContext
    private val root = File(applicationContext.filesDir, "region-packages")
    private val staging = File(root, "staging")
    private val stateFile = File(root, "installed.json")
    private val router = BRouterOfflineRouter(applicationContext)
    private val mapRepository = MapsforgeMapRepository(applicationContext)
    private val downloader = ResumableDownloader()

    fun fetchManifest(rawUrl: String): RegionManifest {
        val url = validateHttps(rawUrl)
        val bytes = readBounded(url, MAXIMUM_MANIFEST_BYTES)
        return verifier.verify(bytes)
    }

    fun install(
        manifest: RegionManifest,
        onProgress: (PackageInstallProgress) -> Unit = {},
    ) {
        require(manifest.version > (installedVersion(manifest.packageId) ?: 0L)) {
            "This package version is already installed"
        }
        val freeBytes = StatFs(applicationContext.filesDir.absolutePath).availableBytes
        require(freeBytes >= manifest.requiredBytes + INSTALL_RESERVE_BYTES) {
            "Not enough storage for region package"
        }
        val packageStaging = File(staging, "${manifest.packageId}-${manifest.version}")
        packageStaging.mkdirs()
        val downloaded = manifest.artifacts.mapIndexed { index, artifact ->
            val file = File(packageStaging, "${artifact.type}-${artifact.id}.bin")
            downloader.download(artifact, file) { current, total ->
                onProgress(
                    PackageInstallProgress(
                        artifactIndex = index + 1,
                        artifactCount = manifest.artifacts.size,
                        artifactId = artifact.id,
                        downloadedBytes = current,
                        artifactBytes = total,
                    ),
                )
            }
            artifact to file
        }
        val routingBackups = ArrayList<Pair<File, File>>()
        try {
            downloaded.forEach { (artifact, file) ->
                when (artifact.type) {
                    RegionArtifactType.MAPSFORGE -> mapRepository.installVerified(file)
                    RegionArtifactType.BROUTER_RD5 -> {
                        val target = router.regionFile(artifact.id)
                        target.parentFile?.mkdirs()
                        if (target.isFile) {
                            val backup = File(target.parentFile, "${target.name}.rollback")
                            target.copyTo(backup, overwrite = true)
                            routingBackups += target to backup
                        }
                        file.copyTo(target, overwrite = true)
                    }
                }
            }
            saveInstalled(manifest)
            packageStaging.deleteRecursively()
        } catch (error: Exception) {
            mapRepository.rollback()
            routingBackups.forEach { (target, backup) ->
                if (backup.isFile) backup.copyTo(target, overwrite = true)
            }
            throw error
        }
    }

    fun installedPackages(): List<RegionManifest> {
        if (!stateFile.isFile) return emptyList()
        val array = JSONArray(stateFile.readText())
        return (0 until array.length()).map { index -> parseState(array.getJSONObject(index)) }
    }

    fun delete(packageId: String) {
        val installed = installedPackages()
        val target = installed.firstOrNull { it.packageId == packageId } ?: return
        target.artifacts.filter { it.type == RegionArtifactType.BROUTER_RD5 }.forEach { artifact ->
            router.regionFile(artifact.id).delete()
        }
        writeState(installed.filterNot { it.packageId == packageId })
    }

    private fun installedVersion(packageId: String): Long? =
        installedPackages().firstOrNull { it.packageId == packageId }?.version

    private fun saveInstalled(manifest: RegionManifest) {
        val retained = installedPackages().filterNot { it.packageId == manifest.packageId }
        writeState(retained + manifest)
    }

    private fun writeState(packages: List<RegionManifest>) {
        root.mkdirs()
        val temporary = File(root, "installed.json.tmp")
        temporary.writeText(JSONArray(packages.map(::toState)).toString())
        if (!temporary.renameTo(stateFile)) {
            temporary.copyTo(stateFile, overwrite = true)
            check(temporary.delete()) { "Unable to save package state" }
        }
    }

    private fun toState(manifest: RegionManifest): JSONObject = JSONObject().apply {
        put("packageId", manifest.packageId)
        put("displayName", manifest.displayName)
        put("version", manifest.version)
        put("keyId", manifest.keyId)
        put("bounds", JSONArray(listOf(
            manifest.bounds.south,
            manifest.bounds.west,
            manifest.bounds.north,
            manifest.bounds.east,
        )))
        put("artifacts", JSONArray(manifest.artifacts.map { artifact ->
            JSONObject().apply {
                put("type", artifact.type.name)
                put("id", artifact.id)
                put("url", artifact.url.toString())
                put("sha256", artifact.sha256)
                put("bytes", artifact.bytes)
            }
        }))
    }

    private fun parseState(json: JSONObject): RegionManifest {
        val bounds = json.getJSONArray("bounds")
        val artifacts = json.getJSONArray("artifacts")
        return RegionManifest(
            packageId = json.getString("packageId"),
            displayName = json.getString("displayName"),
            version = json.getLong("version"),
            bounds = RegionBounds(
                bounds.getDouble(0),
                bounds.getDouble(1),
                bounds.getDouble(2),
                bounds.getDouble(3),
            ),
            artifacts = (0 until artifacts.length()).map { index ->
                artifacts.getJSONObject(index).let { artifact ->
                    RegionArtifact(
                        type = RegionArtifactType.valueOf(artifact.getString("type")),
                        id = artifact.getString("id"),
                        url = URL(artifact.getString("url")),
                        sha256 = artifact.getString("sha256"),
                        bytes = artifact.getLong("bytes"),
                    )
                }
            },
            keyId = json.getString("keyId"),
        )
    }

    private fun readBounded(initialUrl: URL, maximumBytes: Int): ByteArray {
        var url = initialUrl
        repeat(MAXIMUM_REDIRECTS + 1) { redirectCount ->
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= maximumBytes) { "Manifest is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        if (redirectCount == MAXIMUM_REDIRECTS) error("Too many redirects")
                        url = validateHttps(URL(url, connection.getHeaderField("Location")).toString())
                    }
                    else -> error("Server returned HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }

    private fun validateHttps(rawUrl: String): URL {
        val url = URL(rawUrl.trim())
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) {
            "Only HTTPS package links are supported"
        }
        return url
    }

    private companion object {
        const val MAXIMUM_MANIFEST_BYTES = 256 * 1024
        const val MAXIMUM_REDIRECTS = 5
        const val INSTALL_RESERVE_BYTES = 128L * 1024 * 1024
    }
}
