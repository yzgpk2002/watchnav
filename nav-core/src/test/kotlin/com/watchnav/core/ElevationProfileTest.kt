package com.watchnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ElevationProfileTest {
    @Test
    fun calculatesRemainingAscentAndDetectsClimb() {
        val course = Course(
            name = "Hill",
            points = listOf(
                point(31.0000, 100.0),
                point(31.0010, 108.0),
                point(31.0020, 120.0),
                point(31.0030, 135.0),
                point(31.0040, 138.0),
                point(31.0050, 120.0),
            ),
        )
        val prepared = PreparedCourse.prepare(course)
        val profile = ElevationProfile.prepare(prepared)

        assertEquals(38.0, profile.totalAscentMeters, 0.01)
        val climb = assertNotNull(profile.climbs.singleOrNull())
        assertTrue(climb.ascentMeters >= 38.0)
        assertTrue(climb.averageGradePercent > 2.0)
        assertTrue(profile.remainingAscentMeters(prepared.totalDistanceMeters) < 0.01)
        assertNotNull(profile.nextClimb(0.0))
        assertTrue((profile.gradePercentAt(prepared.totalDistanceMeters / 2) ?: 0.0) > 2.0)
        assertNotNull(profile.elevationAt(prepared.totalDistanceMeters / 2))
    }

    @Test
    fun reversePreservesSegmentBreaks() {
        val course = Course(
            name = "Split",
            points = (0..4).map { point(31.0 + it * 0.001, 10.0 + it) },
            breakBeforePointIndices = setOf(2),
        )

        val reversed = course.reversed()

        assertEquals(setOf(3), reversed.breakBeforePointIndices)
        assertEquals(course.points.last(), reversed.points.first())
    }

    private fun point(latitude: Double, elevation: Double) =
        CoursePoint(GeoPoint(latitude, 121.0, elevation))
}
