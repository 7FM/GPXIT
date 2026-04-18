package dev.gpxit.app.ui.import_route

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
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
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val routeInfo by viewModel.routeInfo.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importGpx(it) }
    }

    val loaded = routeInfo != null && !uiState.isLoading

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.bg)
            .statusBarsPadding()
    ) {
        // Settings gear in the top-right.
        IconButton(
            onClick = onNavigateToSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 4.dp, end = 8.dp)
        ) {
            SettingsGearIcon(tint = Palette.ink)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Wordmark + tagline + home pill.
            WordmarkBlock(
                homeStationName = homeStationName,
                onSetHome = onNavigateToSettings,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp)
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
                    )
                } else {
                    EmptyCard(
                        isLoading = uiState.isLoading,
                        loadingStatus = uiState.stationDiscoveryStatus,
                        error = uiState.error,
                    )
                }
            }

            // Bottom action stack.
            Column(
                modifier = Modifier
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (loaded) {
                    PeachButton(
                        text = "View on Map",
                        onClick = onNavigateToMap,
                        large = true,
                    )
                    OutlinePillButton(
                        text = "Offline Map",
                        onClick = onDownloadOfflineMap,
                        enabled = !downloadState.active,
                    )
                    if (downloadState.active || downloadState.label == "Done!") {
                        LinearProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Palette.accentDark,
                            trackColor = Palette.bgWarm,
                        )
                        Text(
                            text = downloadState.label,
                            fontSize = 12.sp,
                            color = Palette.inkSoft,
                        )
                    }
                } else {
                    PeachButton(
                        text = "Import GPX File",
                        onClick = {
                            launcher.launch(arrayOf(
                                "application/gpx+xml",
                                "application/octet-stream",
                                "text/xml",
                                "*/*"
                            ))
                        },
                        enabled = !uiState.isLoading,
                        large = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun WordmarkBlock(
    homeStationName: String?,
    onSetHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "GPXIT",
            color = Palette.ink,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 40.sp,
            letterSpacing = (-1.5).sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Import a route, find your way home.",
            color = Palette.inkSoft,
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
    val isSet = homeStationName != null
    val container = if (isSet) Palette.bgWarm else Color(0xFFFADAD3)
    val foreground = if (isSet) Palette.inkSoft else Palette.accentInk
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onSetHome)
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        HomeGlyph(tint = foreground)
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.bgWarm)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = "BRouter — optional",
                color = Palette.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Install for offline bike-aware navigation",
                color = Palette.inkSoft,
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
) {
    val (from, to) = remember(routeInfo.name) { splitRouteName(routeInfo.name) }
    val (climbM, descentM) = remember(routeInfo.points) {
        routeClimbDescentMeters(routeInfo.points)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.cocoa)
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
                    color = Palette.cocoaInkSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                )
                Spacer(Modifier.height(2.dp))
                if (to != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = from,
                            color = Palette.cocoaInk,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            letterSpacing = (-0.3).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = " → ",
                            color = Palette.cocoaInkSoft,
                            fontWeight = FontWeight.Medium,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = to,
                            color = Palette.cocoaInk,
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
                        color = Palette.cocoaInk,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        letterSpacing = (-0.3).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Route preview thumb. Tapping it (or anywhere on the card)
        // opens the map — same as the dedicated "View on Map" button.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.cocoaThumbBg)
        ) {
            RouteThumb(
                points = routeInfo.points,
                routeColor = Palette.accent,
                backgroundColor = Palette.cocoaThumbBg,
                modifier = Modifier.fillMaxSize(),
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
                        color = Palette.cocoaInkSoft,
                        fontSize = 11.sp,
                        letterSpacing = 0.3.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "+$climbM",
                            color = Palette.cocoaInk,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = " / −$descentM m",
                            color = Palette.cocoaInkSoft,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    }
                }
                ElevationSpark(
                    points = routeInfo.points,
                    strokeColor = Palette.accent,
                    modifier = Modifier
                        .width(140.dp)
                        .height(32.dp),
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
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = Palette.cocoaInk,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = (-0.4).sp,
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    color = Palette.cocoaInkSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = Palette.cocoaInkSoft,
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
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.bgWarm)
            .border(
                width = 1.5.dp,
                brush = SolidColor(Color.Black.copy(alpha = 0.18f)),
                shape = RoundedCornerShape(22.dp)
            )
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
                    .background(Palette.accent),
                contentAlignment = Alignment.Center,
            ) {
                RouteGlyph(tint = Palette.accentInk, sizeDp = 30)
            }
            Text(
                text = if (isLoading) "Importing route…" else "No route loaded",
                color = Palette.ink,
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
                color = if (error != null) Color(0xFFC0392B) else Palette.inkSoft,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (isLoading) CircularProgressIndicator(color = Palette.accentDark)
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
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(if (large) 56.dp else 48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.accent,
            contentColor = Palette.accentInk,
            disabledContainerColor = Palette.accent.copy(alpha = 0.5f),
            disabledContentColor = Palette.accentInk.copy(alpha = 0.6f),
        ),
    ) {
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
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = if (small) Modifier.height(36.dp) else Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Palette.ink,
            disabledContentColor = Palette.ink.copy(alpha = 0.5f),
        ),
    ) {
        Text(
            text = text,
            fontSize = if (small) 13.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Inline glyphs (no extra resource files)
// ---------------------------------------------------------------------------

@Composable
private fun SettingsGearIcon(tint: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = size.width / 2f - 1f
        val innerR = outerR * 0.55f
        val toothW = outerR * 0.32f
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45.0))
            val cosA = kotlin.math.cos(angle).toFloat()
            val sinA = kotlin.math.sin(angle).toFloat()
            drawLine(
                tint,
                start = Offset(cx + innerR * cosA, cy + innerR * sinA),
                end = Offset(cx + outerR * cosA, cy + outerR * sinA),
                strokeWidth = toothW
            )
        }
        drawCircle(tint, radius = innerR + 1f, center = Offset(cx, cy))
        drawCircle(Palette.bg, radius = innerR * 0.45f, center = Offset(cx, cy))
    }
}

@Composable
private fun HomeGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.2f
        // Roof + walls outline of a tiny house.
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.1f, h * 0.55f)
            lineTo(w * 0.5f, h * 0.1f)
            lineTo(w * 0.9f, h * 0.55f)
            lineTo(w * 0.9f, h * 0.95f)
            lineTo(w * 0.1f, h * 0.95f)
            close()
        }
        drawPath(path, tint, style = Stroke(width = stroke))
    }
}

@Composable
private fun RouteGlyph(tint: Color, sizeDp: Int) {
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val s = size.width
        val stroke = 2.2f * (s / 24f)
        val r1 = 2f * (s / 24f)
        // Two endpoint dots + an S-curve between them (matches the
        // design's IconRoute glyph).
        drawCircle(tint, radius = r1, center = Offset(s * 0.25f, s * 0.21f))
        drawCircle(tint, radius = r1, center = Offset(s * 0.75f, s * 0.79f))
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.25f, s * 0.29f)
            cubicTo(
                s * 0.25f, s * 0.5f,
                s * 0.6f, s * 0.34f,
                s * 0.6f, s * 0.55f,
            )
            cubicTo(
                s * 0.6f, s * 0.7f,
                s * 0.75f, s * 0.71f,
                s * 0.75f, s * 0.71f,
            )
        }
        drawPath(path, tint, style = Stroke(width = stroke))
    }
}
