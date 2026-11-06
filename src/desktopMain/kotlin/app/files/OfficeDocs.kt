package app.files

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.CHARACTERS
import javax.xml.stream.XMLStreamConstants.END_ELEMENT
import javax.xml.stream.XMLStreamConstants.START_ELEMENT
import javax.xml.stream.XMLStreamReader

/**
 * 快速預覽 for office documents when Windows has no preview handler for them (no Microsoft Office,
 * or apps that only register themselves as the default program): the text of Word (.docx, .doc),
 * OpenDocument text (.odt) and presentations (.pptx, .odp) with headings, lists, bold / italic and
 * tables, and OpenDocument spreadsheets (.ods) as a table. Layout, pictures and fonts are left out.
 */
object OfficeDocs {
    private val wordExt = setOf("docx", "docm", "dotx", "dotm")
    private val odfTextExt = setOf("odt", "ott", "fodt")
    private val slideExt = setOf("pptx", "pptm", "ppsx", "potx")
    private val odfSlideExt = setOf("odp", "otp")
    private val odfSheetExt = setOf("ods", "ots")
    val exts = wordExt + odfTextExt + slideExt + odfSlideExt + odfSheetExt + "doc"

    private const val MAX_BLOCKS = 5000
    private const val MAX_COLS = 512

    fun read(f: File, ext: String, maxRows: Int, maxSheets: Int): QlContent = when (ext) {
        in wordExt -> doc(zip(f) { docx(it) })
        in odfTextExt, in odfSlideExt -> doc(zip(f) { z -> z.xml("content.xml")?.let { odfText(it) } ?: emptyList() })
        in slideExt -> doc(zip(f) { pptx(it) })
        in odfSheetExt -> zip(f) { z -> z.xml("content.xml")?.let { ods(it, maxRows, maxSheets) } ?: emptyList() }
            .let { if (it.isEmpty()) QlContent.Message(S.qlNoPreview) else QlContent.Table(it) }
        "doc" -> doc(WordBinary.read(f))
        else -> QlContent.Message(S.qlNoPreview)
    }

    private fun doc(blocks: List<MdBlock>) = if (blocks.isEmpty()) QlContent.Message(tr("This document has no text.", "這份文件沒有文字。"))
        else QlContent.Document(blocks)

    // ------------------------------------------------------------------ shared helpers

