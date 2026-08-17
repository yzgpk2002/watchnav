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
        color = Color.rgb(151, 71, 255)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val completedPaint = Paint(routePaint).apply { color = Color.rgb(218, 112, 255) }
    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 64)
        style = Paint.Style.FILL
    }
    private val landmarkTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 14f
        style = Paint.Style.FILL
    }
    private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(198, 92, 255)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 198, 92, 255)
        style = Paint.Style.FILL
    }
    private val positionCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(198, 92, 255)
        style = Paint.Style.FILL
    }

    private var course: Course? = null
    private var preparedCourse: PreparedCourse? = null
    private var renderPointIndices = IntArray(0)
    private var telemetry: NavigationTelemetry = NavigationTelemetry()
    private var headingDegrees: Double? = null
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
    private val routePath = Path()

    fun setHeadingDegrees(value: Double) {
        headingDegrees = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(248, 247, 238))
        val currentCourse = course ?: return
        val prepared = preparedCourse ?: return
        if (currentCourse.points.size < 2) return

        val viewport = viewport ?: return
        drawRoute(canvas, currentCourse, viewport, routePaint) { true }
        val progress = telemetry.progressMeters
        drawRoute(canvas, currentCourse, viewport, completedPaint) { pointIndex ->
            prepared.cumulativeDistanceMeters[pointIndex] <= progress
        }
        currentCourse.landmarks.forEach { landmark ->
            val screen = viewport.project(landmark.position)
            canvas.drawCircle(screen.first, screen.second, 6f, landmarkPaint)
            landmark.name?.take(12)?.let { name ->
                canvas.drawText(name, screen.first + 8f, screen.second - 7f, landmarkTextPaint)
            }
        }
        telemetry.position?.let { position ->
            val screenPoint = viewport.project(position)
            val accuracy = telemetry.horizontalAccuracyMeters ?: 0.0
            val accuracyPixels = (accuracy / viewport.metersPerPixel).toFloat().coerceIn(0f, 70f)
            if (accuracyPixels > 3f) {
                canvas.drawCircle(screenPoint.first, screenPoint.second, accuracyPixels, accuracyPaint)
            }
            val x = screenPoint.first
            val y = screenPoint.second
            val radius = resources.displayMetrics.density * 10f
            val tick = resources.displayMetrics.density * 5f
            canvas.drawCircle(x, y, radius, positionPaint)
            canvas.drawLine(x - radius - tick, y, x - radius + tick, y, positionPaint)
            canvas.drawLine(x + radius - tick, y, x + radius + tick, y, positionPaint)
            canvas.drawLine(x, y - radius - tick, x, y - radius + tick, positionPaint)
            canvas.drawLine(x, y + radius - tick, x, y + radius + tick, positionPaint)
            canvas.drawCircle(x, y, resources.displayMetrics.density * 2.8f, positionCenterPaint)
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
        routePath.rewind()
        var hasPath = false
        renderPointIndices.forEach { index ->
            if (!includePoint(index)) return@forEach
            val point = course.points[index]
            val screen = viewport.project(point.position)
            if (!hasPath || index in course.breakBeforePointIndices) {
                routePath.moveTo(screen.first, screen.second)
                hasPath = true
            } else {
                routePath.lineTo(screen.first, screen.second)
            }
        }
        if (hasPath) canvas.drawPath(routePath, paint)
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
