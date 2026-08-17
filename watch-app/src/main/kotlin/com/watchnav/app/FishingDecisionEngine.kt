package com.watchnav.app

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

enum class FishingConfidence { LOW, MEDIUM, HIGH }

data class FishingDecision(
    val index: Int,
    val confidence: FishingConfidence,
    val theoreticalOxygenSaturationMgL: Double?,
    val seaLevelPressureHpa: Double?,
    val advice: FishingAdvice,
)

enum class FishingAdvice { STORM_RETURN, SLACK_WINDOW, PRESSURE_FALLING, PRESSURE_RISING, NORMAL }

object FishingDecisionEngine {
    fun calculate(
        solunarLevel: Int,
        ambientPressureHpa: Float?,
        pressureDelta3hHpa: Float?,
        elevationMeters: Double?,
        tideStage: TideStage?,
        waterTemperatureCelsius: Float?,
        salinityPpt: Float?,
    ): FishingDecision {
        val components = mutableListOf<Pair<Double, Double>>()
        components += .35 to when (solunarLevel.coerceIn(1, 4)) {
            4 -> 95.0
            3 -> 75.0
            2 -> 55.0
            else -> 30.0
        }
        pressureDelta3hHpa?.let { delta ->
            components += .25 to when {
                delta < -4f -> 8.0
                delta < -2f -> 30.0
                delta < -.5f -> 52.0
                delta > 1.5f -> 88.0
                delta > .5f -> 80.0
                else -> 68.0
            }
        }
        val seaLevelPressure = if (ambientPressureHpa != null && elevationMeters != null) {
            seaLevelPressure(ambientPressureHpa.toDouble(), elevationMeters)
        } else ambientPressureHpa?.toDouble()
        seaLevelPressure?.let { pressure ->
            components += .10 to when {
                pressure in 1_008.0..1_022.0 -> 80.0
                pressure in 1_000.0..1_030.0 -> 66.0
                else -> 45.0
            }
        }
        tideStage?.let { stage ->
            components += .20 to when (stage) {
                TideStage.SLACK_REFERENCE -> 96.0
                TideStage.FLOOD -> 76.0
                TideStage.EBB -> 58.0
            }
        }
        val oxygen = if (waterTemperatureCelsius != null && ambientPressureHpa != null) {
            theoreticalOxygenSaturation(
                waterTemperatureCelsius.toDouble(),
                ambientPressureHpa.toDouble(),
                salinityPpt?.toDouble() ?: 0.0,
            )
        } else null
        oxygen?.let { saturation ->
            components += .10 to when {
                saturation >= 9.0 -> 86.0
                saturation >= 7.5 -> 72.0
                saturation >= 6.0 -> 52.0
                else -> 28.0
            }
        }
        val weightedTotal = components.sumOf { (weight, score) -> weight * score }
        val availableWeight = components.sumOf { it.first }
        val roundedIndex = ((weightedTotal / availableWeight) / 5.0).roundToInt() * 5
        val confidence = when {
            availableWeight >= .95 && oxygen != null -> FishingConfidence.HIGH
            availableWeight >= .70 -> FishingConfidence.MEDIUM
            else -> FishingConfidence.LOW
        }
        val advice = when {
            pressureDelta3hHpa != null && pressureDelta3hHpa < -4f -> FishingAdvice.STORM_RETURN
            tideStage == TideStage.SLACK_REFERENCE -> FishingAdvice.SLACK_WINDOW
            pressureDelta3hHpa != null && pressureDelta3hHpa < -2f -> FishingAdvice.PRESSURE_FALLING
            pressureDelta3hHpa != null && pressureDelta3hHpa > .5f -> FishingAdvice.PRESSURE_RISING
            else -> FishingAdvice.NORMAL
        }
        return FishingDecision(roundedIndex.coerceIn(0, 100), confidence, oxygen, seaLevelPressure, advice)
    }

    /** Hypsometric approximation; used only to compare pressure across elevations. */
    fun seaLevelPressure(ambientPressureHpa: Double, elevationMeters: Double): Double {
        val base = 1.0 - 2.25577E-5 * elevationMeters.coerceIn(-500.0, 9_000.0)
        return ambientPressureHpa / base.pow(5.25588)
    }

    /** Theoretical equilibrium saturation, not measured DO. Valid for 0–40 °C. */
    fun theoreticalOxygenSaturation(
        temperatureCelsius: Double,
        ambientPressureHpa: Double,
        salinityPpt: Double,
    ): Double {
        val temperature = temperatureCelsius.coerceIn(0.0, 40.0)
        val kelvin = temperature + 273.15
        val freshwaterMgL = exp(
            -139.34411 + 157_570.1 / kelvin - 66_423_080.0 / kelvin.pow(2) +
                12_438_000_000.0 / kelvin.pow(3) - 862_194_900_000.0 / kelvin.pow(4),
        )
        val pressureCorrection = ambientPressureHpa.coerceIn(507.0, 1_114.0) / 1_013.25
        val salinityCorrection = exp(-0.0055 * salinityPpt.coerceIn(0.0, 40.0))
        return freshwaterMgL * pressureCorrection * salinityCorrection
    }
}
