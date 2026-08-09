package com.watchnav.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.watchnav.core.Course
import com.watchnav.core.CourseRenderSimplifier
import com.watchnav.core.GeoPoint
import com.watchnav.core.PreparedCourse
import com.watchnav.session.NavigationTelemetry
import kotlin.math.cos
import kotlin.math.max

class CourseMapView(context: Context) : View(context) {
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 95, 102)
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val completedPaint = Paint(routePaint).apply { color = Color.rgb(85, 226, 141) }
    private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 184, 77)
        style = Paint.Style.FILL
    }
    private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 255, 184, 77)
        style = Paint.Style.FILL
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var course: Course? = null
    private var preparedCourse: PreparedCourse? = null
    private var renderPointIndices = IntArray(0)
    private var telemetry: NavigationTelemetry = NavigationTelemetry()
    private var viewport: CourseViewport? = null

    fun setCourse(value: Course?) {
        course = value
        preparedCourse = value?.let { runCatching { PreparedCourse.prepare(it) }.getOrNull() }
        renderPointIndices = value?.let(CourseRenderSimplifier::retainedPointIndices) ?: IntArray(0)
        updateViewport()
        invalidate()
    }

    fun setTelemetry(value: NavigationTelemetry) {
        telemetry = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(8, 10, 12))
        val currentCourse = course ?: return
        val prepared = preparedCourse ?: return
        if (currentCourse.points.size < 2) return

        val viewport = viewport ?: return
        drawRoute(canvas, currentCourse, viewport, routePaint) { true }
        val progress = telemetry.progressMeters
        drawRoute(canvas, currentCourse, viewport, completedPaint) { pointIndex ->
            prepared.cumulativeDistanceMeters[pointIndex] <= progress
        }
        telemetry.position?.let { position ->
            val screenPoint = viewport.project(position)
            val accuracy = telemetry.horizontalAccuracyMeters ?: 0.0
            val accuracyPixels = (accuracy / viewport.metersPerPixel).toFloat().coerceIn(0f, 70f)
            if (accuracyPixels > 3f) {
                canvas.drawCircle(screenPoint.first, screenPoint.second, accuracyPixels, accuracyPaint)
            }
            canvas.drawCircle(screenPoint.first, screenPoint.second, 10f, positionPaint)
            telemetry.bearingDegrees?.let { bearing ->
                val radians = Math.toRadians(bearing)
                val dx = kotlin.math.sin(radians).toFloat() * 24f
                val dy = -kotlin.math.cos(radians).toFloat() * 24f
                canvas.drawLine(
                    screenPoint.first,
                    screenPoint.second,
                    screenPoint.first + dx,
                    screenPoint.second + dy,
                    headingPaint,
                )
            }
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateViewport()
    }

    private fun updateViewport() {
        val currentCourse = course
        viewport = if (currentCourse != null && width > 0 && height > 0) {
            CourseViewport(currentCourse, width, height, padding = 22f)
        } else {
            null
        }
    }

    private fun drawRoute(
        canvas: Canvas,
        course: Course,
        viewport: CourseViewport,
        paint: Paint,
        includePoint: (Int) -> Boolean,
    ) {
        var path = Path()
        var hasPath = false
        renderPointIndices.forEach { index ->
            if (!includePoint(index)) return@forEach
            val point = course.points[index]
            val screen = viewport.project(point.position)
            if (!hasPath || index in course.breakBeforePointIndices) {
                if (hasPath) canvas.drawPath(path, paint)
                path = Path().apply { moveTo(screen.first, screen.second) }
                hasPath = true
            } else {
                path.lineTo(screen.first, screen.second)
            }
        }
        if (hasPath) canvas.drawPath(path, paint)
    }

    private class CourseViewport(course: Course, width: Int, height: Int, padding: Float) {
        private val centerLatitude = course.points.map { it.position.latitude }.average()
        private val lonScale = max(0.15, cos(Math.toRadians(centerLatitude)))
        private val minX = course.points.minOf { it.position.longitude * lonScale }
        private val maxX = course.points.maxOf { it.position.longitude * lonScale }
        private val minY = course.points.minOf { it.position.latitude }
        private val maxY = course.points.maxOf { it.position.latitude }
        private val usableWidth = max(1f, width - padding * 2)
        private val usableHeight = max(1f, height - padding * 2)
        private val xSpan = max(0.00001, maxX - minX)
        private val ySpan = max(0.00001, maxY - minY)
        private val scale = minOf(usableWidth / xSpan.toFloat(), usableHeight / ySpan.toFloat())
        private val contentWidth = xSpan.toFloat() * scale
        private val contentHeight = ySpan.toFloat() * scale
        private val offsetX = (width - contentWidth) / 2f
        private val offsetY = (height - contentHeight) / 2f
        val metersPerPixel: Double = 111_320.0 / scale

        fun project(point: GeoPoint): Pair<Float, Float> =
            Pair(
                offsetX + ((point.longitude * lonScale - minX) * scale).toFloat(),
                offsetY + ((maxY - point.latitude) * scale).toFloat(),
            )
    }
}
