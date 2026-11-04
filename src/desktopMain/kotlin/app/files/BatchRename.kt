package app.files

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which part of a name a rule changes. */
enum class RenameTarget { Name, Extension, Both;
    val text get() = when (this) { Name -> if (S.zh) "主檔名" else "Name"; Extension -> if (S.zh) "副檔名" else "Extension"; Both -> if (S.zh) "完整名稱" else "Whole name" }
}

enum class CaseMode { Lower, Upper, Title, Sentence, Camel, Pascal, Snake, Kebab;
    val text get() = when (this) {
        Lower -> if (S.zh) "全部小寫" else "lowercase"; Upper -> if (S.zh) "全部大寫" else "UPPERCASE"
        Title -> if (S.zh) "每字首大寫" else "Title Case"; Sentence -> if (S.zh) "句首大寫" else "Sentence case"
        Camel -> "camelCase"; Pascal -> "PascalCase"; Snake -> "snake_case"; Kebab -> "kebab-case"
    }
}

/** One step of a batch rename; steps run top to bottom, each on the previous step's result. */
sealed class RenameRule {
    abstract val enabled: Boolean
    abstract fun with(enabled: Boolean): RenameRule
    data class Replace(val find: String = "", val replace: String = "", val regex: Boolean = false, val matchCase: Boolean = false,
                       val target: RenameTarget = RenameTarget.Name, override val enabled: Boolean = true) : RenameRule() { override fun with(enabled: Boolean) = copy(enabled = enabled) }
    data class ChangeCase(val mode: CaseMode = CaseMode.Lower, val target: RenameTarget = RenameTarget.Name, override val enabled: Boolean = true) : RenameRule() { override fun with(enabled: Boolean) = copy(enabled = enabled) }
    /** [at] = -1 start, -2 end, else the character position. [text] may hold tokens. */
    data class Insert(val text: String = "", val at: Int = -2, override val enabled: Boolean = true) : RenameRule() { override fun with(enabled: Boolean) = copy(enabled = enabled) }
    data class Remove(val fromStart: Int = 0, val fromEnd: Int = 0, override val enabled: Boolean = true) : RenameRule() { override fun with(enabled: Boolean) = copy(enabled = enabled) }
    /** The whole name from a pattern such as "{taken:yyyy-MM-dd}_{n:000}" (tokens, see [RenameEngine.tokens]). */
    data class Template(val pattern: String = "{name}_{n:000}", override val enabled: Boolean = true) : RenameRule() { override fun with(enabled: Boolean) = copy(enabled = enabled) }
    data class Clean(val collapseSpaces: Boolean = true, val trim: Boolean = true, val spacesTo: String = "", override val enabled: Boolean = true) : RenameRule() { override fun with(enabled: Boolean) = copy(enabled = enabled) }
}

class RenameRow(val item: FileItem, val newName: String, val problem: String?) {
    val changed get() = newName != item.name
}

object RenameEngine {
    val tokens = listOf("{name}", "{ext}", "{n}", "{n:000}", "{parent}", "{date:yyyyMMdd}", "{modified:yyyy-MM-dd}", "{created:yyyy-MM-dd}",
        "{taken:yyyy-MM-dd_HHmmss}", "{size}", "{width}", "{height}", "{camera}", "{artist}", "{album}", "{title}", "{track:00}", "{duration}", "{field:欄位}", "{tags}")

