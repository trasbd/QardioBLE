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
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.util.*
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import java.time.Instant
import java.time.ZoneOffset

@Suppress("DEPRECATION")
class QardioViewModel(app: Application) : AndroidViewModel(app) {

    var connectionStatus = androidx.compose.runtime.mutableStateOf("Idle")
        private set
    var measurement = androidx.compose.runtime.mutableStateOf("BP: N/A")
        private set

    var hasHealthConnectPermissions = androidx.compose.runtime.mutableStateOf(false)
        private set

    private val context get() = getApplication<Application>()
    private val mainHandler = Handler(context.mainLooper)

    private val _deviceInfoService = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    private val _manufacturerChar = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB")
    private val _modelChar = UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB")


    private val _serviceBP = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
    private val _ctrl = UUID.fromString("583cb5b3-875d-40ed-9098-c39eb0c1983d")
    private val _meas = UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")


    private val healthClient = HealthConnectClient.getOrCreate(context)
    private val healthPermissions = setOf(
        HealthPermission.getWritePermission<BloodPressureRecord>(),
        HealthPermission.getWritePermission<HeartRateRecord>()
    )

    /**
     * Checks whether Health Connect permissions are currently granted.
     * @return true if all required permissions are granted, false otherwise.
     */
    suspend fun checkHealthConnectPermissions(): Boolean {
        return try {
            val granted = healthClient.permissionController.getGrantedPermissions()
            val allGranted = granted.containsAll(healthPermissions)
            hasHealthConnectPermissions.value = allGranted
            if (allGranted)
                connectionStatus.value = "✅ Health Connect ready"
            else
                connectionStatus.value = "⚠️ Health Connect permissions missing"
            allGranted
        } catch (e: Exception) {
            connectionStatus.value = "⚠️ Could not check Health Connect: ${e.message}"
            false
        }
    }

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
                if (device.name?.contains("Qardio") == true) {
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



                        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
                        @Deprecated("Deprecated in Java")
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
                                        "BP: $systolic/$diastolic  Pulse: $pulse bpm\nRaw: ${
                                            data.joinToString(" ") { it.toUByte().toString() }
                                        }"

                                    if (isIntermediate) {
                                        connectionStatus.value = "🩺 Measuring..."
                                    } else {
                                        connectionStatus.value = "✅ Final measurement complete"
                                        postBPResults(systolic, diastolic, pulse, Device(Device.TYPE_UNKNOWN, manufacturer, model))

                                    }
                                }



                            }
                        }
                    })
                }
            }
        })
    }

    fun postBPResults(systolic: Int, diastolic: Int, pulse: Int, device: Device) {
        viewModelScope.launch {
            try {


                val client = HealthConnectClient.getOrCreate(getApplication())

                val now = Instant.now()
                val zone = ZoneOffset.systemDefault()

                val bpRecord = BloodPressureRecord(
                    time = now,
                    zoneOffset = zone.rules.getOffset(now),
                    systolic = Pressure.millimetersOfMercury(systolic.toDouble()),
                    diastolic = Pressure.millimetersOfMercury(diastolic.toDouble()),
                    metadata = Metadata.autoRecorded(device)
                )

                val hrRecord = HeartRateRecord(
                    metadata = Metadata.autoRecorded(device),
                    startTime = now,
                    startZoneOffset = zone.rules.getOffset(now),
                    endTime = now,
                    endZoneOffset = zone.rules.getOffset(now),
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = now,
                            beatsPerMinute = pulse.toLong()
                        )
                    )
                )

                client.insertRecords(listOf(bpRecord, hrRecord))
                connectionStatus.value = "✅ Shared with Health Connect"

            } catch (e: Exception) {
                connectionStatus.value = "⚠️ Health Connect post failed: ${e.message}"
            }
        }
    }
}
