package com.watchnav.packages

import android.content.Context
import com.watchnav.mapsforge.MapsforgeMapRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

data class RemoteMapPackage(
    val id: String,
    val displayName: String,
    val bounds: RegionBounds,
    val url: URL,
    val approximateBytes: Long,
)

object ContourMapCatalog {
    private const val CHINA_BASE_URL =
        "https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/mapsV5/china/"

    // Deliberately omit packages grouping far-apart provinces: a rectangular
    // matcher would otherwise select a gigabyte-scale package for the wrong place.
    private val packages = listOf(
        china("hongkong-macau", "Hong Kong · Macau contours", 21.75, 113.35, 22.65, 114.55,
            "Ch-HongKong-Macau.zip", 47_726_472L),
        china("jiangsu-anhui-zhejiang", "Jiangsu · Anhui · Zhejiang contours", 26.8, 114.7, 35.4, 123.2,
            "Ch-Jiangsu-Anhui-Zhejiang.zip", 431_041_429L),
        china("jiangxi-fujian", "Jiangxi · Fujian contours", 23.3, 113.5, 31.3, 120.8,
            "Ch-Jiangxi-Fujian.zip", 652_553_417L),
        china("henan-hubei", "Henan · Hubei contours", 28.8, 108.2, 37.0, 116.8,
            "Ch-Henan-Hubei.zip", 420_719_394L),
        china("beijing-hebei-shandong", "Beijing · Hebei · Shandong contours", 34.1, 113.2, 42.7, 122.8,
            "Ch-Beijing-Hebei-Shangdong.zip", 376_710_830L),
        china("heilongjiang-jilin-liaoning", "Northeast China contours", 38.4, 118.0, 53.7, 135.2,
            "Ch-Heilongjiang-Jilin-Liaoning.zip", 718_278_210L),
        china("shaanxi-shanxi", "Shaanxi · Shanxi contours", 31.4, 105.4, 40.9, 114.8,
            "Ch-Shaanxi-Shanxi.zip", 640_449_251L),
        china("sichuan-chongqing", "Sichuan · Chongqing contours", 25.8, 97.2, 34.6, 110.3,
            "Ch-Sichuan-Chongqing.zip", 1_226_655_203L),
        china("yunnan", "Yunnan contours", 21.0, 97.2, 29.6, 106.3,
            "Ch-Yunnan.zip", 950_817_626L),
        china("tibet", "Tibet contours", 26.4, 78.0, 36.6, 100.0,
            "Ch-Tibet.zip", 1_328_127_416L),
        china("xinjiang", "Xinjiang contours", 34.0, 73.0, 50.0, 97.0,
            "Ch-Xinjiang.zip", 1_082_870_316L),
    )

    fun covering(latitude: Double, longitude: Double): RemoteMapPackage? = packages.firstOrNull { item ->
        latitude in item.bounds.south..item.bounds.north &&
            longitude in item.bounds.west..item.bounds.east
    }

    private fun china(
        id: String,
        displayName: String,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        fileName: String,
        bytes: Long,
    ) = RemoteMapPackage(
        id = "china-$id",
        displayName = displayName,
        bounds = RegionBounds(south, west, north, east),
        url = URL(CHINA_BASE_URL + fileName),
        approximateBytes = bytes,
    )
}

class DirectMapPackageInstaller(context: Context) {
    private val staging = File(context.filesDir, "regional-map-download")
    private val mapRepository = MapsforgeMapRepository(context.applicationContext)

    fun install(
        mapPackage: RemoteMapPackage,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        staging.mkdirs()
        check(staging.isDirectory) { "Unable to create map download storage" }
        val archive = File(staging, "${mapPackage.id}.zip")
        val extracted = File(staging, "${mapPackage.id}.map")
        try {
            download(mapPackage.url, archive, onProgress)
            extractSingleMap(archive, extracted)
            return mapRepository.installVerified(extracted)
        } finally {
            archive.delete()
            extracted.delete()
        }
    }

    private fun download(
        source: URL,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ) = withTransientRetries {
        downloadOnce(source, destination, onProgress)
    }

    private fun downloadOnce(
        source: URL,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        var url = requireHttps(source)
        repeat(MAXIMUM_REDIRECTS + 1) { redirectCount ->
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "WatchNav/0.9 (Wear OS offline map client)")
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val total = connection.contentLengthLong
                        var written = 0L
                        connection.inputStream.buffered().use { input ->
                            destination.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    written += count
                                    require(written <= MAXIMUM_ARCHIVE_BYTES) { "Map archive is too large" }
                                    output.write(buffer, 0, count)
                                    onProgress(written, total)
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
                        if (redirectCount == MAXIMUM_REDIRECTS) error("Too many redirects")
                        url = requireHttps(URL(url, connection.getHeaderField("Location") ?: error("Missing redirect URL")))
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

    private fun <T> withTransientRetries(block: () -> T): T {
        for (attempt in 1..HttpRetryPolicy.MAXIMUM_ATTEMPTS) {
            try {
                return block()
            } catch (error: RetryableHttpException) {
                if (attempt == HttpRetryPolicy.MAXIMUM_ATTEMPTS) throw error
                Thread.sleep(HttpRetryPolicy.delayMillis(attempt, error.retryAfterMillis))
            }
        }
        error("Map package retry loop ended unexpectedly")
    }

    private fun extractSingleMap(archive: File, destination: File) {
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

    private fun requireHttps(url: URL): URL {
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) {
            "Map package URL must use HTTPS"
        }
        return url
    }

    private companion object {
        const val MAXIMUM_REDIRECTS = 5
        const val MAXIMUM_ARCHIVE_BYTES = 2_000_000_000L
        const val MAXIMUM_MAP_BYTES = 2_000_000_000L
    }
}
