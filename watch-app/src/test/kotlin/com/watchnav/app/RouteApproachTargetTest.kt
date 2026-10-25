package com.watchnav.app

import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteApproachTargetTest {
    @Test
    fun laterButCloserTrackPointCannotReplaceDeclaredStart() {
        val declaredStart = GeoPoint(30.1606, 120.1020)
        val route = Course(
            "Outdoor regression",
            listOf(
                CoursePoint(declaredStart),
                CoursePoint(GeoPoint(30.2702, 120.0950)),
                CoursePoint(GeoPoint(30.1619, 120.1003)),
            ),
        )

        assertEquals(declaredStart, RouteApproachTarget.select(route))
    }
}
