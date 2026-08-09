package com.watchnav.core

data class NavigationUpdate(
    val match: CourseMatch,
    val adherence: AdherenceUpdate,
    val nextCue: NavigationCue?,
    val distanceToNextCueMeters: Double?,
)

class NavigationEngine(course: Course, mode: ActivityMode) {
    val preparedCourse: PreparedCourse = PreparedCourse.prepare(course)
    val cues: List<NavigationCue> = CueGenerator.generate(preparedCourse)
    private val matcher = CourseMatcher(preparedCourse)
    private val offCourseDetector = OffCourseDetector(mode)

    fun update(fix: LocationFix): NavigationUpdate {
        val match = matcher.match(fix)
        val adherence = offCourseDetector.update(
            crossTrackDistanceMeters = match.crossTrackDistanceMeters,
            horizontalAccuracyMeters = fix.accuracyMeters,
        )
        val nextCue = cues.firstOrNull {
            it.distanceFromStartMeters > match.stableProgressMeters + 3.0
        }
        return NavigationUpdate(
            match = match,
            adherence = adherence,
            nextCue = nextCue,
            distanceToNextCueMeters = nextCue?.let {
                (it.distanceFromStartMeters - match.stableProgressMeters).coerceAtLeast(0.0)
            },
        )
    }

    fun reset() {
        matcher.reset()
        offCourseDetector.reset()
    }

    fun restoreProgress(progressMeters: Double) {
        matcher.restoreProgress(progressMeters)
    }
}
