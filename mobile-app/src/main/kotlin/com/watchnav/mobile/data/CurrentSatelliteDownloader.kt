package com.watchnav.mobile.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.watchnav.core.GeoPoint
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

data class SatelliteDownloadProgress(
    val completedTiles: Int,
    val totalTiles: Int,
    val downloadedBytes: Long,
)

/**
 * Builds a resumable regional MBTiles package from EOX's public Sentinel-2 Cloudless WMTS.
 *
 * The 2025 layer is a global, cloud-free visual mosaic. EOX permits direct WMTS use for
 * non-commercial applications under CC BY-NC-SA 4.0 when the required attribution is shown.
 * A partially populated SQLite file is retained after interruption; completed tiles are skipped
 * on the next run instead of starting the area again.
 */
class CurrentSatelliteDownloader(context: Context) {
    private val directory = File(context.filesDir, "current-satellite")

    fun download(
        center: GeoPoint,
        requestedRadiusKm: Double,
        onProgress: (SatelliteDownloadProgress) -> Unit = {},
    ): File {
        val radiusKm = requestedRadiusKm.coerceIn(MIN_RADIUS_KM, MAX_RADIUS_KM)
        val tiles = SatelliteTileCoverage.tiles(center, radiusKm, MIN_ZOOM, MAX_ZOOM)
        require(tiles.isNotEmpty() && tiles.size <= MAX_TILES) { "卫星地图下载范围过大" }
        directory.mkdirs()
        // Include the detail level in the identity. Older z14 packages were only about 5 MB;
        // reusing that filename made a new high-resolution request silently return the old file.
        val areaId = "${formatCoordinate(center.latitude)}-${formatCoordinate(center.longitude)}-${radiusKm.toInt()}km-z$MAX_ZOOM"
        val complete = File(directory, "sentinel2-cloudless-$SOURCE_YEAR-$areaId.mbtiles")
        if (complete.isFile && complete.length() > MINIMUM_COMPLETE_BYTES) return complete
        val partial = File(directory, "${complete.name}.part")
        val database = SQLiteDatabase.openOrCreateDatabase(partial, null)
        var downloadedBytes = 0L
        try {
            createSchema(database)
            writeMetadata(database, center, radiusKm)
            val pending = tiles.filterNot { contains(database, it) }
            var completed = tiles.size - pending.size
            onProgress(SatelliteDownloadProgress(completed, tiles.size, downloadedBytes))
            if (pending.isNotEmpty()) {
                val executor = Executors.newFixedThreadPool(DOWNLOAD_THREADS)
                val completion = ExecutorCompletionService<TilePayload>(executor)
                try {
                    pending.forEach { tile -> completion.submit(Callable { downloadTile(tile) }) }
                    database.beginTransaction()
                    pending.indices.forEach { index ->
                        val payload = completion.take().get()
                        insert(database, payload)
                        downloadedBytes += payload.bytes.size
                        completed++
                        if ((index + 1) % TRANSACTION_TILE_COUNT == 0) {
                            database.setTransactionSuccessful()
                            database.endTransaction()
                            database.beginTransaction()
                        }
                        onProgress(SatelliteDownloadProgress(completed, tiles.size, downloadedBytes))
                    }
                    database.setTransactionSuccessful()
                    database.endTransaction()
                } catch (error: Throwable) {
                    if (database.inTransaction()) database.endTransaction()
                    throw error
                } finally {
                    executor.shutdownNow()
                }
            }
        } finally {
            database.close()
        }
        require(partial.length() > MINIMUM_COMPLETE_BYTES) { "卫星地图为空" }
        if (!partial.renameTo(complete)) {
            partial.copyTo(complete, overwrite = true)
            check(partial.delete())
        }
        File("${partial.absolutePath}-journal").delete()
        return complete
    }

    private fun createSchema(database: SQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS tiles (" +
                "zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, " +
                "PRIMARY KEY (zoom_level, tile_column, tile_row))",
        )
    }

