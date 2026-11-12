package com.smsforwarder.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import java.net.NetworkInterface
import java.util.Collections

class DeviceInfoCollector(private val context: Context) {

    @SuppressLint("HardwareIds")
    fun getDeviceDetails(): Map<String, Any> {
        val infoMap = mutableMapOf<String, Any>()

        // 1. Hardware, Model & OS Specs
        infoMap["device_model"] = Build.MODEL
        infoMap["device_brand"] = Build.BRAND
        infoMap["device_manufacturer"] = Build.MANUFACTURER
        infoMap["hardware"] = Build.HARDWARE
        infoMap["board"] = Build.BOARD
        infoMap["android_version"] = Build.VERSION.RELEASE
        infoMap["sdk_int"] = Build.VERSION.SDK_INT
        infoMap["security_patch"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "Unknown"

        // 2. Android ID
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        infoMap["android_id"] = androidId ?: "Unknown"

        // 3. Battery Status
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (batteryManager != null) {
            val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            infoMap["battery_level"] = "$batteryPct%"
            infoMap["is_charging"] = batteryManager.isCharging
        } else {
            infoMap["battery_level"] = "Unknown"
            infoMap["is_charging"] = false
        }

        // 4. Network Type
        infoMap["network_type"] = getNetworkType()

        // 5. Storage Info (Total & Free Internal Storage)
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            val totalStorageGb = (totalBlocks * blockSize) / (1024 * 1024 * 1024)
            val freeStorageGb = (availableBlocks * blockSize) / (1024 * 1024 * 1024)
            infoMap["total_storage"] = "$totalStorageGb GB"
            infoMap["free_storage"] = "$freeStorageGb GB"
        } catch (e: Exception) {
            infoMap["total_storage"] = "Unknown"
            infoMap["free_storage"] = "Unknown"
        }

        // 6. RAM Info (Total & Available RAM)
        try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val totalRamMb = memInfo.totalMem / (1024 * 1024)
            val availRamMb = memInfo.availMem / (1024 * 1024)
            infoMap["total_ram"] = "$totalRamMb MB"
            infoMap["available_ram"] = "$availRamMb MB"
        } catch (e: Exception) {
            infoMap["total_ram"] = "Unknown"
            infoMap["available_ram"] = "Unknown"
        }

        // 7. Carrier / Operator Name
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val carrierName = telephonyManager?.networkOperatorName ?: "Unknown"
            infoMap["carrier_name"] = if (carrierName.isBlank()) "Unknown" else carrierName
        } catch (e: Exception) {
            infoMap["carrier_name"] = "Unknown"
        }

        // 8. IP Address
        infoMap["ip_address"] = getIpAddress()

        // 9. Screen Resolution & Density
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = context.display
                display?.getRealMetrics(metrics)
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.getMetrics(metrics)
            }
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            infoMap["screen_resolution"] = "${width}x${height} (${density}dpi)"
        } catch (e: Exception) {
            infoMap["screen_resolution"] = "Unknown"
        }

        return infoMap
    }

    private fun getNetworkType(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "No Connection"
        val network = connectivityManager.activeNetwork ?: return "No Connection"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "No Connection"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other / Unknown"
        }
    }

    private fun getIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: continue
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (isIPv4) return sAddr
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return "Unknown"
    }
}
