package com.watchnav.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.GeoMath
import com.watchnav.core.ManeuverKind
import com.watchnav.core.PreparedCourse
import com.watchnav.session.NavigationTelemetry
import java.util.Locale
import kotlin.math.min

/** Glanceable edge metrics shared by the full-map and turn-guidance pages. */
class BezelStatsView(context: Context) : View(context) {
    enum class Mode { MAP_OVERLAY, NAVIGATION }

    var mode: Mode = Mode.NAVIGATION
        set(value) {
            field = value
            invalidate()
        }

    private val arcBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 70, 70, 76)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val secondaryPaint = Paint(labelPaint).apply { color = Color.rgb(195, 198, 205) }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(218, 112, 255)
        style = Paint.Style.FILL
    }
    private val ringBounds = RectF()

    private var course: Course? = null
    private var preparedCourse: PreparedCourse? = null
    private var landmarkProgress = emptyList<Pair<CoursePoint, Double>>()
    private var telemetry = NavigationTelemetry()
    private var batteryPercent = 100

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setCourse(value: Course?) {
        course = value
        preparedCourse = value?.let { runCatching { PreparedCourse.prepare(it) }.getOrNull() }
        landmarkProgress = buildLandmarkProgress(value, preparedCourse)
        invalidate()
    }

    fun setTelemetry(value: NavigationTelemetry, battery: Int = batteryPercent) {
        telemetry = value
        batteryPercent = battery.coerceIn(0, 100)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = if (mode == Mode.MAP_OVERLAY) 7f else 9f
        val diameter = min(width, height).toFloat() - inset * 2
        if (diameter <= 0f) return
        ringBounds.set(
            (width - diameter) / 2f,
            (height - diameter) / 2f,
            (width + diameter) / 2f,
            (height + diameter) / 2f,
        )
        arcBackground.strokeWidth = if (mode == Mode.MAP_OVERLAY) 5f else 7f
        arcPaint.strokeWidth = arcBackground.strokeWidth

        val totalDistance = preparedCourse?.totalDistanceMeters ?: 0.0
        val progress = ratio(telemetry.progressMeters, totalDistance)
        val ascent = if (telemetry.totalAscentMeters > 0.0) {
            ratio(telemetry.totalAscentMeters - telemetry.remainingAscentMeters, telemetry.totalAscentMeters)
        } else {
            0f
        }
        val offCourse = ((telemetry.crossTrackMeters ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        val battery = batteryPercent / 100f

        drawStatArc(canvas, ringBounds, -132f, 78f, progress, Color.rgb(190, 92, 255))
        drawStatArc(canvas, ringBounds, -42f, 78f, ascent, Color.rgb(85, 226, 141))
        drawStatArc(canvas, ringBounds, 48f, 78f, offCourse, Color.rgb(255, 92, 92))
        drawStatArc(canvas, ringBounds, 138f, 78f, battery, Color.rgb(80, 177, 255))

        if (mode == Mode.MAP_OVERLAY) drawMapLabels(canvas) else drawNavigation(canvas)
    }

    private fun drawStatArc(
        canvas: Canvas,
        ring: RectF,
        start: Float,
        sweep: Float,
        progress: Float,
        color: Int,
    ) {
        canvas.drawArc(ring, start, sweep, false, arcBackground)
        arcPaint.color = color
        canvas.drawArc(ring, start, sweep * progress.coerceIn(0f, 1f), false, arcPaint)
    }

    private fun drawMapLabels(canvas: Canvas) {
        labelPaint.textSize = 11f * resources.displayMetrics.scaledDensity
        labelPaint.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        val progressPercent = (ratio(
            telemetry.progressMeters,
            preparedCourse?.totalDistanceMeters ?: 0.0,
        ) * 100).toInt()
        canvas.drawText("$progressPercent%", width / 2f, dp(28f), labelPaint)
        canvas.drawText("海拔 ${telemetry.currentElevationMeters?.toInt() ?: "--"}m", dp(42f), height - dp(18f), labelPaint)
        canvas.drawText("电量 $batteryPercent%", width - dp(42f), height - dp(18f), labelPaint)
        labelPaint.clearShadowLayer()
    }

    private fun drawNavigation(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        labelPaint.textSize = 12f * resources.displayMetrics.scaledDensity
        labelPaint.setShadowLayer(5f, 0f, 1f, Color.BLACK)
        secondaryPaint.setShadowLayer(5f, 0f, 1f, Color.BLACK)
        canvas.drawText("海拔 ${telemetry.currentElevationMeters?.toInt() ?: "--"}m", centerX, dp(29f), labelPaint)
        canvas.drawText("偏离 ${telemetry.crossTrackMeters?.toInt() ?: 0}m", dp(37f), centerY, labelPaint)
        canvas.drawText("心率 ${telemetry.heartRateBpm?.toInt() ?: "--"}", width - dp(37f), centerY, labelPaint)
        secondaryPaint.textSize = 11f * resources.displayMetrics.scaledDensity
        upcomingLandmark()?.let { (point, distance) ->
            val label = point.name ?: point.type ?: return@let
            canvas.drawText(
                "下一标点 ${label.take(14)} · ${formatDistance(distance)}",
                centerX,
                height - dp(43f),
                secondaryPaint,
            )
        }
        labelPaint.textSize = 12f * resources.displayMetrics.scaledDensity
        labelPaint.color = Color.rgb(218, 112, 255)
        canvas.drawText("距离", centerX, height - dp(23f), labelPaint)
        labelPaint.textSize = 19f * resources.displayMetrics.scaledDensity
        labelPaint.color = Color.WHITE
        canvas.drawText(formatDistance(telemetry.remainingMeters), centerX, height - dp(7f), labelPaint)
        labelPaint.clearShadowLayer()
        secondaryPaint.clearShadowLayer()
    }

    private fun drawManeuverArrow(canvas: Canvas, centerX: Float, centerY: Float) {
        val rotation = when (telemetry.nextManeuver) {
            ManeuverKind.SLIGHT_LEFT -> -35f
            ManeuverKind.LEFT -> -90f
            ManeuverKind.SHARP_LEFT -> -135f
            ManeuverKind.U_TURN -> 180f
            ManeuverKind.SHARP_RIGHT -> 135f
            ManeuverKind.RIGHT -> 90f
            ManeuverKind.SLIGHT_RIGHT -> 35f
            ManeuverKind.FINISH -> 0f
            ManeuverKind.START, null -> 0f
        }
        val path = Path().apply {
            moveTo(centerX, centerY - 48f)
            lineTo(centerX + 25f, centerY - 8f)
            lineTo(centerX + 9f, centerY - 13f)
            lineTo(centerX + 9f, centerY + 34f)
            lineTo(centerX - 9f, centerY + 34f)
            lineTo(centerX - 9f, centerY - 13f)
            lineTo(centerX - 25f, centerY - 8f)
            close()
        }
        canvas.save()
        canvas.rotate(rotation, centerX, centerY)
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
    }

    private fun upcomingLandmark(): Pair<CoursePoint, Double>? = landmarkProgress
        .asSequence()
        .map { (point, progress) -> point to (progress - telemetry.progressMeters) }
        .filter { (_, distance) -> distance >= -20.0 }
        .minByOrNull { (_, distance) -> distance }

    private fun buildLandmarkProgress(
        value: Course?,
        prepared: PreparedCourse?,
    ): List<Pair<CoursePoint, Double>> {
        if (value == null || prepared == null) return emptyList()
        val points = (value.landmarks + value.points.filter { it.name != null || it.type != null }).distinct()
        return points.map { landmark ->
            val encodedIndex = landmark.type
                ?.substringAfter("highest-", missingDelimiterValue = "")
                ?.toIntOrNull()
                ?.takeIf { it in value.points.indices }
            val encodedDistance = landmark.type
                ?.substringAfter("distance-", missingDelimiterValue = "")
                ?.toDoubleOrNull()
            val nearestIndex = when {
                landmark.type == "start" -> 0
                landmark.type == "finish" -> value.points.lastIndex
                encodedIndex != null -> encodedIndex
                encodedDistance != null -> prepared.cumulativeDistanceMeters
                    .indexOfFirst { it >= encodedDistance }
                    .coerceAtLeast(0)
                else -> value.points.indices.minByOrNull { index ->
                    GeoMath.distanceMeters(value.points[index].position, landmark.position)
                } ?: 0
            }
            landmark to prepared.cumulativeDistanceMeters[nearestIndex]
        }.distinctBy { it.first.name to it.second }.sortedBy { it.second }
    }

    private fun ratio(value: Double, total: Double): Float =
        if (total > 0.0 && value.isFinite()) (value / total).coerceIn(0.0, 1.0).toFloat() else 0f

    private fun formatDistance(meters: Double?): String {
        val value = meters ?: return "--"
        return if (value >= 1_000.0) {
            String.format(Locale.getDefault(), "%.1f km", value / 1_000.0)
        } else {
            String.format(Locale.getDefault(), "%.0f m", value.coerceAtLeast(0.0))
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
