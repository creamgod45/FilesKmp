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
    fun folderSize(path: String): Long {
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
    fun search(root: String, query: String, limit: Int, cancelled: () -> Boolean, onBatch: (List<FileItem>) -> Unit) {
        val q = query.lowercase()
        val batch = ArrayList<FileItem>()
        var found = 0
        var lastFlush = System.currentTimeMillis()
        fun flush() { if (batch.isNotEmpty()) { onBatch(batch.toList()); batch.clear() }; lastFlush = System.currentTimeMillis() }
        try {
            Files.walkFileTree(Paths.get(root), object : SimpleFileVisitor<Path>() {
                fun check(p: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled() || found >= limit) return FileVisitResult.TERMINATE
                    val name = p.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (name.lowercase().contains(q)) {
                        val dos = attrs as? DosFileAttributes
                        batch += FileItem(p.toString(), name, attrs.isDirectory, if (attrs.isDirectory) 0 else attrs.size(),
                            attrs.lastModifiedTime().toMillis(), attrs.creationTime().toMillis(),
                            dos?.isHidden ?: false, isSystemItem(dos?.isSystem ?: false, name))
                        found++
                    }
                    if (System.currentTimeMillis() - lastFlush > 200) flush()
                    return FileVisitResult.CONTINUE
                }
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir.toString() == root) return FileVisitResult.CONTINUE
                    val r = check(dir, attrs)
                    val n = dir.fileName?.toString() ?: ""
                    // Skip system folders that are huge and never what people look for.
                    if (r == FileVisitResult.CONTINUE && (n.equals("\$Recycle.Bin", true) || n.equals("System Volume Information", true) || n.equals("WinSxS", true)))
                        return FileVisitResult.SKIP_SUBTREE
                    return r
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes) = check(file, attrs)
                override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
            })
        } catch (_: Exception) {
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
