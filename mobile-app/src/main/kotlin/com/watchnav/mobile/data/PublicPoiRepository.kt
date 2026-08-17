package com.watchnav.mobile.data

import com.watchnav.core.GeoPoint
import com.watchnav.session.SavedWaypoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PublicPoiRepository {
    fun download(bounds: Bounds): List<SavedWaypoint> {
        require(bounds.south < bounds.north && bounds.west < bounds.east)
        require((bounds.north - bounds.south) * (bounds.east - bounds.west) <= MAX_AREA_DEGREES) {
            "POI download area is too large"
        }
        val box = "${bounds.south},${bounds.west},${bounds.north},${bounds.east}"
        val query = """[out:json][timeout:30];(
          nwr["leisure"="fishing"]($box);
          nwr["waterway"="access_point"]($box);
          nwr["leisure"="slipway"]($box);
          nwr["amenity"~"toilets|drinking_water|parking|shelter"]($box);
          nwr["man_made"~"pier|quay"]($box);
        );out center tags;""".trimIndent()
        val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 45_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.setRequestProperty("User-Agent", "WatchNav/0.15 Android companion; OSM POI sync")
        connection.outputStream.buffered().use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val bytes = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "POI response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
        connection.disconnect()
        require(code in 200..299) { "Overpass HTTP $code" }
        val elements = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("elements")
        return (0 until elements.length()).mapNotNull { index ->
            val item = elements.getJSONObject(index)
            val latitude = if (item.has("lat")) item.optDouble("lat") else item.optJSONObject("center")?.optDouble("lat")
            val longitude = if (item.has("lon")) item.optDouble("lon") else item.optJSONObject("center")?.optDouble("lon")
            if (latitude == null || longitude == null || latitude.isNaN() || longitude.isNaN()) return@mapNotNull null
            val tags = item.optJSONObject("tags") ?: JSONObject()
            val category = when {
                tags.optString("leisure") == "fishing" -> "公共下钓点"
                tags.optString("waterway") == "access_point" -> "亲水入口"
                tags.optString("leisure") == "slipway" -> "下水坡道"
                tags.optString("amenity").isNotBlank() -> tags.optString("amenity")
                else -> tags.optString("man_made", "公共设施")
            }
            SavedWaypoint(
                id = "osm-${item.getString("type")}-${item.getLong("id")}",
                name = tags.optString("name").ifBlank { category }.take(80),
                point = GeoPoint(latitude, longitude),
                createdAtMillis = System.currentTimeMillis(),
            )
        }.distinctBy(SavedWaypoint::id)
    }

    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double)

    private companion object {
        const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val MAX_AREA_DEGREES = 4.0
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}
