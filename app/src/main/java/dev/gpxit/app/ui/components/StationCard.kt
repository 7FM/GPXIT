package dev.gpxit.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gpxit.app.data.gpx.haversineMeters
import dev.gpxit.app.domain.ConnectionOption
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun StationCard(
    option: ConnectionOption,
    isBest: Boolean = false,
    onClick: () -> Unit = {},
    onLoadMore: (() -> Unit)? = null,
    userLat: Double? = null,
    userLon: Double? = null,
    avgSpeedKmh: Double = 18.0,
    modifier: Modifier = Modifier
) {
    val isOnRoute = option.station.distanceAlongRouteMeters > 0
    val liveDistanceMeters = if (userLat != null && userLon != null) {
        haversineMeters(userLat, userLon, option.station.lat, option.station.lon)
    } else null

    val distLabel: String
    val cyclingMinutes: Int

    if (isOnRoute) {
        val routeKm = option.station.distanceAlongRouteMeters / 1000.0
        val liveKm = liveDistanceMeters?.let { it / 1000.0 }
        distLabel = if (liveKm != null) {
            "%.1f km along route \u00B7 %.1f km away".format(routeKm, liveKm)
        } else {
            "%.1f km along route".format(routeKm)
        }
        cyclingMinutes = option.cyclingTimeMinutes
    } else if (liveDistanceMeters != null) {
        val liveKm = liveDistanceMeters / 1000.0
        distLabel = "%.1f km away".format(liveKm)
        val speedMs = avgSpeedKmh * 1000.0 / 3600.0
        cyclingMinutes = (liveDistanceMeters / speedMs / 60.0).toInt()
    } else {
        val fallbackKm = option.station.distanceFromRouteMeters / 1000.0
        distLabel = "%.1f km away".format(fallbackKm)
        cyclingMinutes = option.cyclingTimeMinutes
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = if (isBest) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = option.station.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "~${cyclingMinutes} min cycling",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = distLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            if (option.connections.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    for (conn in option.connections) {
                        ConnectionRow(conn)
                    }
                }

                if (onLoadMore != null) {
                    OutlinedButton(
                        onClick = onLoadMore,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Load later connections")
                    }
                }
            } else {
                Text(
                    text = "No connections found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (option.bestArrivalHome != null) {
                Text(
                    text = "Home by ${timeFormatter.format(option.bestArrivalHome)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
