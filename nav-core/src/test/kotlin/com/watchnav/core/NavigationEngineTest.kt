package com.watchnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationEngineTest {
    private val course = Course(
        name = "Northbound",
        points = listOf(
            CoursePoint(GeoPoint(31.0000, 121.0000)),
            CoursePoint(GeoPoint(31.0010, 121.0000)),
            CoursePoint(GeoPoint(31.0020, 121.0000)),
        ),
    )

    @Test
    fun progressIsStableAndRemainingDistanceShrinks() {
        val engine = NavigationEngine(course, ActivityMode.HIKE)
        val first = engine.update(fix(31.0004, 121.00002, 1))
        val second = engine.update(fix(31.0014, 121.00002, 2))
        val noisyBackward = engine.update(fix(31.0013, 121.00002, 3))

        assertTrue(second.match.stableProgressMeters > first.match.stableProgressMeters)
        assertEquals(second.match.stableProgressMeters, noisyBackward.match.stableProgressMeters)
        assertTrue(second.match.remainingMeters < first.match.remainingMeters)
    }

    @Test
    fun offCourseRequiresConfirmationAndRecoveryUsesHysteresis() {
        val engine = NavigationEngine(course, ActivityMode.HIKE)
        assertEquals(CourseAdherence.SUSPECTED_OFF_COURSE, engine.update(fix(31.0005, 121.0010, 1)).adherence.state)
        assertEquals(CourseAdherence.SUSPECTED_OFF_COURSE, engine.update(fix(31.0006, 121.0010, 2)).adherence.state)
        assertEquals(CourseAdherence.OFF_COURSE, engine.update(fix(31.0007, 121.0010, 3)).adherence.state)
        assertEquals(CourseAdherence.REJOINING, engine.update(fix(31.0008, 121.00001, 4)).adherence.state)
        assertEquals(CourseAdherence.REJOINING, engine.update(fix(31.0009, 121.00001, 5)).adherence.state)
        assertEquals(CourseAdherence.ON_COURSE, engine.update(fix(31.0010, 121.00001, 6)).adherence.state)
    }

    @Test
    fun restoredProgressDoesNotJumpBackAfterProcessRestart() {
        val engine = NavigationEngine(course, ActivityMode.HIKE)
        engine.restoreProgress(150.0)

        val update = engine.update(fix(31.0002, 121.00001, 1))

        assertTrue(update.match.stableProgressMeters >= 150.0)
        assertTrue(update.match.remainingMeters <= engine.preparedCourse.totalDistanceMeters - 150.0)
    }

    @Test
    fun lateArrivalInsideGnssAccuracyCircleCompletesTheCourse() {
        val engine = NavigationEngine(course, ActivityMode.HIKE)
        engine.restoreProgress(engine.preparedCourse.totalDistanceMeters * 0.80)

        val update = engine.update(
            LocationFix(
                position = GeoPoint(31.00175, 121.0),
                accuracyMeters = 20.0,
                timestampMillis = 1_000,
            ),
        )

        assertEquals(0.0, update.match.remainingMeters)
        assertEquals(engine.preparedCourse.totalDistanceMeters, update.match.stableProgressMeters)
    }

    @Test
    fun closedLoopDoesNotCompleteWhileStillAtTheStart() {
        val start = GeoPoint(31.0, 121.0)
        val loop = Course(
            name = "Loop",
            points = listOf(
                CoursePoint(start),
                CoursePoint(GeoPoint(31.01, 121.0)),
                CoursePoint(GeoPoint(31.01, 121.01)),
                CoursePoint(start),
            ),
        )
        val engine = NavigationEngine(loop, ActivityMode.HIKE)

        val update = engine.update(fix(start.latitude, start.longitude, 1))

        assertTrue(update.match.remainingMeters > 0.0)
    }

    @Test
    fun viaPointIsUpAheadAndShapingPointStaysSilent() {
        val semanticCourse = Course(
            name = "Milestones",
            points = listOf(
                CoursePoint(GeoPoint(31.0000, 121.0000)),
                CoursePoint(GeoPoint(31.0010, 121.0000), name = "塑形", type = "shaping", role = CoursePointRole.SHAPING),
                CoursePoint(GeoPoint(31.0020, 121.0000), name = "补给", type = "via", role = CoursePointRole.VIA),
                CoursePoint(GeoPoint(31.0030, 121.0000)),
            ),
        )
        val engine = NavigationEngine(semanticCourse, ActivityMode.HIKE)

        val update = engine.update(fix(31.0002, 121.0, 1))

        assertEquals("补给", update.nextMilestone?.point?.name)
        assertTrue(update.distanceToNextMilestoneMeters!! > 0.0)
    }

    @Test
    fun accuracyNormalisedInnovationContributesToOffCourseDecision() {
        val engine = NavigationEngine(course, ActivityMode.HIKE)
        val first = engine.update(fix(31.0005, 121.00024, 1))
        val second = engine.update(fix(31.0006, 121.00024, 2))
        val third = engine.update(fix(31.0007, 121.00024, 3))

        assertTrue(first.match.innovationSquared > 9.0)
        assertEquals(CourseAdherence.SUSPECTED_OFF_COURSE, second.adherence.state)
        assertEquals(CourseAdherence.OFF_COURSE, third.adherence.state)
    }

    private fun fix(lat: Double, lon: Double, second: Long) = LocationFix(
        position = GeoPoint(lat, lon),
        accuracyMeters = 5.0,
        timestampMillis = second * 1_000,
        bearingDegrees = 0.0,
    )
}
