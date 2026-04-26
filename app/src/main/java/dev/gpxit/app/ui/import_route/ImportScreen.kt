package dev.gpxit.app.ui.import_route

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpxit.app.data.gpx.routeClimbDescentMeters
import dev.gpxit.app.data.gpx.splitRouteName

@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    homeStationName: String?,
    onNavigateToMap: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDownloadOfflineMap: () -> Unit = {},
    downloadState: dev.gpxit.app.GpxitDownloadState = dev.gpxit.app.GpxitDownloadState(),
    brouterInstalled: Boolean = true,
    onInstallBRouter: () -> Unit = {},
    onClearRoute: () -> Unit = {},
    onReloadStations: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val routeInfo by viewModel.routeInfo.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importGpx(it) }
    }
    // MIME types accepted by the SAF picker — same list as the
    // launcher invocation in both the empty-state "Import GPX" and
    // the loaded-state "Replace GPX" buttons. Pulled out so the two
    // call sites don't drift.
    val gpxPickerMimeTypes = remember {
        arrayOf(
            "application/gpx+xml",
            "application/octet-stream",
            "text/xml",
            "*/*",
        )
    }
    var showClearConfirm by remember { mutableStateOf(false) }

    val loaded = routeInfo != null && !uiState.isLoading
    val c = if (dev.gpxit.app.ui.theme.LocalIsDark.current) DarkPalette else LightPalette

    CompositionLocalProvider(LocalHomePalette provides c) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // Background bleeds edge-to-edge so the page fill sits
            // under the status / nav bars (icon tints are pinned via
            // MainActivity / Theme.kt based on the same palette).
            .background(c.bg)
    ) {
        // Settings gear in the top-right — below the status bar inset.
        IconButton(
            onClick = onNavigateToSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 4.dp, end = 8.dp)
        ) {
            Icon(
                imageVector = DesignIcons.Settings,
                contentDescription = "Settings",
                tint = c.ink,
                modifier = Modifier.size(22.dp),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Wordmark + tagline + home pill — also below the status bar.
            WordmarkBlock(
                homeStationName = homeStationName,
                onSetHome = onNavigateToSettings,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp)
            )

            // Optional BRouter setup card — kept here, restyled to fit
            // the warm palette.
            if (!brouterInstalled) {
                Spacer(Modifier.height(14.dp))
                BRouterSetupRow(
                    onInstall = onInstallBRouter,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            ) {
                if (loaded) {
                    LoadedCard(
                        routeInfo = routeInfo!!,
                        uiState = uiState,
                        onOpenMap = onNavigateToMap,
                        onReplace = { launcher.launch(gpxPickerMimeTypes) },
                        onDelete = { showClearConfirm = true },
                        onReloadStations = onReloadStations,
                    )
                } else {
                    EmptyCard(
                        isLoading = uiState.isLoading,
                        loadingStatus = uiState.stationDiscoveryStatus,
                        error = uiState.error,
                        onImport = { launcher.launch(gpxPickerMimeTypes) },
                    )
                }
            }

            // Bottom action stack — navigationBarsPadding so the
            // buttons clear the 3-button gesture bar on Android
            // devices that still use it.
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (loaded) {
                    PeachButton(
                        text = "View on Map",
                        icon = DesignIcons.Route,
                        onClick = onNavigateToMap,
                        large = true,
                    )
                    OutlinePillButton(
                        text = "Offline Map",
                        icon = DesignIcons.Layers,
                        onClick = onDownloadOfflineMap,
                        enabled = !downloadState.active,
                    )
                    if (downloadState.active || downloadState.label == "Done!") {
                        LinearProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = c.accentDark,
                            trackColor = c.bgWarm,
                        )
                        Text(
                            text = downloadState.label,
                            fontSize = 12.sp,
                            color = c.inkSoft,
                        )
                    }
                    OutlinePillButton(
                        text = "Replace GPX",
                        icon = DesignIcons.Plus,
                        onClick = { launcher.launch(gpxPickerMimeTypes) },
                        enabled = !uiState.isLoading,
                    )
                } else {
                    PeachButton(
                        text = "Import GPX File",
                        icon = DesignIcons.Plus,
                        onClick = { launcher.launch(gpxPickerMimeTypes) },
                        enabled = !uiState.isLoading,
                        large = true,
                    )
                }
            }
        }
    }
    if (showClearConfirm) {
        ClearRouteDialog(
            onConfirm = {
                showClearConfirm = false
                onClearRoute()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
    }
}

@Composable
private fun ClearRouteDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalHomePalette.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("Delete", color = c.accentDark, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = c.inkSoft)
            }
        },
        title = { Text("Delete the imported route?", color = c.ink) },
        text = {
            Text(
                text = "The GPX, its precomputed stations, route POIs and any saved destination will be removed.",
                color = c.inkSoft,
                fontSize = 13.sp,
            )
        },
        containerColor = c.bg,
    )
}

