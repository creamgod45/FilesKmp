package app.files

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = LocalPalette.current.text,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
) {
    Text(
        text, modifier = modifier, fontSize = size, color = color, fontWeight = weight,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, softWrap = maxLines > 1,
    )
}

/** A surface that shows the Fluent hover / pressed / selected fills. */
@Composable
fun Hover(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(M.CONTROL_RADIUS.dp),
    selectedColor: Color = LocalPalette.current.selected,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val bg = when {
        selected -> selectedColor
        !enabled -> Color.Transparent
        pressed -> p.pressed
        hovered -> p.hover
        else -> Color.Transparent
    }
    var m = modifier.clip(shape).background(bg, shape).hoverable(src, enabled)
    if (onClick != null) m = m.clickable(src, indication = null, enabled = enabled, onClick = onClick)
    Box(m, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconBtn(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    width: Dp = M.NAV_BUTTON_W.dp,
    height: Dp = M.NAV_BUTTON_H.dp,
    iconSize: Dp = 16.dp,
    tint: Color = LocalPalette.current.text,
    selected: Boolean = false,
) {
    val p = LocalPalette.current
    TooltipArea(
        tooltip = {
            Box(Modifier.background(p.flyout, RoundedCornerShape(4.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)).padding(8.dp, 5.dp)) {
                T(contentDescription, size = 12.sp)
            }
        },
        delayMillis = 600,
    ) {
        Hover(Modifier.size(width, height), onClick = onClick, enabled = enabled, selected = selected) {
            Icon(
                icon, contentDescription, Modifier.size(iconSize).align(Alignment.Center),
                tint = if (enabled) tint else p.textTertiary,
            )
        }
    }
}

@Composable
fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, chevron: Boolean = false, selected: Boolean = false) {
    val p = LocalPalette.current
    Hover(Modifier.height(36.dp), onClick = onClick, enabled = enabled, selected = selected) {
        Row(Modifier.padding(horizontal = 10.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, Modifier.size(16.dp), tint = if (enabled) p.text else p.textTertiary)
            if (label.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                T(label, size = 14.sp, color = if (enabled) p.text else p.textTertiary)
            }
            if (chevron) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(14.dp), tint = if (enabled) p.textSecondary else p.textTertiary)
            }
        }
    }
}

@Composable
fun VDivider(height: Dp = 18.dp) {
    Box(Modifier.width(1.dp).height(height).background(LocalPalette.current.divider))
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = LocalPalette.current.accent) {
    val p = LocalPalette.current
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.progressTrack)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(2.dp)).background(color))
    }
}

// ------------------------------------------------------------------ icons for files

fun iconFor(item: FileItem): ImageVector = when {
    item.isDir -> Icons.Filled.Folder
    else -> iconForExt(item.ext)
}

fun iconForExt(ext: String): ImageVector = when (ext) {
    "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico", "heic", "tif", "tiff" -> Icons.Outlined.Image
    "mp4", "mkv", "avi", "mov", "wmv", "webm" -> Icons.Outlined.Movie
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma" -> Icons.Outlined.MusicNote
    "zip", "7z", "rar", "tar", "gz", "xz", "bz2" -> Icons.Outlined.FolderZip
    "pdf" -> Icons.Outlined.PictureAsPdf
    "exe", "msi", "bat", "cmd", "ps1", "appinstaller", "msix", "msixbundle" -> Icons.Outlined.Terminal
    "kt", "kts", "java", "cs", "rs", "js", "ts", "py", "c", "cpp", "h", "json", "xml", "html", "css", "toml", "yaml", "yml", "gradle" -> Icons.Outlined.Code
    "txt", "md", "log", "ini", "cfg", "doc", "docx", "rtf" -> Icons.Outlined.Description
    "lnk", "url" -> Icons.Outlined.OpenInNew
    else -> Icons.Outlined.InsertDriveFile
}

@Composable
fun fileTint(item: FileItem): Color {
    val p = LocalPalette.current
    return when {
        item.isDir -> p.folder
        item.ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "heic") -> Color(0xFF2E9E6B)
        item.ext in setOf("mp4", "mkv", "avi", "mov", "wmv", "webm") -> Color(0xFF8A4FD8)
        item.ext in setOf("mp3", "wav", "flac", "aac", "ogg", "m4a") -> Color(0xFFE0629B)
        item.ext in setOf("zip", "7z", "rar", "tar", "gz") -> Color(0xFFD9A030)
        item.ext == "pdf" -> Color(0xFFD13438)
        else -> p.textSecondary
    }
}

@Composable
fun FileIcon(item: FileItem, size: Dp) {
    Icon(iconFor(item), null, Modifier.size(size), tint = fileTint(item))
}

// ------------------------------------------------------------------ context menus

class MenuEntry(
    val label: String,
    val icon: ImageVector? = null,
    val hotkey: String = "",
    val enabled: Boolean = true,
    val separatorBefore: Boolean = false,
    val action: () -> Unit = {},
)

class MenuRequest(val at: Offset, val entries: List<MenuEntry>)

val LocalMenu = staticCompositionLocalOf<MutableState<MenuRequest?>> { mutableStateOf(null) }

/** Right-click opens a menu at the pointer (root coordinates). */
fun Modifier.contextMenu(onOpen: () -> Unit = {}, entries: () -> List<MenuEntry>): Modifier = composed {
    val menu = LocalMenu.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentEntries by rememberUpdatedState(entries)
    val currentOpen by rememberUpdatedState(onOpen)
    this.onGloballyPositioned { coords = it }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Main)
                if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                    val c = coords ?: continue
                    if (e.changes.any { it.isConsumed }) continue
                    e.changes.forEach { it.consume() }
                    currentOpen()
                    menu.value = MenuRequest(c.localToRoot(e.changes.first().position), currentEntries())
                }
            }
        }
    }
}

/** Primary press selects at once (no double-click delay); a second press within 450 ms opens. */
fun Modifier.itemPointer(onPress: (ctrl: Boolean, shift: Boolean) -> Unit, onDouble: () -> Unit): Modifier = composed {
    val press by rememberUpdatedState(onPress)
    val dbl by rememberUpdatedState(onDouble)
    this.pointerInput(Unit) {
        var last = 0L
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                if (e.type == PointerEventType.Press && e.buttons.isPrimaryPressed) {
                    val now = e.changes.first().uptimeMillis
                    val mods = e.keyboardModifiers
                    if (now - last < 450 && !mods.isCtrlPressed && !mods.isShiftPressed) {
                        last = 0; dbl()
                    } else {
                        last = now; press(mods.isCtrlPressed, mods.isShiftPressed)
                    }
                }
            }
        }
    }
}

@Composable
fun EditBox(
    initial: String,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    selectStem: Boolean = true,
    fontSize: TextUnit = 14.sp,
) {
    val p = LocalPalette.current
    val stemEnd = if (selectStem && initial.contains('.')) initial.lastIndexOf('.') else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stemEnd))) }
    val fr = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { fr.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = TextStyle(color = p.text, fontSize = fontSize),
        cursorBrush = SolidColor(p.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(value.text) }),
        modifier = modifier
            .focusRequester(fr)
            .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) onCommit(value.text) }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onCancel(); true }
                else if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { onCommit(value.text); true }
                else false
            }
            .background(if (p.dark) Color(0xFF1F1F1F) else Color.White, RoundedCornerShape(4.dp))
            .border(1.dp, p.accent, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
