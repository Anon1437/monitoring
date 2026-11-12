package com.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.smsforwarder.utils.PrefsManager
import com.smsforwarder.worker.SmsWorker

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        const val UNIQUE_WORK_NAME = "sms_check_work"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = PrefsManager(context)
        if (!prefs.isEnabled) {
            Log.d(TAG, "Forwarding disabled, ignoring SMS")
            return
        }

        Log.d(TAG, "SMS received, enqueueing WorkManager task")
        try {
            val workRequest = OneTimeWorkRequestBuilder<SmsWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue work: ${e.message}")
        }
    }
}