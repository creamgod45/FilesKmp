package app.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ====================================================================== disk usage history

/** One reading: [used] of [total] bytes on a drive (key "C:\") or the user folder (key [PROFILE_KEY]). */
class StorageSample(val time: Long, val key: String, val used: Long, val total: Long)

const val PROFILE_KEY = "profile"

/**
 * Disk usage over time, recorded only while 記錄磁碟空間使用量 is on (off by default). Kept in
 * %LOCALAPPDATA%\FilesKmp\History\storage.tsv: per-machine data that can't be rebuilt, so not in Cache.
 */
object StorageHistory {
    private val file get() = File(AppDirs.history, "storage.tsv")

    fun load(): List<StorageSample> = try {
        if (!file.isFile) emptyList() else file.readLines(Charsets.UTF_8).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size == 4) runCatching { StorageSample(f[0].toLong(), f[1], f[2].toLong(), f[3].toLong()) }.getOrNull() else null
        }
    } catch (_: Exception) { emptyList() }

    /** Fixed and removable drives that are ready: used = total - free. */
    fun readDrives(now: Long = System.currentTimeMillis()): List<StorageSample> = File.listRoots().mapNotNull { r ->
        val total = r.totalSpace
        if (total <= 0) null else StorageSample(now, r.path, total - r.usableSpace, total)
    }

    /**
     * Appends [fresh] readings, skipping a drive whose last reading is recent and nearly the same
     * (the file stays small), and keeps the newest [M.STORAGE_HISTORY_MAX] lines.
     */
    @Synchronized
    fun append(fresh: List<StorageSample>): List<StorageSample> {
        val all = load().toMutableList()
        val last = all.groupBy { it.key }.mapValues { it.value.maxBy { s -> s.time } }
        for (s in fresh) {
            val prev = last[s.key]
            val recent = prev != null && s.time - prev.time < M.STORAGE_MIN_GAP_MS
            val same = prev != null && kotlin.math.abs(s.used - prev.used) < M.STORAGE_MIN_CHANGE_BYTES
            if (!(recent && same)) all += s
        }
        val kept = all.sortedBy { it.time }.takeLast(M.STORAGE_HISTORY_MAX)
        try {
            AppDirs.history.mkdirs()
            file.writeText(kept.joinToString("\n") { "${it.time}\t${it.key}\t${it.used}\t${it.total}" }, Charsets.UTF_8)
        } catch (_: Exception) {}
        return kept
    }

    /** Growth of [key] over the last [windowMs] (null without two readings in that window). */
    fun growth(samples: List<StorageSample>, key: String, windowMs: Long, now: Long = System.currentTimeMillis()): Long? {
        val inWindow = samples.filter { it.key == key && it.time >= now - windowMs }.sortedBy { it.time }
        return if (inWindow.size < 2) null else inWindow.last().used - inWindow.first().used
    }
}

// ====================================================================== sizes

/** Adds up the files under [root] (links and junctions are not followed). Read-only. */
fun folderBytes(root: File, cancelled: () -> Boolean = { false }): Long {
    if (!root.exists()) return 0
    var total = 0L
    try {
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                if (cancelled()) FileVisitResult.TERMINATE else if (attrs.isOther || attrs.isSymbolicLink) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult { total += attrs.size(); return FileVisitResult.CONTINUE }
            override fun visitFileFailed(f: Path, e: IOException) = FileVisitResult.CONTINUE
        })
    } catch (_: Exception) {}
    return total
}

/** One folder of the user's profile and its size. */
class ProfileEntry(val name: String, val path: String, val bytes: Long)

/**
 * Sizes every top-level item of the user folder (C:\Users\name), several at a time, calling
 * [onUpdate] as each finishes. Read-only; can take minutes on a large profile.
 */
