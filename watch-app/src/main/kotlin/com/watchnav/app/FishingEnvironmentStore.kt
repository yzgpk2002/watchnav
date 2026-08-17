package com.watchnav.app

import android.content.Context
import com.watchnav.core.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class FishingPressureSample(
    val timestampMillis: Long,
    val pressureHpa: Float,
)

data class FishingCatchEntry(
    val id: String,
    val transcript: String,
    val species: String?,
    val lengthCm: Int?,
    val point: GeoPoint?,
    val pressureHpa: Float?,
    val createdAtMillis: Long,
    val audioFileName: String? = null,
)

enum class SolunarPeriod { MAJOR, MAJOR_SHOULDER, MINOR, QUIET }

data class SolunarAssessment(
    val activityLevel: Int,
    val period: SolunarPeriod,
    val lunarHourAngleHours: Double,
)

object FishingMath {
    private const val DAY_MILLIS = 86_400_000.0

    /** Low-power lunar hour-angle calculation; longitude is east-positive WGS-84. */
    fun solunar(timestampMillis: Long, longitudeDegrees: Double): SolunarAssessment {
        val julianDate = timestampMillis / DAY_MILLIS + 2_440_587.5
        val days = julianDate - 2_451_543.5
        val ascendingNode = radians(normalizeDegrees(125.1228 - 0.0529538083 * days))
        val inclination = radians(5.1454)
        val periapsis = radians(normalizeDegrees(318.0634 + 0.1643573223 * days))
        val eccentricity = 0.0549
        val meanAnomaly = radians(normalizeDegrees(115.3654 + 13.0649929509 * days))
        var eccentricAnomaly = meanAnomaly + eccentricity * sin(meanAnomaly) * (1.0 + eccentricity * cos(meanAnomaly))
        repeat(3) {
            eccentricAnomaly -= (eccentricAnomaly - eccentricity * sin(eccentricAnomaly) - meanAnomaly) /
                (1.0 - eccentricity * cos(eccentricAnomaly))
        }
        val orbitalX = cos(eccentricAnomaly) - eccentricity
        val orbitalY = sqrt(1.0 - eccentricity * eccentricity) * sin(eccentricAnomaly)
        val trueAnomaly = atan2(orbitalY, orbitalX)
        val radius = sqrt(orbitalX * orbitalX + orbitalY * orbitalY)
        val eclipticX = radius * (cos(ascendingNode) * cos(trueAnomaly + periapsis) -
            sin(ascendingNode) * sin(trueAnomaly + periapsis) * cos(inclination))
        val eclipticY = radius * (sin(ascendingNode) * cos(trueAnomaly + periapsis) +
            cos(ascendingNode) * sin(trueAnomaly + periapsis) * cos(inclination))
        val eclipticZ = radius * sin(trueAnomaly + periapsis) * sin(inclination)
        val obliquity = radians(23.4393 - 3.563E-7 * days)
        val equatorialX = eclipticX
        val equatorialY = eclipticY * cos(obliquity) - eclipticZ * sin(obliquity)
        val rightAscensionHours = normalizeHours(Math.toDegrees(atan2(equatorialY, equatorialX)) / 15.0)
        val gmstHours = normalizeHours(18.697374558 + 24.06570982441908 * (julianDate - 2_451_545.0))
        val hourAngle = normalizeSignedHours(gmstHours + longitudeDegrees / 15.0 - rightAscensionHours)
        val majorDistance = minOf(kotlin.math.abs(hourAngle), kotlin.math.abs(12.0 - kotlin.math.abs(hourAngle)))
        val minorDistance = kotlin.math.abs(kotlin.math.abs(hourAngle) - 6.0)
        return when {
            majorDistance <= 1.0 -> SolunarAssessment(4, SolunarPeriod.MAJOR, hourAngle)
            majorDistance <= 2.0 -> SolunarAssessment(3, SolunarPeriod.MAJOR_SHOULDER, hourAngle)
            minorDistance <= 0.75 -> SolunarAssessment(2, SolunarPeriod.MINOR, hourAngle)
            else -> SolunarAssessment(1, SolunarPeriod.QUIET, hourAngle)
        }
    }

    fun pressureDelta(
        samples: List<FishingPressureSample>,
        nowMillis: Long,
        windowMillis: Long = 3 * 60 * 60 * 1_000L,
    ): Float? {
        val latest = samples.lastOrNull() ?: return null
        val cutoff = nowMillis - windowMillis
        val baseline = samples.lastOrNull { it.timestampMillis <= cutoff }
            ?: return null
        return latest.pressureHpa - baseline.pressureHpa
    }

    fun barometerDriftSuspected(samples: List<FishingPressureSample>, nowMillis: Long): Boolean {
        val recent = samples.filter { nowMillis - it.timestampMillis <= 3 * 60 * 60 * 1_000L }
        if (recent.isEmpty()) return false
        val latest = recent.last().pressureHpa
        if (latest !in 870f..1_085f) return true
        val lastFifteenMinutes = recent.filter { nowMillis - it.timestampMillis <= 15 * 60 * 1_000L }
        return lastFifteenMinutes.size >= 3 &&
            lastFifteenMinutes.maxOf { it.pressureHpa } - lastFifteenMinutes.minOf { it.pressureHpa } > 8f
    }

