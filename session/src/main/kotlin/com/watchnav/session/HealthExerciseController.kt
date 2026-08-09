@file:SuppressLint("RestrictedApi", "WrongConstant")

package com.watchnav.session

import android.annotation.SuppressLint
import android.content.Context
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.endExercise
import androidx.health.services.client.clearUpdateCallback
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.getCurrentExerciseInfo
import androidx.health.services.client.markLap
import androidx.health.services.client.pauseExercise
import androidx.health.services.client.resumeExercise
import androidx.health.services.client.startExercise
import com.watchnav.core.ActivityMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal data class HealthExerciseMetrics(
    val heartRateBpm: Double? = null,
    val averageHeartRateBpm: Double? = null,
    val distanceMeters: Double? = null,
    val calories: Double? = null,
    val lapCount: Int = 0,
    val paused: Boolean = false,
    val error: String? = null,
)

internal class HealthExerciseController(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val exerciseClient = HealthServices.getClient(context.applicationContext).exerciseClient

    @Volatile
    var metrics = HealthExerciseMetrics()
        private set

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val latest = update.latestMetrics
            metrics = metrics.copy(
                heartRateBpm = latest.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
                    ?: metrics.heartRateBpm,
                averageHeartRateBpm = latest.getData(DataType.HEART_RATE_BPM_STATS)?.average
                    ?: metrics.averageHeartRateBpm,
                distanceMeters = latest.getData(DataType.DISTANCE_TOTAL)?.total
                    ?: metrics.distanceMeters,
                calories = latest.getData(DataType.CALORIES_TOTAL)?.total
                    ?: metrics.calories,
                paused = update.exerciseStateInfo.state == ExerciseState.USER_PAUSED ||
                    update.exerciseStateInfo.state == ExerciseState.AUTO_PAUSED,
            )
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {
            metrics = metrics.copy(lapCount = metrics.lapCount + 1)
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) = Unit

        override fun onRegistered() = Unit

        override fun onRegistrationFailed(throwable: Throwable) {
            metrics = metrics.copy(error = throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    fun start(mode: ActivityMode, gpsEnabled: Boolean) {
        exerciseClient.setUpdateCallback(callback)
        scope.launch {
            runCatching {
                if (exerciseClient.getCurrentExerciseInfo().exerciseTrackedStatus ==
                    ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS
                ) return@runCatching
                val exerciseType = when (mode) {
                    ActivityMode.HIKE -> ExerciseType.HIKING
                    ActivityMode.RUN -> ExerciseType.RUNNING
                    ActivityMode.BIKE -> ExerciseType.BIKING
                }
                val typeCapabilities = exerciseClient.getCapabilities()
                    .getExerciseTypeCapabilities(exerciseType)
                val requestedData = setOf(
                    DataType.HEART_RATE_BPM,
                    DataType.HEART_RATE_BPM_STATS,
                    DataType.DISTANCE_TOTAL,
                    DataType.CALORIES_TOTAL,
                ).intersect(typeCapabilities.supportedDataTypes)
                exerciseClient.startExercise(
                    ExerciseConfig(
                        exerciseType = exerciseType,
                        dataTypes = requestedData,
                        isAutoPauseAndResumeEnabled = typeCapabilities.supportsAutoPauseAndResume,
                        isGpsEnabled = gpsEnabled,
                    ),
                )
            }.onFailure { error ->
                metrics = metrics.copy(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun markLap() = launchCommand { exerciseClient.markLap() }

    fun pause() = launchCommand { exerciseClient.pauseExercise() }

    fun resume() = launchCommand { exerciseClient.resumeExercise() }

    fun end() {
        scope.launch {
            runCatching {
                if (exerciseClient.getCurrentExerciseInfo().exerciseTrackedStatus ==
                    ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS
                ) exerciseClient.endExercise()
            }
            runCatching { exerciseClient.clearUpdateCallback(callback) }
            scope.cancel()
        }
    }

    fun detach() {
        scope.launch {
            runCatching { exerciseClient.clearUpdateCallback(callback) }
            scope.cancel()
        }
    }

    private fun launchCommand(command: suspend () -> Unit) {
        scope.launch {
            runCatching { command() }.onFailure { error ->
                metrics = metrics.copy(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }
}