fun scanProfile(cancelled: () -> Boolean, onUpdate: (List<ProfileEntry>, done: Int, total: Int) -> Unit): List<ProfileEntry> {
    val children = File(userHome).listFiles()?.toList().orEmpty()
    val out = java.util.Collections.synchronizedList(ArrayList<ProfileEntry>())
    val pool = Executors.newFixedThreadPool(4)
    try {
        val done = java.util.concurrent.atomic.AtomicInteger()
        val tasks = children.map { f ->
            pool.submit(Callable {
                val bytes = if (f.isDirectory) folderBytes(f, cancelled) else f.length()
                out += ProfileEntry(f.name, f.path, bytes)
                onUpdate(out.toList().sortedByDescending { it.bytes }, done.incrementAndGet(), children.size)
            })
        }
        tasks.forEach { runCatching { it.get() } }
    } finally {
        pool.shutdownNow()
    }
    return out.toList().sortedByDescending { it.bytes }
}

// ====================================================================== cleanup suggestions

enum class CleanupAudience { General, Developer }

/**
 * A place that tends to grow, and how to shrink it, in order of preference:
 * - [command]: the tool's own clean-up command (pip cache purge, npm cache clean, …), run only after
 *   the person confirms, in a visible PowerShell window; [tool] must be on PATH;
 * - [safeDelete]: removal by [SafeCleaner] (preview first, then confirm), for plain caches with no
 *   tool command;
 * - neither: size and advice only (browsers, .cache, Node versions), the person decides.
 *
 * Deliberately left out, as they hold user data or are in use by Windows (lessons from reviewing
 * CrunchyCleaner): PowerShell history, VS Code workspaceStorage, Windows WebCache, the thumbnail
 * cache, and anything that needs administrator rights (C:\Windows\Temp, Logs, SoftwareDistribution).
 */
class CleanupTarget(
    val id: String,
    val name: String,
    val audience: CleanupAudience,
    val paths: List<File>,
    val description: String,
    val command: String? = null,
    val tool: String? = null,
    val safeDelete: SafeDeleteRule? = null,
)

/**
 * What [SafeCleaner] may remove: regular files under [roots] (optionally only those untouched for
 * [olderThanDays]). [blockedBy] names a program (e.g. "Code.exe") that must not be running.
 */
class SafeDeleteRule(val roots: List<File>, val olderThanDays: Int? = null, val blockedBy: String? = null)

/** A measured target: total size and optional detail lines (versions, sub-folders). */
class CleanupResult(val target: CleanupTarget, val bytes: Long, val details: List<String>, val toolMissing: Boolean)

object Cleanup {
    private fun env(n: String) = System.getenv(n)?.takeIf { it.isNotBlank() }
    private val home get() = userHome
    private val local get() = env("LOCALAPPDATA") ?: "$home\\AppData\\Local"
    private val roaming get() = env("APPDATA") ?: "$home\\AppData\\Roaming"

