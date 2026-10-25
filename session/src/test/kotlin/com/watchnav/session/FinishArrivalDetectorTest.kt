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

    @Test
    fun offRouteShortcutCompletesAfterRealTravelWithoutRouteProgress() {
        val detector = FinishArrivalDetector(finish)

        // The whole shortcut may stay inside the old 300 m arming circle.
        assertFalse(detector.update(fix(GeoPoint(30.2705, 120.0950), accuracy = 8.0)))
        assertTrue(detector.update(fix(finish, accuracy = 8.0), travelledDistanceMeters = 320.0))
    }

    @Test
    fun shortLoopArmsAfterLeavingSeventyFiveMetersAndCompletesOnReturn() {
        val detector = FinishArrivalDetector(finish)

        assertFalse(detector.update(fix(finish, accuracy = 8.0)))
        assertFalse(detector.update(fix(GeoPoint(30.2708, 120.0950), accuracy = 8.0)))
        assertTrue(detector.update(fix(finish, accuracy = 8.0)))
    }

    @Test
    fun realOutdoorShortcutCompletesAtDeclaredRouteStart() {
        val routeStart = GeoPoint(30.160621666666668, 120.10198166666666)
        val detector = FinishArrivalDetector(routeStart)

        assertFalse(detector.update(fix(GeoPoint(30.271168333333335, 120.09400166666668), 12.0)))
        assertTrue(detector.update(fix(GeoPoint(30.160384, 120.102087), 40.0), 12_000.0))
    }

    private fun fix(position: GeoPoint, accuracy: Double) = LocationFix(
        position = position,
        accuracyMeters = accuracy,
        timestampMillis = 1_000L,
    )
}
