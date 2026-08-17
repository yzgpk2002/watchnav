package com.watchnav.session

import android.content.Context
import com.watchnav.core.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FavoriteTarget(
    val slot: Int,
    val name: String,
    val point: GeoPoint,
    val updatedAtMillis: Long,
) {
    init {
        require(slot in 0..5)
        require(name.isNotBlank())
    }
}

/** Slot 0 is Home; slots 1..5 are the five common navigation targets. */
class FavoriteTargetStore(context: Context) {
    private val file = File(context.filesDir, "waypoints/favorite-targets.json")

    @Synchronized
    fun list(): List<FavoriteTarget> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val values = JSONArray(file.readText())
        (0 until values.length()).map { index ->
            val json = values.getJSONObject(index)
            FavoriteTarget(
                slot = json.getInt("slot"),
                name = json.getString("name"),
                point = GeoPoint(
                    json.getDouble("latitude"),
                    json.getDouble("longitude"),
                    json.optDouble("elevation").takeIf { !it.isNaN() },
                ),
                updatedAtMillis = json.getLong("updatedAtMillis"),
            )
        }.sortedBy(FavoriteTarget::slot)
    }.getOrDefault(emptyList())

    @Synchronized
    fun set(slot: Int, name: String, point: GeoPoint): FavoriteTarget {
        val target = FavoriteTarget(slot, name.take(60), point, System.currentTimeMillis())
        write(list().filterNot { it.slot == slot } + target)
        return target
    }

    @Synchronized
    fun replace(values: List<FavoriteTarget>) {
        require(values.map(FavoriteTarget::slot).distinct().size == values.size)
        write(values)
    }

    @Synchronized
    fun clear(slot: Int) = write(list().filterNot { it.slot == slot })

    fun encode(): JSONArray = JSONArray(list().map { target -> JSONObject().apply {
        put("slot", target.slot)
        put("name", target.name)
        put("latitude", target.point.latitude)
        put("longitude", target.point.longitude)
        put("elevation", target.point.elevationMeters ?: JSONObject.NULL)
        put("updatedAtMillis", target.updatedAtMillis)
    } })

    fun decodeAndReplace(array: JSONArray) {
        val values = (0 until array.length()).mapNotNull { index ->
            runCatching {
                val json = array.getJSONObject(index)
                FavoriteTarget(
                    json.getInt("slot"),
                    json.getString("name"),
                    GeoPoint(
                        json.getDouble("latitude"),
                        json.getDouble("longitude"),
                        json.optDouble("elevation").takeIf { !it.isNaN() },
                    ),
                    json.optLong("updatedAtMillis", System.currentTimeMillis()),
                )
            }.getOrNull()
        }
        replace(values)
    }

    private fun write(values: List<FavoriteTarget>) {
        file.parentFile?.mkdirs()
        val array = JSONArray(values.sortedBy(FavoriteTarget::slot).map { target -> JSONObject().apply {
            put("slot", target.slot); put("name", target.name)
            put("latitude", target.point.latitude); put("longitude", target.point.longitude)
            put("elevation", target.point.elevationMeters ?: JSONObject.NULL)
            put("updatedAtMillis", target.updatedAtMillis)
        } })
        val temporary = File(file.parentFile, file.name + ".tmp").apply { writeText(array.toString()) }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            check(temporary.delete())
        }
    }
}
