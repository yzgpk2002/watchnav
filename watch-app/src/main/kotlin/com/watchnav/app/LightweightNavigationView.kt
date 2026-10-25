package com.watchnav.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.watchnav.core.Course
import com.watchnav.core.CoursePointCategory
import com.watchnav.core.CoursePointRole
import com.watchnav.core.GeoMath
import com.watchnav.core.GeoPoint
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
    private val completedRoutePaint = Paint(routePaint).apply {
        color = Color.rgb(112, 124, 128)
        strokeWidth = dp(3.5f)
        alpha = 150
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(6f)), 0f)
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
    private val poiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(8, 49, 53); style = Paint.Style.FILL
    }
    private val poiTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        textSize = dp(11f)
    }
    private var course: Course? = null
    private var prepared: PreparedCourse? = null
    private var telemetry = NavigationTelemetry()
    private var headingDegrees = 0.0
    private var hasLiveCompassHeading = false
    private var zoomMultiplier = 1.0
    private var edgeTouchActive = false
    private var lastEdgeAngleDegrees = 0.0
    private var accumulatedEdgeDegrees = 0.0

    fun setCourse(value: Course?) {
        course = value
        prepared = value?.let { runCatching { PreparedCourse.prepare(it) }.getOrNull() }
        zoomMultiplier = 1.0
        invalidate()
    }

    fun zoomBy(steps: Int) {
        if (steps == 0) return
        zoomMultiplier = (zoomMultiplier * Math.pow(ZOOM_STEP_FACTOR, -steps.toDouble()))
            .coerceIn(MINIMUM_ZOOM_MULTIPLIER, MAXIMUM_ZOOM_MULTIPLIER)
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
        drawDeviation(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = kotlin.math.hypot(event.x - centerX, event.y - centerY)
        val edgeThreshold = minOf(width, height) * EDGE_TOUCH_RADIUS_FRACTION
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (radius < edgeThreshold) return false
                edgeTouchActive = true
                lastEdgeAngleDegrees = edgeAngle(event.x, event.y, centerX, centerY)
                accumulatedEdgeDegrees = 0.0
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (edgeTouchActive) {
                val angle = edgeAngle(event.x, event.y, centerX, centerY)
                accumulatedEdgeDegrees += shortestTurn(lastEdgeAngleDegrees, angle)
                lastEdgeAngleDegrees = angle
                while (kotlin.math.abs(accumulatedEdgeDegrees) >= EDGE_DEGREES_PER_ZOOM_STEP) {
                    val direction = if (accumulatedEdgeDegrees > 0.0) -1 else 1
                    zoomBy(direction)
                    accumulatedEdgeDegrees -= kotlin.math.sign(accumulatedEdgeDegrees) * EDGE_DEGREES_PER_ZOOM_STEP
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                return true
            }
            MotionEvent.ACTION_UP -> if (edgeTouchActive) {
                edgeTouchActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                edgeTouchActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun edgeAngle(x: Float, y: Float, centerX: Float, centerY: Float): Double =
        Math.toDegrees(kotlin.math.atan2((y - centerY).toDouble(), (x - centerX).toDouble()))

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
        val baseRangeMeters = maxOf(
            LOOKAHEAD_METERS,
            LOOKBEHIND_METERS,
            (telemetry.crossTrackMeters ?: 0.0) * APPROACH_VISIBILITY_FACTOR,
        )
        val endpointRangeMeters = maxOf(
            GeoMath.distanceMeters(position, course.points.first().position),
            GeoMath.distanceMeters(position, course.points.last().position),
        ) * ENDPOINT_RING_PADDING_FACTOR
        // Zooming out stops when the farther endpoint reaches the safe inner edge of the bearing
        // ring. A fixed 450 m lower bound made a 100 m walking route appear as a tiny centre dot.
        val maximumRangeMeters = maxOf(MINIMUM_VISIBLE_RANGE_METERS, endpointRangeMeters)
        val defaultRangeMeters = baseRangeMeters.coerceAtMost(maximumRangeMeters)
        val visibleRangeMeters = (defaultRangeMeters * zoomMultiplier)
            .coerceIn(MINIMUM_VISIBLE_RANGE_METERS, maximumRangeMeters)
        val showWholeRoute = visibleRangeMeters >= maximumRangeMeters * WHOLE_ROUTE_RANGE_RATIO
        val from = if (showWholeRoute) 0.0 else
            (progress - visibleRangeMeters * LOOKBEHIND_RANGE_RATIO).coerceAtLeast(0.0)
        val to = if (showWholeRoute) prepared.totalDistanceMeters else
            (progress + visibleRangeMeters).coerceAtMost(prepared.totalDistanceMeters)
        val firstInWindow = prepared.cumulativeDistanceMeters.indexOfFirst { it >= from }
        val lastInWindow = prepared.cumulativeDistanceMeters.indexOfFirst { it >= to }
            .takeIf { it >= 0 } ?: course.points.lastIndex
        val indices = if (firstInWindow >= 0) {
            (max(0, firstInWindow - 1)..minOf(course.points.lastIndex, lastInWindow + 1)).toList()
        } else emptyList()
        val rawVisibleIndices = indices.ifEmpty {
            val nearest = course.points.indices.minByOrNull { GeoMath.distanceMeters(position, course.points[it].position) } ?: 0
            (max(0, nearest - 2)..minOf(course.points.lastIndex, nearest + 8)).toList()
        }
        val visibleIndices = decimateIndices(rawVisibleIndices)
        val points = visibleIndices.map { course.points[it].position }
        if (points.size < 2) return
        val bearing = activeMapHeading()
        val boatY = height * BOAT_Y_FRACTION
        val usableRadius = minOf(
            width / 2f - dp(28f),
            boatY - dp(28f),
            height - boatY - dp(28f),
        ).coerceAtLeast(dp(80f))
        val scale = usableRadius / visibleRangeMeters
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
        val completedRoute = Path()
        val remainingRoute = Path()
        visibleIndices.windowed(2).forEach { (startIndex, endIndex) ->
            if ((startIndex + 1..endIndex).any(course.breakBeforePointIndices::contains)) return@forEach
            val start = project(course.points[startIndex].position)
            val end = project(course.points[endIndex].position)
            val target = if (prepared.cumulativeDistanceMeters[endIndex] <= progress) {
                completedRoute
            } else {
                remainingRoute
            }
            target.moveTo(start.first, start.second)
            target.lineTo(end.first, end.second)
        }
        canvas.drawPath(completedRoute, completedRoutePaint)
        canvas.drawPath(remainingRoute, routePaint)
        drawForwardCourseArrow(canvas, course, prepared, progress, visibleIndices, ::project)
        drawCoursePoints(canvas, course, position, ::project)

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

    private fun drawForwardCourseArrow(
        canvas: Canvas,
        course: Course,
        prepared: PreparedCourse,
        progress: Double,
        visibleIndices: List<Int>,
        project: (GeoPoint) -> Pair<Float, Float>,
    ) {
        val index = visibleIndices.firstOrNull {
            it > 0 && it !in course.breakBeforePointIndices &&
                prepared.cumulativeDistanceMeters[it] >= progress + COURSE_ARROW_LOOKAHEAD_METERS
        } ?: return
        val from = project(course.points[index - 1].position)
        val at = project(course.points[index].position)
        val angle = kotlin.math.atan2((at.second - from.second).toDouble(), (at.first - from.first).toDouble())
        val size = dp(8f)
        val arrow = Path().apply {
            moveTo(at.first + kotlin.math.cos(angle).toFloat() * size, at.second + kotlin.math.sin(angle).toFloat() * size)
            lineTo(at.first + kotlin.math.cos(angle + 2.45).toFloat() * size, at.second + kotlin.math.sin(angle + 2.45).toFloat() * size)
            lineTo(at.first + kotlin.math.cos(angle - 2.45).toFloat() * size, at.second + kotlin.math.sin(angle - 2.45).toFloat() * size)
            close()
        }
        canvas.drawPath(arrow, boatPaint)
    }

    private fun drawCoursePoints(
        canvas: Canvas,
        course: Course,
        position: GeoPoint,
        project: (GeoPoint) -> Pair<Float, Float>,
    ) {
        (course.landmarks + course.points.filter { it.role == CoursePointRole.VIA })
            .asSequence()
            .filterNot { it.role == CoursePointRole.SHAPING }
            .filterNot { it.type == "start" || it.type == "finish" || it.type?.startsWith("distance-") == true }
            .filter { !it.name.isNullOrBlank() || it.category != CoursePointCategory.GENERIC }
            .distinctBy {
                "${(it.position.latitude * 100_000).toInt()}:${(it.position.longitude * 100_000).toInt()}"
            }
            .map { it to GeoMath.distanceMeters(position, it.position) }
            .filter { it.second <= POI_VISIBILITY_METERS }
            .sortedBy { it.second }
            .take(MAXIMUM_VISIBLE_POIS)
            .forEach { (point, _) ->
                val screen = project(point.position)
                canvas.drawCircle(screen.first, screen.second, dp(10f), poiPaint)
                canvas.drawText(categoryGlyph(point.category), screen.first, screen.second + dp(4f), poiTextPaint)
                point.name?.take(8)?.takeIf {
                    kotlin.math.hypot(
                        (screen.first - width / 2f).toDouble(),
                        (screen.second - height * BOAT_Y_FRACTION).toDouble(),
                    ) >= dp(MINIMUM_POI_LABEL_DISTANCE_FROM_BOAT_DP).toDouble()
                }?.let { name ->
                    canvas.drawText(name, screen.first, screen.second - dp(14f), poiTextPaint)
                }
            }
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
        val size = dp(9f)
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

    private fun drawDeviation(canvas: Canvas) {
        val deviationMeters = (telemetry.crossTrackMeters ?: 0.0).coerceAtLeast(0.0)
        distancePaint.color = if (deviationMeters > DEVIATION_ALARM_METERS) {
            Color.rgb(255, 70, 70)
        } else {
            Color.rgb(255, 213, 72)
        }
        distancePaint.textSize = dp(30f)
        canvas.drawText(
            context.getString(R.string.deviation_distance, deviationMeters),
            width / 2f,
            height * .78f,
            distancePaint,
        )
    }

    private fun decimateIndices(indices: List<Int>): List<Int> {
        if (indices.size <= MAXIMUM_RENDERED_ROUTE_POINTS) return indices
        val step = kotlin.math.ceil(indices.size.toDouble() / MAXIMUM_RENDERED_ROUTE_POINTS).toInt()
        return buildList {
            indices.forEachIndexed { index, value -> if (index % step == 0) add(value) }
            if (lastOrNull() != indices.last()) add(indices.last())
        }
    }

    private fun categoryGlyph(category: CoursePointCategory): String = when (category) {
        CoursePointCategory.FOOD -> "●"
        CoursePointCategory.WATER -> "◆"
        CoursePointCategory.STORE -> "▣"
        CoursePointCategory.SHELTER -> "⌂"
        CoursePointCategory.MEDICAL -> "+"
        CoursePointCategory.SUMMIT -> "▲"
        CoursePointCategory.VIEWPOINT -> "◎"
        CoursePointCategory.DANGER -> "!"
        CoursePointCategory.FISHING -> "⚓"
        CoursePointCategory.GENERIC -> "•"
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
        const val COURSE_ARROW_LOOKAHEAD_METERS = 70.0
        const val POI_VISIBILITY_METERS = 500.0
        const val MAXIMUM_VISIBLE_POIS = 4
        const val MINIMUM_POI_LABEL_DISTANCE_FROM_BOAT_DP = 48f
        const val DEVIATION_ALARM_METERS = 60.0
        const val MINIMUM_VISIBLE_RANGE_METERS = 30.0
        const val LOOKBEHIND_RANGE_RATIO = 0.55
        const val ENDPOINT_RING_PADDING_FACTOR = 1.08
        const val WHOLE_ROUTE_RANGE_RATIO = 0.96
        const val ZOOM_STEP_FACTOR = 1.35
        const val MINIMUM_ZOOM_MULTIPLIER = 0.20
        const val MAXIMUM_ZOOM_MULTIPLIER = 1_000.0
        const val EDGE_TOUCH_RADIUS_FRACTION = 0.36f
        const val EDGE_DEGREES_PER_ZOOM_STEP = 18.0
        const val MAXIMUM_RENDERED_ROUTE_POINTS = 600
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
