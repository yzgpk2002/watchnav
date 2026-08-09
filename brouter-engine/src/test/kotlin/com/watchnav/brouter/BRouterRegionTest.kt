package com.watchnav.brouter

import com.watchnav.core.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class BRouterRegionTest {
    @Test
    fun resolvesFiveDegreeRegionIds() {
        assertEquals("E120_N30", BRouterOfflineRouter.regionId(GeoPoint(31.2, 121.5)))
        assertEquals("W5_S5", BRouterOfflineRouter.regionId(GeoPoint(-0.1, -0.1)))
        assertEquals("E0_N0", BRouterOfflineRouter.regionId(GeoPoint(0.0, 0.0)))
    }
}
