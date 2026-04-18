package dev.gpxit.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.gpxit.app.ui.import_route.ImportViewModel

// Warm-cream scrim that matches the Import / Settings backgrounds so
// the system status/nav bars blend into the app. Paired with
// SystemBarStyle.light below to pin the system icons to DARK regardless
// of whether the device's night-mode setting is light or dark.
private val lightScrim = android.graphics.Color.argb(0xff, 0xFA, 0xFA, 0xFA)
private val darkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

class MainActivity : ComponentActivity() {

    private val importViewModel: ImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle GPX shared via intent
        handleIncomingIntent(intent)

        // Pin status + nav bars to a light style (dark system icons on
        // a warm-cream scrim). Our content is always light regardless
        // of the device night-mode setting — the Import screen's
        // background is Palette.bg, the map tiles are light — so we
        // don't want the system flipping to a dark scrim / light
        // icons on dark-mode devices. SystemBarStyle.light forces the
        // icon tint to dark and draws the declared scrim.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(lightScrim, darkScrim),
            navigationBarStyle = SystemBarStyle.light(lightScrim, darkScrim),
        )

        setContent {
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
