package dev.gpxit.app.ui.import_route

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    homeStationName: String?,
    onNavigateToMap: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDownloadOfflineMap: () -> Unit = {},
    downloadState: dev.gpxit.app.GpxitDownloadState = dev.gpxit.app.GpxitDownloadState(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val routeInfo by viewModel.routeInfo.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importGpx(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "GPXIT",
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = "Import a route, find your way home",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Home station status
        if (homeStationName != null) {
            Text(
                text = "Home: $homeStationName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Set your home station first",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    OutlinedButton(onClick = onNavigateToSettings) {
                        Text("Settings")
                    }
                }
            }
        }

        Button(
            onClick = {
                launcher.launch(arrayOf(
                    "application/gpx+xml",
                    "application/octet-stream",
                    "text/xml",
                    "*/*"
                ))
            },
            enabled = !uiState.isLoading
        ) {
            Text("Import GPX File")
        }

        if (uiState.isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
            uiState.stationDiscoveryStatus?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        uiState.error?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (routeInfo != null && !uiState.isLoading) {
            Spacer(modifier = Modifier.height(24.dp))
            Card(onClick = onNavigateToMap, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    uiState.routeName?.let {
                        Text(text = it, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(text = "${uiState.pointCount} track points")
                    Text(text = "%.1f km total".format(uiState.totalDistanceKm))
                    Text(text = "${uiState.stationCount} stations found along route")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onNavigateToMap) {
                    Text("View on Map")
                }
                OutlinedButton(
                    onClick = onDownloadOfflineMap,
                    enabled = !downloadState.active
                ) {
                    Text("Offline Map")
                }
            }

            // Download progress
            if (downloadState.active || downloadState.label == "Done!") {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { downloadState.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = downloadState.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
