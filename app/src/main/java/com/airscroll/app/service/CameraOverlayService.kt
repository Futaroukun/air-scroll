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
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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
                    onHandPresenceChanged = { hasHand ->
                        updateIndicator(hasHand)
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
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d(tag, "CameraX bound successfully.")
            } catch (e: Throwable) {
                Log.e(tag, "Failed to bind CameraX use cases: ${e.message}", e)
                overlayBinding?.tvGestureFeedback?.text = "Cam Err"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onGestureRecognized(gesture: GestureType) {
        mainHandler.post {
            val text = when (gesture) {
                GestureType.SCROLL_DOWN -> "⬇ Scroll Down"
                GestureType.SCROLL_UP -> "⬆ Scroll Up"
                GestureType.NONE -> ""
            }
            overlayBinding?.tvGestureFeedback?.text = text
            overlayBinding?.tvGestureFeedback?.setTextColor(Color.parseColor("#10B981"))

            // Dispatch touch gesture via AccessibilityService
            AirAccessibilityService.instance?.performScroll(gesture)

            // Reset text after 800ms
            mainHandler.postDelayed({
                overlayBinding?.tvGestureFeedback?.text = "Ready"
                overlayBinding?.tvGestureFeedback?.setTextColor(Color.WHITE)
            }, 800)
        }
    }

    private fun updateIndicator(hasHand: Boolean) {
        mainHandler.post {
            val color = if (hasHand) Color.parseColor("#10B981") else Color.parseColor("#94A3B8")
            overlayBinding?.indicatorDot?.setBackgroundColor(color)
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
