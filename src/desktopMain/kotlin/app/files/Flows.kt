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

/**
 * What a step takes and leaves. Every value is carried as text; the type says what the text means
 * (a file path, a number, "true"/"false", a JSON document, a date) so the editor can show it and
 * warn when a step gets something it can't use.
 */
enum class VType(val zh: String, val en: String) {
    None("無", "nothing"), Anything("任何值", "anything"), Files("檔案", "files"), Text("文字", "text"),
    Number("數字", "number"), Bool("是/否", "true/false"), Json("字典/JSON", "dictionary/JSON"), Date("日期時間", "date"),
    /** Output only: the step hands on what it got. */
    Same("同輸入", "same as input");
    val label get() = if (S.zh) zh else en
}

/**
 * One step. [input] picks what it works on: "" (the previous step's result), "nothing",
 * "shortcut" (what the shortcut was started with) or "var:NAME". [saveAs] also keeps the result
 * in a variable ({var.NAME}).
 */
class FlowStep(val action: String, val args: Map<String, Any?> = emptyMap(), val enabled: Boolean = true, val input: String = "", val saveAs: String = "") {
    fun with(args: Map<String, Any?> = this.args, enabled: Boolean = this.enabled, input: String = this.input, saveAs: String = this.saveAs) = FlowStep(action, args, enabled, input, saveAs)
}

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
            if (st.input.isNotEmpty()) m["input"] = st.input
            if (st.args.isNotEmpty()) m["args"] = st.args.mapValues { (_, v) -> if (v is List<*> && v.all { it is FlowStep }) stepsJson(v.filterIsInstance<FlowStep>()) else v }
            if (st.saveAs.isNotEmpty()) m["saveAs"] = st.saveAs
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
            FlowStep(action, args, m["enabled"] as? Boolean ?: true, m["input"] as? String ?: "", m["saveAs"] as? String ?: "")
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

/**
 * One argument of an action: its type drives the editor ("text", "multiline", "query" (search syntax), "path", "folder",
 * "bool", "number", "choice", "steps", "app" (an app on this PC), "flow" (another shortcut)).
 */
class ArgSpec(val id: String, val zh: String, val en: String, val type: String = "text", val default: Any? = "", val choices: List<String> = emptyList()) {
    val label get() = if (S.zh) zh else en
}

class FlowAction(
    val id: String, val zh: String, val en: String, val category: String, val descZh: String, val descEn: String,
    val args: List<ArgSpec> = emptyList(),
    /** Changes files or the system: skipped (only logged) in a dry run. */
    val changes: Boolean = false,
    /** What it works on ([VType.None] = ignores the values it gets). */
    val input: VType = VType.Anything,
    /** What it leaves ([VType.Same] = hands its input on unchanged). */
    val output: VType = VType.Same,
    /** When the output type depends on an argument (e.g. http.request's "output"). */
    val outputFor: ((FlowStep) -> VType)? = null,
    val run: suspend FlowRun.(FlowStep) -> Unit,
) {
    val label get() = if (S.zh) zh else en
    val description get() = if (S.zh) descZh else descEn
    fun retyped(input: VType, output: VType, category: String) = FlowAction(id, zh, en, category, descZh, descEn, args, changes, input, output, outputFor, run)
    fun outputOf(st: FlowStep, incoming: VType): VType = (outputFor?.invoke(st) ?: output).let { if (it == VType.Same) incoming else it }
    /** "檔案 → 文字" */
    val ioLabel get() = input.label + " → " + (if (outputFor != null) (if (S.zh) "依設定" else "depends") else output.label)
}

/** Questions a shortcut asks while it runs (ui.input / ui.choose / ui.confirm / wait.skippable / debug.show). */
class FlowPrompt(val kind: String, val title: String, val options: List<String>, val default: String, val answer: kotlinx.coroutines.CompletableDeferred<String?>)

class FlowStopped(msg: String) : Exception(msg)

fun inputLabel(input: String): String = when {
    input.isEmpty() || input == "previous" -> if (S.zh) "上一步的結果" else "previous step"
    input == "nothing" -> if (S.zh) "不使用輸入" else "nothing"
    input == "shortcut" -> if (S.zh) "捷徑的輸入" else "shortcut input"
    input.startsWith("var:") -> (if (S.zh) "變數 " else "variable ") + input.removePrefix("var:")
    else -> input
}

/** A number as text without a needless ".0". */
fun numText(d: Double): String = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

