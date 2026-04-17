package dev.gpxit.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity

/**
 * Paints a scrim over the status-bar area so the system icons stay legible.
 *
 * On targetSdk 35 (Android 15+) the platform enforces edge-to-edge and the
 * status bar is fully transparent — `window.statusBarColor` and the scrim
 * parameters of `SystemBarStyle` are ignored. The recommended fix (per
 * developer.android.com/develop/ui/compose/system/system-bars) is to draw
 * this protection composable on top of the app content.
 *
 * Uses the theme surface container color fading to transparent, so the
 * top of the screen matches the app's background on the Import screen
 * and gently blends into the map on the Map screen.
 */
@Composable
fun StatusBarProtection(
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(
                with(LocalDensity.current) {
                    (WindowInsets.statusBars.getTop(this) * 1.2f).toDp()
                }
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = 1f),
                        color.copy(alpha = 0.8f),
                        Color.Transparent
                    )
                )
            )
    )
}
