package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** One token people can type inside { }: what is inserted, what it means, and its group. */
class TokenInfo(val key: String, private val zh: String, private val en: String, val group: Group) {
    enum class Group(private val zhName: String, private val enName: String) {
        Var("變數", "Variables"), Param("參數", "Parameters"), Values("目前的值", "Current values"),
        Item("逐一處理的檔案", "Each file"), Folders("資料夾", "Folders"), Other("其他", "Other");
        val text get() = tr(enName, zhName)
    }
    val description get() = tr(en, zh)
    val text get() = "{$key}"
}

/**
 * The tokens a shortcut's text fields understand (FlowRun.expand), which of them exist at a given
 * step (variables set by the steps before it, the shortcut's parameters), and a check of a text.
 */
object FlowTokens {
    private val builtIn = listOf(
        TokenInfo("input", "目前所有的值，一行一個", "All current values, one per line", TokenInfo.Group.Values),
        TokenInfo("first", "第一個值", "The first value", TokenInfo.Group.Values),
        TokenInfo("last", "最後一個值", "The last value", TokenInfo.Group.Values),
        TokenInfo("count", "值的數量", "How many values", TokenInfo.Group.Values),
        TokenInfo("item", "這個檔案的完整路徑", "This file's full path", TokenInfo.Group.Item),
        TokenInfo("item.name", "檔名（含副檔名）", "File name (with extension)", TokenInfo.Group.Item),
        TokenInfo("item.stem", "檔名（不含副檔名）", "Name without extension", TokenInfo.Group.Item),
        TokenInfo("item.ext", "副檔名", "Extension", TokenInfo.Group.Item),
        TokenInfo("item.parent", "所在資料夾的路徑", "Its folder's path", TokenInfo.Group.Item),
        TokenInfo("item.folder", "所在資料夾的名稱", "Its folder's name", TokenInfo.Group.Item),
        TokenInfo("item.index:000", "第幾個（補零到 3 位）", "Its number (zero-padded to 3)", TokenInfo.Group.Item),
        TokenInfo("item.size", "大小", "Size", TokenInfo.Group.Item),
        TokenInfo("item.modified:yyyy-MM-dd", "修改日期（可改格式）", "Date modified (any format)", TokenInfo.Group.Item),
        TokenInfo("item.taken:yyyy-MM-dd", "拍攝日期（相片）", "Date taken (photos)", TokenInfo.Group.Item),
        TokenInfo("item.width", "寬度（圖片／影片）", "Width (pictures / videos)", TokenInfo.Group.Item),
        TokenInfo("item.height", "高度（圖片／影片）", "Height (pictures / videos)", TokenInfo.Group.Item),
        TokenInfo("folder", "目前開啟的資料夾", "The open folder", TokenInfo.Group.Folders),
        TokenInfo("home", "使用者資料夾", "Your user folder", TokenInfo.Group.Folders),
        TokenInfo("desktop", "桌面", "Desktop", TokenInfo.Group.Folders),
        TokenInfo("documents", "文件", "Documents", TokenInfo.Group.Folders),
        TokenInfo("downloads", "下載", "Downloads", TokenInfo.Group.Folders),
        TokenInfo("pictures", "圖片", "Pictures", TokenInfo.Group.Folders),
        TokenInfo("music", "音樂", "Music", TokenInfo.Group.Folders),
        TokenInfo("videos", "影片", "Videos", TokenInfo.Group.Folders),
        TokenInfo("temp", "暫存資料夾", "Temporary folder", TokenInfo.Group.Folders),
        TokenInfo("date:yyyy-MM-dd", "今天的日期（可改格式，例如 yyyyMMdd-HHmmss）", "Today's date (any format, e.g. yyyyMMdd-HHmmss)", TokenInfo.Group.Other),
        TokenInfo("clipboard", "剪貼簿的文字", "The text on the clipboard", TokenInfo.Group.Other),
        TokenInfo("newline", "換行", "A line break", TokenInfo.Group.Other),
        TokenInfo("tab", "Tab 字元", "A tab character", TokenInfo.Group.Other),
        TokenInfo("env.USERNAME", "環境變數（把 USERNAME 換成需要的名稱）", "An environment variable (replace USERNAME)", TokenInfo.Group.Other),
    )
    private val plainKeys = builtIn.map { it.key.substringBefore(':') }.filter { '.' !in it }.toSet()

