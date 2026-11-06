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

class HandGestureAnalyzer(
    context: Context,
    private val onGestureDetected: (GestureType) -> Unit,
    private val onHandPresenceChanged: (Boolean) -> Unit
) : ImageAnalysis.Analyzer {

    private val tag = "HandGestureAnalyzer"
    private var handLandmarker: HandLandmarker? = null

    // Motion tracking variables
    private var previousY: Float? = null
    private var previousTimestamp: Long = 0L
    private var lastTriggerTime: Long = 0L

    // Tuning constants
    private val swipeThreshold = 0.16f // Normalized screen percentage (16% delta)
    private val maxSwipeDurationMs = 500L // Fast flick
    private val gestureCooldownMs = 800L  // Minimum delay between scrolls

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.50f)
                .setMinTrackingConfidence(0.50f)
                .setMinHandPresenceConfidence(0.50f)
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
                // Flip horizontally because front camera is mirrored
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
            previousY = null
            onHandPresenceChanged(false)
            return
        }

        onHandPresenceChanged(true)
        val landmarks = landmarksList[0]

        // Track wrist (0) and middle finger mcp (9) for robust center of hand
        val wristY = landmarks[0].y()
        val mcpY = landmarks[9].y()
        val currentY = (wristY + mcpY) / 2.0f

        val prevY = previousY
        val prevTime = previousTimestamp

        if (prevY != null && (now - prevTime) <= maxSwipeDurationMs) {
            val deltaY = currentY - prevY

            if (now - lastTriggerTime > gestureCooldownMs) {
                if (deltaY > swipeThreshold) {
                    // Hand moved downwards -> Scroll down to next post
                    lastTriggerTime = now
                    previousY = currentY
                    previousTimestamp = now
                    onGestureDetected(GestureType.SCROLL_DOWN)
                    return
                } else if (deltaY < -swipeThreshold) {
                    // Hand moved upwards -> Scroll up to previous post
                    lastTriggerTime = now
                    previousY = currentY
                    previousTimestamp = now
                    onGestureDetected(GestureType.SCROLL_UP)
                    return
                }
            }
        }

        // Update tracking reference
        if (prevY == null || (now - prevTime) > maxSwipeDurationMs) {
            previousY = currentY
            previousTimestamp = now
        }
    }

    fun close() {
        try {
            handLandmarker?.close()
        } catch (_: Throwable) {}
        handLandmarker = null
    }
}
