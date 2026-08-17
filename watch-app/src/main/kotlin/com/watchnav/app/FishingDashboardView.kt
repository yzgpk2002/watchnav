package com.watchnav.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.watchnav.core.GeoPoint
import com.watchnav.session.NavigationTelemetry
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class FishingMetric(val shortRes: Int, val titleRes: Int) {
    BARO(R.string.fishing_metric_baro_short, R.string.fishing_metric_baro),
    OXYGEN(R.string.fishing_metric_oxygen_short, R.string.fishing_metric_oxygen),
    TIDE(R.string.fishing_metric_tide_short, R.string.fishing_metric_tide),
    ACTIVITY(R.string.fishing_metric_activity_short, R.string.fishing_metric_activity),
    SUN(R.string.fishing_metric_sun_short, R.string.fishing_metric_sun),
    CASTS(R.string.fishing_metric_casts_short, R.string.fishing_metric_casts),
    ELEVATION(R.string.fishing_metric_elevation_short, R.string.fishing_metric_elevation),
}

internal data class FishingMetricOption(val id: String, val title: String, val enabled: Boolean)

private data class FishingDashboardSnapshot(
    val nowMillis: Long,
    val solunar: SolunarAssessment,
    val pressureDelta3h: Float?,
    val decision: FishingDecision,
    val currentTideHeightMeters: Float?,
    val solar: FishingSolarWindow?,
)

private data class MetricPresentation(
    val value: String,
    val unit: String,
    val primaryDetail: String,
    val secondaryDetail: String,
    val accent: Int,
)

private data class BezelScale(
    val minimum: Double,
    val maximum: Double,
    val current: Double?,
    val unit: String,
    val accent: Int,
)

class FishingDashboardView(context: Context) : View(context) {
    var onVoiceLog: (() -> Unit)? = null
    var onQuickPin: (() -> Unit)? = null
    var onWaterLock: (() -> Unit)? = null
    var onMob: (() -> Unit)? = null
    var onCancelMob: (() -> Unit)? = null
    var onReturnNavigation: (() -> Unit)? = null
    var onManualCast: (() -> Unit)? = null

    private val cyan = Color.rgb(70, 255, 235)
    private val electricBlue = Color.rgb(58, 170, 255)
    private val cyanDim = Color.rgb(20, 87, 87)
    private val orange = Color.rgb(255, 118, 45)
    private val red = Color.rgb(255, 55, 55)
    private val background = Color.rgb(1, 10, 15)
    private val panel = Color.rgb(5, 31, 37)
    private val white = Color.rgb(240, 255, 253)
    private val muted = Color.rgb(128, 172, 177)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val graphPath = Path()
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var pressureHpa: Float? = null
    private var pressureSamples: List<FishingPressureSample> = emptyList()
    private var position: GeoPoint? = null
    private var tideForecast: TideForecast? = null
    private var tideMessage: String? = null
    private var waterTemperatureCelsius: Float? = null
    private var saltWater = true
    private var telemetry = NavigationTelemetry()
    private var listening = false
    private var waterLocked = false
    private var mobActive = false
    private var notice: String? = null
    private var noticeUntilMillis = 0L
    private var selectedIndex = 0
    private var castStats = FishingCastStats(0, 0)
    private var touchDownY = 0f
    private var lastRotaryMillis = 0L
    private val metricPreferences = context.getSharedPreferences(METRIC_PREFERENCES, Context.MODE_PRIVATE)

    internal fun metricOptions(): List<FishingMetricOption> {
        val enabled = enabledMetricNames()
        return FishingMetric.entries.map {
            FishingMetricOption(it.name, context.getString(it.titleRes), it.name in enabled)
        }
    }

    internal fun setMetricEnabled(id: String, enabled: Boolean): Boolean {
        val metric = FishingMetric.entries.firstOrNull { it.name == id } ?: return false
        val names = enabledMetricNames().toMutableSet()
        if (enabled) names += metric.name else names -= metric.name
        if (names.isEmpty()) return false
        metricPreferences.edit().putStringSet(KEY_ENABLED_METRICS, names).apply()
        selectedIndex = selectedIndex.coerceIn(0, availableMetrics().lastIndex)
        invalidate()
        return true
    }

    private fun enabledMetricNames(): Set<String> = metricPreferences
        .getStringSet(KEY_ENABLED_METRICS, null)
        ?.filterTo(linkedSetOf()) { name -> FishingMetric.entries.any { it.name == name } }
        ?.takeIf { it.isNotEmpty() }
        ?: FishingMetric.entries.mapTo(linkedSetOf()) { it.name }

