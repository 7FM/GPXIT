package dev.gpxit.app.ui.import_route

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Settings shortcut — some settings (enabled station products, search
        // radius, sampling interval) affect the GPX import, so the gear is
        // always reachable from here.
        IconButton(
            onClick = onNavigateToSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Canvas(modifier = Modifier.size(22.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val outerR = size.width / 2f - 1f
                val innerR = outerR * 0.55f
                val toothW = outerR * 0.35f
                val col = Color.DarkGray
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45.0))
                    val cos = kotlin.math.cos(angle).toFloat()
                    val sin = kotlin.math.sin(angle).toFloat()
                    drawLine(
                        col,
                        start = Offset(cx + innerR * cos, cy + innerR * sin),
                        end = Offset(cx + outerR * cos, cy + outerR * sin),
                        strokeWidth = toothW
                    )
                }
                drawCircle(col, radius = innerR + 1f, center = Offset(cx, cy))
                drawCircle(Color(0xFFE0E0E0), radius = innerR * 0.5f, center = Offset(cx, cy))
            }
        }

    Column(
        modifier = Modifier
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
    } // end Column
    } // end Box
}
