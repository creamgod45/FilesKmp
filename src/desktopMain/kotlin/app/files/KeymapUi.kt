package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Keys that move around the file list: fixed (Hotkeys.isReserved), listed so people know they exist. */
private val fixedKeys: List<Pair<String, Pair<String, String>>> = listOf(
    "← → ↑ ↓" to ("Move between items (in the preview: previous / next file)" to "在項目之間移動（預覽中：上一個／下一個檔案）"),
    "Shift+↑ / Shift+↓" to ("Extend the selection" to "延伸選取範圍"),
    "Home / End" to ("First / last item" to "第一個／最後一個項目"),
    "Esc" to ("Close the dialog or preview, or clear the selection" to "關閉對話框或預覽，或清除選取"),
    "Ctrl+Wheel" to ("Icon size" to "圖示大小"),
)

/** A key as a small key-cap; [onRemove] adds a ×, [conflict] paints it red. */
@Composable
fun KeyCap(key: String, conflict: Boolean = false, accent: Boolean = false, onRemove: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val border = when { conflict -> p.danger; accent -> p.accent; else -> p.controlStroke }
    Row(
        Modifier.height(24.dp).background(if (conflict) p.danger.copy(alpha = 0.10f) else p.control, RoundedCornerShape(4.dp))
            .border(1.dp, border, RoundedCornerShape(4.dp)).padding(start = 8.dp, end = if (onRemove != null) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(key, size = 12.sp, color = if (conflict) p.danger else p.text)
        if (onRemove != null) {
            Box(Modifier.padding(start = 4.dp).size(18.dp).clip(RoundedCornerShape(3.dp)).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, tr("Remove this key", "移除這個按鍵"), Modifier.size(11.dp), tint = p.textSecondary)
            }
        }
    }
}

@Composable
private fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) p.accent.copy(alpha = 0.16f) else p.control)
            .border(1.dp, if (on) p.accent else p.controlStroke, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, size = 12.sp, color = if (on) p.accentText else p.text) }
}