    private fun writeMetadata(database: SQLiteDatabase, center: GeoPoint, radiusKm: Double) {
        val bounds = SatelliteTileCoverage.bounds(center, radiusKm)
        val values = mapOf(
            "name" to "当前卫星底图 · Sentinel-2 Cloudless $SOURCE_YEAR",
            "type" to "baselayer",
            "version" to "1.3",
            "format" to "jpg",
            "scheme" to "tms",
            "minzoom" to MIN_ZOOM.toString(),
            "maxzoom" to MAX_ZOOM.toString(),
            "center" to "${center.longitude},${center.latitude},$MAX_ZOOM",
            "bounds" to "${bounds.west},${bounds.south},${bounds.east},${bounds.north}",
            "attribution" to DISPLAY_ATTRIBUTION,
            "source_url" to "https://cloudless.eox.at",
            "source_date" to SOURCE_YEAR.toString(),
            "license" to LICENSE,
        )
        values.forEach { (name, value) ->
            database.insertWithOnConflict(
                "metadata",
                null,
                ContentValues().apply { put("name", name); put("value", value) },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    private fun contains(database: SQLiteDatabase, tile: SatelliteTile): Boolean =
        database.rawQuery(
            "SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
            arrayOf(tile.zoom.toString(), tile.x.toString(), tmsRow(tile).toString()),
        ).use { it.moveToFirst() }

    private fun insert(database: SQLiteDatabase, payload: TilePayload) {
        database.insertOrThrow("tiles", null, ContentValues().apply {
            put("zoom_level", payload.tile.zoom)
            put("tile_column", payload.tile.x)
            put("tile_row", tmsRow(payload.tile))
            put("tile_data", payload.bytes)
        })
    }

    private fun downloadTile(tile: SatelliteTile): TilePayload {
        var lastError: Throwable? = null
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                val url = URL(
                    "https://tiles.maps.eox.at/wmts/1.0.0/" +
                        "s2cloudless-${SOURCE_YEAR}_3857/default/g/${tile.zoom}/${tile.y}/${tile.x}.jpg",
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                    setRequestProperty("User-Agent", "WatchNav satellite downloader; https://cloudless.eox.at")
                    setRequestProperty("Accept", "image/jpeg")
                }
                try {
                    require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "卫星瓦片 HTTP ${connection.responseCode}"
                    }
                    require(connection.contentType.orEmpty().startsWith("image/")) { "卫星服务返回的不是图片" }
                    val bytes = connection.inputStream.buffered().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_TILE_BYTES) { "卫星瓦片异常过大" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    require(bytes.size > 256 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                        "卫星瓦片 JPEG 校验失败"
                    }
                    return TilePayload(tile, bytes)
                } finally {
                    connection.disconnect()
                }
            } catch (error: Throwable) {
                lastError = error
                if (attempt + 1 < DOWNLOAD_ATTEMPTS) Thread.sleep(400L * (attempt + 1))
            }
        }
        throw requireNotNull(lastError)
    }

    private fun tmsRow(tile: SatelliteTile) = (1 shl tile.zoom) - 1 - tile.y
    private fun formatCoordinate(value: Double) = "%.3f".format(java.util.Locale.US, value).replace('.', '_')

    companion object {
        const val SOURCE_YEAR = 2025
        const val DISPLAY_ATTRIBUTION = "EOX · Contains modified Copernicus Sentinel data 2025"
        const val FULL_ATTRIBUTION = "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2025)"
        const val LICENSE = "CC BY-NC-SA 4.0 · non-commercial use · attribution required"
        const val MIN_ZOOM = 10
        // A 15 km package at z16 contains roughly 4,500 tiles (normally 50-90 MB). This remains
        // practical for phone storage while providing four times the linear detail of the old
        // z14 package. The same MBTiles can still be down-sampled efficiently on the watch.
        const val MAX_ZOOM = 16
        const val MAX_RADIUS_KM = 15.0
        private const val MIN_RADIUS_KM = 1.0
        private const val MAX_TILES = 6_000
        private const val DOWNLOAD_THREADS = 4
        private const val DOWNLOAD_ATTEMPTS = 3
        private const val TRANSACTION_TILE_COUNT = 25
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val MAX_TILE_BYTES = 1_000_000
        private const val MINIMUM_COMPLETE_BYTES = 4_096
    }
}

data class SatelliteTile(val zoom: Int, val x: Int, val y: Int)
data class SatelliteBounds(val south: Double, val west: Double, val north: Double, val east: Double)
private data class TilePayload(val tile: SatelliteTile, val bytes: ByteArray)

object SatelliteTileCoverage {
    fun bounds(center: GeoPoint, requestedRadiusKm: Double): SatelliteBounds {
        val radiusKm = requestedRadiusKm.coerceIn(1.0, CurrentSatelliteDownloader.MAX_RADIUS_KM)
        val latitudeDelta = radiusKm / 111.32
        val longitudeDelta = radiusKm / (111.32 * cos(Math.toRadians(center.latitude)).coerceAtLeast(.2))
        return SatelliteBounds(
            (center.latitude - latitudeDelta).coerceAtLeast(-85.0),
            (center.longitude - longitudeDelta).coerceAtLeast(-180.0),
            (center.latitude + latitudeDelta).coerceAtMost(85.0),
            (center.longitude + longitudeDelta).coerceAtMost(180.0),
        )
    }

    fun tiles(center: GeoPoint, radiusKm: Double, minZoom: Int, maxZoom: Int): List<SatelliteTile> {
        require(minZoom in 0..22 && maxZoom in minZoom..22)
        val bounds = bounds(center, radiusKm)
        return buildList {
            for (zoom in minZoom..maxZoom) {
                val scale = 2.0.pow(zoom)
                val minX = longitudeToTile(bounds.west, scale)
                val maxX = longitudeToTile(bounds.east, scale)
                val minY = latitudeToTile(bounds.north, scale)
                val maxY = latitudeToTile(bounds.south, scale)
                for (x in minX..maxX) for (y in minY..maxY) add(SatelliteTile(zoom, x, y))
            }
        }
    }

    private fun longitudeToTile(longitude: Double, scale: Double): Int =
        floor((longitude + 180.0) / 360.0 * scale).toInt().coerceIn(0, scale.toInt() - 1)

    private fun latitudeToTile(latitude: Double, scale: Double): Int {
        val radians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
        return floor((1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * scale)
            .toInt().coerceIn(0, scale.toInt() - 1)
    }
}
