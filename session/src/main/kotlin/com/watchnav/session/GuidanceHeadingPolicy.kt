package com.watchnav.session

/** Keeps walking guidance on the resolved route tangent instead of noisy single-fix GNSS COG. */
internal object GuidanceHeadingPolicy {
    fun resolve(
        filteredHeadingDegrees: Double?,
        speedMetersPerSecond: Double?,
        pathHeadingDegrees: Double?,
    ): Double? {
        pathHeadingDegrees ?: return filteredHeadingDegrees
        filteredHeadingDegrees ?: return pathHeadingDegrees
        if ((speedMetersPerSecond ?: 0.0) >= GPS_HEADING_SPEED_METERS_PER_SECOND) {
            return filteredHeadingDegrees
        }
        // The direction estimator has already selected the forward/reverse route tangent. Using
        // that exact geographic bearing keeps the map and the 360-degree north scale locked.
        return pathHeadingDegrees
    }

    private const val GPS_HEADING_SPEED_METERS_PER_SECOND = 0.8
}
