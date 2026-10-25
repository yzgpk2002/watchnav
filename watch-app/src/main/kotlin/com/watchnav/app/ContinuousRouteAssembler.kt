package com.watchnav.app

import com.watchnav.core.Course
import com.watchnav.core.GeoMath

/** Joins an offline road approach and the selected GPX into one unbroken distance axis. */
internal object ContinuousRouteAssembler {
    fun combine(approach: Course, selected: Course): Course {
        val seamIsDuplicate = GeoMath.distanceMeters(
            approach.points.last().position,
            selected.points.first().position,
        ) <= SEAM_DUPLICATE_METERS
        val approachCount = approach.points.size
        val points = if (seamIsDuplicate) {
            approach.points + selected.points.drop(1)
        } else {
            approach.points + selected.points
        }
        val selectedShift = if (seamIsDuplicate) approachCount - 1 else approachCount
        return selected.copy(
            points = points,
            breakBeforePointIndices = approach.breakBeforePointIndices
                .filterTo(linkedSetOf()) { it in 1 until approachCount }
                .plus(selected.breakBeforePointIndices.map { it + selectedShift })
                // Never let metadata split the generated approach from the selected route.
                .minus(selectedShift),
        )
    }

    private const val SEAM_DUPLICATE_METERS = 3.0
}
