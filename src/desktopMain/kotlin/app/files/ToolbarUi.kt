package app.files

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ====================================================================== the toolbar

/**
 * The customizable left part of the command toolbar: 通用工具列, then the active contexts,
 * each after an automatic separator. Items that don't fit go into a "…" menu.
 */
@Composable
fun CustomToolbar(s: AppState, modifier: Modifier, layout: Map<TbContext, List<TbEntry>> = s.toolbarStore.layout,
                  contexts: List<TbContext> = activeContexts(s), interactive: Boolean = true) {
    val items = remember(layout, contexts) {
        val raw = ArrayList<Pair<TbContext, Pair<Int, TbEntry>>>()
        contexts.forEachIndexed { ci, c ->
            if (ci > 0) raw += c to (-1 to TbEntry(SEPARATOR, false, false))
            (layout[c] ?: emptyList()).forEachIndexed { i, e ->
                // An item with neither icon nor label stays in the list but is hidden on the toolbar.
                if (e.isSeparator || e.showIcon || e.showLabel) raw += c to (i to e)
            }
        }
        cleanSeparators(raw.filter { it.second.second.isSeparator || TbCommands.get(it.second.second.id) != null })
    }
    Box(
        modifier.then(if (interactive) Modifier.contextMenu {
            listOf(MenuEntry(S.customizeToolbar, Icons.Outlined.Tune) { s.customizeToolbarOpen = true })
        } else Modifier)
    ) {
        OverflowRow(
            count = items.size,
            item = { i ->
                val (ctx, pair) = items[i]
                ToolbarItem(s, pair.second, interactive, onUnpin = if (pair.first >= 0) ({ s.toolbarStore.unpin(ctx, pair.first) }) else null)
            },
            more = { hidden ->
                var open by remember { mutableStateOf(false) }
                Box {
                    IconBtn(Icons.Outlined.MoreHoriz, if (S.zh) "更多選項" else "More options", { if (interactive) open = true }, width = 40.dp, height = 36.dp)
                    Flyout(open, { open = false }) {
                        hidden.forEach { i ->
                            val e = items[i].second.second
                            if (e.isSeparator) FlyoutSeparator()
                            else TbCommands.get(e.id)?.let { cmd ->
                                if (cmd.isGroup) {
                                    T(cmd.label, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp), size = 12.sp, color = LocalPalette.current.textSecondary)
                                    cmd.groupItems!!(s).forEach { m -> FlyoutItem(m.label, m.icon, m.hotkey, enabled = cmd.enabled(s) && m.enabled) { open = false; m.action() } }
                                } else FlyoutItem(cmd.label, cmd.icon, cmd.hotkey, enabled = cmd.enabled(s)) { open = false; cmd.run(s) }
                            }
                        }
                    }
                }
            },
        )
    }
}

/** Places children left to right; when they don't fit, the rest go into [more]. */
@Composable
fun OverflowRow(count: Int, item: @Composable (Int) -> Unit, more: @Composable (List<Int>) -> Unit, modifier: Modifier = Modifier) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = (0 until count).map { i -> subcompose("i$i") { item(i) }.map { it.measure(loose) } }
        val widths = placeables.map { ps -> ps.sumOf { it.width } }
        var visible = count
        var morePlaceables = emptyList<androidx.compose.ui.layout.Placeable>()
        if (widths.sum() > constraints.maxWidth) {
            val moreW = subcompose("probe") { more(emptyList()) }.map { it.measure(loose) }.sumOf { it.width }
            var used = 0
            visible = 0
            while (visible < count && used + widths[visible] <= constraints.maxWidth - moreW) { used += widths[visible]; visible++ }
            morePlaceables = subcompose("more") { more((visible until count).toList()) }.map { it.measure(loose) }
        }
        val h = (placeables.flatten() + morePlaceables).maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, h) {
            var x = 0
            for (i in 0 until visible) placeables[i].forEach { it.place(x, (h - it.height) / 2); x += it.width }
            morePlaceables.forEach { it.place(x, (h - it.height) / 2); x += it.width }
        }
    }
}