    /** Everything we know how to suggest; [measure] drops the ones not on this PC. */
    fun targets(): List<CleanupTarget> {
        val zh = S.zh
        fun t(en: String, z: String) = if (zh) z else en
        return listOf(
            File(env("TEMP") ?: "$local\\Temp").let { temp ->
                CleanupTarget("temp", t("Temporary files", "暫存檔案"), CleanupAudience.General, listOf(temp),
                    t("Files programs left in %TEMP%. Only files untouched for 7 days are removed; files in use are skipped.",
                        "程式留在 %TEMP% 的檔案。只移除 7 天以上未變動的檔案，使用中的檔案會略過。"),
                    safeDelete = SafeDeleteRule(listOf(temp), olderThanDays = 7))
            },
            CleanupTarget("shader", t("Graphics shader caches", "顯示卡著色器快取"), CleanupAudience.General,
                listOf(File(local, "NVIDIA\\DXCache"), File(local, "NVIDIA\\GLCache"), File(local, "D3DSCache"), File(local, "AMD\\DxCache")),
                t("Shaders compiled by the graphics driver; rebuilt as games and apps need them (the first launch may stutter). Files in use are skipped. NVIDIA's control panel can also cap this size.",
                    "顯示卡驅動編譯的著色器，遊戲與程式需要時會重新產生（首次啟動可能稍有延遲）。使用中的檔案會略過。也可在 NVIDIA 控制台限制其大小。"),
                safeDelete = SafeDeleteRule(listOf(File(local, "NVIDIA\\DXCache"), File(local, "NVIDIA\\GLCache"), File(local, "D3DSCache"), File(local, "AMD\\DxCache")))),
            CleanupTarget("browsers", t("Browser caches", "瀏覽器快取"), CleanupAudience.General,
                listOf(File(local, "Google\\Chrome\\User Data\\Default\\Cache"), File(local, "Microsoft\\Edge\\User Data\\Default\\Cache"),
                    File(local, "BraveSoftware\\Brave-Browser\\User Data\\Default\\Cache")) +
                    // Firefox: only each profile's cache2, never the profile itself (bookmarks, history, logins).
                    File(local, "Mozilla\\Firefox\\Profiles").listFiles().orEmpty().map { File(it, "cache2") },
                t("Clear these from the browser itself (Settings → Privacy → Clear browsing data): it knows what is safe while it runs.",
                    "請在瀏覽器內清除（設定 → 隱私權 → 清除瀏覽資料）：瀏覽器執行中也能安全處理。")),
            CleanupTarget("downloads", t("Downloads", "下載"), CleanupAudience.General, listOf(File(home, "Downloads")),
                t("Installers and archives you've already used often pile up here. Review and remove what you don't need.",
                    "已用過的安裝檔與壓縮檔常堆積在這裡，請檢視後自行移除不需要的檔案。")),
            CleanupTarget("pip", "pip", CleanupAudience.Developer, listOf(File(local, "pip\\Cache")),
                t("Downloaded Python packages and wheels; pip downloads them again when needed.", "pip 下載的套件與 wheel 快取，需要時會重新下載。"),
                "pip cache purge", "pip"),
            CleanupTarget("uv", "uv", CleanupAudience.Developer, listOf(File(local, "uv\\cache")),
                t("uv's package cache. \"prune\" removes only entries no longer used.", "uv 的套件快取。prune 只移除已不再使用的項目。"),
                "uv cache prune", "uv"),
            CleanupTarget("gradle", "Gradle", CleanupAudience.Developer, listOf(File(home, ".gradle\\caches"), File(home, ".gradle\\wrapper\\dists")),
                t("Downloaded dependencies, build caches and Gradle versions. Close IDEs and builds first; the next build downloads what it needs.",
                    "下載的相依套件、建置快取與 Gradle 版本。請先關閉 IDE 與建置，下次建置會重新下載需要的內容。"),
                safeDelete = SafeDeleteRule(listOf(File(home, ".gradle\\caches")))),
            CleanupTarget("dotcache", ".cache", CleanupAudience.Developer, listOf(File(home, ".cache")),
                t("Caches and runtimes of various tools. Check each sub-folder before removing anything.",
                    "各種工具的快取與執行環境，請逐一確認子資料夾後再移除。")),
            CleanupTarget("npm", "npm", CleanupAudience.Developer, listOf(File(local, "npm-cache")),
                t("npm's package cache; packages are downloaded again when needed.", "npm 的套件快取，需要時會重新下載。"),
                "npm cache clean --force", "npm"),
            CleanupTarget("pnpm", "pnpm", CleanupAudience.Developer, listOf(File(local, "pnpm\\store")),
                t("pnpm's content store; \"prune\" removes packages no project uses.", "pnpm 的套件存放區；prune 會移除沒有專案使用的套件。"),
                "pnpm store prune", "pnpm"),
            CleanupTarget("yarn", "Yarn", CleanupAudience.Developer, listOf(File(local, "Yarn\\Cache")),
                t("Yarn's package cache.", "Yarn 的套件快取。"), "yarn cache clean", "yarn"),
            CleanupTarget("nvm", "nvm (Node.js)", CleanupAudience.Developer, listOf(File(env("NVM_HOME") ?: "$local\\nvm")),
                t("Installed Node.js versions. Uninstall the ones you no longer use with: nvm uninstall <version>",
                    "已安裝的 Node.js 版本。可用 nvm uninstall <版本> 移除不再使用的版本。")),
            CleanupTarget("cargo", "Cargo", CleanupAudience.Developer, listOf(File(home, ".cargo\\registry"), File(home, ".cargo\\git")),
                t("Downloaded crates and their sources; Cargo downloads them again when needed.", "下載的 crate 與原始碼，需要時 Cargo 會重新下載。"),
                safeDelete = SafeDeleteRule(listOf(File(home, ".cargo\\registry\\cache"), File(home, ".cargo\\registry\\src")))),
            CleanupTarget("rustup", "rustup (Rust)", CleanupAudience.Developer, listOf(File(home, ".rustup\\toolchains")),
                t("Installed Rust toolchains. Remove unused ones with: rustup toolchain uninstall <name>",
                    "已安裝的 Rust 工具鏈。可用 rustup toolchain uninstall <名稱> 移除不再使用的工具鏈。")),
            CleanupTarget("nuget", "NuGet", CleanupAudience.Developer, listOf(File(home, ".nuget\\packages")),
                t(".NET packages; restored again on the next build.", ".NET 套件，下次建置時會重新還原。"),
                "dotnet nuget locals all --clear", "dotnet"),
            CleanupTarget("maven", "Maven", CleanupAudience.Developer, listOf(File(home, ".m2\\repository")),
                t("Maven's local repository; downloaded again when needed.", "Maven 本機套件庫，需要時會重新下載。")),
            CleanupTarget("conda", "conda", CleanupAudience.Developer, listOf(File(home, "anaconda3\\pkgs"), File(home, "miniconda3\\pkgs")),
                t("conda's package cache.", "conda 的套件快取。"), "conda clean --all", "conda"),
            CleanupTarget("go", "Go modules", CleanupAudience.Developer, listOf(File(home, "go\\pkg\\mod")),
                t("Go's module cache.", "Go 的模組快取。"), "go clean -modcache", "go"),
            CleanupTarget("gobuild", t("Go build cache", "Go 建置快取"), CleanupAudience.Developer, listOf(File(local, "go-build")),
                t("Compiled packages Go reuses between builds.", "Go 在建置之間重複使用的編譯結果。"), "go clean -cache", "go"),
            CleanupTarget("bun", "Bun", CleanupAudience.Developer, listOf(File(home, ".bun\\install\\cache")),
                t("Bun's package cache.", "Bun 的套件快取。"), "bun pm cache rm", "bun"),
            CleanupTarget("nodegyp", "node-gyp", CleanupAudience.Developer, listOf(File(local, "node-gyp")),
                t("Node.js headers downloaded for building native modules; downloaded again when needed.", "編譯原生模組時下載的 Node.js 標頭檔，需要時會重新下載。"),
                safeDelete = SafeDeleteRule(listOf(File(local, "node-gyp")))),
            CleanupTarget("playwright", "Playwright", CleanupAudience.Developer, listOf(File(local, "ms-playwright")),
                t("Browsers downloaded for Playwright tests. Projects reinstall them with: npx playwright install",
                    "Playwright 測試下載的瀏覽器。專案可用 npx playwright install 重新安裝。")),
            vsCodeCaches().let { roots ->
                CleanupTarget("vscode", t("VS Code caches", "VS Code 快取"), CleanupAudience.Developer, roots,
                    t("Code and GPU caches and downloaded extension packages. Workspace storage (your workspace state) is not touched. Close VS Code first.",
                        "程式碼、GPU 快取與下載的擴充套件安裝檔。不會動到工作區儲存資料（workspaceStorage）。請先關閉 VS Code。"),
                    safeDelete = SafeDeleteRule(roots, blockedBy = "Code.exe"))
            },
            oldJetBrainsFolders().let { old ->
                CleanupTarget("jetbrains", t("Old JetBrains IDE versions", "JetBrains IDE 舊版本"), CleanupAudience.Developer, old,
                    t("Caches, indexes and logs left by IDE versions you've upgraded from (in AppData\\Local). Settings in AppData\\Roaming are not touched.",
                        "升級後舊版 IDE 留下的快取、索引與記錄（位於 AppData\\Local）。不會動到 AppData\\Roaming 中的設定。"),
                    safeDelete = SafeDeleteRule(old))
            },
            CleanupTarget("docker", "Docker Desktop", CleanupAudience.Developer, listOf(File(local, "Docker\\wsl")),
                t("Images, containers and build cache in Docker's disk image. \"prune\" removes stopped containers, unused networks, dangling images and build cache (Docker must be running). The disk image file itself doesn't shrink on its own.",
                    "Docker 磁碟映像中的映像檔、容器與建置快取。prune 會移除已停止的容器、未使用的網路、懸置映像與建置快取（需先啟動 Docker）。磁碟映像檔本身不會自動縮小。"),
                "docker system prune", "docker"),
        )
    }

