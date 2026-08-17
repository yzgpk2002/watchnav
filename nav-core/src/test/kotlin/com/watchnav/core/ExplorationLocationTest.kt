package com.watchnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ExplorationLocationTest {
    private val now = 1_000_000L

    @Test
    fun `fresh direct gps wins over stale navigation telemetry`() {
        val staleSession = fix(30.0, 120.0, now - 10 * 60_000L)
        val currentGps = fix(30.1, 120.1, now - 1_000L)

        assertEquals(
            currentGps,
            ExplorationLocation.select(staleSession, currentGps, null, now),
        )
    }

    @Test
    fun `newest fresh live fix wins`() {
        val session = fix(30.0, 120.0, now - 500L)
        val direct = fix(30.1, 120.1, now - 2_000L)

        assertEquals(session, ExplorationLocation.select(session, direct, null, now))
    }

    @Test
    fun `persisted fix is only a no-signal fallback`() {
        val staleSession = fix(30.0, 120.0, now - 20 * 60_000L)
        val persisted = fix(31.0, 121.0, now - 30 * 60_000L)

        assertEquals(persisted, ExplorationLocation.select(staleSession, null, persisted, now))
    }

    private fun fix(latitude: Double, longitude: Double, timestamp: Long) = LocationFix(
        position = GeoPoint(latitude, longitude),
        accuracyMeters = 5.0,
        timestampMillis = timestamp,
    )
}
