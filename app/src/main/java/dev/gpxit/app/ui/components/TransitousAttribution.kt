package dev.gpxit.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import dev.gpxit.app.data.transit.TransitBackendRegistry
import dev.gpxit.app.data.transit.TransitousBackend
import dev.gpxit.app.domain.TrainConnection

/** Transitous asks for a link to its data sources wherever its results are shown. */
@Composable
fun TransitousAttribution(
    connections: List<TrainConnection>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (connections.none { it.backendId == TransitBackendRegistry.TRANSITOUS }) return
    val uriHandler = LocalUriHandler.current
    Text(
        text = "Some connections via Transitous · data sources",
        color = color,
        fontSize = 11.sp,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable { uriHandler.openUri(TransitousBackend.SOURCES_URL) },
    )
}