    private fun availableMetrics(): List<FishingMetric> = FishingMetric.entries.filter { it.name in enabledMetricNames() }

    private fun selectedMetric(): FishingMetric {
        val available = availableMetrics()
        selectedIndex = selectedIndex.coerceIn(0, available.lastIndex)
        return available[selectedIndex]
    }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    fun setTelemetry(value: NavigationTelemetry) {
        telemetry = value
        invalidate()
    }

    fun setPressure(value: Float, samples: List<FishingPressureSample>) {
        pressureHpa = value
        pressureSamples = (samples + FishingPressureSample(System.currentTimeMillis(), value))
            .distinctBy(FishingPressureSample::timestampMillis)
            .takeLast(48)
        invalidate()
    }

    fun setPosition(value: GeoPoint) {
        position = value
        invalidate()
    }

    fun setTideForecast(value: TideForecast?) {
        tideForecast = value
        tideMessage = null
        invalidate()
    }

    fun setTideMessage(value: String) {
        tideMessage = value
        invalidate()
    }

    fun setWaterProfile(temperatureCelsius: Float?, isSaltWater: Boolean) {
        waterTemperatureCelsius = temperatureCelsius
        saltWater = isSaltWater
        invalidate()
    }

    fun setCastStats(value: FishingCastStats) {
        castStats = value
        invalidate()
    }

    fun setListening(value: Boolean) {
        listening = value
        invalidate()
    }

    fun setWaterLocked(value: Boolean) {
        waterLocked = value
        invalidate()
    }

    fun setMobActive(value: Boolean) {
        mobActive = value
        invalidate()
    }

    fun showNotice(value: String) {
        notice = value
        noticeUntilMillis = System.currentTimeMillis() + 6_000L
        invalidate()
    }

