package com.watchnav.session

import com.watchnav.core.GeoMath
import com.watchnav.core.GeoPoint
import com.watchnav.core.LocationFix
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Stabilises low-speed navigation before a fix reaches matching, recording or rendering.
 *
 * Position is estimated with a constant-velocity Kalman model. Heading is a circular blend of
 * GNSS course-over-ground and the tilt-compensated compass, with a low-speed dead zone and a
 * time-based hysteresis gate. The class is Android-free so the behaviour can be unit tested.
 */
internal class AdaptiveNavigationFilter {
    private var origin: GeoPoint? = null
    private val east = AxisKalman()
    private val north = AxisKalman()
    private var lastTimestampMillis: Long? = null
    private var confirmedPosition: GeoPoint? = null
    private var stableHeadingDegrees: Double? = null
    private var pendingHeadingSinceMillis: Long? = null
    private var pendingHeadingSign = 0
    private var gpsCourseAnchor: GeoPoint? = null
    private var gpsCourseAnchorTimeMillis: Long? = null
    private var displacementCourseDegrees: Double? = null
    private var displacementCourseTimeMillis: Long? = null

    fun reset() {
        origin = null
        east.reset()
        north.reset()
        lastTimestampMillis = null
        confirmedPosition = null
        stableHeadingDegrees = null
        pendingHeadingSinceMillis = null
        pendingHeadingSign = 0
        gpsCourseAnchor = null
        gpsCourseAnchorTimeMillis = null
        displacementCourseDegrees = null
        displacementCourseTimeMillis = null
    }

    fun update(
        raw: LocationFix,
        compassHeadingDegrees: Double?,
        compassReliable: Boolean,
    ): LocationFix {
        val currentOrigin = origin
        if (currentOrigin == null) {
            initialise(raw)
            val initialHeading = selectHeading(
                gpsHeading = raw.bearingDegrees,
                compassHeading = compassHeadingDegrees,
                speedMetersPerSecond = raw.speedMetersPerSecond ?: 0.0,
                compassReliable = compassReliable,
            )
            stableHeadingDegrees = initialHeading
            return raw.copy(bearingDegrees = initialHeading)
        }

        updateDisplacementCourse(raw)

        val elapsedSeconds = ((raw.timestampMillis - (lastTimestampMillis ?: raw.timestampMillis)) / 1_000.0)
        val rawDistanceFromConfirmed = confirmedPosition?.let { GeoMath.distanceMeters(it, raw.position) } ?: 0.0
        if (elapsedSeconds <= 0.0 || elapsedSeconds > RESET_AFTER_SECONDS ||
            rawDistanceFromConfirmed > maxOf(RELOCATION_DISTANCE_METERS, raw.accuracyMeters * 10.0)
        ) {
            initialise(raw)
            val heading = selectHeading(
                raw.bearingDegrees,
                compassHeadingDegrees,
                raw.speedMetersPerSecond ?: 0.0,
                compassReliable,
            ) ?: stableHeadingDegrees
            stableHeadingDegrees = heading
            return raw.copy(bearingDegrees = heading)
        }

        val (measuredEast, measuredNorth) = toLocalMeters(raw.position, currentOrigin)
        val dt = elapsedSeconds.coerceIn(MINIMUM_DT_SECONDS, MAXIMUM_DT_SECONDS)
        val processNoise = when {
            (raw.speedMetersPerSecond ?: 0.0) < STATIONARY_SPEED_METERS_PER_SECOND -> 0.35
            (raw.speedMetersPerSecond ?: 0.0) < GPS_DOMINANT_SPEED_METERS_PER_SECOND -> 1.2
            else -> 3.0
        }
        east.predict(dt, processNoise)
        north.predict(dt, processNoise)
        val measurementVariance = raw.accuracyMeters
            .coerceIn(MINIMUM_ACCURACY_METERS, MAXIMUM_ACCURACY_METERS)
            .let { it * it }
        east.correct(measuredEast, measurementVariance)
        north.correct(measuredNorth, measurementVariance)

        val kalmanPosition = fromLocalMeters(east.position, north.position, currentOrigin)
            .copy(elevationMeters = raw.position.elevationMeters)
        val speed = raw.speedMetersPerSecond ?: hypot(east.velocity, north.velocity)
        val candidatePosition = limitMovingLag(kalmanPosition, raw, speed)
        val acceptedPosition = confirmedPosition?.let { previous ->
            val displacement = GeoMath.distanceMeters(previous, candidatePosition)
            if (speed < STATIONARY_SPEED_METERS_PER_SECOND && displacement < POSITION_DEAD_ZONE_METERS) {
                previous.copy(elevationMeters = raw.position.elevationMeters)
            } else {
                candidatePosition.also { confirmedPosition = it }
            }
        } ?: candidatePosition.also { confirmedPosition = it }

        val candidateHeading = selectHeading(
            gpsHeading = effectiveGpsCourse(raw),
            compassHeading = compassHeadingDegrees,
            speedMetersPerSecond = speed,
            compassReliable = compassReliable,
        )
        val heading = applyHeadingHysteresis(candidateHeading, raw.timestampMillis)
        lastTimestampMillis = raw.timestampMillis
        return raw.copy(
            position = acceptedPosition,
            // Filtering reduces visual noise, but it does not improve the receiver's measured
            // uncertainty. Keeping the GNSS accuracy prevents over-confident route matching.
            accuracyMeters = raw.accuracyMeters,
            bearingDegrees = heading,
            speedMetersPerSecond = speed,
        )
    }

