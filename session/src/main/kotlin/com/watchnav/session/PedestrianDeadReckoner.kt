package com.watchnav.session

import com.watchnav.core.GeoPoint
import com.watchnav.core.LocationFix
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Step-and-heading fallback used only after the expected GNSS update window is missed. */
internal class PedestrianDeadReckoner(
    private val stepLengthMeters: Double = DEFAULT_STEP_LENGTH_METERS,
) {
    private var position: GeoPoint? = null
    private var accuracyMeters = 50.0
    private var stepCount: Long? = null
    private var timestampMillis: Long? = null

    fun anchor(fix: LocationFix, cumulativeSteps: Long) {
        position = fix.position
        accuracyMeters = fix.accuracyMeters
        stepCount = cumulativeSteps
        timestampMillis = fix.timestampMillis
    }

    fun advance(cumulativeSteps: Long, trueHeadingDegrees: Double, timestamp: Long): LocationFix? {
        val previousPosition = position ?: return null
        val previousSteps = stepCount ?: return null
        val deltaSteps = (cumulativeSteps - previousSteps).coerceIn(0L, MAXIMUM_BATCH_STEPS)
        if (deltaSteps == 0L) return null
        val distanceMeters = deltaSteps * stepLengthMeters
        val nextPosition = destination(previousPosition, trueHeadingDegrees, distanceMeters)
        val elapsedSeconds = ((timestamp - (timestampMillis ?: timestamp)) / 1_000.0).coerceAtLeast(1.0)
        accuracyMeters = kotlin.math.sqrt(
            accuracyMeters * accuracyMeters +
                (deltaSteps * STEP_DISTANCE_SIGMA_METERS).let { it * it } +
                HEADING_DRIFT_METERS_PER_STEP * HEADING_DRIFT_METERS_PER_STEP * deltaSteps,
        ).coerceAtMost(MAXIMUM_PDR_ACCURACY_METERS)
        position = nextPosition
        stepCount = cumulativeSteps
        timestampMillis = timestamp
        return LocationFix(
            position = nextPosition,
            accuracyMeters = accuracyMeters,
            timestampMillis = timestamp,
            bearingDegrees = trueHeadingDegrees,
            speedMetersPerSecond = distanceMeters / elapsedSeconds,
        )
    }

    private fun destination(start: GeoPoint, bearingDegrees: Double, distanceMeters: Double): GeoPoint {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val latitude = Math.toRadians(start.latitude)
        val longitude = Math.toRadians(start.longitude)
        val nextLatitude = asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing),
        )
        val nextLongitude = longitude + kotlin.math.atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(nextLatitude),
        )
        return GeoPoint(
            latitude = Math.toDegrees(nextLatitude),
            longitude = ((Math.toDegrees(nextLongitude) + 540.0) % 360.0) - 180.0,
            elevationMeters = start.elevationMeters,
        )
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
        const val DEFAULT_STEP_LENGTH_METERS = 0.72
        const val STEP_DISTANCE_SIGMA_METERS = 0.12
        const val HEADING_DRIFT_METERS_PER_STEP = 0.18
        const val MAXIMUM_BATCH_STEPS = 50L
        const val MAXIMUM_PDR_ACCURACY_METERS = 100.0
    }
}