/** One toolbar button as its 圖示 / 文字標籤 switches say; groups open a drop-down; toggles show their state. */
@Composable
fun ToolbarItem(s: AppState, e: TbEntry, interactive: Boolean, onUnpin: (() -> Unit)?) {
    if (e.isSeparator) {
        Box(Modifier.padding(horizontal = 4.dp)) { VDivider() }
        return
    }
    val cmd = TbCommands.get(e.id) ?: return
    val p = LocalPalette.current
    val enabled = interactive && cmd.enabled(s)
    val on = cmd.toggled?.invoke(s) == true
    var open by remember { mutableStateOf(false) }
    val showIcon = e.showIcon && cmd.icon != null
    val showLabel = e.showLabel || (!showIcon)
    val menu = Modifier.then(if (interactive && onUnpin != null) Modifier.contextMenu {
        listOf(
            MenuEntry(S.customizeToolbar, Icons.Outlined.Tune) { s.customizeToolbarOpen = true },
            MenuEntry(S.unpin, Icons.Outlined.PushPin) { onUnpin() },
        )
    } else Modifier)
    val tip = cmd.label + if (cmd.hotkey.isNotEmpty()) " (${cmd.hotkey})" else ""
    Box(menu) {
        if (showIcon && !showLabel && !cmd.isGroup) {
            IconBtn(cmd.icon!!, tip, { if (interactive) cmd.run(s) }, enabled = enabled || !interactive, width = 40.dp, height = 36.dp, selected = on)
        } else {
            Hover(Modifier.height(36.dp), onClick = { if (!interactive) return@Hover; if (cmd.isGroup) open = true else cmd.run(s) },
                enabled = enabled || !interactive, selected = on) {
                Row(Modifier.padding(horizontal = 10.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    val tint = if (enabled || !interactive) p.text else p.textTertiary
                    if (showIcon) Icon(cmd.icon!!, cmd.label, Modifier.size(16.dp), tint = tint)
                    if (showIcon && showLabel) Spacer(Modifier.width(8.dp))
                    if (showLabel) T(cmd.label, size = 14.sp, color = tint)
                    if (cmd.isGroup) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(14.dp), tint = p.textSecondary)
                    }
                }
            }
        }
        if (cmd.isGroup) Flyout(open, { open = false }) {
            cmd.groupItems!!(s).forEach { m ->
                if (m.separatorBefore) FlyoutSeparator()
                FlyoutItem(m.label, m.icon, m.hotkey, enabled = m.enabled, iconTint = if (m.icon == androidx.compose.material.icons.Icons.Filled.Folder) p.folder else null) { open = false; m.action() }
            }
        }
    }
}

// ====================================================================== 自訂工具列 window

private class DragInfo(val entry: TbEntry, val fromIndex: Int?, val label: String) {
    var pos by mutableStateOf(Offset.Zero)
}

/**
 * The 自訂工具列 window (ToolbarCustomizationPage): pick a toolbar mode, drag commands from
 * 可用選項 into 新增選項 (or drag rows to reorder), set 圖示 / 文字標籤, preview, then 儲存.
 */
