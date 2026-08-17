package com.watchnav.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianRouteDirectionEstimatorTest {
    @Test
    fun gpsJitterWithoutStepsCannotFlipDirection() {
        val estimator = PedestrianRouteDirectionEstimator()
        estimator.update(100.0, 90.0, 270.0, true, true, 0, 8.0)
        val result = estimator.update(82.0, 90.0, 270.0, true, true, 0, 8.0)

        assertEquals(AlongRouteDirection.FORWARD, result.direction)
        assertFalse(result.changed)
    }

    @Test
    fun stableCompassAndRealStepsProvideImmediateProvisionalReverseDirection() {
        val estimator = PedestrianRouteDirectionEstimator()
        estimator.update(100.0, 90.0, 270.0, true, true, 0, 8.0)
        val result = estimator.update(101.0, 90.0, 270.0, true, true, 4, 8.0)

        assertEquals(AlongRouteDirection.REVERSE, result.direction)
        assertTrue(result.changed)
    }

    @Test
    fun unstableCompassCannotOverrideExpectedForwardRouteDirection() {
        val estimator = PedestrianRouteDirectionEstimator()
        estimator.update(100.0, 90.0, 270.0, true, false, 0, 8.0)
        val result = estimator.update(99.0, 90.0, 270.0, true, false, 5, 8.0)

        assertEquals(AlongRouteDirection.FORWARD, result.direction)
    }

    @Test
    fun accumulatedRouteProgressCorrectsAProvisionalCompassDecision() {
        val estimator = PedestrianRouteDirectionEstimator()
        estimator.update(100.0, 90.0, 270.0, true, true, 0, 8.0)
        estimator.update(101.0, 90.0, 270.0, true, true, 4, 8.0)
        val firstGpsVote = estimator.update(111.0, 90.0, 270.0, true, true, 10, 8.0)
        val corrected = estimator.update(125.0, 90.0, 270.0, true, true, 18, 8.0)

        assertEquals(AlongRouteDirection.REVERSE, firstGpsVote.direction)
        assertEquals(AlongRouteDirection.FORWARD, corrected.direction)
        assertTrue(corrected.changed)
    }

    @Test
    fun oneNoisyBackwardVoteDoesNotReverseConfirmedForwardDirection() {
        val estimator = PedestrianRouteDirectionEstimator()
        estimator.update(100.0, 90.0, 90.0, true, true, 0, 8.0)
        estimator.update(101.0, 90.0, 90.0, true, true, 4, 8.0)
        val noisy = estimator.update(90.0, 90.0, 90.0, true, true, 10, 8.0)

        assertEquals(AlongRouteDirection.FORWARD, noisy.direction)
        assertFalse(noisy.changed)
    }
}