    private class Zip(val z: ZipFile) {
        fun xml(name: String): XMLStreamReader? = z.getEntry(name)?.let { e ->
            XMLInputFactory.newInstance().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                setProperty(XMLInputFactory.IS_COALESCING, true)
            }.createXMLStreamReader(z.getInputStream(e), "UTF-8")
        }
    }

    private fun <T> zip(f: File, block: (Zip) -> T): T = try {
        ZipFile(f).use { block(Zip(it)) }
    } catch (e: java.util.zip.ZipException) { error(tr("This file is damaged or isn't a document.", "這個檔案已損毀或不是文件。")) }

    private fun XMLStreamReader.attr(local: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeLocalName(it) == local }?.let { getAttributeValue(it) }

    /** Skips the element the reader is on, with everything inside it. */
    private fun XMLStreamReader.skipElement() {
        var depth = 1
        while (hasNext() && depth > 0) when (next()) { START_ELEMENT -> depth++; END_ELEMENT -> depth-- }
    }

    /** Text as written, with Markdown's markers escaped so the renderer shows them literally. */
    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) { if (c in "\\*_~`[<") append('\\'); append(c) }
    }

    /** Runs of text with their bold / italic, merged and turned into the renderer's inline Markdown. */
    private class Runs {
        private val sb = StringBuilder()
        private val cur = StringBuilder()
        private var b = false; private var i = false
        fun add(text: String, bold: Boolean, italic: Boolean) {
            if (text.isEmpty()) return
            if (cur.isNotEmpty() && (bold != b || italic != i)) flushRun()
            b = bold; i = italic; cur.append(text)
        }
        private fun flushRun() {
            val t = cur.toString(); cur.clear()
            if (t.isBlank() || (!b && !i)) { sb.append(esc(t)); return }
            // Markers hug the text: "**word** " rather than "**word **".
            val lead = t.takeWhile { it == ' ' }; val trail = t.takeLastWhile { it == ' ' }
            val mark = (if (b) "**" else "") + (if (i) "*" else "")
            sb.append(lead).append(mark).append(esc(t.trim(' '))).append(mark.reversed()).append(trail)
        }
        fun take(): String { flushRun(); val s = sb.toString(); sb.clear(); return s.trim() }
        fun isEmpty() = sb.isEmpty() && cur.isEmpty()
    }

    // ------------------------------------------------------------------ Word (.docx)

    /** Heading levels by style id, from styles.xml: "heading 1" names, Title, or an outline level. */
    private fun wordHeadings(z: Zip): Map<String, Int> {
        val out = HashMap<String, Int>()
        val r = z.xml("word/styles.xml") ?: return out
        var id: String? = null
        while (r.hasNext()) if (r.next() == START_ELEMENT) when (r.localName) {
            "style" -> id = r.attr("styleId")
            "name" -> { val n = r.attr("val")?.lowercase() ?: ""; val sid = id ?: continue
                Regex("^heading (\\d)$").find(n)?.let { out[sid] = it.groupValues[1].toInt() }
                if (n == "title") out[sid] = 1
                if (n == "subtitle") out[sid] = 2 }
            "outlineLvl" -> { val sid = id ?: continue; r.attr("val")?.toIntOrNull()?.let { if (it < 9) out.putIfAbsent(sid, it + 1) } }
        }
        return out
    }

    private fun docx(z: Zip): List<MdBlock> {
        val headings = wordHeadings(z)
        val r = z.xml("word/document.xml") ?: return emptyList()
        val out = ArrayList<MdBlock>()
        val runs = Runs()
        var pDepth = 0                       // a text box inside a paragraph has paragraphs of its own
        var style: String? = null; var listLevel = -1
        var bold = false; var italic = false; var inRPr = false; var inPPr = false
        // Tables (nested ones are folded into their cell's text).
        var tblDepth = 0
        var rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cell = StringBuilder()
        fun val0(): Boolean = r.attr("val").let { it == null || (it != "0" && it != "false" && it != "none") }
        while (r.hasNext() && out.size < MAX_BLOCKS) when (r.next()) {
            START_ELEMENT -> when (r.localName) {
                "Fallback", "instrText", "delText", "footnoteReference", "endnoteReference", "commentReference" -> r.skipElement()
                "p" -> if (pDepth++ == 0) { style = null; listLevel = -1 } else runs.add(" ", false, false)
                "pPr" -> inPPr = true
                "pStyle" -> if (inPPr && pDepth == 1) style = r.attr("val")
                "ilvl" -> if (inPPr && pDepth == 1) listLevel = r.attr("val")?.toIntOrNull() ?: 0
                "numPr" -> if (inPPr && pDepth == 1 && listLevel < 0) listLevel = 0
                "r" -> { bold = false; italic = false }
                "rPr" -> if (!inPPr) inRPr = true
                "b" -> if (inRPr) bold = val0()
                "i" -> if (inRPr) italic = val0()
                "t" -> runs.add(r.elementText, bold, italic)
                "tab" -> if (!inPPr) runs.add("    ", false, false)
                "br", "cr" -> runs.add("\n", false, false)
                "noBreakHyphen" -> runs.add("-", false, false)
                "tbl" -> if (tblDepth++ == 0) { rows = ArrayList() }
                "tr" -> if (tblDepth == 1) row = ArrayList()
                "tc" -> if (tblDepth == 1) cell.clear()
            }
            END_ELEMENT -> when (r.localName) {
                "pPr" -> inPPr = false
                "rPr" -> inRPr = false
                "p" -> if (--pDepth == 0) {
                    val text = runs.take()
                    if (tblDepth > 0) { if (text.isNotEmpty()) cell.append(if (cell.isEmpty()) "" else " ").append(text) }
                    else if (text.isNotEmpty()) {
                        val h = style?.let { headings[it] }
                        out += when {
                            h != null -> MdBlock.Heading(h.coerceIn(1, 6), text)
                            listLevel >= 0 -> MdBlock.ListItem(listLevel, "•", null, text)
                            else -> MdBlock.Paragraph(text)
                        }
                    }
                }
                "tc" -> if (tblDepth == 1) row.add(cell.toString().replace('\n', ' '))
                "tr" -> if (tblDepth == 1 && row.isNotEmpty()) rows.add(row)
                "tbl" -> if (--tblDepth == 0) table(rows)?.let { out += it }
            }
        }
        return out
    }

    private fun table(rows: List<List<String>>): MdBlock.Table? {
        val kept = rows.filter { r -> r.any { it.isNotBlank() } }
        if (kept.isEmpty()) return null
        return MdBlock.Table(kept.first(), kept.drop(1))
    }

    // ------------------------------------------------------------------ PowerPoint (.pptx)

    private fun pptx(z: Zip): List<MdBlock> {
        val rels = HashMap<String, String>()
        z.xml("ppt/_rels/presentation.xml.rels")?.let { r ->
            while (r.hasNext()) if (r.next() == START_ELEMENT && r.localName == "Relationship") rels[r.attr("Id") ?: ""] = r.attr("Target") ?: ""
        }
        val slides = ArrayList<String>()
        z.xml("ppt/presentation.xml")?.let { r ->
            // <p:sldId id="256" r:id="rId2">: the relationship is the namespaced one.
            while (r.hasNext()) if (r.next() == START_ELEMENT && r.localName == "sldId")
                (0 until r.attributeCount).firstOrNull { r.getAttributeLocalName(it) == "id" && !r.getAttributeNamespace(it).isNullOrEmpty() }
                    ?.let { rels[r.getAttributeValue(it)] }?.let { t -> slides += if (t.startsWith("/")) t.removePrefix("/") else "ppt/" + t }
        }
        val out = ArrayList<MdBlock>()
        slides.forEachIndexed { n, part ->
            if (out.size >= MAX_BLOCKS) return@forEachIndexed
            if (n > 0) out += MdBlock.Rule
            out += MdBlock.Heading(3, tr("Slide ${n + 1}", "投影片 ${n + 1}"))
            val r = z.xml(part) ?: return@forEachIndexed
            val runs = Runs()
            var title = false; var bold = false; var italic = false; var inRPr = false; var level = 0
            var tbl = false; var rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cell = StringBuilder()
            while (r.hasNext()) when (r.next()) {
                START_ELEMENT -> when (r.localName) {
                    "sp", "graphicFrame" -> title = false
                    "ph" -> r.attr("type").let { if (it == "title" || it == "ctrTitle") title = true }
                    "p" -> level = 0
                    "pPr" -> level = r.attr("lvl")?.toIntOrNull() ?: 0
                    "r", "fld" -> { bold = false; italic = false }
                    "rPr" -> { inRPr = true; bold = r.attr("b") == "1"; italic = r.attr("i") == "1" }
                    "t" -> runs.add(r.elementText, bold, italic)
                    "br" -> runs.add("\n", false, false)
                    "tbl" -> { tbl = true; rows = ArrayList() }
                    "tr" -> row = ArrayList()
                    "tc" -> cell.clear()
                }
                END_ELEMENT -> when (r.localName) {
                    "rPr" -> inRPr = false
                    "p" -> { val t = runs.take(); if (t.isNotEmpty()) when {
                        tbl -> cell.append(if (cell.isEmpty()) "" else " ").append(t)
                        title -> out += MdBlock.Heading(2, t)
                        level > 0 -> out += MdBlock.ListItem(level - 1, "•", null, t)
                        else -> out += MdBlock.Paragraph(t)
                    } }
                    "tc" -> row.add(cell.toString())
                    "tr" -> rows.add(row)
                    "tbl" -> { tbl = false; table(rows)?.let { out += it } }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ OpenDocument text and presentations

    /** Bold / italic of the document's automatic (inline) styles, by name. */
    private class OdfStyles { val bold = HashSet<String>(); val italic = HashSet<String>() }

    private fun odfText(r: XMLStreamReader): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val styles = OdfStyles()
        val runs = Runs()
        var styleName: String? = null
        var inBody = false
        var pDepth = 0; var heading = 0
        var listDepth = 0; var inListItem = false
        val spanStack = ArrayList<String?>()
        var tblDepth = 0
        var rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cell = StringBuilder()
        var page = 0
        fun fmtBold() = spanStack.any { it != null && it in styles.bold }
        fun fmtItalic() = spanStack.any { it != null && it in styles.italic }
        while (r.hasNext() && out.size < MAX_BLOCKS) when (r.next()) {
            START_ELEMENT -> {
                val name = r.localName
                if (!inBody) when (name) {
                    "style" -> styleName = r.attr("name")
                    "text-properties" -> styleName?.let { s ->
                        if (r.attr("font-weight") == "bold" || r.attr("font-weight")?.toIntOrNull()?.let { it >= 600 } == true) styles.bold += s
                        if (r.attr("font-style") == "italic") styles.italic += s
                    }
                    "body" -> inBody = true
                } else when (name) {
                    "note", "annotation", "tracked-changes", "notes", "sequence-decls", "variable-decls", "user-field-decls", "table-of-content-source" -> r.skipElement()
                    "page" -> { if (page++ > 0) out += MdBlock.Rule; out += MdBlock.Heading(3, tr("Slide $page", "投影片 $page")) }
                    "frame" -> heading = if (r.attr("class") == "title") 2 else 0
                    "list" -> listDepth++
                    "list-item" -> inListItem = true
                    "h", "p" -> if (pDepth++ == 0) {
                        if (name == "h") heading = (r.attr("outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6)
                        spanStack.clear(); spanStack += r.attr("style-name")
                    } else runs.add(" ", false, false)
                    "span", "a" -> spanStack += r.attr("style-name")
                    "s" -> runs.add(" ".repeat((r.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 100)), false, false)
                    "tab" -> runs.add("    ", false, false)
                    "line-break" -> runs.add("\n", false, false)
                    "table" -> if (tblDepth++ == 0) rows = ArrayList()
                    "table-row" -> if (tblDepth == 1) row = ArrayList()
                    "table-cell", "covered-table-cell" -> if (tblDepth == 1) cell.clear()
                }
            }
            CHARACTERS -> if (inBody && pDepth > 0) runs.add(r.text.replace('\n', ' '), fmtBold(), fmtItalic())
            END_ELEMENT -> if (inBody) when (r.localName) {
                "span", "a" -> if (spanStack.size > 1) spanStack.removeAt(spanStack.size - 1)
                "list" -> listDepth--
                "list-item" -> inListItem = false
                "frame" -> heading = 0
                "h", "p" -> if (--pDepth == 0) {
                    val text = runs.take()
                    if (tblDepth > 0) { if (text.isNotEmpty()) cell.append(if (cell.isEmpty()) "" else " ").append(text) }
                    else if (text.isNotEmpty()) out += when {
                        heading > 0 -> MdBlock.Heading(heading, text)
                        listDepth > 0 -> MdBlock.ListItem(listDepth - 1, "•", null, text)
                        else -> MdBlock.Paragraph(text)
                    }
                    if (r.localName == "h") heading = 0
                    spanStack.clear()
                }
                "table-cell", "covered-table-cell" -> if (tblDepth == 1) row.add(cell.toString())
                "table-row" -> if (tblDepth == 1) rows.add(row)
                "table" -> if (--tblDepth == 0) table(rows)?.let { out += it }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ OpenDocument spreadsheets (.ods)

    /**
     * Sheets of an .ods. Rows and cells can be "repeated" thousands of times (empty space to the
     * end of the sheet), so empty runs are only written out when something follows them.
     */
    private fun ods(r: XMLStreamReader, maxRows: Int, maxSheets: Int): List<QlContent.Sheet> {
        val out = ArrayList<QlContent.Sheet>()
        var inBody = false
        var name = ""; var rows = ArrayList<List<String>>(); var total = 0; var pendingRows = 0
        var row = ArrayList<String>(); var rowRepeat = 1; var pendingCells = 0
        var cellRepeat = 1; val cell = StringBuilder(); var inP = false; var pCount = 0
        var depth = 0                     // tables inside cells (charts, sub-tables) are left alone
        while (r.hasNext() && out.size < maxSheets) when (r.next()) {
            START_ELEMENT -> if (!inBody) { if (r.localName == "spreadsheet") inBody = true } else when (r.localName) {
                "annotation", "named-expressions", "database-ranges" -> r.skipElement()
                "table" -> if (depth++ == 0) { name = r.attr("name") ?: "Sheet"; rows = ArrayList(); total = 0; pendingRows = 0 }
                "table-row" -> if (depth == 1) { row = ArrayList(); pendingCells = 0; rowRepeat = (r.attr("number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1) }
                "table-cell", "covered-table-cell" -> if (depth == 1) { cell.clear(); pCount = 0
                    cellRepeat = (r.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1) }
                "p" -> if (depth == 1) { inP = true; if (pCount++ > 0) cell.append(' ') }
                "s" -> if (inP) cell.append(" ".repeat((r.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 100)))
                "tab" -> if (inP) cell.append("    ")
                "line-break" -> if (inP) cell.append(' ')
            }
            CHARACTERS -> if (inP) cell.append(r.text)
            END_ELEMENT -> if (inBody) when (r.localName) {
                "p" -> inP = false
                "table-cell", "covered-table-cell" -> if (depth == 1) {
                    val v = cell.toString()
                    if (v.isEmpty()) pendingCells += cellRepeat
                    else {
                        repeat(minOf(pendingCells, MAX_COLS - row.size).coerceAtLeast(0)) { row.add("") }
                        pendingCells = 0
                        repeat(minOf(cellRepeat, MAX_COLS - row.size).coerceAtLeast(0)) { row.add(v) }
                    }
                }
                "table-row" -> if (depth == 1) {
                    if (row.isEmpty()) pendingRows += rowRepeat
                    else {
                        val n = pendingRows + rowRepeat
                        repeat(pendingRows) { if (rows.size < maxRows) rows.add(emptyList()) }
                        repeat(rowRepeat) { if (rows.size < maxRows) rows.add(row) }
                        total += n; pendingRows = 0
                    }
                }
                "table" -> if (--depth == 0) out += QlContent.Sheet(name, rows, total)
                "spreadsheet" -> inBody = false
            }
        }
        return out
    }

    // ------------------------------------------------------------------ Word 97–2003 (.doc)

    /**
     * The main text of a binary Word document: the piece table in the table stream says where each
     * run of characters lives in the WordDocument stream (8-bit or UTF-16). Fields keep their shown
     * result, tables become cells separated by bars.
     */
    private object WordBinary {
        fun read(f: File): List<MdBlock> {
            val bytes = f.readBytes()
            if (bytes.size < 512 || ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(0) != 0xE11AB1A1E011CFD0uL.toLong()) {
                // Plenty of ".doc" files are really RTF, HTML or text saved under that name.
                val head = String(bytes, 0, minOf(bytes.size, 5), Charsets.ISO_8859_1)
                if (head.startsWith("{\\rtf")) return rtf(String(bytes, Charsets.ISO_8859_1))
                error(tr("Not a Word 97–2003 document.", "不是 Word 97–2003 文件。"))
            }
            val cfb = Xls.Cfb(bytes)
            val wd = cfb.stream("WordDocument") ?: error(tr("No Word document inside this file.", "這個檔案裡沒有 Word 文件。"))
            val b = ByteBuffer.wrap(wd).order(ByteOrder.LITTLE_ENDIAN)
            if (wd.size < 0x200 || (b.getShort(0).toInt() and 0xFFFF) != 0xA5EC) error(tr("Not a Word 97–2003 document.", "不是 Word 97–2003 文件。"))
            val flags = b.getShort(0x0A).toInt() and 0xFFFF
            if (flags and 0x0100 != 0) error(tr("This document is password-protected.", "這份文件有密碼保護。"))
            if ((b.getShort(0x02).toInt() and 0xFFFF) < 0xC1) error(tr("This is a Word 6/95 document, too old to preview.", "這是 Word 6/95 文件，版本太舊無法預覽。"))
            val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: error(tr("This document is damaged.", "這份文件已損毀。"))
            // FIB: base (32) · csw + shorts · cslw + longs (ccpText is the 4th) · cbRgFcLcb + fc/lcb pairs (Clx is pair 33).
            val csw = b.getShort(32).toInt() and 0xFFFF
            val lwAt = 34 + csw * 2
            val cslw = b.getShort(lwAt).toInt() and 0xFFFF
            val ccpText = b.getInt(lwAt + 2 + 3 * 4)
            val pairs = lwAt + 2 + cslw * 4 + 2
            val fcClx = b.getInt(pairs + 33 * 8); val lcbClx = b.getInt(pairs + 33 * 8 + 4)
            if (lcbClx <= 0 || fcClx < 0 || fcClx + lcbClx > table.size) error(tr("This document is damaged.", "這份文件已損毀。"))
            val t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
            var pos = fcClx
            while (pos < fcClx + lcbClx && table[pos].toInt() == 1) pos += 3 + (t.getShort(pos + 1).toInt() and 0xFFFF)
            if (pos >= table.size || table[pos].toInt() != 2) error(tr("This document is damaged.", "這份文件已損毀。"))
            val lcb = t.getInt(pos + 1); val plc = pos + 5
            val n = (lcb - 4) / 12
            val text = StringBuilder()
            for (i in 0 until n) {
                val cp0 = t.getInt(plc + i * 4); val cp1 = t.getInt(plc + (i + 1) * 4)
                if (cp0 >= ccpText || text.length > 4_000_000) break
                val count = minOf(cp1, ccpText) - cp0
                if (count <= 0) continue
                val fc = t.getInt(plc + (n + 1) * 4 + i * 8 + 2)
                if (fc and 0x40000000 != 0) {
                    val o = (fc and 0x3FFFFFFF) / 2
                    if (o + count <= wd.size) text.append(String(wd, o, count, charset("windows-1252")))
                } else if (fc + count * 2 <= wd.size) text.append(String(wd, fc, count * 2, Charsets.UTF_16LE))
            }
            return blocks(clean(text))
        }

        /** Field codes (between 0x13 and 0x14) go, results stay; special characters become plain ones. */
        private fun clean(s: CharSequence): String {
            val out = StringBuilder(s.length)
            val fields = ArrayList<Boolean>()      // per open field: are we past its separator?
            for (c in s) when (c.code) {
                0x13 -> fields += false
                0x14 -> if (fields.isNotEmpty()) fields[fields.size - 1] = true
                0x15 -> if (fields.isNotEmpty()) fields.removeAt(fields.size - 1)
                else -> if (fields.all { it }) when (c.code) {
                    0x0D, 0x0C -> out.append('\n')
                    0x0B -> out.append(' ')
                    0x07 -> out.append('\u0007')
                    0x09 -> out.append("    ")
                    0x1E -> out.append('-')
                    0xA0 -> out.append(' ')
                    0x01, 0x02, 0x03, 0x04, 0x05, 0x08, 0x1F -> {}
                    else -> if (c.code >= 0x20) out.append(c)
                }
            }
            return out.toString()
        }

        /** Paragraphs; lines with cell marks are table rows, consecutive ones one table. */
        private fun blocks(text: String): List<MdBlock> {
            val out = ArrayList<MdBlock>()
            val rows = ArrayList<List<String>>()
            fun endTable() { table(rows)?.let { out += it }; rows.clear() }
            for (line in text.split('\n')) {
                if (out.size >= MAX_BLOCKS) break
                if ('\u0007' in line) {
                    // A row ends with the last cell's mark and the row mark; cells inside a cell share a line.
                    rows += line.trimEnd('\u0007').split('\u0007').map { it.trim() }
                    continue
                }
                if (rows.isNotEmpty()) endTable()
                val p = line.trim()
                if (p.isNotEmpty()) out += MdBlock.Paragraph(esc(p))
            }
            endTable()
            return out
        }

        /** RTF saved as .doc: the visible text, without control words and groups like fonts and pictures. */
        private fun rtf(s: String): List<MdBlock> {
            val out = StringBuilder()
            val skip = ArrayList<Boolean>(); var skipping = false
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when (c) {
                    '{' -> { skip += skipping; i++ ; if (s.startsWith("{\\*", i - 1)) skipping = true }
                    '}' -> { skipping = skip.removeLastOrNull() ?: false; i++ }
                    '\\' -> {
                        val m = Regex("^\\\\([a-zA-Z]+)(-?\\d+)? ?|^\\\\'([0-9a-fA-F]{2})|^\\\\(.)").find(s.substring(i, minOf(s.length, i + 40)))
                        if (m == null) { i++; continue }
                        i += m.value.length
                        val word = m.groupValues[1]
                        when {
                            m.groupValues[3].isNotEmpty() -> if (!skipping) out.append(String(byteArrayOf(m.groupValues[3].toInt(16).toByte()), charset("windows-1252")))
                            m.groupValues[4].isNotEmpty() -> if (!skipping) m.groupValues[4][0].let { if (it in "\\{}") out.append(it) }
                            word in setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "object", "listtable", "listoverridetable") -> skipping = true
                            word == "par" || word == "line" -> if (!skipping) out.append('\n')
                            word == "tab" -> if (!skipping) out.append("    ")
                            word == "cell" -> if (!skipping) out.append('\u0007')
                            word == "u" -> if (!skipping) m.groupValues[2].toIntOrNull()?.let { out.append((if (it < 0) it + 65536 else it).toChar()); if (i < s.length && s[i] == '?') i++ }
                        }
                    }
                    '\r', '\n' -> i++
                    else -> { if (!skipping) out.append(c); i++ }
                }
            }
            return blocks(out.toString())
        }
    }
}
