package app.files

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Folder listing straight from Windows (FindFirstFileExW with FindExInfoBasic and
 * FIND_FIRST_EX_LARGE_FETCH): name, attributes, size and times all come back with the directory
 * read itself, so a folder of 150 000 files costs one pass over the directory — no per-file
 * open/stat like the JDK's readAttributes does. Explorer and Everything read folders this way.
 * Returns null when it can't be used (not Windows, JVM option missing); callers fall back to NIO.
 */
object FastDir {
    private const val FIND_DATA_SIZE = 592L
    private const val ATTR_HIDDEN = 0x2
    private const val ATTR_SYSTEM = 0x4
    private const val ATTR_DIRECTORY = 0x10
    private const val ATTR_REPARSE = 0x400
    private const val EPOCH_DIFF_MS = 11_644_473_600_000L

    private class Fns(val first: MethodHandle, val next: MethodHandle, val close: MethodHandle)

    private val fns: Fns? by lazy {
        try {
            val k32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
            val l = Linker.nativeLinker()
            Fns(
                l.downcallHandle(k32.find("FindFirstFileExW").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                l.downcallHandle(k32.find("FindNextFileW").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                l.downcallHandle(k32.find("FindClose").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
            )
        } catch (_: Throwable) { null }
    }

    val available: Boolean get() = fns != null

    /** One raw entry, for callers that don't need a [FileItem] (the search index). */
    class Entry(val name: String, val attributes: Int, val size: Long, val created: Long, val modified: Long) {
        val isDir get() = attributes and ATTR_DIRECTORY != 0
        val isReparse get() = attributes and ATTR_REPARSE != 0
    }

    /** Calls [onEntry] for every entry of [dir] (not "." / ".."). False when the folder can't be read. */
    fun forEach(dir: String, cancelled: () -> Boolean = { false }, onEntry: (Entry) -> Unit): Boolean {
        val f = fns ?: return false
        return Arena.ofConfined().use { arena ->
            val base = dir.trimEnd('\\')
            // Long paths need the \\?\ form (\\?\UNC\server\share for network paths).
            val pattern = when {
                base.length < 240 || base.startsWith("\\\\?\\") -> "$base\\*"
                base.startsWith("\\\\") -> "\\\\?\\UNC\\" + base.removePrefix("\\\\") + "\\*"
                else -> "\\\\?\\$base\\*"
            }
            val data = arena.allocate(FIND_DATA_SIZE, 8)
            val h = f.first.invokeWithArguments(arena.allocateFrom(pattern, Charsets.UTF_16LE), 1, data, 0, MemorySegment.NULL, 2) as MemorySegment
            if (h.address() == -1L || h.address() == 0L) return@use false
            try {
                do {
                    if (cancelled()) break
                    val name = data.getString(44, Charsets.UTF_16LE)
                    if (name == "." || name == "..") continue
                    val attrs = data.get(ValueLayout.JAVA_INT, 0)
                    fun time(off: Long): Long {
                        val low = data.get(ValueLayout.JAVA_INT, off).toLong() and 0xFFFFFFFFL
                        val high = data.get(ValueLayout.JAVA_INT, off + 4).toLong() and 0xFFFFFFFFL
                        val ft = (high shl 32) or low
                        return if (ft == 0L) 0L else ft / 10_000 - EPOCH_DIFF_MS
                    }
                    val size = ((data.get(ValueLayout.JAVA_INT, 28).toLong() and 0xFFFFFFFFL) shl 32) or (data.get(ValueLayout.JAVA_INT, 32).toLong() and 0xFFFFFFFFL)
                    onEntry(Entry(name, attrs, size, time(4), time(20)))
                } while (f.next.invokeWithArguments(h, data) as Int != 0)
            } finally {
                f.close.invokeWithArguments(h)
            }
            true
        }
    }

    fun toItem(dir: String, e: Entry): FileItem {
        val path = if (dir.endsWith("\\")) dir + e.name else dir + "\\" + e.name
        return FileItem(
            path = path, name = e.name, isDir = e.isDir, size = if (e.isDir) 0 else e.size,
            modified = e.modified, created = e.created,
            hidden = e.attributes and ATTR_HIDDEN != 0,
            system = isSystemItem(e.attributes and ATTR_SYSTEM != 0, e.name),
        )
    }

    /** Whole folder as items, or null when FastDir can't read it. */
    fun list(dir: String): List<FileItem>? {
        val out = ArrayList<FileItem>()
        return if (forEach(dir) { out.add(toItem(dir, it)) }) out else null
    }

}
