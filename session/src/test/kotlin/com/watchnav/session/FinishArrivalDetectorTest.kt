package com.watchnav.session

import com.watchnav.core.GeoPoint
import com.watchnav.core.LocationFix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishArrivalDetectorTest {
    private val finish = GeoPoint(30.2700, 120.0950)

    @Test
    fun closedLoopDoesNotCompleteBeforeLeavingStartFinishArea() {
        val detector = FinishArrivalDetector(finish)

        assertFalse(detector.update(fix(finish, accuracy = 10.0)))
    }

    @Test
    fun returningInsideAccuracyAdjustedFinishAreaCompletesAfterProgressReset() {
        val detector = FinishArrivalDetector(finish)
        detector.observeHistoricalPosition(GeoPoint(30.1600, 120.0950))

        assertTrue(detector.update(fix(GeoPoint(30.2700, 120.0937), accuracy = 40.0)))
    }

    @Test
    fun finishAreaSuppressesAutomaticReroutingBeforeArrivalIsConfirmed() {
        val detector = FinishArrivalDetector(finish)

        assertTrue(detector.isInsideRerouteProtection(fix(GeoPoint(30.2700, 120.0937), accuracy = 40.0)))
    }

    private fun fix(position: GeoPoint, accuracy: Double) = LocationFix(
        position = position,
        accuracyMeters = accuracy,
        timestampMillis = 1_000L,
    )
}