    private fun radians(value: Double) = value * PI / 180.0
    private fun normalizeDegrees(value: Double) = ((value % 360.0) + 360.0) % 360.0
    private fun normalizeHours(value: Double) = ((value % 24.0) + 24.0) % 24.0
    private fun normalizeSignedHours(value: Double): Double {
        val normalized = normalizeHours(value)
        return if (normalized > 12.0) normalized - 24.0 else normalized
    }
}

class FishingEnvironmentStore(context: Context) {
    private val preferences = context.getSharedPreferences("fishing_environment", Context.MODE_PRIVATE)
    private val catchFile = File(context.filesDir, "fishing/catches.json")

    @Synchronized
    fun recordPressure(pressureHpa: Float, timestampMillis: Long = System.currentTimeMillis()): List<FishingPressureSample> {
        val samples = pressureSamples().toMutableList()
        val newest = samples.lastOrNull()
        if (newest == null || timestampMillis - newest.timestampMillis >= PRESSURE_SAVE_INTERVAL_MILLIS) {
            samples += FishingPressureSample(timestampMillis, pressureHpa)
            val cutoff = timestampMillis - PRESSURE_RETENTION_MILLIS
            val retained = samples.filter { it.timestampMillis >= cutoff }
            preferences.edit().putString(KEY_PRESSURE, retained.joinToString(";") {
                "${it.timestampMillis},${it.pressureHpa}"
            }).apply()
            return retained
        }
        return samples
    }

    fun pressureSamples(): List<FishingPressureSample> = preferences.getString(KEY_PRESSURE, null)
        .orEmpty()
        .split(';')
        .mapNotNull { encoded ->
            val parts = encoded.split(',')
            val time = parts.getOrNull(0)?.toLongOrNull()
            val pressure = parts.getOrNull(1)?.toFloatOrNull()
            if (time != null && pressure != null) FishingPressureSample(time, pressure) else null
        }
        .sortedBy(FishingPressureSample::timestampMillis)

    fun shouldRunBarometerMaintenance(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - preferences.getLong(KEY_LAST_BAROMETER_CHECK, 0L) >= BAROMETER_CHECK_INTERVAL_MILLIS

    fun markBarometerMaintenanceChecked(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit().putLong(KEY_LAST_BAROMETER_CHECK, nowMillis).apply()
    }

    fun waterTemperatureCelsius(): Float? = preferences.getFloat(KEY_WATER_TEMPERATURE, Float.NaN)
        .takeIf { !it.isNaN() }

    fun isSaltWater(): Boolean = preferences.getBoolean(KEY_SALT_WATER, true)

    fun saveWaterProfile(temperatureCelsius: Float?, saltWater: Boolean) {
        preferences.edit().apply {
            if (temperatureCelsius == null) remove(KEY_WATER_TEMPERATURE)
            else putFloat(KEY_WATER_TEMPERATURE, temperatureCelsius.coerceIn(0f, 40f))
            putBoolean(KEY_SALT_WATER, saltWater)
        }.apply()
    }

    /** Daily cast counter. The detector is deliberately conservative and the UI also allows manual correction. */
    @Synchronized
    fun recordCast(nowMillis: Long = System.currentTimeMillis()): FishingCastStats {
        val current = castStats(nowMillis)
        val updated = current.copy(totalToday = current.totalToday + 1)
        saveCastStats(updated, nowMillis)
        return updated
    }

    fun castStats(nowMillis: Long = System.currentTimeMillis()): FishingCastStats {
        val today = castDay(nowMillis)
        val storedDay = preferences.getString(KEY_CAST_DAY, null)
        if (storedDay != today) return FishingCastStats(0, 0)
        val total = preferences.getInt(KEY_CAST_TOTAL, 0).coerceAtLeast(0)
        val baseline = preferences.getInt(KEY_CAST_BASELINE, 0).coerceIn(0, total)
        return FishingCastStats(total, total - baseline)
    }

    /** A catch, voice pin or explicit fishing pin starts a new "casts since mark" interval. */
    @Synchronized
    fun markCastInterval(nowMillis: Long = System.currentTimeMillis()): FishingCastStats {
        val current = castStats(nowMillis)
        val updated = FishingCastStats(current.totalToday, 0)
        preferences.edit()
            .putString(KEY_CAST_DAY, castDay(nowMillis))
            .putInt(KEY_CAST_TOTAL, updated.totalToday)
            .putInt(KEY_CAST_BASELINE, updated.totalToday)
            .apply()
        return updated
    }

    private fun saveCastStats(value: FishingCastStats, nowMillis: Long) {
        preferences.edit()
            .putString(KEY_CAST_DAY, castDay(nowMillis))
            .putInt(KEY_CAST_TOTAL, value.totalToday)
            .putInt(KEY_CAST_BASELINE, value.totalToday - value.sinceLastMark)
            .apply()
    }

    private fun castDay(timestampMillis: Long): String =
        Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    @Synchronized
    fun saveCatch(
        transcript: String,
        point: GeoPoint?,
        pressureHpa: Float?,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): FishingCatchEntry {
        val length = LENGTH_PATTERN.find(transcript)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val species = SPECIES.firstOrNull { transcript.contains(it, ignoreCase = true) }
        val entry = FishingCatchEntry(
            id = UUID.randomUUID().toString(),
            transcript = transcript.take(160),
            species = species,
            lengthCm = length,
            point = point,
            pressureHpa = pressureHpa,
            createdAtMillis = createdAtMillis,
        )
        upsertCatch(entry)
        return entry
    }

    @Synchronized
    fun saveAudioCatch(
        id: String,
        audioFileName: String,
        point: GeoPoint?,
        pressureHpa: Float?,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): FishingCatchEntry = FishingCatchEntry(
        id = id,
        transcript = "",
        species = null,
        lengthCm = null,
        point = point,
        pressureHpa = pressureHpa,
        createdAtMillis = createdAtMillis,
        audioFileName = audioFileName.take(160),
    ).also(::upsertCatch)

    @Synchronized
    fun listCatches(): List<FishingCatchEntry> {
        if (!catchFile.isFile) return emptyList()
        val array = JSONArray(catchFile.readText())
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            FishingCatchEntry(
                id = item.getString("id"),
                transcript = item.optString("transcript"),
                species = item.optString("species").takeIf { it.isNotBlank() && it != "null" },
                lengthCm = item.optInt("lengthCm", -1).takeIf { it >= 0 },
                point = if (item.has("latitude") && !item.isNull("latitude") &&
                    item.has("longitude") && !item.isNull("longitude")
                ) {
                    GeoPoint(
                        item.getDouble("latitude"),
                        item.getDouble("longitude"),
                        item.optDouble("elevation").takeIf { !it.isNaN() },
                    )
                } else null,
                pressureHpa = item.optDouble("pressureHpa").takeIf { !it.isNaN() }?.toFloat(),
                createdAtMillis = item.getLong("createdAtMillis"),
                audioFileName = item.optString("audioFileName").takeIf { it.isNotBlank() && it != "null" },
            )
        }.sortedByDescending(FishingCatchEntry::createdAtMillis)
    }

