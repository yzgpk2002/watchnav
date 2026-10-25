package com.watchnav.app

import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.GeoMath
import com.watchnav.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousRouteAssemblerTest {
    @Test
    fun duplicateJoinPointProducesOneContinuousSegment() {
        val join = GeoPoint(30.001, 120.0)
        val approach = Course("Approach", listOf(point(30.0), CoursePoint(join)))
        val selected = Course("Lake", listOf(CoursePoint(join), point(30.002), point(30.003)))

        val combined = ContinuousRouteAssembler.combine(approach, selected)

        assertEquals(4, combined.points.size)
        assertFalse(combined.breakBeforePointIndices.contains(approach.points.lastIndex))
        assertTrue(combined.points.zipWithNext().all { (a, b) ->
            GeoMath.distanceMeters(a.position, b.position) < 150.0
        })
    }

    @Test
    fun selectedInternalBreakIsPreservedButSeamCannotBreak() {
        val approach = Course("Approach", listOf(point(30.0), point(30.001)))
        val selected = Course(
            "Split GPX",
            listOf(point(30.001), point(30.002), point(30.003)),
            breakBeforePointIndices = setOf(1),
        )

        val combined = ContinuousRouteAssembler.combine(approach, selected)

        assertFalse(combined.breakBeforePointIndices.contains(1))
        assertTrue(combined.breakBeforePointIndices.contains(2))
    }

    private fun point(latitude: Double) = CoursePoint(GeoPoint(latitude, 120.0))
}
