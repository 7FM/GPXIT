package dev.gpxit.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Palette tokens from the `GPXIT Map UI.html` handoff. Light values
 * mirror the design's default `:root` and dark values the
 * `[data-theme="dark"]` overrides. The map screen, its sheets, and
 * the settings accordion all read these directly instead of going
 * through Material's `colorScheme` because the handoff specifies a
 * warm cream surface in light mode and a dark slate / peach accent
 * pairing in dark mode that the dynamic Material palettes can't
 * reproduce on their own.
 *
 * Mirrors the `TRACK` object in the handoff's
 * `components/variations.jsx`.
 */
data class MapPalette(
    val accent: Color,        // primary brand fill (cycling green / peach)
    val accentDark: Color,    // deeper variant for big numbers
    val accentTint: Color,    // ~12-14% accent for chip / pin backgrounds
    val accentBg: Color,      // ~6-8% accent for the softest hover wash
    val accentFill: Color,    // ~18-20% accent for filled markers
    val onAccent: Color,      // ink colour ON top of `accent` fills
    val ink: Color,
    val inkSoft: Color,
    val inkLight: Color,
    val surface: Color,       // elevated card / button background
    val surfaceMuted: Color,  // muted surface variant
    val surfaceAlt: Color,    // secondary stripe (station-detail summary)
    val sheetBg: Color,       // full-screen page background
    val line: Color,          // hairline divider / border
    val handle: Color,        // sheet drag-handle
    val trackActive: Color,   // red-square track indicator
    val transferBg: Color,    // amber transfers chip bg
    val transferInk: Color,   // amber transfers chip text
)

val MapPaletteLight: MapPalette = MapPalette(
    // oklch(62% 0.15 145) → sRGB ≈ #2FA04A
    accent = Color(0xFF2FA04A),
    // oklch(48% 0.14 145) → sRGB ≈ #1F7A38
    accentDark = Color(0xFF1F7A38),
    accentTint = Color(0x1F2FA04A),
    accentBg = Color(0x0F2FA04A),
    accentFill = Color(0x2E2FA04A),
    onAccent = Color(0xFFFFFFFF),
    ink = Color(0xFF1A1A1A),
    inkSoft = Color(0xFF5A5A5A),
    inkLight = Color(0xFF8A8A8A),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF4F3EF),
    surfaceAlt = Color(0xFFFBFAF5),
    sheetBg = Color(0xFFF7F5EF),
    line = Color(0x14000000),
    handle = Color(0x2E000000),
    trackActive = Color(0xFFC23B3B),
    transferBg = Color(0x24DC9628),
    transferInk = Color(0xFF9A6100),
)

val MapPaletteDark: MapPalette = MapPalette(
    accent = Color(0xFFF2B89A),       // peach primary
    accentDark = Color(0xFFE9A079),
    accentTint = Color(0x24F2B89A),   // ~14% peach
    accentBg = Color(0x14F2B89A),     // ~8% peach
    accentFill = Color(0x33F2B89A),   // ~20% peach
    onAccent = Color(0xFF3A1A0C),     // rust ink on peach
    ink = Color(0xFFE9E4DE),
    inkSoft = Color(0xFF9B958C),
    inkLight = Color(0xFF6F6A63),
    surface = Color(0xFF242628),      // elevated card
    surfaceMuted = Color(0xFF2A2C2F),
    surfaceAlt = Color(0xFF1F2022),   // secondary stripe
    sheetBg = Color(0xFF1B1C1E),      // full-screen bg
    line = Color(0x14FFFFFF),
    handle = Color(0x38FFFFFF),
    trackActive = Color(0xFFE9A17A),
    transferBg = Color(0x1FE9A17A),
    transferInk = Color(0xFFE9A17A),
)

/** Light-only kept as an alias for the few callers that need to opt out. */
val MapPaletteDefault: MapPalette = MapPaletteLight

val LocalMapPalette = staticCompositionLocalOf { MapPaletteLight }

/**
 * Single source of truth for the GPX route polyline colour. Used both by the
 * full map (`MapComposable`) and the home-screen "Active route" preview
 * thumbnail (`RouteMapPreview`) so the two never drift apart. Pinned to the
 * Material blue the main map has always used (`#4285F4`).
 */
val RoutePolylineColor: Color = Color(0xFF4285F4)

/**
 * Picks the right palette for the resolved theme (system / forced
 * light / forced dark). Reads [LocalIsDark] which `GpxitTheme`
 * provides at the root, so this honours the user's `ThemeMode`
 * override automatically. Use at the root of any map / settings
 * screen when wrapping content in
 * `CompositionLocalProvider(LocalMapPalette provides …)`.
 */
@Composable
fun rememberMapPalette(): MapPalette =
    if (LocalIsDark.current) MapPaletteDark else MapPaletteLight
