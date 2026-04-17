package dev.gpxit.app.ui.decision

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gpxit.app.domain.ConnectionOption
import dev.gpxit.app.domain.StationCandidate
import dev.gpxit.app.ui.components.ConnectionRow
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecisionScreen(
    viewModel: DecisionViewModel,
    onRefresh: () -> Unit,
    onSearchNearby: () -> Unit,
    onOptionClick: (ConnectionOption) -> Unit,
    onBack: () -> Unit,
    userLat: Double? = null,
    userLon: Double? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Take me home") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("<") }
                }
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = onRefresh,
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val allOptions = uiState.options

            if (allOptions.isNotEmpty()) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "Which station should you ride to?",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    itemsIndexed(allOptions) { _, option ->
                        HomeOptionCard(
                            option = option,
                            onClick = { onOptionClick(option) }
                        )
                    }
                }
            }

            uiState.error?.let { error ->
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            if (allOptions.isEmpty() && !uiState.isLoading && uiState.error == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No route loaded or no stations found",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeOptionCard(
    option: ConnectionOption,
    onClick: () -> Unit
) {
    var showMoreConnections by remember { mutableStateOf(false) }
    val firstConn = option.connections.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (option.isRecommended) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier
                .clickable { onClick() }
                .padding(16.dp)
        ) {
            // Station name + recommended badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = option.station.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (option.isRecommended) {
                    Text(
                        text = "Recommended",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Distance + expected arrival at station
            val distKm = option.station.distanceAlongRouteMeters / 1000.0
            Text(
                text = "%.1f km along route \u00B7 arrive ~${timeFormatter.format(option.estimatedArrivalAtStation)}".format(distKm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Key metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Cycling time
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${option.cyclingTimeMinutes}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("min cycling", style = MaterialTheme.typography.labelSmall)
                }

                // Wait time
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${option.waitTimeMinutes}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (option.waitTimeMinutes > 30)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text("min wait", style = MaterialTheme.typography.labelSmall)
                }

                // Total time / arrival
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (option.bestArrivalHome != null) {
                        Text(
                            text = timeFormatter.format(option.bestArrivalHome),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text("home by", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text(
                            text = "—",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text("no train", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // First connection (clickable/expandable with wait time)
            if (firstConn != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ConnectionRow(
                    connection = firstConn,
                    waitMinutes = option.waitTimeMinutes
                )
            }

            // More connections
            if (option.connections.size > 1) {
                Text(
                    text = if (showMoreConnections) "Hide connections" else "Show ${option.connections.size - 1} more",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { showMoreConnections = !showMoreConnections }
                        .padding(top = 8.dp)
                )
                AnimatedVisibility(visible = showMoreConnections) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        for (conn in option.connections.drop(1)) {
                            val connWait = java.time.Duration.between(
                                option.estimatedArrivalAtStation,
                                conn.departureTime
                            ).toMinutes().coerceAtLeast(0).toInt()
                            ConnectionRow(
                                connection = conn,
                                waitMinutes = connWait
                            )
                        }
                    }
                }
            }
        }
    }
}
