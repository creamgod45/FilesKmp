package app.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Markdown blocks, parsed once per file (CommonMark's common parts plus GitHub tables and task lists). */
sealed class MdBlock {
    class Heading(val level: Int, val text: String) : MdBlock()
    class Paragraph(val text: String) : MdBlock()
    class Code(val lang: String, val text: String) : MdBlock()
    class Quote(val text: String) : MdBlock()
    /** [ordered] items start at [start]; [task] is null for plain items, else checked or not. */
    class ListItem(val depth: Int, val marker: String, val task: Boolean?, val text: String) : MdBlock()
    class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    class Picture(val alt: String, val src: String) : MdBlock()
    data object Rule : MdBlock()
}

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)$")
    private val image = Regex("^!\\[([^]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)\\s*$")
    private val tableSep = Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim()); para.clear() }
        var i = 0
        // Front matter (--- … ---) at the very top is metadata, not text.
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) i = end + 2
        }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush()
                    val fence = t.take(3)
                    val lang = t.drop(3).trim()
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) { body += lines[i]; i++ }
                    out += MdBlock.Code(lang, body.joinToString("\n"))
                }
                t.isEmpty() -> flush()
                heading.matches(t) -> { flush(); heading.find(t)!!.let { out += MdBlock.Heading(it.groupValues[1].length, it.groupValues[2]) } }
                Regex("^([-*_])(\\s*\\1){2,}$").matches(t) -> { flush(); out += MdBlock.Rule }
                // Setext headings: text underlined with === or ---.
                para.isNotEmpty() && Regex("^=+$").matches(t) -> { out += MdBlock.Heading(1, para.toString().trim()); para.clear() }
                para.isNotEmpty() && Regex("^-+$").matches(t) -> { out += MdBlock.Heading(2, para.toString().trim()); para.clear() }
                image.matches(t) -> { flush(); image.find(t)!!.let { out += MdBlock.Picture(it.groupValues[1], it.groupValues[2]) } }
                t.startsWith(">") -> {
                    flush()
                    val q = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { q += lines[i].trim().removePrefix(">").removePrefix(" "); i++ }
                    out += MdBlock.Quote(q.joinToString("\n")); continue
                }
                t.startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1].trim()) -> {
                    flush()
                    fun cells(l: String) = l.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
                    val header = cells(t)
                    i += 2
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i]); i++ }
                    out += MdBlock.Table(header, rows); continue
                }
                bullet.matches(line) -> {
                    flush()
                    val m = bullet.find(line)!!
                    val depth = m.groupValues[1].replace("\t", "    ").length / 2
                    val body = m.groupValues[3]
                    val tm = task.find(body)
                    val marker = m.groupValues[2].let { if (it[0].isDigit()) it else "•" }
                    out += if (tm != null) MdBlock.ListItem(depth, marker, tm.groupValues[1] != " ", tm.groupValues[2])
                        else MdBlock.ListItem(depth, marker, null, body)
                }
                // Indented code (4 spaces) outside a paragraph.
                para.isEmpty() && line.startsWith("    ") -> {
                    val body = ArrayList<String>()
                    while (i < lines.size && (lines[i].startsWith("    ") || lines[i].isBlank())) { body += lines[i].removePrefix("    "); i++ }
                    out += MdBlock.Code("", body.joinToString("\n").trimEnd()); continue
                }
                else -> { if (para.isNotEmpty()) para.append(if (line.endsWith("  ")) "\n" else " "); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    /** **bold**, *italic*, ~~strike~~, `code`, [links](url) and bare images inside a line. */
    fun inline(text: String, code: Color, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            fun span(open: String, close: String = open, style: SpanStyle): Boolean {
                if (!rest.startsWith(open)) return false
                val end = rest.indexOf(close, open.length)
                if (end <= open.length) return false
                withStyle(style) { append(inline(rest.substring(open.length, end), code, codeBg, link)) }
                i += end + close.length
                return true
            }
            when {
                rest.startsWith("\\") && rest.length > 1 -> { append(rest[1]); i += 2 }
                rest.startsWith("`") -> {
                    val end = rest.indexOf('`', 1)
                    if (end > 0) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = code, background = codeBg)) { append(" " + rest.substring(1, end) + " ") }; i += end + 1 }
                    else { append('`'); i++ }
                }
                span("**", style = SpanStyle(fontWeight = FontWeight.Bold)) -> {}
                span("__", style = SpanStyle(fontWeight = FontWeight.Bold)) -> {}
                span("~~", style = SpanStyle(textDecoration = TextDecoration.LineThrough)) -> {}
                span("*", style = SpanStyle(fontStyle = FontStyle.Italic)) -> {}
                rest.startsWith("_") && (i == 0 || !text[i - 1].isLetterOrDigit()) && span("_", style = SpanStyle(fontStyle = FontStyle.Italic)) -> {}
                rest.startsWith("[") || rest.startsWith("![") -> {
                    val m = Regex("^!?\\[([^]]*)]\\(([^)]*)\\)").find(rest)
                    if (m != null) {
                        withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
                        i += m.value.length
                    } else { append('['); i++ }
                }
                rest.startsWith("<") && Regex("^<https?://[^>]+>").containsMatchIn(rest) -> {
                    val end = rest.indexOf('>')
                    withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(rest.substring(1, end)) }
                    i += end + 1
                }
                else -> { append(rest[0]); i++ }
            }
        }
    }
}