    private fun vsCodeCaches() = listOf("Cache", "Code Cache", "CachedData", "CachedExtensionVSIXs", "GPUCache").map { File(roaming, "Code\\$it") }

    /**
     * Version folders in AppData\Local\JetBrains (IntelliJIdea2025.3, …) older than the newest of the
     * same product. Folders without a version (Daemon, acp-agents) are never included.
     */
    private fun oldJetBrainsFolders(): List<File> {
        val dirs = File(local, "JetBrains").listFiles()?.filter { it.isDirectory }.orEmpty()
        val versioned = dirs.mapNotNull { d ->
            Regex("""^([A-Za-z]+)(\d{4})\.(\d+)$""").find(d.name)?.let { m -> Triple(m.groupValues[1], m.groupValues[2].toInt() * 100 + m.groupValues[3].toInt(), d) }
        }
        return versioned.groupBy { it.first }.flatMap { (_, list) ->
            val newest = list.maxOf { it.second }
            list.filter { it.second < newest }.map { it.third }
        }
    }

    /** Whether a program with this executable name is running (e.g. "Code.exe"). */
    fun isRunning(exe: String): Boolean = ProcessHandle.allProcesses().anyMatch { ph ->
        ph.info().command().map { it.substringAfterLast('\\').equals(exe, ignoreCase = true) }.orElse(false)
    }