/** One run of a shortcut: the current values, variables, the log, and how to ask the person. */
class FlowRun(
    val s: AppState,
    val flow: Flow,
    input: List<String>,
    val params: Map<String, String>,
    val dryRun: Boolean,
    val ask: suspend (FlowPrompt) -> String?,
    val onLog: (String) -> Unit,
    /** How deep in flow.run calls this run is (a shortcut may call others, up to [MAX_DEPTH]). */
    val depth: Int = 0,
) {
    /** Steps and hosts the person allowed once during this run (FlowSafety.kt). */
    val permissions = FlowPermissions()
    /** What the shortcut was started with (a step's input "shortcut"). */
    val initial: List<String> = input
    var items: List<String> = input
    val vars = HashMap<String, List<String>>()
    var stepNo = 0

    companion object { const val MAX_DEPTH = 8 }

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
            key == "last" -> items.lastOrNull() ?: ""
            key == "newline" -> "\n"; key == "tab" -> "\t"
            key == "music" -> known("Music"); key == "videos" -> known("Videos")
            key == "temp" -> System.getProperty("java.io.tmpdir").trimEnd('\\')
            key.startsWith("env.") -> System.getenv(key.removePrefix("env.")) ?: ""
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
            items = when {
                st.input.isEmpty() || st.input == "previous" -> items
                st.input == "nothing" -> emptyList()
                st.input == "shortcut" -> initial
                st.input.startsWith("var:") -> vars[st.input.removePrefix("var:")] ?: emptyList()
                else -> items
            }
            log("▶ $stepNo. ${action.label}  [${action.ioLabel}]" + (if (st.input.isNotEmpty()) "  ← " + inputLabel(st.input) else "") +
                if (dryRun && action.changes) (if (S.zh) "（試執行）" else " (dry run)") else "")
            // url.open asks only for a non-web address; a web address goes through the host list instead.
            if (action.id in FlowTrust.gated && action.id != "url.open") requireTrust(st)
            action.run(this, st)
            if (st.saveAs.isNotBlank()) vars[st.saveAs.trim()] = items
            log("   → " + summary(items) + if (st.saveAs.isNotBlank()) "  ⇒ {var.${st.saveAs.trim()}}" else "")
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

    /** Numbers among the values (text that isn't a number is skipped). */
    fun numbers(): List<Double> = items.mapNotNull { it.trim().replace(",", "").toDoubleOrNull() }

    fun stop(): Nothing = throw FlowStopped(if (S.zh) "已取消" else "Cancelled")

    fun freeName(f: File): File {
        if (!f.exists()) return f
        val stem = f.nameWithoutExtension; val ext = if (f.extension.isEmpty()) "" else "." + f.extension
        var n = 2
        while (true) { val c = File(f.parentFile, "$stem ($n)$ext"); if (!c.exists()) return c; n++ }
    }
}

/** Every action a shortcut can use (the registry the editor, the runner and the spec share). */
object FlowActions {
    const val CAT_INPUT = "取得輸入|Get input"
    const val CAT_TEXT = "文字|Text"
    const val CAT_LIST = "清單與字典|Lists & dictionaries"
    const val CAT_DATA = "數字、日期與編碼|Numbers, dates & encoding"
    const val CAT_FILES = "檔案|Files"
    const val CAT_IMAGE = "影像|Images"
    const val CAT_VIDEO = "影片與音訊|Video & audio"
    const val CAT_WEB = "網路|Web"
    const val CAT_APPS = "應用程式|Apps"
    const val CAT_MEDIA = "聲音與語音|Sound & speech"
    const val CAT_SYSTEM = "Windows 系統|Windows"
    const val CAT_SHARE = "郵件與簡訊|Mail & messages"
    const val CAT_CONTROL = "流程控制|Control"
    const val CAT_DEBUG = "除錯|Debug"

    fun categoryLabel(c: String) = if (S.zh) c.substringBefore('|') else c.substringAfter('|')

    private class Io(val input: VType, val output: VType, val category: String? = null)

