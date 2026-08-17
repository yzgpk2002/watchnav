package com.watchnav.session

import com.watchnav.core.GeoMath
import com.watchnav.core.GeoPoint
import com.watchnav.core.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNavigationFilterTest {
    @Test
    fun stationaryGpsJitterStaysInsideFiveMeterDeadZone() {
        val filter = AdaptiveNavigationFilter()
        val first = fix(northMeters = 0.0, eastMeters = 0.0, timeMillis = 1_000, speed = 0.1, bearing = 250.0)
        val initial = filter.update(first, compassHeadingDegrees = 15.0, compassReliable = true)

        listOf(3.0 to -2.0, -4.0 to 1.0, 2.5 to 3.0, -1.0 to -3.5).forEachIndexed { index, offset ->
            val filtered = filter.update(
                fix(offset.first, offset.second, 2_000L + index * 1_000L, 0.1, 70.0 + index * 80.0),
                compassHeadingDegrees = 15.0,
                compassReliable = true,
            )
            assertTrue(GeoMath.distanceMeters(initial.position, filtered.position) < 0.1)
            assertEquals(15.0, requireNotNull(filtered.bearingDegrees), 0.01)
        }
    }

    @Test
    fun movingCourseRejectsACompassThatPointsBackTowardTheWearer() {
        val walking = AdaptiveNavigationFilter().update(
            fix(0.0, 0.0, 1_000, 0.4, 270.0),
            compassHeadingDegrees = 92.0,
            compassReliable = true,
        )
        val highSpeed = AdaptiveNavigationFilter().update(
            fix(0.0, 0.0, 1_000, 2.5, 35.0),
            compassHeadingDegrees = 210.0,
            compassReliable = true,
        )

        assertEquals(270.0, requireNotNull(walking.bearingDegrees), 0.01)
        assertEquals(35.0, requireNotNull(highSpeed.bearingDegrees), 0.01)
    }

    @Test
    fun transitionSpeedBlendsAcrossNorthWithoutWrappingToSouth() {
        val result = AdaptiveNavigationFilter().update(
            fix(0.0, 0.0, 1_000, 1.0, 1.0),
            compassHeadingDegrees = 359.0,
            compassReliable = true,
        )

        val heading = requireNotNull(result.bearingDegrees)
        assertTrue(heading < 2.0 || heading > 358.0)
    }

    @Test
    fun headingChangeMustPersistForOneAndHalfSeconds() {
        val filter = AdaptiveNavigationFilter()
        filter.update(fix(0.0, 0.0, 1_000, 0.2, null), 0.0, true)
        val early = filter.update(fix(0.0, 0.0, 2_000, 0.2, null), 40.0, true)
        val stillEarly = filter.update(fix(0.0, 0.0, 3_000, 0.2, null), 42.0, true)
        val accepted = filter.update(fix(0.0, 0.0, 3_600, 0.2, null), 41.0, true)

        assertEquals(0.0, requireNotNull(early.bearingDegrees), 0.01)
        assertEquals(0.0, requireNotNull(stillEarly.bearingDegrees), 0.01)
        assertTrue(requireNotNull(accepted.bearingDegrees) in 15.0..30.0)
    }

    @Test
    fun implausibleRelocationResetsInsteadOfDraggingOldEstimate() {
        val filter = AdaptiveNavigationFilter()
        filter.update(fix(0.0, 0.0, 1_000, 0.0, null), 0.0, true)
        val relocated = filter.update(fix(10_000.0, 0.0, 2_000, 0.0, null), 0.0, true)

        assertTrue(GeoMath.distanceMeters(relocated.position, point(10_000.0, 0.0)) < 1.0)
    }

    @Test
    fun movingFilterNeverTrailsRawFixByMoreThanTwentyMeters() {
        val filter = AdaptiveNavigationFilter()
        filter.update(fix(0.0, 0.0, 1_000, 1.0, 0.0, accuracy = 40.0), 180.0, true)
        val moved = filter.update(fix(120.0, 0.0, 11_000, 1.0, 0.0, accuracy = 40.0), 180.0, true)

        assertTrue(GeoMath.distanceMeters(moved.position, point(120.0, 0.0)) <= 20.5)
        assertEquals(40.0, moved.accuracyMeters, 0.01)
    }

    @Test
    fun lowSpeedDisplacementCourseCorrectsOpposingCompass() {
        val filter = AdaptiveNavigationFilter()
        filter.update(fix(0.0, 0.0, 1_000, 0.4, null), 180.0, true)
        filter.update(fix(10.0, 0.0, 2_000, 0.4, null), 180.0, true)
        filter.update(fix(20.0, 0.0, 3_000, 0.4, null), 180.0, true)
        val corrected = filter.update(fix(30.0, 0.0, 3_600, 0.4, null), 180.0, true)

        val heading = requireNotNull(corrected.bearingDegrees)
        assertTrue(heading < 10.0 || heading > 350.0)
    }

    @Test
    fun poorAccuracyStillLearnsWalkingDirectionWithinFifteenMeters() {
        val filter = AdaptiveNavigationFilter()
        filter.update(fix(0.0, 0.0, 1_000, 0.45, null, accuracy = 40.0), 180.0, true)
        filter.update(fix(11.0, 0.0, 3_000, 0.45, null, accuracy = 40.0), 180.0, true)
        val corrected = filter.update(fix(16.0, 0.0, 5_000, 0.45, null, accuracy = 40.0), 180.0, true)

        val heading = requireNotNull(corrected.bearingDegrees)
        assertTrue(heading < 10.0 || heading > 350.0)
    }

    private fun fix(
        northMeters: Double,
        eastMeters: Double,
        timeMillis: Long,
        speed: Double,
        bearing: Double?,
        accuracy: Double = 6.0,
    ) = LocationFix(
        position = point(northMeters, eastMeters),
        accuracyMeters = accuracy,
        timestampMillis = timeMillis,
        bearingDegrees = bearing,
        speedMetersPerSecond = speed,
    )

    private fun point(northMeters: Double, eastMeters: Double) = GeoPoint(
        latitude = ORIGIN.latitude + northMeters / 111_320.0,
        longitude = ORIGIN.longitude + eastMeters /
            (111_320.0 * kotlin.math.cos(Math.toRadians(ORIGIN.latitude))),
    )

    private companion object {
        val ORIGIN = GeoPoint(30.25, 120.17)
    }
}
