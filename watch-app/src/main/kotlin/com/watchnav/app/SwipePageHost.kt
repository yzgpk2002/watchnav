package com.watchnav.app

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Hosts full-screen pages without competing with the system edge-back gesture.
 * Swipe paging is opt-in so round Samsung watches do not accidentally leave the app.
 */
class SwipePageHost(context: Context) : FrameLayout(context) {
    var onPageSwipe: ((direction: Int) -> Unit)? = null
    var swipeEnabled: Boolean = false

    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var intercepting = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tracking = true
                intercepting = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_MOVE -> if (tracking && event.pointerCount == 1) {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) >= swipeInterceptPixels && abs(dx) > abs(dy) * 1.35f) {
                    intercepting = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> tracking = false
        }
        return intercepting || super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (intercepting && abs(dx) >= swipeCommitPixels && abs(dx) > abs(dy)) {
                    onPageSwipe?.invoke(if (dx < 0f) 1 else -1)
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                tracking = false
                intercepting = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                intercepting = false
                return true
            }
        }
        return true
    }

    private val swipeInterceptPixels: Float
        get() = 24f * resources.displayMetrics.density

    private val swipeCommitPixels: Float
        get() = 62f * resources.displayMetrics.density
}
