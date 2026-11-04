package app.files

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The person's own metadata fields (專案, 客戶, 狀態, …): defined once, filled in per file, shown
 * as 詳細資料 columns and usable in rename templates ({field:名稱}). Kept by the app in
 * %APPDATA%\FilesKmp\metadata.tsv (path, field, value), moved along when the app renames or moves.
 */
object MetaStore {
    private val file get() = File(Prefs.dir, "metadata.tsv")
    val fields = mutableStateListOf<String>()
    private val values = HashMap<String, HashMap<String, String>>()   // lower path → field → value
    var version by mutableStateOf(0); private set

    init { load() }

    private fun esc(v: String) = v.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(v: String) = buildString {
        var i = 0
        while (i < v.length) {
            if (v[i] == '\\' && i + 1 < v.length) { append(when (v[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> v[i + 1] }); i += 2 } else { append(v[i]); i++ }
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            file.readLines(Charsets.UTF_8).forEach { l ->
                val p = l.split('\t')
                if (p.size == 2 && p[0] == "#field") { if (p[1] !in fields) fields += unesc(p[1]) }
                else if (p.size == 3) values.getOrPut(unesc(p[0]).lowercase()) { HashMap() }[unesc(p[1])] = unesc(p[2])
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        version++
        if (Prefs.readOnly) return
        try {
            file.writeText(buildString {
                fields.forEach { append("#field\t").append(esc(it)).append('\n') }
                for ((path, m) in values) for ((k, v) in m) append(esc(path)).append('\t').append(esc(k)).append('\t').append(esc(v)).append('\n')
            }, Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun get(path: String, field: String): String = values[path.lowercase()]?.get(field) ?: ""

    fun addField(name: String) { val n = name.trim(); if (n.isNotEmpty() && n !in fields) { fields += n; save() } }
    fun removeField(name: String) { fields.remove(name); values.values.forEach { it.remove(name) }; save() }

    /** Sets [field] on every path; an empty value clears it. */
    fun set(paths: Collection<String>, field: String, value: String) {
        for (p in paths) {
            val m = values.getOrPut(p.lowercase()) { HashMap() }
            if (value.isEmpty()) m.remove(field) else m[field] = value
        }
        save()
    }

    fun movePath(from: String, to: String) {
        values.remove(from.lowercase())?.let { values[to.lowercase()] = it; save() }
    }
}

/** Tri-state for attribute switches over several files: unchanged, on, off. */
private enum class Tri { Keep, On, Off }

/**
 * 編輯屬性 for one or many items: dates (created / modified / accessed, or from the photo's date
 * taken), attributes (read-only, hidden, archive) and the person's own fields. Empty = unchanged.
 */
@Composable
fun MetadataDialog(s: AppState, paths: List<String>) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val items = remember(paths) { paths.mapNotNull { FsExtra.item(it) } }
    var created by remember { mutableStateOf("") }
    var modified by remember { mutableStateOf("") }
    var accessed by remember { mutableStateOf("") }
    var fromTaken by remember { mutableStateOf(false) }
    var readOnly by remember { mutableStateOf(Tri.Keep) }
    var hidden by remember { mutableStateOf(Tri.Keep) }
    var archive by remember { mutableStateOf(Tri.Keep) }
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    val clearFields = remember { mutableStateMapOf<String, Boolean>() }
    var newField by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    // One file: start from its current values.
    LaunchedEffect(paths) {
        if (items.size == 1) {
            val f = items[0]
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            fun d(ms: Long) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(fmt)
            created = d(f.created); modified = d(f.modified)
            runCatching { Files.readAttributes(Paths.get(f.path), java.nio.file.attribute.BasicFileAttributes::class.java).lastAccessTime().toMillis() }.getOrNull()?.let { accessed = d(it) }
            MetaStore.fields.forEach { k -> fieldValues[k] = MetaStore.get(f.path, k) }
        }
    }
    val close = { s.metadataFor = null }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures2 { close() } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(620.dp).heightIn(max = 720.dp).softShadow(shape, 18.dp).background(p.solid, shape).border(1.dp, p.controlStroke, shape)
                .pointerInput(Unit) { detectTapGestures2 { } }.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(S.editProperties + "  ·  " + (if (items.size == 1) items[0].name else S.items(items.size)), Modifier.weight(1f), size = 20.sp, weight = FontWeight.SemiBold)
                IconBtn(Icons.Outlined.Close, S.close, close)
            }
            T(S.metaHint, Modifier.padding(vertical = 6.dp), size = 12.sp, color = p.textSecondary, maxLines = 2)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GroupTitle(S.dates)
                @Composable fun dateRow(label: String, v: String, set: (String) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
                    T(label, Modifier.width(120.dp), size = 13.sp); PlainField(v, set, Modifier.weight(1f), placeholder = "yyyy-MM-dd HH:mm:ss")
                }
                dateRow(DetailCol.DateCreated.label, created) { created = it }
                dateRow(DetailCol.DateModified.label, modified) { modified = it }
                dateRow(DetailCol.DateAccessed.label, accessed) { accessed = it }
                TriRow(fromTaken, S.datesFromTaken) { fromTaken = !fromTaken }
                GroupTitle(DetailCol.Attributes.label)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TriBox(readOnly, S.attrReadOnly) { readOnly = it }
                    TriBox(hidden, S.attrHidden) { hidden = it }
                    TriBox(archive, S.attrArchive) { archive = it }
                }
                GroupTitle(S.customFields)
                MetaStore.fields.toList().forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(k, Modifier.width(120.dp), size = 13.sp)
                        PlainField(fieldValues[k] ?: "", { fieldValues[k] = it }, Modifier.weight(1f), placeholder = if (items.size > 1) S.keepValue else "")
                        Spacer(Modifier.width(6.dp))
                        TriRow(clearFields[k] == true, S.clearValue) { clearFields[k] = clearFields[k] != true }
                        IconBtn(Icons.Outlined.Delete, S.deleteField, { MetaStore.removeField(k) }, width = 28.dp, height = 26.dp, iconSize = 14.dp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlainField(newField, { newField = it }, Modifier.weight(1f), placeholder = S.newFieldName)
                    Spacer(Modifier.width(6.dp))
                    DialogButton("+ " + S.addField, primary = false) { MetaStore.addField(newField); newField = "" }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T(message ?: "", Modifier.weight(1f), size = 12.sp, color = p.danger, maxLines = 2)
                DialogButton(S.renameApply, primary = true) {
                    scope.launch {
                        val err = withContext(Dispatchers.IO) {
                            applyMetadata(items, created, modified, accessed, fromTaken, readOnly, hidden, archive, single = items.size == 1)
                        }
                        val fields = MetaStore.fields.toList()
                        for (k in fields) {
                            val v = fieldValues[k] ?: ""
                            if (clearFields[k] == true) MetaStore.set(paths, k, "")
                            else if (v.isNotEmpty() || items.size == 1) MetaStore.set(paths, k, v)
                        }
                        s.afterMetadataChange(paths)
                        if (err == null) close() else message = err
                    }
                }
                DialogButton(S.close, primary = false) { close() }
            }
        }
    }
}

@Composable
private fun TriRow(on: Boolean, label: String, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggle).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(16.dp), tint = if (on) p.accent else p.textSecondary)
        Spacer(Modifier.width(6.dp)); T(label, size = 12.sp)
    }
}

