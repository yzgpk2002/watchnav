package com.watchnav.mobile.data

import com.watchnav.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteTileCoverageTest {
    @Test
    fun `coverage clamps download radius to fifteen kilometres`() {
        val center = GeoPoint(30.274604, 120.141)
        assertEquals(
            SatelliteTileCoverage.bounds(center, 15.0),
            SatelliteTileCoverage.bounds(center, 100.0),
        )
    }

    @Test
    fun `hangzhou package stays bounded and includes every requested zoom`() {
        val values = SatelliteTileCoverage.tiles(GeoPoint(30.274604, 120.141), 15.0, 10, 16)
        assertTrue(values.size in 3_000..6_000)
        assertEquals((10..16).toSet(), values.map { it.zoom }.toSet())
        assertEquals(values.size, values.distinct().size)
    }
}
