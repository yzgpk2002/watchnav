package com.watchnav.core

import java.util.ArrayDeque

/**
 * Builds a lightweight rendering index with Ramer-Douglas-Peucker while leaving
 * the original course untouched for matching, distance and elevation work.
 */
object CourseRenderSimplifier {
    fun retainedPointIndices(course: Course, toleranceMeters: Double = 2.0): IntArray {
        require(toleranceMeters >= 0.0 && toleranceMeters.isFinite())
        if (course.points.size <= 2 || toleranceMeters == 0.0) {
            return course.points.indices.toList().toIntArray()
        }

        val retained = BooleanArray(course.points.size)
        val segmentStarts = listOf(0) + course.breakBeforePointIndices.sorted()
        val segmentEnds = course.breakBeforePointIndices.sorted().map { it - 1 } + course.points.lastIndex
        segmentStarts.zip(segmentEnds).forEach { (start, end) ->
            simplifySegment(course, start, end, toleranceMeters, retained)
        }
        return retained.indices.filter(retained::get).toIntArray()
    }

    private fun simplifySegment(
        course: Course,
        start: Int,
        end: Int,
        toleranceMeters: Double,
        retained: BooleanArray,
    ) {
        retained[start] = true
        retained[end] = true
        if (end - start < 2) return

        val pending = ArrayDeque<IntRange>()
        pending.add(start..end)
        while (pending.isNotEmpty()) {
            val range = pending.removeLast()
            val first = course.points[range.first].position
            val last = course.points[range.last].position
            var furthestIndex = -1
            var furthestDistance = toleranceMeters
            for (index in range.first + 1 until range.last) {
                val distance = GeoMath.projectOnSegment(
                    course.points[index].position,
                    first,
                    last,
                ).distanceMeters
                if (distance > furthestDistance) {
                    furthestDistance = distance
                    furthestIndex = index
                }
            }
            if (furthestIndex >= 0) {
                retained[furthestIndex] = true
                if (furthestIndex - range.first > 1) pending.add(range.first..furthestIndex)
                if (range.last - furthestIndex > 1) pending.add(furthestIndex..range.last)
            }
        }
    }
}