    private val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }

    fun split(name: String, isDir: Boolean): Pair<String, String> =
        if (isDir || !name.contains('.') || name.startsWith(".") && name.count { it == '.' } == 1) name to ""
        else name.substringBeforeLast('.') to name.substringAfterLast('.')

    /** New names for [items] in this order; problems are filled in for every row. */
    fun preview(s: AppState, items: List<FileItem>, rules: List<RenameRule>, start: Int, step: Int): List<RenameRow> {
        val names = items.mapIndexed { i, it -> apply(s, it, rules, start + i * step) }
        val inBatch = items.map { it.path.lowercase() }.toSet()
        val counts = HashMap<String, Int>()
        items.forEachIndexed { i, it -> val k = (Fs.parent(it.path) ?: "") .lowercase() + "\\" + names[i].lowercase(); counts[k] = (counts[k] ?: 0) + 1 }
        return items.mapIndexed { i, it ->
            val n = names[i]
            val target = File(Fs.parent(it.path) ?: "", n)
            val key = (Fs.parent(it.path) ?: "").lowercase() + "\\" + n.lowercase()
            val problem = when {
                // An item that keeps its name is never a problem, whatever that name is.
                n == it.name -> null
                n.isBlank() -> if (S.zh) "名稱是空的" else "Empty name"
                n.any { c -> c in "<>:\"/\\|?*" || c.code < 32 } -> if (S.zh) "含有不允許的字元" else "Contains characters Windows doesn't allow"
                n.endsWith(".") || n.endsWith(" ") -> if (S.zh) "結尾不能是句點或空白" else "Can't end with a dot or space"
                n.substringBefore('.').uppercase() in reserved -> if (S.zh) "Windows 保留名稱" else "Reserved by Windows"
                n.length > 255 -> if (S.zh) "名稱太長" else "Name too long"
                (counts[key] ?: 0) > 1 -> if (S.zh) "與另一個新名稱重複" else "Same as another new name"
                n != it.name && !n.equals(it.name, true) && target.exists() && target.path.lowercase() !in inBatch -> if (S.zh) "已有同名項目" else "An item with this name exists"
                else -> null
            }
            RenameRow(it, n, problem)
        }
    }

    fun apply(s: AppState, item: FileItem, rules: List<RenameRule>, counter: Int): String {
        var (stem, ext) = split(item.name, item.isDir)
        fun onTarget(t: RenameTarget, f: (String) -> String) {
            when (t) {
                RenameTarget.Name -> stem = f(stem)
                RenameTarget.Extension -> ext = f(ext)
                RenameTarget.Both -> { val whole = f(if (ext.isEmpty()) stem else "$stem.$ext"); val sp = split(whole, item.isDir); stem = sp.first; ext = sp.second }
            }
        }
        for (r in rules) {
            if (!r.enabled) continue
            when (r) {
                is RenameRule.Replace -> if (r.find.isNotEmpty()) onTarget(r.target) { v ->
                    if (r.regex) runCatching { Regex(r.find, if (r.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)).replace(v, expand(s, item, r.replace, counter, stem, ext)) }.getOrDefault(v)
                    else v.replace(r.find, expand(s, item, r.replace, counter, stem, ext), ignoreCase = !r.matchCase)
                }
                is RenameRule.ChangeCase -> onTarget(r.target) { changeCase(it, r.mode) }
                is RenameRule.Insert -> {
                    val t = expand(s, item, r.text, counter, stem, ext)
                    stem = when (r.at) { -1 -> t + stem; -2 -> stem + t; else -> stem.substring(0, r.at.coerceIn(0, stem.length)) + t + stem.substring(r.at.coerceIn(0, stem.length)) }
                }
                is RenameRule.Remove -> {
                    val a = r.fromStart.coerceIn(0, stem.length)
                    val b = r.fromEnd.coerceIn(0, stem.length - a)
                    stem = stem.substring(a, stem.length - b)
                }
                is RenameRule.Template -> stem = expand(s, item, r.pattern, counter, stem, ext)
                is RenameRule.Clean -> {
                    var v = stem
                    if (r.spacesTo.isNotEmpty()) v = v.replace(" ", r.spacesTo)
                    if (r.collapseSpaces) v = v.replace(Regex("\\s{2,}"), " ")
                    if (r.trim) v = v.trim().trimEnd('.')
                    stem = v
                }
            }
        }
        return if (ext.isEmpty()) stem else "$stem.$ext"
    }

    private fun words(v: String): List<String> =
        v.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").split(Regex("[\\s_\\-.]+")).filter { it.isNotEmpty() }

    fun changeCase(v: String, mode: CaseMode): String = when (mode) {
        CaseMode.Lower -> v.lowercase()
        CaseMode.Upper -> v.uppercase()
        CaseMode.Title -> v.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } }
        CaseMode.Sentence -> v.lowercase().replaceFirstChar { it.titlecase() }
        CaseMode.Camel -> words(v).mapIndexed { i, w -> if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar { it.titlecase() } }.joinToString("")
        CaseMode.Pascal -> words(v).joinToString("") { w -> w.lowercase().replaceFirstChar { it.titlecase() } }
        CaseMode.Snake -> words(v).joinToString("_") { it.lowercase() }
        CaseMode.Kebab -> words(v).joinToString("-") { it.lowercase() }
    }

    private val tokenRx = Regex("\\{([a-zA-Z]+)(?::([^}]*))?}")

    /** Fills in {tokens}; file metadata comes from the Windows property system (FileProps). */
    fun expand(s: AppState, item: FileItem, text: String, counter: Int, stem: String, ext: String): String = tokenRx.replace(text) { m ->
        val key = m.groupValues[1].lowercase()
        val arg = m.groupValues[2]
        fun date(ms: Long) = if (ms <= 0) "" else LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
            .format(runCatching { DateTimeFormatter.ofPattern(arg.ifEmpty { "yyyy-MM-dd" }) }.getOrDefault(DateTimeFormatter.ISO_LOCAL_DATE))
        fun shell(c: DetailCol) = FileProps.of(item.path)?.shell?.get(c) ?: ""
        when (key) {
            "name" -> stem
            "ext" -> ext
            "n" -> if (arg.isNotEmpty() && arg.all { it == '0' }) counter.toString().padStart(arg.length, '0') else counter.toString()
            "parent" -> File(Fs.parent(item.path) ?: "").name
            "date" -> date(System.currentTimeMillis())
            "modified" -> date(item.modified)
            "created" -> date(item.created)
            "taken" -> shell(DetailCol.DateTaken).let { v ->
                // Stored as yyyy-MM-dd HH:mm:ss (FileProps); re-format, or fall back to the modified date.
                runCatching { LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .format(DateTimeFormatter.ofPattern(arg.ifEmpty { "yyyy-MM-dd" })) }.getOrNull() ?: date(item.modified)
            }
            "size" -> Fs.sizeText(item.size).replace(" ", "")
            "width" -> shell(DetailCol.Dimensions).substringBefore('x').trim()
            "height" -> shell(DetailCol.Dimensions).substringAfter('x', "").trim()
            "camera" -> shell(DetailCol.CameraModel)
            "artist" -> shell(DetailCol.Artist)
            "album" -> shell(DetailCol.Album)
            "title" -> shell(DetailCol.Title)
            "track" -> shell(DetailCol.Track).let { v -> if (arg.isNotEmpty() && v.toIntOrNull() != null) v.padStart(arg.length, '0') else v }
            "duration" -> shell(DetailCol.Duration).replace(':', '.')
            "field" -> MetaStore.get(item.path, arg)
            "tags" -> s.tagStore.tagsOf(item.path).joinToString(" ")
            else -> m.value
        }.replace(Regex("[<>:\"/\\\\|?*]"), "_")
    }

    /**
     * Renames in two passes (each item to a temporary name, then to its new one), so swaps like
     * a→b, b→a work; on a failure, everything done so far is put back. Returns (old, new) pairs.
     */
    fun perform(rows: List<RenameRow>): Pair<List<Pair<String, String>>, String?> {
        val todo = rows.filter { it.changed && it.problem == null }
        val temps = ArrayList<Triple<File, File, File>>()   // original, temp, final
        try {
            for (r in todo) {
                val src = File(r.item.path)
                val tmp = File(src.parentFile, "~fkrn-" + java.util.UUID.randomUUID().toString().take(8) + "-" + src.name.take(40))
                if (!src.renameTo(tmp)) throw IllegalStateException((if (S.zh) "無法重新命名：" else "Couldn't rename: ") + src.name)
                temps += Triple(src, tmp, File(src.parentFile, r.newName))
            }
            val done = ArrayList<Pair<String, String>>()
            for ((src, tmp, dst) in temps) {
                if (!tmp.renameTo(dst)) throw IllegalStateException((if (S.zh) "無法命名為：" else "Couldn't rename to: ") + dst.name)
                done += src.path to dst.path
            }
            return done to null
        } catch (e: Exception) {
            // Put back what was moved: finals first, then temps.
            for ((src, tmp, dst) in temps.reversed()) {
                if (dst.exists() && !tmp.exists() && !src.exists()) dst.renameTo(src)
                if (tmp.exists()) tmp.renameTo(src)
            }
            return emptyList<Pair<String, String>>() to e.message
        }
    }
}