/** The rendered page; pictures next to the file are drawn, remote ones show their alt text. */
@Composable
fun MarkdownView(blocks: List<MdBlock>, baseDir: File?) {
    val p = LocalPalette.current
    val codeBg = if (p.dark) Color(0x22FFFFFF) else Color(0x12000000)
    val codeFg = if (p.dark) Color(0xFFE6B673) else Color(0xFFB0361A)
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 32.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(blocks) { b ->
                when (b) {
                    is MdBlock.Heading -> Column(Modifier.padding(top = if (b.level <= 2) 10.dp else 4.dp)) {
                        Text(Markdown.inline(b.text, codeFg, codeBg, p.accentText), color = p.text, fontWeight = FontWeight.SemiBold,
                            fontSize = when (b.level) { 1 -> 28.sp; 2 -> 22.sp; 3 -> 18.sp; 4 -> 16.sp; else -> 14.sp }, lineHeight = when (b.level) { 1 -> 36.sp; 2 -> 30.sp; else -> 24.sp })
                        if (b.level <= 2) Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(1.dp).background(p.divider))
                    }
                    is MdBlock.Paragraph -> Text(Markdown.inline(b.text, codeFg, codeBg, p.accentText), color = p.text, fontSize = 14.sp, lineHeight = 22.sp)
                    is MdBlock.Code -> Column(Modifier.fillMaxWidth().background(codeBg, RoundedCornerShape(6.dp)).padding(12.dp)) {
                        if (b.lang.isNotEmpty()) T(b.lang, size = 10.sp, color = p.textTertiary)
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            Text(b.text, color = p.text, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(p.accent.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                        Text(Markdown.inline(b.text, codeFg, codeBg, p.accentText), Modifier.padding(start = 12.dp), color = p.textSecondary, fontSize = 14.sp, lineHeight = 22.sp)
                    }
                    is MdBlock.ListItem -> Row(Modifier.padding(start = (b.depth * 20).dp), verticalAlignment = Alignment.Top) {
                        if (b.task != null) Icon(if (b.task) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null,
                            Modifier.padding(top = 2.dp).size(16.dp), tint = if (b.task) p.accent else p.textSecondary)
                        else T(b.marker, Modifier.widthIn(min = 16.dp), size = 14.sp, color = p.textSecondary)
                        Spacer(Modifier.width(8.dp))
                        Text(Markdown.inline(b.text, codeFg, codeBg, p.accentText), color = p.text, fontSize = 14.sp, lineHeight = 22.sp)
                    }
                    is MdBlock.Table -> Box(Modifier.horizontalScroll(rememberScrollState())) {
                        Column(Modifier.border(1.dp, p.divider)) {
                            val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0)
                            (listOf(b.header) + b.rows).forEachIndexed { ri, row ->
                                Row(Modifier.background(if (ri == 0) codeBg else if (ri % 2 == 0) p.hover else Color.Transparent)) {
                                    for (ci in 0 until cols) Box(Modifier.width(160.dp).border(0.5.dp, p.divider).padding(8.dp, 6.dp)) {
                                        Text(Markdown.inline(row.getOrElse(ci) { "" }, codeFg, codeBg, p.accentText), color = p.text, fontSize = 13.sp,
                                            fontWeight = if (ri == 0) FontWeight.SemiBold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                    is MdBlock.Picture -> MdPicture(b, baseDir)
                    MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(p.divider))
                }
            }
        }
    }
}

@Composable
private fun MdPicture(b: MdBlock.Picture, baseDir: File?) {
    val p = LocalPalette.current
    var bmp by remember(b.src) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(b.src) {
        // Only pictures on this PC (relative to the file or absolute); nothing is downloaded.
        if (b.src.startsWith("http", true)) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(b.src).let { if (it.isAbsolute) it else File(baseDir, java.net.URLDecoder.decode(b.src, "UTF-8")) }
                if (f.isFile && f.length() < M.PREVIEW_MAX_BYTES) org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap() else null
            }.getOrNull()
        }
    }
    val img = bmp
    if (img != null) Image(img, b.alt, Modifier.widthIn(max = 720.dp).heightIn(max = 420.dp), contentScale = ContentScale.Fit)
    else T("🖼 " + b.alt.ifEmpty { b.src }, size = 13.sp, color = p.textSecondary)
}
