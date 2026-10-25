package com.watchnav.app

import com.watchnav.core.Course
import com.watchnav.core.GeoPoint

/** Product rule: selecting a GPX first navigates to its declared first point. */
internal object RouteApproachTarget {
    fun select(course: Course): GeoPoint = course.points.first().position
}
