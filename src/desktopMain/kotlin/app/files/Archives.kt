package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** One file or folder inside an archive. [path] uses / and has no trailing slash. Unknown numbers are -1 / 0. */
class ArchEntry(val path: String, val dir: Boolean, val size: Long = -1, val packed: Long = -1, val modified: Long = 0, val encrypted: Boolean = false) {
    val name get() = path.substringAfterLast('/')
}

/** What an archive holds: its entries, the format, and a note (e.g. a single compressed file, a listing cut short). */
class ArchListing(val format: String, val entries: List<ArchEntry>, val truncated: Boolean = false, val note: String? = null)

/**
 * Lists archives for 快速預覽 without extracting anything.
 * - zip / jar, tar, gz / tar.gz / tgz: read here.
 * - rar, 7z, xz / tar.xz, bz2 / tar.bz2, zst, cab: 7-Zip when it's installed (a compressed tar is
 *   decompressed through it and read here, so its files show); otherwise Windows' own tar.exe.
 */
object Archives {
    private const val MAX_ENTRIES = 50_000
    private const val TAR_MAX_BYTES = 4L shl 30     // stop reading a compressed tar after 4 GiB
    private val tarExt = setOf("tgz", "txz", "tbz", "tbz2", "tzst")

    /** True when [name] is an archive the preview lists. */
    fun handles(name: String): Boolean {
        val n = name.lowercase()
        return n.substringAfterLast('.') in setOf("zip", "jar", "tar", "gz", "tgz", "xz", "txz", "bz2", "tbz", "tbz2", "zst", "tzst", "rar", "7z", "cab")
    }

    private fun compressedTar(n: String) = Regex("\\.tar\\.(gz|xz|bz2|zst)$").containsMatchIn(n) || n.substringAfterLast('.') in tarExt

    fun list(f: File): ArchListing {
        val n = f.name.lowercase()
        val ext = n.substringAfterLast('.')
        return when {
            ext == "zip" || ext == "jar" -> zip(f)
            ext == "tar" -> tarFile(f)
            ext == "gz" || ext == "tgz" -> gzip(f)
            else -> external(f, compressedTar(n))
        }
    }

    // ------------------------------------------------------------------ zip

    private fun zip(f: File): ArchListing {
        fun read(cs: Charset) = ZipFile(f, cs).use { z ->
            val out = ArrayList<ArchEntry>(); var cut = false
            for (e in z.entries()) {
                if (out.size >= MAX_ENTRIES) { cut = true; break }
                out += ArchEntry(e.name.replace('\\', '/').trimEnd('/'), e.isDirectory, e.size, e.compressedSize,
                    e.lastModifiedTime?.toMillis() ?: e.time, e.method == 99)   // 99 = AES-encrypted entry
            }
            ArchListing("ZIP", out, cut)
        }
        // Names are UTF-8 when the archive says so; older Windows zips use the system code page (Big5 here).
        return try { read(Charsets.UTF_8) } catch (_: Exception) {
            read(runCatching { Charset.forName(System.getProperty("native.encoding")) }.getOrDefault(Charset.forName("MS950")))
        }
    }

    // ------------------------------------------------------------------ tar (ustar, GNU long names, pax)

    private fun tarFile(f: File) = f.inputStream().buffered().use { val (e, cut) = tar(it); ArchListing("TAR", e, cut) }

