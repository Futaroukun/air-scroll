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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.ArrayDeque

class HandGestureAnalyzer(
    context: Context,
    private val onGestureDetected: (GestureType) -> Unit,
    private val onHandPresenceChanged: (Boolean, String) -> Unit
) : ImageAnalysis.Analyzer {

    private val tag = "HandGestureAnalyzer"
    private var handLandmarker: HandLandmarker? = null

    private data class PointRecord(val y: Float, val timestamp: Long)
    private val history = ArrayDeque<PointRecord>()

    private var lastSeenHandTime: Long = 0L
    private var lastTriggerTime: Long = 0L
    private var wasHandOpen: Boolean = false

    // Tuning constants
    private val swipeThreshold = 0.10f      // 10% vertical delta
    private val windowDurationMs = 400L    // Rolling history window
    private val gestureCooldownMs = 650L   // Delay between scrolls

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            // Lower confidence thresholds to 0.30f so bent fingers and angled hands stay tracked
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.30f)
                .setMinTrackingConfidence(0.30f)
                .setMinHandPresenceConfidence(0.30f)
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

    private fun getDistance(p1: NormalizedLandmark, p2: NormalizedLandmark): Float {
        val dx = p1.x() - p2.x()
        val dy = p1.y() - p2.y()
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun processResult(result: HandLandmarkerResult) {
        val landmarksList = result.landmarks()
        val now = SystemClock.elapsedRealtime()

        if (landmarksList.isEmpty() || landmarksList[0].isEmpty()) {
            // Keep grace period of 300ms so temporary occlusion when bending fingers doesn't drop state
            if (now - lastSeenHandTime > 300L) {
                history.clear()
                wasHandOpen = false
                onHandPresenceChanged(false, "Standby")
            }
            return
        }

        lastSeenHandTime = now
        val landmarks = landmarksList[0]

        val wrist = landmarks[0]
        val indexTip = landmarks[8]
        val middleTip = landmarks[12]
        val middleMcp = landmarks[9]

        // 1. Calculate Finger Bend / Curl ratio
        // Distance from wrist to middle MCP is invariant palm scale
        val palmSize = getDistance(wrist, middleMcp)
        val tipsAvgDist = (getDistance(wrist, indexTip) + getDistance(wrist, middleTip)) / 2.0f
        val extensionRatio = if (palmSize > 0.001f) tipsAvgDist / palmSize else 1.0f

        val isOpenPose = extensionRatio >= 1.30f
        val isBentPose = extensionRatio <= 1.05f

        val poseLabel = if (isOpenPose) "🖐️ Siap" else if (isBentPose) "✊ Ditekuk" else "Tangan OK"
        onHandPresenceChanged(true, poseLabel)

        // 2. Gesture Trigger 1: Tekuk Tangan (Flat/Open -> Bent)
        if (now - lastTriggerTime > gestureCooldownMs) {
            if (wasHandOpen && isBentPose) {
                // User was holding hand open (datar/tos) and bent fingers down -> SCROLL DOWN!
                lastTriggerTime = now
                wasHandOpen = false
                history.clear()
                Log.d(tag, "Triggered SCROLL_DOWN via Finger Bend (ratio = $extensionRatio)")
                onGestureDetected(GestureType.SCROLL_DOWN)
                return
            }
        }

        if (isOpenPose) {
            wasHandOpen = true
        }

        // 3. Gesture Trigger 2: Vertical Movement (Swipe/Wave Naik & Turun)
        val currentY = (wrist.y() + middleMcp.y()) / 2.0f

        while (history.isNotEmpty() && (now - history.first.timestamp > windowDurationMs)) {
            history.removeFirst()
        }
        history.addLast(PointRecord(currentY, now))

        if (now - lastTriggerTime > gestureCooldownMs && history.size >= 2) {
            val oldest = history.first
            val deltaY = currentY - oldest.y

            if (deltaY >= swipeThreshold) {
                // Hand moved down
                lastTriggerTime = now
                history.clear()
                wasHandOpen = false
                Log.d(tag, "Triggered SCROLL_DOWN via Movement (deltaY = $deltaY)")
                onGestureDetected(GestureType.SCROLL_DOWN)
            } else if (deltaY <= -swipeThreshold) {
                // Hand moved up
                lastTriggerTime = now
                history.clear()
                wasHandOpen = false
                Log.d(tag, "Triggered SCROLL_UP via Movement (deltaY = $deltaY)")
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