/** A text button that looks like a link (for small fixes next to a warning). */
@Composable
fun LinkText(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    T(text, Modifier.clip(RoundedCornerShape(3.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp), size = 12.sp, color = p.accentText)
}

/**
 * 鍵盤快速鍵 (settings): every command with its keys. Add a key by pressing it, remove one with its ×,
 * clear all of a command's keys, or go back to the defaults. Keys given to two commands are marked,
 * with a one-click fix.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeymapEditor(s: AppState) {
    val p = LocalPalette.current
    var filter by remember { mutableStateOf(s.keymapFilter) }
    var mode by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { s.keymapFilter = "" }
    DisposableEffect(Unit) { onDispose { s.keyCapture = null } }
    @Suppress("UNUSED_VARIABLE") val watch = Keymap.overrides   // recompose when keys change
    val acts = Keymap.actions()
    val conflicts = Keymap.conflicts(acts).associateBy { it.key }

    fun record(a: KeyAction) {
        recording = a.id; notice = null
        s.keyCapture = { spec ->
            recording = null
            when {
                spec == null -> {}
                Hotkeys.isReserved(spec) -> notice = tr("$spec moves around the file list and can't be given to a command.", "$spec 用於在檔案清單中移動，不能指定給指令。")
                else -> {
                    val others = Keymap.actionsFor(spec, acts).filter { it.id != a.id }
                    Keymap.add(a, spec)
                    notice = if (others.isEmpty()) null else tr("$spec is also given to ${others.joinToString { "“${it.label}”" }}.", "$spec 也指定給 ${others.joinToString("、") { "「${it.label}」" }}。")
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(tr("Click + and press the keys you want. A command can have several keys, or none.",
            "按 + 然後直接按下想要的按鍵。一個指令可以有多個按鍵，也可以都不指定。"), size = 12.sp, color = p.textSecondary, maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlainField(filter, { filter = it }, Modifier.weight(1f), placeholder = tr("Search commands or keys (e.g. Ctrl+K)", "搜尋指令或按鍵（例如 Ctrl+K）"))
            DialogButton(if (confirmReset) tr("Reset all? Click again", "確定全部重設？再按一次") else tr("Reset all", "全部重設"), primary = confirmReset) {
                if (confirmReset) { Keymap.resetAll(); confirmReset = false; notice = tr("All keys are back to their defaults.", "所有按鍵已恢復預設。") } else confirmReset = true
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(tr("All", "全部"), mode == 0) { mode = 0 }
            Pill(tr("Changed by you", "已自訂") + " (${acts.count { Keymap.isModified(it) }})", mode == 1) { mode = 1 }
            Pill(tr("Conflicts", "衝突") + " (${conflicts.size})", mode == 2) { mode = 2 }
            Pill(tr("No key", "未指定"), mode == 3) { mode = 3 }
        }
        notice?.let { n ->
            Row(Modifier.fillMaxWidth().background(p.accent.copy(alpha = 0.10f), RoundedCornerShape(6.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = p.accentText); Spacer(Modifier.width(8.dp))
                T(n, Modifier.weight(1f), size = 12.sp, maxLines = 3)
                IconBtn(Icons.Outlined.Close, S.close, { notice = null }, width = 24.dp, height = 24.dp, iconSize = 12.dp)
            }
        }
        val q = filter.trim()
        val nq = Hotkeys.normalize(q)
        val shown = acts.filter { a ->
            val keys = Keymap.bindings(a)
            (q.isEmpty() || a.label.contains(q, true) || a.id.contains(q, true) || a.category.contains(q, true) ||
                (nq != null && nq in keys) || keys.any { it.contains(q, true) }) &&
                when (mode) { 1 -> Keymap.isModified(a); 2 -> keys.any { it in conflicts }; 3 -> keys.isEmpty(); else -> true }
        }
        if (shown.isEmpty()) T(tr("Nothing matches.", "沒有符合的指令。"), Modifier.padding(8.dp), color = p.textSecondary)
        shown.groupBy { it.category }.forEach { (cat, list) ->
            GroupTitle(cat)
            list.forEach { a -> KeyRow(a, conflicts, recording == a.id, onRecord = { record(a) }, onCancel = { recording = null; s.keyCapture = null }) }
        }
        if (mode == 0 && q.isEmpty()) {
            GroupTitle(tr("Fixed keys", "固定按鍵"))
            fixedKeys.forEach { (k, t) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(if (S.zh) t.second else t.first, Modifier.weight(1f), size = 13.sp, color = p.textSecondary); KeyCap(k)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyRow(a: KeyAction, conflicts: Map<String, KeyConflict>, recording: Boolean, onRecord: () -> Unit, onCancel: () -> Unit) {
    val p = LocalPalette.current
    val keys = Keymap.bindings(a)
    val modified = Keymap.isModified(a)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).border(1.dp, if (keys.any { it in conflicts }) p.danger.copy(alpha = 0.5f) else p.cardStroke, RoundedCornerShape(6.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                T(a.label, size = 13.sp)
                if (modified) T(tr("Changed · default: ", "已自訂 · 預設：") + (a.defaults.joinToString(" / ").ifEmpty { tr("none", "無") }), size = 11.sp, color = p.textTertiary)
            }
            FlowRow(Modifier.widthIn(max = 300.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                keys.forEach { k -> KeyCap(k, conflict = k in conflicts, onRemove = { Keymap.remove(a, k) }) }
                if (recording) Box(Modifier.clickable(onClick = onCancel)) { KeyCap(tr("Press keys… (Esc cancels)", "請按下按鍵…（Esc 取消）"), accent = true) }
                else if (keys.isEmpty()) T(tr("No key", "未指定"), Modifier.padding(horizontal = 4.dp, vertical = 3.dp), size = 12.sp, color = p.textTertiary)
            }
            IconBtn(Icons.Outlined.Add, tr("Add a key", "新增按鍵"), onRecord, width = 30.dp, height = 30.dp, iconSize = 14.dp)
            IconBtn(Icons.Outlined.Undo, tr("Back to default", "重設為預設"), { Keymap.reset(a) }, enabled = modified, width = 30.dp, height = 30.dp, iconSize = 14.dp)
            IconBtn(Icons.Outlined.ClearAll, tr("Clear all keys", "清除所有按鍵"), { Keymap.clear(a) }, enabled = keys.isNotEmpty(), width = 30.dp, height = 30.dp, iconSize = 14.dp)
        }
        keys.forEach { k ->
            val c = conflicts[k] ?: return@forEach
            val others = c.actions.filter { it.id != a.id }
            val winner = c.actions.first()
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, null, Modifier.size(14.dp), tint = p.danger); Spacer(Modifier.width(6.dp))
                T(tr("$k is also given to ", "$k 也指定給 ") + others.joinToString(if (S.zh) "、" else ", ") { if (S.zh) "「${it.label}」" else "“${it.label}”" } +
                    (if (winner.id == a.id) tr(" — this one runs.", "，目前執行這一個。") else tr(" — “${winner.label}” runs.", "，目前執行「${winner.label}」。")),
                    Modifier.weight(1f, fill = false), size = 12.sp, color = p.danger, maxLines = 2)
                others.forEach { o -> LinkText(tr("Remove from “${o.label}”", "從「${o.label}」移除")) { Keymap.remove(o, k) } }
                LinkText(tr("Remove here", "從這裡移除")) { Keymap.remove(a, k) }
            }
        }
    }
}

/** 鍵盤快速鍵 list (F1): the keys as they are now, grouped like the editor. */
@Composable
fun ShortcutList() {
    val p = LocalPalette.current
    @Suppress("UNUSED_VARIABLE") val watch = Keymap.overrides
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Keymap.actions().filter { Keymap.bindings(it).isNotEmpty() }.groupBy { it.category }.forEach { (cat, list) ->
            GroupTitle(cat)
            list.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(a.label, Modifier.weight(1f), size = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Keymap.bindings(a).forEach { KeyCap(it) } }
                }
            }
        }
        GroupTitle(tr("Fixed keys", "固定按鍵"))
        fixedKeys.forEach { (k, t) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                T(if (S.zh) t.second else t.first, Modifier.weight(1f), size = 13.sp, color = p.textSecondary); KeyCap(k)
            }
        }
    }
}

@Composable
fun ShortcutsDialog(s: AppState) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { s.shortcutsOpen = false } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).fillMaxHeight(0.86f).softShadow(shape, 18.dp).background(p.solid, shape).border(1.dp, p.controlStroke, shape)
                .pointerInput(Unit) { detectTapGestures2 { } }.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(S.shortcuts, Modifier.weight(1f), size = 20.sp, weight = FontWeight.SemiBold)
                DialogButton(tr("Customize…", "自訂…"), primary = false) { s.shortcutsOpen = false; s.openKeymap() }
                Spacer(Modifier.width(4.dp))
                IconBtn(Icons.Outlined.Close, S.close, { s.shortcutsOpen = false })
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { ShortcutList() }
        }
    }
}
