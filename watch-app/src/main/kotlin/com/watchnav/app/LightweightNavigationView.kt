package com.watchnav.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import com.watchnav.core.Course
import com.watchnav.core.GeoMath
import com.watchnav.core.GeoPoint
import com.watchnav.core.ManeuverKind
import com.watchnav.core.NavigationDirection
import com.watchnav.core.NavigationInstruction
import com.watchnav.core.PreparedCourse
import com.watchnav.session.NavigationTelemetry
import kotlin.math.cos
import kotlin.math.max

/**
 * Navigation-only renderer. It deliberately has no tile/database/network dependency: only a short
 * corridor around the current progress is projected, so GPS updates stay cheap on a watch.
 */
class LightweightNavigationView(context: Context) : View(context) {
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(188, 92, 255); strokeWidth = dp(6f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val branchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(105, 138, 145); strokeWidth = dp(2.5f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val approachPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(188, 92, 255); strokeWidth = dp(5f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val breadcrumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(101, 225, 238); strokeWidth = dp(3.5f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val boatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(202, 106, 255); style = Paint.Style.FILL
    }
    private val boatOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = dp(2f); style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val bearingRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 118, 122); strokeWidth = dp(1.2f); style = Paint.Style.STROKE
    }
    private val bearingTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(126, 207, 211); strokeWidth = dp(1.3f); style = Paint.Style.STROKE
    }
    private val bearingLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(174, 230, 233); textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD; textSize = dp(11f)
    }
    private val northLabelPaint = Paint(bearingLabelPaint).apply {
        color = Color.rgb(86, 246, 237); textSize = dp(14f)
    }
    private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val distancePaint = Paint(instructionPaint).apply { color = Color.rgb(255, 213, 72) }
    private var course: Course? = null
    private var prepared: PreparedCourse? = null
    private var telemetry = NavigationTelemetry()
    private var headingDegrees = 0.0
    private var hasLiveCompassHeading = false

    fun setCourse(value: Course?) {
        course = value
        prepared = value?.let { runCatching { PreparedCourse.prepare(it) }.getOrNull() }
        invalidate()
    }

    fun setTelemetry(value: NavigationTelemetry) {
        telemetry = value
        invalidate()
    }

    fun setHeadingDegrees(value: Double) {
        hasLiveCompassHeading = true
        if (kotlin.math.abs(shortestTurn(headingDegrees, value)) < 3.0) return
        headingDegrees = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(2, 16, 18))
        drawBearingScale(canvas)
        val active = course
        val preparedCourse = prepared
        val position = telemetry.position
        if (active != null && preparedCourse != null && position != null) {
            drawLocalCorridor(canvas, active, preparedCourse, position)
        }
        drawManeuver(canvas)
    }

    private fun drawBearingScale(canvas: Canvas) {
        if (telemetry.position == null || telemetry.horizontalAccuracyMeters == null) return
        val heading = activeMapHeading()
        val centerX = width / 2f
        val centerY = height / 2f
        val outerRadius = minOf(width, height) / 2f - dp(4f)
        canvas.drawCircle(centerX, centerY, outerRadius, bearingRingPaint)
        for (azimuth in 0 until 360 step BEARING_TICK_STEP_DEGREES) {
            val relative = Math.toRadians(azimuth - heading)
            val major = azimuth % BEARING_MAJOR_TICK_STEP_DEGREES == 0
            val innerRadius = outerRadius - dp(if (major) 10f else 5f)
            val sin = kotlin.math.sin(relative).toFloat()
            val cos = kotlin.math.cos(relative).toFloat()
            bearingTickPaint.strokeWidth = dp(if (major) 2f else 1.1f)
            canvas.drawLine(
                centerX + sin * innerRadius,
                centerY - cos * innerRadius,
                centerX + sin * outerRadius,
                centerY - cos * outerRadius,
                bearingTickPaint,
            )
        }
        listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").forEach { (azimuth, label) ->
            val relative = Math.toRadians(azimuth - heading)
            val labelRadius = outerRadius - dp(20f)
            val x = centerX + kotlin.math.sin(relative).toFloat() * labelRadius
            val y = centerY - kotlin.math.cos(relative).toFloat() * labelRadius + dp(5f)
            canvas.drawText(label, x, y, if (azimuth == 0) northLabelPaint else bearingLabelPaint)
        }
    }

    private fun drawLocalCorridor(canvas: Canvas, course: Course, prepared: PreparedCourse, position: GeoPoint) {
        val progress = telemetry.progressMeters
        val from = (progress - LOOKBEHIND_METERS).coerceAtLeast(0.0)
        val to = (progress + LOOKAHEAD_METERS).coerceAtMost(prepared.totalDistanceMeters)
        val firstInWindow = prepared.cumulativeDistanceMeters.indexOfFirst { it >= from }
        val lastInWindow = prepared.cumulativeDistanceMeters.indexOfFirst { it >= to }
            .takeIf { it >= 0 } ?: course.points.lastIndex
        val indices = if (firstInWindow >= 0) {
            (max(0, firstInWindow - 1)..minOf(course.points.lastIndex, lastInWindow + 1)).toList()
        } else emptyList()
        val visibleIndices = indices.ifEmpty {
            val nearest = course.points.indices.minByOrNull { GeoMath.distanceMeters(position, course.points[it].position) } ?: 0
            (max(0, nearest - 2)..minOf(course.points.lastIndex, nearest + 8)).toList()
        }
        val points = visibleIndices.map { course.points[it].position }
        if (points.size < 2) return
        val bearing = activeMapHeading()
        val visibleRangeMeters = maxOf(
            LOOKAHEAD_METERS,
            LOOKBEHIND_METERS,
            (telemetry.crossTrackMeters ?: 0.0) * APPROACH_VISIBILITY_FACTOR,
        )
        val scale = minOf(width, height) / (visibleRangeMeters * 1.35)
        fun project(point: GeoPoint): Pair<Float, Float> {
            val north = (point.latitude - position.latitude) * 111_320.0
            val east = (point.longitude - position.longitude) * 111_320.0 * max(.15, cos(Math.toRadians(position.latitude)))
            val rotated = HeadingUpTransform.rotate(east, north, bearing)
            return (width / 2f + (rotated.rightMeters * scale).toFloat()) to
                (height * BOAT_Y_FRACTION - (rotated.forwardMeters * scale).toFloat())
        }

        drawBreadcrumbTrail(canvas, position, ::project)

        telemetry.routeJoinPosition?.takeIf {
            GeoMath.distanceMeters(position, it) >= MINIMUM_APPROACH_CONNECTOR_METERS
        }?.let { join ->
            val startX = width / 2f
            val startY = height * BOAT_Y_FRACTION
            val end = project(join)
            canvas.drawLine(startX, startY, end.first, end.second, approachPaint)
        }
        val route = Path()
        points.forEachIndexed { index, point ->
            val screen = project(point)
            val sourceIndex = visibleIndices[index]
            if (index == 0 || sourceIndex in course.breakBeforePointIndices) {
                route.moveTo(screen.first, screen.second)
            } else {
                route.lineTo(screen.first, screen.second)
            }
        }
        canvas.drawPath(route, routePaint)

        // Draw only real nearby course segments as fork hints.  The former centre-to-point rays
        // looked like roads but did not represent actual geometry.
        course.points.indices.asSequence()
            .drop(1)
            .filterNot(visibleIndices::contains)
            .filterNot(course.breakBeforePointIndices::contains)
            .map { index -> course.points[index - 1].position to course.points[index].position }
            .filter { (a, b) ->
                GeoMath.distanceMeters(position, a) <= BRANCH_VISIBILITY_METERS &&
                    GeoMath.distanceMeters(position, b) <= BRANCH_VISIBILITY_METERS
            }
            .take(MAX_BRANCH_SEGMENTS)
            .forEach { (a, b) ->
                val start = project(a)
                val end = project(b)
                canvas.drawLine(start.first, start.second, end.first, end.second, branchPaint)
            }

        drawBoat(canvas, width / 2f, height * BOAT_Y_FRACTION)
    }

    private fun drawBreadcrumbTrail(
        canvas: Canvas,
        position: GeoPoint,
        project: (GeoPoint) -> Pair<Float, Float>,
    ) {
        val recent = telemetry.breadcrumbTrail.asReversed()
            .takeWhile { GeoMath.distanceMeters(position, it) <= MAXIMUM_VISIBLE_BREADCRUMB_DISTANCE_METERS }
            .asReversed()
            .takeLast(MAXIMUM_BREADCRUMB_POINTS)
        if (recent.size < 2) return
        val trail = Path()
        recent.forEachIndexed { index, point ->
            val screen = project(point)
            if (index == 0) trail.moveTo(screen.first, screen.second) else trail.lineTo(screen.first, screen.second)
        }
        // The last recorded fix and the current filtered fix may differ slightly. Join them so the
        // real travelled path visibly crosses the boat instead of ending behind it.
        trail.lineTo(width / 2f, height * BOAT_Y_FRACTION)
        canvas.drawPath(trail, breadcrumbPaint)
    }

    private fun drawBoat(canvas: Canvas, x: Float, y: Float) {
        val size = dp(18f)
        val hull = Path().apply {
            moveTo(x, y - size)
            lineTo(x + size * .62f, y + size * .45f)
            lineTo(x + size * .35f, y + size)
            lineTo(x - size * .35f, y + size)
            lineTo(x - size * .62f, y + size * .45f)
            close()
        }
        canvas.drawPath(hull, boatPaint)
        canvas.drawPath(hull, boatOutlinePaint)
        canvas.drawLine(x, y - size * .65f, x, y + size * .45f, boatOutlinePaint)
        canvas.drawLine(x - size * .38f, y + size * .35f, x + size * .38f, y + size * .35f, boatOutlinePaint)
    }

    private fun drawManeuver(canvas: Canvas) {
        val kind = telemetry.nextManeuver ?: ManeuverKind.START
        val activeDirection = NavigationInstruction.direction(kind)
        instructionPaint.textSize = dp(34f)
        listOf(
            NavigationDirection.LEFT to "↰",
            NavigationDirection.STRAIGHT to "↑",
            NavigationDirection.RIGHT to "↱",
        ).forEachIndexed { index, (direction, symbol) ->
            instructionPaint.color = if (
                activeDirection == direction ||
                activeDirection == NavigationDirection.U_TURN && direction == NavigationDirection.LEFT ||
                activeDirection == NavigationDirection.FINISH && direction == NavigationDirection.STRAIGHT
            ) Color.rgb(255, 213, 72) else Color.rgb(95, 128, 132)
            val x = width * listOf(.34f, .50f, .66f)[index]
            canvas.drawText(symbol, x, height * .40f, instructionPaint)
        }
        distancePaint.textSize = dp(34f)
        val distance = telemetry.distanceToNextManeuverMeters
        val label = when {
            distance == null -> "—"
            distance >= 1_000 -> String.format("%.1f km", distance / 1_000.0)
            else -> "${distance.toInt()} m"
        }
        // Keep the maneuver row unobstructed. The distance belongs with the boat position and is
        // intentionally below it, where it remains readable without covering left/right arrows.
        canvas.drawText(label, width / 2f, height * .81f, distancePaint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    /** The map projection and bezel scale must always use the exact same true-north heading. */
    private fun activeMapHeading(): Double = if (hasLiveCompassHeading) {
        headingDegrees
    } else {
        telemetry.bearingDegrees ?: headingDegrees
    }

    private fun shortestTurn(from: Double, to: Double) = ((to - from + 540.0) % 360.0) - 180.0

    private companion object {
        const val LOOKAHEAD_METERS = 450.0
        const val LOOKBEHIND_METERS = 250.0
        const val MAX_BRANCH_SEGMENTS = 8
        const val BRANCH_VISIBILITY_METERS = 120.0
        const val MINIMUM_APPROACH_CONNECTOR_METERS = 4.0
        const val APPROACH_VISIBILITY_FACTOR = 1.6
        const val MAXIMUM_BREADCRUMB_POINTS = 80
        const val MAXIMUM_VISIBLE_BREADCRUMB_DISTANCE_METERS = 600.0
        const val BOAT_Y_FRACTION = .56f
        const val BEARING_TICK_STEP_DEGREES = 10
        const val BEARING_MAJOR_TICK_STEP_DEGREES = 30
    }
}

internal object HeadingUpTransform {
    data class Result(val rightMeters: Double, val forwardMeters: Double)

    fun rotate(eastMeters: Double, northMeters: Double, bearingDegrees: Double): Result {
        val heading = Math.toRadians(bearingDegrees)
        return Result(
            rightMeters = eastMeters * kotlin.math.cos(heading) - northMeters * kotlin.math.sin(heading),
            forwardMeters = eastMeters * kotlin.math.sin(heading) + northMeters * kotlin.math.cos(heading),
        )
    }
}