    /** Reads tar headers from [input], skipping the file data. Returns the entries and whether the list was cut short. */
    fun tar(input: InputStream, limit: Long = TAR_MAX_BYTES): Pair<List<ArchEntry>, Boolean> {
        val inp = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val out = ArrayList<ArchEntry>()
        val h = ByteArray(512)
        var read = 0L
        var longName: String? = null
        var paxPath: String? = null
        var paxSize: Long? = null
        fun full(b: ByteArray): Boolean { var o = 0; while (o < b.size) { val k = inp.read(b, o, b.size - o); if (k < 0) return false; o += k }; read += b.size; return true }
        fun skip(n: Long) { var left = n; while (left > 0) { val k = inp.skip(left); if (k <= 0) { if (inp.read() < 0) return; left--; read++ } else { left -= k; read += k } } }
        fun str(o: Int, len: Int): String { var e = o; while (e < o + len && h[e] != 0.toByte()) e++; return String(h, o, e - o, Charsets.UTF_8) }
        fun octal(o: Int, len: Int): Long {
            if (h[o].toInt() and 0x80 != 0) { var v = 0L; for (i in 1 until len) v = (v shl 8) or (h[o + i].toLong() and 0xFF); return v }   // base-256 (big files)
            return str(o, len).trim().trim('\u0000').toLongOrNull(8) ?: 0
        }
        while (read < limit) {
            if (!full(h)) break
            if (h.all { it == 0.toByte() }) break     // end of archive
            val type = h[156].toInt().toChar()
            val size = octal(124, 12)
            val padded = (size + 511) / 512 * 512
            when (type) {
                'L' -> { val b = ByteArray(size.toInt().coerceAtMost(1 shl 20)); full(b); skip(padded - b.size); longName = String(b, Charsets.UTF_8).trimEnd('\u0000'); continue }
                'x' -> {
                    val b = ByteArray(size.toInt().coerceAtMost(1 shl 20)); full(b); skip(padded - b.size)
                    // "len key=value\n" records.
                    String(b, Charsets.UTF_8).lineSequence().forEach { l ->
                        val kv = l.substringAfter(' ', "")
                        when (kv.substringBefore('=')) { "path" -> paxPath = kv.substringAfter('='); "size" -> paxSize = kv.substringAfter('=').toLongOrNull() }
                    }
                    continue
                }
                'g' -> { skip(padded); continue }
            }
            val prefix = if (String(h, 257, 5, Charsets.US_ASCII) == "ustar") str(345, 155) else ""
            val name = paxPath ?: longName ?: (if (prefix.isNotEmpty()) "$prefix/" else "") + str(0, 100)
            val realSize = paxSize ?: size
            val dir = type == '5' || name.endsWith("/")
            if (out.size < MAX_ENTRIES) out += ArchEntry(name.replace('\\', '/').removePrefix("./").trimEnd('/'), dir, if (dir) -1 else realSize, -1, octal(136, 12) * 1000)
            else return out to true
            longName = null; paxPath = null; paxSize = null
            skip(if (paxSize != null) (realSize + 511) / 512 * 512 else padded)
        }
        return out.filter { it.path.isNotEmpty() } to (read >= limit)
    }

    // ------------------------------------------------------------------ gzip

