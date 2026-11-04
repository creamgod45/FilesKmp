package app.files

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.DosFileAttributes

/**
 * The 詳細資料 columns besides 名稱, as Explorer offers them (right-click the header, or the
 * column button at its right end). [shell] columns come from the Windows property system,
 * read once per folder in the background; [basic] ones from the file system.
 */
enum class DetailCol(val zh: String, val en: String, val defaultWidth: Int, val sort: SortBy? = null, val basic: Boolean = false, val shell: String? = null) {
    Tags("檔案標籤", "Tags", M.COL_TAG),
    DateModified("修改日期", "Date modified", M.COL_DATE_MODIFIED, SortBy.DateModified),
    Type("類型", "Type", M.COL_TYPE, SortBy.Type),
    Size("大小", "Size", M.COL_SIZE, SortBy.Size),
    DateCreated("建立日期", "Date created", 180, SortBy.DateCreated),
    DateAccessed("存取日期", "Date accessed", 180, basic = true),
    Extension("副檔名", "Extension", 90),
    Attributes("屬性", "Attributes", 80, basic = true),
    Owner("擁有者", "Owner", 160, basic = true),
    Folder("資料夾路徑", "Folder path", 240),
    Dimensions("尺寸", "Dimensions", 120, shell = "System.Image.Dimensions"),
    Duration("長度", "Length", 90, shell = "System.Media.Duration"),
    Title("標題", "Title", 180, shell = "System.Title"),
    Authors("作者", "Authors", 160, shell = "System.Author"),
    FileVersion("檔案版本", "File version", 120, shell = "System.FileVersion"),
    Company("公司", "Company", 160, shell = "System.Company"),
    DateTaken("拍攝日期", "Date taken", 160, shell = "System.Photo.DateTaken"),
    CameraModel("相機型號", "Camera model", 150, shell = "System.Photo.CameraModel"),
    Artist("演出者", "Contributing artists", 160, shell = "System.Music.Artist"),
    Album("專輯", "Album", 160, shell = "System.Music.AlbumTitle"),
    Track("曲目", "#", 50, shell = "System.Music.TrackNumber"),
    Comment("註解", "Comments", 200, shell = "System.Comment"),
    Keywords("標記", "Keywords", 160, shell = "System.Keywords");

    val label get() = if (S.zh) zh else en

    companion object {
        val defaults = listOf(Tags, DateModified, Type, Size)
        fun parse(text: String): List<DetailCol> =
            text.split(',').mapNotNull { n -> entries.firstOrNull { it.name == n.trim() } }.distinct()
    }
}

/** Extra facts about one item, filled in as they are read. */
class ExtraProps {
    @Volatile var accessed: Long = 0
    @Volatile var attributes: String? = null
    @Volatile var owner: String? = null
    val shell = java.util.concurrent.ConcurrentHashMap<DetailCol, String>()
}

object FileProps {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ExtraProps>()
    private val shellDone = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val basicDone = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun of(path: String): ExtraProps? = cache[path]

    /** Forget a folder's values (refresh). */
    fun invalidate(dir: String) {
        shellDone.remove(dir.lowercase()); basicDone.remove(dir.lowercase())
        cache.keys.removeIf { Fs.parent(it).equals(dir, true) }
    }

