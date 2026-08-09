package com.watchnav.packages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapDownloadRegionTest {
    @Test
    fun `bounds remain centered on requested location`() {
        val region = MapDownloadRegion(30.2741, 120.1551, 20.0)
        val bounds = region.bounds

        assertEquals(30.2741, (bounds.south + bounds.north) / 2.0, 0.0001)
        assertEquals(120.1551, (bounds.west + bounds.east) / 2.0, 0.0001)
        assertTrue(bounds.north - bounds.south > 0.3)
        assertTrue(bounds.east - bounds.west > bounds.north - bounds.south)
    }

    @Test
    fun `pinch scaling is clamped to safe extract sizes`() {
        val region = MapDownloadRegion(0.0, 0.0, 20.0)

        assertEquals(200.0, region.resized(20f).radiusKilometers)
        assertEquals(2.0, region.resized(0.01f).radiusKilometers)
    }

    @Test
    fun `selection near date line remains a valid extract box`() {
        val bounds = MapDownloadRegion(10.0, 179.8, 200.0).bounds

        assertTrue(bounds.west < bounds.east)
        assertEquals(180.0, bounds.east)
    }
}
