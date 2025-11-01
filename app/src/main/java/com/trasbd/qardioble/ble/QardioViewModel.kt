package com.trasbd.qardioble.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.util.*
import androidx.health.connect.client.records.metadata.Device
import com.trasbd.qardioble.health.HealthConnectHelper
import com.trasbd.qardioble.BuildConfig


@Suppress("DEPRECATION")
class QardioViewModel(app: Application) : AndroidViewModel(app) {

    var connectionStatus = mutableStateOf("Waiting to connect...")
        private set
    var measurement = mutableStateOf("")
        private set
    var healthConnectStatus = mutableStateOf("")
        private set
    var hasHealthPermissions = mutableStateOf(false)
        private set


    private val context get() = getApplication<Application>()
    private val mainHandler = Handler(context.mainLooper)
    private val _hcHelper = HealthConnectHelper(context)

    private val _deviceInfoService = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    private val _manufacturerChar = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB")
    private val _modelChar = UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB")


    private val _serviceBP = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
    private val _ctrl = UUID.fromString("583cb5b3-875d-40ed-9098-c39eb0c1983d")
    private val _meas = UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")


    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    fun startScan() = viewModelScope.launch {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@launch
        val scanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder().setDeviceName("QardioARM").build()
        val settings = ScanSettings.Builder().build()

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) return@launch

        connectionStatus.value = "🔍 Scanning for QardioARM..."
        scanner.startScan(listOf(filter), settings, object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(type: Int, result: ScanResult) {
                val device = result.device ?: return
                if (device.name?.contains("QardioARM") == true) {
                    connectionStatus.value = "🔗 Found ${device.name}, connecting..."
                    scanner.stopScan(this)

                    device.connectGatt(context, false, object : BluetoothGattCallback() {
                        private var manufacturer: String = "Unknown"
                        private var model: String = "Unknown"

                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                connectionStatus.value = "✅ Connected, discovering services..."
                                gatt.discoverServices()
                            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                connectionStatus.value = "❌ Disconnected"
                            }
                        }

                        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            // --- Read Device Information first ---
                            val deviceInfo = gatt.getService(_deviceInfoService)
                            val manufacturerChar = deviceInfo?.getCharacteristic(_manufacturerChar)
                            val modelChar = deviceInfo?.getCharacteristic(_modelChar)

                            // Read manufacturer and model (will trigger onCharacteristicRead below)
                            if (manufacturerChar != null) gatt.readCharacteristic(manufacturerChar)
                            if (modelChar != null) gatt.readCharacteristic(modelChar)
                        }

                        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
                        private fun enableIndicationsAndStart(gatt: BluetoothGatt) {
                            val measChar = gatt.getService(_serviceBP)?.getCharacteristic(_meas) ?: return
                            val cccd = measChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"))
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                            gatt.setCharacteristicNotification(measChar, true)
                            mainHandler.postDelayed({
                                val ctrl = gatt.services.flatMap { it.characteristics }.find { it.uuid == _ctrl }
                                ctrl?.let {
                                    gatt.writeCharacteristic(
                                        it,
                                        byteArrayOf(0xF1.toByte(), 0x01),
                                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    )
                                }
                            }, 800)
                        }


                        @Suppress("OVERRIDE_DEPRECATION")
                        @Deprecated("Deprecated in Java")
                        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
                        override fun onCharacteristicRead(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            status: Int
                        ) {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                when (characteristic.uuid) {
                                    _manufacturerChar -> {
                                        manufacturer = characteristic.value.toString(Charsets.UTF_8).trim()
                                        // now chain to the next read
                                        val deviceInfo = gatt.getService(_deviceInfoService)
                                        val modelChar = deviceInfo?.getCharacteristic(_modelChar)
                                        if (modelChar != null) gatt.readCharacteristic(modelChar)
                                    }
                                    _modelChar -> {
                                        model = characteristic.value.toString(Charsets.UTF_8).trim()
                                        // both reads finished — continue setup
                                        enableIndicationsAndStart(gatt)
                                    }
                                }
                            }
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        @Deprecated("Deprecated in Java")
                        override fun onCharacteristicChanged(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic
                        ) {
                            if (characteristic.uuid == _meas) {
                                val data = characteristic.value

                                val flags = data.getOrNull(0)?.toInt() ?: 0
                                val isIntermediate: Boolean = (flags and 0b10000) == 0    // bit 4 → intermediate measurement
                                val systolic = data.getOrNull(1)?.toUByte()?.toInt() ?: 0
                                val diastolic = data.getOrNull(3)?.toUByte()?.toInt() ?: 0
                                val pulse = data.getOrNull(7)?.toUByte()?.toInt() ?: 0
                                val failed = (pulse == 0 && systolic >= 250)

                                if (failed) {
                                    connectionStatus.value = "❌ Measurement failed — please retry"
                                    measurement.value = ""
                                } else {
                                    measurement.value =
                                        "BP: $systolic/$diastolic  Pulse: $pulse bpm"

                                    if (BuildConfig.DEBUG) {
                                        measurement.value += "\nRaw: ${
                                            data.joinToString(" ") { it.toUByte().toString() }
                                        }"
                                    }

                                    if (isIntermediate) {
                                        connectionStatus.value = "🩺 Measuring..."
                                    } else {
                                        connectionStatus.value = "✅ Final measurement complete"


                                        viewModelScope.launch {
                                            if(_hcHelper.hasAllPermissions())
                                            {
                                            try {
                                               _hcHelper.postBPResults(
                                                    systolic,
                                                    diastolic,
                                                    pulse,
                                                    Device(Device.TYPE_UNKNOWN, manufacturer, model)
                                                )
                                                healthConnectStatus.value = "Posted to Health Connect"
                                            }
                                            catch (e: Exception) {
                                                // Handle or log it
                                                healthConnectStatus.value = "⚠️ Failed to post to Health Connect: ${e.message}"
                                            }
                                        }}
                                    }
                                }



                            }
                        }
                    })
                }
            }
        })
    }


}
