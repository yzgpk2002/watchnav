package com.watchnav.packages

import kotlin.math.PI
import kotlin.math.cos

data class MapDownloadRegion(
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusKilometers: Double,
) {
    init {
        require(centerLatitude in -85.0..85.0)
        require(centerLongitude in -180.0..180.0)
        require(radiusKilometers in MINIMUM_RADIUS_KM..MAXIMUM_RADIUS_KM)
    }

    val bounds: RegionBounds
        get() {
            val latitudeDelta = radiusKilometers / KILOMETERS_PER_LATITUDE_DEGREE
            val longitudeScale = cos(centerLatitude * PI / 180.0).coerceAtLeast(0.08)
            val longitudeDelta = radiusKilometers / (KILOMETERS_PER_LATITUDE_DEGREE * longitudeScale)
            return RegionBounds(
                south = (centerLatitude - latitudeDelta).coerceAtLeast(-85.0),
                west = (centerLongitude - longitudeDelta).coerceAtLeast(-180.0),
                north = (centerLatitude + latitudeDelta).coerceAtMost(85.0),
                east = (centerLongitude + longitudeDelta).coerceAtMost(180.0),
            )
        }

    val approximateAreaSquareKilometers: Double
        get() = 4.0 * radiusKilometers * radiusKilometers

    fun resized(scaleFactor: Float): MapDownloadRegion = copy(
        radiusKilometers = (radiusKilometers * scaleFactor)
            .coerceIn(MINIMUM_RADIUS_KM, MAXIMUM_RADIUS_KM),
    )

    companion object {
        const val MINIMUM_RADIUS_KM = 2.0
        const val MAXIMUM_RADIUS_KM = 200.0
        private const val KILOMETERS_PER_LATITUDE_DEGREE = 111.32

    }
}
