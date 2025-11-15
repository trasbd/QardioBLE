package com.trasbd.qardioble.health

import android.app.Application
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import java.time.Instant
import java.time.ZoneOffset

/**
 * Centralized Health Connect permission handler.
 * Can be used from ViewModel or Activity to check or request permissions.
 */

class HealthConnectHelper(private val app: Application) {

    private val client = HealthConnectClient.getOrCreate(app)
    private val requiredPermissions = setOf(
        HealthPermission.getWritePermission<BloodPressureRecord>(),
        HealthPermission.getWritePermission<HeartRateRecord>()
    )

    /** Check if all required permissions are already granted. */
    suspend fun hasAllPermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(requiredPermissions)
    }

    /** Launch the system permission dialog via an ActivityResultLauncher. */
    fun requestPermissions(launcher: ActivityResultLauncher<Set<String>>) {
        launcher.launch(requiredPermissions)
    }

    /** Returns the set of all required Health Connect permissions. */
    fun requiredPermissions(): Set<String> = requiredPermissions

    /**
     * Checks Health Connect availability and optionally redirects the user
     * to install/update the Health Connect provider.
     * Returns false if unavailable, true if ready.
     */
    fun ensureHealthConnectAvailable(activity: android.app.Activity): Boolean {
        val providerPackage = "com.google.android.apps.healthdata"
        when (HealthConnectClient.getSdkStatus(app, providerPackage)) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                println("❌ Health Connect not available on this device")
                return false
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                @Suppress("SpellCheckingInspection") val uri =
                    "market://details?id=$providerPackage&url=healthconnect%3A%2F%2Fonboarding".toUri()
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setPackage("com.android.vending")
                    data = uri
                    putExtra("overlay", true)
                    putExtra("callerId", app.packageName)
                }
                activity.startActivity(intent)
                println("⚠️ Redirecting to Play Store to install/update Health Connect")
                return false
            }
            else -> {
                return true
            }
        }
    }
    @Suppress("SameReturnValue")
    suspend fun postBPResults(systolic: Int, diastolic: Int, pulse: Int, device: Device): Boolean {

                val client = HealthConnectClient.getOrCreate(app)

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
                return true
        }
    }



