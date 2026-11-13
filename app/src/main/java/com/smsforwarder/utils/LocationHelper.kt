package com.smsforwarder.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase

class LocationHelper(private val context: Context) {

    private val deviceId = DeviceUtils.getUniqueDeviceId(context)
    private val handler = Handler(Looper.getMainLooper())
    private var activeListener: LocationListener? = null

    companion object {
        private const val TAG = "LocationHelper"
        private const val TIMEOUT_MS = 12_000L
    }

    fun fetchAndUploadLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "❌ Location permission not granted")
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Pehle se koi pending request ho to clean kar do
        cleanup(locationManager)

        try {
            // 1. Last known (instant)
            val lastKnown = getBestLastKnownLocation(locationManager)
            if (lastKnown != null) {
                Log.d(TAG, "✅ Last known → ${lastKnown.latitude}, ${lastKnown.longitude}")
                uploadLocationToFirebase(lastKnown)
            } else {
                Log.d(TAG, "⚠️ No last known location")
            }

            // 2. Fresh location request
            Log.d(TAG, "⏳ Requesting fresh location...")

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    Log.d(TAG, "✅ Fresh location → ${location.latitude}, ${location.longitude}")
                    uploadLocationToFirebase(location)
                    cleanup(locationManager)
                }

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    Log.w(TAG, "Provider disabled: $provider")
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            activeListener = listener

            var requested = false

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,      // 1 second
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
                requested = true
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
                requested = true
            }

            if (!requested) {
                Log.e(TAG, "❌ No location provider enabled (GPS + Network both off)")
                return
            }

            // Safety timeout
            handler.postDelayed({
                Log.w(TAG, "⏰ Timeout — stopping location updates")
                cleanup(locationManager)
            }, TIMEOUT_MS)

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Location Error: ${e.message}")
        }
    }

    private fun getBestLastKnownLocation(lm: LocationManager): Location? {
        val gps = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
        val net = try {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        return when {
            gps != null && net != null -> if (gps.time > net.time) gps else net
            gps != null -> gps
            else -> net
        }
    }

    private fun cleanup(locationManager: LocationManager) {
        activeListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        activeListener = null
        handler.removeCallbacksAndMessages(null)
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
            .addOnSuccessListener {
                Log.d(TAG, "✅ Location uploaded")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Upload failed: ${e.message}")
            }
    }
}
