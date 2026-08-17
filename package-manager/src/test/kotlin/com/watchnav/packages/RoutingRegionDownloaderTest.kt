package com.watchnav.packages

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingRegionDownloaderTest {
    @Test
    fun `selects every brouter tile intersecting map bounds`() {
        assertEquals(
            setOf("E115_N30", "E120_N30"),
            RoutingRegionDownloader.requiredRegionIds(
                RegionBounds(
                    south = 30.090443,
                    west = 119.885732,
                    north = 30.449767,
                    east = 120.301781,
                ),
            ),
        )
    }

    @Test
    fun `handles negative grid coordinates`() {
        assertEquals(
            setOf("W5_S5", "E0_S5", "W5_N0", "E0_N0"),
            RoutingRegionDownloader.requiredRegionIds(
                RegionBounds(south = -0.2, west = -0.2, north = 0.2, east = 0.2),
            ),
        )
    }
}
