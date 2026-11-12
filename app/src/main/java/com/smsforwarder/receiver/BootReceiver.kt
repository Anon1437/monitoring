package com.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.smsforwarder.service.ForwarderService
import com.smsforwarder.utils.PrefsManager
import com.smsforwarder.worker.SmsWorker

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = PrefsManager(context)
        if (!prefs.isEnabled) return

        Log.d(TAG, "Device rebooted, restarting ForwarderService")
        try {
            val serviceIntent = Intent(context, ForwarderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Foreground service start failed on boot, scheduling WorkManager fallback: ${e.message}")
            try {
                val workRequest = OneTimeWorkRequestBuilder<SmsWorker>().build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    SmsReceiver.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            } catch (workError: Exception) {
                Log.e(TAG, "WorkManager fallback also failed: ${workError.message}")
            }
        }
    }
}