package app.files

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ====================================================================== Home

@Composable
fun HomePage(s: AppState) {
    val p = LocalPalette.current
    var qaOpen by remember { mutableStateOf(true) }
    var drivesOpen by remember { mutableStateOf(true) }
    var tagsOpen by remember { mutableStateOf(true) }
    var recentOpen by remember { mutableStateOf(true) }
    var netOpen by remember { mutableStateOf(false) }   // collapsed by default in the original
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
        if (s.showQuickAccessWidget) item {
            Widget(S.quickAccess, qaOpen, { qaOpen = !qaOpen }) {
                if (!s.pinnedLoaded) LoadingRow()
                else UniformGrid(M.QA_MIN_W, M.QA_H, M.QA_MAX_COLS, stretch = true, count = s.pinned.size) { i, w ->
                    QuickAccessCard(s, s.pinned[i], w)
                }
            }
        }
        if (s.showDrivesWidget) item {
            Widget(S.drives, drivesOpen, { drivesOpen = !drivesOpen }) {
                if (!s.drivesLoaded) LoadingRow()
                else UniformGrid(M.DRIVE_TILE_MIN_W, M.DRIVE_TILE_MIN_H, Int.MAX_VALUE, stretch = false, count = s.drives.size) { i, _ ->
                    DriveTile(s, s.drives[i])
                }
            }
        }
        if (s.showNetworkWidget) item {
            Widget(S.networkLocations, netOpen, { netOpen = !netOpen }) {
                T(S.noNetwork, Modifier.padding(8.dp), size = 12.sp, color = p.textSecondary)
            }
        }
        if (s.showTagsWidget) item {
            Widget(S.tags, tagsOpen, { tagsOpen = !tagsOpen }) {
                val defs = s.tagStore.defs
                UniformGrid(M.TAG_MIN_W, M.TAG_H, M.TAG_MAX_COLS, stretch = true, count = defs.size) { i, w ->
                    TagCard(s, defs[i], w)
                }
            }
        }
        if (s.showRecentWidget) item {
            Widget(S.recentFiles, recentOpen, { recentOpen = !recentOpen }) {
                when {
                    !s.recentLoaded -> LoadingRow()
                    s.recent.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.History, null, Modifier.size(32.dp), tint = p.textTertiary)
                        Spacer(Modifier.height(8.dp))
                        T(S.recentEmpty, color = p.textSecondary)
                    }
                    else -> Column {
                        s.recent.forEach { r ->
                            Hover(Modifier.fillMaxWidth().height(36.dp), onClick = { Fs.open(r.path) }) {
                                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(iconForExt(r.name.substringAfterLast('.', "").lowercase()), null, Modifier.size(16.dp), tint = p.textSecondary)
                                    Spacer(Modifier.width(12.dp))
                                    T(r.name, Modifier.weight(1f))
                                    T(Fs.dateText(r.modified), size = 12.sp, color = p.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Card surface used by the Home widgets (CardBackgroundFillColorDefault + CardStroke, radius 4). */
@Composable
fun HomeCard(modifier: Modifier, onClick: (() -> Unit)?, content: @Composable BoxScope.() -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(M.CONTROL_RADIUS.dp)
    Box(modifier.clip(shape).background(p.card, shape).border(1.dp, p.cardStroke, shape)) {
        Hover(Modifier.matchParentSize(), onClick = onClick, shape = shape) {}
        content()
    }
}

/**
 * WinUI UniformGridLayout as the original uses it: columns = as many min-width items as fit
 * (8 px spacing), capped at maxCols; with stretch every item widens to fill its column.
 */
@Composable
fun UniformGrid(minW: Int, itemH: Int, maxCols: Int, stretch: Boolean, count: Int, item: @Composable (Int, androidx.compose.ui.unit.Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spacing = M.WIDGET_SPACING
        val avail = maxWidth.value
        val cols = (((avail + spacing) / (minW + spacing)).toInt()).coerceIn(1, maxCols)
        val w = if (stretch) (avail - spacing * (cols - 1)) / cols else minW.toFloat()
        Column(verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
            for (row in 0 until (count + cols - 1) / cols) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.dp)) {
                    for (c in 0 until cols) {
                        val i = row * cols + c
                        if (i < count) Box(Modifier.width(w.dp).height(itemH.dp)) { item(i, w.dp) }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickAccessCard(s: AppState, f: PinnedFolder, width: androidx.compose.ui.unit.Dp) {
    val p = LocalPalette.current
    Box(Modifier.contextMenu {
        listOf(
            MenuEntry(S.openInNewTab, Icons.Outlined.Tab) { s.newTab(f.path) },
            MenuEntry(S.openInTerminal, Icons.Outlined.Terminal) { Fs.openInTerminal(f.path) },
            MenuEntry(S.copyPath, Icons.Outlined.Link) { copyText(f.path) },
        )
    }) {
        HomeCard(Modifier.size(width, M.QA_H.dp), onClick = { s.navigate(f.path) }) {
            Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                KnownFolderGlyph(knownKind(f), M.QA_ICON.dp)
                T(f.name, size = 14.sp)
            }
            Icon(Icons.Outlined.PushPin, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp), tint = p.textSecondary)
        }
    }
}

@Composable
fun LoadingRow() {
    T(S.loading, Modifier.padding(8.dp), size = 12.sp, color = LocalPalette.current.textSecondary)
}

@Composable
fun Widget(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Hover(Modifier.height(32.dp), onClick = onToggle) {
            Row(Modifier.fillMaxHeight().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(14.dp), tint = p.textSecondary)
                Spacer(Modifier.width(12.dp))
                T(title, size = 12.sp, weight = FontWeight.SemiBold)
            }
        }
        if (open) Box(Modifier.padding(top = 4.dp)) { content() }
    }
}

@Composable
fun DriveTile(s: AppState, d: DriveInfo) {
    val p = LocalPalette.current
    val low = d.usedFraction * 100 >= M.LOW_SPACE_PERCENT
    Box(Modifier.contextMenu {
        listOf(
            MenuEntry(S.openInNewTab, Icons.Outlined.Tab) { s.newTab(d.root) },
            MenuEntry(S.openInTerminal, Icons.Outlined.Terminal) { Fs.openInTerminal(d.root) },
        )
    }) {
        HomeCard(Modifier.width(M.DRIVE_TILE_MIN_W.dp).height(M.DRIVE_TILE_MIN_H.dp), onClick = { s.navigate(d.root) }) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                DriveGlyph(driveKind(d), M.DRIVE_ICON.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    T(d.title)
                    Spacer(Modifier.height(6.dp))
                    if (d.ready) {
                        ProgressBar(d.usedFraction, Modifier.fillMaxWidth(), color = if (low) p.danger else p.accent)
                        Spacer(Modifier.height(6.dp))
                        T(S.freeOf(Fs.sizeText(d.free), Fs.sizeText(d.total)), size = 12.sp, color = p.textSecondary)
                    } else {
                        T(S.notResponding, size = 12.sp, color = p.textSecondary)
                    }
                }
            }
        }
    }
}

/** One tag on Home: its newest tagged files (click to open), "···" opens the whole tag view. */
@Composable
fun TagCard(s: AppState, t: TagDef, width: androidx.compose.ui.unit.Dp) {
    val p = LocalPalette.current
    val files = s.tagStore.filesWith(t.name)
    HomeCard(Modifier.size(width, M.TAG_H.dp), onClick = null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(38.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocalOffer, null, Modifier.size(16.dp), tint = Color(t.color))
                Spacer(Modifier.width(8.dp))
                T(t.name, Modifier.weight(1f), size = 14.sp, weight = FontWeight.SemiBold)
                IconBtn(Icons.Filled.MoreHoriz, S.openTag, { s.navigate("$TAG_PREFIX${t.name}") }, width = 30.dp, height = 28.dp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
            if (files.isEmpty()) {
                T(S.noTagged, Modifier.padding(12.dp), size = 12.sp, color = p.textTertiary, maxLines = 4)
            } else Column(Modifier.padding(4.dp)) {
                files.take(M.TAG_WIDGET_FILES).forEach { path ->
                    val name = Fs.displayName(path)
                    val isDir = java.io.File(path).isDirectory
                    Hover(Modifier.fillMaxWidth().height(32.dp), onClick = { if (isDir) s.navigate(path) else Fs.open(path) }) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (isDir) FolderGlyph(16.dp) else Icon(iconForExt(name.substringAfterLast('.', "").lowercase()), null, Modifier.size(16.dp), tint = p.textSecondary)
                            Spacer(Modifier.width(8.dp))
                            T(name, size = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================== folder views

@Composable
fun FolderView(s: AppState, t: TabState, items: List<FileItem>) {
    val p = LocalPalette.current
    // "Calculate folder sizes" on (off by default, FoldersSettingsService.cs:112): size every folder shown.
    LaunchedEffect(t.raw, s.calculateFolderSizes) {
        if (s.calculateFolderSizes) items.filter { it.isDir && s.folderSizes[it.path] == null }.forEach { s.calcFolderSize(it.path) }
    }
    val emptyMenu = {
        if (t.isVirtual) listOf(
            MenuEntry(S.selectAll, Icons.Outlined.SelectAll, "Ctrl+A") { s.selectAll() },
            MenuEntry(S.refresh, Icons.Outlined.Refresh, "F5") { s.refresh() },
        ) else listOf(
            MenuEntry(S.newFolder, Icons.Filled.Folder, "Ctrl+Shift+N") { s.newFolder() },
            MenuEntry(S.textDocument, Icons.Outlined.Description) { s.newFile() },
            MenuEntry(S.paste, Icons.Outlined.ContentPaste, "Ctrl+V", enabled = s.clipboard != null, separatorBefore = true) { s.paste() },
            MenuEntry(S.selectAll, Icons.Outlined.SelectAll, "Ctrl+A") { s.selectAll() },
            MenuEntry(S.refresh, Icons.Outlined.Refresh, "F5", separatorBefore = true) { s.refresh() },
            MenuEntry(S.pinToSidebar, Icons.Outlined.PushPin) { s.pin(t.location) },
            MenuEntry(S.openInTerminal, Icons.Outlined.Terminal, "Ctrl+`") { Fs.openInTerminal(t.location) },
            MenuEntry(S.copyPath, Icons.Outlined.Link, "Ctrl+Shift+C") { copyText(t.location) },
        )
    }
    Box(
        Modifier.fillMaxSize()
            .contextMenu(onOpen = { s.activate(t); t.selection = emptySet() }, entries = emptyMenu)
            .pointerInput(t) { detectTapGestures2 { s.activate(t); t.selection = emptySet(); t.renaming = null } }
    ) {
        when {
            t.error != null -> CenterNote(t.error!!)
            t.loading && items.isEmpty() -> CenterNote(if (t.isSearch) S.searching else S.loading)
            items.isEmpty() -> CenterNote(
                when {
                    t.isTag -> S.noTagged
                    t.isSearch || s.searchText.isNotBlank() -> S.noResults
                    else -> S.emptyFolder
                }
            )
            else -> when (s.layout) {
                LayoutMode.Details -> DetailsLayout(s, t, items)
                LayoutMode.List -> ListLayout(s, t, items)
                LayoutMode.Cards -> CardsLayout(s, t, items)
                LayoutMode.Grid -> GridLayout(s, t, items)
            }
        }
    }
}

@Composable
fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
        T(text, color = LocalPalette.current.textSecondary)
    }
}

fun itemMenu(s: AppState, item: FileItem): List<MenuEntry> {
    val multi = s.tab.selection.size > 1
    val isZip = item.ext == "zip"
    val pinned = s.pinned.any { it.path.equals(item.path, true) }
    return listOfNotNull(
        MenuEntry(S.open, Icons.Outlined.OpenInNew, "Enter") { s.openSelected() },
        MenuEntry(S.openInNewTab, Icons.Outlined.Tab, enabled = item.isDir) { s.newTab(item.path) },
        MenuEntry(S.openInTerminal, Icons.Outlined.Terminal, enabled = item.isDir) { Fs.openInTerminal(item.path) },
        if (item.isDir) (if (pinned) MenuEntry(S.unpinFromSidebar, Icons.Outlined.PushPin) { s.unpin(item.path) }
            else MenuEntry(S.pinToSidebar, Icons.Outlined.PushPin) { s.pin(item.path) }) else null,
        MenuEntry(S.cut, Icons.Outlined.ContentCut, "Ctrl+X", separatorBefore = true) { s.copy(cut = true) },
        MenuEntry(S.copy, Icons.Outlined.ContentCopy, "Ctrl+C") { s.copy() },
        MenuEntry(S.paste, Icons.Outlined.ContentPaste, "Ctrl+V", enabled = s.clipboard != null && s.canCreateHere) { s.paste() },
        MenuEntry(S.copyPath, Icons.Outlined.Link, "Ctrl+Shift+C") { s.copyPath() },
        MenuEntry(S.editTags, Icons.Outlined.LocalOffer, separatorBefore = true) { s.editTags() },
        MenuEntry(S.compress, Icons.Outlined.FolderZip) { s.compressSelected() },
        if (isZip) MenuEntry(S.extract, Icons.Outlined.Unarchive) { s.extractSelected() } else null,
        MenuEntry(S.rename, Icons.Outlined.DriveFileRenameOutline, "F2", enabled = !multi, separatorBefore = true) { s.startRename() },
        MenuEntry(S.delete, Icons.Outlined.Delete, "Delete") { s.requestDelete() },
        MenuEntry(S.properties, Icons.Outlined.Info, "Alt+Enter", separatorBefore = true) { s.infoPaneOpen = true },
    )
}

/** Shared selection + open + menu behaviour for one item. */
@Composable
fun ItemBox(s: AppState, t: TabState, item: FileItem, items: List<FileItem>, modifier: Modifier, content: @Composable BoxScope.(selected: Boolean) -> Unit) {
    val p = LocalPalette.current
    val selected = item.path in t.selection
    val cut = s.clipboard?.let { it.cut && item.path in it.paths } == true
    val hoverSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    CompositionLocalProvider(LocalRow provides RowCtx(s, t, item, hovered)) {
    Box(
        modifier
            .hoverable(hoverSrc)
            .contextMenu(onOpen = { s.activate(t); if (item.path !in t.selection) { t.selection = setOf(item.path); t.anchor = item.path } }) { itemMenu(s, item) }
            .itemPointer(
                onPress = { ctrl, shift -> s.activate(t); s.click(item, ctrl, shift, items) },
                onDouble = { if (item.isDir) s.openFolder(item.path) else Fs.open(item.path) },
            )
    ) {
        Hover(Modifier.matchParentSize(), selected = selected, selectedColor = p.selected) {}
        if (selected) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(p.accent))
        // Items that were cut are drawn faded until pasted.
        Box(Modifier.matchParentSize().alpha(if (cut) 0.5f else 1f)) { content(selected) }
    }
    }
}

class RowCtx(val s: AppState, val t: TabState, val item: FileItem, val hovered: Boolean)
val LocalRow = staticCompositionLocalOf<RowCtx?> { null }

@Composable
fun NameCell(s: AppState, t: TabState, item: FileItem, modifier: Modifier) {
    if (t.renaming == item.path) {
        EditBox(item.name, onCommit = { s.commitRename(item.path, it) }, onCancel = { t.renaming = null }, modifier = modifier)
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            T(s.displayName(item), Modifier.weight(1f))
            t.git?.let { g -> Git.statusOf(g, item.path, item.isDir)?.let { code ->
                T(Git.label(code), Modifier.padding(start = 4.dp, end = 6.dp), size = 11.sp, color = Color(Git.color(code)))
            } }
        }
    }
}

@Composable
fun DetailsLayout(s: AppState, t: TabState, items: List<FileItem>) {
    val p = LocalPalette.current
    val rowH = M.DETAILS_ROW[s.detailsSize].dp
    val icon = M.DETAILS_ICON[s.detailsSize].dp
    var nameW by remember { mutableStateOf(M.COL_NAME.toFloat()) }
    val state = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        // Header (40)
        Row(Modifier.fillMaxWidth().height(M.DETAILS_HEADER.dp).padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(4.dp))
            if (s.showCheckboxes) SelectAllBox(s, t, items) else Spacer(Modifier.width(20.dp))
            Spacer(Modifier.width(icon + 6.dp))
            HeaderCell(s, S.name, SortBy.Name, Modifier.width(nameW.dp))
            Box(Modifier.width(6.dp).fillMaxHeight()) { Splitter { nameW = (nameW + it / 1.25f).coerceIn(120f, 800f) } }
            T(S.fileTag, Modifier.width(M.COL_TAG.dp).padding(start = 12.dp), size = 13.sp, color = p.textSecondary)
            HeaderCell(s, S.dateModified, SortBy.DateModified, Modifier.width(M.COL_DATE_MODIFIED.dp))
            HeaderCell(s, S.type, SortBy.Type, Modifier.width(M.COL_TYPE.dp))
            HeaderCell(s, S.size, SortBy.Size, Modifier.width(M.COL_SIZE.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
        ScrollList(state, Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
                items(items, key = { it.path }) { item ->
                    ItemBox(s, t, item, items, Modifier.fillMaxWidth().height(rowH)) { selected ->
                        Row(Modifier.fillMaxSize().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CheckMark(selected)
                            Spacer(Modifier.width(8.dp))
                            FileIcon(item, icon)
                            Spacer(Modifier.width(10.dp))
                            NameCell(s, t, item, Modifier.width((nameW - 6).dp))
                            TagPills(s.tagStore.tagsOf(item.path), Modifier.width(M.COL_TAG.dp).padding(start = 12.dp))
                            T(Fs.dateText(item.modified), Modifier.width(M.COL_DATE_MODIFIED.dp).padding(start = 12.dp), size = 13.sp, color = p.textSecondary)
                            T(item.typeName, Modifier.width(M.COL_TYPE.dp).padding(start = 12.dp), size = 13.sp, color = p.textSecondary)
                            SizeCell(s, item, Modifier.width(M.COL_SIZE.dp).padding(start = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Size column. Files show their size; folders show the total once worked out, otherwise a
 * "檢視大小" link (ItemSizeNotCalculated in the original) that adds the folder up on click.
 */
@Composable
fun SizeCell(s: AppState, item: FileItem, modifier: Modifier) {
    val p = LocalPalette.current
    if (!item.isDir) {
        T(Fs.sizeText(item.size), modifier, size = 13.sp, color = p.textSecondary)
        return
    }
    val known = s.folderSizes[item.path]
    when {
        known != null -> T(Fs.sizeText(known), modifier, size = 13.sp, color = p.textSecondary)
        s.folderSizing[item.path] == true -> T(S.calculating, modifier, size = 13.sp, color = p.textTertiary)
        else -> Box(modifier) {
            T(
                S.viewSize,
                Modifier.clip(RoundedCornerShape(3.dp)).clickable { s.calcFolderSize(item.path) }.padding(horizontal = 2.dp),
                size = 13.sp, color = p.accentText,
            )
        }
    }
}

@Composable
fun CheckMark(selected: Boolean) {
    // ShowCheckboxesWhenSelectingItems (on by default): a checkbox shows on hover and on selected items.
    // Clicking it adds or removes only this item — it never clears the rest of the selection,
    // unlike a plain click on the row, which selects just that row.
    val p = LocalPalette.current
    val row = LocalRow.current
    val show = row != null && row.s.showCheckboxes && (selected || row.hovered)
    Box(
        Modifier.size(20.dp).then(
            if (row != null && row.s.showCheckboxes) Modifier.pointerInput(row.item.path) {
                awaitPointerEventScope {
                    var ours = false
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press && e.buttons.isPrimaryPressed) {
                            e.changes.forEach { it.consume() }
                            ours = true
                            row.s.activate(row.t)
                            row.s.toggleSelect(row.item)
                        } else if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Release && ours) {
                            e.changes.forEach { it.consume() }   // keep the click from reaching the row or the list background
                            ours = false
                        }
                    }
                }
            } else Modifier
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (show) Icon(
            if (selected) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(18.dp),
            tint = if (selected) p.accent else p.textSecondary,
        )
    }
}

/** Header checkbox: select every item, or clear the selection when all are selected. */
@Composable
fun SelectAllBox(s: AppState, t: TabState, items: List<FileItem>) {
    val p = LocalPalette.current
    val all = items.isNotEmpty() && items.all { it.path in t.selection }
    val some = !all && items.any { it.path in t.selection }
    Box(Modifier.size(20.dp).clip(RoundedCornerShape(3.dp)).clickable { s.activate(t); t.selection = if (all) emptySet() else items.map { it.path }.toSet() }, contentAlignment = Alignment.Center) {
        Icon(
            when { all -> Icons.Filled.CheckBox; some -> Icons.Filled.IndeterminateCheckBox; else -> Icons.Outlined.CheckBoxOutlineBlank },
            null, Modifier.size(18.dp), tint = if (all || some) p.accent else p.textSecondary,
        )
    }
}

@Composable
fun HeaderCell(s: AppState, label: String, sort: SortBy, modifier: Modifier) {
    val p = LocalPalette.current
    Hover(modifier.fillMaxHeight(), onClick = {
        if (s.sortBy == sort) s.sortAscending = !s.sortAscending else { s.sortBy = sort; s.sortAscending = true }
    }) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            T(label, Modifier.weight(1f, fill = false), size = 13.sp, color = p.textSecondary)
            if (s.sortBy == sort) {
                Spacer(Modifier.width(4.dp))
                Icon(if (s.sortAscending) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, Modifier.size(14.dp), tint = p.textSecondary)
            }
        }
    }
}

@Composable
fun ScrollList(state: LazyListState, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        content()
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp))
    }
}

@Composable
fun ListLayout(s: AppState, t: TabState, items: List<FileItem>) {
    val rowH = M.LIST_ROW[s.detailsSize].dp
    val icon = M.DETAILS_ICON[s.detailsSize].dp
    LazyVerticalGrid(GridCells.Adaptive(260.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        items(items, key = { it.path }) { item ->
            ItemBox(s, t, item, items, Modifier.height(rowH).padding(end = 4.dp)) { selected ->
                Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileIcon(item, icon)
                    Spacer(Modifier.width(10.dp))
                    NameCell(s, t, item, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun CardsLayout(s: AppState, t: TabState, items: List<FileItem>) {
    val p = LocalPalette.current
    val icon = M.CARDS_ICON[1].dp
    LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items, key = { it.path }) { item ->
            ItemBox(s, t, item, items, Modifier.height(icon + 24.dp).border(1.dp, p.cardStroke, RoundedCornerShape(6.dp))) { _ ->
                Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileIcon(item, icon)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        NameCell(s, t, item, Modifier.fillMaxWidth())
                        T(item.typeName, size = 12.sp, color = p.textSecondary)
                        T(if (item.isDir) Fs.dateText(item.modified) else "${Fs.sizeText(item.size)} · ${Fs.dateText(item.modified)}", size = 12.sp, color = p.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
fun GridLayout(s: AppState, t: TabState, items: List<FileItem>) {
    val w = M.GRID_ITEM_WIDTH[s.gridStep]
    val icon: Dp = when {
        s.gridStep <= 3 -> 64.dp
        s.gridStep <= M.GRID_DEFAULT_STEP -> 96.dp
        else -> 128.dp
    }
    LazyVerticalGrid(GridCells.Adaptive(w.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(items, key = { it.path }) { item ->
            ItemBox(s, t, item, items, Modifier.height(icon + 52.dp)) { _ ->
                Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FileIcon(item, icon)
                    Spacer(Modifier.height(6.dp))
                    if (t.renaming == item.path) NameCell(s, t, item, Modifier.fillMaxWidth())
                    else androidx.compose.material3.Text(
                        s.displayName(item), fontSize = 12.sp, color = LocalPalette.current.text, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ====================================================================== info (details) pane

@Composable
fun InfoPane(s: AppState, items: List<FileItem>) {
    val p = LocalPalette.current
    val t = s.tab
    val sel = items.filter { it.path in t.selection }
    val shape = RoundedCornerShape(M.CARD_RADIUS.dp)
    Column(
        Modifier.width(M.INFO_PANE_WIDTH.dp).fillMaxHeight().clip(shape).background(p.toolbar, shape).border(1.dp, p.cardStroke, shape).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T(S.details, Modifier.fillMaxWidth(), size = 14.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(24.dp))
        when {
            t.isHome -> T("Select a folder to see its details.", color = p.textSecondary, maxLines = 3)
            sel.isEmpty() -> {
                Icon(Icons.Filled.Folder, null, Modifier.size(96.dp), tint = p.folder)
                Spacer(Modifier.height(12.dp))
                T(Fs.displayName(t.location), weight = FontWeight.SemiBold)
                T("${items.size} ${if (items.size == 1) "item" else "items"}", size = 12.sp, color = p.textSecondary)
            }
            sel.size > 1 -> {
                Icon(Icons.Outlined.Inventory2, null, Modifier.size(96.dp), tint = p.textSecondary)
                Spacer(Modifier.height(12.dp))
                T("${sel.size} items selected", weight = FontWeight.SemiBold)
                T(Fs.sizeText(sel.sumOf { it.size }), size = 12.sp, color = p.textSecondary)
            }
            else -> {
                val it = sel[0]
                Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    PreviewBox(it)
                    Spacer(Modifier.height(12.dp))
                    T(it.name, weight = FontWeight.SemiBold, maxLines = 3)
                    val tags = s.tagStore.tagsOf(it.path)
                    if (tags.isNotEmpty()) { Spacer(Modifier.height(6.dp)); TagPills(tags) }
                    Spacer(Modifier.height(16.dp))
                    Prop(S.type, it.typeName)
                    if (!it.isDir) Prop(S.size, "${Fs.sizeText(it.size)} (${"%,d".format(it.size)} bytes)")
                    Prop(S.dateModified, Fs.dateTimeText(it.modified))
                    Prop(S.dateCreated, Fs.dateTimeText(it.created))
                    Prop(if (S.zh) "路徑" else "Path", it.path)
                }
            }
        }
    }
}

@Composable
fun Prop(label: String, value: String) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        T(label, size = 12.sp, color = p.textSecondary)
        T(value, size = 13.sp, maxLines = 3)
    }
}
