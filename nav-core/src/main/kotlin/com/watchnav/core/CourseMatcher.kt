package com.watchnav.core

import kotlin.math.max

data class CourseMatch(
    val snappedPosition: GeoPoint,
    val segmentIndex: Int,
    val rawProgressMeters: Double,
    val stableProgressMeters: Double,
    val remainingMeters: Double,
    val crossTrackDistanceMeters: Double,
)

class CourseMatcher(private val preparedCourse: PreparedCourse) {
    private var lastSegmentIndex: Int? = null
    private var stableProgressMeters = 0.0

    fun match(fix: LocationFix): CourseMatch {
        val searchRadius = max(150.0, fix.accuracyMeters * 4)
        var candidates = preparedCourse.candidateSegmentIndices(fix.position, searchRadius)
        if (candidates.isEmpty()) {
            candidates = preparedCourse.candidateSegmentIndices(fix.position, 2_000.0)
        }
        if (candidates.isEmpty()) {
            candidates = preparedCourse.segments.indices.toList().toIntArray()
        }

        var best: Candidate? = null
        for (segmentIndex in candidates) {
            val segment = preparedCourse.segments[segmentIndex]
            val start = preparedCourse.course.points[segment.startPointIndex].position
            val end = preparedCourse.course.points[segment.endPointIndex].position
            val projection = GeoMath.projectOnSegment(fix.position, start, end)
            val progress = segment.startDistanceMeters + segment.lengthMeters * projection.fraction
            // At walking/fishing speed compass interference is common and GNSS COG may be absent.
            // Geometry must win; heading only disambiguates nearby parallel segments at speed.
            val headingPenalty = fix.bearingDegrees?.takeIf {
                (fix.speedMetersPerSecond ?: 0.0) >= HEADING_ASSIST_MINIMUM_SPEED_METERS_PER_SECOND
            }?.let {
                GeoMath.angularDifferenceDegrees(it, segment.bearingDegrees) / 180.0 * MAXIMUM_HEADING_PENALTY_METERS
            } ?: 0.0
            val backwardMeters = (stableProgressMeters - progress - 25.0).coerceAtLeast(0.0)
            val backwardPenalty = backwardMeters.coerceAtMost(250.0) * 1.5
            val continuityPenalty = lastSegmentIndex?.let { previous ->
                val jump = kotlin.math.abs(segmentIndex - previous)
                if (jump > 150) 25.0 else 0.0
            } ?: 0.0
            val score = projection.distanceMeters + headingPenalty + backwardPenalty + continuityPenalty
            if (best == null || score < best.score) {
                best = Candidate(segmentIndex, projection, progress, score)
            }
        }

        val selected = requireNotNull(best)
        if (selected.projection.distanceMeters <= max(100.0, fix.accuracyMeters * 3)) {
            stableProgressMeters = max(stableProgressMeters, selected.progressMeters)
        }
        // A closed course often has dense, overlapping geometry around its shared start/finish.
        // Near the finish GNSS noise can therefore win the segment score and leave the matcher on
        // an older segment even though the athlete has physically arrived. Arm endpoint arrival
        // only late in the activity so standing at the same point before starting cannot complete
        // a loop immediately.
        val finish = preparedCourse.course.points.last().position
        val finishDistanceMeters = GeoMath.distanceMeters(fix.position, finish)
        val finishRadiusMeters = max(
            MINIMUM_FINISH_RADIUS_METERS,
            fix.accuracyMeters * FINISH_ACCURACY_FACTOR,
        ).coerceAtMost(MAXIMUM_FINISH_RADIUS_METERS)
        val finishArmed = stableProgressMeters >=
            preparedCourse.totalDistanceMeters * FINISH_ARMING_PROGRESS_RATIO
        if (finishArmed && finishDistanceMeters <= finishRadiusMeters) {
            stableProgressMeters = preparedCourse.totalDistanceMeters
            val finishSegmentIndex = preparedCourse.segments.lastIndex
            lastSegmentIndex = finishSegmentIndex
            return CourseMatch(
                snappedPosition = finish,
                segmentIndex = finishSegmentIndex,
                rawProgressMeters = preparedCourse.totalDistanceMeters,
                stableProgressMeters = preparedCourse.totalDistanceMeters,
                remainingMeters = 0.0,
                crossTrackDistanceMeters = finishDistanceMeters,
            )
        }
        lastSegmentIndex = selected.segmentIndex
        return CourseMatch(
            snappedPosition = selected.projection.point,
            segmentIndex = selected.segmentIndex,
            rawProgressMeters = selected.progressMeters,
            stableProgressMeters = stableProgressMeters,
            remainingMeters = (preparedCourse.totalDistanceMeters - stableProgressMeters).coerceAtLeast(0.0),
            crossTrackDistanceMeters = selected.projection.distanceMeters,
        )
    }

    fun reset() {
        lastSegmentIndex = null
        stableProgressMeters = 0.0
    }

    fun restoreProgress(progressMeters: Double) {
        stableProgressMeters = progressMeters.coerceIn(0.0, preparedCourse.totalDistanceMeters)
        lastSegmentIndex = preparedCourse.segments.indexOfLast {
            it.startDistanceMeters <= stableProgressMeters
        }.takeIf { it >= 0 }
    }

    private data class Candidate(
        val segmentIndex: Int,
        val projection: SegmentProjection,
        val progressMeters: Double,
        val score: Double,
    )

    private companion object {
        const val HEADING_ASSIST_MINIMUM_SPEED_METERS_PER_SECOND = 1.2
        const val MAXIMUM_HEADING_PENALTY_METERS = 12.0
        const val FINISH_ARMING_PROGRESS_RATIO = 0.75
        const val FINISH_ACCURACY_FACTOR = 1.5
        const val MINIMUM_FINISH_RADIUS_METERS = 20.0
        const val MAXIMUM_FINISH_RADIUS_METERS = 60.0
    }
}
