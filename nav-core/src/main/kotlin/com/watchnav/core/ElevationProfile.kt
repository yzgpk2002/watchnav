package com.watchnav.core

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

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
    private val cumulativeTravelSeconds: DoubleArray,
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

    /** Standard deviation of the local terrain heights used to tune DEM observation noise. */
    fun terrainRoughnessMeters(progressMeters: Double, windowMeters: Double = 200.0): Double? {
        val halfWindow = windowMeters.coerceAtLeast(50.0) / 2.0
        val start = (progressMeters - halfWindow).coerceAtLeast(0.0)
        val end = (progressMeters + halfWindow).coerceAtMost(preparedCourse.totalDistanceMeters)
        if (end <= start) return null
        val samples = ArrayList<Double>()
        var distance = start
        while (distance < end) {
            elevationAt(distance)?.let(samples::add)
            distance += TERRAIN_SAMPLE_SPACING_METERS
        }
        elevationAt(end)?.let(samples::add)
        if (samples.size < 2) return null
        val mean = samples.average()
        return sqrt(samples.sumOf { (it - mean).pow(2) } / samples.size)
    }

    /**
     * Terrain-aware ETA based on Tobler walking speed plus Naismith's ascent allowance.
     * Returned seconds are deterministic and usable without network or live speed data.
     */
    fun estimatedTravelTimeSeconds(
        startProgressMeters: Double,
        endProgressMeters: Double = preparedCourse.totalDistanceMeters,
    ): Double {
        val start = startProgressMeters.coerceIn(0.0, preparedCourse.totalDistanceMeters)
        val end = endProgressMeters.coerceIn(start, preparedCourse.totalDistanceMeters)
        return (travelSecondsAt(end) - travelSecondsAt(start)).coerceAtLeast(0.0)
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

    private fun travelSecondsAt(progressMeters: Double): Double {
        val (low, fraction) = segmentAt(progressMeters)
        if (low == cumulativeTravelSeconds.lastIndex) return cumulativeTravelSeconds.last()
        return cumulativeTravelSeconds[low] +
            (cumulativeTravelSeconds[low + 1] - cumulativeTravelSeconds[low]) * fraction
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
        /** Report model: V = 6 * exp(-3.5 * |slope|), km/h. */
        fun toblerWalkingSpeedKmh(gradeRatio: Double): Double =
            (6.0 * exp(-3.5 * kotlin.math.abs(gradeRatio))).coerceIn(0.5, 6.0)

        fun prepare(
            preparedCourse: PreparedCourse,
            minimumAscentMeters: Double = 20.0,
            minimumLengthMeters: Double = 150.0,
            minimumGradePercent: Double = 2.0,
            descentToleranceMeters: Double = 10.0,
        ): ElevationProfile {
            val points = preparedCourse.course.points
            val cumulativeAscent = DoubleArray(points.size)
            val cumulativeTravel = DoubleArray(points.size)
            for (index in 1 until points.size) {
                cumulativeAscent[index] = cumulativeAscent[index - 1]
                cumulativeTravel[index] = cumulativeTravel[index - 1]
                if (index in preparedCourse.course.breakBeforePointIndices) continue
                val previous = points[index - 1].position.elevationMeters
                val current = points[index].position.elevationMeters
                if (previous != null && current != null && current > previous) {
                    cumulativeAscent[index] += current - previous
                }
                val horizontalMeters = preparedCourse.cumulativeDistanceMeters[index] -
                    preparedCourse.cumulativeDistanceMeters[index - 1]
                if (horizontalMeters > 0.0) {
                    val riseMeters = if (previous != null && current != null) current - previous else 0.0
                    cumulativeTravel[index] += horizontalMeters /
                        (toblerWalkingSpeedKmh(riseMeters / horizontalMeters) / 3.6)
                    if (riseMeters > 0.0) {
                        cumulativeTravel[index] += riseMeters /
                            DEFAULT_ASCENT_RATE_METERS_PER_HOUR * 3_600.0
                    }
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
                cumulativeTravelSeconds = cumulativeTravel,
                climbs = rawClimbs,
                preparedCourse = preparedCourse,
            )
        }

        private const val TERRAIN_SAMPLE_SPACING_METERS = 25.0
        private const val DEFAULT_ASCENT_RATE_METERS_PER_HOUR = 600.0
    }
}
