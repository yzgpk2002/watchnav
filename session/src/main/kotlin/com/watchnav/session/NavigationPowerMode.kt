package com.watchnav.session

enum class NavigationPowerMode(
    val locationIntervalMillis: Long,
    val minimumDistanceMeters: Float,
    val healthServicesGpsEnabled: Boolean,
) {
    ACCURATE(1_000L, 2f, true),
    BALANCED(5_000L, 5f, true),
    EXPEDITION(60_000L, 20f, false),
}
