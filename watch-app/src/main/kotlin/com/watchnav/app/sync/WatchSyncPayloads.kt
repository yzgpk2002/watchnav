package com.watchnav.app.sync

import com.watchnav.app.FishingCatchEntry
import com.watchnav.core.GeoPoint
import com.watchnav.session.SavedWaypoint
import org.json.JSONObject

internal object WatchSyncPayloads {
    fun waypoint(value: SavedWaypoint): ByteArray = JSONObject().apply {
        put("id", value.id)
        put("name", value.name)
        put("latitude", value.point.latitude)
        put("longitude", value.point.longitude)
        put("elevation", value.point.elevationMeters ?: JSONObject.NULL)
        put("createdAtMillis", value.createdAtMillis)
    }.toString().toByteArray()

    fun decodeWaypoint(bytes: ByteArray): SavedWaypoint = JSONObject(bytes.toString(Charsets.UTF_8)).let { json ->
        SavedWaypoint(
            id = json.getString("id"),
            name = json.getString("name"),
            point = GeoPoint(
                json.getDouble("latitude"),
                json.getDouble("longitude"),
                json.optDouble("elevation").takeIf { !it.isNaN() },
            ),
            createdAtMillis = json.getLong("createdAtMillis"),
        )
    }

    fun fishingCatch(value: FishingCatchEntry): ByteArray = JSONObject().apply {
        put("id", value.id)
        put("transcript", value.transcript)
        put("species", value.species ?: JSONObject.NULL)
        put("lengthCm", value.lengthCm ?: JSONObject.NULL)
        put("latitude", value.point?.latitude ?: JSONObject.NULL)
        put("longitude", value.point?.longitude ?: JSONObject.NULL)
        put("elevation", value.point?.elevationMeters ?: JSONObject.NULL)
        put("pressureHpa", value.pressureHpa ?: JSONObject.NULL)
        put("createdAtMillis", value.createdAtMillis)
        put("audioFileName", value.audioFileName ?: JSONObject.NULL)
    }.toString().toByteArray()

    fun decodeFishingCatch(bytes: ByteArray): FishingCatchEntry = JSONObject(bytes.toString(Charsets.UTF_8)).let { json ->
        FishingCatchEntry(
            id = json.getString("id"),
            transcript = json.optString("transcript").take(160),
            species = json.optString("species").takeIf { it.isNotBlank() && it != "null" },
            lengthCm = json.optInt("lengthCm", -1).takeIf { it >= 0 },
            point = if (!json.isNull("latitude") && !json.isNull("longitude")) {
                GeoPoint(
                    json.getDouble("latitude"),
                    json.getDouble("longitude"),
                    json.optDouble("elevation").takeIf { !it.isNaN() },
                )
            } else null,
            pressureHpa = json.optDouble("pressureHpa").takeIf { !it.isNaN() }?.toFloat(),
            createdAtMillis = json.getLong("createdAtMillis"),
            audioFileName = json.optString("audioFileName").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}
