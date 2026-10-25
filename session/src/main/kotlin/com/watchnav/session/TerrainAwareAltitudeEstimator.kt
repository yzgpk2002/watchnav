package com.watchnav.session

import kotlin.math.sqrt

/**
 * Lightweight error-state altitude fusion for Wear OS.
 *
 * The state is altitude plus the pressure-altimeter bias. GNSS provides an absolute but noisy
 * reference, the pressure sensor supplies short-term continuity, and GPX/DEM elevation is an
 * optional terrain observation whose covariance is adapted from local terrain roughness.
 */
internal class TerrainAwareAltitudeEstimator {
    data class Estimate(
        val altitudeMeters: Double,
        val uncertaintyMeters: Double,
        val terrainWeight: Double,
    )

    private var pressureBiasMeters: Double? = null
    private var estimateMeters: Double? = null
    private var varianceMetersSquared = INITIAL_VARIANCE_METERS_SQUARED

    fun reset() {
        pressureBiasMeters = null
        estimateMeters = null
        varianceMetersSquared = INITIAL_VARIANCE_METERS_SQUARED
    }

    fun update(
        pressureAltitudeMeters: Double?,
        gnssAltitudeMeters: Double?,
        terrainAltitudeMeters: Double?,
        terrainRoughnessMeters: Double?,
        gnssVerticalAccuracyMeters: Double? = null,
    ): Estimate? {
        val validatedTerrainAltitude = terrainAltitudeMeters?.takeIf { terrain ->
            gnssAltitudeMeters == null ||
                kotlin.math.abs(terrain - gnssAltitudeMeters) <= MAXIMUM_TERRAIN_REFERENCE_DELTA_METERS
        }
        val terrainSigma = terrainSigmaMeters(terrainRoughnessMeters)
        val gnssSigma = (gnssVerticalAccuracyMeters ?: DEFAULT_GNSS_SIGMA_METERS)
            .coerceIn(MINIMUM_GNSS_SIGMA_METERS, MAXIMUM_GNSS_SIGMA_METERS)

        val absoluteReference = weightedMean(
            gnssAltitudeMeters?.let { Observation(it, gnssSigma) },
            validatedTerrainAltitude?.let { Observation(it, terrainSigma) },
        )
        if (pressureAltitudeMeters != null && absoluteReference != null) {
            val measuredBias = absoluteReference.value - pressureAltitudeMeters
            pressureBiasMeters = pressureBiasMeters?.let { previous ->
                previous + (measuredBias - previous).coerceIn(-MAXIMUM_BIAS_STEP_METERS, MAXIMUM_BIAS_STEP_METERS) *
                    BIAS_ADAPTATION_RATE
            } ?: measuredBias
        }

        val observations = buildList {
            pressureAltitudeMeters?.let { pressure ->
                pressureBiasMeters?.let { bias -> add(Observation(pressure + bias, PRESSURE_SIGMA_METERS)) }
            }
            gnssAltitudeMeters?.let { add(Observation(it, gnssSigma)) }
            validatedTerrainAltitude?.let { add(Observation(it, terrainSigma)) }
        }
        if (observations.isEmpty()) return estimateMeters?.let {
            Estimate(it, sqrt(varianceMetersSquared), 0.0)
        }

        // Error-state prediction: allow slow atmospheric drift without permitting abrupt jumps.
        varianceMetersSquared = (varianceMetersSquared + PROCESS_VARIANCE_METERS_SQUARED)
            .coerceAtMost(MAXIMUM_VARIANCE_METERS_SQUARED)
        var state = estimateMeters ?: weightedMean(*observations.toTypedArray())!!.value
        var variance = varianceMetersSquared
        observations.forEach { observation ->
            val measurementVariance = observation.sigmaMeters * observation.sigmaMeters
            val innovation = (observation.value - state).coerceIn(
                -MAXIMUM_INNOVATION_METERS,
                MAXIMUM_INNOVATION_METERS,
            )
            val gain = variance / (variance + measurementVariance)
            state += gain * innovation
            variance = (1.0 - gain) * variance
        }
        estimateMeters = state
        varianceMetersSquared = variance.coerceAtLeast(MINIMUM_VARIANCE_METERS_SQUARED)

        val terrainWeight = if (validatedTerrainAltitude == null) 0.0 else {
            val terrainPrecision = 1.0 / (terrainSigma * terrainSigma)
            val totalPrecision = observations.sumOf { 1.0 / (it.sigmaMeters * it.sigmaMeters) }
            (terrainPrecision / totalPrecision).coerceIn(0.0, 1.0)
        }
        return Estimate(state, sqrt(varianceMetersSquared), terrainWeight)
    }

    private fun terrainSigmaMeters(roughnessMeters: Double?): Double {
        val roughness = roughnessMeters ?: return DEFAULT_TERRAIN_SIGMA_METERS
        // A flat DEM has little discriminating power, so its R is deliberately increased.
        if (roughness < FLAT_TERRAIN_THRESHOLD_METERS) return FLAT_TERRAIN_SIGMA_METERS
        return (TERRAIN_SIGMA_SCALE / sqrt(roughness))
            .coerceIn(MINIMUM_TERRAIN_SIGMA_METERS, DEFAULT_TERRAIN_SIGMA_METERS)
    }

    private data class Observation(val value: Double, val sigmaMeters: Double)

    private fun weightedMean(vararg observations: Observation?): Observation? {
        val available = observations.filterNotNull()
        if (available.isEmpty()) return null
        val precision = available.sumOf { 1.0 / (it.sigmaMeters * it.sigmaMeters) }
        return Observation(
            value = available.sumOf { it.value / (it.sigmaMeters * it.sigmaMeters) } / precision,
            sigmaMeters = sqrt(1.0 / precision),
        )
    }

    private companion object {
        const val DEFAULT_GNSS_SIGMA_METERS = 18.0
        const val MINIMUM_GNSS_SIGMA_METERS = 5.0
        const val MAXIMUM_GNSS_SIGMA_METERS = 80.0
        const val PRESSURE_SIGMA_METERS = 2.5
        const val DEFAULT_TERRAIN_SIGMA_METERS = 20.0
        const val FLAT_TERRAIN_SIGMA_METERS = 45.0
        const val MINIMUM_TERRAIN_SIGMA_METERS = 5.0
        const val FLAT_TERRAIN_THRESHOLD_METERS = 3.0
        const val TERRAIN_SIGMA_SCALE = 40.0
        const val BIAS_ADAPTATION_RATE = 0.04
        const val MAXIMUM_BIAS_STEP_METERS = 8.0
        const val MAXIMUM_INNOVATION_METERS = 40.0
        const val MAXIMUM_TERRAIN_REFERENCE_DELTA_METERS = 150.0
        const val INITIAL_VARIANCE_METERS_SQUARED = 400.0
        const val PROCESS_VARIANCE_METERS_SQUARED = 1.0
        const val MAXIMUM_VARIANCE_METERS_SQUARED = 900.0
        const val MINIMUM_VARIANCE_METERS_SQUARED = 0.25
    }
}
