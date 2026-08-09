package com.watchnav.core

import kotlin.math.max

enum class CourseAdherence {
    ON_COURSE,
    SUSPECTED_OFF_COURSE,
    OFF_COURSE,
    REJOINING,
}

data class AdherenceUpdate(
    val state: CourseAdherence,
    val previousState: CourseAdherence,
    val thresholdMeters: Double,
) {
    val changed: Boolean get() = state != previousState
}

class OffCourseDetector(
    private val mode: ActivityMode,
    private val confirmationsToLeave: Int = 3,
    private val confirmationsToReturn: Int = 3,
) {
    var state: CourseAdherence = CourseAdherence.ON_COURSE
        private set

    private var outsideCount = 0
    private var insideCount = 0

    fun update(crossTrackDistanceMeters: Double, horizontalAccuracyMeters: Double): AdherenceUpdate {
        val previous = state
        val threshold = max(mode.defaultOffCourseMeters, horizontalAccuracyMeters * 2)
        val recoveryThreshold = threshold * 0.65
        val reliable = horizontalAccuracyMeters <= 100.0
        if (!reliable) return AdherenceUpdate(state, previous, threshold)

        when (state) {
            CourseAdherence.ON_COURSE -> {
                if (crossTrackDistanceMeters > threshold) {
                    outsideCount = 1
                    state = CourseAdherence.SUSPECTED_OFF_COURSE
                }
            }
            CourseAdherence.SUSPECTED_OFF_COURSE -> {
                if (crossTrackDistanceMeters > threshold) {
                    outsideCount++
                    if (outsideCount >= confirmationsToLeave) {
                        state = CourseAdherence.OFF_COURSE
                        insideCount = 0
                    }
                } else {
                    outsideCount = 0
                    state = CourseAdherence.ON_COURSE
                }
            }
            CourseAdherence.OFF_COURSE -> {
                if (crossTrackDistanceMeters < recoveryThreshold) {
                    insideCount = 1
                    state = CourseAdherence.REJOINING
                }
            }
            CourseAdherence.REJOINING -> {
                if (crossTrackDistanceMeters < recoveryThreshold) {
                    insideCount++
                    if (insideCount >= confirmationsToReturn) {
                        state = CourseAdherence.ON_COURSE
                        outsideCount = 0
                        insideCount = 0
                    }
                } else if (crossTrackDistanceMeters > threshold) {
                    insideCount = 0
                    state = CourseAdherence.OFF_COURSE
                }
            }
        }
        return AdherenceUpdate(state, previous, threshold)
    }

    fun reset() {
        state = CourseAdherence.ON_COURSE
        outsideCount = 0
        insideCount = 0
    }
}

