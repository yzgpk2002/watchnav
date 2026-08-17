package com.watchnav.session

import com.watchnav.core.GeoMath
import kotlin.math.abs

/** Direction along a known route, independent of the direction the watch face happens to point. */
internal enum class AlongRouteDirection {
    FORWARD,
    REVERSE,
}

internal data class PedestrianDirectionEstimate(
    val direction: AlongRouteDirection,
    val changed: Boolean,
)

/**
 * Resolves the ambiguity that GNSS course-over-ground has at walking speed.
 *
 * A stable compass may choose a provisional direction only while steps prove that the wearer is
 * moving. The final decision comes from accumulated route-projected displacement beyond a dynamic
 * fraction of the GNSS error circle. Hysteresis ensures that one noisy fix cannot rotate the map
 * and its north scale by 180 degrees.
 */
internal class PedestrianRouteDirectionEstimator {
    private var direction = AlongRouteDirection.FORWARD
    private var evidenceScore = INITIAL_FORWARD_EVIDENCE
    private var anchorProgressMeters: Double? = null
    private var anchorStepCount = 0L
    private var compassProvisionalDecisionMade = false

    fun reset() {
        direction = AlongRouteDirection.FORWARD
        evidenceScore = INITIAL_FORWARD_EVIDENCE
        anchorProgressMeters = null
        anchorStepCount = 0L
        compassProvisionalDecisionMade = false
    }

    fun update(
        rawProgressMeters: Double,
        routeForwardBearingDegrees: Double,
        compassBearingDegrees: Double?,
        compassReliable: Boolean,
        compassStable: Boolean,
        detectedStepCount: Long,
        horizontalAccuracyMeters: Double,
    ): PedestrianDirectionEstimate {
        val anchor = anchorProgressMeters
        if (anchor == null) {
            anchorProgressMeters = rawProgressMeters
            anchorStepCount = detectedStepCount
            return PedestrianDirectionEstimate(direction, changed = false)
        }

        val steps = (detectedStepCount - anchorStepCount).coerceAtLeast(0L)
        var changed = false
        if (!compassProvisionalDecisionMade && steps >= MINIMUM_STEPS_FOR_COMPASS_DECISION &&
            compassReliable && compassStable && compassBearingDegrees != null
        ) {
            compassProvisionalDecisionMade = true
            val forwardDifference = GeoMath.angularDifferenceDegrees(
                compassBearingDegrees,
                routeForwardBearingDegrees,
            )
            val reverseDifference = GeoMath.angularDifferenceDegrees(
                compassBearingDegrees,
                opposite(routeForwardBearingDegrees),
            )
            if (minOf(forwardDifference, reverseDifference) <= MAXIMUM_COMPASS_ALIGNMENT_DEGREES &&
                abs(forwardDifference - reverseDifference) >= MINIMUM_COMPASS_MARGIN_DEGREES
            ) {
                val provisional = if (forwardDifference < reverseDifference) {
                    AlongRouteDirection.FORWARD
                } else {
                    AlongRouteDirection.REVERSE
                }
                changed = provisional != direction
                direction = provisional
                evidenceScore = if (provisional == AlongRouteDirection.FORWARD) 1 else -1
            }
        }

        val progressDelta = rawProgressMeters - anchor
        val requiredDisplacement = (horizontalAccuracyMeters * ACCURACY_DISPLACEMENT_FACTOR)
            .coerceIn(MINIMUM_DISPLACEMENT_METERS, MAXIMUM_DISPLACEMENT_METERS)
        if (steps >= MINIMUM_STEPS_FOR_GNSS_EVIDENCE && abs(progressDelta) >= requiredDisplacement) {
            val evidence = if (progressDelta > 0.0) 1 else -1
            val strength = if (abs(progressDelta) >= requiredDisplacement * STRONG_EVIDENCE_FACTOR) 2 else 1
            evidenceScore = (evidenceScore + evidence * strength).coerceIn(-MAXIMUM_EVIDENCE, MAXIMUM_EVIDENCE)
            val resolved = when {
                evidenceScore >= DIRECTION_CHANGE_EVIDENCE -> AlongRouteDirection.FORWARD
                evidenceScore <= -DIRECTION_CHANGE_EVIDENCE -> AlongRouteDirection.REVERSE
                else -> direction
            }
            changed = changed || resolved != direction
            direction = resolved
            anchorProgressMeters = rawProgressMeters
            anchorStepCount = detectedStepCount
        }
        return PedestrianDirectionEstimate(direction, changed)
    }

    private fun opposite(bearingDegrees: Double) = (bearingDegrees + 180.0) % 360.0

    private companion object {
        const val INITIAL_FORWARD_EVIDENCE = 1
        const val MAXIMUM_EVIDENCE = 4
        const val DIRECTION_CHANGE_EVIDENCE = 2
        const val MINIMUM_STEPS_FOR_COMPASS_DECISION = 3L
        const val MINIMUM_STEPS_FOR_GNSS_EVIDENCE = 4L
        const val MINIMUM_DISPLACEMENT_METERS = 8.0
        const val MAXIMUM_DISPLACEMENT_METERS = 20.0
        const val ACCURACY_DISPLACEMENT_FACTOR = 0.65
        const val STRONG_EVIDENCE_FACTOR = 1.75
        const val MAXIMUM_COMPASS_ALIGNMENT_DEGREES = 45.0
        const val MINIMUM_COMPASS_MARGIN_DEGREES = 90.0
    }
}
