package com.watchnav.session

import android.content.Context
import com.watchnav.core.ActivityMode
import com.watchnav.core.Course
import com.watchnav.core.ElevationProfile
import com.watchnav.core.PreparedCourse
import com.watchnav.core.fit.FitWriter
import com.watchnav.core.gpx.GpxWriter
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ActivitySummary(
    val id: String,
    val mode: ActivityMode,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val pointCount: Int,
    val averageHeartRateBpm: Double?,
    val calories: Double?,
)

class ActivityArchive(context: Context) {
    private val directory = File(context.filesDir, "activities/archive")

    @Synchronized
    fun archive(
        course: Course,
        mode: ActivityMode,
        telemetry: NavigationTelemetry,
    ): ActivitySummary {
        directory.mkdirs()
        val startedAt = course.points.firstNotNullOfOrNull { it.time } ?: Instant.now()
        val endedAt = course.points.asReversed().firstNotNullOfOrNull { it.time } ?: Instant.now()
        val baseId = ID_FORMAT.format(startedAt.atZone(ZoneId.systemDefault()))
        val gpxOutput = ByteArrayOutputStream()
        GpxWriter.write(course, gpxOutput)
        val gpxBytes = gpxOutput.toByteArray()
        findExisting(baseId, gpxBytes)?.let { return it }
        val id = uniqueId(baseId)
        val prepared = PreparedCourse.prepare(course)
        val ascent = ElevationProfile.prepare(prepared).totalAscentMeters
        File(directory, "$id.gpx").writeBytes(gpxBytes)
        File(directory, "$id.fit").outputStream().use { FitWriter.write(course, it) }
        val summary = ActivitySummary(
            id = id,
            mode = mode,
            startedAtMillis = startedAt.toEpochMilli(),
            endedAtMillis = endedAt.toEpochMilli(),
            distanceMeters = prepared.totalDistanceMeters,
            ascentMeters = ascent,
            pointCount = course.points.size,
            averageHeartRateBpm = telemetry.averageHeartRateBpm ?: telemetry.heartRateBpm,
            calories = telemetry.calories,
        )
        File(directory, "$id.json").writeText(toJson(summary).toString())
        return summary
    }

    fun list(): List<ActivitySummary> {
        cleanupExactDuplicateArchives()
        return directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> runCatching { fromJson(JSONObject(file.readText())) }.getOrNull() }
            ?.sortedByDescending(ActivitySummary::startedAtMillis)
            ?.distinctBy { summary ->
                listOf(
                    summary.mode.name,
                    summary.startedAtMillis,
                    summary.endedAtMillis,
                    summary.pointCount,
                    summary.distanceMeters.toLong(),
                ).joinToString(":")
            }
            ?: emptyList()
    }

    fun latestFile(extension: String): File? = list().firstOrNull()?.id
        ?.let { File(directory, "$it.${extension.lowercase()}") }
        ?.takeIf(File::isFile)

    private fun uniqueId(base: String): String {
        if (!File(directory, "$base.json").exists()) return base
        var suffix = 2
        while (File(directory, "$base-$suffix.json").exists()) suffix++
        return "$base-$suffix"
    }

    private fun findExisting(baseId: String, gpxBytes: ByteArray): ActivitySummary? {
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(gpxBytes)
        return directory.listFiles { file ->
            file.extension.equals("gpx", true) &&
                (file.nameWithoutExtension == baseId || file.nameWithoutExtension.startsWith("$baseId-"))
        }?.firstNotNullOfOrNull { gpx ->
            val matches = runCatching {
                MessageDigest.isEqual(
                    expectedDigest,
                    MessageDigest.getInstance("SHA-256").digest(gpx.readBytes()),
                )
            }.getOrDefault(false)
            if (!matches) return@firstNotNullOfOrNull null
            File(directory, "${gpx.nameWithoutExtension}.json")
                .takeIf(File::isFile)
                ?.let { json -> runCatching { fromJson(JSONObject(json.readText())) }.getOrNull() }
        }
    }

    /** Old service/UI races could archive one completed recording twice with a -2 suffix. Only
     * byte-identical GPX copies are removed; independently recorded activities are never merged. */
    private fun cleanupExactDuplicateArchives() {
        if (!directory.isDirectory) return
        val retainedByDigest = HashMap<String, File>()
        directory.listFiles { file -> file.extension.equals("gpx", true) }
            ?.sortedBy { it.name }
            ?.forEach { gpx ->
                val digest = runCatching {
                    MessageDigest.getInstance("SHA-256").digest(gpx.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                }.getOrNull() ?: return@forEach
                if (retainedByDigest.putIfAbsent(digest, gpx) == null) return@forEach
                val duplicateId = gpx.nameWithoutExtension
                gpx.delete()
                File(directory, "$duplicateId.fit").delete()
                File(directory, "$duplicateId.json").delete()
            }
    }

    private fun toJson(summary: ActivitySummary) = JSONObject().apply {
        put("id", summary.id)
        put("mode", summary.mode.name)
        put("startedAtMillis", summary.startedAtMillis)
        put("endedAtMillis", summary.endedAtMillis)
        put("distanceMeters", summary.distanceMeters)
        put("ascentMeters", summary.ascentMeters)
        put("pointCount", summary.pointCount)
        put("averageHeartRateBpm", summary.averageHeartRateBpm ?: JSONObject.NULL)
        put("calories", summary.calories ?: JSONObject.NULL)
    }

    private fun fromJson(json: JSONObject) = ActivitySummary(
        id = json.getString("id"),
        mode = ActivityMode.valueOf(json.getString("mode")),
        startedAtMillis = json.getLong("startedAtMillis"),
        endedAtMillis = json.getLong("endedAtMillis"),
        distanceMeters = json.getDouble("distanceMeters"),
        ascentMeters = json.getDouble("ascentMeters"),
        pointCount = json.getInt("pointCount"),
        averageHeartRateBpm = json.optDouble("averageHeartRateBpm").takeIf { !it.isNaN() },
        calories = json.optDouble("calories").takeIf { !it.isNaN() },
    )

    private companion object {
        val ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
