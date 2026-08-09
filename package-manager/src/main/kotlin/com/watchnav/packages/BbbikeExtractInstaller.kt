package com.watchnav.packages

import android.content.Context
import com.watchnav.mapsforge.MapsforgeMapRepository
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

enum class ExtractStage {
    SUBMITTING,
    WAITING,
    RETRYING,
    DOWNLOADING,
    VERIFYING,
}

data class ExtractProgress(
    val stage: ExtractStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val retryAttempt: Int = 0,
    val retryDelayMillis: Long = 0,
)

/**
 * Creates a user-selected Mapsforge extract through BBBike's public end-user service,
 * waits for the asynchronous job, downloads the resulting archive, and atomically
 * installs the validated map. Public OSM tile servers are deliberately not scraped.
 */
class BbbikeExtractInstaller(context: Context) {
    private val applicationContext = context.applicationContext
    private val stagingDirectory = File(applicationContext.filesDir, "custom-map-extract")
    private val mapRepository = MapsforgeMapRepository(applicationContext)

    fun requestAndInstall(
        bounds: RegionBounds,
        onProgress: (ExtractProgress) -> Unit = {},
    ): File {
        require(bounds.south < bounds.north && bounds.west < bounds.east) {
            "The selected area crosses the date line"
        }
        val requestId = "watchnav-${System.currentTimeMillis()}"
        onProgress(ExtractProgress(ExtractStage.SUBMITTING))
        val retryReporter: (Int, Long) -> Unit = { attempt, delay ->
            onProgress(ExtractProgress(ExtractStage.RETRYING, retryAttempt = attempt, retryDelayMillis = delay))
        }
        val landingPage = readText(EXTRACT_URL, MAXIMUM_HTML_BYTES, retryReporter)
        val expiry = parseExpiry(landingPage) ?: error("Map service did not provide a request token")
        val response = readText(buildRequestUrl(bounds, requestId, expiry), MAXIMUM_HTML_BYTES, retryReporter)
        require(!response.contains("Area too large", ignoreCase = true)) { "Selected area is too large" }

        onProgress(ExtractProgress(ExtractStage.WAITING))
        val downloadUrl = waitForDownload(requestId, retryReporter)
        stagingDirectory.mkdirs()
        check(stagingDirectory.isDirectory) { "Unable to create map download storage" }
        val archive = File(stagingDirectory, "$requestId.zip")
        onProgress(ExtractProgress(ExtractStage.DOWNLOADING))
        val mapFile = File(stagingDirectory, "$requestId.map")
        try {
            download(downloadUrl, archive, onProgress, retryReporter)
            onProgress(ExtractProgress(ExtractStage.VERIFYING))
            extractMap(archive, mapFile)
            return mapRepository.installVerified(mapFile)
        } finally {
            archive.delete()
            mapFile.delete()
        }
    }

    private fun waitForDownload(requestId: String, onRetry: (Int, Long) -> Unit): URL {
        repeat(MAXIMUM_STATUS_POLLS) {
            val status = readText(STATUS_URL, MAXIMUM_STATUS_BYTES, onRetry)
            parseDownloadUrl(status, requestId)?.let { return URL(STATUS_URL, it) }
            Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
        }
        error("Map generation timed out; try again later")
    }

    private fun buildRequestUrl(bounds: RegionBounds, requestId: String, expiry: String): URL {
        val parameters = linkedMapOf(
            "lang" to "en",
            "sw_lng" to coordinate(bounds.west),
            "sw_lat" to coordinate(bounds.south),
            "ne_lng" to coordinate(bounds.east),
            "ne_lat" to coordinate(bounds.north),
            "format" to "mapsforge-osm.zip",
            "city" to requestId,
            "email" to "nobody",
            "as" to "-1",
            "pg" to "0",
            "coords" to "",
            "oi" to "0",
            "layers" to "",
            "submit" to "extract",
            "expire" to expiry,
        )
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return URL("$EXTRACT_URL?$query")
    }

    private fun download(
        url: URL,
        destination: File,
        onProgress: (ExtractProgress) -> Unit,
        onRetry: (Int, Long) -> Unit,
    ) = withTransientRetries(onRetry) {
        downloadOnce(url, destination, onProgress)
    }

