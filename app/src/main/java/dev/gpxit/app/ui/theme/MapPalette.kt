package dev.gpxit.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Palette tokens from the `GPXIT Map UI.html` handoff — light-only,
 * matching the design's `<meta color-scheme="light">`. The map screen
 * and its sheets / settings accordion all read these directly and do
 * not follow Material's dynamic color scheme; the warm cream surface
 * is intentional regardless of system night mode.
 *
 * Mirrors the `TRACK` object in the handoff's
 * `components/variations.jsx`.
 */
data class MapPalette(
    val accent: Color,        // cycling green
    val accentDark: Color,    // deeper cycling green
    val accentTint: Color,    // 12% green for chip backgrounds
    val ink: Color,
    val inkSoft: Color,
    val inkLight: Color,
    val surface: Color,       // pure white (buttons, cards)
    val surfaceMuted: Color,  // warm cream panel
    val sheetBg: Color,       // fullscreen sheet background
    val line: Color,          // 8%-black hairline
    val trackActive: Color,   // red-square track indicator
    val transferBg: Color,    // amber transfers chip bg
    val transferInk: Color,   // amber transfers chip text
)

val MapPaletteDefault: MapPalette = MapPalette(
    // oklch(62% 0.15 145) → sRGB ≈ #2FA04A
    accent = Color(0xFF2FA04A),
    // oklch(48% 0.14 145) → sRGB ≈ #1F7A38
    accentDark = Color(0xFF1F7A38),
    accentTint = Color(0x1F2FA04A),
    ink = Color(0xFF1A1A1A),
    inkSoft = Color(0xFF5A5A5A),
    inkLight = Color(0xFF8A8A8A),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF4F3EF),
    sheetBg = Color(0xFFF7F5EF),
    line = Color(0x14000000),
    trackActive = Color(0xFFC23B3B),
    transferBg = Color(0x24DC9628),
    transferInk = Color(0xFF9A6100),
)

val LocalMapPalette = staticCompositionLocalOf { MapPaletteDefault }
