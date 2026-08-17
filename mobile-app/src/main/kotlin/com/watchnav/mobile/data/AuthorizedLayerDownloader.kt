package com.watchnav.mobile.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Downloads a user-authorized offline artifact with HTTP Range resume support. */
class AuthorizedLayerDownloader(context: Context) {
    private val directory = File(context.filesDir, "authorized-downloads")

    fun download(rawUrl: String, fileName: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        var url = validate(URL(rawUrl.trim()))
        directory.mkdirs()
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(150)
            .ifBlank { "offline-layer.bin" }
        val complete = File(directory, safeName)
        val partial = File(directory, "$safeName.part")
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val offset = partial.length()
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 90_000
                setRequestProperty("User-Agent", "WatchNav/0.15 authorized offline layer downloader")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL -> {
                        val append = code == HttpURLConnection.HTTP_PARTIAL && offset > 0
                        if (!append && offset > 0) partial.delete()
                        var written = if (append) offset else 0L
                        val total = connection.getHeaderFieldLong("Content-Length", -1L)
                            .takeIf { it >= 0 }?.plus(written) ?: -1L
                        connection.inputStream.buffered().use { input ->
                            FileOutputStream(partial, append).buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    written += count
                                    require(written <= MAX_BYTES) { "Layer download exceeds 2 GB" }
                                    output.write(buffer, 0, count)
                                    onProgress(written, total)
                                }
                            }
                        }
                        require(partial.length() > 0) { "Downloaded layer is empty" }
                        if (!partial.renameTo(complete)) {
                            partial.copyTo(complete, overwrite = true)
                            check(partial.delete())
                        }
                        return complete
                    }
                    HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER, 307, 308 -> {
                        require(redirect < MAX_REDIRECTS) { "Too many redirects" }
                        url = validate(URL(url, connection.getHeaderField("Location") ?: error("Missing redirect")))
                    }
                    else -> error("Download server returned HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }

    private fun validate(url: URL): URL {
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) {
            "Only authorized HTTPS downloads are supported"
        }
        return url
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_BYTES = 2_000_000_000L
    }
}