    private fun downloadOnce(url: URL, destination: File, onProgress: (ExtractProgress) -> Unit) {
        var currentUrl = requireHttps(url)
        repeat(MAXIMUM_REDIRECTS + 1) { redirectCount ->
            val connection = (currentUrl.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val total = connection.contentLengthLong
                        var written = 0L
                        connection.inputStream.buffered().use { input ->
                            FileOutputStream(destination, false).buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    written += count
                                    require(written <= MAXIMUM_ARCHIVE_BYTES) { "Map archive is too large" }
                                    output.write(buffer, 0, count)
                                    onProgress(ExtractProgress(ExtractStage.DOWNLOADING, written, total))
                                }
                            }
                        }
                        require(written > 0) { "Map service returned an empty archive" }
                        return
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        if (redirectCount == MAXIMUM_REDIRECTS) error("Too many map download redirects")
                        val location = connection.getHeaderField("Location") ?: error("Missing redirect URL")
                        currentUrl = requireHttps(URL(currentUrl, location))
                    }
                    else -> if (HttpRetryPolicy.shouldRetry(code)) {
                        throw RetryableHttpException(
                            code,
                            HttpRetryPolicy.retryAfterMillis(connection.getHeaderField("Retry-After")),
                        )
                    } else {
                        error("Map download returned HTTP $code")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun extractMap(archive: File, destination: File) {
        var found = false
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".map", ignoreCase = true)) {
                    require(!found) { "Map archive contains multiple map files" }
                    var written = 0L
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            written += count
                            require(written <= MAXIMUM_MAP_BYTES) { "Extracted map is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                    found = true
                }
                zip.closeEntry()
            }
        }
        require(found && destination.length() > 0) { "Map archive does not contain a Mapsforge file" }
    }

    private fun readText(
        initialUrl: URL,
        maximumBytes: Int,
        onRetry: (Int, Long) -> Unit,
    ): String = withTransientRetries(onRetry) {
        readTextOnce(initialUrl, maximumBytes)
    }

    private fun readTextOnce(initialUrl: URL, maximumBytes: Int): String {
        var url = requireHttps(initialUrl)
        repeat(MAXIMUM_REDIRECTS + 1) { redirectCount ->
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        connection.inputStream.use { input ->
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= maximumBytes) { "Map service response is too large" }
                                output.write(buffer, 0, count)
                            }
                        }
                        return output.toString(Charsets.UTF_8.name())
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        if (redirectCount == MAXIMUM_REDIRECTS) error("Too many map service redirects")
                        url = requireHttps(URL(url, connection.getHeaderField("Location") ?: error("Missing redirect URL")))
                    }
                    else -> if (HttpRetryPolicy.shouldRetry(code)) {
                        throw RetryableHttpException(
                            code,
                            HttpRetryPolicy.retryAfterMillis(connection.getHeaderField("Retry-After")),
                        )
                    } else {
                        error("Map service returned HTTP $code")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many map service redirects")
    }

    private fun <T> withTransientRetries(
        onRetry: (attempt: Int, delayMillis: Long) -> Unit,
        block: () -> T,
    ): T {
        for (attempt in 1..HttpRetryPolicy.MAXIMUM_ATTEMPTS) {
            try {
                return block()
            } catch (error: RetryableHttpException) {
                if (attempt == HttpRetryPolicy.MAXIMUM_ATTEMPTS) throw error
                val delay = HttpRetryPolicy.delayMillis(attempt, error.retryAfterMillis)
                onRetry(attempt, delay)
                Thread.sleep(delay)
            }
        }
        error("Map service retry loop ended unexpectedly")
    }

    private fun requireHttps(url: URL): URL {
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) {
            "Map service URL must use HTTPS"
        }
        return url
    }

    companion object {
        private val EXTRACT_URL = URL("https://extract.bbbike.org/")
        private val STATUS_URL = URL("https://download.bbbike.org/osm/extract/?date=1h&format=mapsforge")
        private const val USER_AGENT = "WatchNav/0.9 (Wear OS offline map client)"
        private const val CONNECT_TIMEOUT_MILLIS = 20_000
        private const val READ_TIMEOUT_MILLIS = 60_000
        private const val MAXIMUM_REDIRECTS = 5
        private const val MAXIMUM_HTML_BYTES = 2 * 1024 * 1024
        private const val MAXIMUM_STATUS_BYTES = 4 * 1024 * 1024
        private const val MAXIMUM_ARCHIVE_BYTES = 2_000_000_000L
        private const val MAXIMUM_MAP_BYTES = 2_000_000_000L
        private const val STATUS_POLL_INTERVAL_MILLIS = 30_000L
        private const val MAXIMUM_STATUS_POLLS = 40

        internal fun parseExpiry(html: String): String? = Regex(
            "name=[\\\"']expire[\\\"'][^>]*value=[\\\"'](\\d+)[\\\"']",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)

        internal fun parseDownloadUrl(html: String, requestId: String): String? {
            val escaped = Regex.escape(requestId)
            val row = Regex(
                "<tr[^>]*>.*?<span[^>]*title=[\\\"']$escaped[\\\"'][^>]*>.*?</tr>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(html)?.value ?: return null
            if (!row.contains("format_mapsforge_osm_zip")) return null
            return Regex("href=[\\\"']([^\\\"']+\\.mapsforge-osm(?:\\.[a-z]{2})?\\.zip)[\\\"']", RegexOption.IGNORE_CASE)
                .find(row)?.groupValues?.get(1)
        }

        private fun coordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
