package com.watchnav.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class DownloadedGpx(
    val bytes: ByteArray,
    val fileName: String,
)

internal class GpxDownloader(
    private val maximumBytes: Int = 16 * 1024 * 1024,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) {
    fun download(rawUrl: String): DownloadedGpx {
        var url = validateUrl(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                requestMethod = "GET"
                setRequestProperty("Accept", "application/gpx+xml, application/xml, text/xml, */*")
                setRequestProperty("User-Agent", "WatchNav/0.9")
            }
            try {
                when (val responseCode = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return readResponse(connection, url)
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        if (redirectCount == MAX_REDIRECTS) {
                            throw IOException("Too many redirects")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Redirect has no destination")
                        url = validateUrl(URL(url, location).toString())
                    }
                    else -> throw IOException("Server returned HTTP $responseCode")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun readResponse(connection: HttpsURLConnection, url: URL): DownloadedGpx {
        val declaredLength = connection.contentLengthLong
        if (declaredLength > maximumBytes) {
            throw IOException("GPX is larger than ${maximumBytes / (1024 * 1024)} MB")
        }

        val initialSize = when {
            declaredLength in 1..maximumBytes.toLong() -> declaredLength.toInt()
            else -> DEFAULT_BUFFER_SIZE
        }
        val output = ByteArrayOutputStream(initialSize)
        connection.inputStream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                totalBytes += count
                if (totalBytes > maximumBytes) {
                    throw IOException("GPX is larger than ${maximumBytes / (1024 * 1024)} MB")
                }
                output.write(buffer, 0, count)
            }
        }
        if (output.size() == 0) throw IOException("Server returned an empty file")

        val name = url.path.substringAfterLast('/').takeIf { it.endsWith(".gpx", ignoreCase = true) }
            ?: "downloaded.gpx"
        return DownloadedGpx(output.toByteArray(), name)
    }

    private fun validateUrl(rawUrl: String): URL {
        val value = rawUrl.trim()
        if (value.isEmpty()) throw IOException("Enter a GPX URL")
        val url = runCatching { URL(value) }
            .getOrElse { throw IOException("Invalid GPX URL") }
        if (url.protocol != "https") throw IOException("Only HTTPS links are supported")
        if (url.host.isBlank() || url.userInfo != null) throw IOException("Invalid GPX URL")
        return url
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
