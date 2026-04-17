package dev.gpxit.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import dev.gpxit.app.ui.import_route.ImportViewModel

// Scrim colors for the nav/status bars under edge-to-edge on Android 15+.
// Values come from the canonical enableEdgeToEdge snippet in the Android docs —
// a near-opaque white in light mode and a translucent dark grey in dark mode,
// so the system icons stay legible regardless of what the app draws behind.
private val lightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val darkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

class MainActivity : ComponentActivity() {

    private val importViewModel: ImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle GPX shared via intent
        handleIncomingIntent(intent)

        setContent {
            // Re-apply edge-to-edge with scrim styles that track the Compose
            // dark-theme state. targetSdk 35 enforces edge-to-edge and the
            // default transparent status bar leaves icons white-on-white on
            // light screens like Import — the scrim fixes the contrast.
            val darkTheme = isSystemInDarkTheme()
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim,
                        darkScrim,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim,
                        darkScrim,
                    ) { darkTheme },
                )
                onDispose {}
            }
            GpxitApp(importViewModel = importViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { importViewModel.importGpx(it) }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    importViewModel.importGpx(uri)
                }
            }
        }
    }
}