    @Synchronized
    fun upsertCatch(entry: FishingCatchEntry) {
        require(entry.id.isNotBlank() && entry.id.length <= 160)
        val retained = listCatches().filterNot { it.id == entry.id } + entry
        writeCatches(retained)
    }

    @Synchronized
    fun deleteCatch(id: String) {
        writeCatches(listCatches().filterNot { it.id == id })
    }

    private fun writeCatches(entries: List<FishingCatchEntry>) {
        val array = JSONArray()
        entries.sortedBy(FishingCatchEntry::createdAtMillis).forEach { entry ->
            array.put(JSONObject().apply {
            put("id", entry.id)
            put("transcript", entry.transcript)
            put("species", entry.species ?: JSONObject.NULL)
            put("lengthCm", entry.lengthCm ?: JSONObject.NULL)
            put("latitude", entry.point?.latitude ?: JSONObject.NULL)
            put("longitude", entry.point?.longitude ?: JSONObject.NULL)
            put("elevation", entry.point?.elevationMeters ?: JSONObject.NULL)
            put("pressureHpa", entry.pressureHpa ?: JSONObject.NULL)
            put("createdAtMillis", entry.createdAtMillis)
            put("audioFileName", entry.audioFileName ?: JSONObject.NULL)
        })
        }
        catchFile.parentFile?.mkdirs()
        val temporary = File(catchFile.parentFile, "catches.json.tmp")
        temporary.writeText(array.toString())
        if (!temporary.renameTo(catchFile)) {
            temporary.copyTo(catchFile, overwrite = true)
            check(temporary.delete()) { "Unable to save fishing catch log" }
        }
    }

    private companion object {
        const val KEY_PRESSURE = "pressure_samples"
        const val PRESSURE_SAVE_INTERVAL_MILLIS = 5 * 60 * 1_000L
        const val PRESSURE_RETENTION_MILLIS = 4 * 60 * 60 * 1_000L
        const val BAROMETER_CHECK_INTERVAL_MILLIS = 72 * 60 * 60 * 1_000L
        const val KEY_LAST_BAROMETER_CHECK = "last_barometer_check"
        const val KEY_WATER_TEMPERATURE = "water_temperature_celsius"
        const val KEY_SALT_WATER = "salt_water"
        const val KEY_CAST_DAY = "cast_day"
        const val KEY_CAST_TOTAL = "cast_total"
        const val KEY_CAST_BASELINE = "cast_baseline"
        val LENGTH_PATTERN = Regex("(\\d{1,3})\\s*(?:cm|厘米|公分)", RegexOption.IGNORE_CASE)
        val SPECIES = listOf("黑鲈", "鲈鱼", "鳜鱼", "翘嘴", "鲫鱼", "鲤鱼", "草鱼", "bass", "trout", "carp")
    }
}

data class FishingCastStats(
    val totalToday: Int,
    val sinceLastMark: Int,
)
