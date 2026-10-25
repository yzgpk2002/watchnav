package com.watchnav.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlin.math.abs

internal class OutdoorSensorFusion(
    context: Context,
    private val onStepCountChanged: ((Long) -> Unit)? = null,
) : SensorEventListener {
    private val applicationContext = context.applicationContext
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val sensorRotationMatrix = FloatArray(9)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
    private var magneticDeclinationDegrees = 0.0
    private var compassAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    @Suppress("DEPRECATION")
    private val displayRotation = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
    private var pressureAltitudeMeters: Double? = null
    private val altitudeEstimator = TerrainAwareAltitudeEstimator()
    private var fusedAltitudeMeters: Double? = null
    private var previousAltitudeMeters: Double? = null
    private var previousAltitudeTimeNanos: Long? = null
    private var lastCompassBearingDegrees: Double? = null
    private var compassStableSinceNanos: Long? = null
    private var initialSystemStepCount: Long? = null

    @Volatile
    var compassBearingDegrees: Double? = null
        private set
    val compassReliable: Boolean
        get() = compassAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
    val compassNeedsCalibration: Boolean
        get() = compassAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
    @Volatile
    var compassStable: Boolean = false
        private set
    @Volatile
    var detectedStepCount: Long = 0L
        private set
    var verticalSpeedMetersPerHour: Double? = null
        private set

    fun start() {
        pressureSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                PRESSURE_SAMPLE_INTERVAL_MICROS,
                PRESSURE_BATCH_LATENCY_MICROS,
            )
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(
                this,
                rotationSensor,
                COMPASS_SAMPLE_INTERVAL_MICROS,
                COMPASS_BATCH_LATENCY_MICROS,
            )
        } else {
            // Raw sensors are only enabled as a fallback to avoid paying for duplicate sensor streams.
            accelerometer?.let {
                sensorManager.registerListener(this, it, COMPASS_SAMPLE_INTERVAL_MICROS, COMPASS_BATCH_LATENCY_MICROS)
            }
            magneticSensor?.let {
                sensorManager.registerListener(this, it, COMPASS_SAMPLE_INTERVAL_MICROS, COMPASS_BATCH_LATENCY_MICROS)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            (stepCounter ?: stepDetector)?.let {
                // Prefer Samsung's cumulative hardware counter. It preserves steps across display
                // sleep; the per-step detector remains the fallback on devices without a counter.
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun updateGnssAltitude(
        altitudeMeters: Double?,
        terrainAltitudeMeters: Double? = null,
        terrainRoughnessMeters: Double? = null,
        verticalAccuracyMeters: Double? = null,
    ): Double? {
        fusedAltitudeMeters = altitudeEstimator.update(
            pressureAltitudeMeters = pressureAltitudeMeters,
            gnssAltitudeMeters = altitudeMeters,
            terrainAltitudeMeters = terrainAltitudeMeters,
            terrainRoughnessMeters = terrainRoughnessMeters,
            gnssVerticalAccuracyMeters = verticalAccuracyMeters,
        )?.altitudeMeters ?: altitudeMeters
        return fusedAltitudeMeters
    }

    fun updateGeomagneticReference(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double?,
        timestampMillis: Long,
    ) {
        magneticDeclinationDegrees = GeomagneticField(
            latitude.toFloat(),
            longitude.toFloat(),
            (altitudeMeters ?: 0.0).toFloat(),
            timestampMillis,
        ).declination.toDouble()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val altitude = SensorManager.getAltitude(
                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                    event.values[0],
                ).toDouble()
                pressureAltitudeMeters = altitude
                val fused = altitudeEstimator.update(
                    pressureAltitudeMeters = altitude,
                    gnssAltitudeMeters = null,
                    terrainAltitudeMeters = null,
                    terrainRoughnessMeters = null,
                )?.altitudeMeters
                if (fused != null) {
                    updateVerticalSpeed(fused, event.timestamp)
                    fusedAltitudeMeters = fused
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                compassAccuracy = event.accuracy
                SensorManager.getRotationMatrixFromVector(sensorRotationMatrix, event.values)
                remapForDisplay()
                updateCompassFromRotationMatrix()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, gravity)
                hasGravity = true
                updateRawTiltCompensatedCompass()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                compassAccuracy = event.accuracy
                lowPass(event.values, geomagnetic)
                hasGeomagnetic = true
                updateRawTiltCompensatedCompass()
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                detectedStepCount++
                onStepCountChanged?.invoke(detectedStepCount)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val systemCount = event.values.firstOrNull()?.toLong() ?: return
                val initial = initialSystemStepCount ?: systemCount.also { initialSystemStepCount = it }
                val updatedCount = (systemCount - initial).coerceAtLeast(0L)
                if (updatedCount != detectedStepCount) {
                    detectedStepCount = updatedCount
                    onStepCountChanged?.invoke(updatedCount)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            compassAccuracy = accuracy
        }
    }

    private fun updateRawTiltCompensatedCompass() {
        if (!hasGravity || !hasGeomagnetic) return
        if (SensorManager.getRotationMatrix(sensorRotationMatrix, null, gravity, geomagnetic)) {
            remapForDisplay()
            updateCompassFromRotationMatrix()
        }
    }

    private fun remapForDisplay() {
        val (xAxis, yAxis) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(sensorRotationMatrix, xAxis, yAxis, rotationMatrix)
    }

    private fun updateCompassFromRotationMatrix() {
        SensorManager.getOrientation(rotationMatrix, orientation)
        val measuredBearing = (Math.toDegrees(orientation[0].toDouble()) +
            magneticDeclinationDegrees + 360.0) % 360.0
        updateCompassStability(measuredBearing, System.nanoTime())
        compassBearingDegrees = measuredBearing
    }

    private fun updateCompassStability(measuredBearing: Double, timestampNanos: Long) {
        val previous = lastCompassBearingDegrees
        lastCompassBearingDegrees = measuredBearing
        if (previous == null || angularDifferenceDegrees(previous, measuredBearing) > COMPASS_STABLE_DELTA_DEGREES) {
            compassStableSinceNanos = timestampNanos
            compassStable = false
            return
        }
        val stableSince = compassStableSinceNanos ?: timestampNanos.also { compassStableSinceNanos = it }
        compassStable = timestampNanos - stableSince >= COMPASS_STABLE_DURATION_NANOS
    }

    private fun lowPass(input: FloatArray, output: FloatArray) {
        for (index in 0..2) output[index] += LOW_PASS_ALPHA * (input[index] - output[index])
    }

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

    private fun angularDifferenceDegrees(first: Double, second: Double): Double =
        abs(((second - first + 540.0) % 360.0) - 180.0)

    private companion object {
        const val MAXIMUM_VERTICAL_SPEED_METERS_PER_HOUR = 10_000.0
        const val LOW_PASS_ALPHA = 0.12f
        const val PRESSURE_SAMPLE_INTERVAL_MICROS = 30_000_000
        const val PRESSURE_BATCH_LATENCY_MICROS = 60_000_000
        const val COMPASS_SAMPLE_INTERVAL_MICROS = 500_000
        const val COMPASS_BATCH_LATENCY_MICROS = 1_000_000
        const val COMPASS_STABLE_DELTA_DEGREES = 10.0
        const val COMPASS_STABLE_DURATION_NANOS = 1_500_000_000L
    }
}
