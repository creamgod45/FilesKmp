package app.files

import androidx.compose.runtime.*
import java.io.File
import java.util.Properties

/**
 * Where the app writes, following Windows' per-user data locations:
 * - settings, tags, toolbar, last session: %APPDATA%\FilesKmp (roaming user settings)
 * - everything that can be rebuilt (thumbnails, Skia's native library): %LOCALAPPDATA%\FilesKmp\Cache
 * The program itself is installed in %LOCALAPPDATA%\Programs\FilesKmp, apart from both.
 */
object AppDirs {
    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
    val settings: File = File(env("APPDATA") ?: System.getProperty("user.home"), "FilesKmp")
    val cache: File = File(File(env("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "FilesKmp"), "Cache")

    /** Call first thing in main(), before Compose loads Skia (which otherwise unpacks into %USERPROFILE%\.skiko). */
    fun init() {
        if (System.getProperty("skiko.data.path") == null) System.setProperty("skiko.data.path", File(cache, "skiko").path)
        // Thumbnails used to live in %LOCALAPPDATA%\FilesKmp\thumbs, which was also the install folder.
        Thread {
            runCatching { File(cache.parentFile, "thumbs").takeIf { it.isDirectory }?.deleteRecursively() }
        }.apply { isDaemon = true; start() }
    }
}

/** Where the app keeps its settings, tags and last session (%APPDATA%\FilesKmp). */
object Prefs {
    val dir: File = AppDirs.settings.apply { mkdirs() }
    private val file = File(dir, "settings.properties")
    private val props = Properties()

    init {
        try { if (file.exists()) file.reader(Charsets.UTF_8).use { props.load(it) } } catch (_: Exception) {}
    }

    fun str(key: String, def: String): String = props.getProperty(key) ?: def
    fun bool(key: String, def: Boolean): Boolean = props.getProperty(key)?.toBooleanStrictOrNull() ?: def
    fun int(key: String, def: Int): Int = props.getProperty(key)?.toIntOrNull() ?: def

    fun put(values: Map<String, Any?>) {
        values.forEach { (k, v) -> if (v == null) props.remove(k) else props.setProperty(k, v.toString()) }
        try { file.writer(Charsets.UTF_8).use { props.store(it, "FilesKmp settings") } } catch (_: Exception) {}
    }
}

enum class ThemeMode { System, Light, Dark }

/** A tag definition. Colours and the four starting tags follow the original's defaults. */
data class TagDef(val name: String, val color: Long)

/**
 * File tags: which tags exist and which files carry them. The original keeps this in a
 * database keyed by file ID; here it is a small text file keyed by path, and renames and
 * moves made in this app update it.
 */
class TagStore {
    private val defsFile = File(Prefs.dir, "tags.txt")
    private val mapFile = File(Prefs.dir, "tagged-files.tsv")

    var defs by mutableStateOf(loadDefs())
        private set
    var byPath by mutableStateOf(loadMap())
        private set

    private fun loadDefs(): List<TagDef> = try {
        if (!defsFile.exists()) DefaultTags.map { TagDef(it.name, it.color) }
        else defsFile.readLines(Charsets.UTF_8).mapNotNull { l ->
            val parts = l.split('\t')
            if (parts.size == 2) TagDef(parts[0], parts[1].toLongOrNull(16) ?: 0xFF9EA3A1) else null
        }
    } catch (_: Exception) { DefaultTags.map { TagDef(it.name, it.color) } }

    private fun loadMap(): Map<String, Set<String>> = try {
        if (!mapFile.exists()) emptyMap()
        else mapFile.readLines(Charsets.UTF_8).mapNotNull { l ->
            val i = l.lastIndexOf('\t')
            if (i <= 0) null else l.substring(0, i) to l.substring(i + 1).split('|').filter { it.isNotBlank() }.toSet()
        }.filter { it.second.isNotEmpty() }.toMap()
    } catch (_: Exception) { emptyMap() }

    private fun saveDefs() {
        try { defsFile.writeText(defs.joinToString("\n") { "${it.name}\t${java.lang.Long.toHexString(it.color)}" }, Charsets.UTF_8) } catch (_: Exception) {}
    }

    private fun saveMap() {
        try { mapFile.writeText(byPath.entries.joinToString("\n") { "${it.key}\t${it.value.joinToString("|")}" }, Charsets.UTF_8) } catch (_: Exception) {}
    }

    fun tagsOf(path: String): List<TagDef> {
        val names = byPath[path] ?: return emptyList()
        return defs.filter { it.name in names }
    }

    fun color(name: String): Long = defs.firstOrNull { it.name == name }?.color ?: 0xFF9EA3A1

    fun filesWith(tag: String): List<String> = byPath.filter { tag in it.value }.keys.sortedBy { it.lowercase() }

    /** Turns [tag] on for every path when [on], off otherwise. */
    fun set(paths: Collection<String>, tag: String, on: Boolean) {
        val m = byPath.toMutableMap()
        for (p in paths) {
            val cur = m[p].orEmpty()
            val next = if (on) cur + tag else cur - tag
            if (next.isEmpty()) m.remove(p) else m[p] = next
        }
        byPath = m; saveMap()
    }

    fun clear(paths: Collection<String>) {
        byPath = byPath - paths.toSet(); saveMap()
    }

    /** Keeps tags attached when a file or folder is renamed or moved inside the app. */
    fun movePath(from: String, to: String) {
        val m = LinkedHashMap<String, Set<String>>()
        var changed = false
        for ((p, t) in byPath) {
            val np = when {
                p.equals(from, true) -> to
                p.startsWith(from + "\\", true) -> to + p.substring(from.length)
                else -> p
            }
            if (np != p) changed = true
            m[np] = t
        }
        if (changed) { byPath = m; saveMap() }
    }

    fun addTag(name: String, color: Long): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.contains('|') || n.contains('\t') || defs.any { it.name.equals(n, true) }) return false
        defs = defs + TagDef(n, color); saveDefs(); return true
    }

    fun renameTag(old: String, new: String): Boolean {
        val n = new.trim()
        if (n.isEmpty() || n.contains('|') || n.contains('\t') || (n != old && defs.any { it.name.equals(n, true) })) return false
        defs = defs.map { if (it.name == old) it.copy(name = n) else it }
        byPath = byPath.mapValues { (_, t) -> if (old in t) t - old + n else t }
        saveDefs(); saveMap(); return true
    }

    fun recolor(name: String, color: Long) {
        defs = defs.map { if (it.name == name) it.copy(color = color) else it }; saveDefs()
    }

    fun deleteTag(name: String) {
        defs = defs.filter { it.name != name }
        byPath = byPath.mapValues { it.value - name }.filter { it.value.isNotEmpty() }
        saveDefs(); saveMap()
    }
}

/** Colour choices offered when making or recolouring a tag (the original's picker starts from these hues). */
val TagPalette = listOf(
    0xFF0072BD, 0xFFD95319, 0xFFEDB120, 0xFF77AC30, 0xFF7E2F8E, 0xFF4DBEEE,
    0xFFA2142F, 0xFFE3008C, 0xFF00B294, 0xFF9EA3A1,
)
