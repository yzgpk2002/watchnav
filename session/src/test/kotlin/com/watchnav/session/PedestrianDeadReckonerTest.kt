package com.watchnav.session

import com.watchnav.core.GeoMath
import com.watchnav.core.GeoPoint
import com.watchnav.core.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianDeadReckonerTest {
    @Test
    fun stepsAdvanceAlongTrueHeadingAndGrowUncertainty() {
        val start = LocationFix(GeoPoint(30.0, 120.0), 8.0, 1_000)
        val reckoner = PedestrianDeadReckoner()
        reckoner.anchor(start, cumulativeSteps = 100)

        val moved = requireNotNull(reckoner.advance(110, trueHeadingDegrees = 0.0, timestamp = 6_000))

        assertTrue(moved.position.latitude > start.position.latitude)
        assertEquals(7.2, GeoMath.distanceMeters(start.position, moved.position), 0.15)
        assertTrue(moved.accuracyMeters > start.accuracyMeters)
    }

    @Test
    fun duplicateStepCountDoesNotPublishAnotherFix() {
        val reckoner = PedestrianDeadReckoner()
        reckoner.anchor(LocationFix(GeoPoint(30.0, 120.0), 8.0, 1_000), 20)
        assertTrue(reckoner.advance(20, 90.0, 2_000) == null)
    }
}
