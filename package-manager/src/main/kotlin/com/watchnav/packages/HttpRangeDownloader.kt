package com.watchnav.packages

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** A small HTTP Range client that deliberately keeps [destination] after failures. */
internal object HttpRangeDownloader {
    fun download(
        source: URL,
        destination: File,
        maximumBytes: Long,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        userAgent: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        var currentUrl = requireHttps(source)
        repeat(MAXIMUM_REDIRECTS + 1) { redirectCount ->
            val offset = destination.length()
            require(offset <= maximumBytes) { "Partial download is too large" }
            val connection = (currentUrl.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                setRequestProperty("User-Agent", userAgent)
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK,
                    HttpURLConnection.HTTP_PARTIAL,
                    -> {
                        val append = code == HttpURLConnection.HTTP_PARTIAL && offset > 0L
                        if (append) {
                            val range = connection.getHeaderField("Content-Range").orEmpty()
                            if (!range.startsWith("bytes $offset-")) {
                                throw IOException("Server returned an invalid byte range")
                            }
                        } else if (offset > 0L) {
                            // Server ignored Range. Restart safely with the complete response.
                            destination.delete()
                        }
                        val initial = if (append) offset else 0L
                        val total = contentTotal(connection, initial)
                        var written = initial
                        connection.inputStream.buffered().use { input ->
                            FileOutputStream(destination, append).buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    written += count
                                    if (written > maximumBytes) throw IOException("Download is too large")
                                    output.write(buffer, 0, count)
                                    onProgress(written, total)
                                }
                            }
                        }
                        if (written <= 0L) throw IOException("Server returned an empty file")
                        if (total > 0L && written != total) {
                            throw IOException("Download interrupted at $written of $total bytes")
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
                        currentUrl = requireHttps(
                            URL(currentUrl, connection.getHeaderField("Location") ?: throw IOException("Missing redirect URL")),
                        )
                    }
                    416 -> {
                        // A stale or already-complete partial cannot be trusted without a checksum.
                        destination.delete()
                        throw IOException("Server rejected the saved download position; retrying from zero")
                    }
                    else -> throw IOException("Download server returned HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun contentTotal(connection: HttpsURLConnection, offset: Long): Long {
        val rangeTotal = connection.getHeaderField("Content-Range")
            ?.substringAfter('/', "")
            ?.toLongOrNull()
        if (rangeTotal != null) return rangeTotal
        return connection.contentLengthLong.takeIf { it >= 0L }?.plus(offset) ?: -1L
    }

    private fun requireHttps(url: URL): URL {
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) {
            "Download URL must use HTTPS"
        }
        return url
    }

    private const val MAXIMUM_REDIRECTS = 5
}
