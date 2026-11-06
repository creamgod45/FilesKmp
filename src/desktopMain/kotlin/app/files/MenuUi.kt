package app.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where a menu of [size] opened at the pointer [at] goes in a window of [window], keeping [margin] from the edges.
 * Each axis, in order: after the pointer (down / right) when it fits there; else before it (up / left, the
 * menu's far edge at the pointer) when it fits there; else as close to the pointer as the window allows. The
 * menu is never taller than the window minus the margins (it scrolls instead), so the last case always fits.
 */
internal fun menuPlacement(at: IntOffset, size: IntSize, window: IntSize, margin: Int): IntOffset {
    fun axis(p: Int, len: Int, room: Int): Int = when {
        p + len <= room - margin -> p
        p - len >= margin -> p - len
        else -> (room - margin - len).coerceAtMost(p).coerceAtLeast(margin.coerceAtMost((room - len).coerceAtLeast(0)))
    }
    return IntOffset(axis(at.x, size.width, window.width), axis(at.y, size.height, window.height))
}

/** The space a menu may use in a window of [windowHeight] px: everything but the margins. */
internal fun menuMaxHeight(windowHeight: Int, margin: Int): Int = (windowHeight - 2 * margin).coerceAtLeast(margin * 4)

private class MenuPosition(val at: IntOffset, val margin: Int, val onWindow: (IntSize) -> Unit) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        onWindow(windowSize)
        return menuPlacement(at, popupContentSize, windowSize, margin)
    }
}

/**
 * The right-click menu. A submenu ("Show more options", "Send to") opens in place of the list, with
 * a back row, the way Windows 11 swaps in its classic menu. Entries with a [MenuEntry.loader] show
 * "Loading…" until Windows answers.
 */
@Composable
fun ContextMenuPopup(req: MenuRequest, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val stack = remember(req) { mutableStateListOf<MenuEntry>() }
    // Loaded once per menu, here rather than in the list: opening a submenu and coming back keeps
    // what Windows answered (the items still belong to that one menu, so they can still run).
    val loaded = remember(req) { mutableStateMapOf<MenuEntry, List<MenuEntry>>() }
    LaunchedEffect(req) {
        req.entries.filter { it.loader != null }.forEach { e ->
            launch {
                loaded[e] = runCatching { withContext(Dispatchers.IO) { e.loader!!() } }.getOrElse { ex ->
                    ErrorLog.record(ex, "menu: loading entries")
                    listOf(MenuEntry(tr("Windows menu unavailable", "無法讀取 Windows 選單"), enabled = false, separatorBefore = true))
                }
            }
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    // The window's height, as the popup reports it: the menu may use all of it (minus margins) and scrolls beyond.
    var windowSize by remember { mutableStateOf<IntSize?>(null) }
    val maxHeight = windowSize?.let { with(density) { menuMaxHeight(it.height, margin).toDp() } } ?: 620.dp
    Popup(
        popupPositionProvider = remember(req) { MenuPosition(IntOffset(req.at.x.toInt(), req.at.y.toInt()), margin) { if (windowSize != it) windowSize = it } },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // A submenu starts at its top.
        val scroll = remember(stack.size) { androidx.compose.foundation.ScrollState(0) }
        Box(
            Modifier.width(IntrinsicSize.Max).widthIn(min = 240.dp, max = 420.dp).heightIn(max = maxHeight).shadow8()
                .background(p.flyout, RoundedCornerShape(8.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(8.dp))
        ) {
            Column(Modifier.verticalScroll(scroll).padding(4.dp)) {
                val sub = stack.lastOrNull()
                if (sub != null) {
                    FlyoutItem(sub.label, Icons.AutoMirrored.Outlined.ArrowBack, "") { stack.removeAt(stack.size - 1) }
                    FlyoutSeparator()
                    MenuList(sub.children, loaded, onDismiss) { stack.add(it) }
                } else MenuList(req.entries, loaded, onDismiss) { stack.add(it) }
            }
            // Only when it doesn't all fit: a scrollbar shows there is more (the wheel scrolls too).
            if (scroll.maxValue > 0) androidx.compose.foundation.VerticalScrollbar(androidx.compose.foundation.rememberScrollbarAdapter(scroll),
                Modifier.align(Alignment.CenterEnd).matchParentSize().wrapContentWidth(Alignment.End).padding(vertical = 6.dp, horizontal = 1.dp))
        }
    }
}

@Composable
private fun MenuList(entries: List<MenuEntry>, loaded: Map<MenuEntry, List<MenuEntry>>, onDismiss: () -> Unit, openSub: (MenuEntry) -> Unit) {
    entries.forEach { e ->
        if (e.loader != null) {
            val list = loaded[e]
            if (list != null) MenuList(list, loaded, onDismiss, openSub)
            else {
                if (e.separatorBefore) FlyoutSeparator()
                FlyoutItem(tr("Loading Windows menu…", "正在讀取 Windows 選單…"), Icons.Outlined.MoreHoriz, "", enabled = false) {}
            }
            return@forEach
        }
        if (e.separatorBefore) FlyoutSeparator()
        MenuRow(e) { if (e.children.isNotEmpty()) openSub(e) else { onDismiss(); e.action() } }
    }
}

@Composable
private fun MenuRow(e: MenuEntry, onClick: () -> Unit) {
    val p = LocalPalette.current
    Hover(Modifier.fillMaxWidth().height(34.dp), onClick = onClick, enabled = e.enabled) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp)) {
                when {
                    e.image != null -> Image(e.image, null, Modifier.size(16.dp))
                    e.icon != null -> Icon(e.icon, null, Modifier.size(16.dp),
                        tint = if (e.icon == Icons.Filled.Folder) p.folder else if (e.enabled) p.text else p.textTertiary)
                }
            }
            Spacer(Modifier.width(12.dp))
            T(e.label, Modifier.weight(1f, fill = false), color = if (e.enabled) p.text else p.textTertiary)
            Spacer(Modifier.weight(1f).widthIn(min = 24.dp))
            if (e.hotkey.isNotEmpty()) T(e.hotkey, size = 12.sp, color = p.textSecondary)
            if (e.children.isNotEmpty()) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp), tint = p.textSecondary)
        }
    }
}

