package app.files

import androidx.compose.runtime.*
import com.sun.nio.file.ExtendedOpenOption
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

enum class OpKind { Copy, Move }
enum class JobState { Queued, Running, Done, Failed, Cancelled }

/**
 * One copy or move task. Progress lives in plain atomics written by the worker;
 * the UI reads them on a 100 ms tick, so the copy never waits for the UI.
 */
class CopyJob(val id: Int, val kind: OpKind, val sources: List<String>, val destDir: String) {
    var state by mutableStateOf(JobState.Queued)
    var error by mutableStateOf<String?>(null)
    val totalBytes = AtomicLong(0)
    val doneBytes = AtomicLong(0)
    val totalFiles = AtomicLong(0)
    val doneFiles = AtomicLong(0)
    @Volatile var currentFile: String = ""
    val cancelled = AtomicBoolean(false)
    var startedAt = 0L
    var finishedAt = 0L
    val devices: Set<String> = (sources.map { CopyEngine.deviceOf(it) } + CopyEngine.deviceOf(destDir)).toSet()

    // UI snapshot (refreshed by the tick)
    var uiDone by mutableStateOf(0L)
    var uiTotal by mutableStateOf(0L)
    var uiSpeed by mutableStateOf(0.0)
    var uiFile by mutableStateOf("")

    val title: String
        get() = "${if (kind == OpKind.Copy) "Copying" else "Moving"} ${sources.size} item${if (sources.size == 1) "" else "s"} to ${Fs.displayName(destDir)}"
}

object CopyEngine {
    val jobs = mutableStateListOf<CopyJob>()
    private var nextId = 1
    private val busy = HashMap<String, Int>()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var diskOfLetter: Map<Char, String> = emptyMap()
    @Volatile private var solidState: Set<String> = emptySet()
    @Volatile private var usbDisks: Set<String> = emptySet()
    var disksLoaded by mutableStateOf(false)

    fun isUsb(root: String) = deviceOf(root) in usbDisks
    var onJobFinished: ((CopyJob) -> Unit)? = null

    /** Spinning disks (and anything unknown) take one task at a time; SSDs take several. */
    private fun limitFor(device: String) = if (device in solidState) M.SSD_PARALLEL_JOBS else 1

    init {
        // Which physical disk each drive letter lives on (two partitions of one disk
        // count as one device) and which disks are SSDs. Looked up once, in the background.
        scope.launch {
            try {
                val text = Fs.powershell(
                    "Get-Partition | Where-Object DriveLetter | ForEach-Object { \"P`t\$(\$_.DriveLetter)`t\$(\$_.DiskNumber)\" }; " +
                        "Get-PhysicalDisk | ForEach-Object { \"D`t\$(\$_.DeviceId)`t\$(\$_.MediaType)\" }; " +
                        "Get-Disk | ForEach-Object { \"U`t\$(\$_.Number)`t\$(\$_.BusType)\" }",
                    15_000,
                ) ?: return@launch
                val rows = text.lines().map { it.trim().split('\t') }.filter { it.size == 3 }
                diskOfLetter = rows.filter { it[0] == "P" && it[1].length == 1 }.associate { it[1].uppercase()[0] to "disk${it[2]}" }
                usbDisks = rows.filter { it[0] == "U" && it[2].equals("USB", true) }.map { "disk${it[1]}" }.toSet()
                solidState = rows.filter { it[0] == "D" && it[2].equals("SSD", true) }.map { "disk${it[1]}" }.toSet()
                disksLoaded = true
            } catch (_: Exception) {
            }
        }
        scope.launch {
            while (true) {
                delay(M.PROGRESS_INTERVAL_MS.milliseconds)
                tick()
            }
        }
    }

    fun describeDisks(): String = "disks=$diskOfLetter ssd=$solidState"

    fun deviceOf(path: String): String {
        val root = Paths.get(path).root?.toString() ?: path
        if (root.length >= 2 && root[1] == ':') {
            val letter = root[0].uppercaseChar()
            return diskOfLetter[letter] ?: "drive$letter"
        }
        // UNC: one device per server
        return root.trim('\\').substringBefore('\\').lowercase().ifEmpty { root }
    }

    fun submit(kind: OpKind, sources: List<String>, destDir: String): CopyJob {
        val job = CopyJob(nextId++, kind, sources, destDir)
        jobs.add(0, job)
        schedule()
        return job
    }

    fun cancel(job: CopyJob) {
        job.cancelled.set(true)
        if (job.state == JobState.Queued) {
            job.state = JobState.Cancelled
            schedule()
        }
    }

    fun clearFinished() {
        jobs.removeAll { it.state != JobState.Queued && it.state != JobState.Running }
    }

