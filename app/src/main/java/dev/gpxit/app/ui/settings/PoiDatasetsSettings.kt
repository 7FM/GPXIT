package dev.gpxit.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpxit.app.data.poi.PoiDataset
import dev.gpxit.app.data.poi.PoiDatasetManager
import dev.gpxit.app.ui.theme.LocalMapPalette
import java.util.Locale

/**
 * Offline POI data: which countries are kept on the device, their
 * download state, and the monthly update switch.
 */
@Composable
internal fun PoiDatasetsSettings(
    state: PoiDatasetManager.State,
    autoUpdate: Boolean,
    onSetAutoUpdate: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onRefreshIndex: () -> Unit,
    onSetSelected: (String, Boolean) -> Unit,
) {
    val palette = LocalMapPalette.current
    val index = state.index

    // The list comes from the network; ask again when there is none yet.
    LaunchedEffect(index == null) {
        if (index == null && !state.loadingIndex) onRefreshIndex()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.sheetBg)
            .border(1.dp, palette.line, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "POI database",
                    color = palette.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = poiStatusLine(state),
                    color = palette.inkSoft,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Button(
                onClick = onUpdate,
                enabled = state.selected.isNotEmpty() && state.downloads.isEmpty() && !state.loadingIndex,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = Color.White,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 0.dp,
                ),
                modifier = Modifier.height(32.dp),
            ) {
                Text(text = "Update", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Auto-update monthly",
                color = palette.ink,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            SettingsToggle(on = autoUpdate, onChange = onSetAutoUpdate, small = true)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Divider()
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Shops, water and toilets along your routes work offline for the " +
                "countries you keep here. Importing a route through another country " +
                "offers its data too.",
            color = palette.inkSoft,
            fontSize = 11.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (index == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.loadingIndex) {
                    CircularProgressIndicator(
                        color = palette.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Loading countries…", color = palette.inkSoft, fontSize = 12.sp)
                } else {
                    Text(
                        text = "Couldn't load the list of countries" +
                            (state.indexError?.let { " ($it)" } ?: ""),
                        color = palette.inkSoft,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Retry",
                        color = palette.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(onClick = onRefreshIndex)
                            .padding(6.dp),
                    )
                }
            }
        } else {
            PoiDatasetList(index.datasets, state, onSetSelected)
        }
    }
}

@Composable
private fun PoiDatasetList(
    all: List<PoiDataset>,
    state: PoiDatasetManager.State,
    onSetSelected: (String, Boolean) -> Unit,
) {
    val palette = LocalMapPalette.current
    var filter by remember { mutableStateOf("") }
    OutlinedTextField(
        value = filter,
        onValueChange = { filter = it },
        placeholder = { Text("Search countries", fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFFFAF8F2),
            unfocusedContainerColor = Color(0xFFFAF8F2),
        ),
    )
    Spacer(modifier = Modifier.height(6.dp))

    val query = filter.trim().lowercase(Locale.ROOT)
    // Kept countries first, then the rest alphabetically — by the selection
    // the list opened with, so a row doesn't jump away when toggled.
    val keptFirst = remember(all) { state.selected }
    val datasets = all
        .filter { query.isEmpty() || query in it.name.lowercase(Locale.ROOT) || query in it.id }
        .sortedWith(compareBy<PoiDataset>({ it.id !in keptFirst }, { it.name }))
    for (dataset in datasets) {
        PoiDatasetRow(
            dataset = dataset,
            state = state,
            onSetSelected = { onSetSelected(dataset.id, it) },
        )
    }
    if (datasets.isEmpty()) {
        Text("No country matches", color = palette.inkSoft, fontSize = 12.sp)
    }
}

@Composable
private fun PoiDatasetRow(
    dataset: PoiDataset,
    state: PoiDatasetManager.State,
    onSetSelected: (Boolean) -> Unit,
) {
    val palette = LocalMapPalette.current
    val selected = dataset.id in state.selected
    val download = state.downloads[dataset.id]
    val installed = state.installed[dataset.id]
    val error = state.errors[dataset.id]
    val detail = when {
        download != null -> download.label
        error != null -> error
        installed != null && state.hasUpdate(dataset.id) -> "${formatMb(dataset.sizeBytes)} · update available"
        installed != null -> "${formatMb(installed.sizeBytes)} on the device"
        else -> formatMb(dataset.sizeBytes)
    }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = dataset.name, color = palette.ink, fontSize = 13.sp)
                Text(
                    text = detail,
                    color = if (error != null && download == null) palette.accentDark else palette.inkSoft,
                    fontSize = 11.sp,
                )
            }
            SettingsToggle(on = selected, onChange = onSetSelected, small = true)
        }
        if (download != null) {
            Spacer(modifier = Modifier.height(4.dp))
            if (download.running && download.fraction > 0f) {
                LinearProgressIndicator(
                    progress = { download.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Divider() {
    val palette = LocalMapPalette.current
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.line),
    )
}

private fun poiStatusLine(state: PoiDatasetManager.State): String {
    val running = state.downloads.entries.firstOrNull { it.value.running }
    if (running != null) {
        val name = state.index?.byId(running.key)?.name ?: running.key
        val waiting = state.downloads.size - 1
        return "$name: ${running.value.label}" + if (waiting > 0) " (+$waiting waiting)" else ""
    }
    val installed = state.installed.filterKeys { it in state.selected }
    if (installed.isEmpty()) {
        return if (state.selected.isEmpty()) "No countries selected" else "Not downloaded yet"
    }
    val size = formatMb(installed.values.sumOf { it.sizeBytes })
    val countries = if (installed.size == 1) "1 country" else "${installed.size} countries"
    return "$countries · $size · ${updatedAgo(state.lastUpdateCheckMs)}"
}

/** Summary for the settings group header. */
internal fun poiSummary(state: PoiDatasetManager.State): String {
    if (state.selected.isEmpty()) return "No POI data"
    val names = state.selected
        .map { id -> state.index?.byId(id)?.name ?: id.replaceFirstChar { it.uppercase() } }
        .sorted()
    val list = if (names.size <= 2) names.joinToString(", ") else "${names[0]} +${names.size - 1}"
    return "POIs: $list"
}

private fun updatedAgo(epochMs: Long): String {
    if (epochMs == 0L) return "installed"
    val days = ((System.currentTimeMillis() - epochMs) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "checked today"
        days == 1 -> "checked yesterday"
        else -> "checked $days days ago"
    }
}

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)