    private fun onPath(tool: String): Boolean {
        val path = System.getenv("PATH")?.split(';').orEmpty()
        val exts = listOf(".exe", ".cmd", ".bat", ".ps1")
        return path.any { dir -> exts.any { File(dir.trim(), tool + it).isFile } }
    }

    /** Sizes the targets present on this PC (several at once), largest first. Read-only. */
    fun measure(cancelled: () -> Boolean = { false }): List<CleanupResult> {
        val present = targets().filter { t -> t.paths.isNotEmpty() && t.paths.any { it.exists() } }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = present.map { t ->
                pool.submit(Callable {
                    CleanupResult(t, t.paths.sumOf { folderBytes(it, cancelled) }, details(t), t.tool != null && !onPath(t.tool))
                })
            }
            return futures.mapNotNull { runCatching { it.get(M.STORAGE_MEASURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull() }
                .sortedByDescending { it.bytes }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Extra lines for targets made of separate parts: Node versions, toolchains, .cache sub-folders. */
    private fun details(t: CleanupTarget): List<String> = when (t.id) {
        "nvm" -> {
            val inUse = env("NVM_SYMLINK")?.let { runCatching { File(it).canonicalFile.name }.getOrNull() }
            t.paths.first().listFiles()?.filter { it.isDirectory && it.name.startsWith("v") }?.sortedBy { it.name }
                ?.map { v -> "${v.name}  ${Fs.sizeText(folderBytes(v))}" + if (v.name == inUse) (if (S.zh) "（使用中）" else " (in use)") else "" }
                .orEmpty()
        }
        "jetbrains" -> t.paths.map { "${it.name}  ${Fs.sizeText(folderBytes(it))}" }
        "rustup", "dotcache" -> t.paths.first().listFiles()?.filter { it.isDirectory }
            ?.map { it.name to folderBytes(it) }?.sortedByDescending { it.second }
            ?.map { (n, b) -> "$n  ${Fs.sizeText(b)}" }.orEmpty()
        else -> emptyList()
    }

    /** Runs [command] in a new, visible PowerShell window that stays open to show the result. */
    fun runInTerminal(command: String) {
        val script = "Write-Host '> ' -NoNewline -ForegroundColor DarkGray; Write-Host ${psQuote(command)}; $command; " +
            "Write-Host ''; Write-Host '${if (S.zh) "完成，可關閉此視窗。" else "Done. You can close this window."}' -ForegroundColor Green"
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        runCatching { ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "powershell.exe", "-NoExit", "-NoProfile", "-EncodedCommand", encoded).start() }
    }

    private fun psQuote(s: String) = "'" + s.replace("'", "''") + "'"

    /** Windows' own tools: Disk Cleanup and Storage Sense. */
    fun openDiskCleanup() { runCatching { ProcessBuilder("cleanmgr.exe").start() } }
    fun openStorageSense() = Fs.open("ms-settings:storagesense")
}

// ====================================================================== safe removal

/** What [SafeCleaner.plan] found: the files it would remove, and what it left alone. */
class CleanPlan(val target: CleanupTarget, val files: List<Path>, val bytes: Long, val skippedLinks: Int, val skippedRecent: Int)

/**
 * Removes cache files without the failure modes of tools like CrunchyCleaner:
 * - never follows or removes a symbolic link or junction, at any depth (so a cache folder swapped
 *   for a link can't lead outside it);
 * - removes only regular files, and right before each removal checks again that the file's real
 *   location is still inside its cache root;
 * - shows a preview (count, size, sample paths) and acts only after the person confirms;
 * - never asks for administrator rights; files in use are simply skipped;
 * - empty folders left behind are removed, the cache roots themselves are kept.
 */
object SafeCleaner {
    private fun isLinkLike(a: BasicFileAttributes) = a.isSymbolicLink || a.isOther

    fun plan(target: CleanupTarget, cancelled: () -> Boolean = { false }): CleanPlan {
        val rule = target.safeDelete ?: return CleanPlan(target, emptyList(), 0, 0, 0)
        val cutoff = rule.olderThanDays?.let { System.currentTimeMillis() - it * 24L * 3600_000 }
        val files = ArrayList<Path>()
        var bytes = 0L; var links = 0; var recent = 0
        for (root in rule.roots) {
            val rootPath = root.toPath()
            val rootAttrs = runCatching { Files.readAttributes(rootPath, BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            if (rootAttrs == null || !rootAttrs.isDirectory || isLinkLike(rootAttrs)) { if (rootAttrs != null) links++; continue }
            try {
                Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (cancelled()) return FileVisitResult.TERMINATE
                        if (dir != rootPath && isLinkLike(attrs)) { links++; return FileVisitResult.SKIP_SUBTREE }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                        when {
                            !attrs.isRegularFile -> links++
                            cutoff != null && attrs.lastModifiedTime().toMillis() > cutoff -> recent++
                            else -> { files.add(f); bytes += attrs.size() }
                        }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(f: Path, e: IOException) = FileVisitResult.CONTINUE
                })
            } catch (_: Exception) {}
        }
        return CleanPlan(target, files, bytes, links, recent)
    }

    /** Removes the planned files (re-checking each), then empty folders. Returns (files removed, bytes freed). */
    fun execute(plan: CleanPlan, onProgress: (Int) -> Unit = {}): Pair<Int, Long> {
        val roots = plan.target.safeDelete?.roots.orEmpty().mapNotNull { r ->
            runCatching { r.toPath() to r.toPath().toRealPath() }.getOrNull()
        }
        var removed = 0; var freed = 0L
        plan.files.forEachIndexed { i, f ->
            try {
                val a = Files.readAttributes(f, BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                val parentReal = f.parent.toRealPath()
                val inside = roots.any { (root, real) -> f.startsWith(root) && parentReal.startsWith(real) }
                if (a.isRegularFile && inside && Files.deleteIfExists(f)) { removed++; freed += a.size() }
            } catch (_: Exception) {
                // In use, gone, or no longer inside its root: leave it.
            }
            if (i % 200 == 0) onProgress(i)
        }
        // Empty folders, deepest first; never a root, never a link.
        for ((root, _) in roots) {
            try {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                        if (dir != root && isLinkLike(attrs)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                    override fun visitFileFailed(f: Path, e: IOException) = FileVisitResult.CONTINUE
                    override fun postVisitDirectory(dir: Path, e: IOException?): FileVisitResult {
                        if (dir != root) runCatching { Files.delete(dir) } // fails (and is skipped) unless empty
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (_: Exception) {}
        }
        return removed to freed
    }
}
