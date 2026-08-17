package com.watchnav.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class FishingSolarWindow(
    val sunriseMillis: Long?,
    val sunsetMillis: Long?,
    val civilDawnMillis: Long?,
    val civilDuskMillis: Long?,
)

object FishingSolarMath {
    /** NOAA-style sunrise equation. Accuracy is sufficient for a wrist decision window, not navigation. */
    fun window(
        timestampMillis: Long,
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): FishingSolarWindow {
        val date = Instant.ofEpochMilli(timestampMillis).atZone(zoneId).toLocalDate()
        return FishingSolarWindow(
            sunriseMillis = event(date, latitudeDegrees, longitudeDegrees, zoneId, sunrise = true, zenithDegrees = 90.833),
            sunsetMillis = event(date, latitudeDegrees, longitudeDegrees, zoneId, sunrise = false, zenithDegrees = 90.833),
            civilDawnMillis = event(date, latitudeDegrees, longitudeDegrees, zoneId, sunrise = true, zenithDegrees = 96.0),
            civilDuskMillis = event(date, latitudeDegrees, longitudeDegrees, zoneId, sunrise = false, zenithDegrees = 96.0),
        )
    }

    private fun event(
        date: LocalDate,
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        zoneId: ZoneId,
        sunrise: Boolean,
        zenithDegrees: Double,
    ): Long? {
        val latitude = latitudeDegrees.coerceIn(-89.8, 89.8)
        val longitudeHour = longitudeDegrees.coerceIn(-180.0, 180.0) / 15.0
        val approximateTime = date.dayOfYear + ((if (sunrise) 6.0 else 18.0) - longitudeHour) / 24.0
        val meanAnomaly = 0.9856 * approximateTime - 3.289
        val trueLongitude = normalizeDegrees(
            meanAnomaly + 1.916 * sin(radians(meanAnomaly)) +
                0.020 * sin(2.0 * radians(meanAnomaly)) + 282.634,
        )
        var rightAscension = normalizeDegrees(Math.toDegrees(kotlin.math.atan(0.91764 * tan(radians(trueLongitude)))))
        rightAscension += kotlin.math.floor(trueLongitude / 90.0) * 90.0 - kotlin.math.floor(rightAscension / 90.0) * 90.0
        val rightAscensionHours = rightAscension / 15.0
        val sinDeclination = 0.39782 * sin(radians(trueLongitude))
        val cosDeclination = cos(asin(sinDeclination))
        val cosHour = (cos(radians(zenithDegrees)) - sinDeclination * sin(radians(latitude))) /
            (cosDeclination * cos(radians(latitude)))
        if (cosHour !in -1.0..1.0) return null
        val hourDegrees = if (sunrise) 360.0 - Math.toDegrees(acos(cosHour)) else Math.toDegrees(acos(cosHour))
        val localMeanTime = hourDegrees / 15.0 + rightAscensionHours - 0.06571 * approximateTime - 6.622
        val utcHours = normalizeHours(localMeanTime - longitudeHour)
        var candidate = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + (utcHours * 3_600_000.0).toLong()
        var localCandidateDate = Instant.ofEpochMilli(candidate).atZone(zoneId).toLocalDate()
        if (localCandidateDate.isAfter(date)) candidate -= 86_400_000L
        else if (localCandidateDate.isBefore(date)) candidate += 86_400_000L
        localCandidateDate = Instant.ofEpochMilli(candidate).atZone(zoneId).toLocalDate()
        return candidate.takeIf { localCandidateDate == date }
    }

    private fun radians(value: Double) = value * PI / 180.0
    private fun normalizeDegrees(value: Double) = ((value % 360.0) + 360.0) % 360.0
    private fun normalizeHours(value: Double) = ((value % 24.0) + 24.0) % 24.0
}
