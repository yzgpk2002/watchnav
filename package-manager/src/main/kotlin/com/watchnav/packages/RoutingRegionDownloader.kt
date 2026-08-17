package com.watchnav.packages

import android.content.Context
import com.watchnav.brouter.BRouterOfflineRouter
import com.watchnav.core.GeoPoint
import java.io.File
import java.io.IOException
import java.net.URL

data class RoutingRegionDownloadProgress(
    val regionIndex: Int,
    val regionCount: Int,
    val regionId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

class RoutingRegionDownloader(context: Context) {
    private val applicationContext = context.applicationContext
    private val router = BRouterOfflineRouter(applicationContext)
    private val stagingDirectory = File(applicationContext.filesDir, "routing-download")

    fun install(
        bounds: RegionBounds,
        onProgress: (RoutingRegionDownloadProgress) -> Unit = {},
    ): List<File> = install(requiredRegionIds(bounds), onProgress)

    fun install(
        regionIds: Set<String>,
        onProgress: (RoutingRegionDownloadProgress) -> Unit = {},
    ): List<File> {
        val requested = regionIds.sorted()
        requested.forEach(::validateRegionId)
        val missing = requested.filterNot { router.regionFile(it).isFile }
        val installed = ArrayList<File>(requested.size)
        installed += requested.filterNot(missing::contains).map(router::regionFile)
        missing.forEachIndexed { index, regionId ->
            stagingDirectory.mkdirs()
            check(stagingDirectory.isDirectory) { "Unable to create routing download storage" }
            val temporary = File(stagingDirectory, "$regionId.rd5.part")
            var installedRegion = false
            try {
                download(regionId, temporary) { downloaded, total ->
                    onProgress(
                        RoutingRegionDownloadProgress(
                            regionIndex = index + 1,
                            regionCount = missing.size,
                            regionId = regionId,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                        ),
                    )
                }
                installed += temporary.inputStream().buffered().use { input ->
                    router.importRegion(regionId, input)
                }
                installedRegion = true
            } finally {
                if (installedRegion) temporary.delete()
            }
        }
        return installed
    }

    private fun download(
        regionId: String,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        var lastError: IOException? = null
        repeat(HttpRetryPolicy.MAXIMUM_ATTEMPTS) { attempt ->
            try {
                HttpRangeDownloader.download(
                    URL("$SEGMENT_BASE_URL/$regionId.rd5"),
                    destination,
                    MAXIMUM_REGION_BYTES,
                    CONNECT_TIMEOUT_MILLIS,
                    READ_TIMEOUT_MILLIS,
                    USER_AGENT,
                    onProgress,
                )
                return
            } catch (error: IOException) {
                lastError = error
                if (attempt + 1 < HttpRetryPolicy.MAXIMUM_ATTEMPTS) {
                    Thread.sleep(HttpRetryPolicy.delayMillis(attempt + 1))
                }
            }
        }
        throw lastError ?: IOException("Routing region download failed")
    }

    private fun validateRegionId(regionId: String) {
        require(regionId.matches(REGION_ID_PATTERN)) { "Invalid routing region: $regionId" }
    }

    companion object {
        private const val SEGMENT_BASE_URL = "https://brouter.de/brouter/segments4"
        private const val USER_AGENT = "WatchNav/0.9.3 (Wear OS offline routing client)"
        private const val CONNECT_TIMEOUT_MILLIS = 20_000
        private const val READ_TIMEOUT_MILLIS = 90_000
        private const val MAXIMUM_REGION_BYTES = 512L * 1024 * 1024
        private val REGION_ID_PATTERN = Regex("[EW]\\d{1,3}_[NS]\\d{1,2}")

        internal fun requiredRegionIds(bounds: RegionBounds): Set<String> {
            require(bounds.south < bounds.north && bounds.west < bounds.east)
            val east = bounds.east.coerceAtMost(Math.nextDown(180.0))
            val north = bounds.north.coerceAtMost(Math.nextDown(90.0))
            val ids = linkedSetOf<String>()
            var latitude = bounds.south
            while (latitude <= north) {
                var longitude = bounds.west
                while (longitude <= east) {
                    ids += BRouterOfflineRouter.regionId(GeoPoint(latitude, longitude))
                    val west = kotlin.math.floor(longitude / 5.0) * 5.0
                    longitude = west + 5.0
                }
                val south = kotlin.math.floor(latitude / 5.0) * 5.0
                latitude = south + 5.0
            }
            return ids
        }
    }
}
