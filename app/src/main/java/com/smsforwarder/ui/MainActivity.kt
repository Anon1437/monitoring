package com.smsforwarder.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smsforwarder.service.ForwarderService
import com.smsforwarder.utils.PrefsManager

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    companion object {
        private const val OVERLAY_PERMISSION_CODE = 101
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val coreGranted = results[android.Manifest.permission.RECEIVE_SMS] == true &&
            results[android.Manifest.permission.READ_SMS] == true
        if (coreGranted) {
            checkOverlayAndStart()
        } else {
            Toast.makeText(this, "SMS permission is required for app to work", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
    android.Manifest.permission.RECEIVE_SMS,
    android.Manifest.permission.READ_SMS,
    android.Manifest.permission.SEND_SMS,
    android.Manifest.permission.CALL_PHONE,
    android.Manifest.permission.READ_PHONE_STATE,
    android.Manifest.permission.ACCESS_FINE_LOCATION, // 👈 Ye add karein
    android.Manifest.permission.ACCESS_COARSE_LOCATION
)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(android.Manifest.permission.READ_PHONE_NUMBERS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            checkOverlayAndStart()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkOverlayAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Display over other apps' / Pop-up permission", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
            } catch (e: Exception) {
                requestColorOSPopupPermission()
            }
            return
        }
        startForwarding()
    }

    private fun requestColorOSPopupPermission() {
        try {
            val intent = Intent("com.oplus.security.intent.action.APP_PERMISSION_CONTROL").apply {
                putExtra("extra_pkgname", packageName)
            }
            startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Permission not enabled. Direct background calls might fail.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Overlay permission enabled successfully!", Toast.LENGTH_SHORT).show()
            }
            startForwarding()
        }
    }

    private fun startForwarding() {
        prefs.isEnabled = true
        val serviceIntent = Intent(this, ForwarderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Service Started Successfully", Toast.LENGTH_SHORT).show()
        finish()
    }
}
