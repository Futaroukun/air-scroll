package com.airscroll.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.airscroll.app.databinding.ActivityMainBinding
import com.airscroll.app.service.CameraOverlayService
import com.airscroll.app.util.PermissionHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Izin kamera diberikan", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Izin kamera diperlukan untuk melacak gestur tangan", Toast.LENGTH_LONG).show()
        }
        updateUiState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun setupListeners() {
        binding.btnPermCamera.setOnClickListener {
            if (!PermissionHelper.hasCameraPermission(this)) {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnPermOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        binding.btnPermAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Cari & aktifkan 'AirScroll' di daftar Aksesibilitas", Toast.LENGTH_LONG).show()
        }

        binding.btnToggleService.setOnClickListener {
            if (CameraOverlayService.isRunning) {
                CameraOverlayService.stop(this)
                updateUiState()
            } else {
                if (!PermissionHelper.hasCameraPermission(this)) {
                    Toast.makeText(this, "Harap izinkan akses Kamera terlebih dahulu", Toast.LENGTH_SHORT).show()
                    requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    return@setOnClickListener
                }
                if (!PermissionHelper.hasOverlayPermission(this)) {
                    Toast.makeText(this, "Harap izinkan Tampil di Atas Aplikasi Lain", Toast.LENGTH_SHORT).show()
                    binding.btnPermOverlay.performClick()
                    return@setOnClickListener
                }
                if (!PermissionHelper.isAccessibilityServiceEnabled(this)) {
                    Toast.makeText(this, "Harap aktifkan Layanan Aksesibilitas AirScroll", Toast.LENGTH_SHORT).show()
                    binding.btnPermAccessibility.performClick()
                    return@setOnClickListener
                }

                CameraOverlayService.start(this)
                updateUiState()
            }
        }
    }

    private fun updateUiState() {
        val hasCamera = PermissionHelper.hasCameraPermission(this)
        val hasOverlay = PermissionHelper.hasOverlayPermission(this)
        val hasAccess = PermissionHelper.isAccessibilityServiceEnabled(this)

        // Camera status
        if (hasCamera) {
            binding.tvCameraStatusIcon.text = "✓"
            binding.tvCameraStatusIcon.setTextColor(Color.parseColor("#10B981"))
            binding.btnPermCamera.isEnabled = false
            binding.btnPermCamera.text = "Aktif"
        } else {
            binding.tvCameraStatusIcon.text = "!"
            binding.tvCameraStatusIcon.setTextColor(Color.parseColor("#F59E0B"))
            binding.btnPermCamera.isEnabled = true
            binding.btnPermCamera.text = "Izinkan"
        }

        // Overlay status
        if (hasOverlay) {
            binding.tvOverlayStatusIcon.text = "✓"
            binding.tvOverlayStatusIcon.setTextColor(Color.parseColor("#10B981"))
            binding.btnPermOverlay.isEnabled = false
            binding.btnPermOverlay.text = "Aktif"
        } else {
            binding.tvOverlayStatusIcon.text = "!"
            binding.tvOverlayStatusIcon.setTextColor(Color.parseColor("#F59E0B"))
            binding.btnPermOverlay.isEnabled = true
            binding.btnPermOverlay.text = "Izinkan"
        }

        // Accessibility status
        if (hasAccess) {
            binding.tvAccessStatusIcon.text = "✓"
            binding.tvAccessStatusIcon.setTextColor(Color.parseColor("#10B981"))
            binding.btnPermAccessibility.isEnabled = false
            binding.btnPermAccessibility.text = "Aktif"
        } else {
            binding.tvAccessStatusIcon.text = "!"
            binding.tvAccessStatusIcon.setTextColor(Color.parseColor("#F59E0B"))
            binding.btnPermAccessibility.isEnabled = true
            binding.btnPermAccessibility.text = "Aktifkan"
        }

        // Service running status
        if (CameraOverlayService.isRunning) {
            binding.tvStatusBadge.text = "BERJALAN"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#10B981"))
            binding.btnToggleService.text = "Hentikan AirScroll"
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#EF4444"))
        } else {
            binding.tvStatusBadge.text = "OFF"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#94A3B8"))
            binding.btnToggleService.text = "Mulai AirScroll"
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#6366F1"))
        }
    }
}