    private fun initialise(raw: LocationFix) {
        origin = raw.position
        val variance = raw.accuracyMeters
            .coerceIn(MINIMUM_ACCURACY_METERS, MAXIMUM_ACCURACY_METERS)
            .let { it * it }
        val speed = raw.speedMetersPerSecond ?: 0.0
        val bearingRadians = Math.toRadians(raw.bearingDegrees ?: 0.0)
        east.initialise(0.0, speed * sin(bearingRadians), variance)
        north.initialise(0.0, speed * cos(bearingRadians), variance)
        confirmedPosition = raw.position
        lastTimestampMillis = raw.timestampMillis
        gpsCourseAnchor = raw.position
        gpsCourseAnchorTimeMillis = raw.timestampMillis
        displacementCourseDegrees = null
        displacementCourseTimeMillis = null
        pendingHeadingSinceMillis = null
        pendingHeadingSign = 0
    }

    private fun selectHeading(
        gpsHeading: Double?,
        compassHeading: Double?,
        speedMetersPerSecond: Double,
        compassReliable: Boolean,
    ): Double? {
        val compass = compassHeading?.takeIf { compassReliable }
        if (speedMetersPerSecond <= STATIONARY_SPEED_METERS_PER_SECOND) {
            return compass ?: stableHeadingDegrees ?: gpsHeading
        }
        if (speedMetersPerSecond >= GPS_DOMINANT_SPEED_METERS_PER_SECOND) {
            return gpsHeading ?: compass ?: stableHeadingDegrees
        }
        if (gpsHeading == null) return compass ?: stableHeadingDegrees
        if (compass == null) return stableHeadingDegrees ?: gpsHeading
        // A watch face commonly points back towards the wearer while walking.  Its magnetic
        // azimuth can therefore be almost 180 degrees away from course-over-ground even when
        // both sensors are individually healthy.  Once real movement exists, never blend that
        // disagreement into a backwards navigation display.
        if (GeoMath.angularDifferenceDegrees(gpsHeading, compass) >= COMPASS_REJECTION_DEGREES) {
            return gpsHeading
        }
        val gpsWeight = (speedMetersPerSecond - COMPASS_DOMINANT_SPEED_METERS_PER_SECOND) /
            (GPS_DOMINANT_SPEED_METERS_PER_SECOND - COMPASS_DOMINANT_SPEED_METERS_PER_SECOND)
        return circularBlend(compass, gpsHeading, gpsWeight)
    }

    private fun updateDisplacementCourse(raw: LocationFix) {
        val anchor = gpsCourseAnchor ?: return
        val anchorTime = gpsCourseAnchorTimeMillis ?: return
        val elapsedMillis = raw.timestampMillis - anchorTime
        if (elapsedMillis <= 0L || elapsedMillis > MAXIMUM_COURSE_BASELINE_MILLIS) {
            gpsCourseAnchor = raw.position
            gpsCourseAnchorTimeMillis = raw.timestampMillis
            return
        }
        val displacement = GeoMath.distanceMeters(anchor, raw.position)
        val requiredBaseline = (raw.accuracyMeters * COURSE_BASELINE_ACCURACY_FACTOR)
            .coerceIn(MINIMUM_COURSE_BASELINE_METERS, MAXIMUM_COURSE_BASELINE_METERS)
        if (displacement < requiredBaseline) return
        displacementCourseDegrees = GeoMath.initialBearingDegrees(anchor, raw.position)
        displacementCourseTimeMillis = raw.timestampMillis
        gpsCourseAnchor = raw.position
        gpsCourseAnchorTimeMillis = raw.timestampMillis
    }

