package app.files

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Cursor
import java.io.File

class WindowControls(
    val state: WindowState?,
    val minimize: () -> Unit,
    val close: () -> Unit,
    val drag: @Composable (Modifier, @Composable () -> Unit) -> Unit,
)

val LocalWindowControls = staticCompositionLocalOf<WindowControls?> { null }

// ====================================================================== tab strip

@Composable
fun TabStrip(s: AppState) {
    val p = LocalPalette.current
    val wc = LocalWindowControls.current
    Row(
        Modifier.fillMaxWidth().padding(top = M.TAB_STRIP_TOP_MARGIN.dp).height(M.TAB_HEIGHT.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(M.TAB_BUTTON.dp).align(Alignment.CenterVertically)) {
            IconBtn(Icons.Outlined.Tab, "Tab actions", { s.newTab() }, width = M.TAB_BUTTON.dp, height = M.TAB_BUTTON.dp, iconSize = 16.dp)
        }
        Spacer(Modifier.width(2.dp))
        s.tabs.forEachIndexed { i, t ->
            val sel = i == s.tabIndex
            Box(
                Modifier.widthIn(min = 120.dp, max = 240.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(if (sel) p.tabSelected else Color.Transparent)
                    .contextMenu {
                        listOf(
                            MenuEntry("New tab", Icons.Outlined.Add, "Ctrl+T") { s.newTab() },
                            MenuEntry("Duplicate tab", Icons.Outlined.ContentCopy, "Ctrl+Shift+K") { s.newTab(t.location) },
                            MenuEntry("Close tab", Icons.Outlined.Close, "Ctrl+W", separatorBefore = true) { s.closeTab(i) },
                        )
                    }
            ) {
                Hover(Modifier.fillMaxSize(), onClick = { s.tabIndex = i }, shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp), enabled = !sel) {
                    Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (t.isHome) Icons.Outlined.Home else Icons.Filled.Folder, null, Modifier.size(16.dp), tint = if (t.isHome) p.text else p.folder)
                        Spacer(Modifier.width(10.dp))
                        T(t.title, Modifier.weight(1f), size = 12.sp)
                        IconBtn(Icons.Outlined.Close, "Close tab", { s.closeTab(i) }, width = 28.dp, height = 24.dp, iconSize = 12.dp)
                    }
                }
            }
            if (i != s.tabIndex && i + 1 != s.tabIndex && i < s.tabs.size - 1) {
                Box(Modifier.align(Alignment.CenterVertically).width(1.dp).height(16.dp).background(p.divider))
            }
        }
        Box(Modifier.size(M.TAB_BUTTON.dp).align(Alignment.CenterVertically)) {
            IconBtn(Icons.Outlined.Add, "New tab (Ctrl+T)", { s.newTab() }, width = M.TAB_BUTTON.dp, height = M.TAB_BUTTON.dp, iconSize = 12.dp)
        }
        // Drag area + caption buttons
        val dragContent: @Composable () -> Unit = { Box(Modifier.fillMaxSize()) }
        if (wc != null) wc.drag(Modifier.weight(1f).fillMaxHeight(), dragContent) else Box(Modifier.weight(1f))
        CaptionButtons()
    }
}

