package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Where one Windows menu item goes. */
enum class ShellPlace(private val en: String, private val zh: String) {
    Top("Main menu", "主選單"), More("Show more options", "更多選項"), Hidden("Hidden", "隱藏");
    val text get() = tr(en, zh)
}

/**
 * What the Windows part of the right-click menu shows (設定 › Windows 右鍵選單): per item — main menu,
 * only under "Show more options", or hidden — plus a few switches. Items are known by their name
 * (as Windows shows it), and listed in settings once they've been seen in a menu.
 * Saved in %APPDATA%\FilesKmp\shell-menu.json.
 */
object ShellMenuPrefs {
    private val file get() = File(Prefs.dir, "shell-menu.json")

    class Seen(val label: String, val defaultPlace: ShellPlace)

    /** The person's choices: item key → place. */
    var places by mutableStateOf<Map<String, ShellPlace>>(emptyMap()); private set
    /** Items seen in menus: key → name and where Windows puts it. */
    var seen by mutableStateOf<Map<String, Seen>>(emptyMap()); private set
    var showMore by mutableStateOf(true); private set
    var hideDuplicates by mutableStateOf(true); private set
    var showIcons by mutableStateOf(true); private set

    /** Verbs the app's own menu already has (hidden from the main menu when [hideDuplicates]). */
    val duplicateVerbs = setOf("open", "cut", "copy", "paste", "delete", "rename", "properties", "copyaspath", "pintohome", "pintohomefile")

    init { load() }

    /** The key an item is remembered by: its name without "&", "(H)" and case. */
    fun key(label: String) = label.lowercase().trim()

    fun place(label: String, default: ShellPlace): ShellPlace = places[key(label)] ?: default

    fun set(label: String, p: ShellPlace) {
        val k = key(label)
        val def = seen[k]?.defaultPlace
        places = if (p == def) places - k else places + (k to p)
        save()
    }
    fun changeShowMore(on: Boolean) { showMore = on; save() }
    fun changeHideDuplicates(on: Boolean) { hideDuplicates = on; save() }
    fun changeShowIcons(on: Boolean) { showIcons = on; save() }
    fun resetAll() { places = emptyMap(); showMore = true; hideDuplicates = true; showIcons = true; save() }

    /** Remembers the items of a menu that was just read (for the settings list). */
    fun noteSeen(top: List<ShellItem>, more: List<ShellItem>) {
        val m = seen.toMutableMap(); var changed = false
        fun add(i: ShellItem, p: ShellPlace) {
            if (i.separator || i.label.isBlank()) return
            val k = key(i.label)
            if (k !in m || (p == ShellPlace.Top && m[k]!!.defaultPlace != ShellPlace.Top)) { m[k] = Seen(i.label, p); changed = true }
        }
        top.forEach { add(it, ShellPlace.Top) }
        more.forEach { add(it, ShellPlace.More) }
        if (changed) {
            // Keep the list a reasonable size: the most recent 400 names.
            seen = if (m.size > 400) m.entries.toList().takeLast(400).associate { it.key to it.value } else m
            save()
        }
    }

    /**
     * The menu as the person wants it: [top] and [more] from Windows, rearranged by their choices.
     * Returns (main menu items, items under "Show more options").
     */
    fun arrange(top: List<ShellItem>, more: List<ShellItem>): Pair<List<ShellItem>, List<ShellItem>> {
        fun dup(i: ShellItem) = hideDuplicates && i.verb?.lowercase() in duplicateVerbs
        val main = ArrayList<ShellItem>(); val rest = ArrayList<ShellItem>()
        top.forEach { i ->
            if (i.separator) return@forEach
            when (place(i.label, ShellPlace.Top)) {
                ShellPlace.Top -> if (!dup(i)) main += i
                ShellPlace.More -> if (more.none { key(it.label) == key(i.label) }) rest += i
                ShellPlace.Hidden -> {}
            }
        }
        more.forEach { i ->
            if (i.separator) { rest += i; return@forEach }
            when (place(i.label, ShellPlace.More)) {
                ShellPlace.Top -> { if (main.none { key(it.label) == key(i.label) }) main += i; rest += i }
                ShellPlace.More -> rest += i
                ShellPlace.Hidden -> {}
            }
        }
        // No separators at the ends or twice in a row.
        val cleaned = rest.filterIndexed { idx, i -> !i.separator || (idx > 0 && idx < rest.size - 1 && !rest[idx - 1].separator) }
        return main to (if (showMore) cleaned else emptyList())
    }

