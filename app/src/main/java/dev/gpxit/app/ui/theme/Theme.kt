package dev.gpxit.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * The user's selected theme override, persisted in DataStore.
 *  - [SYSTEM] follows the device night-mode setting
 *  - [LIGHT] / [DARK] force the chosen mode regardless of system
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Resolved "is the UI currently rendering dark?" flag, exposed as a
 * CompositionLocal so individual screens / palettes can react to the
 * user's `ThemeMode` override without each having to plumb it through
 * their own props. Set by [GpxitTheme] at the root of the tree;
 * defaults to false so callers in @Preview don't crash.
 */
val LocalIsDark = staticCompositionLocalOf { false }

@Composable
fun GpxitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    // System-bar icon tint is driven from GpxitApp via a single
    // SideEffect keyed to the resolved dark flag — see `GpxitApp`.

    CompositionLocalProvider(LocalIsDark provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
