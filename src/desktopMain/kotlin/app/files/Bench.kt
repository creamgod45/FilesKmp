package app.files

import com.sun.nio.file.ExtendedOpenOption
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * --bench-copy <work folder> [--size-mb N] [--count K] [--dest <folder on another disk>]
 *
 * Compares, on the same files:
 *  A) "all at once": K copies started together with the Windows copy routine
 *     (CopyFileEx, the routine underneath the shell copy engine the original uses),
 *     each on its own thread with no coordination — the original's pattern;
 *  B) this app's engine: the same K tasks queued one per disk, big unbuffered blocks.
 * Source files are written with unbuffered I/O so they are not sitting in the
 * memory cache (which would flatter whichever test reads them).
 */
object Bench {
    fun run(args: List<String>) {
        val work = File(args[0]).absoluteFile
        val sizeMb = args.optionInt("--size-mb", 1024)
        val count = args.optionInt("--count", 3)
        val destBase = args.option("--dest")?.let { File(it).absoluteFile } ?: work
        work.mkdirs(); destBase.mkdirs()
        val bytes = sizeMb.toLong() * 1024 * 1024
        println("Work folder: $work   destination: $destBase")
        println("Files: $count x $sizeMb MB per test (times include flushing to disk)")
        val until = System.currentTimeMillis() + 20_000 // let the disk map load
        while (CopyEngine.deviceOf(work.path).startsWith("drive") && System.currentTimeMillis() < until) Thread.sleep(100)
        println("Source disk: ${CopyEngine.deviceOf(work.path)}, destination disk: ${CopyEngine.deviceOf(destBase.path)} (${CopyEngine.describeDisks()})\n")
        try {
            // Single file
            val s1 = makeSources(work, "single-a", 1, bytes)
            val s2 = makeSources(work, "single-b", 1, bytes)
            val d1 = File(destBase, "out-single-a").apply { mkdirs() }
            val d2 = File(destBase, "out-single-b").apply { mkdirs() }
            val tA1 = time { Files.copy(s1[0], d1.toPath().resolve(s1[0].fileName), StandardCopyOption.REPLACE_EXISTING); flushAll(d1) }
            val tB1 = time { engineCopy(s2.map { listOf(it.toString()) }, d2); flushAll(d2) }
            report("1 file, Windows copy routine", bytes, tA1)
            report("1 file, this app's engine", bytes, tB1)

            // Several at once
            val srcA = makeSources(work, "multi-a", count, bytes)
            val srcB = makeSources(work, "multi-b", count, bytes)
            val dA = File(destBase, "out-multi-a").apply { mkdirs() }
            val dB = File(destBase, "out-multi-b").apply { mkdirs() }
            val tA = time {
                val threads = srcA.map { src ->
                    Thread { Files.copy(src, dA.toPath().resolve(src.fileName), StandardCopyOption.REPLACE_EXISTING) }.also { it.start() }
                }
                threads.forEach { it.join() }
                flushAll(dA)
            }
            val tB = time { engineCopy(srcB.map { listOf(it.toString()) }, dB); flushAll(dB) }
            report("$count tasks at once, no coordination (original's pattern)", bytes * count, tA)
            report("$count tasks, this app's engine (HDD: queued, SSD: up to ${M.SSD_PARALLEL_JOBS})", bytes * count, tB)
            verify(listOf(s1 to d1, s2 to d2, srcA to dA, srcB to dB))
        } finally {
            listOf("single-a", "single-b", "multi-a", "multi-b").forEach { File(work, it).deleteRecursively() }
            listOf("out-single-a", "out-single-b", "out-multi-a", "out-multi-b").forEach { File(destBase, it).deleteRecursively() }
        }
    }

    private fun engineCopy(tasks: List<List<String>>, dest: File) {
        val jobs = tasks.map { CopyEngine.submit(OpKind.Copy, it, dest.path) }
        while (jobs.any { it.state == JobState.Queued || it.state == JobState.Running }) Thread.sleep(20)
        jobs.firstOrNull { it.state == JobState.Failed }?.let { error("engine failed: ${it.error}") }
    }

    private fun makeSources(work: File, name: String, count: Int, bytes: Long): List<Path> {
        val dir = File(work, name).apply { mkdirs() }
        return (1..count).map { i ->
            val p = File(dir, "file$i.bin").toPath()
            val buf = ByteBuffer.allocateDirect(M.COPY_BUFFER_BYTES + 4096).alignedSlice(4096)
            var seed = (i * 7919L) xor name.hashCode().toLong()
            FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, ExtendedOpenOption.DIRECT).use { ch ->
                var written = 0L
                while (written < bytes) {
                    buf.clear()
                    while (buf.remaining() >= 8) { seed = seed * 6364136223846793005L + 1442695040888963407L; buf.putLong(seed) }
                    buf.flip()
                    val n = minOf(buf.remaining().toLong(), bytes - written).toInt()
                    buf.limit(n)
                    while (buf.hasRemaining()) ch.write(buf)
                    written += n
                }
            }
            p
        }
    }

    private fun verify(pairs: List<Pair<List<Path>, File>>) {
        var ok = 0
        var total = 0
        for ((sources, dest) in pairs) for (src in sources) {
            total++
            val dst = File(dest, src.fileName.toString()).toPath()
            if (Files.exists(dst) && Files.mismatch(src, dst) == -1L) ok++
        }
        println("\nVerified byte-for-byte identical copies: $ok of $total")
    }

    /** Waits until everything written to [dir] is really on the disk (FlushFileBuffers), so cached writes can't look faster than they are. */
    private fun flushAll(dir: File) {
        dir.listFiles()?.forEach { f -> FileChannel.open(f.toPath(), StandardOpenOption.WRITE).use { it.force(true) } }
    }

    private fun time(block: () -> Unit): Double {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e9
    }

    private fun report(label: String, bytes: Long, secs: Double) {
        val mbps = bytes / 1024.0 / 1024.0 / secs
        println(String.format("%-62s %7.2f s  %8.1f MB/s", label, secs, mbps))
    }

    private fun List<String>.option(name: String): String? = indexOf(name).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }
    private fun List<String>.optionInt(name: String, def: Int) = option(name)?.toIntOrNull() ?: def
}
