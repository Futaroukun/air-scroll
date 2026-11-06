package com.airscroll.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.airscroll.app.tracking.GestureType

class AirAccessibilityService : AccessibilityService() {

    private val tag = "AirAccessibility"

    companion object {
        var instance: AirAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(tag, "AirAccessibilityService connected and ready for gestures.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive listener
    }

    override fun onInterrupt() {
        Log.w(tag, "AirAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(tag, "AirAccessibilityService destroyed.")
    }

    fun performScroll(type: GestureType, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (type == GestureType.NONE) return

        val displayMetrics = resources.displayMetrics
        val width = if (displayMetrics.widthPixels > 0) displayMetrics.widthPixels.toFloat() else 1080f
        val height = if (displayMetrics.heightPixels > 0) displayMetrics.heightPixels.toFloat() else 2400f

        val centerX = width / 2f
        val path = Path()
        val gestureDuration = 220L

        when (type) {
            GestureType.SCROLL_DOWN -> {
                // Swipe upwards on screen (bottom to top) to advance to next post
                val startY = height * 0.75f
                val endY = height * 0.20f
                path.moveTo(centerX, startY)
                path.lineTo(centerX, endY)
            }
            GestureType.SCROLL_UP -> {
                // Swipe downwards on screen (top to bottom) to return to previous post
                val startY = height * 0.25f
                val endY = height * 0.80f
                path.moveTo(centerX, startY)
                path.lineTo(centerX, endY)
            }
            else -> return
        }

        val stroke = GestureDescription.StrokeDescription(path, 10L, gestureDuration)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(tag, "Gesture $type dispatched successfully.")
                onResult(true, "OK")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(tag, "Gesture $type cancelled by system.")
                onResult(false, "Batal")
            }
        }, null)

        if (!dispatched) {
            Log.e(tag, "dispatchGesture returned false (disallowed or not ready).")
            onResult(false, "Ditolak")
        }
    }
}
