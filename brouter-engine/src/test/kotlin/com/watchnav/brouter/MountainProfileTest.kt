package com.watchnav.brouter

import com.watchnav.routing.RoutingProfile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class MountainProfileTest {
    @Test
    fun hikingProfileEnablesTerrainCostAndRoutePreference() {
        val configured = configureProfile(
            """
            assign   consider_elevation     = false
            assign   hiking_routes_preference 0.20
            """.trimIndent(),
            RoutingProfile.HIKE,
        )

        assertContains(configured, "consider_elevation     = true")
        assertContains(configured, "hiking_routes_preference 0.35")
        assertFalse(configured.contains("consider_elevation     = false"))
    }
}
