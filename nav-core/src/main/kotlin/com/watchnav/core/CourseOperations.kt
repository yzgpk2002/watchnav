package com.watchnav.core

fun Course.reversed(newName: String = "$name (reverse)"): Course = Course(
    name = newName,
    points = points.asReversed(),
    breakBeforePointIndices = breakBeforePointIndices.mapTo(LinkedHashSet()) { points.size - it },
    landmarks = landmarks,
)

/**
 * Builds the forward portion of this course beginning at the point nearest [origin].
 *
 * Imported GPX tracks often start kilometres away even though the track itself passes beside the
 * user.  Connecting to points.first() in that case makes otherwise correct guidance point towards
 * the wrong end of the route.  This operation projects onto real, unbroken course segments and
 * keeps only the forward continuation from that projection.
 */
fun Course.continuationFromNearestPoint(origin: GeoPoint): NearestCourseContinuation {
    val candidate = (1 until points.size)
        .asSequence()
        .filterNot(breakBeforePointIndices::contains)
        .map { endIndex ->
            val projection = GeoMath.projectOnSegment(
                origin,
                points[endIndex - 1].position,
                points[endIndex].position,
            )
            ContinuationCandidate(endIndex, projection)
        }
        .minByOrNull { it.projection.distanceMeters }
        ?: error("Course has no usable segment")

    val endIndex = candidate.endIndex
    var joinPosition = candidate.projection.point
    // Keep a non-zero final leg when the user is effectively already at the route finish.
    if (endIndex == points.lastIndex &&
        GeoMath.distanceMeters(joinPosition, points.last().position) < MINIMUM_CONTINUATION_METERS
    ) {
        joinPosition = points[endIndex - 1].position
    }
    val continuationPoints = buildList {
        add(CoursePoint(joinPosition))
        addAll(points.drop(endIndex))
    }
    val continuationBreaks = breakBeforePointIndices.asSequence()
        .filter { it > endIndex }
        .mapTo(linkedSetOf()) { it - endIndex + 1 }
    return NearestCourseContinuation(
        joinPosition = joinPosition,
        distanceMeters = GeoMath.distanceMeters(origin, joinPosition),
        course = copy(
            points = continuationPoints,
            breakBeforePointIndices = continuationBreaks,
        ),
    )
}

data class NearestCourseContinuation(
    val joinPosition: GeoPoint,
    val distanceMeters: Double,
    val course: Course,
)

private data class ContinuationCandidate(
    val endIndex: Int,
    val projection: SegmentProjection,
)

private const val MINIMUM_CONTINUATION_METERS = 2.0