    private fun effectiveGpsCourse(raw: LocationFix): Double? {
        val displacement = displacementCourseDegrees?.takeIf {
            raw.timestampMillis - (displacementCourseTimeMillis ?: 0L) <= DISPLACEMENT_COURSE_MAX_AGE_MILLIS
        }
        return displacement ?: raw.bearingDegrees
    }

    private fun limitMovingLag(candidate: GeoPoint, raw: LocationFix, speedMetersPerSecond: Double): GeoPoint {
        if (speedMetersPerSecond < STATIONARY_SPEED_METERS_PER_SECOND) return candidate
        val lag = GeoMath.distanceMeters(candidate, raw.position)
        val maximumLag = (raw.accuracyMeters * MOVING_LAG_ACCURACY_FACTOR)
            .coerceIn(MINIMUM_MOVING_LAG_METERS, MAXIMUM_MOVING_LAG_METERS)
        if (lag <= maximumLag || lag == 0.0) return candidate
        val fractionFromRaw = maximumLag / lag
        return GeoPoint(
            latitude = raw.position.latitude + (candidate.latitude - raw.position.latitude) * fractionFromRaw,
            longitude = raw.position.longitude + (candidate.longitude - raw.position.longitude) * fractionFromRaw,
            elevationMeters = raw.position.elevationMeters,
        )
    }

    private fun applyHeadingHysteresis(candidate: Double?, timestampMillis: Long): Double? {
        candidate ?: return stableHeadingDegrees
        val stable = stableHeadingDegrees ?: return candidate.also { stableHeadingDegrees = it }
        val difference = shortestAngleDegrees(stable, candidate)
        if (kotlin.math.abs(difference) <= HEADING_DEAD_ZONE_DEGREES) {
            pendingHeadingSinceMillis = null
            pendingHeadingSign = 0
            return stable
        }
        val sign = if (difference > 0.0) 1 else -1
        if (pendingHeadingSinceMillis == null || sign != pendingHeadingSign) {
            pendingHeadingSinceMillis = timestampMillis
            pendingHeadingSign = sign
            return stable
        }
        if (timestampMillis - requireNotNull(pendingHeadingSinceMillis) < HEADING_HYSTERESIS_MILLIS) return stable

        val smoothing = when {
            kotlin.math.abs(difference) >= REVERSAL_TURN_DEGREES -> 1.0
            kotlin.math.abs(difference) >= LARGE_TURN_DEGREES -> 0.65
            else -> 0.4
        }
        val updated = normaliseDegrees(stable + difference * smoothing)
        stableHeadingDegrees = updated
        pendingHeadingSinceMillis = null
        pendingHeadingSign = 0
        return updated
    }

    private fun toLocalMeters(point: GeoPoint, origin: GeoPoint): Pair<Double, Double> {
        val northMeters = (point.latitude - origin.latitude) * METERS_PER_DEGREE_LATITUDE
        val eastMeters = (point.longitude - origin.longitude) * METERS_PER_DEGREE_LATITUDE *
            cos(Math.toRadians(origin.latitude)).coerceAtLeast(MINIMUM_LONGITUDE_SCALE)
        return eastMeters to northMeters
    }