    /** Starts every queued job whose disks are all free: one job per disk at a time, different disks in parallel. */
    private fun schedule() {
        synchronized(lock) {
            for (job in jobs.reversed()) {
                if (job.state != JobState.Queued || job.cancelled.get()) continue
                if (job.devices.any { (busy[it] ?: 0) >= limitFor(it) }) continue
                job.devices.forEach { busy[it] = (busy[it] ?: 0) + 1 }
                job.state = JobState.Running
                job.startedAt = System.nanoTime()
                scope.launch {
                    try {
                        run(job)
                        job.state = if (job.cancelled.get()) JobState.Cancelled else JobState.Done
                    } catch (e: Throwable) {
                        job.error = e.message ?: e.toString()
                        job.state = if (job.cancelled.get()) JobState.Cancelled else JobState.Failed
                    } finally {
                        job.finishedAt = System.nanoTime()
                        synchronized(lock) { job.devices.forEach { d -> busy[d] = ((busy[d] ?: 1) - 1).coerceAtLeast(0) } }
                        tick()
                        onJobFinished?.invoke(job)
                        schedule()
                    }
                }
            }
        }
    }

    private fun tick() {
        for (job in jobs.toList()) {
            if (job.state != JobState.Running && job.uiDone == job.doneBytes.get()) continue
            job.uiDone = job.doneBytes.get()
            job.uiTotal = job.totalBytes.get()
            job.uiFile = job.currentFile
            val end = if (job.finishedAt > 0) job.finishedAt else System.nanoTime()
            val secs = (end - job.startedAt) / 1e9
            job.uiSpeed = if (secs > 0) job.uiDone / secs else 0.0
        }
    }

    // ---------------------------------------------------------------- worker

    private class Planned(val src: Path, val dst: Path, val size: Long, val isDir: Boolean)

