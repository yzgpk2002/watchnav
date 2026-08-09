package com.watchnav.session

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

internal class OutdoorSensorFusion(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var pressureAltitudeMeters: Double? = null
    private var altitudeOffsetMeters = 0.0
    private var hasAltitudeCalibration = false
    private var fusedAltitudeMeters: Double? = null
    private var previousAltitudeMeters: Double? = null
    private var previousAltitudeTimeNanos: Long? = null

    var compassBearingDegrees: Double? = null
        private set
    var verticalSpeedMetersPerHour: Double? = null
        private set

    fun start() {
        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun updateGnssAltitude(altitudeMeters: Double?): Double? {
        if (altitudeMeters != null && pressureAltitudeMeters != null) {
            val measuredOffset = altitudeMeters - requireNotNull(pressureAltitudeMeters)
            altitudeOffsetMeters = if (hasAltitudeCalibration) {
                altitudeOffsetMeters * 0.98 + measuredOffset * 0.02
            } else {
                measuredOffset
            }
            hasAltitudeCalibration = true
        }
        fusedAltitudeMeters = pressureAltitudeMeters?.plus(altitudeOffsetMeters) ?: altitudeMeters
        return fusedAltitudeMeters
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val altitude = SensorManager.getAltitude(
                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                    event.values[0],
                ).toDouble()
                pressureAltitudeMeters = altitude
                val fused = altitude + altitudeOffsetMeters
                updateVerticalSpeed(fused, event.timestamp)
                fusedAltitudeMeters = fused
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotation = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                compassBearingDegrees = Math.toDegrees(orientation[0].toDouble())
                    .let { (it + 360.0) % 360.0 }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateVerticalSpeed(altitudeMeters: Double, timestampNanos: Long) {
        val previousAltitude = previousAltitudeMeters
        val previousTime = previousAltitudeTimeNanos
        if (previousAltitude != null && previousTime != null && timestampNanos > previousTime) {
            val elapsedHours = (timestampNanos - previousTime) / 3_600_000_000_000.0
            val instantaneous = (altitudeMeters - previousAltitude) / elapsedHours
            if (abs(instantaneous) < MAXIMUM_VERTICAL_SPEED_METERS_PER_HOUR) {
                verticalSpeedMetersPerHour = verticalSpeedMetersPerHour
                    ?.let { it * 0.85 + instantaneous * 0.15 }
                    ?: instantaneous
            }
        }
        previousAltitudeMeters = altitudeMeters
        previousAltitudeTimeNanos = timestampNanos
    }

    private companion object {
        const val MAXIMUM_VERTICAL_SPEED_METERS_PER_HOUR = 10_000.0
    }
}
