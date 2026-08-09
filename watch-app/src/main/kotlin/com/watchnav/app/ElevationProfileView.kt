package com.watchnav.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.watchnav.core.Course
import com.watchnav.core.ElevationProfile
import com.watchnav.core.PreparedCourse
import com.watchnav.session.NavigationTelemetry

class ElevationProfileView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 72, 49)
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(85, 226, 141)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val climbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 184, 77)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 24f
    }
    private var prepared: PreparedCourse? = null
    private var progressMeters = 0.0
    private var totalDistanceMeters = 1.0
    private var normalizedProfile: List<Pair<Float, Float>> = emptyList()
    private var normalizedClimbs: List<List<Pair<Float, Float>>> = emptyList()
    private var maximumLabel = ""
    private var minimumLabel = ""
    private val linePath = Path()
    private val areaPath = Path()
    private val climbPaths = ArrayList<Path>()

    fun setCourse(course: Course) {
        prepared = PreparedCourse.prepare(course)
        val preparedCourse = requireNotNull(prepared)
        val profile = ElevationProfile.prepare(preparedCourse)
        progressMeters = 0.0
        totalDistanceMeters = preparedCourse.totalDistanceMeters.coerceAtLeast(1.0)
        val elevations = course.points.mapNotNull { it.position.elevationMeters }
        if (elevations.size >= 2) {
            val minimum = elevations.min()
            val maximum = elevations.max()
            val range = (maximum - minimum).coerceAtLeast(10.0)
            normalizedProfile = course.points.mapIndexedNotNull { index, point ->
                val elevation = point.position.elevationMeters ?: return@mapIndexedNotNull null
                (preparedCourse.cumulativeDistanceMeters[index] / totalDistanceMeters).toFloat() to
                    ((elevation - minimum) / range).toFloat()
            }
            normalizedClimbs = profile.climbs.map { climb ->
                (climb.startPointIndex..climb.endPointIndex).mapNotNull { index ->
                    val elevation = course.points[index].position.elevationMeters ?: return@mapNotNull null
                    (preparedCourse.cumulativeDistanceMeters[index] / totalDistanceMeters).toFloat() to
                        ((elevation - minimum) / range).toFloat()
                }
            }
            maximumLabel = "${maximum.toInt()} m"
            minimumLabel = "${minimum.toInt()} m"
        } else {
            normalizedProfile = emptyList()
            normalizedClimbs = emptyList()
        }
        rebuildPaths()
        invalidate()
    }

    fun setTelemetry(telemetry: NavigationTelemetry) {
        progressMeters = telemetry.progressMeters
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (normalizedProfile.size < 2) {
            canvas.drawText("No elevation data", paddingLeft.toFloat(), height / 2f, textPaint)
            return
        }
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat() + 4f
        val bottom = height - paddingBottom.toFloat() - 4f
        canvas.drawPath(areaPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
        climbPaths.forEach { canvas.drawPath(it, climbPaint) }
        val right = width - paddingRight.toFloat()
        val progressX = left +
            (progressMeters.coerceIn(0.0, totalDistanceMeters) / totalDistanceMeters * (right - left)).toFloat()
        canvas.drawLine(progressX, top, progressX, bottom, progressPaint)
        canvas.drawText(maximumLabel, left + 4f, top + textPaint.textSize, textPaint)
        canvas.drawText(minimumLabel, left + 4f, bottom - 4f, textPaint)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuildPaths()
    }

    private fun rebuildPaths() {
        linePath.reset()
        areaPath.reset()
        climbPaths.clear()
        if (width <= 0 || height <= 0 || normalizedProfile.size < 2) return
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        val top = paddingTop.toFloat() + 4f
        val bottom = height - paddingBottom.toFloat() - 4f
        fun addPoints(path: Path, points: List<Pair<Float, Float>>) {
            points.forEachIndexed { index, point ->
                val x = left + point.first * (right - left)
                val y = bottom - point.second * (bottom - top)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        addPoints(linePath, normalizedProfile)
        areaPath.set(linePath)
        areaPath.lineTo(right, bottom)
        areaPath.lineTo(left, bottom)
        areaPath.close()
        normalizedClimbs.forEach { points ->
            climbPaths += Path().also { addPoints(it, points) }
        }
    }
}