    /** Input and output of the first actions (defined below without them), and where they're listed. */
    private val coreIo: Map<String, Io> = mapOf(
        "files.selected" to Io(VType.None, VType.Files), "files.list" to Io(VType.None, VType.Files),
        "text.set" to Io(VType.None, VType.Text, CAT_TEXT), "text.replace" to Io(VType.Text, VType.Text, CAT_TEXT),
        "text.split" to Io(VType.Text, VType.Text, CAT_TEXT), "text.join" to Io(VType.Text, VType.Text, CAT_TEXT),
        "clipboard.get" to Io(VType.None, VType.Text), "clipboard.set" to Io(VType.Text, VType.Same, CAT_TEXT),
        "ui.input" to Io(VType.None, VType.Text), "ui.choose" to Io(VType.Anything, VType.Same),
        "files.filter" to Io(VType.Files, VType.Files), "files.sort" to Io(VType.Files, VType.Files), "files.limit" to Io(VType.Anything, VType.Same, CAT_LIST),
        "file.copy" to Io(VType.Files, VType.Files), "file.move" to Io(VType.Files, VType.Files), "file.rename" to Io(VType.Files, VType.Files),
        "file.delete" to Io(VType.Files, VType.None), "folder.create" to Io(VType.None, VType.Files), "file.zip" to Io(VType.Files, VType.Files),
        "file.unzip" to Io(VType.Files, VType.Files), "file.readText" to Io(VType.Files, VType.Text), "file.writeText" to Io(VType.Text, VType.Files),
        "file.hash" to Io(VType.Files, VType.Text), "file.info" to Io(VType.Files, VType.Text), "app.navigate" to Io(VType.Files, VType.Same),
        "image.convert" to Io(VType.Files, VType.Files), "image.resize" to Io(VType.Files, VType.Files),
        "open" to Io(VType.Files, VType.Same, CAT_APPS), "open.with" to Io(VType.Files, VType.Same, CAT_APPS), "url.open" to Io(VType.Anything, VType.Same, CAT_WEB),
        "app.settings" to Io(VType.None, VType.Same), "shell.run" to Io(VType.Anything, VType.Text, CAT_APPS), "notify" to Io(VType.Anything, VType.Same),
        "speak" to Io(VType.Text, VType.Same, CAT_MEDIA), "wallpaper.set" to Io(VType.Files, VType.Same), "screen.lock" to Io(VType.None, VType.Same),
        "ui.confirm" to Io(VType.Anything, VType.Same), "var.set" to Io(VType.Anything, VType.Same), "var.get" to Io(VType.None, VType.Anything),
        "stop.if" to Io(VType.Anything, VType.Same), "each" to Io(VType.Anything, VType.Anything),
    )

    /** Display order of the categories. */
    private val order = listOf(CAT_INPUT, CAT_TEXT, CAT_LIST, CAT_DATA, CAT_FILES, CAT_IMAGE, CAT_VIDEO, CAT_WEB, CAT_APPS, CAT_MEDIA, CAT_SYSTEM, CAT_SHARE, CAT_CONTROL, CAT_DEBUG)

    val all: List<FlowAction> by lazy {
        val core = coreActions().map { a -> coreIo[a.id]?.let { a.retyped(it.input, it.output, it.category ?: a.category) } ?: a }
        val list = core + moreActions() + systemActions() + videoActions()
        // The registry: actions grouped by category in display order, the original order kept within each.
        list.sortedBy { order.indexOf(it.category).let { i -> if (i < 0) order.size else i } }
    }

