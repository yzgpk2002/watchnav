package com.watchnav.core

import kotlin.test.Test
import kotlin.test.assertContentEquals

class CourseRenderSimplifierTest {
    @Test
    fun `straight route keeps only endpoints`() {
        val course = courseOf(
            GeoPoint(30.0, 120.0),
            GeoPoint(30.0, 120.001),
            GeoPoint(30.0, 120.002),
        )

        assertContentEquals(intArrayOf(0, 2), CourseRenderSimplifier.retainedPointIndices(course, 1.0))
    }

    @Test
    fun `significant bend is retained`() {
        val course = courseOf(
            GeoPoint(30.0, 120.0),
            GeoPoint(30.001, 120.001),
            GeoPoint(30.0, 120.002),
        )

        assertContentEquals(intArrayOf(0, 1, 2), CourseRenderSimplifier.retainedPointIndices(course, 5.0))
    }

    @Test
    fun `track segment boundaries are never joined`() {
        val course = Course(
            name = "segments",
            points = listOf(
                CoursePoint(GeoPoint(30.0, 120.0)),
                CoursePoint(GeoPoint(30.0, 120.001)),
                CoursePoint(GeoPoint(31.0, 121.0)),
                CoursePoint(GeoPoint(31.0, 121.001)),
            ),
            breakBeforePointIndices = setOf(2),
        )

        assertContentEquals(intArrayOf(0, 1, 2, 3), CourseRenderSimplifier.retainedPointIndices(course, 50.0))
    }

    private fun courseOf(vararg points: GeoPoint): Course = Course(
        name = "test",
        points = points.map(::CoursePoint),
    )
}
