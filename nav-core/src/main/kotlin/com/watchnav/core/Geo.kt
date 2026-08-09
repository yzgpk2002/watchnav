package com.watchnav.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "Longitude out of range: $longitude" }
        require(elevationMeters == null || elevationMeters.isFinite()) { "Elevation must be finite" }
    }
}

data class SegmentProjection(
    val point: GeoPoint,
    val fraction: Double,
    val distanceMeters: Double,
)

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.latitude.toRadians()
        val lat2 = b.latitude.toRadians()
        val deltaLat = (b.latitude - a.latitude).toRadians()
        val deltaLon = (b.longitude - a.longitude).toRadians()
        val h = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
    }

    fun initialBearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.latitude.toRadians()
        val lat2 = b.latitude.toRadians()
        val deltaLon = (b.longitude - a.longitude).toRadians()
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return normalizeDegrees(atan2(y, x) * 180.0 / PI)
    }

    fun signedTurnDegrees(incomingBearing: Double, outgoingBearing: Double): Double {
        var delta = normalizeDegrees(outgoingBearing) - normalizeDegrees(incomingBearing)
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        return delta
    }

    fun angularDifferenceDegrees(a: Double, b: Double): Double =
        abs(signedTurnDegrees(a, b))

    fun projectOnSegment(query: GeoPoint, start: GeoPoint, end: GeoPoint): SegmentProjection {
        val referenceLat = query.latitude.toRadians()
        fun x(point: GeoPoint) =
            (point.longitude - query.longitude).toRadians() * cos(referenceLat) * EARTH_RADIUS_METERS
        fun y(point: GeoPoint) =
            (point.latitude - query.latitude).toRadians() * EARTH_RADIUS_METERS

        val startX = x(start)
        val startY = y(start)
        val endX = x(end)
        val endY = y(end)
        val dx = endX - startX
        val dy = endY - startY
        val lengthSquared = dx * dx + dy * dy
        val fraction = if (lengthSquared == 0.0) {
            0.0
        } else {
            (-(startX * dx + startY * dy) / lengthSquared).coerceIn(0.0, 1.0)
        }
        val projectedX = startX + dx * fraction
        val projectedY = startY + dy * fraction
        return SegmentProjection(
            point = GeoPoint(
                latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                longitude = start.longitude + (end.longitude - start.longitude) * fraction,
                elevationMeters = interpolateElevation(start, end, fraction),
            ),
            fraction = fraction,
            distanceMeters = hypot(projectedX, projectedY),
        )
    }

    fun normalizeDegrees(value: Double): Double = ((value % 360) + 360) % 360

    private fun interpolateElevation(start: GeoPoint, end: GeoPoint, fraction: Double): Double? {
        val a = start.elevationMeters ?: return null
        val b = end.elevationMeters ?: return null
        return a + (b - a) * fraction
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}

