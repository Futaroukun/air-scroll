package com.airscroll.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.airscroll.app.MainActivity
import com.airscroll.app.R
import com.airscroll.app.databinding.OverlayCameraBinding
import com.airscroll.app.tracking.GestureType
import com.airscroll.app.tracking.HandGestureAnalyzer
import java.util.concurrent.Executors

class CameraOverlayService : LifecycleService() {

    private val tag = "CameraOverlayService"
    private val notificationId = 1001
    private val channelId = "air_scroll_channel"

    private var windowManager: WindowManager? = null
    private var overlayBinding: OverlayCameraBinding? = null
    private var gestureAnalyzer: HandGestureAnalyzer? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraStarted = false
    private var isPaused = false

    companion object {
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CameraOverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("CameraOverlayService", "Failed to start service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CameraOverlayService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundNotification()
        createFloatingOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!cameraStarted) {
            cameraStarted = true
            startCamera()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.overlay_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.overlay_service_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AirScroll Aktif")
            .setContentText("Melacak gerakan tangan untuk scroll layar")
            .setSmallIcon(R.drawable.ic_airscroll)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Throwable) {
            Log.e(tag, "startForeground with camera type failed: ${e.message}, retrying without camera type")
            try {
                startForeground(notificationId, notification)
            } catch (e2: Throwable) {
                Log.e(tag, "Critical startForeground fallback failed: ${e2.message}")
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingOverlay() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val themedContext = ContextThemeWrapper(this, R.style.Theme_AirScroll)
            val inflater = LayoutInflater.from(themedContext)
            val binding = OverlayCameraBinding.inflate(inflater)
            overlayBinding = binding

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 180
            }

            // Quick Pause overlay button
            binding.btnPauseOverlay.setOnClickListener {
                isPaused = !isPaused
                binding.btnPauseOverlay.text = if (isPaused) "▶" else "⏸"
                if (isPaused) {
                    binding.tvGestureFeedback.text = "⏸ Dijeda"
                    binding.tvGestureFeedback.setTextColor(Color.YELLOW)
                    binding.indicatorDot.setBackgroundColor(Color.YELLOW)
                } else {
                    binding.tvGestureFeedback.text = "Ready"
                    binding.tvGestureFeedback.setTextColor(Color.WHITE)
                    binding.indicatorDot.setBackgroundColor(Color.parseColor("#94A3B8"))
                }
            }

            // Close overlay button
            binding.btnCloseOverlay.setOnClickListener {
                stopSelf()
            }

            // Draggable floating window logic
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            binding.overlayRoot.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(binding.root, params)
                        } catch (_: Exception) {}
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(binding.root, params)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to attach floating overlay: ${e.message}", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    overlayBinding?.previewView?.let { previewView ->
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                }

                gestureAnalyzer = HandGestureAnalyzer(
                    context = this,
                    onGestureDetected = { gesture ->
                        onGestureRecognized(gesture)
                    },
                    onHandPresenceChanged = { hasHand, pose ->
                        updateIndicator(hasHand, pose)
                    }
                )

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        gestureAnalyzer?.let { analyzer ->
                            analysis.setAnalyzer(cameraExecutor, analyzer)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                // Anti-Backlight: Center-metering & Exposure compensation
                try {
                    val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    val centerPoint = factory.createPoint(0.5f, 0.5f)
                    val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AE).build()
                    camera.cameraControl.startFocusAndMetering(action)

                    val expState = camera.cameraInfo.exposureState
                    if (expState.isExposureCompensationSupported) {
                        val range = expState.exposureCompensationRange
                        val boost = ((range.upper - range.lower) * 0.25f).toInt()
                        val targetIndex = (expState.exposureCompensationIndex + boost).coerceIn(range.lower, range.upper)
                        camera.cameraControl.setExposureCompensationIndex(targetIndex)
                        Log.d(tag, "Exposure boosted for backlight: index=$targetIndex range=$range")
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Exposure boost unsupported: ${e.message}")
                }

                Log.d(tag, "CameraX bound successfully.")
            } catch (e: Throwable) {
                Log.e(tag, "Failed to bind CameraX use cases: ${e.message}", e)
                overlayBinding?.tvGestureFeedback?.text = "Cam Err"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onGestureRecognized(gesture: GestureType) {
        if (isPaused) return
        val accessService = AirAccessibilityService.instance
        if (accessService == null) {
            mainHandler.post {
                overlayBinding?.tvGestureFeedback?.text = "⚠️ Aktifkan Akses"
                overlayBinding?.tvGestureFeedback?.setTextColor(Color.YELLOW)
            }
            return
        }

        val gestureLabel = if (gesture == GestureType.SCROLL_DOWN) "⬇ Scroll Down" else "⬆ Scroll Up"

        mainHandler.post {
            overlayBinding?.tvGestureFeedback?.text = gestureLabel
            overlayBinding?.tvGestureFeedback?.setTextColor(Color.parseColor("#10B981"))
        }

        accessService.performScroll(gesture) { success, reason ->
            mainHandler.post {
                if (success) {
                    overlayBinding?.tvGestureFeedback?.text = "✅ $gestureLabel"
                    overlayBinding?.tvGestureFeedback?.setTextColor(Color.parseColor("#10B981"))
                } else {
                    overlayBinding?.tvGestureFeedback?.text = "❌ $reason"
                    overlayBinding?.tvGestureFeedback?.setTextColor(Color.RED)
                }

                mainHandler.postDelayed({
                    val statusText = if (AirAccessibilityService.isConnected()) "Ready" else "⚠️ Off"
                    overlayBinding?.tvGestureFeedback?.text = statusText
                    overlayBinding?.tvGestureFeedback?.setTextColor(Color.WHITE)
                }, 900)
            }
        }
    }

    private fun updateIndicator(hasHand: Boolean, pose: String) {
        if (isPaused) return
        mainHandler.post {
            val color = if (hasHand) Color.parseColor("#10B981") else Color.parseColor("#94A3B8")
            overlayBinding?.indicatorDot?.setBackgroundColor(color)

            val currentText = overlayBinding?.tvGestureFeedback?.text?.toString() ?: ""
            if (currentText.isEmpty() || currentText == "Ready" || currentText == "Standby" || currentText.startsWith("🖐️") || currentText.startsWith("✊") || currentText == "Tangan OK") {
                overlayBinding?.tvGestureFeedback?.text = if (hasHand) pose else "Standby"
                overlayBinding?.tvGestureFeedback?.setTextColor(if (hasHand) Color.parseColor("#10B981") else Color.WHITE)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cameraStarted = false
        try {
            overlayBinding?.let {
                windowManager?.removeView(it.root)
            }
        } catch (_: Throwable) {}
        try {
            gestureAnalyzer?.close()
        } catch (_: Throwable) {}
        try {
            cameraExecutor.shutdown()
        } catch (_: Throwable) {}
        Log.i(tag, "CameraOverlayService destroyed.")
    }
}
