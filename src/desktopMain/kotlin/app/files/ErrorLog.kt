package app.files

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One recorded error (a file in the Logs folder). */
class ErrorReport(
    val id: String,
    val time: String,
    val version: String,
    val source: String,
    val fatal: Boolean,
    val type: String,
    val message: String,
    val stack: String,
    /** Same error, same place: reports with equal signatures are grouped. */
    val signature: String,
    val thread: String,
    val system: Map<String, String>,
    val trail: List<String>,
    val file: File?,
) {
    fun toJson(): String = Json.write(linkedMapOf(
        "id" to id, "time" to time, "version" to version, "source" to source, "fatal" to fatal, "type" to type,
        "message" to message, "signature" to signature, "thread" to thread, "system" to system.toSortedMap(), "trail" to trail, "stack" to stack,
    ))

    /** Plain text for copying or reading. */
    fun text(): String = buildString {
        appendLine("FilesKmp $version · $time · ${if (fatal) "FATAL" else "error"} · $source")
        appendLine("$type: $message")
        appendLine("thread: $thread · signature: $signature")
        system.forEach { (k, v) -> appendLine("$k: $v") }
        if (trail.isNotEmpty()) { appendLine(); appendLine("Recent actions (oldest first):"); trail.forEach { appendLine("  $it") } }
        appendLine(); append(stack)
    }

    companion object {
        fun parse(f: File): ErrorReport? = runCatching {
            val m = Json.parse(f.readText(Charsets.UTF_8)) as Map<*, *>
            fun s(k: String) = m[k]?.toString().orEmpty()
            ErrorReport(s("id"), s("time"), s("version"), s("source"), m["fatal"] == true, s("type"), s("message"), s("stack"), s("signature"), s("thread"),
                (m["system"] as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value.toString() },
                (m["trail"] as? List<*>).orEmpty().map { it.toString() }, f)
        }.getOrNull()
    }
}

/**
 * 錯誤報告: errors are written to %LOCALAPPDATA%\FilesKmp\Logs on this computer only — nothing is sent
 * anywhere. Each report has the error, where it happened, this app's and Windows' versions and (when
 * on) the last actions before it. The settings page groups them and can export a ZIP for you to share.
 */
object ErrorLog {
    val dir: File get() = AppDirs.logs
    private const val MAX_REPORTS = 500
    private const val MAX_SAME = 20
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val shown = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Volatile var enabled = Prefs.bool("errorLog", true)
    @Volatile var keepTrail = Prefs.bool("errorLogTrail", true)
    /** Tests: write here instead (never into the real Logs folder). */
    @Volatile internal var testDir: File? = null
    private val folder get() = testDir ?: dir

    // ---- recent actions ("breadcrumbs")
    private val trail = ArrayDeque<String>()
    /** Remembers an action (navigation, a command, a key…) so a later error shows what led to it. */
    fun crumb(what: String) {
        if (!keepTrail) return
        val line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "  " + what.take(300)
        synchronized(trail) { trail.addLast(line); while (trail.size > 50) trail.removeFirst() }
    }

    /** Called once in main(): every uncaught error is recorded before the app goes on (or ends). */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            record(e, "uncaught", fatal = t.name == "main", thread = t)
            // Then what Java would do anyway: print it. (Not via the thread group: it would call this handler again.)
            if (previous != null) previous.uncaughtException(t, e)
            else { System.err.print("Exception in thread \"${t.name}\" "); e.printStackTrace() }
        }
    }

    private fun signatureOf(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val frames = root.stackTrace.filter { it.className.startsWith("app.files") }.take(4).ifEmpty { root.stackTrace.take(4).toList() }
        val key = root.javaClass.name + "|" + frames.joinToString("|") { "${it.className}.${it.methodName}" }
        return "%08x".format(key.hashCode())
    }

    private val system: Map<String, String> by lazy {
        mapOf("windows" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} build ${ShellMenu.build}",
            "java" to "${System.getProperty("java.vendor")} ${System.getProperty("java.version")}",
            "arch" to System.getProperty("os.arch"), "language" to java.util.Locale.getDefault().toLanguageTag())
    }

    /**
     * Writes a report for [e]. [source] says where (e.g. "shell-menu: classic menu"). Never throws.
     * The same error from the same place is kept at most [MAX_SAME] times; the newest [MAX_REPORTS] are kept.
     */
    fun record(e: Throwable, source: String, fatal: Boolean = false, thread: Thread = Thread.currentThread()): ErrorReport? {
        if (!enabled) return null
        return runCatching {
            val now = LocalDateTime.now()
            val sig = signatureOf(e)
            val rt = Runtime.getRuntime()
            val sys = system + mapOf("memory" to "${(rt.totalMemory() - rt.freeMemory()) / 1048576} / ${rt.maxMemory() / 1048576} MB")
            val r = ErrorReport("${now.format(stamp)}-$sig", now.format(shown), AppInfo.version, source, fatal, e.javaClass.name,
                e.message.orEmpty().take(2000), e.stackTraceToString().take(60_000), sig, thread.name, sys,
                synchronized(trail) { trail.toList() }, null)
            val d = folder.apply { mkdirs() }
            val same = d.listFiles { f -> f.name.endsWith("-$sig.json") }?.size ?: 0
            if (same >= MAX_SAME) return@runCatching r   // already plenty of this one
            File(d, "${r.id}.json").writeText(r.toJson(), Charsets.UTF_8)
            if (fatal) runCatching { File(d, "last-crash.txt").writeText(r.id) }
            prune(d)
            r
        }.getOrNull()
    }

    private fun prune(d: File) {
        val all = d.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: return
        if (all.size > MAX_REPORTS) all.take(all.size - MAX_REPORTS).forEach { it.delete() }
    }

    /** Every report, newest first. */
    fun reports(): List<ErrorReport> = folder.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name }?.mapNotNull { ErrorReport.parse(it) }.orEmpty()

    /** The id of a crash from a previous run that wasn't shown yet (and forgets it). */
    fun takeLastCrash(): String? {
        val f = File(folder, "last-crash.txt")
        if (!f.exists()) return null
        return runCatching { f.readText().trim().also { f.delete() } }.getOrNull()
    }

    /** Deletes every report (settings › 錯誤報告 › 清除). */
    fun clear() { folder.listFiles { f -> f.name.endsWith(".json") || f.name == "last-crash.txt" }?.forEach { it.delete() } }

    /** Replaces the user folder (and user name) with placeholders, for reports you share. */
    fun redact(text: String): String {
        val home = System.getProperty("user.home")
        val user = System.getProperty("user.name")
        var t = text.replace(home, "%USERPROFILE%", ignoreCase = true).replace(home.replace('\\', '/'), "%USERPROFILE%", ignoreCase = true)
        if (user.length >= 3) t = t.replace(Regex("(?i)\\b${Regex.escape(user)}\\b"), "<user>")
        return t
    }

    /** Puts [reports] into one ZIP in [to] (as text files), redacted when [hideUser]. */
    fun export(reports: List<ErrorReport>, to: File, hideUser: Boolean): File {
        val out = File(to, "FilesKmp-error-reports-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.zip")
        ZipOutputStream(out.outputStream()).use { z ->
            reports.forEach { r ->
                z.putNextEntry(ZipEntry("${r.id}.txt"))
                z.write((if (hideUser) redact(r.text()) else r.text()).toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out
    }
}
