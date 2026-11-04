package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Keyboard shortcuts written the way the toolbar shows them ("Ctrl+Shift+N", "Alt+Left", "F11",
 * "Ctrl++", "Ctrl+`"): one parser, so every command that names a shortcut also answers to it.
 */
object Hotkeys {
    private fun keysFor(name: String): List<Key> = when (name.lowercase()) {
        "left" -> listOf(Key.DirectionLeft); "right" -> listOf(Key.DirectionRight)
        "up" -> listOf(Key.DirectionUp); "down" -> listOf(Key.DirectionDown)
        "enter" -> listOf(Key.Enter, Key.NumPadEnter); "delete" -> listOf(Key.Delete)
        "tab" -> listOf(Key.Tab); "space" -> listOf(Key.Spacebar); "esc" -> listOf(Key.Escape)
        "backspace" -> listOf(Key.Backspace); "home" -> listOf(Key.MoveHome); "end" -> listOf(Key.MoveEnd)
        "+" -> listOf(Key.Equals, Key.Plus, Key.NumPadAdd); "-" -> listOf(Key.Minus, Key.NumPadSubtract)
        "," -> listOf(Key.Comma); "`" -> listOf(Key.Grave); "/" -> listOf(Key.Slash)
        else -> when {
            name.length == 1 && name[0].isLetter() -> listOfNotNull(letter(name[0].uppercaseChar()))
            name.length == 1 && name[0].isDigit() -> listOf(digit(name[0] - '0'))
            name.matches(Regex("(?i)f\\d{1,2}")) -> listOfNotNull(fkey(name.substring(1).toInt()))
            else -> emptyList()
        }
    }

    private fun letter(c: Char): Key? = listOf(Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M, Key.N, Key.O, Key.P,
            Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z).getOrNull(c - 'A')

    private fun digit(d: Int): Key = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)[d]
    private fun fkey(n: Int): Key? = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12).getOrNull(n - 1)

    /** True when [e] is exactly [spec] (all of its modifiers and no others). */
    fun matches(spec: String, e: KeyEvent): Boolean {
        if (spec.isBlank()) return false
        // "Ctrl++" ends in the "+" key itself.
        val parts = if (spec.endsWith("++")) spec.dropLast(2).split('+').filter { it.isNotEmpty() } + "+" else spec.split('+')
        val mods = parts.dropLast(1).map { it.lowercase() }.toSet()
        if (("ctrl" in mods) != e.isCtrlPressed || ("alt" in mods) != e.isAltPressed) return false
        val key = parts.last()
        // Shift is part of how "+" is typed on many layouts: don't insist on it there.
        if (key != "+" && ("shift" in mods) != e.isShiftPressed) return false
        return e.key in keysFor(key)
    }

    /** Runs the first toolbar command whose shortcut is [e] (when it can run now). */
    fun dispatch(s: AppState, e: KeyEvent): Boolean {
        // The person's shortcuts (捷徑) with a hotkey come first.
        FlowStore.all().firstOrNull { it.hotkey.isNotEmpty() && matches(it.hotkey, e) }?.let { s.runFlow(it); return true }
        val cmd = TbCommands.all.firstOrNull { it.hotkey.isNotEmpty() && matches(it.hotkey, e) } ?: return false
        if (!cmd.enabled(s)) return true
        if (cmd.isGroup) return false
        cmd.run(s)
        return true
    }

    /** Shortcuts that aren't toolbar commands, for the 鍵盤快速鍵 list. */
    val extra: List<Pair<String, Pair<String, String>>> = listOf(
        "Space" to ("Quick preview of the selected file" to "快速預覽所選檔案"),
        "← → ↑ ↓" to ("Move between items (in the preview: previous / next file)" to "在項目之間移動（預覽中：上一個／下一個檔案）"),
        "Shift+↑ / Shift+↓" to ("Extend the selection" to "延伸選取範圍"),
        "Home / End" to ("First / last item" to "第一個／最後一個項目"),
        "Backspace" to ("Back" to "上一頁"),
        "Alt+D" to ("Edit the path" to "編輯路徑"),
        "F3 / Ctrl+E" to ("Search" to "搜尋"),
        "Ctrl+R" to ("Refresh" to "重新整理"),
        "Ctrl+D" to ("Delete" to "刪除"),
        "Ctrl+1 … Ctrl+9" to ("Go to tab 1–9" to "切換到第 1–9 個分頁"),
        "Ctrl+Wheel" to ("Icon size" to "圖示大小"),
        "Ctrl+Z" to ("Restore (in the Recycle Bin)" to "還原（在資源回收筒中）"),
        "Esc" to ("Close the dialog or preview, or clear the selection" to "關閉對話框或預覽，或清除選取"),
        "F1 / Ctrl+/" to ("This list" to "此快速鍵清單"),
    )
}

/** 鍵盤快速鍵: every shortcut, grouped like the toolbar's command categories. */
@Composable
fun ShortcutList() {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        @Composable
        fun row(keys: String, text: String) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                T(text, Modifier.weight(1f), size = 13.sp)
                Box(Modifier.background(p.control, RoundedCornerShape(4.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    T(keys, size = 12.sp, color = p.textSecondary)
                }
            }
        }
        TbCommands.all.filter { it.hotkey.isNotEmpty() }.groupBy { it.category }.forEach { (cat, cmds) ->
            GroupTitle(cat.text)
            cmds.forEach { row(it.hotkey, it.label) }
        }
        GroupTitle(if (S.zh) "其他" else "Other")
        Hotkeys.extra.forEach { (k, t) -> row(k, if (S.zh) t.second else t.first) }
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
                IconBtn(androidx.compose.material.icons.Icons.Outlined.Close, S.close, { s.shortcutsOpen = false })
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { ShortcutList() }
        }
    }
}
