package com.smsforwarder.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.smsforwarder.service.ForwarderService

object SmsSyncHelper {
    fun requestCheck(context: Context) {
        try {
            val serviceIntent = Intent(context, ForwarderService::class.java).apply {
                action = ForwarderService.ACTION_CHECK_NOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("SmsSyncHelper", "Failed to start service directly: ${e.message}")
        }
    }
}
