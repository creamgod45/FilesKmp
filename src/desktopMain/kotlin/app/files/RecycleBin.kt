package app.files

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** The 資源回收筒 page. */
const val RECYCLE_BIN = "::recyclebin"

/** One deleted item: the `$R…` data and its `$I…` record (original path, size, when it was deleted). */
class RecycledEntry(val dataPath: String, val infoPath: String, val originalPath: String, val size: Long, val deleted: Long, val isDir: Boolean)

/**
 * The person's Recycle Bin, read straight from each drive's `$Recycle.Bin\<SID>` folder: every
 * deleted item is a `$I<id>` record (original path, size, deletion time) next to its `$R<id>` data.
 * Restoring moves `$R` back and removes `$I`, which is what Explorer does too. Emptying the whole
 * bin goes through Windows (SHEmptyRecycleBinW).
 */
object RecycleBin {
    /** `$R` path → record, from the last [list]. */
    @Volatile private var entries: Map<String, RecycledEntry> = emptyMap()

    private val userSid: String? by lazy {
        try {
            val p = ProcessBuilder("whoami", "/user", "/fo", "csv", "/nh").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            Regex("S-1-[0-9-]+").find(out)?.value
        } catch (_: Exception) { null }
    }

    /** This user's bin folder on every drive that has one. */
    private fun binFolders(): List<File> = File.listRoots().mapNotNull { root ->
        val bin = File(root, "\$Recycle.Bin")
        val sid = userSid
        if (sid != null) File(bin, sid).takeIf { it.isDirectory }
        // Without the SID: the folder this user can read (other people's are closed to us).
        else bin.listFiles()?.firstOrNull { it.name.startsWith("S-1-5-21") && it.listFiles() != null }
    }

    fun list(): List<FileItem> {
        val found = LinkedHashMap<String, RecycledEntry>()
        for (dir in binFolders()) {
            val infos = dir.listFiles { f -> f.name.startsWith("\$I") } ?: continue
            for (info in infos) {
                val data = File(dir, "\$R" + info.name.substring(2))
                if (!data.exists()) continue
                val rec = readInfo(info) ?: continue
                found[data.path] = RecycledEntry(data.path, info.path, rec.first, rec.second, rec.third, data.isDirectory)
            }
        }
        entries = found
        return found.values.map { e ->
            val name = e.originalPath.substringAfterLast('\\')
            FileItem(
                path = e.dataPath, name = name, isDir = e.isDir,
                size = if (e.isDir) -1L else e.size, modified = e.deleted, created = e.deleted,
                hidden = false, typeLabel = e.originalPath.substringBeforeLast('\\', e.originalPath),
            )
        }
    }

    fun entry(dataPath: String): RecycledEntry? = entries[dataPath]

    /** `$I` file → (original path, size, deletion time in ms). Version 1 (Vista–8.1) and 2 (10/11). */
    private fun readInfo(f: File): Triple<String, Long, Long>? = try {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val version = b.getLong(0)
        val size = b.getLong(8)
        val fileTime = b.getLong(16)
        val path = when (version) {
            1L -> String(b.array(), 24, minOf(520, b.capacity() - 24), StandardCharsets.UTF_16LE)
            2L -> { val chars = b.getInt(24); String(b.array(), 28, minOf(chars * 2, b.capacity() - 28), StandardCharsets.UTF_16LE) }
            else -> null
        }?.substringBefore('\u0000')
        if (path.isNullOrBlank()) null else Triple(path, size, fileTime / 10_000 - 11_644_473_600_000L)
    } catch (_: Exception) { null }

    /** Puts items back where they were; a name already in use gets " (2)" and so on. Returns how many failed. */
    fun restore(dataPaths: Collection<String>): Int {
        var failed = 0
        for (p in dataPaths) {
            val e = entries[p]
            if (e == null) { failed++; continue }
            try {
                val target = freeName(File(e.originalPath))
                target.parentFile?.mkdirs()
                java.nio.file.Files.move(File(e.dataPath).toPath(), target.toPath())
                File(e.infoPath).delete()
            } catch (_: Exception) { failed++ }
        }
        return failed
    }

    private fun freeName(f: File): File {
        if (!f.exists()) return f
        val stem = if (f.isDirectory) f.name else f.nameWithoutExtension
        val ext = if (f.isDirectory || f.extension.isEmpty()) "" else "." + f.extension
        var n = 2
        while (true) { val c = File(f.parentFile, "$stem ($n)$ext"); if (!c.exists()) return c; n++ }
    }

    /** Deletes chosen items from the bin for good (data and record). */
    fun purge(dataPaths: Collection<String>) {
        for (p in dataPaths) {
            val e = entries[p] ?: continue
            File(e.dataPath).deleteRecursively()
            File(e.infoPath).delete()
        }
    }

    /** Item count and bytes in the whole bin (all drives), from Windows; null if it can't say. */
    fun query(): Pair<Long, Long>? = try {
        Arena.ofConfined().use { arena ->
            val shell = SymbolLookup.libraryLookup("shell32", arena)
            val fn = Linker.nativeLinker().downcallHandle(
                shell.find("SHQueryRecycleBinW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )
            // SHQUERYRBINFO on x64: DWORD cbSize; (pad); __int64 i64Size; __int64 i64NumItems = 24 bytes
            val info = arena.allocate(24)
            info.set(ValueLayout.JAVA_INT, 0, 24)
            val hr = fn.invokeWithArguments(MemorySegment.NULL, info) as Int
            if (hr != 0) null else info.get(ValueLayout.JAVA_LONG, 16) to info.get(ValueLayout.JAVA_LONG, 8)
        }
    } catch (_: Throwable) { null }

    /** Empties the bin on every drive (Windows shows its own progress). True when it worked. */
    fun empty(): Boolean = try {
        Arena.ofConfined().use { arena ->
            val shell = SymbolLookup.libraryLookup("shell32", arena)
            val fn = Linker.nativeLinker().downcallHandle(
                shell.find("SHEmptyRecycleBinW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            )
            // SHERB_NOCONFIRMATION: the app has already asked. S_OK, or E_UNEXPECTED when it was already empty.
            val hr = fn.invokeWithArguments(MemorySegment.NULL, MemorySegment.NULL, 0x1) as Int
            hr == 0 || hr == 0x8000FFFF.toInt()
        }
    } catch (_: Throwable) { false }
}
