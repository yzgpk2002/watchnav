package com.watchnav.routing

import com.watchnav.core.Course
import com.watchnav.core.GeoPoint

enum class RoutingProfile { HIKE, RUN, BIKE }

data class RouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val via: List<GeoPoint> = emptyList(),
    val profile: RoutingProfile,
)

sealed interface RouteResult {
    data class Success(val course: Course, val calculationMillis: Long) : RouteResult
    data class MissingRegion(val regionIds: Set<String>) : RouteResult
    data class Failure(val reason: String, val recoverable: Boolean) : RouteResult
}

interface OfflineRouter {
    suspend fun route(request: RouteRequest): RouteResult
    suspend fun nearestRoutablePoint(point: GeoPoint, profile: RoutingProfile): GeoPoint?
}

