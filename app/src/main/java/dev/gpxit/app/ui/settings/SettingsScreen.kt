package dev.gpxit.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gpxit.app.data.prefs.PrefsRepository
import dev.gpxit.app.data.transit.TransitRepository
import kotlinx.coroutines.flow.StateFlow

private val ALL_PRODUCTS = listOf(
    "HIGH_SPEED_TRAIN" to "ICE/IC",
    "REGIONAL_TRAIN" to "Regional",
    "SUBURBAN_TRAIN" to "S-Bahn",
    "SUBWAY" to "U-Bahn",
    "TRAM" to "Tram",
    "BUS" to "Bus",
    "FERRY" to "Ferry",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    prefsFlow: StateFlow<PrefsRepository.UserPreferences>,
    onSetHomeStation: (TransitRepository.StationSuggestion) -> Unit,
    onSetSpeed: (Double) -> Unit,
    onSetSearchRadius: (Int) -> Unit,
    onQueryChanged: (String) -> Unit,
    onToggleProduct: (String) -> Unit,
    onToggleConnectionProduct: (String) -> Unit,
    onSetElevationAwareTime: (Boolean) -> Unit,
    onSetMinWaitBuffer: (Int) -> Unit,
    onSetMaxWaitMinutes: (Int) -> Unit,
    onSetMaxStationsToCheck: (Int) -> Unit,
    onSetShowElevationGraph: (Boolean) -> Unit,
    onSetPoiDbAutoUpdate: (Boolean) -> Unit,
    onUpdatePoiDb: () -> Unit,
    poiDbDownloadState: dev.gpxit.app.GpxitDownloadState,
    poiDbAvailable: Boolean,
    stationSuggestions: List<TransitRepository.StationSuggestion>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs by prefsFlow.collectAsState(initial = PrefsRepository.UserPreferences())
    var stationQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("<") }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Home station
            Text("Home Station", style = MaterialTheme.typography.titleMedium)
            if (prefs.homeStationName != null) {
                Text(
                    text = "Current: ${prefs.homeStationName}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            OutlinedTextField(
                value = stationQuery,
                onValueChange = {
                    stationQuery = it
                    onQueryChanged(it)
                },
                label = { Text("Search station") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true
            )
            for (suggestion in stationSuggestions) {
                Card(
                    onClick = {
                        onSetHomeStation(suggestion)
                        stationQuery = ""
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Text(
                        text = suggestion.name,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Station types
            Text("Station Types", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Which stations to show along the route",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for ((productKey, label) in ALL_PRODUCTS) {
                    FilterChip(
                        selected = productKey in prefs.enabledProducts,
                        onClick = { onToggleProduct(productKey) },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Connection types for the way home
            Text("Connection Types (way home)", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Allowed transport for connections back home",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for ((productKey, label) in ALL_PRODUCTS) {
                    FilterChip(
                        selected = productKey in prefs.connectionProducts,
                        onClick = { onToggleConnectionProduct(productKey) },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Minimum wait buffer
            Text("Minimum wait at station", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (prefs.minWaitBufferMinutes == 0) "No buffer (0 min)"
                       else "${prefs.minWaitBufferMinutes} min",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Safety margin to not miss a connection",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = prefs.minWaitBufferMinutes.toFloat(),
                onValueChange = { f ->
                    onSetMinWaitBuffer(kotlin.math.round(f).toInt().coerceIn(0, 30))
                },
                valueRange = 0f..30f,
                steps = 29,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Maximum wait filter
            Text("Maximum wait at station", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (prefs.maxWaitMinutes == 0) "No limit"
                       else "${prefs.maxWaitMinutes} min",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Hide connections with longer wait times",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = prefs.maxWaitMinutes.toFloat(),
                onValueChange = { f ->
                    val snapped = (kotlin.math.round(f / 15f).toInt() * 15)
                        .coerceIn(0, 120)
                    onSetMaxWaitMinutes(snapped)
                },
                valueRange = 0f..120f,
                steps = 7, // 0, 15, 30, 45, 60, 75, 90, 105, 120
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Max stations to check for "Take me home"
            Text("Exit points to check", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${prefs.maxStationsToCheck}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "How many upcoming stations \"Take me home\" considers",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = prefs.maxStationsToCheck.toFloat(),
                onValueChange = { f ->
                    onSetMaxStationsToCheck(
                        kotlin.math.round(f).toInt().coerceIn(4, 20)
                    )
                },
                valueRange = 4f..20f,
                steps = 15, // 4..20 inclusive
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Average cycling speed
            Text("Average Speed", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "%.0f km/h".format(prefs.avgSpeedKmh),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = prefs.avgSpeedKmh.toFloat(),
                onValueChange = { f ->
                    onSetSpeed(kotlin.math.round(f).toDouble().coerceIn(10.0, 35.0))
                },
                valueRange = 10f..35f,
                steps = 24,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Elevation-aware time", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Adjust speed for uphill/downhill",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = prefs.elevationAwareTime,
                    onCheckedChange = { onSetElevationAwareTime(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Search radius (for route station precomputation)
            Text("Station Search Radius", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${prefs.searchRadiusMeters}m",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Max distance from route to find stations",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = prefs.searchRadiusMeters.toFloat(),
                onValueChange = { f ->
                    val snapped = (kotlin.math.round(f / 500f).toInt() * 500)
                        .coerceIn(500, 10000)
                    onSetSearchRadius(snapped)
                },
                valueRange = 500f..10000f,
                steps = 18,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Map
            Text("Map", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Show elevation graph", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = prefs.showElevationGraph,
                    onCheckedChange = { onSetShowElevationGraph(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // POI database — downloaded from GitHub Releases, rebuilt monthly
            // by the build-poi-dataset workflow.
            Text("POI database", style = MaterialTheme.typography.titleMedium)
            val statusText = when {
                poiDbDownloadState.active -> poiDbDownloadState.label
                !poiDbAvailable -> "Not downloaded yet"
                prefs.poiDbLastUpdateMs == 0L -> "Installed"
                else -> "Updated " + formatRelativeDays(prefs.poiDbLastUpdateMs)
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (poiDbDownloadState.active || poiDbDownloadState.progress > 0f) {
                LinearProgressIndicator(
                    progress = { poiDbDownloadState.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onUpdatePoiDb,
                    enabled = !poiDbDownloadState.active
                ) {
                    Text(if (poiDbAvailable) "Update now" else "Download")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Auto-update monthly",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Switch(
                        checked = prefs.poiDbAutoUpdate,
                        onCheckedChange = { onSetPoiDbAutoUpdate(it) }
                    )
                }
            }
        }
    }
}

/** "today", "3 days ago", "12 days ago" — compact for the settings row. */
private fun formatRelativeDays(epochMs: Long): String {
    val nowMs = System.currentTimeMillis()
    val days = ((nowMs - epochMs) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        else -> "$days days ago"
    }
}