@Composable
fun CaptionButtons() {
    val wc = LocalWindowControls.current
    val p = LocalPalette.current
    Row(Modifier.fillMaxHeight().offset(y = (-M.TAB_STRIP_TOP_MARGIN / 2).dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.Outlined.Remove, "Minimize", { wc?.minimize?.invoke() }, width = 46.dp, height = 32.dp, iconSize = 14.dp)
        val maximized = wc?.state?.placement == WindowPlacement.Maximized
        IconBtn(
            if (maximized) Icons.Outlined.FilterNone else Icons.Outlined.CropSquare, if (maximized) "Restore" else "Maximize",
            { wc?.state?.let { it.placement = if (it.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized } },
            width = 46.dp, height = 32.dp, iconSize = 12.dp,
        )
        IconBtn(Icons.Outlined.Close, "Close", { wc?.close?.invoke() }, width = 46.dp, height = 32.dp, iconSize = 14.dp, tint = p.text)
    }
}

// ====================================================================== address bar

@Composable
fun AddressBar(s: AppState) {
    val p = LocalPalette.current
    val t = s.tab
    Row(
        Modifier.fillMaxWidth().height(M.ADDRESS_BAR_HEIGHT.dp).background(p.layer).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconBtn(Icons.Outlined.ArrowBack, "Back (Alt+Left)", { s.back() }, enabled = t.canBack, iconSize = M.NAV_ICON.dp)
        IconBtn(Icons.Outlined.ArrowForward, "Forward (Alt+Right)", { s.forward() }, enabled = t.canForward, iconSize = M.NAV_ICON.dp)
        IconBtn(Icons.Outlined.ArrowUpward, "Up (Alt+Up)", { s.up() }, enabled = !t.isHome, iconSize = M.NAV_ICON.dp)
        IconBtn(Icons.Outlined.Refresh, "Refresh (F5)", { s.refresh() }, iconSize = M.NAV_ICON.dp)
        Omnibar(s, Modifier.weight(1f))
        StatusCenterButton(s)
    }
}

@Composable
fun Omnibar(s: AppState, modifier: Modifier) {
    val p = LocalPalette.current
    val t = s.tab
    val shape = RoundedCornerShape(M.OMNIBAR_RADIUS.dp)
    var searching by remember { mutableStateOf(false) }
    Row(
        modifier.height(M.OMNIBAR_HEIGHT.dp).clip(shape).background(p.control, shape).border(1.dp, p.controlStroke, shape).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.editingPath) {
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                PathEditor(s)
            }
        } else if (searching) {
            val fr = remember { FocusRequester() }
            LaunchedEffect(Unit) { fr.requestFocus() }
            Icon(Icons.Outlined.Search, null, Modifier.padding(start = 12.dp).size(16.dp), tint = p.textSecondary)
            Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (s.searchText.isEmpty()) T("Search for files and folders...", color = p.textTertiary)
                BasicTextField(
                    s.searchText, { s.searchText = it }, singleLine = true,
                    textStyle = TextStyle(color = p.text, fontSize = 14.sp), cursorBrush = SolidColor(p.text),
                    modifier = Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { s.textFocus = it.isFocused },
                )
            }
            IconBtn(Icons.Outlined.Close, "Close search", { searching = false; s.searchText = "" }, width = 34.dp, height = 34.dp, iconSize = 14.dp)
        } else {
            // Breadcrumb (Path mode, the omnibar's default)
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(17.dp))
                    .pointerInput(t.location) { detectTapGestures2 { s.editingPath = true } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Hover(Modifier.height(34.dp).width(40.dp), onClick = { s.navigate(HOME) }, shape = RoundedCornerShape(17.dp)) {
                    Icon(Icons.Outlined.Home, "Home", Modifier.size(16.dp).align(Alignment.Center), tint = p.text)
                }
                if (!t.isHome) {
                    val crumbs = Fs.breadcrumbs(t.location)
                    val scroll = rememberScrollState()
                    LaunchedEffect(t.location) { scroll.scrollTo(scroll.maxValue) }
                    Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                        crumbs.forEach { (name, path) ->
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = p.textSecondary)
                            Hover(Modifier.height(30.dp), onClick = { s.navigate(path) }) {
                                T(name, Modifier.align(Alignment.Center).padding(horizontal = 8.dp))
                            }
                        }
                    }
                } else {
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = p.textSecondary)
                    Hover(Modifier.height(30.dp), onClick = {}) { T("Home", Modifier.align(Alignment.Center).padding(horizontal = 8.dp)) }
                }
                Spacer(Modifier.weight(1f))
            }
            if (!t.isHome) {
                IconBtn(Icons.Outlined.Search, "Search (Ctrl+F)", { searching = true }, width = 34.dp, height = 34.dp, iconSize = 16.dp)
            }
        }
    }
    LaunchedEffect(s.searchRequest) { if (s.searchRequest > 0 && !t.isHome) searching = true }
}

/** Tap on the empty part of the breadcrumb switches to typing a path. */
suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGestures2(onTap: () -> Unit) {
    detectTapGestures(onTap = { onTap() })
}

