package com.watchnav.session

import com.watchnav.core.ManeuverKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnDisplayPolicyTest {
    @Test
    fun showsStraightUntilTurnIsNear() {
        assertEquals(ManeuverKind.START, TurnDisplayPolicy.resolve(null, ManeuverKind.LEFT, 121.0))
        assertEquals(ManeuverKind.LEFT, TurnDisplayPolicy.resolve(null, ManeuverKind.LEFT, 120.0))
    }

    @Test
    fun offRouteApproachAlwaysWins() {
        assertEquals(ManeuverKind.RIGHT, TurnDisplayPolicy.resolve(ManeuverKind.RIGHT, ManeuverKind.LEFT, 20.0))
    }
}
