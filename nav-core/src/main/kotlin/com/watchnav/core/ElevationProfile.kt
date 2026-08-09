package com.watchnav.core

data class ClimbSegment(
    val startPointIndex: Int,
    val endPointIndex: Int,
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
    val ascentMeters: Double,
    val averageGradePercent: Double,
) {
    val lengthMeters: Double get() = endDistanceMeters - startDistanceMeters
}

data class ClimbProgress(
    val climb: ClimbSegment,
    val distanceToStartMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingAscentMeters: Double,
    val active: Boolean,
)

class ElevationProfile private constructor(
    val cumulativeAscentMeters: DoubleArray,
    val climbs: List<ClimbSegment>,
    private val preparedCourse: PreparedCourse,
) {
    val totalAscentMeters: Double = cumulativeAscentMeters.last()

    fun remainingAscentMeters(progressMeters: Double): Double =
        (totalAscentMeters - ascentAt(progressMeters)).coerceAtLeast(0.0)

    fun elevationAt(progressMeters: Double): Double? {
        val (index, fraction) = segmentAt(progressMeters)
        val start = preparedCourse.course.points[index].position.elevationMeters ?: return null
        if (index == preparedCourse.course.points.lastIndex) return start
        val end = preparedCourse.course.points[index + 1].position.elevationMeters ?: return start
        return start + (end - start) * fraction
    }

    fun gradePercentAt(progressMeters: Double, smoothingWindowMeters: Double = 80.0): Double? {
        val halfWindow = smoothingWindowMeters.coerceAtLeast(10.0) / 2.0
        val startDistance = (progressMeters - halfWindow).coerceAtLeast(0.0)
        val endDistance = (progressMeters + halfWindow).coerceAtMost(preparedCourse.totalDistanceMeters)
        if (endDistance - startDistance < 5.0) return null
        val startElevation = elevationAt(startDistance) ?: return null
        val endElevation = elevationAt(endDistance) ?: return null
        return (endElevation - startElevation) / (endDistance - startDistance) * 100.0
    }

    fun nextClimb(progressMeters: Double): ClimbProgress? {
        val climb = climbs.firstOrNull { it.endDistanceMeters > progressMeters } ?: return null
        val active = progressMeters >= climb.startDistanceMeters
        val remainingDistance = (climb.endDistanceMeters - progressMeters).coerceAtLeast(0.0)
        val gainedWithinClimb = if (active) {
            (ascentAt(progressMeters) - cumulativeAscentMeters[climb.startPointIndex])
                .coerceIn(0.0, climb.ascentMeters)
        } else {
            0.0
        }
        return ClimbProgress(
            climb = climb,
            distanceToStartMeters = (climb.startDistanceMeters - progressMeters).coerceAtLeast(0.0),
            remainingDistanceMeters = remainingDistance,
            remainingAscentMeters = (climb.ascentMeters - gainedWithinClimb).coerceAtLeast(0.0),
            active = active,
        )
    }

    private fun ascentAt(progressMeters: Double): Double {
        val (low, fraction) = segmentAt(progressMeters)
        if (low == cumulativeAscentMeters.lastIndex) return cumulativeAscentMeters.last()
        return cumulativeAscentMeters[low] +
            (cumulativeAscentMeters[low + 1] - cumulativeAscentMeters[low]) * fraction
    }

    private fun segmentAt(progressMeters: Double): Pair<Int, Double> {
        val progress = progressMeters.coerceIn(0.0, preparedCourse.totalDistanceMeters)
        val distances = preparedCourse.cumulativeDistanceMeters
        var low = 0
        var high = distances.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (distances[mid] <= progress) low = mid else high = mid - 1
        }
        if (low == distances.lastIndex) return low to 0.0
        val span = distances[low + 1] - distances[low]
        if (span <= 0.0) return low to 0.0
        val fraction = ((progress - distances[low]) / span).coerceIn(0.0, 1.0)
        return low to fraction
    }

    companion object {
        fun prepare(
            preparedCourse: PreparedCourse,
            minimumAscentMeters: Double = 20.0,
            minimumLengthMeters: Double = 150.0,
            minimumGradePercent: Double = 2.0,
            descentToleranceMeters: Double = 10.0,
        ): ElevationProfile {
            val points = preparedCourse.course.points
            val cumulativeAscent = DoubleArray(points.size)
            for (index in 1 until points.size) {
                cumulativeAscent[index] = cumulativeAscent[index - 1]
                if (index in preparedCourse.course.breakBeforePointIndices) continue
                val previous = points[index - 1].position.elevationMeters
                val current = points[index].position.elevationMeters
                if (previous != null && current != null && current > previous) {
                    cumulativeAscent[index] += current - previous
                }
            }

            val rawClimbs = ArrayList<ClimbSegment>()
            var startIndex: Int? = null
            var peakIndex: Int? = null
            var peakElevation: Double? = null

            fun closeClimb() {
                val start = startIndex ?: return
                val end = peakIndex ?: return
                if (end <= start) return
                val length = preparedCourse.cumulativeDistanceMeters[end] -
                    preparedCourse.cumulativeDistanceMeters[start]
                val ascent = cumulativeAscent[end] - cumulativeAscent[start]
                val grade = if (length > 0) ascent / length * 100.0 else 0.0
                if (ascent >= minimumAscentMeters && length >= minimumLengthMeters &&
                    grade >= minimumGradePercent
                ) {
                    rawClimbs += ClimbSegment(
                        startPointIndex = start,
                        endPointIndex = end,
                        startDistanceMeters = preparedCourse.cumulativeDistanceMeters[start],
                        endDistanceMeters = preparedCourse.cumulativeDistanceMeters[end],
                        ascentMeters = ascent,
                        averageGradePercent = grade,
                    )
                }
            }

            points.forEachIndexed { index, point ->
                val elevation = point.position.elevationMeters
                if (elevation == null || index in preparedCourse.course.breakBeforePointIndices) {
                    closeClimb()
                    startIndex = null
                    peakIndex = null
                    peakElevation = null
                    return@forEachIndexed
                }
                if (startIndex == null) {
                    startIndex = index
                    peakIndex = index
                    peakElevation = elevation
                    return@forEachIndexed
                }
                if (elevation >= requireNotNull(peakElevation)) {
                    peakElevation = elevation
                    peakIndex = index
                }
                if (requireNotNull(peakElevation) - elevation >= descentToleranceMeters) {
                    closeClimb()
                    startIndex = index
                    peakIndex = index
                    peakElevation = elevation
                } else {
                    val start = requireNotNull(startIndex)
                    val candidateAscent = cumulativeAscent[index] - cumulativeAscent[start]
                    val candidateLength = preparedCourse.cumulativeDistanceMeters[index] -
                        preparedCourse.cumulativeDistanceMeters[start]
                    val netRise = elevation -
                        requireNotNull(points[start].position.elevationMeters)
                    if (candidateAscent < minimumAscentMeters && candidateLength > 250.0 && netRise < 5.0) {
                        startIndex = index
                        peakIndex = index
                        peakElevation = elevation
                    }
                }
            }
            closeClimb()

            return ElevationProfile(
                cumulativeAscentMeters = cumulativeAscent,
                climbs = rawClimbs,
                preparedCourse = preparedCourse,
            )
        }
    }
}
