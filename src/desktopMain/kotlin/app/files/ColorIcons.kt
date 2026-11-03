package app.files

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp

/** Colourful icons in the style of the original's Windows 11 shell icons (drawn, not copied). */
enum class KnownKind { Desktop, Downloads, Documents, Pictures, Music, Videos, Folder }

fun knownKind(f: PinnedFolder): KnownKind {
    val n = f.name.lowercase()
    val leaf = f.path.trimEnd('\\').substringAfterLast('\\').lowercase()
    fun any(vararg k: String) = k.any { it == n || it == leaf }
    return when {
        any("desktop", "桌面") -> KnownKind.Desktop
        any("downloads", "下載") -> KnownKind.Downloads
        any("documents", "文件") -> KnownKind.Documents
        any("pictures", "圖片") -> KnownKind.Pictures
        any("music", "音樂") -> KnownKind.Music
        any("videos", "影片") -> KnownKind.Videos
        else -> KnownKind.Folder
    }
}

@Composable
fun KnownFolderGlyph(kind: KnownKind, size: Dp) {
    when (kind) {
        KnownKind.Desktop -> Canvas(Modifier.size(size)) {
            val w = this.size.width; val h = this.size.height
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFF39C2EE), Color(0xFF1173C9))),
                topLeft = Offset(w * 0.06f, h * 0.16f), size = Size(w * 0.88f, h * 0.58f), cornerRadius = CornerRadius(w * 0.06f),
            )
            drawRoundRect(Color(0xFF2A3A4A), topLeft = Offset(w * 0.06f, h * 0.70f), size = Size(w * 0.88f, h * 0.1f), cornerRadius = CornerRadius(w * 0.03f))
        }
        KnownKind.Downloads -> Icon(Icons.Outlined.Download, null, Modifier.size(size), tint = Color(0xFF13A86B))
        KnownKind.Documents -> Icon(Icons.Filled.Description, null, Modifier.size(size), tint = Color(0xFF7C93B5))
        KnownKind.Pictures -> Icon(Icons.Filled.Image, null, Modifier.size(size), tint = Color(0xFF1C8BE4))
        KnownKind.Music -> Box(
            Modifier.size(size).background(Brush.linearGradient(listOf(Color(0xFFF59A44), Color(0xFFE5528C))), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.MusicNote, null, Modifier.size(size * 0.6f), tint = Color.White) }
        KnownKind.Videos -> Icon(Icons.Filled.VideoLibrary, null, Modifier.size(size), tint = Color(0xFF8C3FE0))
        KnownKind.Folder -> FolderGlyph(size)
    }
}

@Composable
fun FolderGlyph(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(Color(0xFFE8A92A), topLeft = Offset(0f, h * 0.14f), size = Size(w * 0.45f, h * 0.2f), cornerRadius = CornerRadius(w * 0.06f))
        drawRoundRect(Color(0xFFF2B83A), topLeft = Offset(0f, h * 0.22f), size = Size(w, h * 0.66f), cornerRadius = CornerRadius(w * 0.07f))
        drawRoundRect(Color(0xFFFFD25E), topLeft = Offset(0f, h * 0.34f), size = Size(w, h * 0.54f), cornerRadius = CornerRadius(w * 0.07f))
    }
}

enum class DriveKind { System, Hdd, Usb }

/** Flat drive glyph: system drive gets a four-pane badge, external drives a portable case. */
@Composable
fun DriveGlyph(kind: DriveKind, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        if (kind == DriveKind.Usb) {
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFFD9DCE0), Color(0xFFA9AEB4))),
                topLeft = Offset(w * 0.28f, h * 0.08f), size = Size(w * 0.44f, h * 0.84f), cornerRadius = CornerRadius(w * 0.08f),
            )
            return@Canvas
        }
        drawRoundRect(Color(0xFF8A9199), topLeft = Offset(w * 0.04f, h * 0.52f), size = Size(w * 0.92f, h * 0.3f), cornerRadius = CornerRadius(w * 0.05f))
        drawRoundRect(Color(0xFF5B636B), topLeft = Offset(w * 0.04f, h * 0.70f), size = Size(w * 0.92f, h * 0.12f), cornerRadius = CornerRadius(w * 0.04f))
        drawCircle(Color(0xFF3CC36B), radius = w * 0.04f, center = Offset(w * 0.16f, h * 0.76f))
        if (kind == DriveKind.System) {
            val q = w * 0.15f; val g = w * 0.03f; val x0 = w * 0.08f; val y0 = h * 0.12f
            for (i in 0..1) for (j in 0..1) {
                drawRect(Color(0xFF1E88E5), topLeft = Offset(x0 + i * (q + g), y0 + j * (q + g)), size = Size(q, q))
            }
        }
    }
}

fun sectionIcon(key: String): Pair<ImageVector, Color> = when (key) {
    "pinned" -> Icons.Filled.PushPin to Color(0xFF2B7BD4)
    "drives" -> Icons.Filled.Storage to Color(0xFF5B636B)
    "cloud" -> Icons.Filled.Cloud to Color(0xFFA978E0)
    "network" -> Icons.Filled.Public to Color(0xFF2E9D4F)
    "wsl" -> Icons.Filled.Terminal to Color(0xFF3B6FB6)
    "tags" -> Icons.Filled.LocalOffer to Color(0xFFF7A707)
    else -> Icons.Filled.Folder to Color(0xFFF2B83A)
}
