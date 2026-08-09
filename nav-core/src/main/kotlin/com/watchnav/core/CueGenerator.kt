package com.watchnav.core

import kotlin.math.abs

object CueGenerator {
    fun generate(
        preparedCourse: PreparedCourse,
        minimumTurnDegrees: Double = 35.0,
        minimumSpacingMeters: Double = 25.0,
    ): List<NavigationCue> {
        val course = preparedCourse.course
        val cues = ArrayList<NavigationCue>()
        cues += NavigationCue(0, 0.0, ManeuverKind.START, "Start")
        var lastCueDistance = 0.0
        for (index in 1 until course.points.lastIndex) {
            if (index in course.breakBeforePointIndices || index + 1 in course.breakBeforePointIndices) continue
            val incoming = GeoMath.initialBearingDegrees(
                course.points[index - 1].position,
                course.points[index].position,
            )
            val outgoing = GeoMath.initialBearingDegrees(
                course.points[index].position,
                course.points[index + 1].position,
            )
            val turn = GeoMath.signedTurnDegrees(incoming, outgoing)
            val distance = preparedCourse.cumulativeDistanceMeters[index]
            if (abs(turn) < minimumTurnDegrees || distance - lastCueDistance < minimumSpacingMeters) continue
            cues += NavigationCue(index, distance, classify(turn))
            lastCueDistance = distance
        }
        cues += NavigationCue(
            course.points.lastIndex,
            preparedCourse.totalDistanceMeters,
            ManeuverKind.FINISH,
            "Finish",
        )
        return cues
    }

    private fun classify(turnDegrees: Double): ManeuverKind = when {
        abs(turnDegrees) >= 150 -> ManeuverKind.U_TURN
        turnDegrees <= -100 -> ManeuverKind.SHARP_LEFT
        turnDegrees <= -50 -> ManeuverKind.LEFT
        turnDegrees < 0 -> ManeuverKind.SLIGHT_LEFT
        turnDegrees >= 100 -> ManeuverKind.SHARP_RIGHT
        turnDegrees >= 50 -> ManeuverKind.RIGHT
        else -> ManeuverKind.SLIGHT_RIGHT
    }
}