/** Click cycles: unchanged (–) → on (✓) → off (✗). */
@Composable
private fun TriBox(v: Tri, label: String, set: (Tri) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable { set(when (v) { Tri.Keep -> Tri.On; Tri.On -> Tri.Off; Tri.Off -> Tri.Keep }) }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(when (v) { Tri.Keep -> Icons.Outlined.IndeterminateCheckBox; Tri.On -> Icons.Outlined.CheckBox; Tri.Off -> Icons.Outlined.DisabledByDefault }, null,
            Modifier.size(16.dp), tint = when (v) { Tri.Keep -> p.textTertiary; Tri.On -> p.accent; Tri.Off -> p.danger })
        Spacer(Modifier.width(6.dp)); T(label, size = 12.sp)
    }
}

/** For --test-meta: the same writer the dialog uses. */
internal fun applyMetadataForTest(items: List<FileItem>, created: String, modified: String, hidden: Boolean): String? =
    applyMetadata(items, created, modified, "", false, Tri.Keep, if (hidden) Tri.On else Tri.Keep, Tri.Keep, single = false)

/** Writes dates and attributes; returns a message for the files that couldn't be changed. */
private fun applyMetadata(items: List<FileItem>, created: String, modified: String, accessed: String, fromTaken: Boolean,
                          readOnly: Tri, hidden: Tri, archive: Tri, single: Boolean): String? {
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")
    fun parse(v: String): FileTime? = v.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { FileTime.from(LocalDateTime.parse(it.replace('/', '-').replace('T', ' '), fmt).atZone(ZoneId.systemDefault()).toInstant()) }.getOrNull()
            ?: throw IllegalArgumentException((if (S.zh) "日期格式不正確：" else "Not a date: ") + it)
    }
    val c: FileTime?; val m: FileTime?; val a: FileTime?
    try { c = parse(created); m = parse(modified); a = parse(accessed) } catch (e: IllegalArgumentException) { return e.message }
    if (fromTaken) items.groupBy { Fs.parent(it.path) }.forEach { (dir, l) -> FileProps.load(dir, l, listOf(DetailCol.DateTaken)) { false } }
    val failed = ArrayList<String>()
    for (f in items) {
        try {
            val path = Paths.get(f.path)
            val view = Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            // Read-only must be lifted before the times can be written.
            val wasReadOnly = view.readAttributes().isReadOnly
            if (wasReadOnly) view.setReadOnly(false)
            val taken = if (fromTaken) FileProps.of(f.path)?.shell?.get(DetailCol.DateTaken)?.let {
                runCatching { FileTime.from(LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.systemDefault()).toInstant()) }.getOrNull()
            } else null
            // A single file shows its current dates: only write the ones that were edited.
            val cur = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            fun pick(edited: FileTime?, now: FileTime) = if (edited == null || (single && kotlin.math.abs(edited.toMillis() - now.toMillis()) < 1000)) null else edited
            view.setTimes(pick(m, cur.lastModifiedTime()) ?: taken, pick(a, cur.lastAccessTime()), pick(c, cur.creationTime()) ?: taken)
            when (readOnly) { Tri.On -> view.setReadOnly(true); Tri.Off -> {}; Tri.Keep -> if (wasReadOnly) view.setReadOnly(true) }
            when (hidden) { Tri.On -> view.setHidden(true); Tri.Off -> view.setHidden(false); Tri.Keep -> {} }
            when (archive) { Tri.On -> view.setArchive(true); Tri.Off -> view.setArchive(false); Tri.Keep -> {} }
        } catch (e: Exception) { failed += f.name }
    }
    return if (failed.isEmpty()) null else (if (S.zh) "無法變更：" else "Couldn't change: ") + failed.take(5).joinToString(", ") + if (failed.size > 5) " …" else ""
}