    fun onRotary(scroll: Float) {
        val now = System.currentTimeMillis()
        if (scroll == 0f || now - lastRotaryMillis < 55L) return
        lastRotaryMillis = now
        selectRelative(if (scroll > 0f) -1 else 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(background)
        if (mobActive) {
            drawMob(canvas)
            return
        }
        val snapshot = snapshot()
        drawOuterIndicator(canvas, snapshot)
        drawBackButton(canvas)
        drawMetricMenu(canvas, snapshot)
        drawSelectedMetric(canvas, snapshot)
        drawQuickControls(canvas)
        drawNotice(canvas)
    }

    private fun snapshot(): FishingDashboardSnapshot {
        val now = System.currentTimeMillis()
        val solunar = FishingMath.solunar(now, position?.longitude ?: 0.0)
        val pressureDelta = FishingMath.pressureDelta(pressureSamples, now)
        val elevation = telemetry.currentElevationMeters ?: position?.elevationMeters
        val decision = FishingDecisionEngine.calculate(
            solunarLevel = solunar.activityLevel,
            ambientPressureHpa = pressureHpa,
            pressureDelta3hHpa = pressureDelta,
            elevationMeters = elevation,
            tideStage = tideForecast?.stage(now),
            waterTemperatureCelsius = waterTemperatureCelsius,
            salinityPpt = if (saltWater) 35f else 0f,
        )
        return FishingDashboardSnapshot(
            nowMillis = now,
            solunar = solunar,
            pressureDelta3h = pressureDelta,
            decision = decision,
            currentTideHeightMeters = tideForecast?.heightAt(now),
            solar = position?.let { FishingSolarMath.window(now, it.latitude, it.longitude) },
        )
    }

    private fun drawBackButton(canvas: Canvas) {
        val cx = width * .29f
        val cy = height * .15f
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawCircle(cx, cy, min(width, height) * .052f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = cyan
        canvas.drawArc(RectF(cx - dp(10f), cy - dp(9f), cx + dp(10f), cy + dp(9f)), 145f, 245f, false, paint)
        graphPath.reset()
        graphPath.moveTo(cx - dp(11f), cy - dp(1f))
        graphPath.lineTo(cx - dp(4f), cy - dp(8f))
        graphPath.lineTo(cx - dp(3f), cy + dp(2f))
        paint.style = Paint.Style.FILL
        canvas.drawPath(graphPath, paint)
    }

    private fun drawMetricMenu(canvas: Canvas, snapshot: FishingDashboardSnapshot) {
        val metric = selectedMetric()
        val centerY = height * .49f
        val bounds = RectF(width * .055f, centerY - height * .085f, width * .36f, centerY + height * .085f)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(8, 58, 62)
        canvas.drawRoundRect(bounds, dp(12f), dp(12f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = cyan
        canvas.drawRoundRect(bounds, dp(12f), dp(12f), paint)
        text(canvas, context.getString(metric.shortRes), bounds.centerX(), centerY + sp(6f), 17f, white, Paint.Align.CENTER, true)
        text(canvas, context.getString(R.string.fishing_metric_scroll_hint), bounds.centerX(), height * .625f, 7.5f, muted, Paint.Align.CENTER)
    }

    private fun drawMiniMetric(
        canvas: Canvas,
        metric: FishingMetric,
        bounds: RectF,
        snapshot: FishingDashboardSnapshot,
        selected: Boolean,
    ) {
        val color = if (selected) cyan else cyanDim
        when (metric) {
            FishingMetric.BARO -> drawPressureGraph(canvas, bounds, color, dp(if (selected) 2f else 1f))
            FishingMetric.OXYGEN -> {
                val value = snapshot.decision.theoreticalOxygenSaturationMgL
                val progress = value?.let { ((it - 4.0) / 6.0).coerceIn(0.0, 1.0).toFloat() } ?: 0f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(3f)
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = cyanDim
                canvas.drawLine(bounds.left, bounds.centerY(), bounds.right, bounds.centerY(), paint)
                paint.color = if (value != null && value < 6.0) red else color
                canvas.drawLine(bounds.left, bounds.centerY(), bounds.left + bounds.width() * progress, bounds.centerY(), paint)
                repeat(3) { index ->
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1f)
                    canvas.drawCircle(bounds.left + bounds.width() * (.30f + index * .22f), bounds.centerY() - dp((index % 2) * 5f), dp(2.5f + index), paint)
                }
            }
            FishingMetric.TIDE -> tideForecast?.let { drawTideGraph(canvas, bounds, it, snapshot.nowMillis, color) }
                ?: text(canvas, "--", bounds.centerX(), bounds.centerY() + sp(4f), 11f, muted, Paint.Align.CENTER)
            FishingMetric.ACTIVITY -> {
                repeat(4) { index ->
                    paint.style = if (index < snapshot.solunar.activityLevel) Paint.Style.FILL else Paint.Style.STROKE
                    paint.strokeWidth = dp(1f)
                    paint.color = if (index < snapshot.solunar.activityLevel) color else cyanDim
                    canvas.drawOval(
                        RectF(
                            bounds.left + index * bounds.width() / 4f,
                            bounds.centerY() - dp(3f),
                            bounds.left + index * bounds.width() / 4f + dp(9f),
                            bounds.centerY() + dp(3f),
                        ),
                        paint,
                    )
                }
            }
            FishingMetric.SUN -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2f)
                paint.color = orange
                canvas.drawArc(bounds, 200f, 140f, false, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(bounds.centerX(), bounds.centerY() - bounds.height() * .28f, dp(3f), paint)
                paint.color = color
                canvas.drawLine(bounds.left, bounds.bottom - dp(3f), bounds.right, bounds.bottom - dp(3f), paint)
            }
            FishingMetric.CASTS -> {
                val progress = (castStats.sinceLastMark / 30f).coerceIn(0f, 1f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(3f)
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = cyanDim
                canvas.drawArc(bounds, 180f, 180f, false, paint)
                paint.color = if (progress >= 1f) orange else color
                canvas.drawArc(bounds, 180f, 180f * progress, false, paint)
            }
            FishingMetric.ELEVATION -> {
                val progress = if (telemetry.totalAscentMeters > 0.0) {
                    ((telemetry.totalAscentMeters - telemetry.remainingAscentMeters) /
                        telemetry.totalAscentMeters).toFloat().coerceIn(0f, 1f)
                } else 0f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(3f)
                paint.color = cyanDim
                canvas.drawLine(bounds.left, bounds.bottom, bounds.right, bounds.bottom, paint)
                paint.color = color
                canvas.drawLine(bounds.left, bounds.bottom, bounds.left + bounds.width() * progress, bounds.top, paint)
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawSelectedMetric(canvas: Canvas, snapshot: FishingDashboardSnapshot) {
        val metric = selectedMetric()
        val presentation = presentation(metric, snapshot)
        text(canvas, context.getString(metric.shortRes), width * .64f, height * .145f, 13f, presentation.accent, Paint.Align.CENTER, true)
        text(canvas, context.getString(metric.titleRes), width * .62f, height * .19f, 9f, muted, Paint.Align.CENTER)

        val maxValueWidth = width * .52f
        val valueSize = fittedTextSize(presentation.value, maxValueWidth, 56f, 29f)
        text(canvas, presentation.value, width * .64f, height * .445f, valueSize, white, Paint.Align.CENTER, true)
        text(canvas, presentation.unit, width * .64f, height * .505f, 13f, presentation.accent, Paint.Align.CENTER, true)
        fittedText(canvas, presentation.primaryDetail, width * .64f, height * .59f, width * .50f, 10f, 7.5f, presentation.accent, true)
        fittedText(canvas, presentation.secondaryDetail, width * .64f, height * .645f, width * .50f, 9f, 7f, muted, false)
    }

    private fun presentation(metric: FishingMetric, snapshot: FishingDashboardSnapshot): MetricPresentation {
        val period = when (snapshot.solunar.period) {
            SolunarPeriod.MAJOR -> context.getString(R.string.fishing_solunar_major)
            SolunarPeriod.MAJOR_SHOULDER -> context.getString(R.string.fishing_solunar_major_shoulder)
            SolunarPeriod.MINOR -> context.getString(R.string.fishing_solunar_minor)
            SolunarPeriod.QUIET -> context.getString(R.string.fishing_solunar_quiet)
        }
        val advice = when (snapshot.decision.advice) {
            FishingAdvice.STORM_RETURN -> context.getString(R.string.fishing_advice_storm)
            FishingAdvice.SLACK_WINDOW -> context.getString(R.string.fishing_advice_slack)
            FishingAdvice.PRESSURE_FALLING -> context.getString(R.string.fishing_advice_falling)
            FishingAdvice.PRESSURE_RISING -> context.getString(R.string.fishing_advice_rising)
            FishingAdvice.NORMAL -> context.getString(R.string.fishing_advice_normal)
        }
        return when (metric) {
            FishingMetric.BARO -> {
                val delta = snapshot.pressureDelta3h
                val trend = when {
                    delta == null -> context.getString(R.string.fishing_pressure_collecting)
                    delta > .4f -> context.getString(R.string.fishing_pressure_rising, delta)
                    delta < -.4f -> context.getString(R.string.fishing_pressure_falling, -delta)
                    else -> context.getString(R.string.fishing_pressure_stable)
                }
                MetricPresentation(
                    pressureHpa?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "--",
                    "hPa",
                    trend,
                    advice,
                    if (delta != null && delta < -2f) if (delta < -4f) red else orange else cyan,
                )
            }
            FishingMetric.OXYGEN -> {
                val oxygen = snapshot.decision.theoreticalOxygenSaturationMgL
                MetricPresentation(
                    oxygen?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "--",
                    "mg/L",
                    if (oxygen == null) context.getString(R.string.fishing_metric_oxygen_missing)
                    else context.getString(R.string.fishing_metric_theoretical),
                    waterTemperatureCelsius?.let {
                        String.format(Locale.getDefault(), "%.1f°C · %s", it, if (saltWater) context.getString(R.string.fishing_water_salt) else context.getString(R.string.fishing_water_fresh))
                    } ?: advice,
                    if (oxygen != null && oxygen < 6.0) red else electricBlue,
                )
            }
            FishingMetric.TIDE -> {
                val forecast = tideForecast
                val next = forecast?.nextExtreme(snapshot.nowMillis)
                val stage = forecast?.stage(snapshot.nowMillis)
                val stageLabel = when (stage) {
                    TideStage.FLOOD -> context.getString(R.string.fishing_tide_flood)
                    TideStage.EBB -> context.getString(R.string.fishing_tide_ebb)
                    TideStage.SLACK_REFERENCE -> context.getString(R.string.fishing_tide_slack_golden)
                    null -> tideMessage ?: context.getString(R.string.fishing_metric_tide_missing)
                }
                MetricPresentation(
                    snapshot.currentTideHeightMeters?.let { String.format(Locale.getDefault(), "%+.2f", it) } ?: "--",
                    "m",
                    if (forecast == null) stageLabel else context.getString(R.string.fishing_metric_tide_height) + " · " + stageLabel,
                    next?.let {
                        context.getString(
                            if (it.high) R.string.fishing_metric_next_high else R.string.fishing_metric_next_low,
                            durationLabel(it.timestampMillis - snapshot.nowMillis),
                        )
                    } ?: context.getString(R.string.fishing_metric_tide_missing),
                    if (stage == TideStage.SLACK_REFERENCE) orange else cyan,
                )
            }
            FishingMetric.ACTIVITY -> MetricPresentation(
                snapshot.decision.index.toString(),
                "%",
                context.getString(R.string.fishing_metric_activity_detail, snapshot.solunar.activityLevel, period),
                advice,
                if (snapshot.solunar.activityLevel >= 4) orange else cyan,
            )
            FishingMetric.SUN -> {
                val next = nextSolarEvent(snapshot.nowMillis)
                MetricPresentation(
                    next?.let { clock.format(Date(it.first)) } ?: "--:--",
                    next?.let {
                        context.getString(if (it.second) R.string.fishing_metric_sunrise_unit else R.string.fishing_metric_sunset_unit)
                    }.orEmpty(),
                    when {
                        position == null -> context.getString(R.string.fishing_metric_sun_location)
                        next == null && snapshot.solar?.sunriseMillis == null -> context.getString(R.string.fishing_metric_polar_night)
                        next == null -> context.getString(R.string.fishing_metric_polar_day)
                        else -> context.getString(
                            if (next.second) R.string.fishing_metric_next_sunrise else R.string.fishing_metric_next_sunset,
                            durationLabel(next.first - snapshot.nowMillis),
                        )
                    },
                    context.getString(R.string.fishing_metric_magic_hour),
                    orange,
                )
            }
            FishingMetric.CASTS -> MetricPresentation(
                castStats.totalToday.toString(),
                context.getString(R.string.fishing_metric_cast_unit),
                context.getString(R.string.fishing_metric_cast_total),
                context.getString(R.string.fishing_metric_cast_since, castStats.sinceLastMark) + " · " + context.getString(R.string.fishing_metric_cast_correct),
                if (castStats.sinceLastMark >= 30) orange else cyan,
            )
            FishingMetric.ELEVATION -> MetricPresentation(
                (telemetry.currentElevationMeters ?: position?.elevationMeters)
                    ?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "--",
                "m",
                context.getString(
                    R.string.fishing_metric_elevation_grade,
                    telemetry.gradePercent ?: 0.0,
                    telemetry.verticalSpeedMetersPerHour ?: 0.0,
                ),
                context.getString(
                    R.string.fishing_metric_elevation_ascent,
                    telemetry.remainingAscentMeters,
                    telemetry.totalAscentMeters,
                ),
                electricBlue,
            )
        }
    }

    private fun drawOuterIndicator(canvas: Canvas, snapshot: FishingDashboardSnapshot) {
        val inset = dp(8f)
        val bounds = RectF(inset, inset, width - inset, height - inset)
        val scale = bezelScale(snapshot)
        val startAngle = 125f
        val sweepAngle = 270f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(5f)
        paint.shader = null
        paint.color = Color.rgb(10, 49, 54)
        canvas.drawArc(bounds, startAngle, sweepAngle, false, paint)
        val progress = scale.current?.let {
            ((it - scale.minimum) / (scale.maximum - scale.minimum)).coerceIn(0.0, 1.0).toFloat()
        }
        if (progress != null) {
            paint.color = scale.accent
            canvas.drawArc(bounds, startAngle, sweepAngle * progress, false, paint)
            drawArcPointer(canvas, bounds, startAngle + sweepAngle * progress, scale.accent)
        }
        drawBezelTicks(canvas, bounds, scale, startAngle, sweepAngle)
        paint.shader = null
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun bezelScale(snapshot: FishingDashboardSnapshot): BezelScale = when (selectedMetric()) {
        FishingMetric.BARO -> BezelScale(
            -4.0, 4.0, snapshot.pressureDelta3h?.toDouble(),
            if (AppLanguageController.current(context) == AppLanguage.CHINESE) "hPa/3小时" else "hPa/3h",
            when {
                snapshot.pressureDelta3h == null -> muted
                snapshot.pressureDelta3h < -4f -> red
                snapshot.pressureDelta3h < -2f -> orange
                else -> cyan
            },
        )
        FishingMetric.OXYGEN -> BezelScale(
            4.0, 12.0, snapshot.decision.theoreticalOxygenSaturationMgL, "mg/L",
            if ((snapshot.decision.theoreticalOxygenSaturationMgL ?: 8.0) < 6.0) red else electricBlue,
        )
        FishingMetric.TIDE -> {
            val points = tideForecast?.points.orEmpty()
            val minimum = points.minOfOrNull(TidePoint::heightMeters)?.toDouble() ?: -2.0
            val maximum = points.maxOfOrNull(TidePoint::heightMeters)?.toDouble() ?: 2.0
            BezelScale(minimum, max(maximum, minimum + .1), snapshot.currentTideHeightMeters?.toDouble(), "m", cyan)
        }
        FishingMetric.ACTIVITY -> BezelScale(0.0, 100.0, snapshot.decision.index.toDouble(), "%", if (snapshot.decision.index >= 85) orange else cyan)
        FishingMetric.SUN -> {
            val local = Instant.ofEpochMilli(snapshot.nowMillis).atZone(ZoneId.systemDefault())
            BezelScale(0.0, 24.0, local.hour + local.minute / 60.0, if (AppLanguageController.current(context) == AppLanguage.CHINESE) "时" else "h", orange)
        }
        FishingMetric.CASTS -> BezelScale(0.0, 30.0, castStats.sinceLastMark.toDouble().coerceAtMost(30.0), context.getString(R.string.fishing_metric_cast_unit), if (castStats.sinceLastMark >= 30) orange else cyan)
        FishingMetric.ELEVATION -> BezelScale(
            0.0,
            max(1.0, telemetry.totalAscentMeters),
            (telemetry.totalAscentMeters - telemetry.remainingAscentMeters).coerceAtLeast(0.0),
            "m",
            electricBlue,
        )
    }

    private fun drawBezelTicks(canvas: Canvas, bounds: RectF, scale: BezelScale, startAngle: Float, sweepAngle: Float) {
        val outerRadius = bounds.width() / 2f
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        repeat(7) { index ->
            val fraction = index / 6f
            val angle = startAngle + sweepAngle * fraction
            val radians = Math.toRadians(angle.toDouble())
            val cosine = kotlin.math.cos(radians).toFloat()
            val sine = kotlin.math.sin(radians).toFloat()
            val inner = outerRadius - dp(if (index % 3 == 0) 12f else 8f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(if (index % 3 == 0) 1.8f else 1f)
            paint.color = if (index % 3 == 0) white else muted
            canvas.drawLine(centerX + cosine * inner, centerY + sine * inner, centerX + cosine * (outerRadius - dp(2f)), centerY + sine * (outerRadius - dp(2f)), paint)
            if (index % 3 == 0) {
                val value = scale.minimum + (scale.maximum - scale.minimum) * fraction
                val labelRadius = outerRadius - dp(22f)
                val label = if (abs(value) >= 10 || value % 1.0 == 0.0) String.format(Locale.getDefault(), "%.0f", value)
                else String.format(Locale.getDefault(), "%.1f", value)
                text(canvas, label, centerX + cosine * labelRadius, centerY + sine * labelRadius + sp(2.5f), 7f, muted, Paint.Align.CENTER)
            }
        }
        fittedText(canvas, scale.unit, width * .50f, height * .055f, width * .22f, 7.5f, 6f, muted, true)
    }

    private fun drawSunRing(canvas: Canvas, bounds: RectF, snapshot: FishingDashboardSnapshot) {
        val solar = snapshot.solar ?: return
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(snapshot.nowMillis).atZone(zone).toLocalDate()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        fun angle(time: Long): Float = -90f + ((time - start).toFloat() / 86_400_000f) * 360f
        val dawn = solar.civilDawnMillis
        val sunrise = solar.sunriseMillis
        val sunset = solar.sunsetMillis
        val dusk = solar.civilDuskMillis
        if (dawn != null && sunrise != null) {
            paint.color = orange
            canvas.drawArc(bounds, angle(dawn), angle(sunrise) - angle(dawn), false, paint)
        }
        if (sunrise != null && sunset != null) {
            paint.color = Color.rgb(200, 224, 216)
            canvas.drawArc(bounds, angle(sunrise), angle(sunset) - angle(sunrise), false, paint)
        }
        if (sunset != null && dusk != null) {
            paint.color = orange
            canvas.drawArc(bounds, angle(sunset), angle(dusk) - angle(sunset), false, paint)
        }
        val nowAngle = angle(snapshot.nowMillis)
        drawArcPointer(canvas, bounds, nowAngle, cyan)
    }

    private fun drawArcPointer(canvas: Canvas, bounds: RectF, angleDegrees: Float, color: Int) {
        val radians = Math.toRadians(angleDegrees.toDouble())
        val radius = bounds.width() / 2f
        val x = bounds.centerX() + kotlin.math.cos(radians).toFloat() * radius
        val y = bounds.centerY() + kotlin.math.sin(radians).toFloat() * radius
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color
        canvas.drawCircle(x, y, dp(4f), paint)
        paint.style = Paint.Style.STROKE
    }

    private fun drawQuickControls(canvas: Canvas) {
        val cx = width * .50f
        val cy = height * .855f
        paint.style = Paint.Style.FILL
        paint.color = if (listening) red else panel
        canvas.drawCircle(cx, cy, dp(22f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(2f)
        paint.color = if (listening) white else orange
        canvas.drawCircle(cx, cy, dp(22f), paint)
        canvas.drawRoundRect(RectF(cx - dp(5f), cy - dp(11f), cx + dp(5f), cy + dp(5f)), dp(5f), dp(5f), paint)
        canvas.drawArc(RectF(cx - dp(10f), cy - dp(3f), cx + dp(10f), cy + dp(11f)), 0f, 180f, false, paint)
        canvas.drawLine(cx, cy + dp(11f), cx, cy + dp(15f), paint)
        canvas.drawLine(cx - dp(6f), cy + dp(15f), cx + dp(6f), cy + dp(15f), paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawNotice(canvas: Canvas) {
        val activeNotice = notice?.takeIf { System.currentTimeMillis() < noticeUntilMillis } ?: return
        val bounds = RectF(width * .25f, height * .70f, width * .88f, height * .78f)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(18, 89, 91)
        canvas.drawRoundRect(bounds, dp(12f), dp(12f), paint)
        fittedText(canvas, activeNotice, bounds.centerX(), bounds.centerY() + sp(3f), bounds.width() - dp(10f), 9f, 7f, white, true)
    }

    private fun drawPressureGraph(canvas: Canvas, bounds: RectF, color: Int, stroke: Float) {
        val samples = pressureSamples.filter { it.timestampMillis >= System.currentTimeMillis() - 3 * 60 * 60 * 1_000L }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = cyanDim
        canvas.drawLine(bounds.left, bounds.centerY(), bounds.right, bounds.centerY(), paint)
        if (samples.size < 2) return
        val minPressure = samples.minOf { it.pressureHpa }
        val maxPressure = samples.maxOf { it.pressureHpa }
        val range = max(1f, maxPressure - minPressure)
        val firstTime = samples.first().timestampMillis
        val timeRange = max(1L, samples.last().timestampMillis - firstTime)
        graphPath.reset()
        samples.forEachIndexed { index, sample ->
            val x = bounds.left + (sample.timestampMillis - firstTime).toFloat() / timeRange * bounds.width()
            val y = bounds.bottom - (sample.pressureHpa - minPressure) / range * bounds.height()
            if (index == 0) graphPath.moveTo(x, y) else graphPath.lineTo(x, y)
        }
        paint.strokeWidth = stroke
        paint.color = color
        canvas.drawPath(graphPath, paint)
    }

    private fun drawTideGraph(canvas: Canvas, bounds: RectF, forecast: TideForecast, nowMillis: Long, color: Int) {
        val points = forecast.points.filter { it.timestampMillis in (nowMillis - 6 * 60 * 60 * 1_000L)..(nowMillis + 6 * 60 * 60 * 1_000L) }
        if (points.size < 2) return
        val minHeight = points.minOf(TidePoint::heightMeters)
        val maxHeight = points.maxOf(TidePoint::heightMeters)
        val range = max(.1f, maxHeight - minHeight)
        val firstTime = points.first().timestampMillis
        val timeRange = max(1L, points.last().timestampMillis - firstTime)
        graphPath.reset()
        points.forEachIndexed { index, point ->
            val x = bounds.left + (point.timestampMillis - firstTime).toFloat() / timeRange * bounds.width()
            val y = bounds.bottom - (point.heightMeters - minHeight) / range * bounds.height()
            if (index == 0) graphPath.moveTo(x, y) else graphPath.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = color
        canvas.drawPath(graphPath, paint)
    }

    private fun TideForecast.heightAt(nowMillis: Long): Float? {
        val before = points.lastOrNull { it.timestampMillis <= nowMillis }
        val after = points.firstOrNull { it.timestampMillis > nowMillis }
        if (before == null) return after?.heightMeters
        if (after == null) return before.heightMeters
        val range = (after.timestampMillis - before.timestampMillis).coerceAtLeast(1L)
        val fraction = (nowMillis - before.timestampMillis).toFloat() / range
        return before.heightMeters + (after.heightMeters - before.heightMeters) * fraction
    }

    private fun tideCycleProgress(nowMillis: Long): Float {
        val forecast = tideForecast ?: return .02f
        val previous = forecast.extremes.lastOrNull { it.timestampMillis <= nowMillis }
        val next = forecast.extremes.firstOrNull { it.timestampMillis > nowMillis }
        if (previous == null || next == null) return .02f
        return ((nowMillis - previous.timestampMillis).toFloat() / (next.timestampMillis - previous.timestampMillis).coerceAtLeast(1L))
            .coerceIn(0f, 1f)
    }

    private fun nextSolarEvent(nowMillis: Long): Pair<Long, Boolean>? {
        val point = position ?: return null
        val today = FishingSolarMath.window(nowMillis, point.latitude, point.longitude)
        listOfNotNull(today.sunriseMillis?.let { it to true }, today.sunsetMillis?.let { it to false })
            .filter { it.first > nowMillis }
            .minByOrNull { it.first }
            ?.let { return it }
        val tomorrow = FishingSolarMath.window(nowMillis + 86_400_000L, point.latitude, point.longitude)
        return listOfNotNull(tomorrow.sunriseMillis?.let { it to true }, tomorrow.sunsetMillis?.let { it to false })
            .filter { it.first > nowMillis }
            .minByOrNull { it.first }
    }

    private fun durationLabel(durationMillis: Long): String {
        val minutes = (durationMillis.coerceAtLeast(0L) / 60_000L)
        return if (minutes >= 60) context.getString(R.string.fishing_duration_hours, minutes / 60, minutes % 60)
        else context.getString(R.string.fishing_duration_minutes, minutes)
    }

    private fun drawMob(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(76, 0, 0)
        canvas.drawCircle(w / 2, h / 2, min(w, h) * .47f, paint)
        text(canvas, "MOB", w / 2, h * .30f, 42f, Color.WHITE, Paint.Align.CENTER, true)
        fittedText(canvas, context.getString(R.string.fishing_mob_active), w / 2, h * .42f, w * .72f, 18f, 12f, Color.WHITE, true)
        fittedText(canvas, context.getString(R.string.fishing_mob_tracback), w / 2, h * .54f, w * .72f, 12f, 8f, Color.rgb(255, 205, 205), false)
        paint.color = orange
        val cancel = RectF(w * .28f, h * .66f, w * .72f, h * .78f)
        canvas.drawRoundRect(cancel, dp(16f), dp(16f), paint)
        text(canvas, context.getString(R.string.fishing_cancel_mob), cancel.centerX(), cancel.centerY() + sp(5f), 12f, Color.WHITE, Paint.Align.CENTER, true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (mobActive) {
                    if (event.y / height in .62f..86f) onCancelMob?.invoke()
                    return true
                }
                val x = event.x / width
                val y = event.y / height
                val verticalTravel = event.y - touchDownY
                when {
                    x < .40f && abs(verticalTravel) > dp(18f) -> selectRelative(if (verticalTravel > 0f) -1 else 1)
                    x < .42f && y < .25f -> onReturnNavigation?.invoke()
                    x < .39f && y in .38f..62f -> selectRelative(1)
                    y > .78f && x in .38f..62f -> onVoiceLog?.invoke()
                    selectedMetric() == FishingMetric.CASTS && x > .39f && y in .22f..70f -> onManualCast?.invoke()
                }
                return true
            }
        }
        return true
    }

    private fun selectRelative(delta: Int) {
        selectedIndex = floorMod(selectedIndex + delta, availableMetrics().size)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        postInvalidateOnAnimation()
    }

    private fun fittedText(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        maxSizeSp: Float,
        minSizeSp: Float,
        color: Int,
        bold: Boolean,
    ) = text(canvas, value, x, y, fittedTextSize(value, maxWidth, maxSizeSp, minSizeSp), color, Paint.Align.CENTER, bold)

    private fun fittedTextSize(value: String, maxWidth: Float, maxSizeSp: Float, minSizeSp: Float): Float {
        var size = maxSizeSp
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        while (size > minSizeSp) {
            paint.textSize = sp(size)
            if (paint.measureText(value) <= maxWidth) return size
            size -= .5f
        }
        return minSizeSp
    }

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        sizeSp: Float,
        color: Int,
        align: Paint.Align,
        bold: Boolean = false,
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textAlign = align
        paint.textSize = sp(sizeSp)
        paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        canvas.drawText(value, x, y, paint)
    }

    private fun floorMod(value: Int, divisor: Int): Int = ((value % divisor) + divisor) % divisor

    private companion object {
        const val METRIC_PREFERENCES = "fishing_metric_preferences"
        const val KEY_ENABLED_METRICS = "enabled_metrics"
    }
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
