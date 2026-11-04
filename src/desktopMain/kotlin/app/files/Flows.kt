package app.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

/*
 * 捷徑 — workflows in the spirit of macOS Shortcuts, written in this app's own open format,
 * "fileskmp.shortcut/v1" (a .fkflow.json file):
 *
 *   {
 *     "schema": "fileskmp.shortcut/v1",
 *     "name": "…", "description": "…", "icon": "photo", "hotkey": "Ctrl+Alt+1",
 *     "accepts": ["files", "folder", "text", "none"],
 *     "params": [ { "id": "dest", "label": "…", "type": "folder", "default": "{pictures}\\Sorted" } ],
 *     "steps":  [ { "action": "files.filter", "args": { "query": "ext:jpg;png" } }, … ]
 *   }
 *
 * Steps pass a list of values along (file paths or text), like Shortcuts' "content graph": each
 * action takes the current list and leaves a new one. Text in args may use {tokens} (see
 * [FlowRun.expand]); actions that act per file also get {item…} tokens. Every action is listed in
 * [FlowActions.all] with its arguments — the in-app reference and the exported spec come from there.
 * A dry run (試執行) logs what file and system actions would do without doing them.
 */

class FlowParam(val id: String, val label: String, val type: String = "text", val default: String = "")
class FlowStep(val action: String, val args: Map<String, Any?> = emptyMap(), val enabled: Boolean = true)

class Flow(
    val id: String,
    val name: String,
    val description: String = "",
    val icon: String = "bolt",
    val hotkey: String = "",
    val accepts: List<String> = listOf("files"),
    val params: List<FlowParam> = emptyList(),
    val steps: List<FlowStep> = emptyList(),
    val builtIn: Boolean = false,
) {
    fun toJson(): String = Json.write(linkedMapOf(
        "schema" to FlowStore.SCHEMA, "name" to name, "description" to description, "icon" to icon, "hotkey" to hotkey,
        "accepts" to accepts,
        "params" to params.map { linkedMapOf("id" to it.id, "label" to it.label, "type" to it.type, "default" to it.default) },
        "steps" to stepsJson(steps),
    ))

    fun copy(id: String = this.id, name: String = this.name, description: String = this.description, icon: String = this.icon, hotkey: String = this.hotkey,
             accepts: List<String> = this.accepts, params: List<FlowParam> = this.params, steps: List<FlowStep> = this.steps, builtIn: Boolean = this.builtIn) =
        Flow(id, name, description, icon, hotkey, accepts, params, steps, builtIn)

    companion object {
        fun stepsJson(steps: List<FlowStep>): List<Any?> = steps.map { st ->
            val m = linkedMapOf<String, Any?>("action" to st.action)
            if (st.args.isNotEmpty()) m["args"] = st.args.mapValues { (_, v) -> if (v is List<*> && v.all { it is FlowStep }) stepsJson(v.filterIsInstance<FlowStep>()) else v }
            if (!st.enabled) m["enabled"] = false
            m
        }

        @Suppress("UNCHECKED_CAST")
        fun stepsFrom(list: Any?): List<FlowStep> = (list as? List<*>).orEmpty().mapNotNull { o ->
            val m = o as? Map<String, Any?> ?: return@mapNotNull null
            val action = m["action"] as? String ?: return@mapNotNull null
            val args = (m["args"] as? Map<String, Any?>).orEmpty().mapValues { (k, v) ->
                if (FlowActions.byId[action]?.args?.firstOrNull { it.id == k }?.type == "steps") stepsFrom(v) else v
            }
            FlowStep(action, args, m["enabled"] as? Boolean ?: true)
        }

        /** Reads one shortcut; throws with a readable message when it isn't valid. */
        @Suppress("UNCHECKED_CAST")
        fun fromJson(id: String, text: String): Flow {
            val m = Json.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("Not a JSON object")
            val schema = m["schema"] as? String ?: ""
            if (!schema.startsWith("fileskmp.shortcut/")) throw IllegalArgumentException((if (S.zh) "不是 FilesKmp 捷徑（schema 應為 " else "Not a FilesKmp shortcut (schema should be ") + FlowStore.SCHEMA + (if (S.zh) "）" else ")"))
            return Flow(
                id = id, name = m["name"] as? String ?: id, description = m["description"] as? String ?: "",
                icon = m["icon"] as? String ?: "bolt", hotkey = m["hotkey"] as? String ?: "",
                accepts = (m["accepts"] as? List<*>)?.mapNotNull { it as? String } ?: listOf("files"),
                params = (m["params"] as? List<*>).orEmpty().mapNotNull { p ->
                    (p as? Map<String, Any?>)?.let { FlowParam(it["id"] as? String ?: return@mapNotNull null, it["label"] as? String ?: "", it["type"] as? String ?: "text", it["default"]?.toString() ?: "") }
                },
                steps = stepsFrom(m["steps"]),
            )
        }
    }
}

/** One argument of an action: its type drives the editor ("text", "multiline", "path", "folder", "bool", "number", "choice", "steps"). */
class ArgSpec(val id: String, val zh: String, val en: String, val type: String = "text", val default: Any? = "", val choices: List<String> = emptyList()) {
    val label get() = if (S.zh) zh else en
}

class FlowAction(
    val id: String, val zh: String, val en: String, val category: String, val descZh: String, val descEn: String,
    val args: List<ArgSpec> = emptyList(),
    /** Changes files or the system: skipped (only logged) in a dry run. */
    val changes: Boolean = false,
    val run: suspend FlowRun.(FlowStep) -> Unit,
) {
    val label get() = if (S.zh) zh else en
    val description get() = if (S.zh) descZh else descEn
}

/** Questions a shortcut asks while it runs (ui.input / ui.choose / ui.confirm). */
class FlowPrompt(val kind: String, val title: String, val options: List<String>, val default: String, val answer: kotlinx.coroutines.CompletableDeferred<String?>)

class FlowStopped(msg: String) : Exception(msg)