    /** Variables some actions set by themselves, and the actions whose inner steps get them. */
    val setBy: Map<String, List<String>> = mapOf("wait.skippable" to listOf("skipped"), "volume" to listOf("muted"))
    val insideOf: Map<String, List<String>> = mapOf("each" to listOf("item", "index"), "repeat" to listOf("index"))

    /** Variables the steps set (saveAs, Set variable, and actions that set one themselves), in order. */
    fun varsSetBy(steps: List<FlowStep>): List<String> = steps.filter { it.enabled }.flatMap { st ->
        @Suppress("UNCHECKED_CAST") val inner = st.args.values.filterIsInstance<List<*>>().filter { l -> l.isNotEmpty() && l.all { it is FlowStep } }
            .flatMap { varsSetBy(it as List<FlowStep>) }
        listOfNotNull(st.saveAs.trim().takeIf { it.isNotEmpty() }, if (st.action == "var.set") (st.args["name"] ?: "saved").toString() else null) +
            setBy[st.action].orEmpty() + inner
    }.distinct()

    /** Everything that can be typed at a step that sees [params] and [vars]. */
    fun catalog(params: List<String>, vars: List<String>): List<TokenInfo> =
        vars.distinct().map { TokenInfo("var.$it", "變數（前面的步驟設定的）", "Variable (set by an earlier step)", TokenInfo.Group.Var) } +
            params.distinct().map { TokenInfo("param.$it", "執行時詢問的參數", "Parameter asked when the shortcut runs", TokenInfo.Group.Param) } +
            builtIn

    private val tokenRx = Regex("\\{([a-zA-Z][\\w.]*)(?::([^}]*))?}")

    /** Problems in [text] at a step that sees [params] and [vars]: unknown variables, parameters or tokens, a missing }. */
    fun check(text: String, params: List<String>, vars: List<String>): List<String> {
        val out = ArrayList<String>()
        tokenRx.findAll(text).forEach { m ->
            val key = m.groupValues[1]
            when {
                key.startsWith("var.") -> key.removePrefix("var.").let { v ->
                    if (v !in vars) out += tr("Variable “$v” isn't set before this step (set it with “Also save result as” on an earlier step).",
                        "變數「$v」在這一步之前沒有設定（請在前面的步驟用「結果存成變數」設定）。")
                }
                key.startsWith("param.") -> key.removePrefix("param.").let { v ->
                    if (v !in params) out += tr("There's no parameter “$v” (parameters: ${params.joinToString().ifEmpty { "none" }}).",
                        "沒有參數「$v」（目前的參數：${params.joinToString("、").ifEmpty { "無" }}）。")
                }
                key.startsWith("item.") || key.startsWith("env.") || key in plainKeys -> {}
                else -> out += tr("Unknown token {$key}: it will stay as typed. Type { to see the list.", "不認識的標記 {$key}，會原樣保留。輸入 { 可以看到所有標記。")
            }
        }
        // "{var.x" with no closing brace.
        if (Regex("\\{[a-zA-Z][\\w.:-]*(?![\\w.:}-])").containsMatchIn(text.replace(tokenRx, "")))
            out += tr("A token is missing its closing }.", "有標記少了結尾的 }。")
        return out.distinct()
    }

    /** The token being typed just left of [caret] ("{va" → "va"), or null. */
    fun typing(text: String, caret: Int): String? =
        Regex("\\{([a-zA-Z][\\w.:-]*)?$").find(text.substring(0, caret.coerceIn(0, text.length)))?.groupValues?.get(1)
}

/** Which parameters and variables a step's fields can use (provided by the shortcut editor). */
class FlowFieldContext(val params: List<String>, val vars: List<String>)
val LocalFlowFields = compositionLocalOf { FlowFieldContext(emptyList(), emptyList()) }

/**
 * A text field for shortcut steps: typing { lists the tokens and variables usable here (↑ ↓ to pick,
 * Enter or Tab to insert, Esc to close), the { } button lists them all, and problems show under it.
 */