@Composable
fun PathEditor(s: AppState) {
    val start = if (s.tab.isHome) "Home" else s.tab.location
    EditBox(
        initial = start,
        selectStem = false,
        onCommit = { text ->
            s.editingPath = false
            val v = text.trim().trim('"')
            when {
                v.equals("Home", true) || v.isEmpty() -> if (!s.tab.isHome) s.navigate(HOME)
                v == start -> {}
                File(v).exists() -> s.navigate(File(v).absolutePath)
                else -> s.toast = "Can't find \"$v\". Check the spelling and try again."
            }
        },
        onCancel = { s.editingPath = false },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun StatusCenterButton(s: AppState) {
    val p = LocalPalette.current
    val running = CopyEngine.jobs.count { it.state == JobState.Running || it.state == JobState.Queued }
    Box {
        IconBtn(Icons.Outlined.SwapVert, "File operations", { s.statusCenterOpen = !s.statusCenterOpen }, selected = s.statusCenterOpen)
        if (running > 0) {
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp).background(p.accent, CircleShape)) {
                T("$running", Modifier.align(Alignment.Center), size = 9.sp, color = Color.White, weight = FontWeight.SemiBold)
            }
        }
    }
}

// ====================================================================== command toolbar

@Composable
fun CommandToolbar(s: AppState) {
    val p = LocalPalette.current
    val t = s.tab
    val shape = RoundedCornerShape(M.CARD_RADIUS.dp)
    val hasSel = t.selection.isNotEmpty() && !t.isHome
    var newOpen by remember { mutableStateOf(false) }
    var selOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var layoutOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(M.TOOLBAR_HEIGHT.dp).clip(shape).background(p.toolbar, shape).border(1.dp, p.cardStroke, shape).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ToolButton(Icons.Outlined.AddCircleOutline, "New", { newOpen = true }, enabled = !t.isHome, chevron = true)
            Flyout(newOpen, { newOpen = false }) {
                FlyoutItem("Folder", Icons.Filled.Folder, "Ctrl+Shift+N", iconTint = p.folder) { newOpen = false; s.newFolder() }
                FlyoutItem("Text document", Icons.Outlined.Description, "") { newOpen = false; s.newFile() }
            }
        }
        Spacer(Modifier.width(4.dp)); VDivider(); Spacer(Modifier.width(4.dp))
        IconBtn(Icons.Outlined.ContentCut, "Cut (Ctrl+X)", { s.copy(cut = true) }, enabled = hasSel, width = 40.dp, height = 36.dp)
        IconBtn(Icons.Outlined.ContentCopy, "Copy (Ctrl+C)", { s.copy() }, enabled = hasSel, width = 40.dp, height = 36.dp)
        IconBtn(Icons.Outlined.ContentPaste, "Paste (Ctrl+V)", { s.paste() }, enabled = s.clipboard != null && !t.isHome, width = 40.dp, height = 36.dp)
        IconBtn(Icons.Outlined.DriveFileRenameOutline, "Rename (F2)", { s.startRename() }, enabled = t.selection.size == 1 && !t.isHome, width = 40.dp, height = 36.dp)
        IconBtn(Icons.Outlined.Link, "Copy path (Ctrl+Shift+C)", { s.copyPath() }, enabled = !t.isHome, width = 40.dp, height = 36.dp)
        IconBtn(Icons.Outlined.Delete, "Delete (Delete)", { s.requestDelete() }, enabled = hasSel, width = 40.dp, height = 36.dp)
        IconBtn(Icons.Outlined.Info, "Properties (Alt+Enter)", { s.infoPaneOpen = true }, enabled = hasSel, width = 40.dp, height = 36.dp)
        Spacer(Modifier.weight(1f))
        if (!t.isHome) {
            Box {
                IconBtn(Icons.Outlined.Checklist, "Selection options", { selOpen = true }, width = 46.dp, height = 36.dp)
                Flyout(selOpen, { selOpen = false }) {
                    FlyoutItem("Select all", Icons.Outlined.SelectAll, "Ctrl+A") { selOpen = false; s.selectAll() }
                    FlyoutItem("Invert selection", Icons.Outlined.FlipToBack, "") { selOpen = false; s.invertSelection() }
                    FlyoutItem("Clear selection", Icons.Outlined.Deselect, "") { selOpen = false; s.clearSelection() }
                }
            }
            Box {
                ToolButton(Icons.Outlined.Sort, "Sort", { sortOpen = true })
                Flyout(sortOpen, { sortOpen = false }) {
                    SortBy.entries.forEach { sb ->
                        FlyoutItem(sb.label, if (s.sortBy == sb) Icons.Outlined.Check else null, "") { sortOpen = false; s.sortBy = sb }
                    }
                    FlyoutSeparator()
                    FlyoutItem("Ascending", if (s.sortAscending) Icons.Outlined.Check else null, "") { sortOpen = false; s.sortAscending = true }
                    FlyoutItem("Descending", if (!s.sortAscending) Icons.Outlined.Check else null, "") { sortOpen = false; s.sortAscending = false }
                }
            }
            Box {
                ToolButton(layoutIcon(s.layout), "Layout", { layoutOpen = true })
                Flyout(layoutOpen, { layoutOpen = false }) {
                    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LayoutMode.entries.forEach { lm ->
                            Hover(Modifier.size(76.dp, 72.dp), onClick = { s.layout = lm; layoutOpen = false }, selected = s.layout == lm) {
                                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(layoutIcon(lm), lm.label, Modifier.size(28.dp), tint = if (s.layout == lm) p.accent else p.text)
                                    Spacer(Modifier.height(6.dp))
                                    T(lm.label, size = 12.sp)
                                }
                            }
                        }
                    }
                    FlyoutSeparator()
                    FlyoutItem("Show hidden items", if (s.showHidden) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "Ctrl+H") { s.showHidden = !s.showHidden }
                    FlyoutItem("Show file extensions", if (s.showExtensions) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "") { s.showExtensions = !s.showExtensions }
                }
            }
        }
        IconBtn(Icons.Outlined.VerticalSplit, "Details pane (Ctrl+Alt+I)", { s.infoPaneOpen = !s.infoPaneOpen }, selected = s.infoPaneOpen, width = 40.dp, height = 36.dp)
    }
}

