package com.watchnav.core

import java.time.Instant

data class CoursePoint(
    val position: GeoPoint,
    val time: Instant? = null,
    val name: String? = null,
    val type: String? = null,
)

data class Course(
    val name: String,
    val points: List<CoursePoint>,
    val breakBeforePointIndices: Set<Int> = emptySet(),
    val landmarks: List<CoursePoint> = emptyList(),
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