/** One run of a shortcut: the current values, variables, the log, and how to ask the person. */
class FlowRun(
    val s: AppState,
    val flow: Flow,
    input: List<String>,
    val params: Map<String, String>,
    val dryRun: Boolean,
    val ask: suspend (FlowPrompt) -> String?,
    val onLog: (String) -> Unit,
) {
    var items: List<String> = input
    val vars = HashMap<String, List<String>>()
    var stepNo = 0

    fun log(text: String) = onLog(text)

    fun arg(st: FlowStep, id: String): String {
        val spec = FlowActions.byId[st.action]?.args?.firstOrNull { it.id == id }
        val v = st.args[id] ?: spec?.default ?: ""
        return expand(v.toString())
    }
    fun rawArg(st: FlowStep, id: String): String = (st.args[id] ?: FlowActions.byId[st.action]?.args?.firstOrNull { it.id == id }?.default ?: "").toString()
    fun bool(st: FlowStep, id: String): Boolean = when (val v = st.args[id] ?: FlowActions.byId[st.action]?.args?.firstOrNull { it.id == id }?.default) {
        is Boolean -> v; is String -> v.equals("true", true) || v == "1"; else -> false
    }
    fun int(st: FlowStep, id: String, def: Int): Int = arg(st, id).trim().toDoubleOrNull()?.toInt() ?: def

    private val tokenRx = Regex("\\{([a-zA-Z][\\w.]*)(?::([^}]*))?}")

    /**
     * Tokens: {param.x} {var.x} {count} {input} (all values, one per line) {first} {date:fmt}
     * {folder} (the open folder) {home} {desktop} {documents} {downloads} {pictures} {clipboard};
     * with a file: {item} {item.name} {item.stem} {item.ext} {item.parent} {item.folder} {item.index:000}
     * and every rename token as {item.x}: {item.modified:fmt} {item.taken:fmt} {item.size} {item.width} {item.artist} …
     */
    fun expand(text: String, item: String? = null, index: Int = 0): String = tokenRx.replace(text) { m ->
        val key = m.groupValues[1]; val a = m.groupValues[2]
        val home = System.getProperty("user.home")
        fun known(name: String) = File(home, name).takeIf { it.isDirectory }?.path ?: File(File(home, "OneDrive"), name).path
        when {
            key.startsWith("param.") -> params[key.removePrefix("param.")] ?: ""
            key.startsWith("var.") -> vars[key.removePrefix("var.")]?.joinToString("\n") ?: ""
            key == "count" -> items.size.toString()
            key == "input" -> items.joinToString("\n")
            key == "first" -> items.firstOrNull() ?: ""
            key == "date" -> LocalDateTime.now().format(runCatching { DateTimeFormatter.ofPattern(a.ifEmpty { "yyyy-MM-dd" }) }.getOrDefault(DateTimeFormatter.ISO_LOCAL_DATE))
            key == "folder" -> if (s.tab.isVirtual) home else s.tab.location
            key == "home" -> home
            key == "desktop" -> known("Desktop"); key == "documents" -> known("Documents"); key == "downloads" -> File(home, "Downloads").path
            key == "pictures" -> known("Pictures")
            key == "clipboard" -> runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String }.getOrDefault("")
            item != null && key == "item" -> item
            item != null && key == "item.name" -> File(item).name
            item != null && key == "item.parent" -> File(item).parent ?: ""
            item != null && key == "item.folder" -> File(item).parentFile?.name ?: ""
            item != null && key == "item.index" -> (index + 1).toString().let { if (a.isNotEmpty() && a.all { c -> c == '0' }) it.padStart(a.length, '0') else it }
            item != null && key.startsWith("item.") -> {
                val f = FsExtra.item(item)
                if (f == null) "" else {
                    val (stem, ext) = RenameEngine.split(f.name, f.isDir)
                    when (val k = key.removePrefix("item.")) {
                        "stem" -> stem; "ext" -> ext
                        else -> RenameEngine.expand(s, f, "{$k${if (a.isNotEmpty()) ":$a" else ""}}", index + 1, stem, ext)
                    }
                }
            }
            else -> m.value
        }
    }

    /** Files among the current values (text values are skipped). */
    fun files(): List<String> = items.filter { it.length < 4096 && (it.contains('\\') || it.contains('/')) && File(it).exists() }

    suspend fun runSteps(steps: List<FlowStep>) {
        for (st in steps) {
            coroutineContext.ensureActive()
            if (!st.enabled) continue
            val action = FlowActions.byId[st.action] ?: throw FlowStopped((if (S.zh) "未知的動作：" else "Unknown action: ") + st.action)
            stepNo++
            log("▶ $stepNo. ${action.label}" + if (dryRun && action.changes) (if (S.zh) "（試執行）" else " (dry run)") else "")
            action.run(this, st)
            log("   → " + summary(items))
        }
    }

    private fun summary(v: List<String>) = when {
        v.isEmpty() -> if (S.zh) "（沒有內容）" else "(nothing)"
        v.size == 1 -> v[0].take(120)
        else -> (if (S.zh) "${v.size} 項：" else "${v.size} items: ") + v.take(3).joinToString(", ") { File(it).name.ifEmpty { it }.take(40) } + if (v.size > 3) " …" else ""
    }

    /** Folder from a template for one item, created unless dry run. */
    fun targetDir(template: String, item: String, index: Int): File {
        val d = File(expand(template, item, index))
        if (!dryRun) d.mkdirs()
        return d
    }

    fun freeName(f: File): File {
        if (!f.exists()) return f
        val stem = f.nameWithoutExtension; val ext = if (f.extension.isEmpty()) "" else "." + f.extension
        var n = 2
        while (true) { val c = File(f.parentFile, "$stem ($n)$ext"); if (!c.exists()) return c; n++ }
    }
}

/** Every action a shortcut can use (the registry the editor, the runner and the spec share). */
object FlowActions {
    private const val CAT_INPUT = "輸入與文字|Input & text"
    private const val CAT_FILES = "檔案|Files"
    private const val CAT_IMAGE = "影像|Images"
    private const val CAT_SYSTEM = "Windows|Windows"
    private const val CAT_CONTROL = "流程控制|Control"

