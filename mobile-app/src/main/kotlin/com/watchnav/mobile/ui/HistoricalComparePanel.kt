package com.watchnav.mobile.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.watchnav.core.Course
import com.watchnav.mobile.data.OfflineLayer

class HistoricalComparePanel(
    context: Context,
    private val layers: List<OfflineLayer>,
    private val course: Course?,
    private val onClose: () -> Unit,
) : FrameLayout(context), AutoCloseable {
    private val mapHost = FrameLayout(context)
    private val label = TextView(context)
    private var index = 0
    private var map: MbtilesRouteView? = null

    init {
        setBackgroundColor(Color.rgb(4, 7, 8))
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
        }
        header.addView(Button(context).apply {
            text = "返回"; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(dp(72), dp(50)))
        header.addView(label.apply {
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(context).apply {
            text = "切换时期"; isAllCaps = false; setTextColor(Color.rgb(255, 98, 0)); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { if (layers.isNotEmpty()) { index = (index + 1) % layers.size; showLayer() } }
        }, LinearLayout.LayoutParams(dp(100), dp(50)))
        root.addView(header)
        root.addView(mapHost, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(context).apply {
            text = "双指缩放 · 单指平移 · 紫色为当前路线。切换不同时期影像，对比枯水期暴露的沟槽、石坎与旧河道。"
            textSize = 13f; setTextColor(Color.LTGRAY); setPadding(dp(18), dp(10), dp(18), dp(16))
        })
        addView(root, LayoutParams(-1, -1))
        showLayer()
    }

    private fun showLayer() {
        map?.close(); mapHost.removeAllViews()
        val layer = layers[index]
        label.text = listOfNotNull(layer.name, layer.sourceDate).joinToString(" · ")
        map = MbtilesRouteView(context, layer.file, course).also { mapHost.addView(it, LayoutParams(-1, -1)) }
    }

    override fun close() { map?.close(); map = null }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
