package com.watchnav.session

import com.watchnav.core.CoursePoint
import com.watchnav.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class TransientRejoinPlanTest {
    @Test
    fun repeatedReroutesKeepSingleDisplayPrefix() {
        val rejoin = listOf(point(0.0), point(0.1))
        val original = listOf(point(0.2), point(0.3), point(0.4))

        val value = TransientRejoinPlan.build("Rejoin · Rejoin · Lake route", rejoin, original, 0)

        assertEquals("Rejoin · Lake route", value.name)
        assertEquals(4, value.points.size)
    }

    private fun point(value: Double) = CoursePoint(GeoPoint(value, value))
}
