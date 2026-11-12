package com.smsforwarder.utils

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sms_forwarder_prefs", Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var lastSmsId: Long
        get() = prefs.getLong(KEY_LAST_SMS_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_LAST_SMS_ID, value).apply()
        
    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_DEVICE_ID).apply()
            } else {
                prefs.edit().putString(KEY_DEVICE_ID, value).commit()
            }
        }

    companion object {
        private const val KEY_ENABLED = "is_enabled"
        private const val KEY_LAST_SMS_ID = "last_sms_id"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
