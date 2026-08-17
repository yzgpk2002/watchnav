package com.watchnav.app

import com.watchnav.core.Course
import com.watchnav.core.GeoMath
import com.watchnav.core.GeoPoint
import com.watchnav.core.PreparedCourse
import kotlin.math.min

/** Keeps an access route on the road graph without accepting an obviously wrong first detour. */
internal object ApproachRoutePolicy {
    private const val HEADING_SAMPLE_METERS = 180.0
    private const val RETRY_MINIMUM_DIRECT_METERS = 250.0
    private const val RETRY_ROUTE_RATIO = 1.45
    private const val RETRY_HEADING_DIVERGENCE_DEGREES = 55.0
    private const val EGRESS_GATE_METERS = 220.0
    private const val MAXIMUM_GATE_SNAP_METERS = 180.0
    private const val MAXIMUM_CORRIDOR_ROUTE_RATIO = 1.35
    private const val REQUIRED_HEADING_IMPROVEMENT_DEGREES = 18.0

    data class RouteMetrics(
        val distanceMeters: Double,
        val initialHeadingDegrees: Double,
        val targetHeadingDegrees: Double,
    ) {
        val initialDivergenceDegrees: Double
            get() = GeoMath.angularDifferenceDegrees(initialHeadingDegrees, targetHeadingDegrees)
    }

    fun metrics(origin: GeoPoint, destination: GeoPoint, route: Course): RouteMetrics = RouteMetrics(
        distanceMeters = PreparedCourse.prepare(route).totalDistanceMeters,
        initialHeadingDegrees = initialHeading(origin, route),
        targetHeadingDegrees = GeoMath.initialBearingDegrees(origin, destination),
    )

    fun shouldTryCorridor(
        origin: GeoPoint,
        destination: GeoPoint,
        route: Course,
    ): Boolean {
        val direct = GeoMath.distanceMeters(origin, destination)
        if (direct < RETRY_MINIMUM_DIRECT_METERS) return false
        val metrics = metrics(origin, destination, route)
        return metrics.distanceMeters > direct * RETRY_ROUTE_RATIO &&
            metrics.initialDivergenceDegrees > RETRY_HEADING_DIVERGENCE_DEGREES
    }

    fun egressGate(origin: GeoPoint, destination: GeoPoint): GeoPoint {
        val direct = GeoMath.distanceMeters(origin, destination).coerceAtLeast(1.0)
        val fraction = min(EGRESS_GATE_METERS / direct, 0.20)
        return GeoPoint(
            latitude = origin.latitude + (destination.latitude - origin.latitude) * fraction,
            longitude = origin.longitude + (destination.longitude - origin.longitude) * fraction,
        )
    }

    fun gateSnapIsUsable(gate: GeoPoint, roadFromGate: Course): Boolean =
        GeoMath.distanceMeters(gate, roadFromGate.points.first().position) <= MAXIMUM_GATE_SNAP_METERS

    fun prependEgress(
        origin: GeoPoint,
        gate: GeoPoint,
        roadFromGate: Course,
    ): Course = roadFromGate.copy(
        points = listOf(
            com.watchnav.core.CoursePoint(origin),
            com.watchnav.core.CoursePoint(gate),
        ) + roadFromGate.points,
        breakBeforePointIndices = roadFromGate.breakBeforePointIndices.mapTo(linkedSetOf()) { it + 2 },
    )

    fun preferCorridor(
        origin: GeoPoint,
        destination: GeoPoint,
        baseline: Course,
        corridor: Course,
    ): Boolean {
        val baselineMetrics = metrics(origin, destination, baseline)
        val corridorMetrics = metrics(origin, destination, corridor)
        return corridorMetrics.distanceMeters <= baselineMetrics.distanceMeters * MAXIMUM_CORRIDOR_ROUTE_RATIO &&
            corridorMetrics.initialDivergenceDegrees + REQUIRED_HEADING_IMPROVEMENT_DEGREES <=
            baselineMetrics.initialDivergenceDegrees
    }

    private fun initialHeading(origin: GeoPoint, course: Course): Double {
        var distance = 0.0
        var previous = origin
        for (point in course.points) {
            distance += GeoMath.distanceMeters(previous, point.position)
            if (distance >= HEADING_SAMPLE_METERS) {
                return GeoMath.initialBearingDegrees(origin, point.position)
            }
            previous = point.position
        }
        return GeoMath.initialBearingDegrees(origin, course.points.last().position)
    }
}