    private fun coreActions(): List<FlowAction> = listOf(
        // ---------------------------------------------------------------- input & text
        FlowAction("files.selected", "取得選取的項目", "Get selected items", CAT_INPUT, "目前選取的檔案與資料夾（沒有選取時為開啟中的資料夾）。", "The selected files and folders (the open folder when nothing is selected).") {
            items = s.tab.selection.toList().ifEmpty { if (s.tab.isVirtual) emptyList() else listOf(s.tab.location) }
        },
        FlowAction("files.list", "列出資料夾內容", "List folder", CAT_INPUT, "列出資料夾中的項目，可篩選與包含子資料夾。", "Items in a folder, optionally filtered and including subfolders.",
            listOf(ArgSpec("folder", "資料夾", "Folder", "folder", "{folder}"), ArgSpec("query", "篩選（搜尋語法）", "Filter (search syntax)", "query", ""),
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
        FlowAction("files.filter", "篩選", "Filter", CAT_FILES, "只留下符合搜尋語法的項目（file:、folder:、ext:、size:、dm:、萬用字元、regex: …）。", "Keeps the items matching the search syntax (file:, folder:, ext:, size:, dm:, wildcards, regex: …).",
            listOf(ArgSpec("query", "條件", "Condition", "query", "ext:jpg;png"))) { st ->
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
        FlowAction("file.hash", "計算雜湊值", "Hash", CAT_FILES, "每個檔案的雜湊值（SHA-256 / SHA-1 / MD5），小寫十六進位；可只要雜湊值（拿來比對）或附上檔名。",
            "Each file's hash (SHA-256 / SHA-1 / MD5) in lowercase hex: the hash alone (for comparing) or with the file name.",
            listOf(ArgSpec("algorithm", "演算法", "Algorithm", "choice", "SHA-256", listOf("SHA-256", "SHA-1", "MD5")),
                ArgSpec("format", "結果", "Result", "choice", "hash", listOf("hash", "hash+name")))) { st ->
            val algo = arg(st, "algorithm")
            val withName = arg(st, "format") == "hash+name"
            items = files().filter { File(it).isFile }.map { p ->
                val md = java.security.MessageDigest.getInstance(algo)
                File(p).inputStream().use { inp -> val buf = ByteArray(1 shl 20); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
                md.digest().joinToString("") { "%02x".format(it) } + if (withName) "  " + File(p).name else ""
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
            if (dryRun) { log("   $u"); return@FlowAction }
            val uri = runCatching { java.net.URI(u.trim().replace(" ", "%20")) }.getOrElse { throw FlowStopped((if (S.zh) "網址無效：" else "Not a valid address: ") + u) }
            // A web page: the host list. mailto: opens a draft. Anything else (file:, ms-…:, custom
            // protocols) can start a program, so it needs a trusted shortcut.
            when (uri.scheme?.lowercase()) {
                "http", "https" -> requireHost(uri)
                "mailto" -> {}
                else -> requireTrust(st)
            }
            runCatching { java.awt.Desktop.getDesktop().browse(uri) }
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
                items = listOf(v); vars["index"] = listOf((i + 1).toString()); vars["item"] = listOf(v)
                runSteps(inner); out += items
            }
            items = out
        },
    )

    val byId: Map<String, FlowAction> by lazy { all.associateBy { it.id } }

    /**
     * The arguments that are filled in once per value, so {item…} tokens work in them (FlowRun.expand with an item).
     * Keep in step with the actions: everywhere else {item…} stays as typed.
     */
    val perItemArgs: Map<String, Set<String>> = mapOf(
        "file.copy" to setOf("to"), "file.move" to setOf("to"), "file.rename" to setOf("name"), "file.unzip" to setOf("to"),
        "image.convert" to setOf("to"), "image.resize" to setOf("to"), "text.template" to setOf("template"),
        "json.make" to setOf("entries"), "json.set" to setOf("value"), "number.calc" to setOf("formula"),
        "base64.decode" to setOf("path"), "app.launch" to setOf("args"),
        "video.convert" to setOf("name", "to"), "video.compress" to setOf("name", "to"), "video.scale" to setOf("name", "to"),
        "ffmpeg.run" to setOf("args", "output", "to"),
    )

    class StepType(val incoming: VType, val outgoing: VType, val warning: String?)

    /** Whether a step that wants [want] can use [got]. Everything is text, so text accepts anything. */
    fun accepts(want: VType, got: VType): Boolean = when (want) {
        VType.None, VType.Anything, VType.Text, VType.Same -> true
        VType.Files -> got == VType.Files || got == VType.Anything
        VType.Number, VType.Bool, VType.Json, VType.Date -> got == want || got == VType.Text || got == VType.Anything
    }

    /** The type entering a shortcut, from what it accepts. */
    fun startType(accepts: List<String>): VType = when {
        accepts.isEmpty() || accepts == listOf("none") -> VType.None
        accepts.all { it == "files" || it == "folder" } -> VType.Files
        accepts == listOf("text") -> VType.Text
        else -> VType.Anything
    }

    /**
     * What goes into and comes out of each step, following inputs, variables and nested steps — the
     * editor shows it on every step and warns when a step can't use what it gets.
     */
    fun typeFlow(steps: List<FlowStep>, start: VType, shortcutInput: VType = start, varTypes: MutableMap<String, VType> = HashMap()): List<StepType> {
        var cur = start
        return steps.map { st ->
            val a = byId[st.action]
            val incoming = when {
                st.input == "nothing" -> VType.None
                st.input == "shortcut" -> shortcutInput
                st.input.startsWith("var:") -> varTypes[st.input.removePrefix("var:")] ?: VType.Anything
                else -> cur
            }
            if (a == null || !st.enabled) return@map StepType(incoming, incoming, null)
            @Suppress("UNCHECKED_CAST") fun inner(id: String) = (st.args[id] as? List<FlowStep>).orEmpty()
            val out = when (st.action) {
                "var.get" -> varTypes[st.args["name"]?.toString() ?: "saved"] ?: VType.Anything
                "each", "repeat" -> typeFlow(inner("steps"), incoming, shortcutInput, HashMap(varTypes)).lastOrNull()?.outgoing ?: incoming
                "if" -> {
                    val t = typeFlow(inner("then"), incoming, shortcutInput, HashMap(varTypes)).lastOrNull()?.outgoing ?: incoming
                    val e = typeFlow(inner("else"), incoming, shortcutInput, HashMap(varTypes)).lastOrNull()?.outgoing ?: incoming
                    if (t == e) t else VType.Anything
                }
                else -> a.outputOf(st, incoming)
            }
            if (st.action == "var.set") varTypes[st.args["name"]?.toString() ?: "saved"] = incoming
            if (st.saveAs.isNotBlank()) varTypes[st.saveAs.trim()] = out
            val warn = when {
                a.input == VType.None || a.input == VType.Anything -> null
                incoming == VType.None -> if (S.zh) "這個步驟需要${a.input.zh}，但沒有任何輸入" else "This step needs ${a.input.en} but gets nothing"
                !accepts(a.input, incoming) -> if (S.zh) "這個步驟需要${a.input.zh}，但收到的是${incoming.zh}" else "This step needs ${a.input.en} but gets ${incoming.en}"
                else -> null
            }
            cur = out
            StepType(incoming, out, warn)
        }
    }

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

    /** Worked shortcuts for the spec and the assistant; the token test checks each one passes the validator. */
    val recipes: List<Pair<String, String>> = listOf(
        "Convert videos to a chosen resolution and format" to """{"schema":"fileskmp.shortcut/v1","name":"影片轉檔（選解析度與格式）","accepts":["files"],"steps":[
  {"action":"files.selected"},
  {"action":"files.filter","args":{"query":"ext:mp4;mkv;mov;webm;ts;m2ts;avi;m4v;wmv;flv"},"saveAs":"videos"},
  {"action":"text.set","input":"nothing","args":{"text":"1080p\n720p\n480p"}},
  {"action":"text.split"},
  {"action":"ui.choose","args":{"title":"選擇解析度"},"saveAs":"res"},
  {"action":"text.set","input":"nothing","args":{"text":"mp4\nmkv\nwebm\nmov"}},
  {"action":"text.split"},
  {"action":"ui.choose","args":{"title":"選擇格式"},"saveAs":"fmt"},
  {"action":"video.convert","input":"var:videos","args":{"format":"{var.fmt}","height":"{var.res}","quality":"medium","name":"{item.stem}_{var.res}","to":"{item.parent}"}}
]}""",
        "Shrink videos to send (under 25 MB)" to """{"schema":"fileskmp.shortcut/v1","name":"壓縮影片到 25 MB","accepts":["files"],"steps":[
  {"action":"files.filter","args":{"query":"ext:mp4;mkv;mov;webm;ts;avi;m4v"}},
  {"action":"video.compress","args":{"method":"size","sizeMB":"25","codec":"h264","name":"{item.stem}_25MB"}}
]}""",
    )

    /** The format spec as Markdown, generated from this registry (設定 → 捷徑 → 匯出規格). */
    fun specMarkdown(): String = buildString {
        append("# FilesKmp 捷徑格式 — ${FlowStore.SCHEMA}\n\n")
        append("A shortcut is a UTF-8 JSON file with the extension `.fkflow.json`:\n\n```json\n")
        append(FlowStore.samples().first().toJson()).append("\n```\n\n")
        append("| Field | Meaning |\n|---|---|\n")
        append("| `schema` | Always `${FlowStore.SCHEMA}` |\n| `name`, `description`, `icon` | Shown in the shortcut list |\n")
        append("| `hotkey` | Optional, e.g. `Ctrl+Alt+1` |\n| `accepts` | `files`, `folder`, `text`, `none` |\n")
        append("| `params` | Values asked before running: `id`, `label`, `type` (`text`/`folder`/`path`), `default`; used as `{param.id}` |\n")
        append("| `steps` | Actions run in order (see *Steps*) |\n\n")
        append("## Steps\n\n")
        append("```json\n{ \"action\": \"file.hash\", \"input\": \"var:photos\", \"args\": { \"algorithm\": \"SHA-256\" }, \"saveAs\": \"hashes\", \"enabled\": true }\n```\n\n")
        append("| Field | Meaning |\n|---|---|\n")
        append("| `action` | The action id (below) |\n| `args` | The action's arguments; text may use tokens |\n")
        append("| `input` | What the step works on: omitted = the previous step's result, `nothing`, `shortcut` (what the shortcut was started with), `var:NAME` |\n")
        append("| `saveAs` | Also keeps the step's result in a variable, used as `{var.NAME}` or `input: \"var:NAME\"` |\n")
        append("| `enabled` | `false` skips the step |\n\n")
        append("## Values and types\n\nSteps pass a **list of values** along. Every value is text; each action declares what it takes and leaves:\n\n")
        append("| Type | Values are |\n|---|---|\n")
        append("| `files` | Full paths of files or folders |\n| `text` | Any text (one value per item; `text.split` / `text.join` convert) |\n")
        append("| `number` | Decimal numbers (`3`, `2.5`) |\n| `true/false` | `true` or `false` |\n| `dictionary/JSON` | A JSON document (`{\"a\": 1}` or `[1, 2]`) |\n")
        append("| `date` | `yyyy-MM-dd HH:mm:ss` unless a format is chosen |\n| `nothing` | The action ignores its input / leaves no values |\n| `same as input` | The values are handed on unchanged (for actions done for their effect) |\n\n")
        append("## Running\n\n")
        append("- The shortcut starts with the selected files and folders (full paths). With nothing selected it starts with nothing, ")
        append("unless `accepts` includes `folder`: then it starts with the open folder. A `files.selected` step gives the selection, or the open folder when nothing is selected.\n")
        append("- Each step replaces the values with its result; `input` picks other values for one step. `saveAs` names (and `var.set` names) ")
        append("must be ASCII letters, digits or `_` (e.g. `fileHash`), or `{var.NAME}` won't be recognised.\n")
        append("- File actions only use values that are full paths of existing files or folders; any other value is skipped silently.\n")
        append("- `stop.if` looks at how many values there are, not at their text: one empty text is still a value. To test text, use `if` with `isEmpty`.\n")
        append("- A step that fails, or `stop.if` / a declined `ui.confirm`, ends the whole run and shows its message.\n\n")
        append("## Tokens (how text arguments are filled in)\n\n")
        append("Every text argument may contain `{name}` or `{name:format}`. Rules of the parser:\n\n")
        append("- A name is ASCII letters, digits, `_` and `.`, starting with a letter. Anything else (`{var.檔案}`, `{var.my-hash}`) is not a token.\n")
        append("- An unknown token stays exactly as typed, with no error. A `{param.x}` or `{var.x}` that doesn't exist becomes empty text.\n")
        append("- `{var.x}` and `{input}` holding several values become one text with a line break between the values.\n")
        append("- Date formats are Java DateTimeFormatter patterns (`yyyy` `MM` `dd` `HH` `mm` `ss`); the default is `yyyy-MM-dd`.\n")
        append("- Tokens are filled in once, when the step runs; their results are not searched for tokens again.\n\n")
        append("| Token | Value |\n|---|---|\n")
        append("| `{param.ID}` | A parameter's answer (`params`) |\n| `{var.NAME}` | A variable set by an earlier step (`saveAs` / `var.set`) |\n")
        FlowTokens.tokens.filter { it.group != TokenInfo.Group.Item }.forEach { append("| `${it.text}` | ${it.en} |\n") }
        append("\nInside `each` the current value is `{var.item}` (and `{first}`, since the inner steps get just that value); `{var.index}` is 1, 2, … ")
        append("Inside `repeat`: `{var.index}`.\n\n")
        append("### `{item…}` tokens: only in *per item* arguments\n\n")
        append("These are filled in for each value in turn, and **only** in the arguments marked *per item* in the action list: ")
        append(perItemArgs.entries.joinToString(", ") { (a, g) -> "`$a` (${g.joinToString("/") { "`$it`" }})" })
        append(". Anywhere else (`notify` text, `if` subject, `text.set` …) they stay as typed: use `{first}` or `{var.item}` inside `each` instead.\n\n")
        append("| Token | Value |\n|---|---|\n")
        FlowTokens.tokens.filter { it.group == TokenInfo.Group.Item }.forEach { append("| `${it.text}` | ${it.en} |\n") }
        append("\n`{item.index}` counts from 1. Metadata tokens are empty when the file has none. In metadata and date tokens, characters a file name ")
        append("can't hold (`<>:\"/\\|?*`) become `_`, so `{item.modified:HH:mm}` gives `12_30`.\n\n")
        append("## Conditions (`if`, `text.test`)\n\n")
        append("`if` compares `subject` with `value` (both may use tokens) using `op`: ${FlowCompare.ops.joinToString(" ") { "`$it`" }}.\n\n")
        append("- When both sides are numbers they compare as numbers (`10 > 9`); otherwise as text, ignoring case unless `ignoreCase` is false.\n")
        append("- `equals` / `notEquals` ignore spaces and line breaks around both sides. `contains`, `startsWith`, `endsWith` don't trim.\n")
        append("- `matches` is a regular expression found anywhere in the subject (anchor with `^…\$`). `isEmpty` / `notEmpty` ignore `value` and treat blank text as empty.\n")
        append("- The subject is one text: `{first}` for the first value, `{input}` for all of them joined by line breaks.\n\n")
        append("## Search syntax (`query` arguments)\n\n")
        append("Arguments of type `query` (`files.filter`, `files.list`) use the file search syntax. Only these filters exist; ")
        append("any other `word:` (e.g. `kind:file`, `type:folder`, `name:x`) is searched as plain text in the name and matches nothing.\n\n")
        append("- Words separated by spaces must all match (AND); `a | b` matches either; `!word` (or `!ext:tmp`) excludes; `\"quoted text\"` keeps spaces\n")
        append("- Plain text matches part of the name, ignoring case; `*` and `?` wildcards match the whole name (`*.log`, `IMG_????.jpg`); text with a `\\` matches the full path\n")
        SearchQuery.hints.forEach { h -> append("- `${h.text}` — ${h.en}\n") }
        append("- `file:` and `folder:` take no value: `file: ext:pdf`, not `file:pdf`\n\n")
        append(SAFETY_SPEC)
        append("## Recipes (proven designs; start from these)\n\n")
        recipes.forEach { (title, json) -> append("### ").append(title).append("\n\n```json\n").append(json).append("\n```\n\n") }
        append("Notes for video and audio:\n\n")
        append("- One `video.convert` does format, codec, quality **and** resolution (`height`) in a single encode. Don't chain `video.scale` + `video.convert` (two lossy encodes), ")
        append("and don't branch with `if` per resolution: pass the choice as `{var.res}` (\"720p\" and \"720\" both work).\n")
        append("- To let the person pick from fixed options while running: `text.set` (`input: \"nothing\"`, one option per line) → `text.split` → `ui.choose` with `saveAs`. ")
        append("Keep the files in a variable first (`saveAs: \"videos\"`) and give the video step `input: \"var:videos\"`.\n")
        append("- `name` / `to` / `output` of the video actions are *per item*: `{item.stem}`, `{item.parent}` work there, mixed with `{var.x}`. Don't put `{item…}` in `if` or `text.set`.\n")
        append("- ts → mp4 / mkv → mp4: `video.convert` with codec `auto` copies the streams (seconds, no loss). Sending limits: `video.compress` with `method: size`.\n\n")
        append("## Actions\n\n")
        all.groupBy { it.category }.forEach { (cat, acts) ->
            append("### ${cat.replace('|', '/')}\n\n")
            acts.forEach { a ->
                val outText = if (a.outputFor != null) "depends on the arguments" else a.output.en
                append("- **`${a.id}`** — ${a.en} / ${a.zh}${if (a.changes) " *(changes files or the system; skipped in a dry run)*" else ""}. ${a.descEn}  \n")
                append("  Input: *${a.input.en}* → Output: *$outText*\n")
                a.args.forEach { g -> append("  - `${g.id}` (${g.type}${if (g.choices.isNotEmpty()) ": " + g.choices.joinToString("|") else ""}) ${g.en}${g.default.let { d -> if (d is String && d.isEmpty() || d is List<*>) "" else ", default `$d`" }}${if (g.id in perItemArgs[a.id].orEmpty()) " — *per item*: `{item…}` tokens work here" else ""}\n") }
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
        FlowTrust.loaded(ok)
    }

    /** Tests only: shortcuts that exist without being saved. */
    internal var testFlows: List<Flow> = emptyList()

    fun all(): List<Flow> = mine + testFlows + samples()

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
                steps = listOf(FlowStep("files.selected"), FlowStep("file.hash", mapOf("format" to "hash+name")), FlowStep("text.join"), FlowStep("clipboard.set"),
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
            Flow("builtin:mail-files", if (zh) "以郵件寄出選取的檔案" else "Email the selected files",
                if (zh) "詢問收件者，把選取的檔案附加到郵件草稿（用預設郵件程式開啟）。示範「存成變數」與「輸入來源」。" else "Asks for a recipient and attaches the selection to a mail draft. Shows Save as variable and Works on.", "mail",
                steps = listOf(
                    FlowStep("files.selected", saveAs = "files"),
                    FlowStep("files.filter", mapOf("query" to "file:")),
                    FlowStep("stop.if", mapOf("when" to "empty", "message" to if (zh) "請先選取檔案" else "Select some files first")),
                    FlowStep("ui.input", mapOf("label" to if (zh) "收件者電子郵件" else "Recipient's email", "default" to ""), saveAs = "to"),
                    FlowStep("email.send", mapOf("to" to "{var.to}", "subject" to if (zh) "{count} 個檔案" else "{count} files",
                        "body" to if (zh) "附件：\n{input}" else "Attached:\n{input}", "method" to "draft"), input = "var:files"),
                ), builtIn = true),
            Flow("builtin:focus", if (zh) "專注 25 分鐘" else "Focus for 25 minutes",
                if (zh) "關閉通知橫幅並降低音量，倒數 25 分鐘（按任意鍵提前結束），結束後恢復並提醒。" else "Banners off and volume down for 25 minutes (any key ends early), then restores and reminds you.", "timer",
                accepts = listOf("none"),
                steps = listOf(
                    FlowStep("volume", mapOf("op" to "get"), saveAs = "volume"),
                    FlowStep("dnd", mapOf("op" to "on")),
                    FlowStep("volume", mapOf("op" to "set", "value" to 15)),
                    FlowStep("wait.skippable", mapOf("seconds" to 1500, "message" to if (zh) "專注中…按任意鍵提前結束" else "Focusing… press any key to end early")),
                    FlowStep("dnd", mapOf("op" to "off")),
                    FlowStep("volume", mapOf("op" to "set", "value" to "{var.volume}")),
                    FlowStep("sound.play", mapOf("sound" to "asterisk")),
                    FlowStep("speak", mapOf("text" to if (zh) "時間到，休息一下吧" else "Time's up, take a break")),
                ), builtIn = true),
            Flow("builtin:cli", if (zh) "探索程式的命令列" else "Explore a program's command line",
                if (zh) "找出程式的命令列選項，選一個執行並顯示輸出。" else "Finds a program's command-line options, runs the one you pick and shows the output.", "terminal",
                accepts = listOf("none"),
                steps = listOf(
                    FlowStep("ui.input", mapOf("label" to if (zh) "程式名稱（例如 winget、git、ping）" else "Program (e.g. winget, git, ping)", "default" to "winget"), saveAs = "app"),
                    FlowStep("app.cli", mapOf("app" to "{var.app}", "output" to "options")),
                    FlowStep("ui.choose", mapOf("title" to if (zh) "要執行哪個選項？" else "Which option?")),
                    FlowStep("text.find", mapOf("find" to "^[^\\s,|=\\[<]+", "regex" to true)),
                    FlowStep("app.launch", mapOf("app" to "{var.app}", "args" to "{first}", "wait" to true, "output" to "output", "timeout" to 60)),
                    FlowStep("debug.show", mapOf("title" to "{var.app} {first}")),
                ), builtIn = true),
            Flow("builtin:api", if (zh) "讀取網路 API（JSON）" else "Read a web API (JSON)",
                if (zh) "呼叫 GitHub API，取出專案的星星數與說明。示範 HTTP 與字典。" else "Calls the GitHub API and picks out the stars and description. Shows HTTP and dictionaries.", "web",
                accepts = listOf("none"),
                steps = listOf(
                    FlowStep("http.request", mapOf("url" to "https://api.github.com/repos/creamgod45/FilesKmp", "headers" to "Accept: application/vnd.github+json", "output" to "json"), saveAs = "repo"),
                    FlowStep("json.get", mapOf("path" to "stargazers_count"), saveAs = "stars"),
                    FlowStep("json.get", mapOf("path" to "description"), input = "var:repo"),
                    FlowStep("text.template", mapOf("template" to "★ {var.stars} — {item}")),
                    FlowStep("debug.show", mapOf("title" to "FilesKmp")),
                ), builtIn = true),
            Flow("builtin:battery", if (zh) "電量不足時提醒" else "Low battery reminder",
                if (zh) "電量低於 20% 且沒有充電時，顯示通知並朗讀。" else "Under 20% and not charging: a notification and a spoken reminder.", "battery",
                accepts = listOf("none"),
                steps = listOf(
                    FlowStep("battery", mapOf("get" to "charging"), saveAs = "charging"),
                    FlowStep("battery", mapOf("get" to "percent")),
                    FlowStep("if", mapOf("subject" to "{first}", "op" to "<", "value" to "20", "then" to listOf(
                        FlowStep("if", mapOf("subject" to "{var.charging}", "op" to "equals", "value" to "false", "then" to listOf(
                            FlowStep("notify", mapOf("title" to if (zh) "電量不足" else "Battery low", "text" to if (zh) "剩下 {first}%" else "{first}% left")),
                            FlowStep("speak", mapOf("text" to if (zh) "電量剩下 {first}%，請接上電源" else "Battery at {first} percent, please plug in")),
                        ))),
                    ))),
                ), builtIn = true),
            Flow("builtin:screenshot", if (zh) "螢幕截圖並顯示" else "Screenshot and show it",
                if (zh) "擷取所有螢幕存到「圖片\\Screenshots」，複製到剪貼簿並在此顯示。" else "Captures every screen to Pictures\\Screenshots, copies it and shows it here.", "photo",
                accepts = listOf("none"),
                steps = listOf(FlowStep("screenshot", mapOf("copy" to true)), FlowStep("sound.play", mapOf("sound" to "asterisk", "wait" to false)), FlowStep("app.navigate")), builtIn = true),
        )
    }
}
