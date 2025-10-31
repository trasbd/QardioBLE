package com.trasbd.qardioble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import java.util.*

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {
    // private var bluetoothGatt: BluetoothGatt? = null

    // Characteristic UUIDs for QardioARM
    private val _serviceBP = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb") // Blood Pressure Service
    private val _ctrl = UUID.fromString("583cb5b3-875d-40ed-9098-c39eb0c1983d")       // Control characteristic
    private val _meas = UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")       // Measurement
    private val _bat  = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")       // Battery (optional)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask for BLE + location permissions
        requestPermissions.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )

        setContent {
            MaterialTheme {
                var output by remember { mutableStateOf("Idle") }

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Button(onClick = { startScan { output = it } }) {
                        Text("Connect + Start QardioARM")
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(output)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(onUpdate: (String) -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val scanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder().setDeviceName("QardioARM").build()
        val settings = ScanSettings.Builder().build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) return

        onUpdate("🔍 Scanning for QardioARM...")
        scanner.startScan(listOf(filter), settings, object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(type: Int, result: ScanResult) {
                val device = result.device ?: return
                if (device.name?.contains("Qardio") == true) {
                    onUpdate("🔗 Found ${device.name}, connecting...")
                    scanner.stopScan(this)

                    device.connectGatt(this@MainActivity, false, object : BluetoothGattCallback() {

                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                onUpdate("✅ Connected, discovering services...")
                                gatt.discoverServices()
                            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                onUpdate("❌ Disconnected")
                            }
                        }

                        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            // 🔎 Find measurement characteristic
                            val measChar = gatt.getService(_serviceBP)?.getCharacteristic(_meas)
                            if (measChar != null) {
                                // Enable local notifications
                                gatt.setCharacteristicNotification(measChar, true)

                                // ✳️ Write 0x02-00 to CCCD (enable indications)
                                val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                                val descriptor = measChar.getDescriptor(cccdUuid)
                                if (descriptor != null) {
                                    descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                    gatt.writeDescriptor(descriptor)
                                }

                                onUpdate("🩺 Indications enabled for BP measurement")
                            } else {
                                onUpdate("⚠️ Measurement characteristic not found")
                            }

                            // Small delay before control write, to avoid collision with CCCD write
                            android.os.Handler(mainLooper).postDelayed({
                                val ctrlChar = gatt.services.flatMap { it.characteristics }.find { it.uuid == _ctrl }
                                if (ctrlChar != null) {
                                    val ok = gatt.writeCharacteristic(
                                        ctrlChar,
                                        byteArrayOf(0xF1.toByte(), 0x01),
                                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    )
                                    onUpdate("➡️ Sent start command (F1 01) = $ok")
                                } else {
                                    onUpdate("⚠️ Control characteristic not found")
                                }
                            }, 800) // ~0.8 s
                        }


                        // ✅ Receive blood pressure readings
                        @Deprecated("Deprecated in Java")
                        override fun onCharacteristicChanged(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic
                        ) {
                            if (characteristic.uuid == _meas) {
                                val data = characteristic.value
                                // These bytes follow the Bluetooth BP profile (simplified)
                                val systolic = data.getOrNull(1)?.toUByte()?.toInt() ?: 0
                                val diastolic = data.getOrNull(3)?.toUByte()?.toInt() ?: 0
                                val pulse = data.getOrNull(7)?.toUByte()?.toInt() ?: 0

                                onUpdate("BP: $systolic/$diastolic  Pulse: $pulse bpm")
                            }
                        }
                    })
                }
            }
        })
    }
}
