package com.smsforwarder.service

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.smsforwarder.utils.ConfigReader
import com.smsforwarder.utils.DeviceUtils
import com.smsforwarder.utils.SmsSyncHelper

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCM"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Data Message Received, keys: ${remoteMessage.data.keys}")
        if (remoteMessage.data.isNotEmpty()) {
            SmsSyncHelper.requestCheck(applicationContext)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New Token Generated")
        saveTokenToFirebase(token)
    }

    private fun saveTokenToFirebase(token: String) {
        try {
            val deviceId = DeviceUtils.getUniqueDeviceId(this)
            val firebaseUrl = ConfigReader.getFirebaseUrl(this)
            val ref = FirebaseDatabase.getInstance(firebaseUrl)
                .getReference("devices/$deviceId/fcm_token")
            ref.setValue(token)
                .addOnSuccessListener {
                    Log.d(TAG, "FCM Token successfully updated in Firebase")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "FCM Token save failed: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving FCM token: ${e.message}")
        }
    }
}
