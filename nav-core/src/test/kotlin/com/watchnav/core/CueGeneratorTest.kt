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

    @Test
    fun explicitCuesRemainBoundToDistanceAxisWhenCoordinatesRepeat() {
        val repeated = GeoPoint(31.0010, 121.0000)
        val course = Course(
            "Out and back",
            listOf(
                CoursePoint(GeoPoint(31.0000, 121.0000)),
                CoursePoint(repeated, name = "第一次", symbol = "left", maneuver = ManeuverKind.LEFT),
                CoursePoint(GeoPoint(31.0020, 121.0000)),
                CoursePoint(repeated, name = "第二次", symbol = "right", maneuver = ManeuverKind.RIGHT),
                CoursePoint(GeoPoint(31.0000, 121.0000)),
            ),
        )

        val explicit = CueGenerator.generate(PreparedCourse.prepare(course)).filter { it.label?.startsWith("第") == true }

        assertEquals(2, explicit.size)
        assertTrue(explicit[1].distanceFromStartMeters > explicit[0].distanceFromStartMeters)
        assertEquals(ManeuverKind.LEFT, explicit[0].kind)
        assertEquals(ManeuverKind.RIGHT, explicit[1].kind)
    }
}

