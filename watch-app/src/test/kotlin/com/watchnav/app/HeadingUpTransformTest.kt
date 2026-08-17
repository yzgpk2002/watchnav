package com.watchnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingUpTransformTest {
    @Test
    fun northIsForwardWhenHeadingNorth() {
        val result = HeadingUpTransform.rotate(eastMeters = 0.0, northMeters = 20.0, bearingDegrees = 0.0)

        assertEquals(0.0, result.rightMeters, 0.001)
        assertEquals(20.0, result.forwardMeters, 0.001)
    }

    @Test
    fun eastIsForwardWhenHeadingEast() {
        val result = HeadingUpTransform.rotate(eastMeters = 20.0, northMeters = 0.0, bearingDegrees = 90.0)

        assertEquals(0.0, result.rightMeters, 0.001)
        assertEquals(20.0, result.forwardMeters, 0.001)
    }

    @Test
    fun northAppearsLeftWhenHeadingEast() {
        val result = HeadingUpTransform.rotate(eastMeters = 0.0, northMeters = 20.0, bearingDegrees = 90.0)

        assertTrue(result.rightMeters < -19.9)
        assertEquals(0.0, result.forwardMeters, 0.001)
    }
}
