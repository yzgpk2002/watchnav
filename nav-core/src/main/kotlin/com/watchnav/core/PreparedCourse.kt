package com.watchnav.core

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

class PreparedCourse private constructor(
    val course: Course,
    val cumulativeDistanceMeters: DoubleArray,
    internal val segments: List<Segment>,
    private val grid: Map<Long, IntArray>,
    private val cellSizeDegrees: Double,
) {
    data class Segment(
        val startPointIndex: Int,
        val endPointIndex: Int,
        val startDistanceMeters: Double,
        val lengthMeters: Double,
        val bearingDegrees: Double,
    )

    val totalDistanceMeters: Double = cumulativeDistanceMeters.last()

    fun candidateSegmentIndices(point: GeoPoint, radiusMeters: Double): IntArray {
        val latitudeRadius = radiusMeters / 111_320.0
        val lonScale = max(0.15, cos(Math.toRadians(point.latitude)))
        val longitudeRadius = radiusMeters / (111_320.0 * lonScale)
        val minX = floor((point.longitude - longitudeRadius) / cellSizeDegrees).toInt()
        val maxX = floor((point.longitude + longitudeRadius) / cellSizeDegrees).toInt()
        val minY = floor((point.latitude - latitudeRadius) / cellSizeDegrees).toInt()
        val maxY = floor((point.latitude + latitudeRadius) / cellSizeDegrees).toInt()
        val result = LinkedHashSet<Int>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                grid[cellKey(x, y)]?.forEach(result::add)
            }
        }
        return result.toIntArray()
    }

    companion object {
        private const val DEFAULT_CELL_SIZE_DEGREES = 0.0025

        fun prepare(course: Course): PreparedCourse {
            val cumulative = DoubleArray(course.points.size)
            val segments = ArrayList<Segment>(course.points.size - 1)
            for (pointIndex in 1 until course.points.size) {
                cumulative[pointIndex] = cumulative[pointIndex - 1]
                if (pointIndex in course.breakBeforePointIndices) continue
                val start = course.points[pointIndex - 1].position
                val end = course.points[pointIndex].position
                val length = GeoMath.distanceMeters(start, end)
                if (length < 0.05) continue
                segments += Segment(
                    startPointIndex = pointIndex - 1,
                    endPointIndex = pointIndex,
                    startDistanceMeters = cumulative[pointIndex - 1],
                    lengthMeters = length,
                    bearingDegrees = GeoMath.initialBearingDegrees(start, end),
                )
                cumulative[pointIndex] += length
            }
            require(segments.isNotEmpty()) { "Course has no non-zero segments" }

            val mutableGrid = HashMap<Long, MutableList<Int>>()
            segments.forEachIndexed { segmentIndex, segment ->
                val start = course.points[segment.startPointIndex].position
                val end = course.points[segment.endPointIndex].position
                val minX = floor(minOf(start.longitude, end.longitude) / DEFAULT_CELL_SIZE_DEGREES).toInt()
                val maxX = floor(maxOf(start.longitude, end.longitude) / DEFAULT_CELL_SIZE_DEGREES).toInt()
                val minY = floor(minOf(start.latitude, end.latitude) / DEFAULT_CELL_SIZE_DEGREES).toInt()
                val maxY = floor(maxOf(start.latitude, end.latitude) / DEFAULT_CELL_SIZE_DEGREES).toInt()
                val maximumCells = 512
                if ((maxX - minX + 1) * (maxY - minY + 1) > maximumCells) {
                    val samples = max(2, ceil(segment.lengthMeters / 100.0).toInt())
                    for (sample in 0..samples) {
                        val fraction = sample.toDouble() / samples
                        val x = floor((start.longitude + (end.longitude - start.longitude) * fraction) /
                            DEFAULT_CELL_SIZE_DEGREES).toInt()
                        val y = floor((start.latitude + (end.latitude - start.latitude) * fraction) /
                            DEFAULT_CELL_SIZE_DEGREES).toInt()
                        mutableGrid.getOrPut(cellKey(x, y)) { ArrayList() }.add(segmentIndex)
                    }
                } else {
                    for (x in minX..maxX) {
                        for (y in minY..maxY) {
                            mutableGrid.getOrPut(cellKey(x, y)) { ArrayList() }.add(segmentIndex)
                        }
                    }
                }
            }
            return PreparedCourse(
                course = course,
                cumulativeDistanceMeters = cumulative,
                segments = segments,
                grid = mutableGrid.mapValues { (_, value) -> value.distinct().toIntArray() },
                cellSizeDegrees = DEFAULT_CELL_SIZE_DEGREES,
            )
        }

        private fun cellKey(x: Int, y: Int): Long =
            (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    }
}

private fun cellKey(x: Int, y: Int): Long =
    (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

