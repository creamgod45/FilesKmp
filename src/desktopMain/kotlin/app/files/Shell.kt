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
import androidx.compose.ui.input.key.*
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer

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
        Modifier.fillMaxWidth().padding(top = M.TAB_STRIP_TOP_MARGIN.dp).height(M.TAB_HEIGHT.dp)
            .onGloballyPositioned { s.tabStripRect = it.boundsInRoot() },
        verticalAlignment = Alignment.Bottom,
    ) {
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(M.TAB_BUTTON.dp).align(Alignment.CenterVertically)) {
            IconBtn(Icons.Outlined.Tab, "Tab actions", { s.newTab() }, width = M.TAB_BUTTON.dp, height = M.TAB_BUTTON.dp, iconSize = 16.dp)
        }
        Spacer(Modifier.width(2.dp))
        s.tabs.forEachIndexed { i, t -> key(t) {
            val sel = i == s.tabIndex
            var coords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
            val dragging = s.tabDrag?.tab === t
            Box(
                Modifier.widthIn(min = 120.dp, max = 240.dp).fillMaxHeight()
                    .onGloballyPositioned { coords = it; s.tabRects[t] = it.boundsInRoot() }
                    // Drag sideways to reorder, or down into the file area to split (TabDrag.kt).
                    .tabDragSource(s, t) { coords }
                    .graphicsLayer { alpha = if (dragging) 0.6f else 1f }
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
                        Icon(when { t.isHome -> Icons.Filled.Home; t.left.isUpdates -> Icons.Filled.NewReleases; else -> Icons.Filled.Folder }, null, Modifier.size(16.dp),
                            tint = when { t.isHome -> Color(0xFFF7630C); t.left.isUpdates -> p.accent; else -> p.folder })
                        Spacer(Modifier.width(10.dp))
                        T(t.title, Modifier.weight(1f), size = 12.sp)
                        IconBtn(Icons.Outlined.Close, "Close tab", { s.closeTab(i) }, width = 28.dp, height = 24.dp, iconSize = 12.dp)
                    }
                }
            }
            if (i != s.tabIndex && i + 1 != s.tabIndex && i < s.tabs.size - 1) {
                Box(Modifier.align(Alignment.CenterVertically).width(1.dp).height(16.dp).background(p.divider))
            }
        } }
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
                if (s.searchText.isEmpty()) T(S.searchPlaceholder + "  ·  " + S.searchSubfolders, color = p.textTertiary)
                BasicTextField(
                    s.searchText, { s.searchText = it }, singleLine = true,
                    textStyle = TextStyle(color = p.text, fontSize = 14.sp), cursorBrush = SolidColor(p.text),
                    modifier = Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { s.textFocus = it.isFocused }
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { s.searchDeep(s.searchText); true }
                            else if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { searching = false; s.searchText = ""; true }
                            else false
                        },
                )
            }
            // .* regex and Aa case switches (they apply to the quick filter and to Enter's deep search).
            SearchSwitch(".*", S.searchRegexMode, s.searchRegex) { s.searchRegex = !s.searchRegex }
            SearchSwitch("Aa", S.searchCaseMode, s.searchCase) { s.searchCase = !s.searchCase }
            IconBtn(Icons.Outlined.HelpOutline, S.searchSyntax + "\n" + S.searchSyntaxHelp, {}, width = 30.dp, height = 34.dp, iconSize = 15.dp)
            IconBtn(Icons.Outlined.Close, "Close search", { searching = false; s.searchText = "" }, width = 34.dp, height = 34.dp, iconSize = 14.dp)
        } else {
            // Breadcrumb (Path mode, the omnibar's default)
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(17.dp))
                    .pointerInput(t.location) { detectTapGestures2 { s.editingPath = true } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Hover(Modifier.height(34.dp).width(40.dp), onClick = { s.navigate(HOME) }, shape = RoundedCornerShape(17.dp)) {
                    Icon(Icons.Filled.Home, S.home, Modifier.size(16.dp).align(Alignment.Center), tint = Color(0xFFF7630C))
                }
                if (t.isVirtual && !t.isHome && !t.isServer) {
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = p.textSecondary)
                    Hover(Modifier.height(30.dp), onClick = {}) { T(t.title, Modifier.align(Alignment.Center).padding(horizontal = 8.dp)) }
                } else if (!t.isHome) {
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
                    Hover(Modifier.height(30.dp), onClick = {}) { T(S.home, Modifier.align(Alignment.Center).padding(horizontal = 8.dp)) }
                }
                Spacer(Modifier.weight(1f))
            }
            if (!t.isHome && !t.isTag && !t.isNetwork && !t.isServer && !t.isRecycleBin) {
                IconBtn(Icons.Outlined.Search, S.search + " (Ctrl+F)", { searching = true }, width = 34.dp, height = 34.dp, iconSize = 16.dp)
            }
        }
    }
    LaunchedEffect(s.searchRequest) { if (s.searchRequest > 0 && !t.isHome) searching = true }
}

