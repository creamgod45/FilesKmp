package app.files

import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Extra file operations: zip archives, deep search, tag views, pinning and previews. */
object FsExtra {

    fun item(path: String): FileItem? = try {
        val p = Paths.get(path)
        val a = Files.readAttributes(p, DosFileAttributes::class.java)
        FileItem(path, p.fileName?.toString() ?: path, a.isDirectory, if (a.isDirectory) 0 else a.size(),
            a.lastModifiedTime().toMillis(), a.creationTime().toMillis(), a.isHidden, isSystemItem(a.isSystem, p.fileName?.toString()))
    } catch (_: Exception) { null }

    /** Total size of everything inside a folder (files that can't be read are skipped). */
    /**
     * Desktop background through SystemParametersInfoW(SPI_SETDESKWALLPAPER) directly: the path is
     * passed as data, never put into a script.
     */
    fun setWallpaper(path: String): Boolean = try {
        java.lang.foreign.Arena.ofConfined().use { arena ->
            val user32 = java.lang.foreign.SymbolLookup.libraryLookup("user32", arena)
            val I = java.lang.foreign.ValueLayout.JAVA_INT
            val fn = java.lang.foreign.Linker.nativeLinker().downcallHandle(user32.find("SystemParametersInfoW").orElseThrow(),
                java.lang.foreign.FunctionDescriptor.of(I, I, I, java.lang.foreign.ValueLayout.ADDRESS, I))
            // SPI_SETDESKWALLPAPER = 20; SPIF_UPDATEINIFILE | SPIF_SENDCHANGE = 3
            (fn.invokeWithArguments(20, 0, arena.allocateFrom(File(path).absolutePath, Charsets.UTF_16LE), 3) as Int) != 0
        }
    } catch (_: Throwable) { false }

