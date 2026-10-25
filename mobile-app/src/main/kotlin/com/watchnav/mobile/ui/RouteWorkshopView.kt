package com.watchnav.mobile.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.watchnav.core.Course
import com.watchnav.core.CoursePointMetrics
import com.watchnav.core.CoursePointRole
import com.watchnav.core.PreparedCourse
import com.watchnav.mobile.R
import kotlin.math.roundToInt

class RouteWorkshopView(context: Context) : FrameLayout(context) {
    val mapHost = WorkshopMapHost(context)
    val watchStatus: TextView
    val searchInput: EditText
    val routeName: TextView
    val distanceText: TextView
    val durationText: TextView
    val startText: TextView
    val semanticText: TextView
    val dependencyText: TextView
    val statusText: TextView
    val importButton: Button
    val sendButton: Button
    val shareButton: Button
    var onLayers: () -> Unit = {}
    var onRoutes: () -> Unit = {}
    var onMarks: () -> Unit = {}
    var onMeasure: () -> Unit = {}
    var onWatchData: () -> Unit = {}
    var onCenter: () -> Unit = {}
    private lateinit var toolMenu: LinearLayout
    private lateinit var menuToggle: Button
    private var menuExpanded = true

    init {
        setBackgroundColor(BLACK)
        addView(mapHost, LayoutParams(-1, -1).apply {
            topMargin = dp(126)
            bottomMargin = dp(258)
        })

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
            setBackgroundColor(Color.argb(242, 4, 7, 8))
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            setText(R.string.route_workshop)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        watchStatus = TextView(context).apply {
            setText(R.string.watch_disconnected)
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(Color.rgb(17, 20, 22), Color.rgb(57, 62, 65), 12)
        }
        titleRow.addView(watchStatus)
        header.addView(titleRow, LinearLayout.LayoutParams(-1, -2))
        searchInput = EditText(context).apply {
            setHint(R.string.search_hint)
            setHintTextColor(Color.rgb(145, 149, 153))
            setTextColor(Color.WHITE)
            textSize = 16f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(Color.rgb(16, 20, 22), Color.rgb(61, 66, 69), 14)
        }
        header.addView(searchInput, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) })
        addView(header, LayoutParams(-1, dp(126), Gravity.TOP))

        toolMenu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(4), dp(2), dp(4))
            background = rounded(Color.argb(232, 17, 20, 21), Color.rgb(54, 59, 61), 18)
            listOf(
                R.string.layers to { onLayers() },
                R.string.routes to { onRoutes() },
                R.string.marks to { onMarks() },
                R.string.measure to { onMeasure() },
                R.string.watch_data to { onWatchData() },
                R.string.center to { onCenter() },
            ).forEach { (label, action) ->
                addView(Button(context).apply {
                    setText(label)
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.TRANSPARENT)
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(dp(74), dp(54)))
            }
        }
        addView(toolMenu, LayoutParams(dp(78), dp(334), Gravity.START or Gravity.TOP).apply {
            leftMargin = dp(8)
            topMargin = dp(178)
        })
        menuToggle = Button(context).apply {
            text = "⌃"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            background = rounded(Color.argb(232, 17, 20, 21), Color.rgb(54, 59, 61), 14)
            setOnClickListener { setMenuExpanded(!menuExpanded) }
        }
        addView(menuToggle, LayoutParams(dp(44), dp(40), Gravity.START or Gravity.TOP).apply {
            leftMargin = dp(25)
            topMargin = dp(134)
        })

        val summary = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            background = rounded(Color.rgb(13, 16, 18), Color.rgb(54, 58, 61), 22)
        }
        routeName = TextView(context).apply {
            setText(R.string.no_route)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
        }
        summary.addView(routeName)
        val metrics = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        distanceText = metricText().apply { text = "— km" }
        durationText = metricText().apply { text = "—" }
        metrics.addView(distanceText, LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(durationText, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        startText = TextView(context).apply {
            text = context.getString(R.string.route_start, "—")
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        summary.addView(startText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        semanticText = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.rgb(185, 192, 196))
            maxLines = 1
        }
        summary.addView(semanticText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        dependencyText = TextView(context).apply {
            setText(R.string.dependencies_missing)
            textSize = 14f
            setTextColor(CYAN)
            setPadding(0, dp(8), 0, dp(8))
        }
        summary.addView(dependencyText)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        importButton = actionButton(R.string.receive_route, outlined = true)
        sendButton = actionButton(R.string.send_route, outlined = false)
        shareButton = actionButton(R.string.share_route, outlined = true)
        buttons.addView(importButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(4) })
        buttons.addView(shareButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        buttons.addView(sendButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(4) })
        summary.addView(buttons)
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusText = TextView(context).apply {
            setText(R.string.data_date)
            textSize = 11f
            setTextColor(Color.GRAY)
            gravity = Gravity.END
        }
        bottomRow.addView(statusText, LinearLayout.LayoutParams(-1, -2))
        summary.addView(bottomRow)
        addView(summary, LayoutParams(-1, dp(266), Gravity.BOTTOM).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })
    }

    private fun setMenuExpanded(expanded: Boolean) {
        menuExpanded = expanded
        toolMenu.visibility = if (expanded) View.VISIBLE else View.GONE
        menuToggle.text = if (expanded) "⌃" else "⌄"
    }

    fun renderCourse(course: Course?, dependenciesReady: Boolean) {
        if (course == null) {
            mapHost.setCourse(null)
            routeName.setText(R.string.no_route)
            distanceText.text = "— km"
            durationText.text = "—"
            startText.text = context.getString(R.string.route_start, "—")
            semanticText.text = ""
            dependencyText.setText(R.string.dependencies_missing)
            sendButton.isEnabled = false
            shareButton.isEnabled = false
            return
        }
        mapHost.setCourse(course)
        val prepared = PreparedCourse.prepare(course)
        val kilometers = prepared.totalDistanceMeters / 1_000.0
        val minutes = (kilometers / 4.0 * 60.0).roundToInt().coerceAtLeast(1)
        routeName.text = course.name
        distanceText.text = context.getString(R.string.route_distance, kilometers)
        durationText.text = context.getString(R.string.route_duration, minutes / 60, minutes % 60)
        startText.text = context.getString(
            R.string.route_start,
            course.landmarks.firstOrNull()?.name ?: course.points.firstOrNull()?.name ?: "路线起点",
        )
        val allPoints = course.points + course.landmarks
        val poiCount = course.landmarks.count { it.role != CoursePointRole.SHAPING }
        val viaCount = allPoints.count { it.role == CoursePointRole.VIA }
        val cueCount = course.points.count { it.maneuver != null }
        val sensorCount = course.points.count { it.metrics != CoursePointMetrics() }
        semanticText.text = context.getString(R.string.route_semantics, poiCount, viaCount, cueCount, sensorCount)
        dependencyText.setText(if (dependenciesReady) R.string.dependencies_ready else R.string.dependencies_missing)
        dependencyText.setTextColor(if (dependenciesReady) Color.rgb(54, 211, 120) else CYAN)
        sendButton.isEnabled = true
        shareButton.isEnabled = true
    }

    private fun metricText() = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(PURPLE)
    }

    private fun actionButton(label: Int, outlined: Boolean) = Button(context).apply {
        setText(label)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = rounded(
            if (outlined) Color.rgb(14, 17, 19) else ORANGE,
            if (outlined) Color.rgb(103, 108, 112) else ORANGE,
            13,
        )
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val BLACK = Color.rgb(4, 7, 8)
        val CYAN = Color.rgb(0, 220, 236)
        val ORANGE = Color.rgb(255, 98, 0)
        val PURPLE = Color.rgb(159, 72, 255)
    }
}
