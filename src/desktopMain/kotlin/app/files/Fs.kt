package app.files

import kotlinx.coroutines.*
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.DosFileAttributes
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit

class FileItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val created: Long,
    val hidden: Boolean,
) {
    val ext: String get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
    val typeName: String
        get() = when {
            isDir -> "File folder"
            ext.isEmpty() -> "File"
            else -> "${ext.uppercase()} File"
        }
}

class DriveInfo(
    val root: String,
    val label: String,
    val total: Long,
    val free: Long,
    val ready: Boolean,
) {
    val used: Long get() = total - free
    val usedFraction: Float get() = if (total > 0) used.toFloat() / total else 0f
    val title: String get() = if (label.isNotBlank()) "$label (${root.trimEnd('\\')})" else "Local Disk (${root.trimEnd('\\')})"
}

class PinnedFolder(val name: String, val path: String)

class RecentFile(val name: String, val path: String, val modified: Long)

object Fs {
    private val sizeFmt = DecimalFormat("0.##")
    private val dateFmt: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

    /** Binary units, shown with the short names the original uses (KB, MB...). */
    fun sizeText(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return "${sizeFmt.format(v)} ${units[i]}"
    }

    fun dateText(millis: Long): String =
        if (millis <= 0) "" else dateFmt.format(Instant.ofEpochMilli(millis))

    fun displayName(path: String): String {
        val p = path.trimEnd('\\', '/')
        if (p.length <= 2 && p.endsWith(":")) return "$p\\"
        return p.substringAfterLast('\\').substringAfterLast('/').ifEmpty { path }
    }

    /** Reads a folder on the caller's (IO) thread. Attributes come with the listing, one pass. */
    fun list(dir: String): List<FileItem> {
        val out = ArrayList<FileItem>()
        Files.newDirectoryStream(Paths.get(dir)).use { stream ->
            for (p in stream) {
                try {
                    val a = Files.readAttributes(p, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    out += FileItem(
                        path = p.toString(),
                        name = p.fileName?.toString() ?: p.toString(),
                        isDir = a.isDirectory,
                        size = if (a.isDirectory) 0 else a.size(),
                        modified = a.lastModifiedTime().toMillis(),
                        created = a.creationTime().toMillis(),
                        hidden = a.isHidden || a.isSystem,
                    )
                } catch (_: Exception) {
                }
            }
        }
        return out
    }

    fun sort(items: List<FileItem>, sort: SortBy, ascending: Boolean): List<FileItem> {
        val cmp: Comparator<FileItem> = when (sort) {
            SortBy.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.DateModified -> compareBy { it.modified }
            SortBy.DateCreated -> compareBy { it.created }
            SortBy.Size -> compareBy { it.size }
            SortBy.Type -> compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.typeName }
        }
        val ordered = if (ascending) cmp else cmp.reversed()
        // Folders first (the original's default: not mixed, files not first).
        return items.sortedWith(compareBy<FileItem> { !it.isDir }.then(ordered))
    }

    /** Drives, each queried in parallel with its own time limit so a sleeping or offline disk can't hold up the rest. */
    suspend fun drives(): List<DriveInfo> = coroutineScope {
        File.listRoots().map { root ->
            async(Dispatchers.IO) {
                val r = root.path
                val info = withTimeoutOrNull(M.DRIVE_QUERY_TIMEOUT_MS) {
                    runInterruptible {
                        val store = try { Files.getFileStore(root.toPath()) } catch (_: Exception) { null }
                        DriveInfo(
                            root = r,
                            label = store?.name() ?: "",
                            total = store?.totalSpace ?: root.totalSpace,
                            free = store?.usableSpace ?: root.usableSpace,
                            ready = true,
                        )
                    }
                }
                info ?: DriveInfo(r, "", 0, 0, ready = false)
            }
        }.awaitAll()
    }

