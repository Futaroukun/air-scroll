package com.airscroll.app.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.airscroll.app.service.AirAccessibilityService

object PermissionHelper {

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedServiceName = "${context.packageName}/${AirAccessibilityService::class.java.canonicalName}"

        for (service in enabledServices) {
            if (service.id.equals(expectedServiceName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun hasAllPermissions(context: Context): Boolean {
        return hasCameraPermission(context) &&
                hasOverlayPermission(context) &&
                isAccessibilityServiceEnabled(context)
    }
}
