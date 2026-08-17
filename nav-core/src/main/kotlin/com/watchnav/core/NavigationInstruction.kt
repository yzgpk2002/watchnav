package com.watchnav.core

enum class NavigationDirection { LEFT, STRAIGHT, RIGHT, U_TURN, FINISH }

object NavigationInstruction {
    fun direction(kind: ManeuverKind?): NavigationDirection = when (kind) {
        ManeuverKind.SLIGHT_LEFT, ManeuverKind.LEFT, ManeuverKind.SHARP_LEFT -> NavigationDirection.LEFT
        ManeuverKind.SLIGHT_RIGHT, ManeuverKind.RIGHT, ManeuverKind.SHARP_RIGHT -> NavigationDirection.RIGHT
        ManeuverKind.U_TURN -> NavigationDirection.U_TURN
        ManeuverKind.FINISH -> NavigationDirection.FINISH
        ManeuverKind.START, null -> NavigationDirection.STRAIGHT
    }
}
