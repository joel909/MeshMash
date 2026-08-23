package com.example.meshmash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.meshmash.mesh.MeshRequest
import com.example.meshmash.mesh.MeshRequestStore
import com.example.meshmash.mesh.MeshUploadStatusTracker
import com.example.meshmash.ui.theme.MeshMashTheme
import java.io.Closeable
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
class ReceivedIssuesActivity : ComponentActivity() {
    private lateinit var requestStore: MeshRequestStore
    private var requests by mutableStateOf(emptyList<MeshRequest>())
    private var uploadStatusObservation: Closeable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStore = MeshRequestStore(this)
        uploadStatusObservation = MeshUploadStatusTracker.observe { progress ->
            if (!progress.isUploading) {
                runOnUiThread { requests = requestStore.getReceivedRequests() }
            }
        }
        setContent {
            MeshMashTheme {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("Received issues") }) },
                ) { padding ->
                    if (requests.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(24.dp),
                        ) {
                            Text("No issues received from other devices yet")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(requests, key = { it.requestId }) { request ->
                                IssueCard(request)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requests = requestStore.getReceivedRequests()
    }

    override fun onDestroy() {
        uploadStatusObservation?.close()
        requestStore.close()
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun IssueCard(request: MeshRequest) {
    val location = request.location
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                request.category.replace('_', ' '),
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Priority: ${request.priority.name}")
            Text(
                "Status: ${if (request.status == com.example.meshmash.mesh.RequestStatus.DELIVERED) "Sent" else "Pending"}",
            )
            Text(request.payload.toString(Charsets.UTF_8))
            if (location != null) {
                Text("Location: %.6f, %.6f".format(location.latitude, location.longitude))
            }
            Text(
                "Received: ${DateFormat.getDateTimeInstance().format(Date(request.receivedAtMillis))}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("ID: ${request.requestId}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
