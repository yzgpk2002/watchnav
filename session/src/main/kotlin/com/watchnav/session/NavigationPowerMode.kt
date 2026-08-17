package com.watchnav.session

enum class NavigationPowerMode(
    val locationIntervalMillis: Long,
    val minimumDistanceMeters: Float,
    val healthServicesGpsEnabled: Boolean,
) {
    // A 2 s GNSS loop keeps the watch radio awake almost continuously and caused
    // substantial heat during walking-speed navigation. Four seconds still gives
    // timely turn correction while the compass supplies the between-fix heading.
    ACCURATE(6_000L, 6f, false),
    BALANCED(15_000L, 10f, false),
    EXPEDITION(60_000L, 20f, false),
}
