package app.files

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.*
import java.nio.file.attribute.DosFileAttributeView

/**
 * Why deleting a whole folder can ask for an administrator while deleting it piece by piece,
 * from the inside out, works — and how this app avoids it:
 *
 *  - Sending a folder to the Recycle Bin MOVES the whole tree in one go. That needs the DELETE
 *    right on the top folder itself (not just "delete subfolders and files" on its parent), and no
 *    open handle anywhere inside (the search indexer, antivirus, a preview / thumbnail handler, a
 *    console whose current folder is in there). Corporate PCs, with their own permissions on
 *    folders and scanners holding files, hit this far more often. Explorer then asks for an
 *    administrator, although each file on its own can be deleted.
 *  - Read-only files: deleting them needs their attribute cleared first.
 *  - "Delete pending": a deleted file that something still has open keeps its name until that
 *    handle closes, so its folder is "not empty" for a moment.
 *  - Paths longer than 260 characters (deep node_modules).
 *
 * [deleteTree] does what works by hand: files first, deepest folders first, read-only lifted,
 * short retries for delete-pending folders, links removed as links (never followed into their
 * target). For what still fails, Windows' Restart Manager names the program that has it open.
 */
object SafeDelete {
    class Failure(val path: String, val reason: String)
    class Result(val removed: Int, val failures: List<Failure>)

