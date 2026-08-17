package com.watchnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingMathTest {
    @Test
    fun `pressure delta uses a complete three hour baseline`() {
        val now = 20_000_000L
        val samples = listOf(
            FishingPressureSample(now - 3 * 60 * 60 * 1_000L, 1016.2f),
            FishingPressureSample(now - 60 * 60 * 1_000L, 1013.1f),
            FishingPressureSample(now, 1011.7f),
        )

        assertEquals(-4.5f, FishingMath.pressureDelta(samples, now)!!, 0.01f)
    }

    @Test
    fun `activity level always fits the four step display`() {
        for (hour in 0..23) {
            for (longitude in listOf(-170.0, 0.0, 120.0)) {
                val result = FishingMath.solunar(1_800_000_000_000L + hour * 3_600_000L, longitude)
                assertTrue(result.activityLevel in 1..4)
                assertTrue(result.lunarHourAngleHours in -12.0..12.0)
            }
        }
    }

    @Test
    fun `tide extreme creates a fifteen minute golden window`() {
        val now = 1_800_000_000_000L
        val forecast = TideForecast(
            latitude = 30.0,
            longitude = 120.0,
            stationName = "test",
            points = listOf(TidePoint(now - 600_000L, 1f), TidePoint(now + 600_000L, 1.1f)),
            extremes = listOf(TideExtreme(now + 14 * 60_000L, 1.2f, high = true)),
            fetchedAtMillis = now,
        )

        assertEquals(TideStage.SLACK_REFERENCE, forecast.stage(now))
    }

    @Test
    fun `impossible pressure is treated as barometer drift`() {
        val now = 20_000_000L
        assertTrue(FishingMath.barometerDriftSuspected(listOf(FishingPressureSample(now, 700f)), now))
    }

    @Test
    fun `oxygen saturation responds to temperature pressure and salinity`() {
        val fresh = FishingDecisionEngine.theoreticalOxygenSaturation(20.0, 1_013.25, 0.0)
        val salt = FishingDecisionEngine.theoreticalOxygenSaturation(20.0, 1_013.25, 35.0)
        val lowPressure = FishingDecisionEngine.theoreticalOxygenSaturation(20.0, 900.0, 0.0)

        assertEquals(9.09, fresh, 0.15)
        assertTrue(salt < fresh)
        assertTrue(lowPressure < fresh)
    }

    @Test
    fun `complete sensor fusion produces a rounded high confidence index`() {
        val decision = FishingDecisionEngine.calculate(
            solunarLevel = 4,
            ambientPressureHpa = 1_013f,
            pressureDelta3hHpa = .8f,
            elevationMeters = 12.0,
            tideStage = TideStage.SLACK_REFERENCE,
            waterTemperatureCelsius = 20f,
            salinityPpt = 35f,
        )

        assertEquals(FishingConfidence.HIGH, decision.confidence)
        assertEquals(0, decision.index % 5)
        assertTrue(decision.index >= 85)
    }
}
