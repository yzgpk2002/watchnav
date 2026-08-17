package com.watchnav.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FishingDashboardMathTest {
    @Test
    fun `cast detector requires peak and release and applies cooldown`() {
        val detector = FishingCastDetector()
        assertFalse(detector.addSample(1_000L, 9f, 0f, 0f))
        assertTrue(detector.addSample(1_300L, 1f, 1f, 0f))
        assertFalse(detector.addSample(1_600L, 10f, 0f, 0f))
        assertFalse(detector.addSample(1_900L, 1f, 0f, 0f))
        assertFalse(detector.addSample(3_200L, 9f, 0f, 0f))
        assertTrue(detector.addSample(3_450L, 1f, 0f, 0f))
    }

    @Test
    fun `sun window returns plausible Beijing summer times`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val noon = Instant.parse("2024-06-21T04:00:00Z").toEpochMilli()
        val window = FishingSolarMath.window(noon, 39.9042, 116.4074, zone)
        val sunrise = assertNotNull(window.sunriseMillis).let { window.sunriseMillis!! }
        val sunset = assertNotNull(window.sunsetMillis).let { window.sunsetMillis!! }
        val sunriseHour = Instant.ofEpochMilli(sunrise).atZone(zone).hour
        val sunsetHour = Instant.ofEpochMilli(sunset).atZone(zone).hour
        assertTrue("sunrise hour was $sunriseHour", sunriseHour in 4..5)
        assertTrue("sunset hour was $sunsetHour", sunsetHour in 19..20)
        assertTrue(sunset > sunrise)
    }
}