/** A small toggle with a text glyph (.* / Aa), lit when on. */
@Composable
fun SearchSwitch(glyph: String, tip: String, on: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    TooltipArea(tooltip = {
        Box(Modifier.background(p.flyout, RoundedCornerShape(4.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)).padding(8.dp, 5.dp)) { T(tip, size = 12.sp) }
    }, delayMillis = 500) {
        Hover(Modifier.size(30.dp, 26.dp), onClick = onToggle, selected = on, selectedColor = p.accent.copy(alpha = 0.22f)) {
            T(glyph, Modifier.align(Alignment.Center), size = 12.sp, weight = FontWeight.SemiBold, color = if (on) p.accentText else p.textSecondary,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

/** Tap on the empty part of the breadcrumb switches to typing a path. */
suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGestures2(onTap: () -> Unit) {
    detectTapGestures(onTap = { onTap() })
}

@Composable
fun PathEditor(s: AppState) {
    val start = when {
        s.tab.isNetwork -> S.network
        s.tab.isVirtual && !s.tab.isServer -> S.home
        else -> s.tab.location
    }
    EditBox(
        initial = start,
        selectStem = false,
        onCommit = { text ->
            s.editingPath = false
            val typed = text.trim().trim('"')
            // %APPDATA%, %USERPROFILE%\Desktop, %TEMP% … as in Explorer's address bar; a real path with % in it wins.
            val v = if (File(typed).exists()) typed else Fs.expandEnv(typed)
            when {
                v.equals("Home", true) || v == S.home || v.isEmpty() -> if (!s.tab.isHome) s.navigate(HOME)
                v == start -> {}
                v.equals("Network", true) || v == S.network -> s.navigate(NETWORK)
                // \\server, \\server\share, smb://..., nfs://...: opened here even when unreachable (the pane says why).
                NetworkFs.isUnc(v) || v.startsWith("//") || Regex("^(?i)(smb|cifs|nfs)://").containsMatchIn(v) -> s.navigate(v)
                File(v).exists() -> s.navigate(File(v).absolutePath)
                else -> s.toast = if (S.zh) "找不到「$v」。請檢查拼字後再試一次。" else "Can't find \"$v\". Check the spelling and try again."
            }
        },
        onCancel = { s.editingPath = false },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun StatusCenterButton(s: AppState) {
    val p = LocalPalette.current
    val running = CopyEngine.jobs.count { it.state == JobState.Running || it.state == JobState.Queued } + (if (s.flowRun?.running == true) 1 else 0)
    Box {
        IconBtn(Icons.Outlined.SwapVert, "File operations", { s.statusCenterOpen = !s.statusCenterOpen }, selected = s.statusCenterOpen)
        if (running > 0) {
            // A pill that grows for 2+ digits. The text's line height is its font size, trimmed and centred:
            // the theme's 24 sp body line height pushed a 9 sp digit down and out of the 14 dp circle.
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp).height(14.dp).widthIn(min = 14.dp).background(p.accent, RoundedCornerShape(7.dp)).padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(if (running > 99) "99+" else "$running", color = Color.White, maxLines = 1, softWrap = false,
                    style = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.SemiBold,
                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)))
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
    var selOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var layoutOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(M.TOOLBAR_HEIGHT.dp).cardShadow(s, shape, 0.6f).clip(shape).background(p.toolbar, shape).border(1.dp, p.cardStroke, shape).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left part: the customizable toolbar (自訂工具列).
        if (t.isRecycleBin) {
            ToolButton(Icons.Outlined.RestoreFromTrash, S.recycleRestore, { s.restoreSelected() }, enabled = t.selection.isNotEmpty())
            ToolButton(Icons.Outlined.DeleteForever, S.recycleDeleteForever, { s.requestDelete() }, enabled = t.selection.isNotEmpty())
            ToolButton(Icons.Outlined.DeleteSweep, S.recycleEmpty, { s.confirmEmptyRecycle = true }, enabled = t.raw.isNotEmpty() && !s.emptyingRecycle)
            VDivider()
        }
        CustomToolbar(s, Modifier.weight(1f))
        if (!t.isHome) {
            Box {
                IconBtn(Icons.Outlined.Checklist, "Selection options", { selOpen = true }, width = 46.dp, height = 36.dp)
                Flyout(selOpen, { selOpen = false }) {
                    FlyoutItem(S.selectAll, Icons.Outlined.SelectAll, "Ctrl+A") { selOpen = false; s.selectAll() }
                    FlyoutItem(S.invertSelection, Icons.Outlined.FlipToBack, "") { selOpen = false; s.invertSelection() }
                    FlyoutItem(S.clearSelection, Icons.Outlined.Deselect, "") { selOpen = false; s.clearSelection() }
                }
            }
            Box {
                ToolButton(Icons.Outlined.Sort, S.sort, { sortOpen = true })
                Flyout(sortOpen, { sortOpen = false }) {
                    SortBy.entries.forEach { sb ->
                        FlyoutItem(sb.text, if (s.sortBy == sb) Icons.Outlined.Check else null, "") { sortOpen = false; s.sortBy = sb; s.extraSort = null }
                    }
                    FlyoutSeparator()
                    FlyoutItem(S.ascending, if (s.sortAscending) Icons.Outlined.Check else null, "") { sortOpen = false; s.sortAscending = true }
                    FlyoutItem(S.descending, if (!s.sortAscending) Icons.Outlined.Check else null, "") { sortOpen = false; s.sortAscending = false }
                }
            }
            Box {
                ToolButton(layoutIcon(s.layout), S.layout, { layoutOpen = true })
                Flyout(layoutOpen, { layoutOpen = false }) {
                    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LayoutMode.entries.forEach { lm ->
                            Hover(Modifier.size(76.dp, 72.dp), onClick = { s.layout = lm; layoutOpen = false }, selected = s.layout == lm) {
                                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(layoutIcon(lm), lm.label, Modifier.size(28.dp), tint = if (s.layout == lm) p.accent else p.text)
                                    Spacer(Modifier.height(6.dp))
                                    T(lm.text, size = 12.sp)
                                }
                            }
                        }
                    }
                    FlyoutSeparator()
                    FlyoutItem(S.showHidden, if (s.showHidden) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "Ctrl+H") { s.showHidden = !s.showHidden }
                    FlyoutItem(S.showSystem, if (s.showSystem) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "") { s.showSystem = !s.showSystem }
                    FlyoutItem(S.fileExtensions, if (s.showExtensions) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "") { s.showExtensions = !s.showExtensions }
                    FlyoutItem(S.showThumbnails, if (s.showThumbnails) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "") { s.showThumbnails = !s.showThumbnails }
                }
            }
        }
        Box {
            var panesOpen by remember { mutableStateOf(false) }
            val ct = s.currentTab
            IconBtn(
                if (ct.right != null && ct.arrangement == PaneArrangement.Horizontal) Icons.Outlined.Splitscreen else Icons.Outlined.VerticalSplit,
                S.panes, { panesOpen = true }, selected = ct.right != null, width = 40.dp, height = 36.dp,
            )
            Flyout(panesOpen, { panesOpen = false }) {
                FlyoutItem(S.splitVertical, Icons.Outlined.VerticalSplit, "Ctrl+Shift+S") { panesOpen = false; s.splitPane(PaneArrangement.Vertical) }
                FlyoutItem(S.splitHorizontal, Icons.Outlined.Splitscreen, "") { panesOpen = false; s.splitPane(PaneArrangement.Horizontal) }
                FlyoutSeparator()
                FlyoutItem(S.arrangeVertical, if (ct.right != null && ct.arrangement == PaneArrangement.Vertical) Icons.Outlined.Check else null, "", enabled = ct.right != null) { panesOpen = false; s.arrangePanes(PaneArrangement.Vertical) }
                FlyoutItem(S.arrangeHorizontal, if (ct.right != null && ct.arrangement == PaneArrangement.Horizontal) Icons.Outlined.Check else null, "", enabled = ct.right != null) { panesOpen = false; s.arrangePanes(PaneArrangement.Horizontal) }
                FlyoutSeparator()
                FlyoutItem(S.focusOtherPane, Icons.Outlined.SwapHoriz, "", enabled = ct.right != null) { panesOpen = false; s.focusOtherPane() }
                FlyoutItem(S.closePane, Icons.Outlined.Close, "", enabled = ct.right != null) { panesOpen = false; s.closeActivePane() }
            }
        }
        IconBtn(Icons.Outlined.ViewSidebar, S.details + " (Ctrl+Alt+I)", { s.infoPaneOpen = !s.infoPaneOpen }, selected = s.infoPaneOpen, width = 40.dp, height = 36.dp)
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

/** Menus and dialogs: a feathered shadow (SoftShadow.kt) instead of a hard-edged one. */
fun Modifier.shadow8(): Modifier = this.softShadow(RoundedCornerShape(8.dp), elevation = 14.dp, strength = 1.3f)

/** The file area, toolbar and details pane: the 卡片陰影 setting (stronger in dark mode, where it shows less). */
@Composable
fun Modifier.cardShadow(s: AppState, shape: androidx.compose.ui.graphics.Shape, scale: Float = 1f): Modifier {
    val p = LocalPalette.current
    return this.softShadow(shape, s.cardShadow.elevation * scale, strength = if (p.dark) 1.8f else 1f)
}

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
    // Expanded by default: Pinned, Drives, Cloud Drives. The rebuild also opens Network, WSL and
    // Tags so they show like the person's own setup (original defaults: collapsed).
    var pinnedOpen by remember { mutableStateOf(true) }
    var drivesOpen by remember { mutableStateOf(true) }
    var cloudOpen by remember { mutableStateOf(true) }
    var networkOpen by remember { mutableStateOf(true) }
    var wslOpen by remember { mutableStateOf(true) }
    var tagsOpen by remember { mutableStateOf(true) }
    val loc = s.tab.location
    Column(modifier.fillMaxHeight()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            item {
                SidebarRow(S.home, 0, loc == HOME, onClick = { s.navigate(HOME) }) {
                    Icon(Icons.Filled.Home, null, Modifier.size(M.SIDEBAR_ICON.dp), tint = Color(0xFFF7630C))
                }
            }
            item {
                val full = (s.recycleInfo?.first ?: 0L) > 0
                SidebarRow(S.recycleBin, 0, loc == RECYCLE_BIN,
                    menu = { listOf(
                        MenuEntry(S.open, Icons.Outlined.OpenInNew) { s.navigate(RECYCLE_BIN) },
                        MenuEntry(S.openInNewTab, Icons.Outlined.Tab) { s.newTab(RECYCLE_BIN) },
                        MenuEntry(S.recycleEmpty, Icons.Outlined.DeleteSweep, enabled = full && !s.emptyingRecycle, separatorBefore = true) { s.confirmEmptyRecycle = true },
                    ) },
                    onClick = { s.navigate(RECYCLE_BIN) }) {
                    Icon(if (full) Icons.Filled.Delete else Icons.Outlined.Delete, null, Modifier.size(M.SIDEBAR_ICON.dp), tint = if (full) Color(0xFF6A8CAF) else p.textSecondary)
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
            item { SidebarSection(S.pinned, "pinned", pinnedOpen) { pinnedOpen = !pinnedOpen } }
            if (pinnedOpen) items(s.pinned) { f ->
                SidebarRow(f.name, 1, loc == f.path,
                    menu = { listOf(
                        MenuEntry(S.openInNewTab, Icons.Outlined.Tab) { s.newTab(f.path) },
                        MenuEntry(S.copyPath, Icons.Outlined.Link) { copyText(f.path) },
                        MenuEntry(S.unpinFromSidebar, Icons.Outlined.PushPin, separatorBefore = true) { s.unpin(f.path) },
                    ) },
                    onClick = { s.navigate(f.path) }) { KnownFolderGlyph(knownKindOf(f), M.SIDEBAR_ICON.dp) }
            }
            item { SidebarSection(S.drives, "drives", drivesOpen) { drivesOpen = !drivesOpen } }
            if (drivesOpen) items(s.drives) { d ->
                SidebarTree(s, d.root, d.title) { DriveGlyph(driveKindOf(d), M.SIDEBAR_ICON.dp) }
            }
            if (s.cloud.isNotEmpty()) {
                item { SidebarSection(S.cloudDrives, "cloud", cloudOpen) { cloudOpen = !cloudOpen } }
                if (cloudOpen) items(s.cloud) { c ->
                    SidebarTree(s, c.path, c.name) {
                        if (c.name.contains("Photos")) Icon(Icons.Filled.LocalFlorist, null, Modifier.size(M.SIDEBAR_ICON.dp), tint = Color(0xFFF29B38))
                        else Icon(Icons.Filled.Cloud, null, Modifier.size(M.SIDEBAR_ICON.dp), tint = Color(0xFF2F95F0))
                    }
                }
            }
            item { SidebarSection(S.network, "network", networkOpen) { networkOpen = !networkOpen } }
            if (networkOpen) item {
                SidebarRow(S.network, 1, loc == NETWORK, onClick = { s.navigate(NETWORK) }) {
                    Icon(Icons.Filled.Computer, null, Modifier.size(M.SIDEBAR_ICON.dp), tint = Color(0xFF2B88D8))
                }
            }
            if (s.wsl.isNotEmpty()) {
                item { SidebarSection(S.wsl, "wsl", wslOpen) { wslOpen = !wslOpen } }
                if (wslOpen) items(s.wsl) { d ->
                    val path = "\\\\wsl.localhost\\$d"
                    SidebarRow(d, 1, loc == path, onClick = { s.navigate(path) }) {
                        WslGlyph(d, s.wslIcons[d], M.SIDEBAR_ICON.dp)
                    }
                }
            }
            item { SidebarSection(S.tags, "tags", tagsOpen) { tagsOpen = !tagsOpen } }
            if (tagsOpen) items(s.tagStore.defs, key = { it.name }) { t ->
                SidebarRow(t.name, 1, loc == "$TAG_PREFIX${t.name}", onClick = { s.navigate("$TAG_PREFIX${t.name}") }) {
                    Icon(Icons.Filled.LocalOffer, null, Modifier.size(M.SIDEBAR_ICON.dp), tint = Color(t.color))
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(p.divider))
        Box(Modifier.padding(4.dp)) {
            SidebarRow(S.settings, 0, s.settingsOpen, onClick = { s.settingsOpen = true }) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(M.SIDEBAR_ICON.dp), tint = p.text)
            }
        }
    }
}

fun driveKindOf(d: DriveInfo): DriveKind = when {
    d.isSystem -> DriveKind.System
    CopyEngine.isUsb(d.root) -> DriveKind.Usb
    else -> DriveKind.Hdd
}

fun copyText(text: String) {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
}

@Composable
fun SidebarSection(title: String, iconKey: String, open: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val (icon, tint) = sectionIcon(iconKey)
    Hover(Modifier.fillMaxWidth().height(M.SIDEBAR_ROW.dp).padding(vertical = 2.dp), onClick = onToggle) {
        Row(Modifier.fillMaxSize().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = p.textSecondary)
            Spacer(Modifier.width(8.dp))
            Icon(icon, null, Modifier.size(M.SIDEBAR_ICON.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
            T(title, size = 14.sp, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SidebarRow(
    label: String,
    indent: Int,
    selected: Boolean,
    chevron: Boolean = false,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    menu: (() -> List<MenuEntry>)? = null,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val m = Modifier.fillMaxWidth().height(M.SIDEBAR_ROW.dp).padding(vertical = 2.dp)
    Box(if (menu != null) m.contextMenu(entries = menu) else m) {
        Hover(Modifier.fillMaxSize(), onClick = onClick, selected = selected, selectedColor = p.hover) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(M.SIDEBAR_INDICATOR_W.dp).height(M.SIDEBAR_INDICATOR_H.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) p.accent else Color.Transparent))
                Spacer(Modifier.width((3 + indent * 14).dp))
                Box(Modifier.width(16.dp)) {
                    if (chevron) IconBtn(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                        if (expanded) "Collapse $label" else "Expand $label", { onToggle?.invoke() },
                        width = 16.dp, height = 28.dp, iconSize = 14.dp)
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(M.SIDEBAR_ICON.dp)) { icon() }
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
fun StatusBar(s: AppState, items: List<FileItem>, t: TabState = s.tab) {
    val p = LocalPalette.current
    val selected = items.filter { it.path in t.selection }
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
        Row(
            Modifier.fillMaxWidth().heightIn(min = M.STATUS_BAR_HEIGHT.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (t.isHome) {
                T(S.items(s.pinned.size + s.drives.size + s.recent.size), size = 12.sp, color = p.textSecondary)
            } else {
                T(S.items(items.size) + if (t.loading && !t.isSearch && !t.isNetwork) "  ·  " + S.loading else "", size = 12.sp, color = p.textSecondary)
                if (selected.isNotEmpty()) {
                    VDivider()
                    T(S.selected(selected.size), size = 12.sp, color = p.textSecondary)
                    // Files plus any folders whose size has been worked out.
                    val bytes = selected.sumOf { s.sizeOf(it) ?: 0L }
                    if (selected.any { s.sizeOf(it) != null }) {
                        VDivider()
                        T(Fs.sizeText(bytes), size = 12.sp, color = p.textSecondary)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            s.gitBusy?.let { T(it, Modifier.widthIn(max = 300.dp), size = 12.sp, color = p.accentText) }
            s.deleteBusy?.let { T(it, Modifier.widthIn(max = 300.dp), size = 12.sp, color = p.accentText) }
            if (t.isSearch) t.searchInfo?.let { T(it, Modifier.widthIn(max = 420.dp), size = 12.sp, color = p.textTertiary) }
            t.git?.let { g ->
                T("${g.branch}  ↑${g.ahead} ↓${g.behind}", size = 12.sp, color = p.textSecondary)
            }
            s.clipboard?.let { T("${it.paths.size} on clipboard (${if (it.cut) "cut" else "copy"})", size = 12.sp, color = p.textTertiary) }
            if (!t.isHome) IconSizeSlider(s)
        }
    }
}

/**
 * 圖示大小 in the status bar (like Explorer's zoom slider): the item size of the current layout —
 * the 12 grid widths in 圖示, the 5 row sizes in the other layouts. Ctrl+wheel and Ctrl +/- do the same.
 */
@Composable
fun IconSizeSlider(s: AppState) {
    val p = LocalPalette.current
    val grid = s.layout == LayoutMode.Grid
    val max = if (grid) M.GRID_ITEM_WIDTH.size - 1 else 4
    val value = if (grid) s.gridStep else s.detailsSize
    fun set(v: Int) { if (grid) s.gridStep = v.coerceIn(0, max) else s.detailsSize = v.coerceIn(0, max) }
    VDivider()
    IconBtn(Icons.Outlined.ZoomOut, S.iconSize + " (Ctrl+-)", { set(value - 1) }, enabled = value > 0, width = 26.dp, height = 24.dp, iconSize = 14.dp)
    FluentSlider(value.toFloat(), { set(it.toInt()) }, range = 0f..max.toFloat(), steps = max, width = 110.dp)
    IconBtn(Icons.Outlined.ZoomIn, S.iconSize + " (Ctrl++)", { set(value + 1) }, enabled = value < max, width = 26.dp, height = 24.dp, iconSize = 14.dp)
    T(if (grid) "${M.GRID_ITEM_WIDTH[value]}px" else "${M.DETAILS_ICON[value]}px", Modifier.width(40.dp), size = 11.sp, color = p.textTertiary)
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
        // A running (or finished, not yet closed) shortcut, above the file operations.
        s.flowRun?.let { r -> FlowRunCard(s, r); Spacer(Modifier.height(8.dp)) }
        if (CopyEngine.jobs.isEmpty() && s.flowRun == null) {
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

/** 清空資源回收筒: asks once, with how much will go. */
@Composable
fun EmptyRecycleDialog(s: AppState) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { s.confirmEmptyRecycle = false } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(440.dp).softShadow(RoundedCornerShape(8.dp), elevation = 18.dp).background(p.flyout, RoundedCornerShape(8.dp))
                .border(1.dp, p.controlStroke, RoundedCornerShape(8.dp)).pointerInput(Unit) { detectTapGestures2 { } }
        ) {
            Column(Modifier.padding(24.dp)) {
                T(S.recycleEmpty, size = 20.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                T(S.recycleConfirm, maxLines = 3)
                s.recycleInfo?.let { (n, bytes) ->
                    Spacer(Modifier.height(8.dp))
                    T(S.recycleSummary(n, Fs.sizeText(bytes)), size = 12.sp, color = p.textSecondary)
                }
            }
            Row(Modifier.fillMaxWidth().background(p.layer).padding(24.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(4.dp)).background(p.danger).clickable { s.emptyRecycleBin() }) {
                    T(S.recycleEmpty, Modifier.align(Alignment.Center), color = if (p.dark) Color.Black else Color.White)
                }
                Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(4.dp)).background(p.control).border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)).clickable { s.confirmEmptyRecycle = false }) {
                    T(if (S.zh) "取消" else "Cancel", Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
fun DeleteDialog(s: AppState, paths: List<String>) {
    val p = LocalPalette.current
    val inBin = s.tab.isRecycleBin
    var permanent by remember { mutableStateOf(inBin) }
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
                if (inBin) T(S.cannotUndo, size = 12.sp, color = p.textSecondary)
                else Row(Modifier.clickable { permanent = !permanent }, verticalAlignment = Alignment.CenterVertically) {
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