    private fun fromLocalMeters(eastMeters: Double, northMeters: Double, origin: GeoPoint): GeoPoint {
        val latitude = origin.latitude + northMeters / METERS_PER_DEGREE_LATITUDE
        val longitude = origin.longitude + eastMeters /
            (METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(origin.latitude)).coerceAtLeast(MINIMUM_LONGITUDE_SCALE))
        return GeoPoint(latitude, longitude)
    }

    private fun circularBlend(fromDegrees: Double, toDegrees: Double, toWeight: Double): Double {
        val weight = toWeight.coerceIn(0.0, 1.0)
        val from = Math.toRadians(fromDegrees)
        val to = Math.toRadians(toDegrees)
        val x = cos(from) * (1.0 - weight) + cos(to) * weight
        val y = sin(from) * (1.0 - weight) + sin(to) * weight
        return normaliseDegrees(Math.toDegrees(atan2(y, x)))
    }

    private fun shortestAngleDegrees(from: Double, to: Double) = ((to - from + 540.0) % 360.0) - 180.0
    private fun normaliseDegrees(value: Double) = (value % 360.0 + 360.0) % 360.0

    private class AxisKalman {
        var position = 0.0
            private set
        var velocity = 0.0
            private set
        var positionVariance = 1.0
            private set
        private var p01 = 0.0
        private var p10 = 0.0
        private var p11 = 25.0

        fun reset() {
            position = 0.0
            velocity = 0.0
            positionVariance = 1.0
            p01 = 0.0
            p10 = 0.0
            p11 = 25.0
        }

        fun initialise(position: Double, velocity: Double, positionVariance: Double) {
            this.position = position
            this.velocity = velocity
            this.positionVariance = positionVariance
            p01 = 0.0
            p10 = 0.0
            p11 = 25.0
        }

        fun predict(dt: Double, accelerationVariance: Double) {
            position += velocity * dt
            val oldP00 = positionVariance
            val oldP01 = p01
            val oldP10 = p10
            val oldP11 = p11
            val dt2 = dt * dt
            val dt3 = dt2 * dt
            val dt4 = dt2 * dt2
            positionVariance = oldP00 + dt * (oldP01 + oldP10) + dt2 * oldP11 +
                0.25 * dt4 * accelerationVariance
            p01 = oldP01 + dt * oldP11 + 0.5 * dt3 * accelerationVariance
            p10 = oldP10 + dt * oldP11 + 0.5 * dt3 * accelerationVariance
            p11 = oldP11 + dt2 * accelerationVariance
        }

        fun correct(measurement: Double, measurementVariance: Double) {
            val innovationVariance = positionVariance + measurementVariance
            if (innovationVariance <= 0.0) return
            val gainPosition = positionVariance / innovationVariance
            val gainVelocity = p10 / innovationVariance
            val innovation = measurement - position
            position += gainPosition * innovation
            velocity += gainVelocity * innovation
            val oldP00 = positionVariance
            val oldP01 = p01
            positionVariance = (1.0 - gainPosition) * oldP00
            p01 = (1.0 - gainPosition) * oldP01
            p10 -= gainVelocity * oldP00
            p11 -= gainVelocity * oldP01
            val symmetric = (p01 + p10) * 0.5
            p01 = symmetric
            p10 = symmetric
        }
    }

    private companion object {
        const val COMPASS_DOMINANT_SPEED_METERS_PER_SECOND = 0.3
        const val GPS_DOMINANT_SPEED_METERS_PER_SECOND = 0.8
        const val STATIONARY_SPEED_METERS_PER_SECOND = 0.3
        const val COMPASS_REJECTION_DEGREES = 90.0
        const val POSITION_DEAD_ZONE_METERS = 5.0
        const val HEADING_DEAD_ZONE_DEGREES = 10.0
        const val HEADING_HYSTERESIS_MILLIS = 1_500L
        const val LARGE_TURN_DEGREES = 45.0
        const val REVERSAL_TURN_DEGREES = 90.0
        const val MINIMUM_ACCURACY_METERS = 2.0
        const val MAXIMUM_ACCURACY_METERS = 100.0
        const val MINIMUM_DT_SECONDS = 0.1
        const val MAXIMUM_DT_SECONDS = 10.0
        const val RESET_AFTER_SECONDS = 120.0
        const val RELOCATION_DISTANCE_METERS = 1_000.0
        const val MINIMUM_COURSE_BASELINE_METERS = 8.0
        const val MAXIMUM_COURSE_BASELINE_METERS = 15.0
        const val COURSE_BASELINE_ACCURACY_FACTOR = 0.25
        const val MAXIMUM_COURSE_BASELINE_MILLIS = 30_000L
        const val DISPLACEMENT_COURSE_MAX_AGE_MILLIS = 15_000L
        const val MINIMUM_MOVING_LAG_METERS = 8.0
        const val MAXIMUM_MOVING_LAG_METERS = 20.0
        const val MOVING_LAG_ACCURACY_FACTOR = 0.5
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0
        const val MINIMUM_LONGITUDE_SCALE = 0.15
    }
}
