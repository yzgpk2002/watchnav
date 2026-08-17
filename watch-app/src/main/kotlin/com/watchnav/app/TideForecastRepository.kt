package com.watchnav.app

import android.content.Context
import com.watchnav.core.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

data class TidePoint(val timestampMillis: Long, val heightMeters: Float)

data class TideExtreme(
    val timestampMillis: Long,
    val heightMeters: Float,
    val high: Boolean,
)

enum class TideStage { FLOOD, EBB, SLACK_REFERENCE }

data class TideForecast(
    val latitude: Double,
    val longitude: Double,
    val stationName: String?,
    val points: List<TidePoint>,
    val extremes: List<TideExtreme>,
    val fetchedAtMillis: Long,
) {
    fun stage(nowMillis: Long): TideStage {
        if (extremes.any { abs(it.timestampMillis - nowMillis) <= SLACK_WINDOW_MILLIS }) {
            return TideStage.SLACK_REFERENCE
        }
        val before = points.lastOrNull { it.timestampMillis <= nowMillis }
        val after = points.firstOrNull { it.timestampMillis > nowMillis }
        return if (before != null && after != null && after.heightMeters >= before.heightMeters) TideStage.FLOOD else TideStage.EBB
    }

    fun nearestExtreme(nowMillis: Long): TideExtreme? = extremes.minByOrNull { abs(it.timestampMillis - nowMillis) }

    fun nextExtreme(nowMillis: Long): TideExtreme? = extremes.firstOrNull { it.timestampMillis >= nowMillis }

    private companion object {
        const val SLACK_WINDOW_MILLIS = 15 * 60 * 1_000L
    }
}

class TideForecastRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "fishing/tide-forecast.json")

    fun apiKey(): String = preferences.getString(KEY_API_KEY, "").orEmpty().trim()

    fun saveApiKey(value: String) {
        preferences.edit().putString(KEY_API_KEY, value.trim()).apply()
    }

    fun cached(): TideForecast? = runCatching {
        if (!cacheFile.isFile) return@runCatching null
        decode(JSONObject(cacheFile.readText()))
    }.getOrNull()

    fun exportCache(): ByteArray? = cacheFile.takeIf(File::isFile)?.readBytes()

    fun importCache(bytes: ByteArray): TideForecast {
        require(bytes.isNotEmpty() && bytes.size <= MAXIMUM_CACHE_BYTES) { "Invalid tide cache size" }
        val forecast = decode(JSONObject(bytes.toString(Charsets.UTF_8)))
        require(forecast.points.size >= 4) { "Tide cache contains too few points" }
        writeCache(forecast)
        return forecast
    }

    fun needsRefresh(point: GeoPoint, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val cached = cached() ?: return true
        val stale = nowMillis - cached.fetchedAtMillis >= CACHE_REFRESH_MILLIS
        val moved = distanceSquaredDegrees(cached.latitude, cached.longitude, point.latitude, point.longitude) > .0025
        return stale || moved || cached.points.none { it.timestampMillis >= nowMillis + 3 * 60 * 60 * 1_000L }
    }

    fun fetch(point: GeoPoint): TideForecast {
        val key = apiKey()
        require(key.isNotBlank()) { "WorldTides API key is not configured" }
        val endpoint = String.format(
            Locale.US,
            "https://www.worldtides.info/api/v3?heights&extremes&stations&days=2&lat=%.6f&lon=%.6f&key=%s",
            point.latitude,
            point.longitude,
            java.net.URLEncoder.encode(key, Charsets.UTF_8.name()),
        )
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        val responseCode = connection.responseCode
        val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        connection.disconnect()
        require(responseCode in 200..299) { "WorldTides HTTP $responseCode" }
        val json = JSONObject(body)
        json.optString("error").takeIf { it.isNotBlank() }?.let(::error)
        val forecast = TideForecast(
            latitude = point.latitude,
            longitude = point.longitude,
            stationName = json.optJSONArray("stations")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() },
            points = json.optJSONArray("heights").toTidePoints(),
            extremes = json.optJSONArray("extremes").toTideExtremes(),
            fetchedAtMillis = System.currentTimeMillis(),
        )
        require(forecast.points.size >= 4) { "WorldTides returned no usable height series" }
        writeCache(forecast)
        return forecast
    }

    private fun writeCache(forecast: TideForecast) {
        cacheFile.parentFile?.mkdirs()
        val temporary = File(cacheFile.parentFile, "tide-forecast.json.tmp")
        temporary.writeText(encode(forecast).toString())
        if (!temporary.renameTo(cacheFile)) {
            temporary.copyTo(cacheFile, overwrite = true)
            temporary.delete()
        }
    }

    private fun encode(forecast: TideForecast) = JSONObject().apply {
        put("latitude", forecast.latitude)
        put("longitude", forecast.longitude)
        put("stationName", forecast.stationName ?: JSONObject.NULL)
        put("fetchedAtMillis", forecast.fetchedAtMillis)
        put("points", JSONArray(forecast.points.map { point ->
            JSONObject().put("timestampMillis", point.timestampMillis).put("heightMeters", point.heightMeters)
        }))
        put("extremes", JSONArray(forecast.extremes.map { extreme ->
            JSONObject()
                .put("timestampMillis", extreme.timestampMillis)
                .put("heightMeters", extreme.heightMeters)
                .put("high", extreme.high)
        }))
    }

    private fun decode(json: JSONObject) = TideForecast(
        latitude = json.getDouble("latitude"),
        longitude = json.getDouble("longitude"),
        stationName = json.optString("stationName").takeIf { it.isNotBlank() },
        points = json.getJSONArray("points").mapObjects { item ->
            TidePoint(item.getLong("timestampMillis"), item.getDouble("heightMeters").toFloat())
        },
        extremes = json.getJSONArray("extremes").mapObjects { item ->
            TideExtreme(item.getLong("timestampMillis"), item.getDouble("heightMeters").toFloat(), item.getBoolean("high"))
        },
        fetchedAtMillis = json.getLong("fetchedAtMillis"),
    )

    private fun JSONArray?.toTidePoints(): List<TidePoint> = this?.mapObjects { item ->
        TidePoint(item.getLong("dt") * 1_000L, item.getDouble("height").toFloat())
    }.orEmpty().sortedBy(TidePoint::timestampMillis)

    private fun JSONArray?.toTideExtremes(): List<TideExtreme> = this?.mapObjects { item ->
        TideExtreme(
            timestampMillis = item.getLong("dt") * 1_000L,
            heightMeters = item.getDouble("height").toFloat(),
            high = item.optString("type").equals("High", ignoreCase = true),
        )
    }.orEmpty().sortedBy(TideExtreme::timestampMillis)

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { index -> transform(getJSONObject(index)) }

    private fun distanceSquaredDegrees(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val lat = aLat - bLat
        val lon = aLon - bLon
        return lat * lat + lon * lon
    }

    private companion object {
        const val PREFERENCES = "world_tides"
        const val KEY_API_KEY = "api_key"
        const val CACHE_REFRESH_MILLIS = 6 * 60 * 60 * 1_000L
        const val MAXIMUM_CACHE_BYTES = 512 * 1024
    }
}