    /** Runs PowerShell once and returns its UTF-8 output, or null after the time limit. */
    fun powershell(script: String, timeoutMs: Long): String? {
        return try {
            val full = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; $script"
            // -EncodedCommand avoids every quoting problem with quotes and tabs in the script.
            val encoded = java.util.Base64.getEncoder().encodeToString(full.toByteArray(StandardCharsets.UTF_16LE))
            val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(false).start()
            p.outputStream.close()
            val text = StringBuilder()
            val t = Thread.ofVirtual().start {
                text.append(String(p.inputStream.readAllBytes(), StandardCharsets.UTF_8))
            }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly(); return null
            }
            t.join(1000)
            text.toString()
        } catch (_: Exception) {
            null
        }
    }

    /** Windows' own Quick access pins (the original mirrors these), with known folders as the fallback. */
    fun quickAccess(): List<PinnedFolder> {
        val script = "\$s=New-Object -ComObject Shell.Application; " +
            "\$s.Namespace('shell:::{679f85cb-0220-4080-b29b-5540cc05aab6}').Items() | " +
            "Where-Object { \$_.IsFolder -and \$_.Path -match '^[A-Za-z]:\\\\|^\\\\\\\\' } | " +
            "ForEach-Object { \$_.Name + [char]9 + \$_.Path }"
        val out = powershell(script, M.SHELL_QUERY_TIMEOUT_MS)
        val pins = out?.lines()?.mapNotNull { line ->
            val parts = line.trim().split('\t')
            if (parts.size == 2 && parts[1].isNotBlank()) PinnedFolder(parts[0], parts[1]) else null
        }.orEmpty()
        if (pins.isNotEmpty()) return pins
        val home = System.getProperty("user.home")
        return listOf("Desktop", "Downloads", "Documents", "Pictures", "Music", "Videos")
            .map { PinnedFolder(it, "$home\\$it") }
            .filter { File(it.path).isDirectory }
    }

    /** Recent files from Windows' Recent folder (shortcut names, newest first), capped. */
    fun recentFiles(): List<RecentFile> {
        val dir = File(System.getenv("APPDATA") ?: return emptyList(), "Microsoft\\Windows\\Recent")
        val links = dir.listFiles { f -> f.isFile && f.name.endsWith(".lnk", true) } ?: return emptyList()
        return links.sortedByDescending { it.lastModified() }
            .take(M.RECENT_FILES_LIMIT)
            .map { RecentFile(it.name.removeSuffix(".lnk").removeSuffix(".LNK"), it.path, it.lastModified()) }
    }

    fun open(path: String) {
        try {
            ProcessBuilder("cmd.exe", "/c", "start", "\"\"", path).start()
        } catch (_: Exception) {
        }
    }

    fun openInTerminal(dir: String) {
        try {
            ProcessBuilder("cmd.exe", "/c", "start", "wt.exe", "-d", dir).start()
        } catch (_: Exception) {
        }
    }

    fun newFolder(dir: String, base: String = "New folder"): String {
        var name = base
        var i = 2
        while (File(dir, name).exists()) name = "$base ($i)".also { i++ }
        File(dir, name).mkdirs()
        return File(dir, name).path
    }

    fun newFile(dir: String, base: String = "New Text Document", ext: String = ".txt"): String {
        var name = "$base$ext"
        var i = 2
        while (File(dir, name).exists()) name = "$base ($i)$ext".also { i++ }
        File(dir, name).createNewFile()
        return File(dir, name).path
    }

    fun rename(path: String, newName: String): String? {
        val src = Paths.get(path)
        val dst = src.resolveSibling(newName)
        return try { Files.move(src, dst).toString() } catch (_: Exception) { null }
    }

    /** Deletes to the Recycle Bin (as the original does by default). */
    fun recycle(paths: List<String>): Boolean {
        val d = java.awt.Desktop.getDesktop()
        var ok = true
        for (p in paths) {
            ok = try { d.moveToTrash(File(p)) } catch (_: Exception) { false } && ok
        }
        return ok
    }

    fun deletePermanently(paths: List<String>) {
        for (p in paths) File(p).deleteRecursively()
    }

    fun parent(path: String): String? {
        val p = Paths.get(path).parent ?: return null
        return p.toString()
    }

    fun breadcrumbs(path: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var p: Path? = Paths.get(path)
        while (p != null) {
            out += displayName(p.toString()) to p.toString()
            p = p.parent
        }
        return out.reversed()
    }
}
