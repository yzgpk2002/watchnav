package com.watchnav.core

/**
 * Selects the position used by the two-tap free-exploration control.
 *
 * A fresh session fix is preferred, but a fresh activity-owned GNSS fix must win over stale
 * navigation telemetry. This is important after navigation has stopped: the state hub can still
 * contain the last point from the previous session while the user has moved elsewhere.
 */
object ExplorationLocation {
    fun select(
        sessionFix: LocationFix?,
        directGnssFix: LocationFix?,
        persistedFix: LocationFix?,
        nowMillis: Long,
        maximumLiveFixAgeMillis: Long = 2 * 60_000L,
    ): LocationFix? {
        val live = listOfNotNull(sessionFix, directGnssFix)
            .filter { it.timestampMillis in 1..nowMillis && nowMillis - it.timestampMillis <= maximumLiveFixAgeMillis }
            .maxByOrNull { it.timestampMillis }
        return live ?: persistedFix
    }
}