    /** Reads what [cols] need for [items] of folder [dir]. Returns true when something new was read. */
    fun load(dir: String?, items: List<FileItem>, cols: List<DetailCol>, cancelled: () -> Boolean): Boolean {
        var changed = false
        if (cols.any { it.basic }) {
            val key = (dir ?: items.hashCode().toString()).lowercase()
            if (basicDone.add(key)) {
                val wantOwner = DetailCol.Owner in cols
                for (it in items.take(20_000)) {
                    if (cancelled()) { basicDone.remove(key); return changed }
                    val props = cache.getOrPut(it.path) { ExtraProps() }
                    try {
                        val p = Paths.get(it.path)
                        val a = Files.readAttributes(p, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        props.accessed = a.lastAccessTime().toMillis()
                        props.attributes = buildString {
                            if (a.isReadOnly) append('R'); if (a.isHidden) append('H'); if (a.isSystem) append('S')
                            if (a.isArchive) append('A'); if (a.isSymbolicLink || a.isOther) append('L')
                        }
                        if (wantOwner) props.owner = runCatching { Files.getOwner(p, LinkOption.NOFOLLOW_LINKS).name }.getOrNull()
                    } catch (_: Exception) {}
                }
                changed = true
            }
        }
        val shellCols = cols.filter { it.shell != null }
        if (shellCols.isNotEmpty() && dir != null && !dir.startsWith("::") && File(dir).isDirectory && shellDone.add(dir.lowercase())) {
            // Every shell column at once, so turning on a second one later doesn't need another pass.
            val all = DetailCol.entries.filter { it.shell != null }
            val names = all.joinToString(",") { "'" + it.shell + "'" }
            val script = "\$f = (New-Object -ComObject Shell.Application).NameSpace(\$env:FILESKMP_DIR); " +
                "if (\$f) { foreach (\$i in \$f.Items()) { \$v = @(\$i.Path); foreach (\$k in @($names)) { \$x = \$i.ExtendedProperty(\$k); " +
                "if (\$x -is [array]) { \$x = \$x -join '; ' }; if (\$x -is [datetime]) { \$x = \$x.ToString('yyyy-MM-dd HH:mm:ss') }; " +
                "\$v += ([string]\$x) -replace '[\\t\\r\\n]', ' ' }; \$v -join [char]9 } }"
            val out = Fs.powershell(script, 90_000, mapOf("FILESKMP_DIR" to dir))
            if (out == null) { shellDone.remove(dir.lowercase()); return changed }
            for (line in out.lines()) {
                val f = line.split('\t')
                if (f.size < all.size + 1) continue
                val props = cache.getOrPut(f[0].trim()) { ExtraProps() }
                all.forEachIndexed { i, c ->
                    val v = clean(f[i + 1])
                    if (v.isNotEmpty()) props.shell[c] = if (c == DetailCol.Duration) duration(v) else v
                }
            }
            changed = true
        }
        return changed
    }

    /** Windows wraps some values in direction marks (‪1920 x 1080‬). */
    private fun clean(v: String) = v.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e]"), "").trim()

    /** System.Media.Duration is in 100-ns units. */
    private fun duration(v: String): String {
        val ticks = v.toLongOrNull() ?: return v
        val sec = ticks / 10_000_000
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** The text a column shows for [item] (Tags and Size are drawn by their own cells). */
    fun text(s: AppState, item: FileItem, c: DetailCol): String = when (c) {
        DetailCol.DateModified -> Fs.dateText(item.modified)
        DetailCol.Type -> item.typeName
        DetailCol.DateCreated -> Fs.dateText(item.created)
        DetailCol.DateAccessed -> of(item.path)?.accessed?.takeIf { it > 0 }?.let { Fs.dateText(it) } ?: ""
        DetailCol.Extension -> if (item.isDir) "" else item.ext.let { if (it.isEmpty()) "" else ".$it" }
        DetailCol.Attributes -> of(item.path)?.attributes ?: ""
        DetailCol.Owner -> of(item.path)?.owner ?: ""
        DetailCol.Folder -> Fs.parent(item.path) ?: ""
        DetailCol.Tags -> s.tagStore.tagsOf(item.path).joinToString(", ")
        DetailCol.Size -> if (item.isDir) s.folderSizes[item.path]?.let { Fs.sizeText(it) } ?: "" else Fs.sizeText(item.size)
        else -> of(item.path)?.shell?.get(c) ?: ""
    }

    /** Sort key for the columns [SortBy] doesn't cover. */
    fun sortKey(s: AppState, item: FileItem, c: DetailCol): Comparable<*> = when (c) {
        DetailCol.DateAccessed -> of(item.path)?.accessed ?: 0L
        DetailCol.Duration -> of(item.path)?.shell?.get(c)?.split(':')?.fold(0L) { acc, x -> acc * 60 + (x.toLongOrNull() ?: 0) } ?: -1L
        DetailCol.Dimensions -> of(item.path)?.shell?.get(c)?.split('x')?.mapNotNull { it.trim().toLongOrNull() }?.fold(1L) { a, b -> a * b } ?: -1L
        else -> text(s, item, c).lowercase()
    }
}