fun layoutIcon(l: LayoutMode) = when (l) {
    LayoutMode.Details -> Icons.Outlined.ViewHeadline
    LayoutMode.List -> Icons.Outlined.ViewList
    LayoutMode.Cards -> Icons.Outlined.ViewAgenda
    LayoutMode.Grid -> Icons.Outlined.GridView
}

@Composable
fun Flyout(open: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!open) return
    val p = LocalPalette.current
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopStart, offset = offsetDp(0, 40), onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(IntrinsicSize.Max).widthIn(min = 200.dp).shadow8()
                .background(p.flyout, RoundedCornerShape(8.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(8.dp)).padding(4.dp),
            content = content,
        )
    }
}

@Composable
fun offsetDp(x: Int, y: Int): androidx.compose.ui.unit.IntOffset {
    val d = LocalDensity.current
    return with(d) { androidx.compose.ui.unit.IntOffset(x.dp.roundToPx(), y.dp.roundToPx()) }
}

fun Modifier.shadow8(): Modifier = this.shadow(8.dp, RoundedCornerShape(8.dp), clip = false)

@Composable
fun FlyoutItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, hotkey: String, enabled: Boolean = true, iconTint: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Hover(Modifier.fillMaxWidth().height(34.dp), onClick = onClick, enabled = enabled) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp)) { if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = iconTint ?: if (enabled) p.text else p.textTertiary) }
            Spacer(Modifier.width(12.dp))
            T(label, Modifier.weight(1f, fill = false), color = if (enabled) p.text else p.textTertiary)
            Spacer(Modifier.weight(1f).widthIn(min = 24.dp))
            if (hotkey.isNotEmpty()) T(hotkey, size = 12.sp, color = p.textSecondary)
        }
    }
}

@Composable
fun FlyoutSeparator() {
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(LocalPalette.current.divider))
}

// ====================================================================== sidebar

@Composable
fun Sidebar(s: AppState, modifier: Modifier) {
    val p = LocalPalette.current
    var pinnedOpen by remember { mutableStateOf(true) }     // expanded by default
    var drivesOpen by remember { mutableStateOf(true) }     // expanded by default
    var networkOpen by remember { mutableStateOf(false) }   // collapsed by default
    val loc = s.tab.location
    Column(modifier.fillMaxHeight().background(p.layer)) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            item { SidebarRow("Home", Icons.Outlined.Home, null, loc == HOME, 0) { s.navigate(HOME) } }
            item { SidebarSection("Pinned", pinnedOpen) { pinnedOpen = !pinnedOpen } }
            if (pinnedOpen) items(s.pinned) { f ->
                SidebarRow(f.name, knownFolderIcon(f), p.folder.takeIf { knownFolderIcon(f) == Icons.Filled.Folder }, loc == f.path, 1,
                    menu = { listOf(MenuEntry("Open in new tab", Icons.Outlined.Tab) { s.newTab(f.path) }, MenuEntry("Copy path", Icons.Outlined.Link) { copyText(f.path) }) }) { s.navigate(f.path) }
            }
            item { SidebarSection("Drives", drivesOpen) { drivesOpen = !drivesOpen } }
            if (drivesOpen) items(s.drives) { d ->
                SidebarRow(d.title, Icons.Outlined.Storage, null, loc.equals(d.root, true), 1,
                    menu = { listOf(MenuEntry("Open in new tab", Icons.Outlined.Tab) { s.newTab(d.root) }) }) { s.navigate(d.root) }
            }
            item { SidebarSection("Network", networkOpen) { networkOpen = !networkOpen } }
            if (networkOpen) item {
                val nets = s.drives.filter { it.root.startsWith("\\\\") }
                if (nets.isEmpty()) T("No network locations", Modifier.padding(start = 44.dp, top = 6.dp, bottom = 6.dp), size = 12.sp, color = p.textSecondary)
            }
        }
    }
}