    fun categoryLabel(c: String) = if (S.zh) c.substringBefore('|') else c.substringAfter('|')

    val all: List<FlowAction> = listOf(
        // ---------------------------------------------------------------- input & text
        FlowAction("files.selected", "取得選取的項目", "Get selected items", CAT_INPUT, "目前選取的檔案與資料夾（沒有選取時為開啟中的資料夾）。", "The selected files and folders (the open folder when nothing is selected).") {
            items = s.tab.selection.toList().ifEmpty { if (s.tab.isVirtual) emptyList() else listOf(s.tab.location) }
        },
        FlowAction("files.list", "列出資料夾內容", "List folder", CAT_INPUT, "列出資料夾中的項目，可篩選與包含子資料夾。", "Items in a folder, optionally filtered and including subfolders.",
            listOf(ArgSpec("folder", "資料夾", "Folder", "folder", "{folder}"), ArgSpec("query", "篩選（搜尋語法）", "Filter (search syntax)", "text", ""),
                ArgSpec("recursive", "包含子資料夾", "Include subfolders", "bool", false))) { st ->
            val q = arg(st, "query").takeIf { it.isNotBlank() }?.let { SearchQuery.parse(it) }
            val out = ArrayList<String>()
            val queue = ArrayDeque<String>().apply { add(arg(st, "folder")) }
            val rec = bool(st, "recursive")
            while (queue.isNotEmpty() && out.size < 100_000) {
                val d = queue.removeFirst()
                FastDir.forEach(d) { e ->
                    val p = if (d.endsWith("\\")) d + e.name else d + "\\" + e.name
                    if (rec && e.isDir && !e.isReparse) queue.add(p)
                    if (q == null || q.matches(SearchQuery.Candidate(e.name, e.name.lowercase(), e.isDir, e.size, e.modified, e.created) { p })) out += p
                }
            }
            items = out
        },
        FlowAction("text.set", "文字", "Text", CAT_INPUT, "以這段文字取代目前內容（可用標記）。", "Replaces the values with this text (tokens allowed).",
            listOf(ArgSpec("text", "文字", "Text", "multiline", ""))) { st -> items = listOf(arg(st, "text")) },
        FlowAction("text.replace", "取代文字", "Replace text", CAT_INPUT, "在每個值中取代文字，可用正規表示式。", "Replaces text in every value; regex allowed.",
            listOf(ArgSpec("find", "尋找", "Find"), ArgSpec("replace", "取代為", "Replace with"), ArgSpec("regex", "正規表示式", "Regex", "bool", false))) { st ->
            val f = rawArg(st, "find"); val r = arg(st, "replace")
            items = items.map { v -> if (bool(st, "regex")) Regex(f).replace(v, r) else v.replace(f, r) }
        },
        FlowAction("text.split", "分割文字", "Split text", CAT_INPUT, "依分隔符號（預設換行）把文字分成多個值。", "Splits text into values (by line by default).",
            listOf(ArgSpec("separator", "分隔符號（\\n 為換行）", "Separator (\\n = new line)", "text", "\\n"))) { st ->
            val sep = rawArg(st, "separator").replace("\\n", "\n").replace("\\t", "\t")
            items = items.flatMap { it.split(sep) }.filter { it.isNotEmpty() }
        },
        FlowAction("text.join", "合併文字", "Join text", CAT_INPUT, "把所有值合併成一段文字。", "Joins all values into one text.",
            listOf(ArgSpec("separator", "分隔符號（\\n 為換行）", "Separator (\\n = new line)", "text", "\\n"))) { st ->
            items = listOf(items.joinToString(rawArg(st, "separator").replace("\\n", "\n").replace("\\t", "\t")))
        },
        FlowAction("clipboard.get", "取得剪貼簿", "Get clipboard", CAT_INPUT, "剪貼簿中的文字。", "The text on the clipboard.") {
            items = listOf(expand("{clipboard}"))
        },
        FlowAction("clipboard.set", "複製到剪貼簿", "Copy to clipboard", CAT_INPUT, "把目前內容（每行一個）放到剪貼簿。", "Puts the values (one per line) on the clipboard.") {
            copyText(items.joinToString("\n"))
        },
        FlowAction("ui.input", "要求輸入", "Ask for input", CAT_INPUT, "執行時請使用者輸入文字。", "Asks for text while running.",
            listOf(ArgSpec("label", "提示", "Prompt", "text", "輸入文字"), ArgSpec("default", "預設值", "Default", "text", ""))) { st ->
            val a = ask(FlowPrompt("input", arg(st, "label"), emptyList(), arg(st, "default"), kotlinx.coroutines.CompletableDeferred())) ?: throw FlowStopped(if (S.zh) "已取消" else "Cancelled")
            items = listOf(a)
        },
        FlowAction("ui.choose", "從清單選擇", "Choose from list", CAT_INPUT, "從目前的值中選一個。", "Pick one of the current values.",
            listOf(ArgSpec("title", "標題", "Title", "text", "選擇一項"))) { st ->
            val a = ask(FlowPrompt("choose", arg(st, "title"), items, "", kotlinx.coroutines.CompletableDeferred())) ?: throw FlowStopped(if (S.zh) "已取消" else "Cancelled")
            items = listOf(a)
        },
        // ---------------------------------------------------------------- files
        FlowAction("files.filter", "篩選", "Filter", CAT_FILES, "只留下符合搜尋語法的項目（ext:、size:、dm:、萬用字元、regex: …）。", "Keeps the items matching the search syntax (ext:, size:, dm:, wildcards, regex: …).",
            listOf(ArgSpec("query", "條件", "Condition", "text", "ext:jpg;png"))) { st ->
            val q = SearchQuery.parse(arg(st, "query")) ?: return@FlowAction
            items = items.filter { p ->
                val f = FsExtra.item(p)
                if (f == null) q.matches(SearchQuery.Candidate(p, p.lowercase(), false, 0, 0, 0) { p })
                else q.matches(SearchQuery.Candidate(f.name, f.name.lowercase(), f.isDir, f.size, f.modified, f.created) { f.path })
            }
        },
        FlowAction("files.sort", "排序", "Sort", CAT_FILES, "依名稱、大小或日期排序。", "Sorts by name, size or date.",
            listOf(ArgSpec("by", "依據", "By", "choice", "name", listOf("name", "size", "modified", "created")), ArgSpec("descending", "遞減", "Descending", "bool", false))) { st ->
            val fs = items.associateWith { FsExtra.item(it) }
            val cmp: Comparator<String> = when (arg(st, "by")) {
                "size" -> compareBy { fs[it]?.size ?: 0L }
                "modified" -> compareBy { fs[it]?.modified ?: 0L }
                "created" -> compareBy { fs[it]?.created ?: 0L }
                else -> compareBy(String.CASE_INSENSITIVE_ORDER) { File(it).name }
            }
            items = items.sortedWith(if (bool(st, "descending")) cmp.reversed() else cmp)
        },
        FlowAction("files.limit", "取前幾項", "Keep first", CAT_FILES, "只保留前 N 項。", "Keeps the first N values.",
            listOf(ArgSpec("count", "數量", "Count", "number", 10))) { st -> items = items.take(int(st, "count", 10)) },
        FlowAction("file.copy", "複製到", "Copy to", CAT_FILES, "把每個項目複製到資料夾（可用 {item.taken:yyyy} 等標記分資料夾）。", "Copies each item into a folder (tokens like {item.taken:yyyy} make subfolders).",
            listOf(ArgSpec("to", "目的資料夾", "Destination folder", "folder", "{desktop}\\Copied")), changes = true) { st ->
            items = files().mapIndexed { i, p ->
                val dst = freeName(File(targetDir(rawArg(st, "to"), p, i), File(p).name))
                if (dryRun) log("   ${File(p).name} → ${dst.path}")
                else { val src = File(p); if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst) }
                dst.path
            }
        },
        FlowAction("file.move", "移動到", "Move to", CAT_FILES, "把每個項目移到資料夾（可用標記）。", "Moves each item into a folder (tokens allowed).",
            listOf(ArgSpec("to", "目的資料夾", "Destination folder", "folder", "{folder}\\Moved")), changes = true) { st ->
            items = files().mapIndexed { i, p ->
                val dst = freeName(File(targetDir(rawArg(st, "to"), p, i), File(p).name))
                if (dryRun) log("   ${File(p).name} → ${dst.path}")
                else java.nio.file.Files.move(File(p).toPath(), dst.toPath())
                dst.path
            }
        },
        FlowAction("file.rename", "重新命名", "Rename", CAT_FILES, "依範本重新命名每個項目，例如 {item.taken:yyyy-MM-dd}_{item.index:000}.{item.ext}。", "Renames each item from a template, e.g. {item.taken:yyyy-MM-dd}_{item.index:000}.{item.ext}.",
            listOf(ArgSpec("name", "新名稱", "New name", "text", "{item.stem}_{item.index:000}.{item.ext}")), changes = true) { st ->
            items = files().mapIndexed { i, p ->
                val name = expand(rawArg(st, "name"), p, i).trimEnd('.').replace(Regex("[<>:\"/\\\\|?*]"), "_")
                val dst = File(File(p).parentFile, name).let { if (it.path.equals(p, true)) it else freeName(it) }
                if (dryRun) log("   ${File(p).name} → ${dst.name}") else if (dst.path != p) { File(p).renameTo(dst); s.tagStore.movePath(p, dst.path) }
                dst.path
            }
        },
        FlowAction("file.delete", "刪除", "Delete", CAT_FILES, "刪除每個項目（預設移到資源回收筒）。", "Deletes each item (to the Recycle Bin by default).",
            listOf(ArgSpec("recycle", "移到資源回收筒", "To the Recycle Bin", "bool", true)), changes = true) { st ->
            val fs = files()
            if (dryRun) fs.forEach { log("   ✕ $it") }
            else {
                val left = if (bool(st, "recycle")) fs.filter { !Fs.recycle(listOf(it)) } else emptyList()
                val r = SafeDelete.deleteTree(if (bool(st, "recycle")) left else fs, toRecycleBin = bool(st, "recycle"))
                r.failures.forEach { log("   ! ${it.path}: ${it.reason}") }
            }
            items = emptyList()
        },
        FlowAction("folder.create", "建立資料夾", "Create folder", CAT_FILES, "建立資料夾（可用標記），結果為該資料夾。", "Creates a folder (tokens allowed); the result is that folder.",
            listOf(ArgSpec("path", "路徑", "Path", "folder", "{folder}\\New folder")), changes = true) { st ->
            val d = File(arg(st, "path")); if (dryRun) log("   + ${d.path}") else d.mkdirs(); items = listOf(d.path)
        },
        FlowAction("file.zip", "壓縮成 zip", "Make a zip", CAT_FILES, "把所有項目壓縮成一個 zip 檔。", "Puts all items into one zip file.",
            listOf(ArgSpec("to", "zip 檔路徑", "Zip file", "path", "{desktop}\\archive-{date:yyyyMMdd-HHmm}.zip")), changes = true) { st ->
            val out = freeName(File(arg(st, "to")))
            if (dryRun) log("   ${files().size} → ${out.path}") else withContext(Dispatchers.IO) { zip(files(), out) }
            items = listOf(out.path)
        },
        FlowAction("file.unzip", "解壓縮", "Extract zip", CAT_FILES, "解壓縮每個 zip 檔。", "Extracts each zip file.",
            listOf(ArgSpec("to", "目的資料夾", "Destination", "folder", "{item.parent}\\{item.stem}")), changes = true) { st ->
            items = files().filter { it.endsWith(".zip", true) }.mapIndexed { i, p ->
                val d = File(expand(rawArg(st, "to"), p, i))
                if (dryRun) log("   ${File(p).name} → ${d.path}") else withContext(Dispatchers.IO) { unzip(File(p), d) }
                d.path
            }
        },
        FlowAction("file.readText", "讀取文字內容", "Read text", CAT_FILES, "每個檔案的文字內容（最多 1 MiB）。", "The text of each file (up to 1 MiB).") {
            items = files().map { p -> File(p).inputStream().use { QuickLook.decode(it.readNBytes(1 shl 20)) } }
        },
        FlowAction("file.writeText", "寫入文字檔", "Write text file", CAT_FILES, "把目前內容寫入檔案。", "Writes the values to a file.",
            listOf(ArgSpec("path", "檔案", "File", "path", "{desktop}\\output.txt"), ArgSpec("append", "附加到結尾", "Append", "bool", false)), changes = true) { st ->
            val f = File(arg(st, "path")); val text = items.joinToString("\r\n")
            if (dryRun) log("   ${text.length} chars → ${f.path}") else { f.parentFile?.mkdirs(); if (bool(st, "append")) f.appendText(text + "\r\n") else f.writeText(text) }
            items = listOf(f.path)
        },
        FlowAction("file.hash", "計算雜湊值", "Hash", CAT_FILES, "每個檔案的雜湊值（SHA-256 / SHA-1 / MD5）。", "Each file's hash (SHA-256 / SHA-1 / MD5).",
            listOf(ArgSpec("algorithm", "演算法", "Algorithm", "choice", "SHA-256", listOf("SHA-256", "SHA-1", "MD5")))) { st ->
            val algo = arg(st, "algorithm")
            items = files().filter { File(it).isFile }.map { p ->
                val md = java.security.MessageDigest.getInstance(algo)
                File(p).inputStream().use { inp -> val buf = ByteArray(1 shl 20); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
                md.digest().joinToString("") { "%02x".format(it) } + "  " + File(p).name
            }
        },
        FlowAction("file.info", "檔案資訊", "File info", CAT_FILES, "每個項目的名稱、大小與修改日期（以 Tab 分隔）。", "Name, size and date of each item (tab separated).") {
            items = items.mapNotNull { FsExtra.item(it) }.map { "${it.name}\t${Fs.sizeText(it.size)}\t${Fs.dateTimeText(it.modified)}" }
        },
        FlowAction("app.navigate", "在 FilesKmp 中顯示", "Show in FilesKmp", CAT_FILES, "開啟第一個項目所在的資料夾並選取這些項目。", "Opens the first item's folder here and selects the items.") {
            val f = items.firstOrNull { File(it).exists() } ?: return@FlowAction
            val dir = if (File(f).isDirectory && items.size == 1) f else File(f).parent
            withContext(Dispatchers.Main) { s.navigate(dir); s.tab.selection = items.filter { Fs.parent(it).equals(dir, true) }.toSet() }
        },
        // ---------------------------------------------------------------- images
        FlowAction("image.convert", "轉換影像格式", "Convert images", CAT_IMAGE, "把每張圖片轉成 PNG、JPEG 或 WebP。", "Converts each picture to PNG, JPEG or WebP.",
            listOf(ArgSpec("format", "格式", "Format", "choice", "webp", listOf("png", "jpg", "webp")), ArgSpec("quality", "品質（1–100）", "Quality (1–100)", "number", 85),
                ArgSpec("to", "目的資料夾", "Destination", "folder", "{item.parent}")), changes = true) { st ->
            val fmt = arg(st, "format").lowercase()
            items = files().filter { it.substringAfterLast('.', "").lowercase() in FsExtra.imageExt }.mapIndexed { i, p ->
                val out = freeName(File(targetDir(rawArg(st, "to"), p, i), File(p).nameWithoutExtension + "." + fmt))
                if (dryRun) log("   ${File(p).name} → ${out.path}") else writeImage(org.jetbrains.skia.Image.makeFromEncoded(File(p).readBytes()), fmt, int(st, "quality", 85), out)
                out.path
            }
        },
        FlowAction("image.resize", "縮小影像", "Resize images", CAT_IMAGE, "等比例縮小到最長邊不超過指定像素。", "Scales pictures down so the longest side fits.",
            listOf(ArgSpec("max", "最長邊（像素）", "Longest side (px)", "number", 1920), ArgSpec("to", "目的資料夾", "Destination", "folder", "{item.parent}\\resized")), changes = true) { st ->
            val max = int(st, "max", 1920)
            items = files().filter { it.substringAfterLast('.', "").lowercase() in FsExtra.imageExt }.mapIndexed { i, p ->
                val out = freeName(File(targetDir(rawArg(st, "to"), p, i), File(p).name))
                if (dryRun) log("   ${File(p).name} → ${out.path} (≤ $max px)")
                else {
                    val img = org.jetbrains.skia.Image.makeFromEncoded(File(p).readBytes())
                    val k = minOf(1.0, max.toDouble() / maxOf(img.width, img.height))
                    val w = (img.width * k).toInt().coerceAtLeast(1); val h = (img.height * k).toInt().coerceAtLeast(1)
                    val surf = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
                    surf.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(img.width.toFloat(), img.height.toFloat()), org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()), org.jetbrains.skia.SamplingMode.MITCHELL, null, true)
                    writeImage(surf.makeImageSnapshot(), out.extension.lowercase().let { if (it == "jpeg") "jpg" else it }, 90, out)
                }
                out.path
            }
        },
        // ---------------------------------------------------------------- Windows
        FlowAction("open", "開啟", "Open", CAT_SYSTEM, "以預設應用程式開啟每個項目。", "Opens each item in its default app.", changes = true) {
            files().forEach { if (dryRun) log("   ↗ $it") else Fs.open(it) }
        },
        FlowAction("open.with", "以應用程式開啟", "Open with app", CAT_SYSTEM, "以指定程式開啟所有項目。", "Opens the items with a program.",
            listOf(ArgSpec("app", "程式（例如 notepad.exe、code）", "Program (e.g. notepad.exe, code)", "path", "notepad.exe")), changes = true) { st ->
            val app = arg(st, "app")
            if (dryRun) log("   $app ${files().joinToString(" ")}") else runCatching { ProcessBuilder(listOf("cmd.exe", "/c", "start", "\"\"", app) + files()).start() }
        },
        FlowAction("url.open", "開啟網址", "Open URL", CAT_SYSTEM, "在瀏覽器開啟網址（可用標記）。", "Opens a web address (tokens allowed).",
            listOf(ArgSpec("url", "網址", "URL", "text", "https://www.bing.com/search?q={first}")), changes = true) { st ->
            val u = arg(st, "url")
            if (dryRun) log("   $u") else runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(u.replace(" ", "%20"))) }
        },
        FlowAction("app.settings", "開啟 Windows 設定頁", "Open a Settings page", CAT_SYSTEM, "例如 display、bluetooth、network-wifi、storagesense、windowsupdate。", "e.g. display, bluetooth, network-wifi, storagesense, windowsupdate.",
            listOf(ArgSpec("page", "設定頁", "Page", "choice", "display", listOf("display", "sound", "bluetooth", "network-wifi", "storagesense", "windowsupdate", "apps-volume", "personalization-background", "defaultapps", "privacy"))), changes = true) { st ->
            val page = arg(st, "page").filter { it.isLetterOrDigit() || it == '-' }
            if (dryRun) log("   ms-settings:$page") else runCatching { ProcessBuilder("cmd.exe", "/c", "start", "", "ms-settings:$page").start() }
        },
        FlowAction("shell.run", "執行指令碼", "Run script", CAT_SYSTEM, "以 PowerShell 或 cmd 執行；目前的值在環境變數 FILES_ITEMS（每行一個），輸出成為新的值。", "Runs PowerShell or cmd; the values are in the FILES_ITEMS environment variable (one per line); the output becomes the new values.",
            listOf(ArgSpec("shell", "殼層", "Shell", "choice", "powershell", listOf("powershell", "cmd")), ArgSpec("script", "指令碼", "Script", "multiline", "\$env:FILES_ITEMS -split \"`n\" | ForEach-Object { (Get-Item \$_).Length }")), changes = true) { st ->
            val script = rawArg(st, "script")
            if (dryRun) { log("   " + script.lines().first().take(100)); return@FlowAction }
            val env = mapOf("FILES_ITEMS" to items.joinToString("\n"), "FILES_FOLDER" to expand("{folder}"))
            val out = withContext(Dispatchers.IO) {
                if (arg(st, "shell") == "cmd") {
                    val tmp = File(AppDirs.cache, "flow-${System.nanoTime()}.cmd").apply { parentFile.mkdirs(); writeText("@echo off\r\n" + script.replace("\n", "\r\n")) }
                    try {
                        val p = ProcessBuilder("cmd.exe", "/c", tmp.path).redirectErrorStream(true).also { it.environment().putAll(env) }.start()
                        p.outputStream.close(); String(p.inputStream.readAllBytes(), charset("MS950")).also { p.waitFor() }
                    } finally { tmp.delete() }
                } else Fs.powershell(script, 120_000, env) ?: ""
            }
            items = out.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
        },
        FlowAction("notify", "顯示通知", "Show notification", CAT_SYSTEM, "顯示 Windows 通知。", "Shows a Windows notification.",
            listOf(ArgSpec("title", "標題", "Title", "text", "FilesKmp"), ArgSpec("text", "內容", "Text", "text", "{count} 個項目已完成"))) { st ->
            val t = arg(st, "title"); val m = arg(st, "text")
            if (dryRun) log("   🔔 $t — $m") else Notifier.show(t, m)
        },
        FlowAction("speak", "朗讀文字", "Speak", CAT_SYSTEM, "以 Windows 語音朗讀文字。", "Reads text aloud with Windows' voice.",
            listOf(ArgSpec("text", "文字", "Text", "text", "{first}")), changes = true) { st ->
            val t = arg(st, "text")
            if (dryRun) log("   🔊 $t") else withContext(Dispatchers.IO) {
                Fs.powershell("Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak(\$env:FILES_TEXT)", 120_000, mapOf("FILES_TEXT" to t))
            }
        },
        FlowAction("wallpaper.set", "設為桌面背景", "Set as desktop background", CAT_SYSTEM, "把第一張圖片設為桌面背景。", "Makes the first picture the desktop background.", changes = true) {
            val img = files().firstOrNull { it.substringAfterLast('.', "").lowercase() in FsExtra.imageExt } ?: return@FlowAction
            if (dryRun) log("   🖼 $img") else withContext(Dispatchers.IO) { FsExtra.setWallpaper(img) }
        },
        FlowAction("screen.lock", "鎖定電腦", "Lock the PC", CAT_SYSTEM, "鎖定 Windows（同 Win+L）。", "Locks Windows (like Win+L).", changes = true) {
            if (dryRun) log("   🔒") else runCatching { ProcessBuilder("rundll32.exe", "user32.dll,LockWorkStation").start() }
        },
        // ---------------------------------------------------------------- control
        FlowAction("ui.confirm", "要求確認", "Ask to continue", CAT_CONTROL, "顯示訊息；選擇取消就停止捷徑。", "Shows a message; Cancel stops the shortcut.",
            listOf(ArgSpec("message", "訊息", "Message", "text", "要繼續處理 {count} 個項目嗎？"))) { st ->
            if (ask(FlowPrompt("confirm", arg(st, "message"), emptyList(), "", kotlinx.coroutines.CompletableDeferred())) == null) throw FlowStopped(if (S.zh) "已取消" else "Cancelled")
        },
        FlowAction("var.set", "設定變數", "Set variable", CAT_CONTROL, "把目前的值存成變數，之後用 {var.名稱} 或「取得變數」。", "Saves the values as a variable: {var.name} or Get variable.",
            listOf(ArgSpec("name", "名稱", "Name", "text", "saved"))) { st -> vars[arg(st, "name")] = items },
        FlowAction("var.get", "取得變數", "Get variable", CAT_CONTROL, "以變數的內容取代目前的值。", "Replaces the values with a variable.",
            listOf(ArgSpec("name", "名稱", "Name", "text", "saved"))) { st -> items = vars[arg(st, "name")] ?: emptyList() },
        FlowAction("stop.if", "條件停止", "Stop if", CAT_CONTROL, "當沒有任何值（或有值）時停止。", "Stops when there are no values (or when there are).",
            listOf(ArgSpec("when", "條件", "When", "choice", "empty", listOf("empty", "notEmpty")), ArgSpec("message", "訊息", "Message", "text", ""))) { st ->
            val stop = if (arg(st, "when") == "notEmpty") items.isNotEmpty() else items.isEmpty()
            if (stop) throw FlowStopped(arg(st, "message").ifEmpty { if (S.zh) "沒有可處理的項目" else "Nothing to do" })
        },
        FlowAction("each", "逐項重複", "Repeat with each", CAT_CONTROL, "對每個值分別執行內含的步驟，最後收集所有結果。", "Runs the inner steps once per value and collects the results.",
            listOf(ArgSpec("steps", "步驟", "Steps", "steps", emptyList<FlowStep>()))) { st ->
            @Suppress("UNCHECKED_CAST") val inner = (st.args["steps"] as? List<FlowStep>).orEmpty()
            val all = items
            val out = ArrayList<String>()
            all.forEachIndexed { i, v ->
                items = listOf(v); vars["index"] = listOf((i + 1).toString())
                runSteps(inner); out += items
            }
            items = out
        },
    )

    val byId = all.associateBy { it.id }

    private fun writeImage(img: org.jetbrains.skia.Image, fmt: String, quality: Int, out: File) {
        val f = when (fmt) { "png" -> org.jetbrains.skia.EncodedImageFormat.PNG; "webp" -> org.jetbrains.skia.EncodedImageFormat.WEBP; else -> org.jetbrains.skia.EncodedImageFormat.JPEG }
        val data = img.encodeToData(f, quality.coerceIn(1, 100)) ?: throw IllegalStateException("Can't encode $fmt")
        out.parentFile?.mkdirs(); out.writeBytes(data.bytes)
    }

    private fun zip(paths: List<String>, out: File) {
        out.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
            for (p in paths) {
                val root = File(p)
                val base = root.parentFile
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(base).path.replace('\\', '/')))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
    }

    private fun unzip(zip: File, dest: File) {
        val root = dest.canonicalFile
        java.util.zip.ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val f = File(root, e.name).canonicalFile
                // No "zip slip": every entry must stay inside the destination.
                if (!f.path.startsWith(root.path + File.separator) && f != root) continue
                if (e.isDirectory) f.mkdirs() else { f.parentFile.mkdirs(); z.getInputStream(e).use { inp -> f.outputStream().use { inp.copyTo(it) } } }
            }
        }
    }

    /** The format spec as Markdown, generated from this registry (設定 → 捷徑 → 匯出規格). */
    fun specMarkdown(): String = buildString {
        append("# FilesKmp 捷徑格式 — ${FlowStore.SCHEMA}\n\n")
        append("A shortcut is a UTF-8 JSON file with the extension `.fkflow.json`:\n\n```json\n")
        append(FlowStore.samples().first().toJson()).append("\n```\n\n")
        append("| Field | Meaning |\n|---|---|\n")
        append("| `schema` | Always `${FlowStore.SCHEMA}` |\n| `name`, `description`, `icon` | Shown in the shortcut list |\n")
        append("| `hotkey` | Optional, e.g. `Ctrl+Alt+1` |\n| `accepts` | `files`, `folder`, `text`, `none` |\n")
        append("| `params` | Values asked before running: `id`, `label`, `type` (`text`/`folder`/`path`), `default`; used as `{param.id}` |\n")
        append("| `steps` | Actions run in order: `action`, `args`, optional `enabled: false` |\n\n")
        append("## Tokens\n\n`{param.x}` `{var.x}` `{count}` `{input}` `{first}` `{date:yyyy-MM-dd}` `{folder}` `{home}` `{desktop}` `{documents}` `{downloads}` `{pictures}` `{clipboard}`\n\n")
        append("Per item: `{item}` `{item.name}` `{item.stem}` `{item.ext}` `{item.parent}` `{item.folder}` `{item.index:000}` `{item.modified:fmt}` `{item.created:fmt}` `{item.taken:fmt}` `{item.size}` `{item.width}` `{item.height}` `{item.camera}` `{item.artist}` `{item.album}` `{item.title}` `{item.track:00}` `{item.field:Name}`\n\n")
        append("## Actions\n\n")
        all.groupBy { it.category }.forEach { (cat, acts) ->
            append("### ${cat.replace('|', '/')}\n\n")
            acts.forEach { a ->
                append("- **`${a.id}`** — ${a.en} / ${a.zh}${if (a.changes) " *(changes files or the system; skipped in a dry run)*" else ""}. ${a.descEn}\n")
                a.args.forEach { g -> append("  - `${g.id}` (${g.type}${if (g.choices.isNotEmpty()) ": " + g.choices.joinToString("|") else ""}) ${g.en}${g.default.let { d -> if (d is String && d.isEmpty() || d is List<*>) "" else ", default `$d`" }}\n") }
            }
            append('\n')
        }
    }
}

