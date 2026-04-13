package dev.gpxit.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gpxit.app.domain.TrainConnection
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectionRow(
    connection: TrainConnection,
    waitMinutes: Int? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(expanded) {
        if (expanded) {
            kotlinx.coroutines.delay(250)
            bringIntoViewRequester.bringIntoView()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp)
    ) {
        // Wait time badge
        if (waitMinutes != null && waitMinutes > 0) {
            Text(
                text = "wait $waitMinutes min",
                style = MaterialTheme.typography.labelSmall,
                color = if (waitMinutes > 30) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        // Summary row with fixed columns
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = connection.line,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${timeFormatter.format(connection.departureTime)} \u2192 ${timeFormatter.format(connection.arrivalTime)}",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(2f)
            )
            val changes = if (connection.numChanges == 0) "direct" else "${connection.numChanges}x"
            Text(
                text = changes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.7f)
            )
        }

        // Expanded detail
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .bringIntoViewRequester(bringIntoViewRequester)
            ) {
                for ((index, leg) in connection.legs.withIndex()) {
                    if (index > 0) {
                        Text(
                            text = "\u21C4 Change",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    if (leg.isWalk) {
                        val walkMin = Duration.between(leg.departureTime, leg.arrivalTime).toMinutes()
                        Text(
                            text = "\uD83D\uDEB6 Walk ${walkMin} min: ${leg.departureStation} \u2192 ${leg.arrivalStation}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    } else {
                        val lineLabel = leg.line ?: "?"
                        val dirLabel = leg.direction?.let { " \u2192 $it" } ?: ""
                        Text(
                            text = "$lineLabel$dirLabel",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )

                        // Use monospace-style alignment for stop times
                        Text(
                            text = "${timeFormatter.format(leg.departureTime)}  ${leg.departureStation}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )

                        for (stop in leg.intermediateStops) {
                            val stopTime = stop.arrivalTime ?: stop.departureTime
                            val timeStr = stopTime?.let { timeFormatter.format(it) } ?: "     "
                            Text(
                                text = "$timeStr  ${stop.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }

                        Text(
                            text = "${timeFormatter.format(leg.arrivalTime)}  ${leg.arrivalStation}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