    private fun load() = runCatching {
        if (!file.exists()) return@runCatching
        val m = Json.parse(file.readText(Charsets.UTF_8)) as Map<*, *>
        showMore = m["showMore"] as? Boolean ?: true
        hideDuplicates = m["hideDuplicates"] as? Boolean ?: true
        showIcons = m["showIcons"] as? Boolean ?: true
        places = (m["places"] as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) ->
            ShellPlace.entries.firstOrNull { it.name == v }?.let { k.toString() to it }
        }.toMap()
        seen = (m["seen"] as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) ->
            val x = v as? Map<*, *> ?: return@mapNotNull null
            k.toString() to Seen(x["label"]?.toString() ?: k.toString(), ShellPlace.entries.firstOrNull { it.name == x["default"] } ?: ShellPlace.More)
        }.toMap()
    }

    private fun save() {
        if (Prefs.readOnly) return
        runCatching {
            file.writeText(Json.write(linkedMapOf(
                "showMore" to showMore, "hideDuplicates" to hideDuplicates, "showIcons" to showIcons,
                "places" to places.mapValues { it.value.name }.toSortedMap(),
                "seen" to seen.mapValues { linkedMapOf("label" to it.value.label, "default" to it.value.defaultPlace.name) },
            )), Charsets.UTF_8)
        }
    }

    /** Tests: start empty, without the file. */
    internal fun testReset() { places = emptyMap(); seen = emptyMap(); showMore = true; hideDuplicates = true; showIcons = true }
}

/** 設定 › 檔案和資料夾 › Windows 右鍵選單: which menu, and what it shows. */
@Composable
fun ShellMenuSettings(s: AppState) {
    val p = LocalPalette.current
    var filter by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    GroupTitle(tr("Windows right-click menu", "Windows 右鍵選單"))
    ChoiceCard(tr("Which menu", "使用的選單"), ShellMenuMode.entries.map { it to it.text }, s.shellMenuMode) { s.shellMenuMode = it }
    if (s.shellMenuMode == ShellMenuMode.Off) return
    ToggleCard(tr("Show “Show more options” (the full classic menu)", "顯示「顯示更多選項」（完整的舊版選單）"), ShellMenuPrefs.showMore) { ShellMenuPrefs.changeShowMore(it) }
    ToggleCard(tr("Hide Windows items this app's menu already has (Open, Copy, Delete, Properties…)", "隱藏與本程式選單重複的項目（開啟、複製、刪除、內容…）"), ShellMenuPrefs.hideDuplicates) { ShellMenuPrefs.changeHideDuplicates(it) }
    ToggleCard(tr("Show the items' icons", "顯示項目圖示"), ShellMenuPrefs.showIcons) { ShellMenuPrefs.changeShowIcons(it) }
    T(tr("Where each item goes. Items appear here once you've seen them in a right-click menu.", "每個項目放在哪裡。在右鍵選單中出現過的項目會列在這裡。"),
        Modifier.padding(start = 2.dp, top = 8.dp), size = 12.sp, color = p.textSecondary, maxLines = 2)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlainField(filter, { filter = it }, Modifier.weight(1f), placeholder = tr("Search items", "搜尋項目"))
        DialogButton(if (confirmReset) tr("Reset? Click again", "確定恢復預設？再按一次") else tr("Reset", "恢復預設"), primary = confirmReset) {
            if (confirmReset) { ShellMenuPrefs.resetAll(); confirmReset = false } else confirmReset = true
        }
    }
    val items = ShellMenuPrefs.seen.values.filter { filter.isBlank() || it.label.contains(filter.trim(), true) }.sortedBy { it.label.lowercase() }
    if (items.isEmpty()) T(if (ShellMenuPrefs.seen.isEmpty()) tr("Right-click a file to see its Windows menu items here.", "在檔案上按右鍵後，這裡就會列出 Windows 選單的項目。") else tr("Nothing matches.", "沒有符合的項目。"),
        Modifier.padding(8.dp), size = 13.sp, color = p.textSecondary, maxLines = 2)
    items.forEach { item ->
        val cur = ShellMenuPrefs.place(item.label, item.defaultPlace)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.card).border(1.dp, p.cardStroke, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(item.label, size = 13.sp)
                if (cur != item.defaultPlace) T(tr("Changed · Windows: ", "已自訂 · Windows 預設：") + item.defaultPlace.text, size = 11.sp, color = p.textTertiary)
            }
            Row(Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(4.dp))) {
                ShellPlace.entries.forEach { pl ->
                    T(pl.text, Modifier.background(if (cur == pl) p.accent.copy(alpha = 0.18f) else p.control).clickable { ShellMenuPrefs.set(item.label, pl) }
                        .padding(horizontal = 10.dp, vertical = 5.dp), size = 12.sp, color = if (cur == pl) p.accentText else p.text)
                }
            }
        }
    }
}
