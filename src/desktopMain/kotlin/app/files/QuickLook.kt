package app.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** What the quick preview shows for one file. */
sealed class QlContent {
    class Picture(val image: ImageBitmap) : QlContent()
    class Text(val lines: List<String>, val truncated: Boolean) : QlContent()
    /** Markdown: the rendered page, with the source one click away. */
    class Markdown(val blocks: List<MdBlock>, val source: Text, val dir: File?) : QlContent()
    /** CSV or a workbook: sheets of rows ([total] = rows in the file, more than shown when large). */
    class Table(val sheets: List<Sheet>) : QlContent()
    /** Video or sound: played here when [playable] (MediaPlayer.kt), else its poster and the default player. */
    class Media(val poster: ImageBitmap?, val video: Boolean, val playable: Boolean = false) : QlContent()
    class Message(val text: String) : QlContent()
    class Sheet(val name: String, val rows: List<List<String>>, val total: Int)
}

/**
 * 快速預覽 (Space): pictures, text and code, CSV / TSV, Excel workbooks (.xlsx / .xlsm), and a
 * poster frame for videos and music. Read on a background thread, limited in size so a huge file
 * never stalls the app.
 */
object QuickLook {
    private const val TEXT_MAX_BYTES = 1 shl 20          // 1 MiB of text
    private const val TABLE_MAX_BYTES = 8 shl 20         // 8 MiB of CSV
    private const val TABLE_MAX_ROWS = 2000
    private const val SHEET_MAX = 12
    private val audioExt = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus")
    private val moreText = setOf("tsv", "sql", "sh", "go", "mod", "sum", "gitignore", "gitattributes", "editorconfig", "env",
        "vue", "jsx", "tsx", "scss", "less", "swift", "dart", "lua", "rb", "php", "pl", "r", "m", "mm", "hpp", "cc", "conf",
        "csproj", "sln", "vb", "fs", "fsx", "xaml", "plist", "reg", "srt", "vtt", "license", "patch", "diff", "lock", "svg")

    suspend fun load(item: FileItem): QlContent = withContext(Dispatchers.IO) {
        try { read(item) } catch (e: Throwable) { QlContent.Message(e.message ?: S.qlNoPreview) }
    }

    private suspend fun read(item: FileItem): QlContent {
        val f = File(item.path)
        val ext = item.ext
        return when {
            item.isDir -> QlContent.Message(S.items(f.list()?.size ?: 0))
            ext in FsExtra.imageExt && f.length() < 8 * M.PREVIEW_MAX_BYTES ->
                org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap().let { QlContent.Picture(it) }
            ext == "md" || ext == "markdown" || ext == "mdx" || ext == "mkd" -> {
                val src = text(f) as QlContent.Text
                QlContent.Markdown(Markdown.parse(src.lines.joinToString("\n")), src, f.parentFile)
            }
            ext == "csv" || ext == "tsv" -> csv(f, if (ext == "tsv") '\t' else null)
            ext == "xlsx" || ext == "xlsm" -> xlsx(f)
            ext == "xls" -> QlContent.Table(Xls.read(f, TABLE_MAX_ROWS, SHEET_MAX).map { QlContent.Sheet(it.name, it.rows, it.total) }.ifEmpty { return QlContent.Message(S.qlNoPreview) })
            ext in Thumbnails.videoExt || ext in audioExt -> QlContent.Media(Thumbnails.get(item, M.THUMB_LARGE), ext in Thumbnails.videoExt, playable = true)
            ext in FsExtra.textExt || ext in moreText || ext.isEmpty() || looksLikeText(f) -> text(f)
            Thumbnails.supports(item) -> QlContent.Media(Thumbnails.get(item, M.THUMB_LARGE), false)
            else -> QlContent.Message(S.qlNoPreview)
        }
    }

    /** The first bytes contain no NULs: treat it as text. */
    private fun looksLikeText(f: File): Boolean = try {
        f.inputStream().use { val b = it.readNBytes(4096); b.isNotEmpty() && b.none { x -> x == 0.toByte() } }
    } catch (_: Exception) { false }

