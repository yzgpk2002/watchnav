package com.watchnav.session

import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainAwareAltitudeEstimatorTest {
    @Test
    fun pressureContinuitySuppressesGnssAltitudeJump() {
        val estimator = TerrainAwareAltitudeEstimator()
        estimator.update(pressureAltitudeMeters = 80.0, gnssAltitudeMeters = 100.0,
            terrainAltitudeMeters = 102.0, terrainRoughnessMeters = 15.0)
        val updated = estimator.update(pressureAltitudeMeters = 81.0, gnssAltitudeMeters = 145.0,
            terrainAltitudeMeters = 103.0, terrainRoughnessMeters = 15.0)

        assertTrue(requireNotNull(updated).altitudeMeters < 115.0)
        assertTrue(updated.altitudeMeters > 99.0)
    }

    @Test
    fun flatTerrainGetsLessWeightThanDistinctiveTerrain() {
        val flat = TerrainAwareAltitudeEstimator().update(
            pressureAltitudeMeters = null,
            gnssAltitudeMeters = 100.0,
            terrainAltitudeMeters = 110.0,
            terrainRoughnessMeters = 0.5,
        )
        val rough = TerrainAwareAltitudeEstimator().update(
            pressureAltitudeMeters = null,
            gnssAltitudeMeters = 100.0,
            terrainAltitudeMeters = 110.0,
            terrainRoughnessMeters = 25.0,
        )

        assertTrue(requireNotNull(flat).terrainWeight < requireNotNull(rough).terrainWeight)
        assertTrue(flat.altitudeMeters < rough.altitudeMeters)
    }

    @Test
    fun implausibleGpxElevationIsRejected() {
        val estimate = TerrainAwareAltitudeEstimator().update(
            pressureAltitudeMeters = null,
            gnssAltitudeMeters = 100.0,
            terrainAltitudeMeters = 2_000.0,
            terrainRoughnessMeters = 30.0,
        )

        assertTrue(requireNotNull(estimate).altitudeMeters < 120.0)
        assertTrue(estimate.terrainWeight == 0.0)
    }
}
