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

    fun update(
        crossTrackDistanceMeters: Double,
        horizontalAccuracyMeters: Double,
        innovationSquared: Double = 0.0,
        forkAmbiguous: Boolean = false,
    ): AdherenceUpdate {
        val previous = state
        val threshold = max(mode.defaultOffCourseMeters, horizontalAccuracyMeters * 2)
        val recoveryThreshold = threshold * 0.65
        val reliable = horizontalAccuracyMeters <= 100.0
        if (!reliable) return AdherenceUpdate(state, previous, threshold)

        // A distance threshold alone reacts late at forks. ForkTracker also tests the normalised
        // innovation against the 99% chi-square boundary (2 DoF), while still requiring a
        // meaningful physical separation so one noisy GNSS fix cannot trigger an alarm.
        val innovationRejected = innovationSquared > CHI_SQUARE_99_TWO_DOF &&
            crossTrackDistanceMeters > threshold * INNOVATION_DISTANCE_RATIO
        val outside = crossTrackDistanceMeters > threshold || innovationRejected
        val leaveConfirmations = if (forkAmbiguous) FORK_CONFIRMATIONS_TO_LEAVE else confirmationsToLeave

        when (state) {
            CourseAdherence.ON_COURSE -> {
                if (outside) {
                    outsideCount = 1
                    state = CourseAdherence.SUSPECTED_OFF_COURSE
                }
            }
            CourseAdherence.SUSPECTED_OFF_COURSE -> {
                if (outside) {
                    outsideCount++
                    if (outsideCount >= leaveConfirmations) {
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
                } else if (outside) {
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

    private companion object {
        const val CHI_SQUARE_99_TWO_DOF = 9.21034
        const val INNOVATION_DISTANCE_RATIO = 0.70
        const val FORK_CONFIRMATIONS_TO_LEAVE = 2
    }
}

