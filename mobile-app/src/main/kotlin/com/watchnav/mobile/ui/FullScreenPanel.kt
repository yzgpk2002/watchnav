package com.watchnav.mobile.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class PanelAction(
    val title: String,
    val detail: String,
    val accent: Int = Color.rgb(0, 220, 236),
    val enabled: Boolean = true,
    val action: () -> Unit,
)

class FullScreenPanel(
    context: Context,
    title: String,
    subtitle: String,
    actions: List<PanelAction>,
    onClose: () -> Unit,
) : FrameLayout(context) {
    init {
        setBackgroundColor(Color.rgb(4, 7, 8))
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(context).apply {
            text = "返回"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(dp(72), dp(50)))
        top.addView(TextView(context).apply {
            text = title
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)
        root.addView(TextView(context).apply {
            text = subtitle
            textSize = 14f
            setTextColor(Color.rgb(174, 183, 188))
            setPadding(dp(6), 0, dp(6), dp(12))
        })
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        actions.forEach { item ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(15, 19, 21)); cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.rgb(50, 57, 60))
                }
                alpha = if (item.enabled) 1f else .45f
                isEnabled = item.enabled
                isClickable = item.enabled
                setOnClickListener { item.action() }
                addView(TextView(context).apply {
                    text = item.title; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(item.accent)
                })
                addView(TextView(context).apply {
                    text = item.detail; textSize = 14f; setTextColor(Color.WHITE)
                    setPadding(0, dp(5), 0, 0)
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        root.addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(root, LayoutParams(-1, -1))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
