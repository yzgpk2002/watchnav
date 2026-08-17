package com.watchnav.app

import kotlin.math.sqrt

/**
 * Conservative two-phase cast detector for TYPE_LINEAR_ACCELERATION.
 * A strong wrist acceleration must be followed by a release within the time window;
 * the cooldown prevents a single cast/retrieve motion from being counted repeatedly.
 */
class FishingCastDetector(
    private val peakThresholdMetersPerSecondSquared: Float = 8.5f,
    private val releaseThresholdMetersPerSecondSquared: Float = 3.2f,
    private val releaseWindowMillis: Long = 1_100L,
    private val cooldownMillis: Long = 1_800L,
) {
    private var peakAtMillis = 0L
    private var lastCastAtMillis = Long.MIN_VALUE

    fun addSample(timestampMillis: Long, x: Float, y: Float, z: Float): Boolean {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (lastCastAtMillis != Long.MIN_VALUE && timestampMillis - lastCastAtMillis < cooldownMillis) return false
        if (magnitude >= peakThresholdMetersPerSecondSquared) {
            peakAtMillis = timestampMillis
            return false
        }
        val elapsed = timestampMillis - peakAtMillis
        if (peakAtMillis > 0L && elapsed in 80L..releaseWindowMillis && magnitude <= releaseThresholdMetersPerSecondSquared) {
            lastCastAtMillis = timestampMillis
            peakAtMillis = 0L
            return true
        }
        if (peakAtMillis > 0L && elapsed > releaseWindowMillis) peakAtMillis = 0L
        return false
    }

    fun reset() {
        peakAtMillis = 0L
        lastCastAtMillis = Long.MIN_VALUE
    }
}
