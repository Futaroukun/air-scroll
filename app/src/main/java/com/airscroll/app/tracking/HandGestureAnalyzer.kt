package com.airscroll.app.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.ArrayDeque

class HandGestureAnalyzer(
    context: Context,
    private val onGestureDetected: (GestureType) -> Unit,
    private val onHandPresenceChanged: (Boolean) -> Unit
) : ImageAnalysis.Analyzer {

    private val tag = "HandGestureAnalyzer"
    private var handLandmarker: HandLandmarker? = null

    private data class PointRecord(val y: Float, val timestamp: Long)
    private val history = ArrayDeque<PointRecord>()

    private var lastTriggerTime: Long = 0L

    // Sensitivity tuning
    private val swipeThreshold = 0.11f // 11% vertical delta in normalized frame
    private val windowDurationMs = 380L // Rolling history duration window
    private val gestureCooldownMs = 700L // Delay before next gesture can trigger

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.45f)
                .setMinTrackingConfidence(0.45f)
                .setMinHandPresenceConfidence(0.45f)
                .setNumHands(1)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.d(tag, "MediaPipe HandLandmarker initialized successfully.")
        } catch (t: Throwable) {
            Log.e(tag, "Failed to initialize HandLandmarker: ${t.message}", t)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            val result: HandLandmarkerResult = landmarker.detect(mpImage)

            processResult(result)
        } catch (t: Throwable) {
            Log.e(tag, "Error processing camera frame: ${t.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun processResult(result: HandLandmarkerResult) {
        val landmarksList = result.landmarks()
        val now = SystemClock.elapsedRealtime()

        if (landmarksList.isEmpty() || landmarksList[0].isEmpty()) {
            history.clear()
            onHandPresenceChanged(false)
            return
        }

        onHandPresenceChanged(true)
        val landmarks = landmarksList[0]

        // Center of hand (average of wrist 0 and MCP 9)
        val wristY = landmarks[0].y()
        val mcpY = landmarks[9].y()
        val currentY = (wristY + mcpY) / 2.0f

        // Purge points older than windowDurationMs
        while (history.isNotEmpty() && (now - history.first.timestamp > windowDurationMs)) {
            history.removeFirst()
        }
        history.addLast(PointRecord(currentY, now))

        // Check if cooldown has elapsed
        if (now - lastTriggerTime < gestureCooldownMs) {
            return
        }

        // Compare current position with the oldest point in rolling window
        if (history.size >= 2) {
            val oldest = history.first
            val deltaY = currentY - oldest.y

            if (deltaY >= swipeThreshold) {
                // Hand moved down -> Scroll Down to next item
                lastTriggerTime = now
                history.clear()
                Log.d(tag, "Gesture triggered: SCROLL_DOWN (deltaY = $deltaY)")
                onGestureDetected(GestureType.SCROLL_DOWN)
            } else if (deltaY <= -swipeThreshold) {
                // Hand moved up -> Scroll Up to previous item
                lastTriggerTime = now
                history.clear()
                Log.d(tag, "Gesture triggered: SCROLL_UP (deltaY = $deltaY)")
                onGestureDetected(GestureType.SCROLL_UP)
            }
        }
    }

    fun close() {
        try {
            handLandmarker?.close()
        } catch (_: Throwable) {}
        handLandmarker = null
    }
}
