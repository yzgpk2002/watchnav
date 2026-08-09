package com.watchnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CueGeneratorTest {
    @Test
    fun createsRightTurnBetweenStartAndFinish() {
        val course = Course(
            "Corner",
            listOf(
                CoursePoint(GeoPoint(31.0000, 121.0000)),
                CoursePoint(GeoPoint(31.0010, 121.0000)),
                CoursePoint(GeoPoint(31.0010, 121.0010)),
            ),
        )
        val cues = CueGenerator.generate(PreparedCourse.prepare(course))

        assertEquals(ManeuverKind.START, cues.first().kind)
        assertTrue(cues.any { it.kind == ManeuverKind.RIGHT })
        assertEquals(ManeuverKind.FINISH, cues.last().kind)
    }
}

