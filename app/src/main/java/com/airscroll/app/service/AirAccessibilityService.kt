package com.airscroll.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
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
        Log.i(tag, "AirAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event interception needed, service is used for gesture dispatch
    }

    override fun onInterrupt() {
        Log.w(tag, "AirAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(tag, "AirAccessibilityService destroyed.")
    }

    fun performScroll(type: GestureType) {
        if (type == GestureType.NONE) return

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val centerX = width / 2f
        val path = Path()

        val gestureDuration = 220L

        when (type) {
            GestureType.SCROLL_DOWN -> {
                // Swipe upwards on screen: bottom -> top (advances to next feed item)
                val startY = height * 0.72f
                val endY = height * 0.22f
                path.moveTo(centerX, startY)
                path.lineTo(centerX, endY)
            }
            GestureType.SCROLL_UP -> {
                // Swipe downwards on screen: top -> bottom (returns to previous feed item)
                val startY = height * 0.28f
                val endY = height * 0.78f
                path.moveTo(centerX, startY)
                path.lineTo(centerX, endY)
            }
            else -> return
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, gestureDuration)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(tag, "Gesture $type dispatched successfully.")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(tag, "Gesture $type was cancelled.")
            }
        }, null)
    }
}
