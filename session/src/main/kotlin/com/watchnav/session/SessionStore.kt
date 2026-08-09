package com.watchnav.session

import android.content.Context
import androidx.core.content.edit

data class SessionSnapshot(
    val courseName: String,
    val progressMeters: Double,
    val remainingMeters: Double,
)

internal class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("navigation_session", Context.MODE_PRIVATE)

    fun save(courseName: String, progressMeters: Double, remainingMeters: Double) {
        preferences.edit {
            putBoolean(KEY_ACTIVE, true)
            putString(KEY_COURSE, courseName)
            putLong(KEY_PROGRESS, progressMeters.toBits())
            putLong(KEY_REMAINING, remainingMeters.toBits())
        }
    }

    fun load(): SessionSnapshot? {
        if (!preferences.getBoolean(KEY_ACTIVE, false)) return null
        return SessionSnapshot(
            courseName = preferences.getString(KEY_COURSE, null) ?: return null,
            progressMeters = Double.fromBits(preferences.getLong(KEY_PROGRESS, 0L)),
            remainingMeters = Double.fromBits(preferences.getLong(KEY_REMAINING, 0L)),
        )
    }

    fun clear() = preferences.edit { clear() }

    private companion object {
        const val KEY_ACTIVE = "active"
        const val KEY_COURSE = "course"
        const val KEY_PROGRESS = "progress"
        const val KEY_REMAINING = "remaining"
    }
}