@Composable
private fun WordmarkBlock(
    homeStationName: String?,
    onSetHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalHomePalette.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // App icon as the brand mark next to the wordmark. We can't
            // render `R.mipmap.ic_launcher` directly via painterResource
            // because it's an adaptive icon (AdaptiveIconDrawable XML)
            // which Compose's resource loader only supports for vectors
            // / rasters. Rebuild the same visual by stacking the
            // launcher's foreground PNG over its white background inside
            // a rounded-corner box — matches the home-screen tile.
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(dev.gpxit.app.R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    // Foreground PNGs are drawn on a 108 dp canvas with
                    // a 72 dp safe zone; scale up so the glyph fills the
                    // 56 dp rounded box the way the launcher renders it.
                    modifier = Modifier.size(84.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "GPXIT",
                color = c.ink,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 40.sp,
                letterSpacing = (-1.5).sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Import a route, find your way home.",
            color = c.inkSoft,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        HomePill(
            homeStationName = homeStationName,
            onSetHome = onSetHome,
        )
    }
}

@Composable
private fun HomePill(
    homeStationName: String?,
    onSetHome: () -> Unit,
) {
    val c = LocalHomePalette.current
    val isSet = homeStationName != null
    val container = if (isSet) c.pillBg else c.pillBgMissing
    val foreground = if (isSet) c.inkSoft else c.pillFgMissing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onSetHome)
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(
            imageVector = DesignIcons.Home,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (isSet) "Home · $homeStationName" else "Set your home station",
            color = foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun BRouterSetupRow(
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalHomePalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.bgWarm)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = "BRouter — optional",
                color = c.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Install for offline bike-aware navigation",
                color = c.inkSoft,
                fontSize = 12.sp,
            )
        }
        OutlinePillButton(
            text = "Install",
            onClick = onInstall,
            small = true,
        )
    }
}

@Composable
private fun LoadedCard(
    routeInfo: dev.gpxit.app.domain.RouteInfo,
    uiState: ImportUiState,
    onOpenMap: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
    onReloadStations: () -> Unit,
) {
    val c = LocalHomePalette.current
    val (from, to) = remember(routeInfo.name) { splitRouteName(routeInfo.name) }
    val (climbM, descentM) = remember(routeInfo.points) {
        routeClimbDescentMeters(routeInfo.points)
    }
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(c.cocoa)
            .clickable(onClick = onOpenMap)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header row.
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ACTIVE ROUTE",
                    color = c.cocoaInkSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                )
                Spacer(Modifier.height(2.dp))
                if (to != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = from,
                            color = c.cocoaInk,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            letterSpacing = (-0.3).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = " → ",
                            color = c.cocoaInkSoft,
                            fontWeight = FontWeight.Medium,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = to,
                            color = c.cocoaInk,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            letterSpacing = (-0.3).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                } else {
                    Text(
                        text = from.ifBlank { "Imported route" },
                        color = c.cocoaInk,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        letterSpacing = (-0.3).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Overflow menu — sits where IconMenu lives in the
            // handoff. Hosts the Replace / Delete actions so the
            // card itself stays one big "tap to open the map" hit
            // target. Anchored as a Box so the dropdown opens
            // below it instead of from the screen corner.
            Box {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DesignIcons.Menu,
                        contentDescription = "Route actions",
                        tint = c.cocoaInk,
                        modifier = Modifier.size(16.dp),
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = c.bg,
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Replace GPX", color = c.ink) },
                        leadingIcon = {
                            Icon(
                                imageVector = DesignIcons.Plus,
                                contentDescription = null,
                                tint = c.ink,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onReplace()
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Reload stations", color = c.ink) },
                        leadingIcon = {
                            Icon(
                                imageVector = DesignIcons.Refresh,
                                contentDescription = null,
                                tint = c.ink,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        enabled = !uiState.isLoading,
                        onClick = {
                            menuOpen = false
                            onReloadStations()
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Delete route", color = c.accentDark) },
                        leadingIcon = {
                            Icon(
                                imageVector = DesignIcons.Trash,
                                contentDescription = null,
                                tint = c.accentDark,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }

        // Route preview thumb. Renders an actual osmdroid MapView
        // with the route polyline on top so the user gets real
        // surroundings (rivers, roads, city footprints) instead of
        // the flat-coloured vector preview the design originally
        // showed. The cocoa background still bleeds through while
        // tiles are loading, giving a nicer transition than the
        // default light-grey "missing tile" colour. Tapping the
        // thumb opens the map — the parent Column's `clickable` is
        // shadowed by the MapView's own touch handling, so we
        // route the tap through a transparent click overlay that
        // mirrors `onOpenMap` directly.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.cocoaThumbBg)
        ) {
            RouteMapPreview(
                routeInfo = routeInfo,
                modifier = Modifier.fillMaxSize(),
            )
            Spacer(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClick = onOpenMap)
            )
        }

        // Offline / failed-discovery banner — only shown when the last
        // station-discovery attempt couldn't reach the transit provider.
        // Persists across app restarts via the marker file in
        // RouteStorage.stationDiscoveryFailed().
        if (uiState.stationDiscoveryFailed) {
            StationRetryBanner(
                retrying = uiState.isLoading,
                onRetry = onReloadStations,
            )
        }

        // Stats row: distance / track points / stations.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatColumn(
                value = "%.1f".format(uiState.totalDistanceKm),
                unit = "km",
                label = "DISTANCE",
                modifier = Modifier.weight(1f),
            )
            StatColumn(
                value = uiState.pointCount.toString(),
                unit = "pts",
                label = "TRACK",
                modifier = Modifier.weight(1f),
            )
            StatColumn(
                value = uiState.stationCount.toString(),
                unit = "",
                label = "STATIONS",
                modifier = Modifier.weight(1f),
            )
        }

        // Elevation strip.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ELEVATION",
                        color = c.cocoaInkSoft,
                        fontSize = 11.sp,
                        letterSpacing = 0.3.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "+$climbM",
                            color = c.cocoaInk,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = " / −$descentM m",
                            color = c.cocoaInkSoft,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    }
                }
                ElevationSpark(
                    points = routeInfo.points,
                    strokeColor = c.accent,
                    modifier = Modifier
                        .width(140.dp)
                        .height(32.dp),
                )
            }
        }
    }
}

