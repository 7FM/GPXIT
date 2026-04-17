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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
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
    onSetUseDarkMap: (Boolean) -> Unit,
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
                onValueChange = { onSetMinWaitBuffer(it.toInt()) },
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
                onValueChange = { onSetMaxWaitMinutes(it.toInt()) },
                valueRange = 0f..120f,
                steps = 7, // 0, 15, 30, 45, 60, 75, 90, 105, 120
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
                onValueChange = { onSetSpeed(it.toDouble()) },
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
                onValueChange = { onSetSearchRadius(it.toInt()) },
                valueRange = 500f..10000f,
                steps = 18,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Dark map tiles
            Text("Map Appearance", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dark map tiles", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = prefs.useDarkMap,
                    onCheckedChange = { onSetUseDarkMap(it) }
                )
            }
        }
    }
}
