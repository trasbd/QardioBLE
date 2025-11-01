package com.trasbd.qardioble

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.trasbd.qardioble.ble.QardioViewModel
import com.trasbd.qardioble.ui.QardioView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm by viewModels<QardioViewModel>()
    private lateinit var healthClient: HealthConnectClient

    private val healthPermissions = setOf(
        HealthPermission.getWritePermission<BloodPressureRecord>(),
        HealthPermission.getWritePermission<HeartRateRecord>()
    )

    // Launcher for Health Connect permissions
    private val requestHealthPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(healthPermissions)) {
                println("✅ Health Connect permissions granted")
            } else {
                println("⚠️ Health Connect permissions missing — open Health Connect app to enable")
            }
        }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Check Health Connect availability ---
        val providerPackage = "com.google.android.apps.healthdata"
        val availability = HealthConnectClient.getSdkStatus(application, providerPackage)
        when (availability) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                println("❌ Health Connect not available on this device")
                return
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                val uri =
                    "market://details?id=$providerPackage&url=healthconnect%3A%2F%2Fonboarding".toUri()
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setPackage("com.android.vending")
                        data = uri
                        putExtra("overlay", true)
                        putExtra("callerId", packageName)
                    }
                )
                println("⚠️ Redirecting to Play Store to install/update Health Connect")
                return
            }
        }

        // --- Bluetooth + Location runtime permissions ---
        val blePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                val denied = result.filterValues { !it }.keys
                if (denied.isNotEmpty()) println("⚠️ BLE permissions denied: $denied")
            }

        blePermissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )


        // --- Initialize client ---
        healthClient = HealthConnectClient.getOrCreate(application)

        // --- Check & request Health Connect permissions ---
        lifecycleScope.launch {
            val granted = healthClient.permissionController.getGrantedPermissions()
            if (!granted.containsAll(healthPermissions)) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        requestHealthPermissions.launch(healthPermissions)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        println("❌ Failed to launch Health Connect permission request: ${e.message}")
                    }
                }
            } else {
                println("✅ Already has Health Connect permissions")
            }
        }



        // --- Compose UI ---
        setContent {
            QardioView(
                vm,
                onStartClicked = { vm.startScan() },
                onHCPermissionsClicked = {
                    lifecycleScope.launch {
                        val granted = healthClient.permissionController.getGrantedPermissions()
                        val missing = healthPermissions.subtract(granted)
                        if (missing.isNotEmpty()) {
                            requestHealthPermissions.launch(healthPermissions)
                        } else {
                            println("✅ Health Connect already granted")
                        }
                    }
                }
            )
        }



    }
}
