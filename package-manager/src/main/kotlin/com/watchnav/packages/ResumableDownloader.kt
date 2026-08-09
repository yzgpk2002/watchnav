package com.watchnav.packages

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal class ResumableDownloader(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 45_000,
) {
    fun download(artifact: RegionArtifact, destination: File, onProgress: (Long, Long) -> Unit) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.part")
        require(partial.length() <= artifact.bytes) { "Partial download exceeds expected size" }
        if (partial.length() == artifact.bytes) {
            runCatching { verify(partial, artifact) }.getOrElse { error ->
                partial.delete()
                throw error
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                check(partial.delete()) { "Unable to finalize package artifact" }
            }
            return
        }
        var url = artifact.url
        repeat(MAXIMUM_REDIRECTS + 1) { redirectCount ->
            val offset = partial.length()
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                requestMethod = "GET"
                setRequestProperty("User-Agent", "WatchNav/0.6")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK,
                    HttpURLConnection.HTTP_PARTIAL,
                    -> {
                        val append = code == HttpURLConnection.HTTP_PARTIAL && offset > 0
                        if (append) {
                            require(connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) {
                                "Server returned an invalid byte range"
                            }
                        }
                        if (!append && offset > 0) partial.delete()
                        receive(connection, partial, artifact, append, onProgress)
                        runCatching { verify(partial, artifact) }.getOrElse { error ->
                            partial.delete()
                            throw error
                        }
                        if (!partial.renameTo(destination)) {
                            partial.copyTo(destination, overwrite = true)
                            check(partial.delete()) { "Unable to finalize package artifact" }
                        }
                        return
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        if (redirectCount == MAXIMUM_REDIRECTS) throw IOException("Too many redirects")
                        val next = URL(url, connection.getHeaderField("Location") ?: error("Missing redirect"))
                        require(next.protocol == "https" && next.userInfo == null) { "Unsafe redirect" }
                        url = next
                    }
                    else -> throw IOException("Server returned HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun receive(
        connection: HttpsURLConnection,
        partial: File,
        artifact: RegionArtifact,
        append: Boolean,
        onProgress: (Long, Long) -> Unit,
    ) {
        var written = if (append) partial.length() else 0L
        connection.inputStream.use { input ->
            java.io.FileOutputStream(partial, append).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    require(written <= artifact.bytes) { "Download exceeds declared size" }
                    output.write(buffer, 0, count)
                    onProgress(written, artifact.bytes)
                }
            }
        }
        require(written == artifact.bytes) { "Downloaded size does not match manifest" }
    }

    private fun verify(file: File, artifact: RegionArtifact) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == artifact.sha256) { "SHA-256 verification failed for ${artifact.id}" }
    }

    private companion object {
        const val MAXIMUM_REDIRECTS = 5
    }
}
