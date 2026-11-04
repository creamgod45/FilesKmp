package app.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Window backdrop choices (BackdropMaterialType in the original; default MicaAlt). */
enum class Backdrop { Solid, Mica, MicaAlt, Acrylic, ThinAcrylic;
    val text: String get() = when (this) {
        Solid -> if (S.zh) "純色" else "Solid"
        Mica -> "Mica"
        MicaAlt -> "Mica Alt"
        Acrylic -> if (S.zh) "壓克力" else "Acrylic"
        ThinAcrylic -> if (S.zh) "薄壓克力" else "Thin acrylic"
    }
}

/** Background picture fit (Stretch in the original; default UniformToFill). */
enum class ImageFit { UniformToFill, Uniform, Fill, None;
    val text: String get() = when (this) {
        UniformToFill -> if (S.zh) "填滿" else "Fill (crop)"
        Uniform -> if (S.zh) "等比例" else "Fit"
        Fill -> if (S.zh) "延伸" else "Stretch"
        None -> if (S.zh) "原始大小" else "Original size"
    }
    val scale: ContentScale get() = when (this) {
        UniformToFill -> ContentScale.Crop; Uniform -> ContentScale.Fit; Fill -> ContentScale.FillBounds; None -> ContentScale.None
    }
}

enum class ImageAlign { TopLeft, Top, Center, Bottom, BottomRight;
    val text: String get() = when (this) {
        TopLeft -> if (S.zh) "左上" else "Top left"
        Top -> if (S.zh) "上" else "Top"
        Center -> if (S.zh) "置中" else "Center"
        Bottom -> if (S.zh) "下" else "Bottom"
        BottomRight -> if (S.zh) "右下" else "Bottom right"
    }
    val alignment: Alignment get() = when (this) {
        TopLeft -> Alignment.TopStart; Top -> Alignment.TopCenter; Center -> Alignment.Center
        Bottom -> Alignment.BottomCenter; BottomRight -> Alignment.BottomEnd
    }
}

/** How the background picture changes when several are chosen. */
enum class BgMode { Single, Timer, OnStartup;
    val text: String get() = when (this) {
        Single -> if (S.zh) "固定一張" else "One picture"
        Timer -> if (S.zh) "定時切換" else "Change on a timer"
        OnStartup -> if (S.zh) "每次開啟時切換" else "Change each time the app opens"
    }
}

/** "Show this picture in this folder" (optionally its subfolders too). */
data class BgRule(val folder: String, val image: String, val includeSubfolders: Boolean)

/** Background colour choices — the original's AppThemeResourcesFactory list (each tint at alpha 0x32). */
class TintChoice(val en: String, val zh: String, val argb: String) { val name get() = if (S.zh) zh else en }

val TintChoices = listOf(
    TintChoice("Default", "預設", "#00000000"),
    TintChoice("Yellow gold", "金黃色", "#32FFB900"),
    TintChoice("Orange bright", "亮橘色", "#32F7630C"),
    TintChoice("Brick red", "磚紅色", "#32D13438"),
    TintChoice("Mod red", "暗紅色", "#32FF4343"),
    TintChoice("Red", "紅色", "#32EA005E"),
    TintChoice("Rose bright", "亮玫瑰色", "#32EA005E"),
    TintChoice("Blue", "藍色", "#320078D7"),
    TintChoice("Iris pastel", "淡紫色", "#328764B8"),
    TintChoice("Violet red light", "淺紫紅色", "#32B146C2"),
    TintChoice("Cool blue bright", "亮冷藍色", "#320099BC"),
    TintChoice("Seafoam", "海沫綠", "#3200B7C3"),
    TintChoice("Mint light", "淺薄荷綠", "#3200B294"),
    TintChoice("Gray", "灰色", "#327A7574"),
    TintChoice("Green", "綠色", "#32107C10"),
    TintChoice("Overcast", "陰天灰", "#32767676"),
    TintChoice("Storm", "暴風灰", "#324C4A48"),
    TintChoice("Blue gray", "藍灰色", "#3269797E"),
    TintChoice("Gray dark", "深灰色", "#324A5459"),
    TintChoice("Camouflage", "迷彩色", "#327E735F"),
)

fun parseArgb(hex: String): Color = try {
    val h = hex.removePrefix("#")
    val v = h.toLong(16)
    if (h.length == 6) Color(0xFF000000 or v) else Color(v)
} catch (_: Exception) { Color.Transparent }

object Wallpaper {
    /** Windows keeps a copy of the current wallpaper here (any format, no extension). */
    private val file = File(System.getenv("APPDATA") ?: "", "Microsoft\\Windows\\Themes\\TranscodedWallpaper")

    /** The desktop wallpaper, shrunk and heavily blurred once, like the Mica material samples it. */
    fun loadBlurred(): ImageBitmap? = try {
        if (!file.exists()) null else {
            val img = org.jetbrains.skia.Image.makeFromEncoded(file.readBytes())
            val w = 320
            val h = (img.height * w / img.width.toFloat()).toInt().coerceAtLeast(1)
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
            val paint = org.jetbrains.skia.Paint().apply {
                imageFilter = org.jetbrains.skia.ImageFilter.makeBlur(M.MICA_BLUR, M.MICA_BLUR, org.jetbrains.skia.FilterTileMode.CLAMP)
            }
            surface.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()), paint)
            surface.makeImageSnapshot().toComposeImageBitmap()
        }
    } catch (_: Throwable) { null }
}

/** Keeps the last few background pictures decoded so switching folders back and forth is instant. */
object BgCache {
    private val cache = object : LinkedHashMap<String, ImageBitmap?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 6
    }
    @Synchronized fun get(path: String): ImageBitmap? {
        if (path.isBlank()) return null
        cache[path]?.let { return it }
        return loadPicture(path).also { cache[path] = it }
    }
}

fun loadPicture(path: String): ImageBitmap? = try {
    if (path.isBlank() || !File(path).isFile) null
    else org.jetbrains.skia.Image.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap()
} catch (_: Throwable) { null }

/** How strongly each material covers the wallpaper (higher = closer to the plain theme colour). Estimated from Fluent. */
fun Backdrop.cover(): Float = when (this) {
    Backdrop.Solid -> 1f
    Backdrop.Mica -> 0.86f
    Backdrop.MicaAlt -> 0.74f
    Backdrop.Acrylic -> 0.62f
    Backdrop.ThinAcrylic -> 0.48f
}

/** Everything behind the app: backdrop material, colour tint, then the background picture. */
@Composable
fun BackdropLayer(s: AppState, dark: Boolean) {
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) { wallpaper = withContext(Dispatchers.IO) { Wallpaper.loadBlurred() } }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    // The picture can change with the folder (per-folder rules), the timer or the start-up switch.
    val wanted = s.effectiveBackground()
    LaunchedEffect(wanted) { picture = withContext(Dispatchers.IO) { BgCache.get(wanted) } }

    val base = if (dark) Color(0xFF202020) else Color(0xFFF3F3F3)
    Box(Modifier.fillMaxSize().background(base)) {
        val wp = wallpaper
        if (wp != null && s.backdrop != Backdrop.Solid) {
            Image(wp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(base.copy(alpha = s.backdrop.cover())))
        }
        val tint = parseArgb(s.backgroundTint)
        if (tint.alpha > 0f) Box(Modifier.fillMaxSize().background(tint))
        picture?.let {
            Image(
                it, null, Modifier.fillMaxSize().alpha(s.backgroundImageOpacity.coerceIn(0f, 1f)),
                contentScale = s.backgroundImageFit.scale, alignment = s.backgroundImageAlign.alignment,
            )
        }
    }
}
