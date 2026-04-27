package dev.gpxit.app.ui.komoot

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gpxit.app.data.komoot.KomootRef
import dev.gpxit.app.data.komoot.KomootTourSummary
import dev.gpxit.app.data.komoot.KomootUrlParser
import dev.gpxit.app.ui.import_route.DesignIcons
import dev.gpxit.app.ui.theme.LocalMapPalette
import dev.gpxit.app.ui.theme.rememberMapPalette
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Single Komoot import surface: paste-URL field at the top, the user's
 * saved planned-tour list below. Reachable from both the empty home
 * card and the loaded-state dropdown so there's exactly one place a
 * Komoot import can start.
 *
 * Tapping a tour row fires [onPickTour] with a [KomootRef]; the
 * parent activity drives the import via
 * [dev.gpxit.app.ui.import_route.ImportViewModel.importKomoot].
 */
@Composable
fun KomootBrowseScreen(
    onBack: () -> Unit,
    onPickTour: (KomootRef) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: KomootBrowseViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadInitial() }

    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }

    // Auto-prefill the paste field from the clipboard if it carries a
    // Komoot URL — most common path is "user copied a share link from
    // the Komoot app, then opened GPXIT".
    LaunchedEffect(Unit) {
        val clip = readClipboardText(context)
        if (!clip.isNullOrBlank() && KomootUrlParser.parse(clip) != null) {
            pasteText = clip
        }
    }

    val palette = rememberMapPalette()
    CompositionLocalProvider(LocalMapPalette provides palette) {
        val statusBars = WindowInsets.statusBars.asPaddingValues()
        val navBars = WindowInsets.navigationBars.asPaddingValues()
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(palette.sheetBg),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .border(1.dp, palette.line)
                    .padding(top = 12.dp + statusBars.calculateTopPadding())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DesignIcons.ChevronLeft,
                        contentDescription = "Back",
                        tint = palette.ink,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Import from Komoot",
                    color = palette.ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Paste-link block — works even when the user-ID isn't
            // configured (share-link imports only need email+password).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "Paste a Komoot tour link",
                    color = palette.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = {
                            pasteText = it
                            pasteError = null
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("https://www.komoot.com/tour/…") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        isError = pasteError != null,
                    )
                    Button(
                        onClick = {
                            val ref = KomootUrlParser.parse(pasteText)
                            if (ref != null) {
                                onPickTour(ref)
                            } else {
                                pasteError = "That doesn't look like a Komoot tour link."
                            }
                        },
                        enabled = pasteText.isNotBlank(),
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = palette.onAccent,
                        ),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text("Import", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (pasteError != null) {
                    Text(
                        pasteError!!,
                        color = Color(0xFFC0392B),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "— or pick from your saved tours —",
                    color = palette.inkSoft,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                )
            }

            when {
                state.missingCredentials -> {
                    EmptyMessage(
                        title = "Not signed in",
                        body = state.error ?: "Sign in to Komoot in Settings to browse your tours. " +
                            "Pasting a link still works without browsing.",
                        action = "Open Settings" to onNavigateToSettings,
                    )
                }
                state.tours.isEmpty() && state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = palette.accent)
                    }
                }
                state.tours.isEmpty() && state.error != null -> {
                    EmptyMessage(
                        title = "Couldn't load your tour list",
                        body = state.error!!,
                        action = "Retry" to { vm.loadInitial() },
                    )
                }
                state.tours.isEmpty() -> {
                    EmptyMessage(
                        title = "No planned tours found",
                        body = "Plan a tour in Komoot, or paste a share link above.",
                        action = null,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Bottom contentPadding clears the system gesture
                        // bar so the "Load more" button isn't masked by
                        // the 3-button nav on devices that still use it.
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 14.dp,
                            end = 14.dp,
                            top = 0.dp,
                            bottom = 14.dp + navBars.calculateBottomPadding(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.tours, key = { it.id }) { t ->
                            TourRow(t, onClick = { onPickTour(KomootRef(t.id, null)) })
                        }
                        item {
                            if (state.hasMore) {
                                Button(
                                    onClick = { vm.loadMore() },
                                    enabled = !state.isLoading,
                                    shape = RoundedCornerShape(999.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.accent,
                                        contentColor = palette.onAccent,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .height(44.dp),
                                ) {
                                    Text(
                                        if (state.isLoading) "Loading…" else "Load more",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            } else if (state.error != null) {
                                Text(
                                    text = state.error!!,
                                    color = Color(0xFFC0392B),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun readClipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.text?.toString()
}

@Composable
private fun TourRow(t: KomootTourSummary, onClick: () -> Unit) {
    val palette = LocalMapPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surface)
            .border(1.dp, palette.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = t.name,
            color = palette.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val subtitle = buildString {
            append("%.1f km".format(t.distanceMeters / 1000.0))
            t.sport?.let { append("  ·  $it") }
        }
        Text(
            text = subtitle,
            color = palette.inkSoft,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun EmptyMessage(
    title: String,
    body: String,
    action: Pair<String, () -> Unit>?,
) {
    val palette = LocalMapPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = palette.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = palette.inkSoft, fontSize = 13.sp)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = action.second,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.onAccent,
                ),
            ) {
                Text(action.first, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
