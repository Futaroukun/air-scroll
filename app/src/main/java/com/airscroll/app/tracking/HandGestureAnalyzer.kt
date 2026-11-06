package com.airscroll.app.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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

    private data class PointRecord(val x: Float, val y: Float, val timestamp: Long)
    private val history = ArrayDeque<PointRecord>()

    private var lastSeenHandTime: Long = 0L
    private var lastTriggerTime: Long = 0L
    private var wasHandOpen: Boolean = false

    // Sensitivity tuning: Upward motion has less headroom before exiting top bezel, so threshold is more sensitive
    private val swipeUpThreshold = 0.055f    // 5.5% delta for upward flick
    private val swipeDownThreshold = 0.085f  // 8.5% delta for downward flick
    private val swipeHorizThreshold = 0.13f  // 13% delta for horizontal flick
    private val windowDurationMs = 450L     // Rolling history window
    private val gestureCooldownMs = 650L    // Cooldown between triggers

    // Reusable paint for backlight shadow lifting
    private val backlightPaint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            1.25f, 0f, 0f, 0f, 32f,
            0f, 1.25f, 0f, 0f, 32f,
            0f, 0f, 1.25f, 0f, 32f,
            0f, 0f, 0f, 1f, 0f
        )))
    }

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            // Lower confidence to 0.22f so backlit hands and dark silhouettes stay tracked
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.22f)
                .setMinTrackingConfidence(0.22f)
                .setMinHandPresenceConfidence(0.22f)
                .setNumHands(1)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.d(tag, "MediaPipe HandLandmarker initialized with backlight-tolerant settings.")
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
            val srcBitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            val isRotated = (rotation == 90 || rotation == 270)
            val targetWidth = if (isRotated) srcBitmap.height else srcBitmap.width
            val targetHeight = if (isRotated) srcBitmap.width else srcBitmap.height

            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                postScale(-1f, 1f, srcBitmap.width / 2f, srcBitmap.height / 2f)
            }

            // Draw with backlight enhancement to lift shadows and boost contrast
            val enhancedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(enhancedBitmap)
            canvas.drawBitmap(srcBitmap, matrix, backlightPaint)

            val mpImage = BitmapImageBuilder(enhancedBitmap).build()
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
            // Grace period: allow 400ms of missing frames (e.g. backlight flicker) without dropping state
            if (now - lastSeenHandTime > 400L) {
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
        val palmSize = getDistance(wrist, middleMcp)
        val tipsAvgDist = (getDistance(wrist, indexTip) + getDistance(wrist, middleTip)) / 2.0f
        val extensionRatio = if (palmSize > 0.001f) tipsAvgDist / palmSize else 1.0f

        val isOpenPose = extensionRatio >= 1.25f
        val isBentPose = extensionRatio <= 1.05f

        val poseLabel = if (isOpenPose) "🖐️ Siap" else if (isBentPose) "✊ Ditekuk" else "Tangan OK"
        onHandPresenceChanged(true, poseLabel)

        // 2. Gesture Trigger 1: Tekuk Tangan (Flat/Open -> Bent) -> SCROLL DOWN
        if (now - lastTriggerTime > gestureCooldownMs) {
            if (wasHandOpen && isBentPose) {
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

        // 3. Gesture Trigger 2: Movement Tracking (Naik, Turun, Kiri, Kanan)
        val currentX = (wrist.x() + middleMcp.x()) / 2.0f
        val currentY = (wrist.y() + middleMcp.y()) / 2.0f

        while (history.isNotEmpty() && (now - history.first.timestamp > windowDurationMs)) {
            history.removeFirst()
        }
        history.addLast(PointRecord(currentX, currentY, now))

        if (now - lastTriggerTime > gestureCooldownMs && history.size >= 2) {
            val oldest = history.first
            val deltaX = currentX - oldest.x
            val deltaY = currentY - oldest.y

            // Check vertical movement first
            if (deltaY <= -swipeUpThreshold) {
                // Hand moved UP -> SCROLL UP
                lastTriggerTime = now
                history.clear()
                wasHandOpen = false
                Log.d(tag, "Triggered SCROLL_UP via Upward Movement (deltaY = $deltaY)")
                onGestureDetected(GestureType.SCROLL_UP)
                return
            } else if (deltaY >= swipeDownThreshold) {
                // Hand moved DOWN -> SCROLL DOWN
                lastTriggerTime = now
                history.clear()
                wasHandOpen = false
                Log.d(tag, "Triggered SCROLL_DOWN via Downward Movement (deltaY = $deltaY)")
                onGestureDetected(GestureType.SCROLL_DOWN)
                return
            }

            // Optional horizontal flicks: Left = Previous (Up), Right = Next (Down)
            if (deltaX <= -swipeHorizThreshold) {
                lastTriggerTime = now
                history.clear()
                wasHandOpen = false
                Log.d(tag, "Triggered SCROLL_UP via Left Swipe (deltaX = $deltaX)")
                onGestureDetected(GestureType.SCROLL_UP)
                return
            } else if (deltaX >= swipeHorizThreshold) {
                lastTriggerTime = now
                history.clear()
                wasHandOpen = false
                Log.d(tag, "Triggered SCROLL_DOWN via Right Swipe (deltaX = $deltaX)")
                onGestureDetected(GestureType.SCROLL_DOWN)
                return
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
