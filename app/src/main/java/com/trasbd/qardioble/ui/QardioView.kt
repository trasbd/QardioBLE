package com.trasbd.qardioble.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trasbd.qardioble.ble.QardioViewModel

@Composable
fun QardioView(
    vm: QardioViewModel,
    onStartClicked: () -> Unit,
    onHCPermissionsClicked: () -> Unit
) {
    MaterialTheme {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(onClick = onStartClicked) {
                Text("Connect + Start QardioARM")
            }

            Spacer(Modifier.height(24.dp))

            Text(vm.connectionStatus.value)
            Text(vm.measurement.value)

            Spacer(Modifier.height(24.dp))

            if (!vm.hasHealthPermissions.value) {
                Button(onClick = onHCPermissionsClicked) {
                    Text("Enable Health Connect")
                }
            }
            Text(vm.healthConnectStatus.value)

        }
    }
}
