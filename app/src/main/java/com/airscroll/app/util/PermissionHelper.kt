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
        // 1. Direct active instance check (fastest & most accurate)
        if (AirAccessibilityService.isConnected()) {
            return true
        }

        val packageName = context.packageName
        val serviceClass = AirAccessibilityService::class.java.simpleName

        // 2. Query Android secure settings for enabled accessibility services
        try {
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            if (enabledServicesSetting.isNotEmpty()) {
                val services = enabledServicesSetting.split(":")
                for (service in services) {
                    if (service.contains(packageName, ignoreCase = true) &&
                        service.contains(serviceClass, ignoreCase = true)
                    ) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback to AccessibilityManager below
        }

        // 3. Fallback to AccessibilityManager list check
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledList = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (enabledList != null) {
                for (service in enabledList) {
                    val id = service.id ?: continue
                    if (id.contains(packageName, ignoreCase = true) &&
                        id.contains(serviceClass, ignoreCase = true)
                    ) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore
        }

        return false
    }

    fun hasAllPermissions(context: Context): Boolean {
        return hasCameraPermission(context) &&
                hasOverlayPermission(context) &&
                isAccessibilityServiceEnabled(context)
    }
}
