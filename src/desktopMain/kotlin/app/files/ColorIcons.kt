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
enum class KnownKind { Home, Desktop, Downloads, Documents, Pictures, Music, Videos, Folder }

/** The user's own folder (C:\Users\name). */
val userHome: String = System.getProperty("user.home")
fun isUserHome(path: String) = path.trimEnd('\\').equals(userHome.trimEnd('\\'), ignoreCase = true)

fun knownKindOf(f: PinnedFolder): KnownKind {
    val n = f.name.lowercase()
    val leaf = f.path.trimEnd('\\').substringAfterLast('\\').lowercase()
    fun any(vararg k: String) = k.any { it == n || it == leaf }
    return when {
        isUserHome(f.path) -> KnownKind.Home
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
        KnownKind.Home -> Icon(Icons.Filled.AccountCircle, null, Modifier.size(size), tint = Color(0xFF2B88D8))
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

// ---------------------------------------------------------------------- WSL distributions

/** Docker Desktop's own WSL distributions (docker-desktop, and docker-desktop-data on older versions). */
fun isDockerDistro(name: String) = name.startsWith("docker-desktop", ignoreCase = true)

/**
 * Sidebar icon for a WSL distribution: Docker's whale for Docker Desktop, otherwise the icon WSL
 * registered for the distribution, otherwise a Linux penguin.
 */
@Composable
fun WslGlyph(name: String, icon: androidx.compose.ui.graphics.ImageBitmap?, size: Dp) {
    when {
        isDockerDistro(name) -> DockerGlyph(size)
        icon != null -> androidx.compose.foundation.Image(
            icon, null, Modifier.size(size), filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        )
        else -> TuxGlyph(size)
    }
}

/** A small Linux penguin: black body, white belly, orange beak and feet (drawn, not copied). */
@Composable
fun TuxGlyph(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val orange = Color(0xFFF6B21B)
        // feet first, so the body overlaps them
        drawOval(orange, topLeft = Offset(w * 0.16f, h * 0.84f), size = Size(w * 0.32f, h * 0.15f))
        drawOval(orange, topLeft = Offset(w * 0.52f, h * 0.84f), size = Size(w * 0.32f, h * 0.15f))
        drawOval(Color(0xFF1E1E1E), topLeft = Offset(w * 0.18f, h * 0.04f), size = Size(w * 0.64f, h * 0.86f))
        drawOval(Color(0xFFF7F4EC), topLeft = Offset(w * 0.29f, h * 0.36f), size = Size(w * 0.42f, h * 0.50f))
        // eyes
        drawOval(Color.White, topLeft = Offset(w * 0.35f, h * 0.18f), size = Size(w * 0.13f, h * 0.15f))
        drawOval(Color.White, topLeft = Offset(w * 0.52f, h * 0.18f), size = Size(w * 0.13f, h * 0.15f))
        drawCircle(Color(0xFF1E1E1E), radius = w * 0.035f, center = Offset(w * 0.43f, h * 0.27f))
        drawCircle(Color(0xFF1E1E1E), radius = w * 0.035f, center = Offset(w * 0.57f, h * 0.27f))
        // beak
        drawOval(orange, topLeft = Offset(w * 0.39f, h * 0.33f), size = Size(w * 0.22f, h * 0.11f))
    }
}

/** Docker's whale carrying containers, simplified for 16 px. */
@Composable
fun DockerGlyph(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val blue = Color(0xFF1D91E6)
        // hull, with the tail rising on the right
        val hull = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.03f, h * 0.53f)
            lineTo(w * 0.80f, h * 0.53f)
            quadraticTo(w * 0.88f, h * 0.42f, w * 0.98f, h * 0.44f)
            quadraticTo(w * 0.94f, h * 0.56f, w * 0.86f, h * 0.60f)
            cubicTo(w * 0.76f, h * 0.84f, w * 0.48f, h * 0.90f, w * 0.30f, h * 0.88f)
            quadraticTo(w * 0.08f, h * 0.84f, w * 0.03f, h * 0.53f)
            close()
        }
        drawPath(hull, blue)
        // containers: four on deck, two above, one on top
        val b = w * 0.125f; val g = w * 0.035f
        fun box(col: Int, row: Int) = drawRoundRect(
            blue, topLeft = Offset(w * 0.10f + col * (b + g), h * 0.53f - (row + 1) * (b + g)),
            size = Size(b, b), cornerRadius = CornerRadius(w * 0.02f),
        )
        for (c in 0..3) box(c, 0)
        box(2, 1); box(3, 1)
        box(3, 2)
        // eye
        drawCircle(Color.White, radius = w * 0.03f, center = Offset(w * 0.24f, h * 0.66f))
    }
}