/**
 * 批次重新命名: a list of rules (replace with text or regex, change case, insert, remove, a
 * naming template with file metadata, clean up), a live preview with every problem marked, and
 * undo for the last batch.
 */
@Composable
fun BatchRenameDialog(s: AppState, paths: List<String>) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val items = remember(paths) { val order = s.visibleItems(s.tab).associateBy { it.path }; paths.mapNotNull { order[it] ?: FsExtra.item(it) } }
    val rules = remember { mutableStateListOf<RenameRule>(RenameRule.Replace()) }
    var start by remember { mutableStateOf(1) }
    var step by remember { mutableStateOf(1) }
    var metaVersion by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Metadata tokens ({taken}, {artist}, …) need the Windows properties of these files.
    LaunchedEffect(paths) {
        withContext(Dispatchers.IO) {
            items.groupBy { Fs.parent(it.path) }.forEach { (dir, list) ->
                FileProps.load(dir, list, DetailCol.entries.filter { it.shell != null }) { false }
            }
        }
        metaVersion++
    }
    val rows = remember(rules.toList(), start, step, metaVersion, items) { RenameEngine.preview(s, items, rules.toList(), start, step) }
    val changed = rows.count { it.changed && it.problem == null }
    val problems = rows.count { it.problem != null }
    val close = { s.batchRenameFor = null }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { close() } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.88f).widthIn(max = 1180.dp).softShadow(shape, 18.dp).background(p.solid, shape)
                .border(1.dp, p.controlStroke, shape).pointerInput(Unit) { detectTapGestures2 { } }.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(S.batchRename + "  ·  " + S.items(items.size), Modifier.weight(1f), size = 20.sp, weight = FontWeight.SemiBold)
                IconBtn(Icons.Outlined.Close, S.close, close)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.weight(1f)) {
                // Rules
                Column(Modifier.width(430.dp).fillMaxHeight()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rules.forEachIndexed { i, r ->
                            RuleCard(r, i, rules.size, onChange = { rules[i] = it }, onMove = { d -> val j = i + d; if (j in rules.indices) { val x = rules[i]; rules[i] = rules[j]; rules[j] = x } },
                                onDelete = { rules.removeAt(i) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    AddRuleMenu { rules.add(it) }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        T(S.counterStart, size = 12.sp, color = p.textSecondary)
                        PlainField(start.toString(), { v -> v.toIntOrNull()?.let { start = it } }, Modifier.width(64.dp))
                        T(S.counterStep, size = 12.sp, color = p.textSecondary)
                        PlainField(step.toString(), { v -> v.toIntOrNull()?.let { step = it } }, Modifier.width(56.dp))
                    }
                    T(S.renameTokens + " " + RenameEngine.tokens.joinToString(" "), Modifier.padding(top = 6.dp), size = 11.sp, color = p.textTertiary, maxLines = 4)
                }
                Spacer(Modifier.width(16.dp))
                // Preview
                Column(Modifier.weight(1f).fillMaxHeight().border(1.dp, p.controlStroke, RoundedCornerShape(6.dp))) {
                    Row(Modifier.fillMaxWidth().background(p.control).padding(10.dp, 8.dp)) {
                        T(S.renameOld, Modifier.weight(1f), size = 12.sp, color = p.textSecondary)
                        T(S.renameNew, Modifier.weight(1f), size = 12.sp, color = p.textSecondary)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(rows, key = { _, r -> r.item.path }) { _, r ->
                            Row(Modifier.fillMaxWidth().background(if (r.problem != null) p.danger.copy(alpha = 0.08f) else Color.Transparent).padding(10.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    FileIcon(r.item, 16.dp); Spacer(Modifier.width(6.dp))
                                    T(r.item.name, size = 13.sp, color = p.textSecondary)
                                }
                                Column(Modifier.weight(1f)) {
                                    T(r.newName, size = 13.sp, weight = if (r.changed) FontWeight.SemiBold else FontWeight.Normal,
                                        color = when { r.problem != null -> p.danger; r.changed -> p.accentText; else -> p.textTertiary })
                                    r.problem?.let { T(it, size = 11.sp, color = p.danger) }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T(S.renameSummary(changed, problems) + (message?.let { "  ·  $it" } ?: ""), Modifier.weight(1f), size = 12.sp,
                    color = if (problems > 0) p.danger else p.textSecondary, maxLines = 2)
                if (s.lastRename.isNotEmpty()) DialogButton(S.undoRename, primary = false) {
                    if (!busy) { busy = true; scope.launch { message = s.undoLastRename(); busy = false; close() } }
                }
                DialogButton(S.renameApply, primary = true) {
                    if (busy || changed == 0 || problems > 0) return@DialogButton
                    busy = true
                    scope.launch { message = s.performRename(rows); busy = false; if (message == null) close() }
                }
                DialogButton(S.close, primary = false) { close() }
            }
        }
    }
}

@Composable
private fun AddRuleMenu(add: (RenameRule) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        DialogButton("+ " + S.addRenameRule, primary = false) { open = true }
        Flyout(open, { open = false }) {
            FlyoutItem(S.ruleReplace, Icons.Outlined.FindReplace, "") { open = false; add(RenameRule.Replace()) }
            FlyoutItem(S.ruleRegex, Icons.Outlined.Code, "") { open = false; add(RenameRule.Replace(regex = true)) }
            FlyoutItem(S.ruleCase, Icons.Outlined.TextFields, "") { open = false; add(RenameRule.ChangeCase()) }
            FlyoutItem(S.ruleInsert, Icons.Outlined.Add, "") { open = false; add(RenameRule.Insert()) }
            FlyoutItem(S.ruleRemove, Icons.Outlined.ContentCut, "") { open = false; add(RenameRule.Remove()) }
            FlyoutItem(S.ruleTemplate, Icons.Outlined.DataObject, "") { open = false; add(RenameRule.Template()) }
            FlyoutItem(S.ruleClean, Icons.Outlined.CleaningServices, "") { open = false; add(RenameRule.Clean()) }
        }
    }
}

@Composable
private fun <E> Choice(options: List<E>, current: E, label: (E) -> String, onPick: (E) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Hover(Modifier.height(30.dp).border(1.dp, LocalPalette.current.controlStroke, RoundedCornerShape(4.dp)), onClick = { open = true }) {
            Row(Modifier.fillMaxHeight().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                T(label(current), size = 12.sp)
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(14.dp))
            }
        }
        Flyout(open, { open = false }) { options.forEach { o -> FlyoutItem(label(o), if (o == current) Icons.Outlined.Check else null, "") { open = false; onPick(o) } } }
    }
}

@Composable
private fun Check(on: Boolean, label: String, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggle).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(16.dp), tint = if (on) p.accent else p.textSecondary)
        Spacer(Modifier.width(4.dp)); T(label, size = 12.sp)
    }
}

