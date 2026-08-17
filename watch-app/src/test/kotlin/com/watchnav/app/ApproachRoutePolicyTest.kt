package com.watchnav.app

import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproachRoutePolicyTest {
    private val origin = GeoPoint(30.0, 120.0)
    private val northTarget = GeoPoint(30.04, 120.0)

    @Test
    fun retriesWhenRoadRouteStartsInOppositeDirectionAndDetours() {
        val detour = course(
            origin,
            GeoPoint(29.998, 120.002),
            GeoPoint(29.98, 120.02),
            GeoPoint(30.04, 120.0),
        )

        assertTrue(ApproachRoutePolicy.shouldTryCorridor(origin, northTarget, detour))
    }

    @Test
    fun doesNotRetryAReasonableRoadRoute() {
        val road = course(
            origin,
            GeoPoint(30.002, 120.0),
            GeoPoint(30.02, 120.001),
            northTarget,
        )

        assertFalse(ApproachRoutePolicy.shouldTryCorridor(origin, northTarget, road))
    }

    @Test
    fun prefersRoadCorridorThatCorrectsInitialHeading() {
        val detour = course(
            origin,
            GeoPoint(29.998, 120.002),
            GeoPoint(29.98, 120.02),
            northTarget,
        )
        val corridor = course(
            origin,
            GeoPoint(30.004, 120.0002),
            GeoPoint(30.02, 120.001),
            northTarget,
        )

        assertTrue(ApproachRoutePolicy.preferCorridor(origin, northTarget, detour, corridor))
    }

    @Test
    fun egressGateIsPlacedTowardTheTarget() {
        val gate = ApproachRoutePolicy.egressGate(origin, northTarget)

        assertTrue(gate.latitude > origin.latitude)
        assertTrue(kotlin.math.abs(gate.longitude - origin.longitude) < 0.00001)
    }

    private fun course(vararg points: GeoPoint) = Course(
        name = "test",
        points = points.map(::CoursePoint),
    )
}
