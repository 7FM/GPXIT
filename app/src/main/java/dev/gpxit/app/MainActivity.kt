package dev.gpxit.app

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.gpxit.app.ui.import_route.ImportViewModel

// Scrim colours for the system bars, chosen to match the Import /
// Settings backgrounds in each theme. The Import screen's background
// is #FAFAFA in light mode and #1B1C1E in dark mode (from
// HomePalette.bg) — same values here so the system bars blend into
// the page fill.
private val scrimLight = android.graphics.Color.argb(0xff, 0xFA, 0xFA, 0xFA)
private val scrimDark = android.graphics.Color.argb(0xff, 0x1B, 0x1C, 0x1E)

class MainActivity : ComponentActivity() {

    private val importViewModel: ImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle GPX shared via intent
        handleIncomingIntent(intent)

        // Mirror the device night-mode setting onto the system bars so
        // the Import screen's cream bg (light) or near-black bg (dark)
        // extends seamlessly under the status and nav bars. Auto picks
        // dark icons for light scrim and vice versa via the returned
        // lambda.
        val isNightMode = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val style = if (isNightMode) {
            SystemBarStyle.dark(scrimDark)
        } else {
            SystemBarStyle.light(scrimLight, scrimDark)
        }
        enableEdgeToEdge(
            statusBarStyle = style,
            navigationBarStyle = style,
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
