package com.watchnav.session

import com.watchnav.core.CourseAdherence
import com.watchnav.core.GeoPoint
import com.watchnav.core.ManeuverKind
import java.util.concurrent.CopyOnWriteArraySet

enum class SessionPhase {
    IDLE,
    LOADING_COURSE,
    ACQUIRING_LOCATION,
    NAVIGATING,
    OFF_COURSE,
    COMPLETED,
    ERROR,
}

data class NavigationTelemetry(
    val phase: SessionPhase = SessionPhase.IDLE,
    val courseName: String? = null,
    val position: GeoPoint? = null,
    val progressMeters: Double = 0.0,
    val remainingMeters: Double = 0.0,
    val crossTrackMeters: Double? = null,
    val horizontalAccuracyMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val currentElevationMeters: Double? = null,
    val gradePercent: Double? = null,
    val verticalSpeedMetersPerHour: Double? = null,
    val nextManeuver: ManeuverKind? = null,
    val distanceToNextManeuverMeters: Double? = null,
    val adherence: CourseAdherence = CourseAdherence.ON_COURSE,
    val totalAscentMeters: Double = 0.0,
    val remainingAscentMeters: Double = 0.0,
    val climbNumber: Int? = null,
    val climbActive: Boolean = false,
    val distanceToClimbMeters: Double? = null,
    val climbRemainingDistanceMeters: Double? = null,
    val climbRemainingAscentMeters: Double? = null,
    val climbAverageGradePercent: Double? = null,
    val recordedPointCount: Int = 0,
    val heartRateBpm: Double? = null,
    val averageHeartRateBpm: Double? = null,
    val exerciseDistanceMeters: Double? = null,
    val calories: Double? = null,
    val lapCount: Int = 0,
    val exercisePaused: Boolean = false,
    val updatedAtMillis: Long = 0L,
    val message: String? = null,
)

object NavigationStateHub {
    private val listeners = CopyOnWriteArraySet<(NavigationTelemetry) -> Unit>()

    @Volatile
    var current: NavigationTelemetry = NavigationTelemetry()
        private set

    fun addListener(listener: (NavigationTelemetry) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun removeListener(listener: (NavigationTelemetry) -> Unit) {
        listeners -= listener
    }

    internal fun publish(telemetry: NavigationTelemetry) {
        current = telemetry
        listeners.forEach { it(telemetry) }
    }
}