    fun run(job: CopyJob) {
        val destRoot = Paths.get(job.destDir)
        val plan = ArrayList<Planned>()
        val sameDeviceMoves = ArrayList<Pair<Path, Path>>()
        for (s in job.sources) {
            val src = Paths.get(s)
            var dst = destRoot.resolve(src.fileName.toString())
            if (src.toAbsolutePath() == dst.toAbsolutePath() || Files.exists(dst)) {
                if (job.kind == OpKind.Move && src.toAbsolutePath() == dst.toAbsolutePath()) continue
                dst = uniqueCopyName(dst)
            }
            if (job.kind == OpKind.Move && src.root == destRoot.root) {
                sameDeviceMoves += src to dst
                continue
            }
            // Count sizes in the same pass that builds the plan (no separate scan racing the copy).
            Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    plan += Planned(dir, dst.resolve(src.relativize(dir).toString()), 0, true)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val target = if (file == src) dst else dst.resolve(src.relativize(file).toString())
                    plan += Planned(file, target, attrs.size(), false)
                    job.totalBytes.addAndGet(attrs.size())
                    job.totalFiles.incrementAndGet()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        // Moves inside one drive are just renames.
        for ((src, dst) in sameDeviceMoves) {
            if (job.cancelled.get()) return
            job.currentFile = src.fileName.toString()
            Files.move(src, dst)
        }
        for (item in plan) {
            if (job.cancelled.get()) return
            if (item.isDir) {
                Files.createDirectories(item.dst)
                continue
            }
            job.currentFile = item.src.fileName.toString()
            copyFile(item.src, item.dst, item.size, job.doneBytes, job.cancelled)
            job.doneFiles.incrementAndGet()
        }
        if (job.kind == OpKind.Move && !job.cancelled.get()) {
            for (s in job.sources) {
                val f = File(s)
                if (f.exists() && plan.any { it.src == f.toPath() }) f.deleteRecursively()
            }
        }
    }

    fun uniqueCopyName(dst: Path): Path {
        val name = dst.fileName.toString()
        val isDir = Files.isDirectory(dst)
        val base = if (isDir || !name.contains('.')) name else name.substringBeforeLast('.')
        val ext = if (isDir || !name.contains('.')) "" else "." + name.substringAfterLast('.')
        var candidate = dst.resolveSibling("$base - Copy$ext")
        var i = 2
        while (Files.exists(candidate)) {
            candidate = dst.resolveSibling("$base - Copy ($i)$ext"); i++
        }
        return candidate
    }

    private fun isSsd(p: Path) = deviceOf(p.toString()) in solidState

    /**
     * Copies one file. Small files, and big files going from SSD to SSD, use Windows'
     * own copy routine (fastest there, measured). Big files touching a hard disk use
     * large unbuffered chunks with several I/Os in flight.
     */
    fun copyFile(src: Path, dst: Path, size: Long, done: AtomicLong, cancelled: AtomicBoolean) {
        val forceOwn = System.getProperty("copy.engine") == "own"
        if (size < M.DIRECT_IO_THRESHOLD || (!forceOwn && isSsd(src) && isSsd(dst))) {
            windowsCopy(src, dst, size, done, cancelled)
            return
        }
        val before = done.get()
        try {
            pipelinedCopy(src, dst, size, done, cancelled, direct = true)
        } catch (e: IOException) {
            if (cancelled.get()) throw e
            done.set(before)
            pipelinedCopy(src, dst, size, done, cancelled, direct = false)
        } catch (e: UnsupportedOperationException) {
            done.set(before)
            pipelinedCopy(src, dst, size, done, cancelled, direct = false)
        }
        try {
            Files.setLastModifiedTime(dst, Files.getLastModifiedTime(src))
        } catch (_: Exception) {
        }
        if (cancelled.get()) Files.deleteIfExists(dst)
    }

    /** Windows copy routine; progress for big files comes from watching the destination grow. */
    private fun windowsCopy(src: Path, dst: Path, size: Long, done: AtomicLong, cancelled: AtomicBoolean) {
        val before = done.get()
        val watcher = if (size >= M.DIRECT_IO_THRESHOLD) Thread.ofVirtual().start {
            try {
                while (true) {
                    Thread.sleep(M.PROGRESS_INTERVAL_MS)
                    val now = try { Files.size(dst) } catch (_: Exception) { 0L }
                    done.set(before + now.coerceAtMost(size))
                }
            } catch (_: InterruptedException) {
            }
        } else null
        try {
            if (cancelled.get()) return
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        } finally {
            watcher?.interrupt()
            watcher?.join()
            done.set(before + size)
        }
    }

    /**
     * Large-file copy: the file is cut into big chunks and several workers, each with
     * its own read and write handle, keep several reads and writes in flight at once
     * (like FastCopy's overlapped I/O). With [direct] the Windows file cache is bypassed.
     */
    private fun pipelinedCopy(src: Path, dst: Path, size: Long, done: AtomicLong, cancelled: AtomicBoolean, direct: Boolean) {
        val align = 4096L
        val chunk = (System.getProperty("copy.chunkMb")?.toLongOrNull()?.times(1024 * 1024)) ?: M.COPY_BUFFER_BYTES.toLong()
        val chunks = (size + chunk - 1) / chunk
        val workers = (System.getProperty("copy.depth")?.toLongOrNull() ?: M.COPY_IO_DEPTH.toLong()).coerceAtMost(chunks).coerceAtLeast(1).toInt()
        val rounded = if (direct) ((size + align - 1) / align) * align else size
        // Size the destination once up front so the workers don't each have to grow it.
        val prealloc = System.getProperty("copy.prealloc")?.toBoolean() ?: M.COPY_PREALLOCATE
        RandomAccessFile(dst.toFile(), "rw").use { it.setLength(0); if (prealloc) it.setLength(rounded) }
        val readOpts = mutableSetOf<OpenOption>(StandardOpenOption.READ)
        val writeOpts = mutableSetOf<OpenOption>(StandardOpenOption.WRITE)
        if (direct) {
            readOpts += ExtendedOpenOption.DIRECT
            writeOpts += ExtendedOpenOption.DIRECT
        }
        val next = AtomicLong(0)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val threads = (0 until workers).map { w ->
            Thread.ofPlatform().name("copy-io-$w").start {
                try {
                    val buf = ByteBuffer.allocateDirect((chunk + align).toInt()).alignedSlice(align.toInt())
                    FileChannel.open(src, readOpts).use { input ->
                        FileChannel.open(dst, writeOpts).use { output ->
                            while (!cancelled.get() && failure.get() == null) {
                                val i = next.getAndIncrement()
                                if (i >= chunks) break
                                val pos = i * chunk
                                val want = minOf(chunk, size - pos)
                                buf.clear()
                                buf.limit((if (direct) ((want + align - 1) / align) * align else want).toInt())
                                while (buf.hasRemaining()) {
                                    val r = input.read(buf, pos + buf.position())
                                    if (r < 0) break
                                    // A short unbuffered read means end of file (position no longer aligned).
                                    if (direct && r.toLong() % align != 0L) break
                                }
                                val got = buf.position().toLong().coerceAtMost(want)
                                buf.flip()
                                if (direct && buf.limit() % align.toInt() != 0) {
                                    buf.limit((((buf.limit() + align - 1) / align) * align).toInt())
                                }
                                while (buf.hasRemaining()) output.write(buf, pos + buf.position())
                                done.addAndGet(got)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        threads.forEach { it.join() }
        failure.get()?.let { throw if (it is IOException) it else IOException(it) }
        if (rounded != size) RandomAccessFile(dst.toFile(), "rw").use { it.setLength(size) }
    }
}
