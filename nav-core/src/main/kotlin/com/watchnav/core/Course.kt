package com.watchnav.core

import java.time.Instant

data class CoursePoint(
    val position: GeoPoint,
    val time: Instant? = null,
    val name: String? = null,
    val type: String? = null,
    val symbol: String? = null,
    val description: String? = null,
    val comment: String? = null,
    val role: CoursePointRole = CoursePointRole.NORMAL,
    val category: CoursePointCategory = CoursePointCategory.GENERIC,
    val maneuver: ManeuverKind? = null,
    val links: List<CourseLink> = emptyList(),
    val address: CourseAddress? = null,
    val phoneNumber: String? = null,
    val displayColor: String? = null,
    val metrics: CoursePointMetrics = CoursePointMetrics(),
)

enum class CoursePointRole { NORMAL, VIA, SHAPING, TURN, POI }

enum class CoursePointCategory {
    GENERIC,
    FOOD,
    WATER,
    STORE,
    SHELTER,
    MEDICAL,
    SUMMIT,
    VIEWPOINT,
    DANGER,
    FISHING,
}

data class CourseLink(
    val href: String,
    val text: String? = null,
    val mimeType: String? = null,
)

data class CourseAddress(
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
) {
    fun isEmpty(): Boolean = listOf(street, city, state, postalCode, country).all { it.isNullOrBlank() }
}

data class CoursePointMetrics(
    val airTemperatureCelsius: Double? = null,
    val waterTemperatureCelsius: Double? = null,
    val depthMeters: Double? = null,
    val barometricAltitudeMeters: Double? = null,
    val heartRateBpm: Double? = null,
    val cadenceRpm: Double? = null,
    val powerWatts: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val courseDegrees: Double? = null,
    val surface: String? = null,
    val maximumSpeed: Double? = null,
)

data class CourseStyle(
    val color: String? = null,
    val width: String? = null,
    val translucent: Boolean = false,
)

data class Course(
    val name: String,
    val points: List<CoursePoint>,
    val breakBeforePointIndices: Set<Int> = emptySet(),
    val landmarks: List<CoursePoint> = emptyList(),
    val style: CourseStyle = CourseStyle(),
) {
    init {
        require(points.size >= 2) { "A course needs at least two points" }
        require(breakBeforePointIndices.all { it in 1 until points.size }) {
            "Course breaks must refer to a non-first point"
        }
    }
}

enum class ManeuverKind {
    START,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    U_TURN,
    SHARP_RIGHT,
    RIGHT,
    SLIGHT_RIGHT,
    FINISH,
}

data class NavigationCue(
    val pointIndex: Int,
    val distanceFromStartMeters: Double,
    val kind: ManeuverKind,
    val label: String? = null,
)

data class CourseMilestone(
    val point: CoursePoint,
    val distanceFromStartMeters: Double,
)

data class LocationFix(
    val position: GeoPoint,
    val accuracyMeters: Double,
    val timestampMillis: Long,
    val bearingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
) {
    init {
        require(accuracyMeters >= 0 && accuracyMeters.isFinite())
    }
}

enum class ActivityMode(val defaultOffCourseMeters: Double) {
    HIKE(30.0),
    RUN(30.0),
    BIKE(50.0),
}