    fun folderSize(path: String): Long {
        // One native directory read per folder (FastDir) instead of a stat per file; links aren't followed.
        if (FastDir.available) {
            var total = 0L
            val stack = ArrayDeque<String>().apply { add(path) }
            var any = false
            while (stack.isNotEmpty()) {
                val dir = stack.removeLast()
                val ok = FastDir.forEach(dir) { e ->
                    if (e.isDir) { if (!e.isReparse) stack.add(if (dir.endsWith("\\")) dir + e.name else dir + "\\" + e.name) }
                    else total += e.size
                }
                any = any || ok
            }
            if (any) return total
        }
        var total = 0L
        try {
            Files.walkFileTree(Paths.get(path), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    total += attrs.size(); return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
            })
        } catch (_: Exception) {
        }
        return total
    }

    // ---------------------------------------------------------------- zip

    /** Compresses [paths] into [zip] (folders keep their structure). */
    fun compress(paths: List<String>, zip: File, cancelled: () -> Boolean = { false }) {
        ZipOutputStream(zip.outputStream().buffered(1 shl 20)).use { out ->
            for (p in paths) {
                val root = Paths.get(p)
                val base = root.parent
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (cancelled()) return FileVisitResult.TERMINATE
                        val rel = base.relativize(dir).toString().replace('\\', '/') + "/"
                        out.putNextEntry(ZipEntry(rel)); out.closeEntry()
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (cancelled()) return FileVisitResult.TERMINATE
                        if (file.toAbsolutePath() == zip.toPath().toAbsolutePath()) return FileVisitResult.CONTINUE
                        val e = ZipEntry(base.relativize(file).toString().replace('\\', '/'))
                        e.time = attrs.lastModifiedTime().toMillis()
                        out.putNextEntry(e)
                        Files.copy(file, out)
                        out.closeEntry()
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
                })
            }
        }
    }

    fun uniqueSibling(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base$ext")
        var i = 2
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    /** Extracts [zip] into a new folder next to it named after the archive; returns that folder. */
    fun extract(zip: File): File {
        val dest = uniqueSibling(zip.parentFile, zip.nameWithoutExtension, "")
        dest.mkdirs()
        extractInto(zip, dest)
        return dest
    }

    /** Extracts [zip] into [dest] (never writes outside it). */
    fun extractInto(zip: File, dest: File) {
        dest.mkdirs()
        val destPath = dest.toPath().toAbsolutePath().normalize()
        ZipInputStream(zip.inputStream().buffered(1 shl 20)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val target = destPath.resolve(e.name).normalize()
                if (!target.startsWith(destPath)) continue   // never write outside the folder
                if (e.isDirectory) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING)
                    if (e.time > 0) target.toFile().setLastModified(e.time)
                }
            }
        }
    }

    // ---------------------------------------------------------------- deep search

    /**
     * Searches [root] and every folder below it for names containing [query]. Results are
     * reported in batches through [onBatch]; stops at [limit] results or when [cancelled].
     */
    fun search(root: String, q: SearchQuery, caseSensitive: Boolean, limit: Int, cancelled: () -> Boolean, onBatch: (List<FileItem>) -> Unit) {
        val batch = ArrayList<FileItem>()
        var found = 0
        var lastFlush = System.currentTimeMillis()
        fun flush() { if (batch.isNotEmpty()) { onBatch(batch.toList()); batch.clear() }; lastFlush = System.currentTimeMillis() }
        // Breadth-first with the native folder read (FastDir): one directory read per folder, no stat per file.
        val queue = ArrayDeque<String>().apply { add(root) }
        while (queue.isNotEmpty() && found < limit && !cancelled()) {
            val dir = queue.removeFirst()
            val ok = FastDir.forEach(dir, { cancelled() || found >= limit }) { e ->
                val path = if (dir.endsWith("\\")) dir + e.name else dir + "\\" + e.name
                if (e.isDir && !e.isReparse && !(e.name.equals("\$Recycle.Bin", true) || e.name.equals("System Volume Information", true) || e.name.equals("WinSxS", true)))
                    queue.add(path)
                val c = SearchQuery.Candidate(e.name, if (caseSensitive) e.name else e.name.lowercase(), e.isDir, e.size, e.modified, e.created) { path }
                if (q.matches(c)) { batch += FastDir.toItem(dir, e); found++ }
                if (System.currentTimeMillis() - lastFlush > 200) flush()
            }
            if (!ok && !FastDir.available) {
                // No native access: the JDK way for this folder.
                runCatching {
                    Files.newDirectoryStream(Paths.get(dir)).use { st -> for (p in st) {
                        val it = item(p.toString()) ?: continue
                        if (it.isDir) queue.add(it.path)
                        val c = SearchQuery.Candidate(it.name, if (caseSensitive) it.name else it.name.lowercase(), it.isDir, it.size, it.modified, it.created) { it.path }
                        if (q.matches(c)) { batch += it; found++ }
                    } }
                }
            }
        }
        flush()
    }

    // ---------------------------------------------------------------- pin / unpin (Windows Quick access, as the original does)

    fun pin(path: String): Boolean {
        val f = File(path)
        val parent = f.parentFile?.path ?: return false
        val ps = "\$s=New-Object -ComObject Shell.Application; \$s.Namespace('${parent.replace("'", "''")}').ParseName('${f.name.replace("'", "''")}').InvokeVerb('pintohome')"
        return Fs.powershell(ps, 8000) != null
    }

    fun unpin(path: String): Boolean {
        val ps = "\$s=New-Object -ComObject Shell.Application; " +
            "\$s.Namespace('shell:::{679f85cb-0220-4080-b29b-5540cc05aab6}').Items() | " +
            "Where-Object { \$_.Path -eq '${path.replace("'", "''")}' } | ForEach-Object { \$_.InvokeVerb('unpinfromhome') }"
        return Fs.powershell(ps, 8000) != null
    }

    // ---------------------------------------------------------------- preview

    val imageExt = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "ico")
    val textExt = setOf("txt", "md", "log", "ini", "cfg", "json", "xml", "csv", "kt", "kts", "java", "cs", "rs", "js", "ts",
        "py", "c", "cpp", "h", "html", "css", "toml", "yaml", "yml", "gradle", "bat", "cmd", "ps1", "properties", "appinstaller")

    fun readTextPreview(path: String, maxBytes: Int = 8192): String? = try {
        File(path).inputStream().use { input ->
            val buf = input.readNBytes(maxBytes)
            String(buf, Charsets.UTF_8)
        }
    } catch (_: Exception) { null }
}