@Composable
private fun RuleCard(r: RenameRule, index: Int, total: Int, onChange: (RenameRule) -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    val p = LocalPalette.current
    val title = when (r) {
        is RenameRule.Replace -> if (r.regex) S.ruleRegex else S.ruleReplace
        is RenameRule.ChangeCase -> S.ruleCase; is RenameRule.Insert -> S.ruleInsert; is RenameRule.Remove -> S.ruleRemove
        is RenameRule.Template -> S.ruleTemplate; is RenameRule.Clean -> S.ruleClean
    }
    Column(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(6.dp)).border(1.dp, p.cardStroke, RoundedCornerShape(6.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Check(r.enabled, "${index + 1}. $title") { onChange(r.with(!r.enabled)) }
            Spacer(Modifier.weight(1f))
            IconBtn(Icons.Outlined.ArrowUpward, S.moveUp, { onMove(-1) }, enabled = index > 0, width = 26.dp, height = 24.dp, iconSize = 13.dp)
            IconBtn(Icons.Outlined.ArrowDownward, S.moveDown, { onMove(1) }, enabled = index < total - 1, width = 26.dp, height = 24.dp, iconSize = 13.dp)
            IconBtn(Icons.Outlined.Close, S.remove, onDelete, width = 26.dp, height = 24.dp, iconSize = 13.dp)
        }
        when (r) {
            is RenameRule.Replace -> {
                PlainField(r.find, { onChange(r.copy(find = it)) }, Modifier.fillMaxWidth(), placeholder = if (r.regex) S.regexFind else S.findText)
                PlainField(r.replace, { onChange(r.copy(replace = it)) }, Modifier.fillMaxWidth(), placeholder = if (r.regex) S.regexReplace else S.replaceWith)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Check(r.regex, ".* Regex") { onChange(r.copy(regex = !r.regex)) }
                    Check(r.matchCase, "Aa") { onChange(r.copy(matchCase = !r.matchCase)) }
                    Choice(RenameTarget.entries, r.target, { it.text }) { onChange(r.copy(target = it)) }
                }
                if (r.regex && r.find.isNotEmpty()) runCatching { Regex(r.find) }.exceptionOrNull()?.let { T(it.message?.lines()?.first() ?: "", size = 11.sp, color = p.danger) }
            }
            is RenameRule.ChangeCase -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(CaseMode.entries, r.mode, { it.text }) { onChange(r.copy(mode = it)) }
                Choice(RenameTarget.entries, r.target, { it.text }) { onChange(r.copy(target = it)) }
            }
            is RenameRule.Insert -> {
                PlainField(r.text, { onChange(r.copy(text = it)) }, Modifier.fillMaxWidth(), placeholder = S.insertText)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(listOf(-1, -2, 0), if (r.at >= 0) 0 else r.at, { when (it) { -1 -> S.atStart; -2 -> S.atEnd; else -> S.atPosition } }) { onChange(r.copy(at = it)) }
                    if (r.at >= 0) PlainField(r.at.toString(), { v -> v.toIntOrNull()?.let { onChange(r.copy(at = it.coerceAtLeast(0))) } }, Modifier.width(60.dp))
                }
            }
            is RenameRule.Remove -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T(S.removeFirst, size = 12.sp); PlainField(r.fromStart.toString(), { v -> v.toIntOrNull()?.let { onChange(r.copy(fromStart = it.coerceAtLeast(0))) } }, Modifier.width(56.dp))
                T(S.removeLast, size = 12.sp); PlainField(r.fromEnd.toString(), { v -> v.toIntOrNull()?.let { onChange(r.copy(fromEnd = it.coerceAtLeast(0))) } }, Modifier.width(56.dp))
            }
            is RenameRule.Template -> PlainField(r.pattern, { onChange(r.copy(pattern = it)) }, Modifier.fillMaxWidth(), placeholder = "{taken:yyyy-MM-dd}_{n:000}")
            is RenameRule.Clean -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Check(r.collapseSpaces, S.collapseSpaces) { onChange(r.copy(collapseSpaces = !r.collapseSpaces)) }
                Check(r.trim, S.trimEnds) { onChange(r.copy(trim = !r.trim)) }
                T(S.spacesTo, size = 12.sp); PlainField(r.spacesTo, { onChange(r.copy(spacesTo = it)) }, Modifier.width(48.dp))
            }
        }
    }
}