    /** UTF-8 (with or without BOM) or UTF-16 by BOM; otherwise Windows' own code page (Big5 here). */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        return try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            // A cut in the middle of a UTF-8 character at the end isn't a reason to give up on UTF-8.
            val trimmed = runCatching {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, (bytes.size - 3).coerceAtLeast(0))).toString()
            }.getOrNull()
            trimmed ?: String(bytes, Charset.forName(System.getProperty("native.encoding") ?: "MS950", ))
        }
    }

    private fun head(f: File, max: Int): Pair<ByteArray, Boolean> =
        f.inputStream().use { val b = it.readNBytes(max); b to (f.length() > b.size) }

    private fun text(f: File): QlContent {
        val (bytes, cut) = head(f, TEXT_MAX_BYTES)
        return QlContent.Text(decode(bytes).replace("\r\n", "\n").split('\n'), cut)
    }

    /** CSV with quoted fields ("a, b", "say ""hi"""), separator guessed from the first line. */
    private fun csv(f: File, sep: Char?): QlContent {
        val (bytes, cut) = head(f, TABLE_MAX_BYTES)
        val text = decode(bytes)
        val first = text.substringBefore('\n')
        val d = sep ?: listOf(',', ';', '\t', '|').maxBy { c -> first.count { it == c } }
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        var total = 0
        fun endRow() { row.add(cell.toString()); cell.clear(); total++; if (rows.size < TABLE_MAX_ROWS) rows.add(row); row = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                !quoted && c == d -> { row.add(cell.toString()); cell.clear() }
                !quoted && c == '\n' -> endRow()
                !quoted && c == '\r' -> {}
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return QlContent.Table(listOf(QlContent.Sheet(f.name + if (cut) " …" else "", rows, total)))
    }

    /** An Office Open XML workbook: sheet names from workbook.xml, cells from each sheet's XML. */
    private fun xlsx(f: File): QlContent = java.util.zip.ZipFile(f).use { zip ->
        fun xml(name: String): javax.xml.stream.XMLStreamReader? = zip.getEntry(name)?.let { e ->
            javax.xml.stream.XMLInputFactory.newInstance().apply {
                setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false)
                setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }.createXMLStreamReader(zip.getInputStream(e), "UTF-8")
        }
        val START = javax.xml.stream.XMLStreamConstants.START_ELEMENT
        val END = javax.xml.stream.XMLStreamConstants.END_ELEMENT
        val CHARS = javax.xml.stream.XMLStreamConstants.CHARACTERS
        // Shared strings: <si> holds one or more <t> runs.
        val shared = ArrayList<String>()
        xml("xl/sharedStrings.xml")?.let { r ->
            val sb = StringBuilder(); var inSi = false; var inT = false
            while (r.hasNext()) {
                when (r.next()) {
                    START -> when (r.localName) { "si" -> { inSi = true; sb.clear() }; "t" -> inT = inSi; "rPh" -> inT = false }
                    CHARS -> if (inT) sb.append(r.text)
                    END -> when (r.localName) { "t" -> inT = false; "si" -> { shared.add(sb.toString()); inSi = false } }
                }
            }
        }
        // Sheet name → part, through the workbook's relationships.
        val rels = HashMap<String, String>()
        xml("xl/_rels/workbook.xml.rels")?.let { r ->
            while (r.hasNext()) if (r.next() == START && r.localName == "Relationship")
                rels[r.getAttributeValue(null, "Id")] = r.getAttributeValue(null, "Target")
        }
        val sheets = ArrayList<Pair<String, String>>()
        xml("xl/workbook.xml")?.let { r ->
            while (r.hasNext()) if (r.next() == START && r.localName == "sheet") {
                val name = r.getAttributeValue(null, "name") ?: "Sheet"
                val rid = (0 until r.attributeCount).firstOrNull { r.getAttributeLocalName(it) == "id" }?.let { r.getAttributeValue(it) }
                val target = rels[rid]?.let { t -> if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t } ?: continue
                sheets += name to target
            }
        }
        val out = sheets.take(SHEET_MAX).map { (name, part) ->
            val rows = ArrayList<List<String>>()
            var total = 0
            xml(part)?.let { r ->
                var row = ArrayList<String>()
                var col = 0; var type = ""; val v = StringBuilder(); var inV = false
                while (r.hasNext()) {
                    when (r.next()) {
                        START -> when (r.localName) {
                            "row" -> { row = ArrayList() }
                            "c" -> { col = colIndex(r.getAttributeValue(null, "r")) ?: row.size; type = r.getAttributeValue(null, "t") ?: ""; v.clear() }
                            "v", "t" -> inV = true
                        }
                        CHARS -> if (inV) v.append(r.text)
                        END -> when (r.localName) {
                            "v", "t" -> inV = false
                            "c" -> {
                                val raw = v.toString()
                                val text = when (type) {
                                    "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                    "b" -> if (raw == "1") "TRUE" else "FALSE"
                                    "", "n" -> number(raw)
                                    else -> raw
                                }
                                while (row.size < col) row.add("")
                                if (col < 512) row.add(text)
                            }
                            "row" -> { total++; if (rows.size < TABLE_MAX_ROWS) rows.add(row) }
                        }
                    }
                }
            }
            QlContent.Sheet(name, rows, total)
        }
        if (out.isEmpty()) QlContent.Message(S.qlNoPreview) else QlContent.Table(out)
    }

    /** "C12" → 2. */
    private fun colIndex(ref: String?): Int? {
        if (ref == null) return null
        var n = 0; var any = false
        for (ch in ref) { if (ch in 'A'..'Z') { n = n * 26 + (ch - 'A' + 1); any = true } else break }
        return if (any) n - 1 else null
    }

    /** Excel stores 3 as "3" and 0.1 as "0.10000000000000001": show them the short way. */
    private fun number(raw: String): String = raw.toDoubleOrNull()?.let { d ->
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    } ?: raw

    fun columnName(i: Int): String {
        var n = i + 1; val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
        return sb.toString()
    }
}

