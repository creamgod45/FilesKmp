package app.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Details-pane preview: pictures are drawn, text files show their first lines, others their icon. */
@Composable
fun PreviewBox(item: FileItem) {
    val p = LocalPalette.current
    var image by remember(item.path) { mutableStateOf<ImageBitmap?>(null) }
    var text by remember(item.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.path) {
        if (item.isDir) return@LaunchedEffect
        if (item.ext in FsExtra.imageExt && item.size < M.PREVIEW_MAX_BYTES) {
            image = withContext(Dispatchers.IO) {
                runCatching { org.jetbrains.skia.Image.makeFromEncoded(File(item.path).readBytes()).toComposeImageBitmap() }.getOrNull()
            }
        } else if (item.ext in FsExtra.textExt) {
            text = withContext(Dispatchers.IO) { FsExtra.readTextPreview(item.path) }
        }
    }
    when {
        image != null -> Image(
            image!!, null, Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Fit,
        )
        text != null -> Box(
            Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(4.dp)).background(p.card).padding(8.dp)
        ) {
            androidx.compose.material3.Text(
                text!!.lines().take(40).joinToString("\n"), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = p.textSecondary, lineHeight = 14.sp,
            )
        }
        // Videos (and pictures too large to decode) show their Windows thumbnail, big.
        else -> FileIcon(item, if (Thumbnails.supports(item)) 220.dp else 96.dp)
    }
}
