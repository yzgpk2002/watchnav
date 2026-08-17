package com.watchnav.session

import com.watchnav.core.GeoMath
import com.watchnav.core.GeoPoint
import com.watchnav.core.LocationFix
import kotlin.math.max

/** Detects a finish-area return independently of progress that a transient rejoin may reset. */
internal class FinishArrivalDetector(private val finish: GeoPoint) {
    private var departedFinishArea = false
    private var completed = false

    fun observeHistoricalPosition(position: GeoPoint) {
        if (GeoMath.distanceMeters(position, finish) >= MINIMUM_DEPARTURE_RADIUS_METERS) {
            departedFinishArea = true
        }
    }

    fun update(fix: LocationFix): Boolean {
        val distance = GeoMath.distanceMeters(fix.position, finish)
        val departureRadius = max(
            MINIMUM_DEPARTURE_RADIUS_METERS,
            fix.accuracyMeters * DEPARTURE_ACCURACY_FACTOR,
        ).coerceAtMost(MAXIMUM_DEPARTURE_RADIUS_METERS)
        if (distance >= departureRadius) departedFinishArea = true

        val arrivalRadius = max(
            MINIMUM_ARRIVAL_RADIUS_METERS,
            fix.accuracyMeters * ARRIVAL_ACCURACY_FACTOR,
        ).coerceAtMost(MAXIMUM_ARRIVAL_RADIUS_METERS)
        if (departedFinishArea && distance <= arrivalRadius) completed = true
        return completed
    }

    fun isInsideRerouteProtection(fix: LocationFix): Boolean {
        val radius = max(
            MINIMUM_REROUTE_PROTECTION_METERS,
            fix.accuracyMeters * REROUTE_PROTECTION_ACCURACY_FACTOR,
        ).coerceAtMost(MAXIMUM_REROUTE_PROTECTION_METERS)
        return GeoMath.distanceMeters(fix.position, finish) <= radius
    }

    private companion object {
        const val MINIMUM_DEPARTURE_RADIUS_METERS = 300.0
        const val MAXIMUM_DEPARTURE_RADIUS_METERS = 500.0
        const val DEPARTURE_ACCURACY_FACTOR = 5.0
        const val MINIMUM_ARRIVAL_RADIUS_METERS = 35.0
        const val MAXIMUM_ARRIVAL_RADIUS_METERS = 150.0
        const val ARRIVAL_ACCURACY_FACTOR = 3.5
        const val MINIMUM_REROUTE_PROTECTION_METERS = 200.0
        const val MAXIMUM_REROUTE_PROTECTION_METERS = 300.0
        const val REROUTE_PROTECTION_ACCURACY_FACTOR = 5.0
    }
}