/** The 快速預覽 window over the app. ← → go through the folder, Space / Esc close, Enter opens. */
@Composable
fun QuickLookDialog(s: AppState, path: String) {
    val p = LocalPalette.current
    val item = remember(path, s.tab.raw) { s.tab.raw.firstOrNull { it.path == path } ?: FsExtra.item(path) }
    var content by remember(path) { mutableStateOf<QlContent?>(null) }
    LaunchedEffect(path) { content = item?.let { QuickLook.load(it) } ?: QlContent.Message(S.qlNoPreview) }
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxSize().background(Color(0x80000000)).pointerInput(Unit) { detectTapGestures2 { s.quickLook = null } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.84f).fillMaxHeight(0.88f).softShadow(shape, 22.dp, strength = 1.4f).clip(shape).background(p.solid, shape)
                .border(1.dp, p.controlStroke, shape).pointerInput(Unit) { detectTapGestures2 { } }
        ) {
            // Title bar: icon, name, size · date, open, close.
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item != null) FileIcon(item, 22.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    T(item?.name ?: File(path).name, size = 14.sp, weight = FontWeight.SemiBold)
                    if (item != null) T(listOfNotNull(if (!item.isDir && item.size >= 0) Fs.sizeText(item.size) else null, Fs.dateTimeText(item.modified).ifEmpty { null }, item.typeName).joinToString("  ·  "),
                        size = 11.sp, color = p.textSecondary)
                }
                DialogButton(S.qlOpen, primary = false) { s.quickLook = null; if (item?.isDir == true) s.openFolder(path) else Fs.open(path) }
                Spacer(Modifier.width(6.dp))
                IconBtn(Icons.Outlined.Close, S.close + " (Esc)", { s.quickLook = null })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val c = content) {
                    null -> T(S.loading, Modifier.align(Alignment.Center), color = p.textSecondary)
                    is QlContent.Picture -> Image(c.image, null, Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit)
                    is QlContent.Text -> TextPreview(c)
                    is QlContent.Markdown -> {
                        var source by remember(path) { mutableStateOf(false) }
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Hover(Modifier.height(28.dp), onClick = { source = false }, selected = !source) { T(S.mdPreview, Modifier.align(Alignment.Center).padding(horizontal = 10.dp), size = 12.sp) }
                                Hover(Modifier.height(28.dp), onClick = { source = true }, selected = source) { T(S.mdSource, Modifier.align(Alignment.Center).padding(horizontal = 10.dp), size = 12.sp) }
                            }
                            Box(Modifier.weight(1f)) { if (source) TextPreview(c.source) else MarkdownView(c.blocks, c.dir) }
                        }
                    }
                    is QlContent.Table -> TablePreview(c)
                    is QlContent.Media -> if (c.playable) MediaPlayerView(path, c.video, c.poster, item, s.translucentActive) { s.quickLook = null; Fs.open(path) }
                        else MediaPreview(c, item) { Fs.open(path) }
                    is QlContent.Message -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (item != null) FileIcon(item, 96.dp)
                        Spacer(Modifier.height(12.dp))
                        T(c.text, color = p.textSecondary, maxLines = 3)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
            T(if (S.zh) "← → 上一個／下一個   ·   Space / Esc 關閉   ·   Enter 開啟" else "← → previous / next   ·   Space / Esc close   ·   Enter open",
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp), size = 11.sp, color = p.textTertiary)
        }
    }
}