fun knownFolderIcon(f: PinnedFolder) = when {
    f.path.endsWith("\\Desktop", true) || f.name in setOf("Desktop", "桌面") -> Icons.Outlined.DesktopWindows
    f.path.endsWith("\\Downloads", true) || f.name in setOf("Downloads", "下載") -> Icons.Outlined.Download
    f.name in setOf("Documents", "文件") -> Icons.Outlined.Description
    f.name in setOf("Pictures", "圖片") -> Icons.Outlined.Image
    f.name in setOf("Music", "音樂") -> Icons.Outlined.MusicNote
    f.name in setOf("Videos", "影片") -> Icons.Outlined.Movie
    else -> Icons.Filled.Folder
}

fun copyText(text: String) {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
}

@Composable
fun SidebarSection(title: String, open: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Hover(Modifier.fillMaxWidth().height(M.SIDEBAR_ROW.dp).padding(vertical = 2.dp), onClick = onToggle) {
        Row(Modifier.fillMaxSize().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = p.textSecondary)
            Spacer(Modifier.width(8.dp))
            T(title, size = 14.sp, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SidebarRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color?,
    selected: Boolean,
    indent: Int,
    menu: (() -> List<MenuEntry>)? = null,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val m = Modifier.fillMaxWidth().height(M.SIDEBAR_ROW.dp).padding(vertical = 2.dp)
    Box(if (menu != null) m.contextMenu(entries = menu) else m) {
        Hover(Modifier.fillMaxSize().then(if (selected) Modifier.border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)) else Modifier), onClick = onClick, selected = selected, selectedColor = p.control.copy(alpha = if (p.dark) 0.1f else 1f)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(M.SIDEBAR_INDICATOR_W.dp).height(M.SIDEBAR_INDICATOR_H.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) p.accent else Color.Transparent))
                Spacer(Modifier.width((10 + indent * 20).dp))
                Icon(icon, null, Modifier.size(M.SIDEBAR_ICON.dp), tint = tint ?: p.text)
                Spacer(Modifier.width(12.dp))
                T(label, Modifier.weight(1f), size = 14.sp)
            }
        }
    }
}

@Composable
fun Splitter(onDrag: (Float) -> Unit) {
    Box(
        Modifier.width(6.dp).fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); onDrag(drag.x) } }
    )
}

// ====================================================================== status bar