    private fun gzip(f: File): ArchListing {
        // A .tar inside? Look for the ustar mark in the first header.
        val head = runCatching { GZIPInputStream(f.inputStream()).use { it.readNBytes(512) } }.getOrDefault(ByteArray(0))
        val isTar = head.size >= 262 && String(head, 257, 5, Charsets.US_ASCII) == "ustar" ||
            (head.size == 512 && f.name.lowercase().let { it.endsWith(".tar.gz") || it.endsWith(".tgz") })
        if (isTar) return GZIPInputStream(f.inputStream(), 1 shl 16).use { val (e, cut) = tar(it); ArchListing("TAR.GZ", e, cut) }
        // One compressed file: its name from the header (FNAME), its size from the trailer (ISIZE, modulo 4 GiB).
        val bytes = f.inputStream().use { it.readNBytes(1024) }
        var name = f.name.removeSuffix(".gz").removeSuffix(".GZ")
        if (bytes.size > 10 && bytes[3].toInt() and 0x08 != 0) {
            var o = 10
            if (bytes[3].toInt() and 0x04 != 0) o += 2 + ((bytes[10].toInt() and 0xFF) or ((bytes[11].toInt() and 0xFF) shl 8))
            val e = (o until bytes.size).firstOrNull { bytes[it] == 0.toByte() }
            // The stored name: UTF-8 when it is valid UTF-8, else the system code page (Big5 here).
            if (e != null && e > o) name = runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes, o, e - o)).toString()
            }.getOrElse { String(bytes, o, e - o, runCatching { Charset.forName(System.getProperty("native.encoding")) }.getOrDefault(Charset.forName("MS950"))) }
        }
        val size = java.io.RandomAccessFile(f, "r").use { r -> if (r.length() < 4) -1L else { r.seek(r.length() - 4); val b = ByteArray(4); r.readFully(b)
            (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24) } }
        return ArchListing("GZIP", listOf(ArchEntry(name, false, size, f.length(), f.lastModified())),
            note = tr("One compressed file (not a folder archive).", "單一壓縮檔案（不是資料夾壓縮檔）。"))
    }

    // ------------------------------------------------------------------ 7-Zip, then Windows' tar.exe

    /** 7-Zip, when installed. */
    val sevenZip: File? by lazy {
        if (System.getProperty("fileskmp.no7zip") != null) return@lazy null   // tests: the tar.exe path
        listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramW6432"), System.getenv("ProgramFiles(x86)"))
            .map { File(it, "7-Zip\\7z.exe") }.firstOrNull { it.isFile }
            ?: WinReg.string(WinReg.HKLM, "SOFTWARE\\7-Zip", "Path")?.let { File(it, "7z.exe") }?.takeIf { it.isFile }
    }
    private val bsdtar = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\tar.exe")

    private fun run(cmd: List<String>, timeoutSec: Long = 30): Pair<Int, ByteArray> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = java.io.ByteArrayOutputStream()
        val t = Thread { runCatching { p.inputStream.use { it.copyTo(out) } } }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); error(tr("Listing took too long.", "讀取目錄逾時。")) }
        t.join(2000)
        return p.exitValue() to out.toByteArray()
    }

    private fun external(f: File, isTar: Boolean): ArchListing {
        val sz = sevenZip
        if (sz != null) {
            if (isTar) {
                // Decompress through 7-Zip and read the tar here (7-Zip itself would only show the inner .tar).
                val p = ProcessBuilder(sz.path, "x", "-so", "--", f.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                p.outputStream.close()
                try {
                    val (e, cut) = tar(p.inputStream)
                    if (e.isNotEmpty()) return ArchListing("TAR." + f.name.substringAfterLast('.').uppercase(), e, cut)
                } finally { p.destroy() }
            }
            val (code, bytes) = run(listOf(sz.path, "l", "-slt", "-sccUTF-8", "-p", "--", f.path))
            val text = String(bytes, Charsets.UTF_8)
            if (code == 0 || text.contains("----------")) return parse7z(text, f)
            if (text.contains("Wrong password", true) || text.contains("Can not open encrypted archive", true))
                error(tr("This archive is encrypted: its file list needs the password.", "這個壓縮檔已加密，要輸入密碼才能看到檔案清單。"))
        }
        if (bsdtar.isFile) {
            if (isTar) {
                // tar.exe re-writes the archive uncompressed, as pax (names kept in UTF-8), into a pipe read here.
                val p = ProcessBuilder(bsdtar.path, "-cf", "-", "--format", "pax", "@" + f.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                p.outputStream.close()
                try {
                    val (e, cut) = tar(p.inputStream)
                    if (e.isNotEmpty()) return ArchListing("TAR." + f.name.substringAfterLast('.').uppercase(), e, cut)
                } finally { p.destroy() }
            }
            val (code, bytes) = run(listOf(bsdtar.path, "-tvf", f.path))
            val text = WinSys.decodeConsole(bytes)
            if (code == 0) return parseBsdtar(text, f)
            // One compressed file (not an archive): tar.exe can't list it, but there's nothing more to show.
            if (f.name.substringAfterLast('.').lowercase() in setOf("xz", "bz2", "zst", "lzma"))
                return ArchListing(f.name.substringAfterLast('.').uppercase(), listOf(ArchEntry(f.name.substringBeforeLast('.'), false, -1, f.length(), f.lastModified())),
                    note = tr("One compressed file (not a folder archive).", "單一壓縮檔案（不是資料夾壓縮檔）。"))
            error(text.lines().firstOrNull { it.isNotBlank() } ?: tr("Can't read this archive.", "無法讀取這個壓縮檔。"))
        }
        error(tr("Install 7-Zip to see inside this kind of archive.", "安裝 7-Zip 後就能檢視這類壓縮檔的內容。"))
    }

    private fun parse7z(text: String, f: File): ArchListing {
        val (head, body) = text.substringBefore("----------") to text.substringAfter("----------", "")
        val format = Regex("(?m)^Type = (.+)$").findAll(head).lastOrNull()?.groupValues?.get(1)?.trim()?.uppercase() ?: "?"
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val out = ArrayList<ArchEntry>(); var cut = false
        body.split(Regex("\\r?\\n\\r?\\n")).forEach { block ->
            val m = block.lines().mapNotNull { l -> l.indexOf(" = ").takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 3).trim() } }.toMap()
            // A single compressed file (xz, bz2…) has no path of its own: it's the archive's name without the extension.
            val path = m["Path"] ?: (if (m.containsKey("Size")) f.name.substringBeforeLast('.') else return@forEach)
            if (out.size >= MAX_ENTRIES) { cut = true; return@forEach }
            val dir = m["Folder"] == "+" || (m["Attributes"]?.startsWith("D") == true)
            val mod = m["Modified"]?.take(19)?.let { s -> runCatching { LocalDateTime.parse(s, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() } ?: 0
            out += ArchEntry(path.replace('\\', '/').trimEnd('/'), dir, m["Size"]?.toLongOrNull() ?: -1, m["Packed Size"]?.toLongOrNull() ?: -1, mod, m["Encrypted"] == "+")
        }
        val single = out.size == 1 && format in setOf("XZ", "BZIP2", "ZSTD", "GZIP", "LZMA")
        return ArchListing(format, out, cut, if (single) tr("One compressed file (not a folder archive).", "單一壓縮檔案（不是資料夾壓縮檔）。") else null)
    }

    /** "-rw-r--r--  0 user group  1234 <date: 3 words> path" (the date is in the system's language: not read). */
    private fun parseBsdtar(text: String, f: File): ArchListing {
        val rx = Regex("^([dlbcps-][rwxsStT-]{9})\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+\\S+\\s+\\S+\\s+\\S+\\s(.*)$")
        val out = text.lines().mapNotNull { l -> rx.find(l.trimEnd())?.let { m ->
            ArchEntry(m.groupValues[3].replace('\\', '/').trimEnd('/'), m.groupValues[1].startsWith("d"), m.groupValues[2].toLongOrNull() ?: -1)
        } }.take(MAX_ENTRIES)
        return ArchListing(f.name.substringAfterLast('.').uppercase(), out)
    }
}

/** A folder in the archive tree, with its children sorted folders first. */
private class ArchNode(val name: String, val path: String, val entry: ArchEntry?) {
    val children = LinkedHashMap<String, ArchNode>()
    val dir get() = entry?.dir ?: true
    var size = 0L; var packed = 0L; var files = 0
}

private fun buildTree(entries: List<ArchEntry>): ArchNode {
    val root = ArchNode("", "", null)
    entries.forEach { e ->
        var n = root; var path = ""
        val parts = e.path.split('/').filter { it.isNotEmpty() }
        parts.forEachIndexed { i, part ->
            path = if (path.isEmpty()) part else "$path/$part"
            val last = i == parts.size - 1
            n = n.children.getOrPut(part) { ArchNode(part, path, if (last) e else null) }
        }
    }
    fun total(n: ArchNode) {
        n.children.values.forEach { c -> if (c.dir) total(c) else { c.size = c.entry?.size?.coerceAtLeast(0) ?: 0; c.packed = c.entry?.packed?.coerceAtLeast(0) ?: 0; c.files = 1 } }
        n.size = n.children.values.sumOf { it.size }; n.packed = n.children.values.sumOf { it.packed }; n.files = n.children.values.sumOf { it.files }
    }
    total(root)
    return root
}

/**
 * 快速預覽 of an archive: what's inside, as a folder tree (click a folder to open or close it), with
 * sizes, packed sizes and dates, a summary, and a search that lists matching files with their folders.
 */
@Composable
fun ArchivePreview(listing: ArchListing) {
    val p = LocalPalette.current
    val root = remember(listing) { buildTree(listing.entries) }
    // Open the top folder when everything is inside one.
    var open by remember(listing) { mutableStateOf(if (root.children.size == 1) setOf(root.children.values.first().path) else emptySet()) }
    var query by remember(listing) { mutableStateOf("") }
    val files = root.files
    val dirs = remember(listing) { var c = 0; fun walk(n: ArchNode) { n.children.values.forEach { if (it.dir) { c++; walk(it) } } }; walk(root); c }
    val hasPacked = listing.entries.any { it.packed >= 0 }
    val hasDate = listing.entries.any { it.modified > 0 }
    val rows: List<Pair<ArchNode, Int>> = remember(listing, open, query) {
        val out = ArrayList<Pair<ArchNode, Int>>()
        if (query.isNotBlank()) {
            fun find(n: ArchNode) { n.children.values.forEach { c -> if (c.name.contains(query.trim(), true)) out += c to 0; if (c.dir) find(c) } }
            find(root)
        } else {
            fun add(n: ArchNode, depth: Int) {
                n.children.values.sortedWith(compareBy<ArchNode>({ !it.dir }, { it.name.lowercase() })).forEach { c ->
                    out += c to depth
                    if (c.dir && c.path in open) add(c, depth + 1)
                }
            }
            add(root, 0)
        }
        out
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                T(listOfNotNull(listing.format, tr("$files files", "$files 個檔案"), if (dirs > 0) tr("$dirs folders", "$dirs 個資料夾") else null,
                    if (root.size > 0) tr("${Fs.sizeText(root.size)} uncompressed", "原始 ${Fs.sizeText(root.size)}") else null,
                    if (hasPacked && root.packed > 0 && root.size > 0) tr("packed ${Fs.sizeText(root.packed)} (${root.packed * 100 / root.size}%)", "壓縮後 ${Fs.sizeText(root.packed)}（${root.packed * 100 / root.size}%）") else null,
                ).joinToString("  ·  "), size = 12.sp, color = p.textSecondary)
                listing.note?.let { T(it, size = 11.sp, color = p.accentText) }
                if (listing.truncated) T(tr("Only the first ${listing.entries.size} entries are shown.", "只顯示前 ${listing.entries.size} 個項目。"), size = 11.sp, color = p.accentText)
            }
            if (listing.entries.size > 1) {
                DialogButton(tr("Open all", "全部展開"), primary = false) { val all = HashSet<String>(); fun w(n: ArchNode) { n.children.values.forEach { if (it.dir) { all += it.path; w(it) } } }; w(root); open = all }
                DialogButton(tr("Close all", "全部收合"), primary = false) { open = emptySet() }
                PlainField(query, { query = it }, Modifier.width(220.dp), placeholder = tr("Search in archive", "在壓縮檔中搜尋"))
            }
        }
        // Column titles.
        Row(Modifier.fillMaxWidth().background(p.control).padding(horizontal = 14.dp, vertical = 5.dp)) {
            T(S.name, Modifier.weight(1f), size = 11.sp, color = p.textSecondary)
            T(S.size, Modifier.width(100.dp), size = 11.sp, color = p.textSecondary)
            if (hasPacked) T(tr("Packed", "壓縮後"), Modifier.width(100.dp), size = 11.sp, color = p.textSecondary)
            if (hasDate) T(S.dateModified, Modifier.width(150.dp), size = 11.sp, color = p.textSecondary)
        }
        if (rows.isEmpty()) T(if (query.isNotBlank()) tr("Nothing matches.", "沒有符合的項目。") else tr("The archive is empty.", "壓縮檔是空的。"), Modifier.padding(16.dp), color = p.textSecondary)
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.first.path }) { (n, depth) ->
                val isOpen = n.path in open
                Row(Modifier.fillMaxWidth().clickable(enabled = n.dir && query.isBlank()) { open = if (isOpen) open - n.path else open + n.path }
                    .padding(start = (14 + depth * 18).dp, end = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp)) { if (n.dir && query.isBlank()) Icon(if (isOpen) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = p.textSecondary) }
                    Spacer(Modifier.width(4.dp))
                    Icon(if (n.dir) Icons.Filled.Folder else Icons.Outlined.InsertDriveFile, null, Modifier.size(16.dp), tint = if (n.dir) p.folder else p.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(n.name, Modifier.weight(1f, fill = false), size = 13.sp, weight = if (n.dir) FontWeight.Medium else FontWeight.Normal)
                            if (n.entry?.encrypted == true) Icon(Icons.Outlined.Lock, tr("Encrypted", "已加密"), Modifier.padding(start = 4.dp).size(12.dp), tint = p.textTertiary)
                            if (n.dir) T("  " + tr("${n.files} files", "${n.files} 個檔案"), size = 11.sp, color = p.textTertiary)
                        }
                        if (query.isNotBlank() && n.path.contains('/')) T(n.path.substringBeforeLast('/'), size = 11.sp, color = p.textTertiary)
                    }
                    T(if (n.dir) (if (n.size > 0) Fs.sizeText(n.size) else "") else (n.entry?.size?.takeIf { it >= 0 }?.let { Fs.sizeText(it) } ?: "—"),
                        Modifier.width(100.dp), size = 12.sp, color = p.textSecondary)
                    if (hasPacked) T(if (n.dir) (if (n.packed > 0) Fs.sizeText(n.packed) else "") else (n.entry?.packed?.takeIf { it >= 0 }?.let { Fs.sizeText(it) } ?: "—"),
                        Modifier.width(100.dp), size = 12.sp, color = p.textSecondary)
                    if (hasDate) T(n.entry?.modified?.takeIf { it > 0 }?.let { Fs.dateTimeText(it) } ?: "", Modifier.width(150.dp), size = 12.sp, color = p.textSecondary)
                }
            }
        }
    }
}
