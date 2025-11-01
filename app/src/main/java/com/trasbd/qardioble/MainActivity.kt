package com.trasbd.qardioble

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.trasbd.qardioble.ble.QardioViewModel
import com.trasbd.qardioble.health.HealthConnectHelper
import com.trasbd.qardioble.ui.QardioView
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm by viewModels<QardioViewModel>()
    private lateinit var hcHelper: HealthConnectHelper

    // Launcher for Health Connect permission dialog
    private val requestHealthPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            lifecycleScope.launch {
                val allGranted = granted.containsAll(hcHelper.requiredPermissions())
                //vm.hasHealthConnectPermissions.value = allGranted
                vm.connectionStatus.value =
                    if (allGranted) "✅ Health Connect ready"
                    else "⚠️ Health Connect permissions missing"
            }
        }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Initialize helper and check availability ---
        hcHelper = HealthConnectHelper(application)
        if (!hcHelper.ensureHealthConnectAvailable(this)) return

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

        // --- Check existing Health Connect permissions on startup ---
        lifecycleScope.launch {
            val hasPerms = hcHelper.hasAllPermissions()
            vm.hasHealthPermissions.value = hasPerms
            //vm.connectionStatus.value =
            //    if (hasPerms) "✅ Health Connect ready"
             //   else "⚠️ Health Connect permissions missing"
        }



        // --- Compose UI ---
        setContent {
            QardioView(
                vm,
                onStartClicked = { vm.startScan() },
                onHCPermissionsClicked = {
                    lifecycleScope.launch {
                        hcHelper.requestPermissions(requestHealthPermissions)
                        vm.hasHealthPermissions.value = hcHelper.hasAllPermissions()
                    }
                }
            )
        }
    }
}
