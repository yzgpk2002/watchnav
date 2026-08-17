package com.watchnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationInstructionTest {
    @Test fun groupsTurnsIntoGlanceableDirections() {
        assertEquals(NavigationDirection.LEFT, NavigationInstruction.direction(ManeuverKind.SHARP_LEFT))
        assertEquals(NavigationDirection.LEFT, NavigationInstruction.direction(ManeuverKind.SLIGHT_LEFT))
        assertEquals(NavigationDirection.STRAIGHT, NavigationInstruction.direction(ManeuverKind.START))
        assertEquals(NavigationDirection.STRAIGHT, NavigationInstruction.direction(null))
        assertEquals(NavigationDirection.RIGHT, NavigationInstruction.direction(ManeuverKind.RIGHT))
        assertEquals(NavigationDirection.FINISH, NavigationInstruction.direction(ManeuverKind.FINISH))
    }
}