@Composable
fun StatusBar(s: AppState, items: List<FileItem>) {
    val p = LocalPalette.current
    val t = s.tab
    val selected = items.filter { it.path in t.selection }
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
        Row(
            Modifier.fillMaxWidth().heightIn(min = M.STATUS_BAR_HEIGHT.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (t.isHome) {
                T("${s.pinned.size + s.drives.size + s.recent.size} items", size = 12.sp, color = p.textSecondary)
            } else {
                T("${items.size} ${if (items.size == 1) "item" else "items"}", size = 12.sp, color = p.textSecondary)
                if (selected.isNotEmpty()) {
                    VDivider()
                    T("${selected.size} ${if (selected.size == 1) "item" else "items"} selected", size = 12.sp, color = p.textSecondary)
                    val bytes = selected.filter { !it.isDir }.sumOf { it.size }
                    if (selected.any { !it.isDir }) {
                        VDivider()
                        T(Fs.sizeText(bytes), size = 12.sp, color = p.textSecondary)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            s.clipboard?.let { T("${it.paths.size} on clipboard (${if (it.cut) "cut" else "copy"})", size = 12.sp, color = p.textTertiary) }
        }
    }
}

// ====================================================================== status center (copy jobs)

@Composable
fun StatusCenter(s: AppState) {
    val p = LocalPalette.current
    Column(
        Modifier.width(380.dp).heightIn(max = 460.dp).shadow8()
            .background(p.flyout, RoundedCornerShape(8.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(8.dp)).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("File operations", Modifier.weight(1f), size = 16.sp, weight = FontWeight.SemiBold)
            Hover(Modifier.height(28.dp), onClick = { CopyEngine.clearFinished() }) {
                T("Clear finished", Modifier.align(Alignment.Center).padding(horizontal = 8.dp), size = 12.sp, color = p.accentText)
            }
        }
        T("One task per disk at a time; tasks on different disks run together.", Modifier.padding(top = 2.dp, bottom = 8.dp), size = 12.sp, color = p.textSecondary, maxLines = 2)
        if (CopyEngine.jobs.isEmpty()) {
            T("No file operations yet", Modifier.padding(vertical = 16.dp), color = p.textSecondary)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CopyEngine.jobs, key = { it.id }) { job -> JobCard(job) }
        }
    }
}

@Composable
fun JobCard(job: CopyJob) {
    val p = LocalPalette.current
    val frac = if (job.uiTotal > 0) job.uiDone.toFloat() / job.uiTotal else if (job.state == JobState.Done) 1f else 0f
    Column(Modifier.fillMaxWidth().background(p.control, RoundedCornerShape(6.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(6.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (job.kind == OpKind.Copy) Icons.Outlined.ContentCopy else Icons.Outlined.DriveFileMove, null, Modifier.size(16.dp), tint = p.text)
            Spacer(Modifier.width(8.dp))
            T(job.title, Modifier.weight(1f), size = 13.sp, weight = FontWeight.SemiBold)
            if (job.state == JobState.Running || job.state == JobState.Queued) {
                IconBtn(Icons.Outlined.Close, "Cancel", { CopyEngine.cancel(job) }, width = 28.dp, height = 24.dp, iconSize = 12.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        ProgressBar(frac, Modifier.fillMaxWidth(), color = when (job.state) { JobState.Failed -> p.danger; JobState.Cancelled -> p.textTertiary; else -> p.accent })
        Spacer(Modifier.height(6.dp))
        val status = when (job.state) {
            JobState.Queued -> "Waiting for the disk to be free"
            JobState.Running -> "${Fs.sizeText(job.uiDone)} of ${Fs.sizeText(job.uiTotal)} · ${Fs.sizeText(job.uiSpeed.toLong())}/s"
            JobState.Done -> "Done · ${Fs.sizeText(job.uiTotal)} at ${Fs.sizeText(job.uiSpeed.toLong())}/s"
            JobState.Failed -> "Failed: ${job.error}"
            JobState.Cancelled -> "Cancelled"
        }
        T(status, size = 12.sp, color = p.textSecondary)
        if (job.state == JobState.Running && job.uiFile.isNotEmpty()) T(job.uiFile, size = 12.sp, color = p.textTertiary)
    }
}

// ====================================================================== dialogs

@Composable
fun DeleteDialog(s: AppState, paths: List<String>) {
    val p = LocalPalette.current
    var permanent by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(440.dp).shadow8().background(p.flyout, RoundedCornerShape(8.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(8.dp))
        ) {
            Column(Modifier.padding(24.dp)) {
                T("Delete item${if (paths.size == 1) "" else "s"}", size = 20.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                T(
                    if (paths.size == 1) "Are you sure you want to delete \"${Fs.displayName(paths[0])}\"?" else "Are you sure you want to delete these ${paths.size} items?",
                    maxLines = 3,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.clickable { permanent = !permanent }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (permanent) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(18.dp), tint = if (permanent) p.accent else p.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    T("Permanently delete (skip the Recycle Bin)")
                }
            }
            Row(Modifier.fillMaxWidth().background(p.layer).padding(24.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(4.dp)).background(p.accent).clickable { s.doDelete(paths, permanent) }) {
                    T("Delete", Modifier.align(Alignment.Center), color = if (p.dark) Color.Black else Color.White)
                }
                Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(4.dp)).background(p.control).border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)).clickable { s.confirmDelete = null }) {
                    T("Cancel", Modifier.align(Alignment.Center))
                }
            }
        }
    }
}
