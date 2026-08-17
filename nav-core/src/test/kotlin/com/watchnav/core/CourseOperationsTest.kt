package com.watchnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CourseOperationsTest {
    @Test
    fun continuationJoinsNearbyMiddleInsteadOfDistantFileStart() {
        val route = Course(
            "route",
            listOf(
                CoursePoint(GeoPoint(30.20, 120.10)),
                CoursePoint(GeoPoint(30.10, 120.10)),
                CoursePoint(GeoPoint(30.10, 120.20)),
            ),
        )
        val origin = GeoPoint(30.1002, 120.15)

        val result = route.continuationFromNearestPoint(origin)

        assertTrue(result.distanceMeters < 30.0)
        assertEquals(2, result.course.points.size)
        assertTrue(result.course.points.first().position.longitude in 120.149..120.151)
        assertEquals(route.points.last().position, result.course.points.last().position)
    }

    @Test
    fun continuationPreservesFutureCourseBreaks() {
        val route = Course(
            "route",
            listOf(
                CoursePoint(GeoPoint(30.0, 120.0)),
                CoursePoint(GeoPoint(30.0, 120.01)),
                CoursePoint(GeoPoint(30.1, 120.1)),
                CoursePoint(GeoPoint(30.1, 120.11)),
            ),
            breakBeforePointIndices = setOf(2),
        )

        val result = route.continuationFromNearestPoint(GeoPoint(30.0, 120.005))

        assertEquals(setOf(2), result.course.breakBeforePointIndices)
    }
}
