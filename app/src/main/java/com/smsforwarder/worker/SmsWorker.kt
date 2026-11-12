package com.smsforwarder.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.smsforwarder.service.ForwarderService

class SmsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            val serviceIntent = Intent(applicationContext, ForwarderService::class.java).apply {
                action = ForwarderService.ACTION_CHECK_NOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}