// ------------------------------------------------------------------ Windows' menu as menu entries

/** Windows' own menu for [paths] (or a folder's empty space), as entries that load when the menu opens. */
fun systemMenuEntry(s: AppState, paths: List<String>, background: String? = null): MenuEntry? {
    if (s.shellMenuMode == ShellMenuMode.Off) return null
    val all = paths + listOfNotNull(background)
    if (all.isEmpty() || all.any { it.startsWith("::") || NetworkFs.isServer(it) || !(File(it).exists()) }) return null
    return MenuEntry("", separatorBefore = true, loader = {
        ErrorLog.crumb("shell menu: ${all.size} item(s)")
        ShellMenu.query(paths, background, s.shellMenuMode).toEntries(s)
    })
}

private fun List<ShellItem>.toEntries(): List<MenuEntry> {
    val out = ArrayList<MenuEntry>()
    var sep = false
    forEach { i ->
        if (i.separator) { sep = out.isNotEmpty(); return@forEach }
        out += MenuEntry(i.label, enabled = i.enabled, separatorBefore = sep, image = if (ShellMenuPrefs.showIcons) i.icon else null, children = i.children.toEntries(),
            action = { ErrorLog.crumb("shell menu: run “${i.label}” (${i.verb ?: "command"})"); i.run?.invoke() })
        sep = false
    }
    return out
}

private fun ShellMenuResult.toEntries(s: AppState): List<MenuEntry> {
    val moreLabel = tr("Show more options", "顯示更多選項")
    val customize = MenuEntry(tr("Customize this menu…", "自訂這個選單…"), Icons.Outlined.Tune, separatorBefore = true) {
        s.settingsSectionRequest = "Folders"; s.settingsOpen = true
    }
    if (source == ShellMenuResult.Source.Unavailable) return listOf(MenuEntry(tr("Windows menu unavailable", "無法讀取 Windows 選單") + (note?.let { " — $it" } ?: ""),
        enabled = false, separatorBefore = true))
    // Windows 11: [top] is the main menu, [more] the classic one. Classic (fallback): everything is "more".
    val (winTop, winMore) = if (source == ShellMenuResult.Source.Modern) top to more else emptyList<ShellItem>() to top
    ShellMenuPrefs.noteSeen(winTop, winMore)
    val (main, rest) = ShellMenuPrefs.arrange(winTop, winMore)
    val label = if (source == ShellMenuResult.Source.Classic) moreLabel + tr(" (classic menu)", "（舊版選單）") else moreLabel
    val moreEntry = if (rest.isNotEmpty()) MenuEntry(label, Icons.Outlined.MoreHoriz,
        children = listOfNotNull(note?.let { MenuEntry(it, enabled = false) }) + rest.toEntries() + customize) else null
    return (main.toEntries() + listOfNotNull(moreEntry)).mapIndexed { i, e -> if (i == 0) e.withSeparator() else e }
        .ifEmpty { listOf(customize) }
}

private fun MenuEntry.withSeparator() = MenuEntry(label, icon, hotkey, enabled, true, children, image, loader, action)
