package com.watchnav.session

import android.content.Context
import com.watchnav.core.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedWaypoint(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val createdAtMillis: Long,
)

class WaypointStore(context: Context) {
    private val file = File(context.filesDir, "waypoints/saved.json")

    @Synchronized
    fun save(name: String, point: GeoPoint): SavedWaypoint {
        val waypoint = SavedWaypoint(
            id = System.currentTimeMillis().toString(),
            name = name.take(80),
            point = point,
            createdAtMillis = System.currentTimeMillis(),
        )
        write(list().filterNot { it.id == waypoint.id } + waypoint)
        return waypoint
    }

    @Synchronized
    fun list(): List<SavedWaypoint> {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
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
        }.sortedByDescending(SavedWaypoint::createdAtMillis)
    }

    @Synchronized
    fun delete(id: String) {
        write(list().filterNot { it.id == id })
    }

    private fun write(waypoints: List<SavedWaypoint>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "saved.json.tmp")
        temporary.writeText(JSONArray(waypoints.map { waypoint ->
            JSONObject().apply {
                put("id", waypoint.id)
                put("name", waypoint.name)
                put("latitude", waypoint.point.latitude)
                put("longitude", waypoint.point.longitude)
                put("elevation", waypoint.point.elevationMeters ?: JSONObject.NULL)
                put("createdAtMillis", waypoint.createdAtMillis)
            }
        }).toString())
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            check(temporary.delete()) { "Unable to save waypoint" }
        }
    }
}
