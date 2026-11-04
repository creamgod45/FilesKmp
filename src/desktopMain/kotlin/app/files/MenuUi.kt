package app.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.withContext

/** Places the menu at the pointer, moved left / up when it would leave the window. */
private class MenuPosition(val at: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (at.x + popupContentSize.width > windowSize.width) (at.x - popupContentSize.width).coerceAtLeast(0) else at.x
        val y = if (at.y + popupContentSize.height > windowSize.height) (windowSize.height - popupContentSize.height).coerceAtLeast(0) else at.y
        return IntOffset(x, y)
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
    Popup(
        popupPositionProvider = remember(req) { MenuPosition(IntOffset(req.at.x.toInt(), req.at.y.toInt())) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(IntrinsicSize.Max).widthIn(min = 240.dp, max = 420.dp).shadow8()
                .background(p.flyout, RoundedCornerShape(8.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(8.dp))
                .heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(4.dp)
        ) {
            val sub = stack.lastOrNull()
            if (sub != null) {
                FlyoutItem(sub.label, Icons.AutoMirrored.Outlined.ArrowBack, "") { stack.removeAt(stack.size - 1) }
                FlyoutSeparator()
                MenuList(sub.children, onDismiss) { stack.add(it) }
            } else MenuList(req.entries, onDismiss) { stack.add(it) }
        }
    }
}

@Composable
private fun MenuList(entries: List<MenuEntry>, onDismiss: () -> Unit, openSub: (MenuEntry) -> Unit) {
    entries.forEach { e ->
        if (e.loader != null) { LoadedEntries(e, onDismiss, openSub); return@forEach }
        if (e.separatorBefore) FlyoutSeparator()
        MenuRow(e) { if (e.children.isNotEmpty()) openSub(e) else { onDismiss(); e.action() } }
    }
}

@Composable
private fun LoadedEntries(e: MenuEntry, onDismiss: () -> Unit, openSub: (MenuEntry) -> Unit) {
    val loaded by produceState<List<MenuEntry>?>(null, e) {
        value = runCatching { withContext(Dispatchers.IO) { e.loader!!() } }.getOrElse { ex ->
            ErrorLog.record(ex, "menu: loading entries")
            listOf(MenuEntry(tr("Windows menu unavailable", "無法讀取 Windows 選單"), enabled = false, separatorBefore = true))
        }
    }
    val list = loaded
    if (list == null) {
        if (e.separatorBefore) FlyoutSeparator()
        FlyoutItem(tr("Loading Windows menu…", "正在讀取 Windows 選單…"), Icons.Outlined.MoreHoriz, "", enabled = false) {}
    } else MenuList(list, onDismiss, openSub)
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
