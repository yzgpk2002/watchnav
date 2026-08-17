package com.watchnav.session

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidanceHeadingPolicyTest {
    @Test
    fun lowSpeedCompassFacingBackwardsUsesKnownPathDirection() {
        assertEquals(90.0, GuidanceHeadingPolicy.resolve(270.0, 0.2, 90.0)!!, 0.01)
    }

    @Test
    fun lowSpeedCompassNearPathDirectionUsesStableRouteTangent() {
        assertEquals(90.0, GuidanceHeadingPolicy.resolve(70.0, 0.2, 90.0)!!, 0.01)
    }

    @Test
    fun movingGpsCourseWinsEvenWhenRouteTurnsAway() {
        assertEquals(270.0, GuidanceHeadingPolicy.resolve(270.0, 1.0, 90.0)!!, 0.01)
    }
}
