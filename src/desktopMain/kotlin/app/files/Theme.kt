package app.files

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Fluent-style colours approximating the original's theme brushes (App.xaml) over the Mica Alt backdrop. */
@Immutable
data class Palette(
    val dark: Boolean,
    val window: Color,          // Mica Alt backdrop behind tabs
    val layer: Color,           // address bar + sidebar (LayerOnMicaBaseAlt)
    val tabSelected: Color,
    val toolbar: Color,         // CardBackgroundSecondary
    val fileArea: Color,        // FileArea brush
    val cardStroke: Color,
    val divider: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val hover: Color,           // SubtleFillColorSecondary
    val pressed: Color,
    val selected: Color,        // ControlFillColorDefault-ish selection
    val control: Color,         // ControlFillColorDefault (omnibar)
    val controlStroke: Color,
    val accent: Color,
    val accentText: Color,
    val folder: Color,
    val folderDark: Color,
    val progressTrack: Color,
    val danger: Color,
    val flyout: Color,
    val card: Color = Color(0xFFFFFFFF),   // CardBackgroundFillColorDefault on the file area
    val solid: Color = Color(0xFFF3F3F3),  // opaque window colour for dialogs (SolidBackgroundFillColorBase)
)

val LightPalette = Palette(
    dark = false,
    // The original's theme brushes (App.xaml:120-155) are translucent so the backdrop shows through.
    window = Color(0x00000000),
    layer = Color(0x00000000),        // address bar + sidebar sit directly on the backdrop
    tabSelected = Color(0xB3FFFFFF),  // LayerOnMicaBaseAltFillColorDefault
    toolbar = Color(0x99F9F9F9),      // CardBackgroundFillColorSecondary
    fileArea = Color(0xC0FCFCFC),     // App.xaml:125
    cardStroke = Color(0x14000000),
    divider = Color(0x14000000),
    text = Color(0xE4000000),
    textSecondary = Color(0x9E000000),
    textTertiary = Color(0x72000000),
    hover = Color(0x0A000000),
    pressed = Color(0x06000000),
    selected = Color(0xFFE6EEF7),
    control = Color(0xB3FFFFFF),
    controlStroke = Color(0x1A000000),
    accent = Color(0xFF0078D4),
    accentText = Color(0xFF005FB8),
    folder = Color(0xFFF5C24C),
    folderDark = Color(0xFFE2A82F),
    progressTrack = Color(0x24000000),
    danger = Color(0xFFC42B1C),
    flyout = Color(0xFFF9F9F9),
    card = Color(0xB3FFFFFF),
    solid = Color(0xFFF3F3F3),
)

val DarkPalette = Palette(
    dark = true,
    window = Color(0x00000000),
    layer = Color(0x00000000),
    tabSelected = Color(0x733A3A3A),
    toolbar = Color(0x14FFFFFF),
    fileArea = Color(0x0DFFFFFF),
    cardStroke = Color(0x19000000),
    divider = Color(0x15FFFFFF),
    text = Color(0xFFFFFFFF),
    textSecondary = Color(0xC5FFFFFF),
    textTertiary = Color(0x87FFFFFF),
    hover = Color(0x0FFFFFFF),
    pressed = Color(0x0AFFFFFF),
    selected = Color(0xFF34393F),
    control = Color(0x0FFFFFFF),
    controlStroke = Color(0x18FFFFFF),
    accent = Color(0xFF4CC2FF),
    accentText = Color(0xFF99EBFF),
    folder = Color(0xFFF5C24C),
    folderDark = Color(0xFFD9A030),
    progressTrack = Color(0x30FFFFFF),
    danger = Color(0xFFFF99A4),
    flyout = Color(0xFF2C2C2C),
    card = Color(0x12FFFFFF),
    solid = Color(0xFF202020),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Reads Windows' "apps use light theme" choice (the original follows the system by default). */
fun systemUsesDarkTheme(): Boolean = try {
    val p = ProcessBuilder(
        "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"
    ).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    out.contains("0x0") && !out.contains("0x1")
} catch (_: Exception) {
    false
}

/** Windows accent colour (DWM AccentColor is stored as ABGR). */
fun systemAccent(): Color? = try {
    val p = ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\DWM", "/v", "AccentColor").start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    val hex = Regex("0x([0-9a-fA-F]+)").find(out)?.groupValues?.get(1)
    hex?.toLong(16)?.let { v ->
        val r = (v and 0xFF).toInt()
        val g = ((v shr 8) and 0xFF).toInt()
        val b = ((v shr 16) and 0xFF).toInt()
        Color(r, g, b)
    }
} catch (_: Exception) {
    null
}