@Composable
private fun TextPreview(c: QlContent.Text) {
    val p = LocalPalette.current
    val numW = (c.lines.size.toString().length * 8 + 12).dp
    Column(Modifier.fillMaxSize()) {
        if (c.truncated) T(S.qlTruncated, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), size = 11.sp, color = p.accentText)
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(c.lines) { i, line ->
                    Row {
                        T("${i + 1}", Modifier.width(numW).padding(end = 10.dp), size = 12.sp, color = p.textTertiary, fontFamily = FontFamily.Monospace)
                        T(line.replace("\t", "    ").ifEmpty { " " }, size = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun TablePreview(c: QlContent.Table) {
    val p = LocalPalette.current
    var sheet by remember(c) { mutableStateOf(0) }
    val sh = c.sheets[sheet.coerceIn(0, c.sheets.size - 1)]
    val cols = sh.rows.maxOfOrNull { it.size } ?: 0
    val cellW = 140.dp
    Column(Modifier.fillMaxSize()) {
        if (c.sheets.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            c.sheets.forEachIndexed { i, s ->
                Hover(Modifier.height(28.dp), onClick = { sheet = i }, selected = i == sheet) {
                    T(s.name, Modifier.align(Alignment.Center).padding(horizontal = 10.dp), size = 12.sp)
                }
            }
        }
        if (sh.total > sh.rows.size) T(S.qlRowsShown(sh.rows.size, sh.total), Modifier.padding(horizontal = 14.dp, vertical = 4.dp), size = 11.sp, color = p.accentText)
        val hs = rememberScrollState()
        Column(Modifier.fillMaxSize().horizontalScroll(hs)) {
            // Column letters, like a spreadsheet.
            Row(Modifier.background(p.control)) {
                Box(Modifier.width(48.dp).height(26.dp))
                for (ci in 0 until cols) Box(Modifier.width(cellW).height(26.dp).border(0.5.dp, p.divider), contentAlignment = Alignment.Center) {
                    T(QuickLook.columnName(ci), size = 11.sp, color = p.textSecondary)
                }
            }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxHeight()) {
                    itemsIndexed(sh.rows) { ri, row ->
                        Row(Modifier.background(if (ri % 2 == 1) p.hover else Color.Transparent)) {
                            Box(Modifier.width(48.dp).height(26.dp).background(p.control).border(0.5.dp, p.divider), contentAlignment = Alignment.Center) {
                                T("${ri + 1}", size = 11.sp, color = p.textSecondary)
                            }
                            for (ci in 0 until cols) Box(Modifier.width(cellW).height(26.dp).border(0.5.dp, p.divider).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                                T(row.getOrElse(ci) { "" }, size = 12.sp, weight = if (ri == 0) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaPreview(c: QlContent.Media, item: FileItem?, play: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        if (c.poster != null) Image(c.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (item != null) FileIcon(item, 128.dp)
        // Play: hands the file to the default player (the app has no media engine of its own).
        Box(
            Modifier.size(72.dp).softShadow(CircleShape, 10.dp).clip(CircleShape).background(Color(0xCC000000))
                .pointerInput(Unit) { detectTapGestures2 { play() } },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.PlayArrow, S.qlPlay, Modifier.size(40.dp), tint = Color.White) }
        T(S.qlPlay, Modifier.align(Alignment.BottomCenter), size = 12.sp, color = p.textSecondary)
    }
}