@Composable
fun TokenField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, multiline: Boolean = false, mono: Boolean = false, placeholder: String = "") {
    val p = LocalPalette.current
    val app = LocalAppState.current
    val ctx = LocalFlowFields.current
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (tfv.text != value) tfv = TextFieldValue(value, TextRange(value.length.coerceAtMost(tfv.selection.end)))
    var focused by remember { mutableStateOf(false) }
    var listAll by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf<String?>(null) }
    var sel by remember { mutableStateOf(0) }
    var height by remember { mutableStateOf(0) }
    val catalog = remember(ctx.params, ctx.vars) { FlowTokens.catalog(ctx.params, ctx.vars) }
    val typed = FlowTokens.typing(tfv.text, tfv.selection.end)
    val prefix = if (listAll) "" else typed
    val open = listAll || (focused && typed != null && dismissed != tfv.text)
    val shown = if (!open) emptyList() else catalog.filter { prefix.isNullOrEmpty() || it.key.contains(prefix, true) || it.description.contains(prefix, true) }
    if (sel >= shown.size) sel = 0

    fun insert(t: TokenInfo) {
        val caret = tfv.selection.end
        val text = tfv.text
        val start = if (!listAll && typed != null) caret - typed.length - 1 else caret
        val after = text.substring(caret).let { if (it.startsWith("}")) it.drop(1) else it }
        val ins = t.text
        tfv = TextFieldValue(text.substring(0, start) + ins + after, TextRange(start + ins.length))
        onChange(tfv.text); listAll = false; dismissed = null
    }

    Column(modifier) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f)) {
                if (tfv.text.isEmpty() && placeholder.isNotEmpty()) T(placeholder, Modifier.padding(8.dp), size = 13.sp, color = p.textTertiary)
                BasicTextField(
                    tfv, { v -> tfv = v; if (v.text != value) onChange(v.text); if (v.text != dismissed) dismissed = null },
                    singleLine = !multiline,
                    textStyle = TextStyle(color = p.text, fontSize = 13.sp, fontFamily = if (mono) FontFamily.Monospace else null),
                    cursorBrush = SolidColor(p.text),
                    modifier = Modifier.fillMaxWidth().then(if (multiline) Modifier.heightIn(min = 70.dp) else Modifier.height(32.dp))
                        .background(if (p.dark) Color(0xFF1F1F1F) else Color.White, RoundedCornerShape(4.dp))
                        .border(1.dp, if (open) p.accent else p.controlStroke, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = if (multiline) 8.dp else 7.dp)
                        .onSizeChanged { height = it.height }
                        .onFocusChanged { focused = it.isFocused; app?.textFocus = it.isFocused; if (it.isFocused) listAll = false }
                        .onPreviewKeyEvent { e ->
                            if (!open || shown.isEmpty() || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> { sel = (sel + 1) % shown.size; true }
                                Key.DirectionUp -> { sel = (sel - 1 + shown.size) % shown.size; true }
                                Key.Enter, Key.NumPadEnter, Key.Tab -> { insert(shown[sel]); true }
                                Key.Escape -> { dismissed = tfv.text; listAll = false; true }
                                else -> false
                            }
                        },
                )
                if (open && shown.isNotEmpty()) Popup(offset = IntOffset(0, height + 2), properties = PopupProperties(focusable = false)) {
                    Column(Modifier.width(420.dp).heightIn(max = 280.dp).shadow8().background(p.flyout, RoundedCornerShape(6.dp))
                        .border(1.dp, p.controlStroke, RoundedCornerShape(6.dp)).verticalScroll(rememberScrollState()).padding(4.dp)) {
                        var group: TokenInfo.Group? = null
                        shown.forEachIndexed { i, t ->
                            if (t.group != group) { group = t.group; T(t.group.text, Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp), size = 10.sp, color = p.textTertiary) }
                            Row(Modifier.fillMaxWidth().background(if (i == sel) p.accent.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(4.dp))
                                .clickable { insert(t) }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                T(t.text, Modifier.width(190.dp), size = 12.sp, fontFamily = FontFamily.Monospace, color = p.accentText)
                                T(t.description, Modifier.weight(1f), size = 11.sp, color = p.textSecondary, maxLines = 2)
                            }
                        }
                    }
                }
            }
            IconBtn(androidx.compose.material.icons.Icons.Outlined.DataObject, tr("Insert a token or variable", "插入標記或變數"),
                { listAll = !listAll }, width = 30.dp, height = 32.dp, iconSize = 15.dp, selected = listAll)
        }
        val problems = remember(tfv.text, ctx.params, ctx.vars) { FlowTokens.check(tfv.text, ctx.params, ctx.vars) }
        problems.forEach { T("⚠ $it", Modifier.padding(top = 2.dp), size = 11.sp, color = p.danger, maxLines = 2) }
    }
}
