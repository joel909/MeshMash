package com.example.meshmash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.meshmash.ui.theme.MeshMashTheme

class BleMeshTestActivity : ComponentActivity() {
    private enum class PendingAction { LISTEN, SEND }

    private var status by mutableStateOf("Choose Listen on one phone and Send on the other")
    private val receivedData = mutableStateListOf<String>()
    private var pendingAction: PendingAction? = null
    private var pendingText = ""
    private lateinit var node: BleMeshNode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        node = BleMeshNode(
            context = this,
            onStatus = { text -> runOnUiThread { status = text } },
            onMessage = { text -> runOnUiThread { receivedData.add(0, text) } },
        )
        enableEdgeToEdge()
        setContent {
            MeshMashTheme {
                var textToSend by remember { mutableStateOf("") }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { results ->
                    if (results.values.all { it }) runPendingAction()
                    else status = "Nearby devices permission is required"
                }

                fun request(action: PendingAction, text: String = "") {
                    pendingAction = action
                    pendingText = text
                    val missing = BLE_PERMISSIONS.filter {
                        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) runPendingAction()
                    else permissionLauncher.launch(missing.toTypedArray())
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("BLE Test", style = MaterialTheme.typography.headlineMedium)
                        Text(status, style = MaterialTheme.typography.bodyMedium)

                        OutlinedTextField(
                            value = textToSend,
                            onValueChange = { textToSend = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Data") },
                            minLines = 3,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { request(PendingAction.LISTEN) },
                            ) { Text("Listen") }
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = textToSend.isNotEmpty(),
                                onClick = {
                                    request(PendingAction.SEND, textToSend)
                                },
                            ) { Text("Send") }
                        }

                        Text("Received", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (receivedData.isEmpty()) {
                                item { Text("No data received yet") }
                            }
                            items(receivedData) { Text(it) }
                        }
                    }
                }
            }
        }
    }

    private fun runPendingAction() {
        if (!node.isBluetoothEnabled) {
            status = "Turn on Bluetooth, then tap the button again"
            return
        }
        when (pendingAction) {
            PendingAction.LISTEN -> node.listen()
            PendingAction.SEND -> node.send(pendingText)
            null -> Unit
        }
        pendingAction = null
    }

    override fun onDestroy() {
        node.stop()
        super.onDestroy()
    }

    companion object {
        private val BLE_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    }
}
