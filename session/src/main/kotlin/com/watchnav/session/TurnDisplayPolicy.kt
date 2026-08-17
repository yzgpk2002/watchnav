package com.watchnav.session

import com.watchnav.core.ManeuverKind

/** Keeps the centre arrow active until a real nearby turn needs the user's attention. */
internal object TurnDisplayPolicy {
    fun resolve(
        approachManeuver: ManeuverKind?,
        routeManeuver: ManeuverKind?,
        distanceToRouteManeuverMeters: Double?,
    ): ManeuverKind = approachManeuver
        ?: routeManeuver?.takeIf {
            distanceToRouteManeuverMeters != null &&
                distanceToRouteManeuverMeters <= TURN_PREVIEW_DISTANCE_METERS
        }
        ?: ManeuverKind.START

    private const val TURN_PREVIEW_DISTANCE_METERS = 120.0
}
