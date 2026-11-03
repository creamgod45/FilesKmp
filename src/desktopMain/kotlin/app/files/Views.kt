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
    var netOpen by remember { mutableStateOf(false) }     // collapsed by default in the original
    var recentOpen by remember { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Widget("Quick access", qaOpen, { qaOpen = !qaOpen }) {
                if (!s.pinnedLoaded) LoadingRow()
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.pinned.forEach { f ->
                        Box(Modifier.contextMenu {
                            listOf(
                                MenuEntry("Open in new tab", Icons.Outlined.Tab) { s.newTab(f.path) },
                                MenuEntry("Open in terminal", Icons.Outlined.Terminal) { Fs.openInTerminal(f.path) },
                                MenuEntry("Copy path", Icons.Outlined.Link) { copyText(f.path) },
                            )
                        }) {
                            Hover(Modifier.size(width = 128.dp, height = 96.dp), onClick = { s.navigate(f.path) }) {
                                Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    val ic = knownFolderIcon(f)
                                    Box {
                                        Icon(Icons.Filled.Folder, null, Modifier.size(40.dp), tint = p.folder)
                                        if (ic != Icons.Filled.Folder) Icon(ic, null, Modifier.size(16.dp).align(Alignment.Center).offset(y = 2.dp), tint = Color(0xFF7A5A12))
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    T(f.name, size = 12.sp, weight = FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Widget("Drives", drivesOpen, { drivesOpen = !drivesOpen }) {
                if (!s.drivesLoaded) LoadingRow()
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.drives.forEach { d -> DriveTile(s, d) }
                }
            }
        }
        item {
            Widget("Network locations", netOpen, { netOpen = !netOpen }) {
                T("No network locations. Mapped network drives show up here.", size = 12.sp, color = p.textSecondary)
            }
        }
        item {
            Widget("Recent files", recentOpen, { recentOpen = !recentOpen }) {
                when {
                    !s.recentLoaded -> LoadingRow()
                    s.recent.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.History, null, Modifier.size(32.dp), tint = p.textTertiary)
                        Spacer(Modifier.height(8.dp))
                        T("Files you've previously accessed will show up here", color = p.textSecondary)
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

@Composable
fun LoadingRow() {
    T("Loading…", Modifier.padding(8.dp), size = 12.sp, color = LocalPalette.current.textSecondary)
}

@Composable
fun Widget(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Hover(Modifier.height(36.dp), onClick = onToggle) {
            Row(Modifier.fillMaxHeight().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = p.textSecondary)
                Spacer(Modifier.width(8.dp))
                T(title, size = 14.sp, weight = FontWeight.SemiBold)
            }
        }
        if (open) Box(Modifier.padding(start = 8.dp, top = 4.dp)) { content() }
    }
}

@Composable
fun DriveTile(s: AppState, d: DriveInfo) {
    val p = LocalPalette.current
    val low = d.usedFraction * 100 >= M.LOW_SPACE_PERCENT
    Box(Modifier.contextMenu {
        listOf(
            MenuEntry("Open in new tab", Icons.Outlined.Tab) { s.newTab(d.root) },
            MenuEntry("Open in terminal", Icons.Outlined.Terminal) { Fs.openInTerminal(d.root) },
        )
    }) {
        Hover(
            Modifier.widthIn(min = M.DRIVE_TILE_MIN_W.dp).width(260.dp).heightIn(min = M.DRIVE_TILE_MIN_H.dp)
                .border(1.dp, p.cardStroke, RoundedCornerShape(M.CONTROL_RADIUS.dp)),
            onClick = { s.navigate(d.root) },
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storage, null, Modifier.size(M.DRIVE_ICON.dp), tint = p.textSecondary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(d.title, weight = FontWeight.Normal)
                    Spacer(Modifier.height(6.dp))
                    if (d.ready) {
                        ProgressBar(d.usedFraction, Modifier.fillMaxWidth(), color = if (low) p.danger else p.accent)
                        Spacer(Modifier.height(4.dp))
                        T("${Fs.sizeText(d.free)} free of ${Fs.sizeText(d.total)}", size = 12.sp, color = p.textSecondary)
                    } else {
                        T("Not responding — skipped so Home stays fast", size = 12.sp, color = p.textSecondary)
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
    val emptyMenu = {
        listOf(
            MenuEntry("New folder", Icons.Filled.Folder, "Ctrl+Shift+N") { s.newFolder() },
            MenuEntry("New text document", Icons.Outlined.Description) { s.newFile() },
            MenuEntry("Paste", Icons.Outlined.ContentPaste, "Ctrl+V", enabled = s.clipboard != null, separatorBefore = true) { s.paste() },
            MenuEntry("Select all", Icons.Outlined.SelectAll, "Ctrl+A") { s.selectAll() },
            MenuEntry("Refresh", Icons.Outlined.Refresh, "F5", separatorBefore = true) { s.refresh() },
            MenuEntry("Open in terminal", Icons.Outlined.Terminal, "Ctrl+`") { Fs.openInTerminal(t.location) },
            MenuEntry("Copy path", Icons.Outlined.Link, "Ctrl+Shift+C") { copyText(t.location) },
        )
    }
    Box(
        Modifier.fillMaxSize()
            .contextMenu(onOpen = { t.selection = emptySet() }, entries = emptyMenu)
            .pointerInput(t) { detectTapGestures2 { t.selection = emptySet(); t.renaming = null } }
    ) {
        when {
            t.error != null -> CenterNote(t.error!!)
            t.loading && items.isEmpty() -> CenterNote("Loading…")
            items.isEmpty() -> CenterNote(if (s.searchText.isNotBlank()) "No items match your search." else "This folder is empty.")
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
    return listOf(
        MenuEntry("Open", Icons.Outlined.OpenInNew, "Enter") { s.openSelected() },
        MenuEntry("Open in new tab", Icons.Outlined.Tab, enabled = item.isDir) { s.newTab(item.path) },
        MenuEntry("Open in terminal", Icons.Outlined.Terminal, enabled = item.isDir) { Fs.openInTerminal(item.path) },
        MenuEntry("Cut", Icons.Outlined.ContentCut, "Ctrl+X", separatorBefore = true) { s.copy(cut = true) },
        MenuEntry("Copy", Icons.Outlined.ContentCopy, "Ctrl+C") { s.copy() },
        MenuEntry("Paste", Icons.Outlined.ContentPaste, "Ctrl+V", enabled = s.clipboard != null) { s.paste() },
        MenuEntry("Copy path", Icons.Outlined.Link, "Ctrl+Shift+C") { s.copyPath() },
        MenuEntry("Rename", Icons.Outlined.DriveFileRenameOutline, "F2", enabled = !multi, separatorBefore = true) { s.startRename() },
        MenuEntry("Delete", Icons.Outlined.Delete, "Delete") { s.requestDelete() },
        MenuEntry("Properties", Icons.Outlined.Info, "Alt+Enter", separatorBefore = true) { s.infoPaneOpen = true },
    )
}

/** Shared selection + open + menu behaviour for one item. */
@Composable
fun ItemBox(s: AppState, t: TabState, item: FileItem, items: List<FileItem>, modifier: Modifier, content: @Composable BoxScope.(selected: Boolean) -> Unit) {
    val p = LocalPalette.current
    val selected = item.path in t.selection
    val cut = s.clipboard?.let { it.cut && item.path in it.paths } == true
    Box(
        modifier
            .contextMenu(onOpen = { if (item.path !in t.selection) { t.selection = setOf(item.path); t.anchor = item.path } }) { itemMenu(s, item) }
            .itemPointer(
                onPress = { ctrl, shift -> s.click(item, ctrl, shift, items) },
                onDouble = { if (item.isDir) s.navigate(item.path) else Fs.open(item.path) },
            )
    ) {
        Hover(Modifier.matchParentSize(), selected = selected, selectedColor = p.selected) {}
        if (selected) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(p.accent))
        // Items that were cut are drawn faded until pasted.
        Box(Modifier.matchParentSize().alpha(if (cut) 0.5f else 1f)) { content(selected) }
    }
}

@Composable
fun NameCell(s: AppState, t: TabState, item: FileItem, modifier: Modifier) {
    if (t.renaming == item.path) {
        EditBox(item.name, onCommit = { s.commitRename(item.path, it) }, onCancel = { t.renaming = null }, modifier = modifier)
    } else {
        T(s.displayName(item), modifier)
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
            Spacer(Modifier.width(icon + 30.dp))
            HeaderCell(s, "Name", SortBy.Name, Modifier.width(nameW.dp))
            Box(Modifier.width(6.dp).fillMaxHeight()) { Splitter { nameW = (nameW + it / 1.25f).coerceIn(120f, 800f) } }
            HeaderCell(s, "Date modified", SortBy.DateModified, Modifier.width(M.COL_DATE_MODIFIED.dp))
            HeaderCell(s, "Type", SortBy.Type, Modifier.width(M.COL_TYPE.dp))
            HeaderCell(s, "Size", SortBy.Size, Modifier.width(M.COL_SIZE.dp))
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
                            T(Fs.dateText(item.modified), Modifier.width(M.COL_DATE_MODIFIED.dp).padding(start = 12.dp), size = 13.sp, color = p.textSecondary)
                            T(item.typeName, Modifier.width(M.COL_TYPE.dp).padding(start = 12.dp), size = 13.sp, color = p.textSecondary)
                            T(if (item.isDir) "" else Fs.sizeText(item.size), Modifier.width(M.COL_SIZE.dp).padding(start = 12.dp), size = 13.sp, color = p.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CheckMark(selected: Boolean) {
    // ShowCheckboxesWhenSelectingItems = true: a checkbox appears on selected items.
    val p = LocalPalette.current
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        if (selected) Icon(Icons.Filled.CheckBox, null, Modifier.size(18.dp), tint = p.accent)
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
        T("Details", Modifier.fillMaxWidth(), size = 14.sp, weight = FontWeight.SemiBold)
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
                FileIcon(it, 96.dp)
                Spacer(Modifier.height(12.dp))
                T(it.name, weight = FontWeight.SemiBold, maxLines = 3)
                Spacer(Modifier.height(16.dp))
                Prop("Item type", it.typeName)
                if (!it.isDir) Prop("Size", "${Fs.sizeText(it.size)} (${"%,d".format(it.size)} bytes)")
                Prop("Date modified", Fs.dateText(it.modified))
                Prop("Date created", Fs.dateText(it.created))
                Prop("Path", it.path)
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