/** Windows notifications through the system tray (Action Center shows them like any app's). */
object Notifier {
    private var icon: java.awt.TrayIcon? = null
    fun show(title: String, text: String) {
        try {
            if (!java.awt.SystemTray.isSupported()) return
            val tray = java.awt.SystemTray.getSystemTray()
            val ic = icon ?: java.awt.TrayIcon(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), "FilesKmp").also {
                it.isImageAutoSize = true; tray.add(it); icon = it
            }
            ic.displayMessage(title, text, java.awt.TrayIcon.MessageType.INFO)
        } catch (_: Throwable) {}
    }
}

/** The person's shortcuts in %APPDATA%\FilesKmp\shortcuts (*.fkflow.json) plus the built-in samples. */
object FlowStore {
    const val SCHEMA = "fileskmp.shortcut/v1"
    val dir get() = File(Prefs.dir, "shortcuts")
    var mine by androidx.compose.runtime.mutableStateOf<List<Flow>>(emptyList()); private set
    var errors by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList()); private set

    fun reload() {
        val ok = ArrayList<Flow>(); val bad = ArrayList<String>()
        dir.listFiles { f -> f.name.endsWith(".fkflow.json", true) }?.sortedBy { it.name.lowercase() }?.forEach { f ->
            try { ok += Flow.fromJson(f.name.removeSuffix(".fkflow.json"), f.readText(Charsets.UTF_8)) } catch (e: Exception) { bad += "${f.name}: ${e.message}" }
        }
        mine = ok; errors = bad
    }

    fun all(): List<Flow> = mine + samples()

    fun save(f: Flow): Flow {
        val id = if (f.builtIn || f.id.isBlank()) uniqueId(f.name) else f.id
        val out = f.copy(id = id, builtIn = false)
        if (!Prefs.readOnly) { dir.mkdirs(); File(dir, "$id.fkflow.json").writeText(out.toJson(), Charsets.UTF_8) }
        reload()
        return out
    }

    fun delete(f: Flow) { if (!f.builtIn) File(dir, "${f.id}.fkflow.json").delete(); reload() }

    private fun uniqueId(name: String): String {
        val base = name.replace(Regex("[<>:\"/\\\\|?*\\s]+"), "-").trim('-').ifEmpty { "shortcut" }.take(60)
        var id = base; var n = 2
        while (File(dir, "$id.fkflow.json").exists()) id = "$base-${n++}"
        return id
    }

    fun importFile(file: File): Flow = save(Flow.fromJson("", file.readText(Charsets.UTF_8)).copy(id = ""))

    fun samples(): List<Flow> {
        val zh = S.zh
        return listOf(
            Flow("builtin:photos-by-date", if (zh) "相片依拍攝日期整理" else "Sort photos by date taken",
                if (zh) "把選取的相片複製到「年\\月」資料夾，依拍攝日期（沒有時用修改日期）。" else "Copies the selected photos into year\\month folders by date taken (or modified).",
                "photo", accepts = listOf("files", "folder"),
                params = listOf(FlowParam("dest", if (zh) "目的資料夾" else "Destination", "folder", "{pictures}\\依日期整理")),
                steps = listOf(
                    FlowStep("files.selected"),
                    FlowStep("files.filter", mapOf("query" to "ext:jpg;jpeg;png;heic;webp;gif")),
                    FlowStep("stop.if", mapOf("when" to "empty", "message" to if (zh) "選取的項目中沒有相片" else "No photos selected")),
                    FlowStep("file.copy", mapOf("to" to "{param.dest}\\{item.taken:yyyy}\\{item.taken:MM}")),
                    FlowStep("notify", mapOf("title" to if (zh) "相片整理完成" else "Photos sorted", "text" to if (zh) "已複製 {count} 張相片" else "Copied {count} photos")),
                ), builtIn = true),
            Flow("builtin:hash", if (zh) "計算 SHA-256 並複製" else "Copy SHA-256 hashes",
                if (zh) "計算選取檔案的 SHA-256，複製到剪貼簿。" else "Hashes the selected files and copies the list.", "fingerprint",
                steps = listOf(FlowStep("files.selected"), FlowStep("file.hash"), FlowStep("text.join"), FlowStep("clipboard.set"),
                    FlowStep("notify", mapOf("title" to "SHA-256", "text" to if (zh) "已複製到剪貼簿" else "Copied to the clipboard"))), builtIn = true),
            Flow("builtin:webp", if (zh) "圖片縮小並轉成 WebP" else "Shrink pictures to WebP",
                if (zh) "最長邊縮到 1920 像素，轉成 WebP 存到「webp」資料夾。" else "Longest side 1920 px, saved as WebP in a webp folder.", "image",
                steps = listOf(FlowStep("files.selected"), FlowStep("files.filter", mapOf("query" to "ext:jpg;jpeg;png;bmp;webp")),
                    FlowStep("image.resize", mapOf("max" to 1920, "to" to "{item.parent}\\webp")),
                    FlowStep("image.convert", mapOf("format" to "webp", "quality" to 82, "to" to "{item.parent}")),
                    FlowStep("app.navigate")), builtIn = true),
            Flow("builtin:old-downloads", if (zh) "清理 30 天前的下載" else "Clean downloads older than 30 days",
                if (zh) "列出「下載」中 30 天沒動過的檔案，確認後移到資源回收筒。" else "Lists files in Downloads untouched for 30 days and recycles them after you confirm.", "broom",
                accepts = listOf("none"),
                steps = listOf(FlowStep("files.list", mapOf("folder" to "{downloads}", "query" to "file: dm:<30d")),
                    FlowStep("stop.if", mapOf("when" to "empty", "message" to if (zh) "沒有 30 天前的下載檔案" else "Nothing older than 30 days")),
                    FlowStep("ui.confirm", mapOf("message" to if (zh) "要把 {count} 個 30 天前的下載檔案移到資源回收筒嗎？" else "Move {count} downloads older than 30 days to the Recycle Bin?")),
                    FlowStep("file.delete", mapOf("recycle" to true))), builtIn = true),
            Flow("builtin:largest", if (zh) "列出最大的 20 個檔案" else "List the 20 largest files",
                if (zh) "在目前資料夾（含子資料夾）找出最大的 20 個檔案並複製清單。" else "Finds the 20 largest files here (with subfolders) and copies the list.", "chart",
                accepts = listOf("folder"),
                steps = listOf(FlowStep("files.list", mapOf("folder" to "{folder}", "query" to "file:", "recursive" to true)),
                    FlowStep("files.sort", mapOf("by" to "size", "descending" to true)), FlowStep("files.limit", mapOf("count" to 20)),
                    FlowStep("file.info"), FlowStep("text.join"), FlowStep("clipboard.set")), builtIn = true),
            Flow("builtin:zip-desktop", if (zh) "壓縮到桌面" else "Zip to the desktop",
                if (zh) "把選取的項目壓縮成桌面上的 zip，並在此顯示。" else "Zips the selection onto the desktop and shows it.", "archive",
                steps = listOf(FlowStep("files.selected"), FlowStep("file.zip", mapOf("to" to "{desktop}\\{date:yyyyMMdd-HHmm}.zip")), FlowStep("app.navigate")), builtIn = true),
        )
    }
}
