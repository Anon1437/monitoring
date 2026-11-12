package com.smsforwarder.utils

import android.content.Context
import org.json.JSONObject

object ConfigReader {
    private var jsonConfig: JSONObject? = null

    private fun loadConfig(context: Context) {
        if (jsonConfig == null) {
            try {
                val inputStream = context.assets.open("config.json")
                val size = inputStream.available()
                val buffer = ByteArray(size)
                inputStream.read(buffer)
                inputStream.close()
                val jsonStr = String(buffer, Charsets.UTF_8)
                jsonConfig = JSONObject(jsonStr)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getFirebaseUrl(context: Context): String {
        loadConfig(context)
        return jsonConfig?.optString("firebase_url", "https://sms-tracker-panel-default-rtdb.asia-southeast1.firebasedatabase.app/") ?: ""
    }

    fun getApiKey(context: Context): String {
        loadConfig(context)
        return jsonConfig?.optString("api_key", "") ?: ""
    }

    fun getApplicationId(context: Context): String {
        loadConfig(context)
        return jsonConfig?.optString("application_id", "") ?: ""
    }

    fun getProjectId(context: Context): String {
        loadConfig(context)
        return jsonConfig?.optString("project_id", "") ?: ""
    }
}
