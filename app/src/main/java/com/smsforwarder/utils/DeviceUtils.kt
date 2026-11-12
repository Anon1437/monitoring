package com.smsforwarder.utils

import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

object DeviceUtils {
    private const val TAG = "DeviceUtils"

    @Volatile
    private var cachedId: String? = null

    fun getUniqueDeviceId(context: Context): String {
        cachedId?.let { return it }

        synchronized(this) {
            cachedId?.let { return it }

            val prefs = PrefsManager(context.applicationContext)
            prefs.deviceId?.takeIf { it.isNotBlank() }?.let {
                cachedId = it
                return it
            }

            val newId = computeLegacyId(context) ?: "dev_" + UUID.randomUUID().toString()
            prefs.deviceId = newId
            cachedId = newId
            return newId
        }
    }

    private fun computeLegacyId(context: Context): String? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val widevineUuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
                val mediaDrm = MediaDrm(widevineUuid)
                try {
                    val deviceIdBytes = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                    if (deviceIdBytes != null && deviceIdBytes.isNotEmpty()) {
                        val hash = MessageDigest.getInstance("SHA-256")
                            .digest(deviceIdBytes)
                            .joinToString("") { "%02x".format(it) }
                        return "dev_" + hash
                    }
                } finally {
                    mediaDrm.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaDrm Error: ${e.message}")
        }

        try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                return "dev_" + androidId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Android ID Error: ${e.message}")
        }

        return null
    }
}