    /**
     * Deletes [paths] (files or folders). [toRecycleBin]: each file goes to the Recycle Bin on its
     * own (restoring puts it back in its folder), the emptied folders are removed.
     */
    fun deleteTree(paths: List<String>, toRecycleBin: Boolean, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): Result {
        var removed = 0
        val failures = ArrayList<Failure>()
        fun file(p: String) {
            val err = deleteFile(p, toRecycleBin)
            if (err == null) { removed++; if (removed % 200 == 0) progress(removed) } else failures += Failure(p, err)
        }
        fun dir(root: String) {
            // Iterative post-order: children before their folder.
            val stack = ArrayDeque<Pair<String, Boolean>>().apply { add(root to false) }
            while (stack.isNotEmpty() && !cancelled()) {
                val (d, expanded) = stack.removeLast()
                if (expanded) {
                    val err = deleteDir(d)
                    if (err == null) removed++ else failures += Failure(d, err)
                    continue
                }
                stack.add(d to true)
                val ok = FastDir.forEach(d, cancelled) { e ->
                    val child = if (d.endsWith("\\")) d + e.name else d + "\\" + e.name
                    when {
                        e.isDir && e.isReparse -> { val err = deleteLink(child); if (err == null) removed++ else failures += Failure(child, err) }
                        e.isDir -> stack.add(child to false)
                        else -> file(child)
                    }
                }
                if (!ok && !FastDir.available) runCatching {
                    Files.newDirectoryStream(Paths.get(d)).use { st -> for (c in st) {
                        val a = Files.readAttributes(c, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        when { a.isSymbolicLink || a.isOther -> deleteLink(c.toString()); a.isDirectory -> stack.add(c.toString() to false); else -> file(c.toString()) }
                    } }
                }
            }
        }
        for (p in paths) {
            if (cancelled()) break
            val a = runCatching { Files.readAttributes(Paths.get(p), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            when {
                a == null -> failures += Failure(p, if (S.zh) "找不到此項目" else "Not found")
                a.isSymbolicLink || (a.isOther && a.isDirectory) -> deleteLink(p)?.let { failures += Failure(p, it) } ?: removed++
                a.isDirectory -> dir(p)
                else -> file(p)
            }
        }
        progress(removed)
        return Result(removed, failures)
    }

    private fun clearReadOnly(p: String) = runCatching {
        Files.getFileAttributeView(Paths.get(p), DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.let { v ->
            val a = v.readAttributes()
            if (a.isReadOnly) v.setReadOnly(false)
            if (a.isSystem) v.setSystem(false)
        }
    }

    private fun deleteFile(p: String, toRecycleBin: Boolean): String? {
        repeat(3) { attempt ->
            try {
                if (toRecycleBin) {
                    if (java.awt.Desktop.getDesktop().moveToTrash(File(p))) return null
                    if (attempt == 0) { clearReadOnly(p); return@repeat }
                    return explain(p, null)
                }
                Files.delete(Paths.get(p)); return null
            } catch (e: NoSuchFileException) { return null
            } catch (e: AccessDeniedException) {
                if (attempt == 0) clearReadOnly(p) else return explain(p, e)
            } catch (e: FileSystemException) {
                if (attempt < 2) Thread.sleep(60L * (attempt + 1)) else return explain(p, e)
            } catch (e: Exception) { return explain(p, e) }
        }
        return explain(p, null)
    }

    private fun deleteLink(p: String): String? = try {
        // A junction / directory symlink: RemoveDirectory takes away the link only, not what it points to.
        Files.delete(Paths.get(p)); null
    } catch (e: Exception) { explain(p, e) }

    private fun deleteDir(d: String): String? {
        for (attempt in 0 until 5) {
            try { Files.delete(Paths.get(d)); return null }
            catch (_: NoSuchFileException) { return null }
            catch (e: DirectoryNotEmptyException) {
                // Children deleted a moment ago may still be "delete pending" while something has them open.
                Thread.sleep(40L shl attempt)
                if (attempt == 4) {
                    val left = File(d).list()?.size ?: 0
                    return if (left == 0) explain(d, e) else (if (S.zh) "資料夾內還有 $left 個無法刪除的項目" else "$left item(s) inside couldn't be deleted")
                }
            }
            catch (e: AccessDeniedException) { if (attempt == 0) clearReadOnly(d) else return explain(d, e) }
            catch (e: Exception) { return explain(d, e) }
        }
        return null
    }

    /** A readable reason, naming the programs that have the item open when Windows can tell. */
    private fun explain(p: String, e: Exception?): String {
        val users = whoHasOpen(listOf(p))
        if (users.isNotEmpty()) return (if (S.zh) "使用中：" else "In use by ") + users.joinToString(", ")
        return when (e) {
            is AccessDeniedException -> if (S.zh) "權限不足：您沒有此項目的刪除權限（請聯絡系統管理員）" else "Access denied: you don't have delete permission on this item"
            is DirectoryNotEmptyException -> if (S.zh) "資料夾仍在使用或不是空的" else "Folder still in use or not empty"
            null -> if (S.zh) "無法移到資源回收筒" else "Couldn't move it to the Recycle Bin"
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * Programs with any of [files] open, via the Restart Manager (RmStartSession / RmRegisterResources /
     * RmGetList) — the same API Windows' "file in use" dialog uses. Names like "Code.exe (1234)".
     */
    fun whoHasOpen(files: List<String>): List<String> = try {
        Arena.ofConfined().use { arena ->
            val rm = SymbolLookup.libraryLookup("rstrtmgr", arena)
            val l = Linker.nativeLinker()
            val I = ValueLayout.JAVA_INT; val A = ValueLayout.ADDRESS
            val start = l.downcallHandle(rm.find("RmStartSession").orElseThrow(), FunctionDescriptor.of(I, A, I, A))
            val register = l.downcallHandle(rm.find("RmRegisterResources").orElseThrow(), FunctionDescriptor.of(I, I, I, A, I, A, I, A))
            val getList = l.downcallHandle(rm.find("RmGetList").orElseThrow(), FunctionDescriptor.of(I, I, A, A, A, A))
            val end = l.downcallHandle(rm.find("RmEndSession").orElseThrow(), FunctionDescriptor.of(I, I))
            val session = arena.allocate(I)
            val key = arena.allocate(2L * 33)
            if (start.invokeWithArguments(session, 0, key) as Int != 0) return@use emptyList()
            val sid = session.get(I, 0)
            try {
                val names = arena.allocate(A, files.size.toLong())
                files.forEachIndexed { i, f -> names.setAtIndex(A, i.toLong(), arena.allocateFrom(f, Charsets.UTF_16LE)) }
                if (register.invokeWithArguments(sid, files.size, names, 0, MemorySegment.NULL, 0, MemorySegment.NULL) as Int != 0) return@use emptyList()
                val needed = arena.allocate(I); val count = arena.allocate(I); val reasons = arena.allocate(I)
                // RM_PROCESS_INFO = 668 bytes: pid(4) + start time(8) + app name[256] + service[64] + type, status, session, restartable.
                var cap = 16
                var info = arena.allocate(668L * cap, 4)
                count.set(I, 0, cap)
                var rc = getList.invokeWithArguments(sid, needed, count, info, reasons) as Int
                if (rc == 234) { cap = needed.get(I, 0); info = arena.allocate(668L * cap, 4); count.set(I, 0, cap); rc = getList.invokeWithArguments(sid, needed, count, info, reasons) as Int }
                if (rc != 0) return@use emptyList()
                (0 until count.get(I, 0)).map { i ->
                    val base = 668L * i
                    val pid = info.get(I, base)
                    val app = info.getString(base + 12, Charsets.UTF_16LE).ifBlank { "PID" }
                    "$app ($pid)"
                }.distinct()
            } finally { end.invokeWithArguments(sid) }
        }
    } catch (_: Throwable) { emptyList() }
}
