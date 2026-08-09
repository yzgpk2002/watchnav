package com.watchnav.core

import kotlin.test.Test
import kotlin.test.assertTrue

class SpatialIndexTest {
    @Test
    fun largeCourseQueriesOnlyNearbySegments() {
        val points = (0..100_000).map { index ->
            CoursePoint(GeoPoint(31.0 + index * 0.000001, 121.0))
        }
        val prepared = PreparedCourse.prepare(Course("100k", points))
        val candidates = prepared.candidateSegmentIndices(GeoPoint(31.05, 121.0), 150.0)

        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.size < 10_000, "Spatial index returned ${candidates.size} candidates")
    }
}