@Composable
private fun StationRetryBanner(
    retrying: Boolean,
    onRetry: () -> Unit,
) {
    val c = LocalHomePalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = DesignIcons.Alert,
            contentDescription = null,
            tint = c.accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Couldn't reach transit service",
            color = c.cocoaInk,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (retrying) {
            CircularProgressIndicator(
                color = c.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(c.accent)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Retry",
                    color = c.accentDeepInk,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun StatColumn(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalHomePalette.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = c.cocoaInk,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = (-0.4).sp,
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    color = c.cocoaInkSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = c.cocoaInkSoft,
            fontSize = 11.sp,
            letterSpacing = 0.3.sp,
        )
    }
}

@Composable
private fun EmptyCard(
    isLoading: Boolean,
    loadingStatus: String?,
    error: String?,
    onImport: () -> Unit,
) {
    val c = LocalHomePalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(c.emptyCardBg)
            .border(
                width = 1.5.dp,
                brush = SolidColor(c.emptyCardBorder),
                shape = RoundedCornerShape(22.dp)
            )
            // Whole card mirrors the bottom "Import GPX File" button while
            // empty — disabled mid-import so a second tap can't fire the
            // SAF picker on top of the in-flight launcher.
            .clickable(enabled = !isLoading, onClick = onImport)
            .padding(horizontal = 20.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(c.emptyIconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DesignIcons.Route,
                    contentDescription = null,
                    tint = c.emptyIconInk,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                text = if (isLoading) "Importing route…" else "No route loaded",
                color = c.ink,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = (-0.3).sp,
            )
            Text(
                text = when {
                    isLoading && loadingStatus != null -> loadingStatus
                    isLoading -> "Reading GPX, scanning stations and POIs."
                    error != null -> error
                    else -> "Import a GPX file to see your route, elevation, and nearby stations along the way."
                },
                color = if (error != null) Color(0xFFC0392B) else c.inkSoft,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (isLoading) CircularProgressIndicator(color = c.accent)
        }
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

@Composable
private fun PeachButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    large: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = LocalHomePalette.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(if (large) 56.dp else 48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.accentDeep,
            contentColor = c.accentDeepInk,
            disabledContainerColor = c.accentDeep.copy(alpha = 0.5f),
            disabledContentColor = c.accentDeepInk.copy(alpha = 0.6f),
        ),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = c.accentDeepInk,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            fontSize = if (large) 17.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.1).sp,
        )
    }
}

@Composable
private fun OutlinePillButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    small: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = LocalHomePalette.current
    // Dark-mode border is a thin white line at low alpha; light-mode
    // matches the design's 18%-black rule.
    val border = if (c.isDark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.18f)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = if (small) Modifier.height(36.dp) else Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, border),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = c.ink,
            disabledContentColor = c.ink.copy(alpha = 0.5f),
        ),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = c.ink,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            fontSize = if (small) 13.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
        )
    }
}

