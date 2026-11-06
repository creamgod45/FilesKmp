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
import kotlin.time.Duration.Companion.milliseconds

/** OS-protected item: System attribute, or a "$" name ($Recycle.Bin, $WinREAgent, …). */
fun isSystemItem(systemAttr: Boolean, name: String?) = systemAttr || name?.startsWith("$") == true

class FileItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val created: Long,
    /** Hidden attribute set (ordinary hidden item), see [system] for OS-protected items. */
    val hidden: Boolean,
    /** System attribute set, or a "$" name such as $Recycle.Bin: protected operating-system item. */
    val system: Boolean = false,
    /** Shown in the 類型 column instead of the file type (network servers, shares, exports). */
    val typeLabel: String? = null,
) {
    val ext: String get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
    val typeName: String
        get() = when {
            typeLabel != null -> typeLabel
            isDir -> S.fileFolder
            ext.isEmpty() -> S.file
            else -> if (S.zh) "${ext.uppercase()} 檔案" else "${ext.uppercase()} File"
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
    val title: String get() = if (label.isNotBlank()) "$label (${root.trimEnd('\\')})" else "${if (S.zh) "本機磁碟" else "Local Disk"} (${root.trimEnd('\\')})"
    val isSystem: Boolean get() = root.equals((System.getenv("SystemDrive") ?: "C:") + "\\", true)
}

class CloudDrive(val name: String, val path: String)

/** Default file tags (FileTagsSettingsService.DefaultFileTags in the original). */
class FileTag(val name: String, val color: Long)

val DefaultTags = listOf(
    FileTag("Home", 0xFF0072BD),
    FileTag("Work", 0xFFD95319),
    FileTag("Photos", 0xFFEDB120),
    FileTag("Important", 0xFF77AC30),
)

class PinnedFolder(val name: String, val path: String)

/**
 * One entry of Windows' Recent list: [path] is the shortcut in %APPDATA%\Microsoft\Windows\Recent, [target]
 * the file or folder it points to (null when the shortcut can't be read), [exists] whether that is still there.
 */
class RecentFile(val name: String, val path: String, val modified: Long, val target: String? = null, val exists: Boolean = true, val isDir: Boolean = false)

/**
 * Reads a shortcut's target straight from the .lnk file (MS-SHLLINK: header, optional ID list, then LinkInfo
 * with the local base path or the network share plus the common path suffix). Fast enough to run for every
 * Recent entry; null when the link has no LinkInfo path (e.g. a shell-namespace item).
 */
object LnkFile {
    fun target(lnk: File): String? = runCatching { parse(lnk.readBytes()) }.getOrNull()?.takeIf { it.isNotBlank() }

    internal fun parse(b: ByteArray): String? {
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (b.size < 0x4C || buf.getInt(0) != 0x4C) return null
        val flags = buf.getInt(0x14)
        var at = 0x4C
        if (flags and 0x1 != 0) at += 2 + (buf.getShort(at).toInt() and 0xFFFF)          // HasLinkTargetIDList
        if (flags and 0x2 == 0) return null                                               // HasLinkInfo
        val li = at
        val headerSize = buf.getInt(li + 4)
        val liFlags = buf.getInt(li + 8)
        val ansi = java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding") ?: "windows-1252")
        fun cstr(off: Int): String { var e = off; while (e < b.size && b[e] != 0.toByte()) e++; return String(b, off, e - off, ansi) }
        fun wstr(off: Int): String { var e = off; while (e + 1 < b.size && (b[e] != 0.toByte() || b[e + 1] != 0.toByte())) e += 2; return String(b, off, e - off, Charsets.UTF_16LE) }
        val suffix = if (headerSize >= 0x24 && buf.getInt(li + 0x20) != 0) wstr(li + buf.getInt(li + 0x20)) else buf.getInt(li + 0x18).takeIf { it != 0 }?.let { cstr(li + it) } ?: ""
        fun join(base: String) = if (suffix.isEmpty()) base else base.trimEnd('\\') + "\\" + suffix
        if (liFlags and 0x1 != 0) {                                                       // VolumeIDAndLocalBasePath
            val base = if (headerSize >= 0x24 && buf.getInt(li + 0x1C) != 0) wstr(li + buf.getInt(li + 0x1C)) else cstr(li + buf.getInt(li + 0x10))
            if (base.isNotEmpty()) return join(base)
        }
        if (liFlags and 0x2 != 0) {                                                       // CommonNetworkRelativeLink
            val net = li + buf.getInt(li + 0x14)
            val netNameOff = buf.getInt(net + 8)
            val name = if (netNameOff > 0x14) wstr(net + buf.getInt(net + 0x14)) else cstr(net + netNameOff)
            if (name.isNotEmpty()) return join(name)
        }
        return null
    }
}

object Fs {
    /** Dates follow the app language (zh-TW gives 2026年7月6日 星期一, as the original shows). */
    private val uiLocale: java.util.Locale = if (S.zh) java.util.Locale.TRADITIONAL_CHINESE else java.util.Locale.getDefault()
    private val sizeFmt = DecimalFormat("0.##")
    private val dateFmt: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(uiLocale).withZone(ZoneId.systemDefault())

    /** Binary units (1024), named KiB/MiB/GiB/TiB as the original shows them. */
    fun sizeText(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return "${sizeFmt.format(v)} ${units[i]}"
    }

    private val longDate: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(uiLocale).withZone(ZoneId.systemDefault())

    /**
     * The original's default "Application" date format (ApplicationDateTimeFormatter.ToShortLabel):
     * a week or older -> long date ("D", e.g. 2026年7月6日 星期一); otherwise days / hours /
     * minutes / seconds ago, and "now" under a second.
     */
    fun dateText(millis: Long): String {
        if (millis <= 0) return ""
        val elapsed = System.currentTimeMillis() - millis
        val sec = elapsed / 1000
        return when {
            elapsed < 0 -> longDate.format(Instant.ofEpochMilli(millis))
            sec >= 7 * 86400 -> longDate.format(Instant.ofEpochMilli(millis))
            sec >= 86400 -> S.daysAgo(sec / 86400)
            sec >= 3600 -> S.hoursAgo(sec / 3600)
            sec >= 60 -> S.minutesAgo(sec / 60)
            sec >= 1 -> S.secondsAgo(sec)
            else -> S.now
        }
    }

    /** Full date and time, for the details pane. */
    fun dateTimeText(millis: Long): String = if (millis <= 0) "" else dateFmt.format(Instant.ofEpochMilli(millis))

    fun displayName(path: String): String {
        val p = path.trimEnd('\\', '/')
        if (p.length <= 2 && p.endsWith(":")) return "$p\\"
        return p.substringAfterLast('\\').substringAfterLast('/').ifEmpty { path }
    }

    /** Reads a folder on the caller's (IO) thread. Attributes come with the listing, one pass. */
    /**
     * Lists [dir] in chunks so a folder with hundreds of thousands of entries shows its first items
     * at once: the first [firstChunk] entries go out immediately, then whatever has been read every
     * [intervalMs]. Attributes come from the directory read itself (Windows' FindNextFile data,
     * which the JDK caches on each path), so there is no extra disk access per file.
     */
    fun listChunked(dir: String, cancelled: () -> Boolean, firstChunk: Int = 300, intervalMs: Long = 120, onChunk: (List<FileItem>) -> Unit) {
        var chunk = ArrayList<FileItem>(firstChunk)
        var sentFirst = false
        var last = System.nanoTime()
        fun add(item: FileItem) {
            chunk.add(item)
            val now = System.nanoTime()
            if ((!sentFirst && chunk.size >= firstChunk) || (sentFirst && (now - last) / 1_000_000 >= intervalMs)) {
                onChunk(chunk); chunk = ArrayList(4096); sentFirst = true; last = now
            }
        }
        // Native directory read first (FastDir); the JDK's per-file stat only when that isn't available.
        val native = FastDir.available && FastDir.forEach(dir, cancelled) { add(FastDir.toItem(dir, it)) }
        if (!native) {
            if (FastDir.available && !File(dir).isDirectory) throw java.io.FileNotFoundException(dir)
            Files.newDirectoryStream(Paths.get(dir)).use { stream ->
                for (p in stream) {
                    if (cancelled()) return
                    toItem(p)?.let { add(it) }
                }
            }
        }
        if (!cancelled()) onChunk(chunk)
    }

    private fun toItem(p: Path): FileItem? = try {
        val a = Files.readAttributes(p, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val name = p.fileName?.toString() ?: p.toString()
        FileItem(
            path = p.toString(), name = name, isDir = a.isDirectory,
            size = if (a.isDirectory) 0 else a.size(),
            modified = a.lastModifiedTime().toMillis(), created = a.creationTime().toMillis(),
            hidden = a.isHidden, system = isSystemItem(a.isSystem, name),
        )
    } catch (_: Exception) { null }

    fun list(dir: String): List<FileItem> {
        FastDir.list(dir)?.let { return it }
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
                        hidden = a.isHidden,
                        system = isSystemItem(a.isSystem, p.fileName?.toString()),
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
                val info = withTimeoutOrNull(M.DRIVE_QUERY_TIMEOUT_MS.milliseconds) {
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
    /** Runs [script]; [env] values reach it as $env:NAME, so data never has to be quoted into the script. */
    fun powershell(script: String, timeoutMs: Long, env: Map<String, String> = emptyMap()): String? {
        return try {
            val full = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; $script"
            // -EncodedCommand avoids every quoting problem with quotes and tabs in the script.
            val encoded = java.util.Base64.getEncoder().encodeToString(full.toByteArray(StandardCharsets.UTF_16LE))
            val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(false).also { it.environment().putAll(env) }.start()
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

    /** Known folders get their display name in the app's language (as Explorer and the original show them). */
    private fun localName(f: PinnedFolder): String {
        val zh = mapOf(
            KnownKind.Desktop to "桌面", KnownKind.Downloads to "下載", KnownKind.Documents to "文件",
            KnownKind.Pictures to "圖片", KnownKind.Music to "音樂", KnownKind.Videos to "影片",
        )
        val en = mapOf(
            KnownKind.Desktop to "Desktop", KnownKind.Downloads to "Downloads", KnownKind.Documents to "Documents",
            KnownKind.Pictures to "Pictures", KnownKind.Music to "Music", KnownKind.Videos to "Videos",
        )
        return (if (S.zh) zh else en)[knownKindOf(f)] ?: f.name
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
        if (pins.isNotEmpty()) return pins.map { PinnedFolder(localName(it), it.path) }
        val home = System.getProperty("user.home")
        return listOf("Desktop", "Downloads", "Documents", "Pictures", "Music", "Videos")
            .map { PinnedFolder(it, "$home\\$it") }
            .filter { File(it.path).isDirectory }
    }

    /** Cloud drive folders that sync providers register on this PC (iCloud here). */
    fun cloudDrives(): List<CloudDrive> {
        val home = System.getProperty("user.home")
        val out = ArrayList<CloudDrive>()
        File(home, "iCloudDrive").takeIf { it.isDirectory }?.let { out += CloudDrive("iCloud Drive", it.path) }
        listOf(File(home, "iCloudPhotos\\Photos"), File(home, "iCloudPhotos")).firstOrNull { it.isDirectory }
            ?.let { out += CloudDrive("iCloud Photos", it.path) }
        File(home, "Dropbox").takeIf { it.isDirectory }?.let { out += CloudDrive("Dropbox", it.path) }
        return out
    }

    /** WSL distributions (wsl -l -q prints UTF-16), with a time limit so a stopped WSL can't hang anything. */
    fun wslDistros(): List<String> = try {
        val p = ProcessBuilder("wsl.exe", "-l", "-q").redirectErrorStream(true).start()
        p.outputStream.close()
        val bytes = java.util.concurrent.CompletableFuture.supplyAsync { p.inputStream.readAllBytes() }
        if (!p.waitFor(4, TimeUnit.SECONDS)) { p.destroyForcibly(); emptyList() }
        else String(bytes.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_16LE)
            .lines().map { it.replace("\u0000", "").trim() }.filter { it.isNotEmpty() && !it.contains(' ') }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The icon WSL registered for each distribution: HKCU\…\Lxss\{id} holds DistributionName and
     * BasePath, and WSL puts shortcut.ico there (the Start-menu and Windows Terminal icon).
     * Distributions without one (docker-desktop, older imports) are simply missing from the map.
     */
    fun wslIconFiles(): Map<String, File> = try {
        val p = ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Lxss", "/s")
            .redirectErrorStream(true).start()
        p.outputStream.close()
        val bytes = java.util.concurrent.CompletableFuture.supplyAsync { p.inputStream.readAllBytes() }
        if (!p.waitFor(4, TimeUnit.SECONDS)) { p.destroyForcibly(); emptyMap() }
        else {
            val charset = runCatching { java.nio.charset.Charset.forName(System.getProperty("native.encoding")) }.getOrDefault(Charsets.UTF_8)
            val out = HashMap<String, File>()
            var name: String? = null; var base: String? = null
            fun flush() {
                val n = name; val b = base
                if (n != null && b != null) File(b.removePrefix("\\\\?\\"), "shortcut.ico").takeIf { it.isFile }?.let { out[n] = it }
                name = null; base = null
            }
            for (line in String(bytes.get(1, TimeUnit.SECONDS), charset).lines()) {
                if (line.startsWith("HKEY_")) { flush(); continue }
                val parts = line.trim().split(Regex("\\s{2,}|\\t"), limit = 3)
                if (parts.size == 3 && parts[1].startsWith("REG_")) when (parts[0]) {
                    "DistributionName" -> name = parts[2]
                    "BasePath" -> base = parts[2]
                }
            }
            flush()
            out
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Recent files from Windows' Recent folder (shortcut names, newest first), capped. */
    fun recentFiles(): List<RecentFile> {
        val dir = File(System.getenv("APPDATA") ?: return emptyList(), "Microsoft\\Windows\\Recent")
        val links = dir.listFiles { f -> f.isFile && f.name.endsWith(".lnk", true) } ?: return emptyList()
        return links.sortedByDescending { it.lastModified() }
            .take(M.RECENT_FILES_LIMIT)
            .map { l ->
                val target = LnkFile.target(l)
                // Only local paths are checked: a share that doesn't answer would stall the list (and isn't "missing").
                val local = target != null && !target.startsWith("\\\\")
                val exists = !local || File(target!!).exists()
                RecentFile(l.name.removeSuffix(".lnk").removeSuffix(".LNK"), l.path, l.lastModified(), target, exists, local && File(target!!).isDirectory)
            }
    }

    /**
     * Windows environment variables in a typed path, the way cmd and Explorer read them: `%NAME%` (any case)
     * becomes its value; an unknown name, a lone `%` and `%%` stay as typed. [env] is for tests.
     */
    fun expandEnv(text: String, env: (String) -> String? = { System.getenv(it) }): String =
        Regex("%([^%\\\\/:*?\"<>|\\r\\n]+)%").replace(text) { m -> env(m.groupValues[1]) ?: m.value }

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
        if (NetworkFs.isServer(path)) return if (NetworkFs.isWslHost(NetworkFs.hostOf(path))) HOME else NETWORK
        if (NetworkFs.isShareRoot(path)) return NetworkFs.serverPath(NetworkFs.hostOf(path))
        val p = Paths.get(path).parent ?: return null
        return p.toString()
    }

    fun breadcrumbs(path: String): List<Pair<String, String>> {
        // Network paths start at 網路 > server, then the share and its folders.
        if (NetworkFs.isUnc(path)) {
            val host = NetworkFs.hostOf(path)
            // WSL paths read WSL > distribution > …, other servers 網路 > server > share > ….
            val head = if (NetworkFs.isWslHost(host)) listOf(S.wsl to NetworkFs.serverPath(host))
                else listOf(S.network to NETWORK, host to NetworkFs.serverPath(host))
            return if (NetworkFs.isServer(path)) head else head + localCrumbs(path)
        }
        return localCrumbs(path)
    }

    private fun localCrumbs(path: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var p: Path? = Paths.get(path)
        while (p != null) {
            out += displayName(p.toString()) to p.toString()
            p = p.parent
        }
        return out.reversed()
    }
}
