package com.watchnav.core

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

enum class CoordinateSystem(val displayName: String) {
    WGS84("WGS-84"),
    GCJ02("GCJ-02"),
    BD09("BD-09"),
}

/** Converts imported Chinese map coordinates to the WGS-84 used by GNSS and Mapsforge. */
object CoordinateConverter {
    fun courseToWgs84(course: Course, source: CoordinateSystem): Course {
        if (source == CoordinateSystem.WGS84) return course
        fun convert(point: CoursePoint) = point.copy(position = toWgs84(point.position, source))
        return course.copy(
            points = course.points.map(::convert),
            landmarks = course.landmarks.map(::convert),
        )
    }

    fun toWgs84(point: GeoPoint, source: CoordinateSystem): GeoPoint = when (source) {
        CoordinateSystem.WGS84 -> point
        CoordinateSystem.GCJ02 -> gcj02ToWgs84(point)
        CoordinateSystem.BD09 -> gcj02ToWgs84(bd09ToGcj02(point))
    }

    private fun bd09ToGcj02(point: GeoPoint): GeoPoint {
        val x = point.longitude - 0.0065
        val y = point.latitude - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = kotlin.math.atan2(y, x) - 0.000003 * kotlin.math.cos(x * X_PI)
        return GeoPoint(z * kotlin.math.sin(theta), z * kotlin.math.cos(theta), point.elevationMeters)
    }

    private fun gcj02ToWgs84(point: GeoPoint): GeoPoint {
        if (outsideChina(point.latitude, point.longitude)) return point
        // Binary inversion is more accurate than the common single-step approximation.
        var south = point.latitude - 0.02
        var north = point.latitude + 0.02
        var west = point.longitude - 0.02
        var east = point.longitude + 0.02
        var latitude = point.latitude
        var longitude = point.longitude
        repeat(30) {
            latitude = (south + north) / 2.0
            longitude = (west + east) / 2.0
            val projected = wgs84ToGcj02(GeoPoint(latitude, longitude))
            val latitudeError = projected.latitude - point.latitude
            val longitudeError = projected.longitude - point.longitude
            if (abs(latitudeError) < 1e-7 && abs(longitudeError) < 1e-7) return@repeat
            if (latitudeError > 0) north = latitude else south = latitude
            if (longitudeError > 0) east = longitude else west = longitude
        }
        return GeoPoint(latitude, longitude, point.elevationMeters)
    }

    internal fun wgs84ToGcj02(point: GeoPoint): GeoPoint {
        if (outsideChina(point.latitude, point.longitude)) return point
        var latitudeDelta = transformLatitude(point.longitude - 105.0, point.latitude - 35.0)
        var longitudeDelta = transformLongitude(point.longitude - 105.0, point.latitude - 35.0)
        val radians = point.latitude / 180.0 * Math.PI
        var magic = sin(radians)
        magic = 1 - EE * magic * magic
        val root = sqrt(magic)
        latitudeDelta = latitudeDelta * 180.0 / ((A * (1 - EE)) / (magic * root) * Math.PI)
        longitudeDelta = longitudeDelta * 180.0 / (A / root * kotlin.math.cos(radians) * Math.PI)
        return GeoPoint(
            point.latitude + latitudeDelta,
            point.longitude + longitudeDelta,
            point.elevationMeters,
        )
    }

    private fun outsideChina(latitude: Double, longitude: Double): Boolean =
        longitude !in 72.004..137.8347 || latitude !in 0.8293..55.8271

    private fun transformLatitude(x: Double, y: Double): Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        result += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return result
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        result += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return result
    }

    private const val A = 6_378_245.0
    private const val EE = 0.006693421622965943
    private const val X_PI = Math.PI * 3000.0 / 180.0
}