@Composable
fun CustomizeToolbarDialog(s: AppState) {
    val p = LocalPalette.current
    val work = remember { mutableStateMapOf<TbContext, List<TbEntry>>().apply { putAll(s.toolbarStore.layout) } }
    var changed by remember { mutableStateOf(false) }
    var ctx by remember { mutableStateOf(TbContext.AlwaysVisible) }
    var search by remember { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<TbCategory, Boolean>() }
    var drag by remember { mutableStateOf<DragInfo?>(null) }
    var listRect by remember { mutableStateOf(Rect.Zero) }
    val rowRects = remember { mutableStateMapOf<Int, Rect>() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val list = work[ctx] ?: emptyList()

    fun setList(l: List<TbEntry>) {
        // Remove stale rowRects entries that are now out of bounds.
        val newSize = l.size
        rowRects.keys.filter { it >= newSize }.forEach { rowRects.remove(it) }
        work[ctx] = l
        changed = true
    }
    fun insertIndexFor(y: Float): Int {
        val size = list.size
        val rows = rowRects.entries.filter { it.key < size }.sortedBy { it.key }
        return rows.firstOrNull { y < it.value.center.y }?.key?.coerceIn(0, size) ?: size
    }
    /** Inside the 新增選項 list, or just under its last row (= add at the end). */
    fun inList(pos: Offset) = pos.x in listRect.left..listRect.right && pos.y in listRect.top..(listRect.bottom + 48f)
    fun drop(d: DragInfo) {
        if (!inList(d.pos)) return
        val target = insertIndexFor(d.pos.y)
        val l = list.toMutableList()
        if (d.fromIndex != null) {
            val item = l.removeAt(d.fromIndex)
            l.add(if (target > d.fromIndex) target - 1 else target, item)
        } else l.add(target, d.entry)
        setList(l)
    }
    fun newEntry(id: String) = if (id == SEPARATOR) TbEntry(SEPARATOR, false, false) else TbEntry(id, TbCommands.get(id)?.icon != null, true)

    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)).onGloballyPositioned { rootOrigin = it.boundsInRoot().topLeft }
            .pointerInput(Unit) { detectTapGestures2 { s.customizeToolbarOpen = false } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.size(M.TOOLBAR_DIALOG_W.dp, M.TOOLBAR_DIALOG_H.dp).shadow8().clip(RoundedCornerShape(8.dp))
                .background(p.solid).border(1.dp, p.controlStroke, RoundedCornerShape(8.dp))
                .pointerInput(Unit) { detectTapGestures2 { } }
        ) {
            // Title bar (36)
            Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                T(S.customizeToolbar, Modifier.weight(1f), size = 12.sp)
                IconBtn(Icons.Outlined.Close, S.cancel, { s.customizeToolbarOpen = false }, width = 46.dp, height = 36.dp, iconSize = 14.dp)
            }
            // Mode picker
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                T(S.chooseToolbarMode, Modifier.weight(1f))
                var open by remember { mutableStateOf(false) }
                Box {
                    Hover(Modifier.width(240.dp).height(32.dp).border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)), onClick = { open = true }) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(ctx.text, Modifier.weight(1f))
                            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(14.dp), tint = p.textSecondary)
                        }
                    }
                    Flyout(open, { open = false }) {
                        TbContext.entries.forEach { c -> FlyoutItem(c.text, if (c == ctx) Icons.Outlined.Check else null, "") { open = false; ctx = c; rowRects.clear() } }
                    }
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                // ---- 可用選項
                Column(Modifier.width(240.dp).fillMaxHeight()) {
                    T(S.availableOptions, Modifier.padding(bottom = 6.dp), weight = FontWeight.SemiBold)
                    PlainField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = S.search)
                    Spacer(Modifier.height(6.dp))
                    val leaf: @Composable (String, String, androidx.compose.ui.graphics.vector.ImageVector?, Int) -> Unit = { id, label, icon, indent ->
                        var coords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                        Hover(
                            Modifier.fillMaxWidth().height(30.dp).onGloballyPositioned { coords = it }
                                .pointerInput(id) {
                                    detectDragGestures(
                                        onDragStart = { o -> drag = DragInfo(newEntry(id), null, label).also { d -> d.pos = (coords?.localToRoot(o) ?: o) - rootOrigin } },
                                        onDrag = { c, d -> c.consume(); drag?.let { it.pos = coords?.localToRoot(c.position)?.minus(rootOrigin) ?: (it.pos + d) } },
                                        onDragEnd = { drag?.let { drop(it) }; drag = null },
                                        onDragCancel = { drag = null },
                                    )
                                },
                            onClick = {},
                        ) {
                            Row(Modifier.fillMaxSize().padding(start = (8 + indent * 16).dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(16.dp)) { if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = p.text) }
                                Spacer(Modifier.width(8.dp))
                                T(label, Modifier.weight(1f), size = 13.sp)
                                // Convenience: the original is drag-only; "+" adds at the end.
                                IconBtn(Icons.Outlined.Add, S.add, { setList(list + newEntry(id)) }, width = 24.dp, height = 24.dp, iconSize = 12.dp)
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        val q = search.trim()
                        if (q.isNotEmpty()) {
                            // Search: flat list, case-insensitive match on the full label.
                            val hits = TbCommands.all.filter { it.listLabel.contains(q, true) }.sortedBy { it.listLabel }
                            items(hits.size) { i -> leaf(hits[i].id, hits[i].listLabel, hits[i].icon, 0) }
                        } else {
                            item { leaf(SEPARATOR, S.separator, Icons.Outlined.HorizontalRule, 0) }
                            // Categories and items in plain character order, as the original sorts them.
                            TbCategory.entries.sortedBy { it.text }.forEach { cat ->
                                val open = expanded[cat] == true
                                item(key = cat.name) {
                                    Hover(Modifier.fillMaxWidth().height(30.dp), onClick = { expanded[cat] = !open }) {
                                        Row(Modifier.fillMaxSize().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = p.textSecondary)
                                            Spacer(Modifier.width(6.dp))
                                            T(cat.text, size = 13.sp, weight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                                if (open) {
                                    val cmds = TbCommands.all.filter { it.category == cat }.sortedBy { it.listLabel }
                                    items(cmds.size) { i -> leaf(cmds[i].id, cmds[i].listLabel, cmds[i].icon, 1) }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                // ---- 新增選項
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    T(S.addedOptions, Modifier.padding(bottom = 6.dp), weight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(S.action, Modifier.weight(1f), size = 12.sp, color = p.textSecondary)
                        T(S.iconCol, Modifier.width(56.dp), size = 12.sp, color = p.textSecondary)
                        T(S.labelCol, Modifier.width(72.dp), size = 12.sp, color = p.textSecondary)
                        Spacer(Modifier.width(32.dp))
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card)
                            .border(1.dp, if (drag != null && inList(drag!!.pos)) p.accent else p.cardStroke, RoundedCornerShape(6.dp))
                            .onGloballyPositioned { listRect = it.boundsInRoot().translate(-rootOrigin) }
                    ) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            // Use the current list snapshot for each item to avoid index-capture bugs.
                            // Each lambda re-reads `work[ctx]` (via `list`) at the time it fires.
                            itemsIndexed(list, key = { idx, e -> "$idx:${e.id}" }) { i, e ->
                                val cmd = TbCommands.get(e.id)
                                val hoverSrc = remember { MutableInteractionSource() }
                                val hovered by hoverSrc.collectIsHoveredAsState()
                                var coords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                                val dropHere = drag != null && inList(drag!!.pos) && insertIndexFor(drag!!.pos.y) == i
                                Column(Modifier.fillMaxWidth().onGloballyPositioned { coords = it; rowRects[i] = it.boundsInRoot().translate(-rootOrigin) }) {
                                    if (dropHere) Box(Modifier.fillMaxWidth().height(2.dp).background(p.accent))
                                    Row(Modifier.fillMaxWidth().height(36.dp).hoverable(hoverSrc).background(if (hovered) p.hover else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                                        var grip by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                                        Box(
                                            Modifier.size(28.dp, 36.dp).onGloballyPositioned { grip = it }.pointerInput(i, e) {
                                                detectDragGestures(
                                                    onDragStart = { o -> drag = DragInfo(e, i, cmd?.listLabel ?: S.separator).also { d -> d.pos = (grip?.localToRoot(o) ?: o) - rootOrigin } },
                                                    onDrag = { c, d -> c.consume(); drag?.let { it.pos = grip?.localToRoot(c.position)?.minus(rootOrigin) ?: (it.pos + d) } },
                                                    onDragEnd = { drag?.let { drop(it) }; drag = null },
                                                    onDragCancel = { drag = null },
                                                )
                                            },
                                            contentAlignment = Alignment.Center,
                                        ) { Icon(Icons.Outlined.DragIndicator, S.dragToReorder, Modifier.size(16.dp), tint = p.textSecondary) }
                                        if (e.isSeparator) {
                                            T(S.separator, Modifier.weight(1f), color = p.textSecondary)
                                            Spacer(Modifier.width(128.dp))
                                        } else {
                                            T(cmd?.listLabel ?: e.id, Modifier.weight(1f))
                                            Box(Modifier.width(56.dp)) {
                                                // Use the captured index only if it is still in bounds.
                                                TinyCheck(e.showIcon, enabled = cmd?.icon != null, tip = S.showIconTip) { v ->
                                                    val cur = work[ctx] ?: return@TinyCheck
                                                    if (i < cur.size) setList(cur.toMutableList().also { it[i] = cur[i].copy(showIcon = v) })
                                                }
                                            }
                                            Box(Modifier.width(72.dp)) {
                                                TinyCheck(e.showLabel, enabled = true, tip = S.showLabelTip) { v ->
                                                    val cur = work[ctx] ?: return@TinyCheck
                                                    if (i < cur.size) setList(cur.toMutableList().also { it[i] = cur[i].copy(showLabel = v) })
                                                }
                                            }
                                        }
                                        Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                                            IconBtn(Icons.Outlined.Close, S.remove, {
                                                val cur = work[ctx] ?: return@IconBtn
                                                if (i < cur.size) setList(cur.filterIndexed { j, _ -> j != i })
                                            }, width = 28.dp, height = 28.dp, iconSize = 12.dp,
                                                tint = if (hovered) p.danger else p.textSecondary)
                                        }
                                    }
                                }
                            }
                            item {
                                if (drag != null && inList(drag!!.pos) && insertIndexFor(drag!!.pos.y) == list.size)
                                    Box(Modifier.fillMaxWidth().height(2.dp).background(p.accent))
                                if (list.isEmpty()) T(S.dragHere, Modifier.padding(16.dp), size = 12.sp, color = p.textTertiary, maxLines = 3)
                            }
                        }
                    }
                }
            }
            // Preview of the chosen mode's toolbar
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp).clip(RoundedCornerShape(8.dp))
                    .background(p.toolbar).border(1.dp, p.cardStroke, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                CustomToolbar(s, Modifier.fillMaxWidth(), layout = work.toMap(), contexts = listOf(ctx), interactive = false)
            }
            // Buttons
            Row(Modifier.fillMaxWidth().background(p.layer).padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                DialogButton(S.resetDefaults, primary = false) { work.clear(); work.putAll(toolbarDefaults()); changed = true }
                Spacer(Modifier.weight(1f))
                Box(Modifier.alpha(if (changed) 1f else 0.5f)) {
                    DialogButton(S.save, primary = true) { if (changed) { s.toolbarStore.save(TbContext.entries.associateWith { work[it] ?: emptyList() }); s.customizeToolbarOpen = false } }
                }
                Spacer(Modifier.width(8.dp))
                DialogButton(S.cancel, primary = false) { s.customizeToolbarOpen = false }
            }
        }
        // Dragged item follows the pointer
        drag?.let { d ->
            Box(
                Modifier.align(Alignment.TopStart).offset { IntOffset(d.pos.x.toInt() + 12, d.pos.y.toInt() - 12) }
                    .shadow8().background(p.flyout, RoundedCornerShape(6.dp)).border(1.dp, p.accent, RoundedCornerShape(6.dp)).padding(10.dp, 6.dp)
            ) { T(d.label, size = 13.sp) }
        }
    }
}

@Composable
private fun TinyCheck(on: Boolean, enabled: Boolean, tip: String, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).then(if (enabled) Modifier.clickable { onChange(!on) } else Modifier), contentAlignment = Alignment.Center) {
        Icon(
            if (on && enabled) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, tip, Modifier.size(18.dp),
            tint = if (!enabled) p.textTertiary.copy(alpha = 0.4f) else if (on) p.accent else p.textSecondary,
        )
    }
}
