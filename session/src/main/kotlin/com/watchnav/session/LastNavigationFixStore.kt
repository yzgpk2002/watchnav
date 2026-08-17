package com.watchnav.session

import android.content.Context
import com.watchnav.core.GeoPoint
import com.watchnav.core.LocationFix

/** Persists the latest usable position so navigation can render before GNSS reacquires. */
class LastNavigationFixStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun save(fix: LocationFix) {
        preferences.edit()
            .putString(KEY_LATITUDE, fix.position.latitude.toString())
            .putString(KEY_LONGITUDE, fix.position.longitude.toString())
            .putString(KEY_ELEVATION, fix.position.elevationMeters?.toString())
            .putString(KEY_ACCURACY, fix.accuracyMeters.toString())
            .putLong(KEY_TIMESTAMP, fix.timestampMillis)
            .putString(KEY_BEARING, fix.bearingDegrees?.toString())
            .putString(KEY_SPEED, fix.speedMetersPerSecond?.toString())
            .apply()
    }

    fun load(): LocationFix? = runCatching {
        val latitude = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        LocationFix(
            position = GeoPoint(
                latitude,
                longitude,
                preferences.getString(KEY_ELEVATION, null)?.toDoubleOrNull(),
            ),
            accuracyMeters = preferences.getString(KEY_ACCURACY, null)?.toDoubleOrNull()
                ?.coerceAtLeast(1.0) ?: FALLBACK_ACCURACY_METERS,
            timestampMillis = preferences.getLong(KEY_TIMESTAMP, 0L).takeIf { it > 0 }
                ?: System.currentTimeMillis(),
            bearingDegrees = preferences.getString(KEY_BEARING, null)?.toDoubleOrNull(),
            speedMetersPerSecond = preferences.getString(KEY_SPEED, null)?.toDoubleOrNull(),
        )
    }.getOrNull()

    private companion object {
        const val PREFERENCES = "last_navigation_fix"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ELEVATION = "elevation"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_BEARING = "bearing"
        const val KEY_SPEED = "speed"
        const val FALLBACK_ACCURACY_METERS = 250.0
    }
}
