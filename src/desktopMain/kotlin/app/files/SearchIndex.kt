package app.files

import java.io.*
import java.nio.CharBuffer
import java.nio.file.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Instant file-name search, the way Everything does it (voidtools):
 *
 *  1. Everything reads each NTFS volume's Master File Table directly and follows the USN change
 *     journal, so it never walks folders and catches up on changes made while it was closed. Both
 *     need administrator rights (a raw volume handle), which this app doesn't ask for.
 *  2. It keeps only what a name search needs — name, parent, size, dates, attributes — packed in
 *     memory, and saves it to a database file so the next start is instant.
 *  3. A search is a brute-force scan over that packed list (no inverted index): with names in one
 *     contiguous block a modern CPU checks tens of millions of names per second, and the scan is
 *     split across threads. Paths are only rebuilt for the results (or for path terms).
 *
 * Here: (1) becomes a native folder walk with FindFirstFileEx (FastDir, several threads) plus a
 * recursive ReadDirectoryChangesW watch (Java's FILE_TREE watch) to stay current while the app
 * runs; the saved index is used at start and refreshed in the background. (2) and (3) follow
 * Everything: names in one char pool (plus a lower-case copy), parallel scan, no per-name objects.
 */
class IndexRoot(val root: String) {
    private val lock = ReentrantReadWriteLock()
    // Entry columns (parallel arrays, grown by doubling).
    private var parent = IntArray(1 shl 12)      // directory id of the folder the entry is in
    private var nameOff = IntArray(1 shl 12)
    private var nameLen = IntArray(1 shl 12)
    private var flags = ByteArray(1 shl 12)      // 1 dir, 2 deleted, 4 hidden, 8 system
    private var size = LongArray(1 shl 12)
    private var mtime = LongArray(1 shl 12)
    private var ctime = LongArray(1 shl 12)
    private var chars = CharArray(1 shl 16)
    private var lower = CharArray(1 shl 16)
    private var charCount = 0
    var count = 0; private set
    var deleted = 0; private set
    // Directories: id → full path, its entry index and its children (for watch updates).
    private val dirPath = ArrayList<String>()
    private val dirEntry = ArrayList<Int>()
    private val dirChildren = ArrayList<MutableList<Int>>()
    private val dirByPath = HashMap<String, Int>()

    @Volatile var builtAt = 0L; internal set
    @Volatile var buildMs = 0L; internal set

    init { addDir(root.trimEnd('\\').let { if (it.endsWith(":")) "$it\\" else it }, -1) }

    private fun addDir(path: String, entry: Int): Int {
        val id = dirPath.size
        dirPath.add(path); dirEntry.add(entry); dirChildren.add(ArrayList()); dirByPath[path.lowercase()] = id
        return id
    }

    private fun grow() {
        val n = parent.size * 2
        parent = parent.copyOf(n); nameOff = nameOff.copyOf(n); nameLen = nameLen.copyOf(n); flags = flags.copyOf(n)
        size = size.copyOf(n); mtime = mtime.copyOf(n); ctime = ctime.copyOf(n)
    }

    /** Adds one entry under directory [dirId]; returns the new directory id for folders, else -1. Caller holds the write lock. */
    private fun addEntry(dirId: Int, e: FastDir.Entry): Int {
        if (count == parent.size) grow()
        val n = e.name.length
        if (charCount + n > chars.size) {
            val cap = maxOf(chars.size * 2, charCount + n)
            chars = chars.copyOf(cap); lower = lower.copyOf(cap)
        }
        e.name.toCharArray(chars, charCount, 0, n)
        for (k in 0 until n) lower[charCount + k] = Character.toLowerCase(chars[charCount + k])
        val i = count++
        parent[i] = dirId; nameOff[i] = charCount; nameLen[i] = n; charCount += n
        var f = 0
        if (e.isDir) f = f or 1
        if (e.attributes and 0x2 != 0) f = f or 4
        if (isSystemItem(e.attributes and 0x4 != 0, e.name)) f = f or 8
        flags[i] = f.toByte(); size[i] = e.size; mtime[i] = e.modified; ctime[i] = e.created
        dirChildren[dirId].add(i)
        return if (e.isDir && !e.isReparse) addDir(childPath(dirId, e.name), i) else -1
    }

    private fun childPath(dirId: Int, name: String): String = dirPath[dirId].let { if (it.endsWith("\\")) it + name else "$it\\$name" }

    fun pathOf(i: Int): String = childPath(parent[i], String(chars, nameOff[i], nameLen[i]))

    /**
     * Walks everything under the root with FastDir on several threads. [progress] gets the count so
     * far. Skips the recycle bin and System Volume Information, like Everything's defaults.
     */
    fun build(threads: Int, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val started = System.currentTimeMillis()
        val queue = ConcurrentLinkedQueue<Int>().apply { add(0) }
        val pending = AtomicInteger(1)
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "index-$root").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        val done = java.util.concurrent.CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                try {
                    while (pending.get() > 0 && !cancelled()) {
                        val dirId = queue.poll() ?: run { Thread.sleep(2); null } ?: continue
                        val path = lock.read { dirPath[dirId] }
                        val batch = ArrayList<FastDir.Entry>(64)
                        FastDir.forEach(path, cancelled) { e ->
                            if (!(e.isDir && (e.name.equals("\$Recycle.Bin", true) || e.name.equals("System Volume Information", true)))) batch += e
                        }
                        val newDirs = lock.write { batch.mapNotNull { e -> addEntry(dirId, e).takeIf { it >= 0 } } }
                        pending.addAndGet(newDirs.size)
                        queue.addAll(newDirs)
                        if (pending.decrementAndGet() % 64 == 0) progress(count)
                    }
                } finally { done.countDown() }
            }
        }
        done.await()
        pool.shutdownNow()
        builtAt = System.currentTimeMillis()
        buildMs = builtAt - started
        progress(count)
    }

    /** Matches under [under] (a folder inside the root, or null for all), at most [limit], names sorted. */
    fun search(q: SearchQuery, caseSensitive: Boolean, under: String?, limit: Int, cancelled: () -> Boolean): List<FileItem> = lock.read {
        val n = count
        val prefix = under?.trimEnd('\\')?.lowercase()?.let { "$it\\" }
        val underDir = under?.let { dirByPath[it.trimEnd('\\').lowercase().let { p -> if (p.endsWith(":")) "$p\\" else p }] }
        // Folders inside [under]: an entry is in scope when its parent folder is one of them.
        val inScope: BooleanArray? = if (prefix == null || underDir == 0) null else BooleanArray(dirPath.size).also { arr ->
            for (d in dirPath.indices) { val p = dirPath[d].lowercase(); if (p == prefix.dropLast(1) || p.startsWith(prefix)) arr[d] = true }
        }
        val parts = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val found = AtomicInteger(0)
        val results = (0 until parts).toList().parallelStream().map { part ->
            val out = ArrayList<Int>()
            var i = part * (n / parts)
            val end = if (part == parts - 1) n else (part + 1) * (n / parts)
            while (i < end && found.get() < limit) {
                if (i and 0xFFF == 0 && cancelled()) break
                val f = flags[i].toInt()
                if (f and 2 == 0 && (inScope == null || inScope[parent[i]])) {
                    val idx = i
                    val c = SearchQuery.Candidate(
                        CharBuffer.wrap(chars, nameOff[i], nameLen[i]), if (caseSensitive) CharBuffer.wrap(chars, nameOff[i], nameLen[i]) else CharBuffer.wrap(lower, nameOff[i], nameLen[i]),
                        f and 1 != 0, size[i], mtime[i], ctime[i]) { pathOf(idx) }
                    if (q.matches(c)) { out += i; found.incrementAndGet() }
                }
                i++
            }
            out
        }.toList().flatten()
        results.take(limit).map { i ->
            val f = flags[i].toInt()
            FileItem(pathOf(i), String(chars, nameOff[i], nameLen[i]), f and 1 != 0, if (f and 1 != 0) 0 else size[i], mtime[i], ctime[i], f and 4 != 0, f and 8 != 0)
        }
    }

    // ---------------------------------------------------------------- live updates (watch events)

    private fun find(path: String): Pair<Int, Int>? {
        val parentPath = path.substringBeforeLast('\\').let { if (it.endsWith(":")) "$it\\" else it }
        val dirId = dirByPath[parentPath.lowercase()] ?: return null
        val name = path.substringAfterLast('\\')
        val i = dirChildren[dirId].firstOrNull { flags[it].toInt() and 2 == 0 && nameLen[it] == name.length && String(chars, nameOff[it], nameLen[it]).equals(name, true) }
        return i?.let { dirId to it }
    }

    /** A file or folder appeared (created, or moved / renamed in). Folders are read whole. */
    fun added(path: String) {
        val parentPath = path.substringBeforeLast('\\').let { if (it.endsWith(":")) "$it\\" else it }
        val name = path.substringAfterLast('\\')
        val e = statEntry(path, name) ?: return
        val newDirs = lock.write {
            find(path)?.let { (_, i) -> flags[i] = (flags[i].toInt() or 2).toByte(); deleted++ }
            val dirId = dirByPath[parentPath.lowercase()] ?: return
            listOfNotNull(addEntry(dirId, e).takeIf { it >= 0 })
        }
        // A folder moved in brings its contents with it.
        for (d in newDirs) {
            val queue = ArrayDeque<Int>().apply { add(d) }
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                val p = lock.read { dirPath[id] }
                val batch = ArrayList<FastDir.Entry>()
                FastDir.forEach(p) { batch += it }
                lock.write { batch.forEach { addEntry(id, it).takeIf { x -> x >= 0 }?.let { x -> queue.add(x) } } }
            }
        }
    }

    private fun statEntry(path: String, name: String): FastDir.Entry? = try {
        val a = Files.readAttributes(Paths.get(path), java.nio.file.attribute.DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        var attrs = 0
        if (a.isDirectory) attrs = attrs or 0x10; if (a.isHidden) attrs = attrs or 0x2; if (a.isSystem) attrs = attrs or 0x4
        if (a.isSymbolicLink || a.isOther) attrs = attrs or 0x400
        FastDir.Entry(name, attrs, if (a.isDirectory) 0 else a.size(), a.creationTime().toMillis(), a.lastModifiedTime().toMillis())
    } catch (_: Exception) { null }

    /** Gone (deleted, or moved / renamed away): it and, for a folder, everything inside. */
    fun removed(path: String) = lock.write {
        val (_, i) = find(path) ?: return@write
        fun drop(idx: Int) {
            flags[idx] = (flags[idx].toInt() or 2).toByte(); deleted++
            if (flags[idx].toInt() and 1 != 0) dirByPath[pathOf(idx).lowercase()]?.let { d -> dirChildren[d].forEach { drop(it) }; dirByPath.remove(dirPath[d].lowercase()) }
        }
        drop(i)
    }

    fun modified(path: String) {
        val e = statEntry(path, path.substringAfterLast('\\')) ?: return
        lock.write { find(path)?.let { (_, i) -> size[i] = e.size; mtime[i] = e.modified } }
    }

    // ---------------------------------------------------------------- saved index (next start is instant)

    fun save(f: File) = lock.read {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 20)).use { o ->
            o.writeInt(0x46494458); o.writeInt(1); o.writeUTF(root); o.writeLong(builtAt)
            o.writeInt(dirPath.size); dirPath.forEachIndexed { d, p -> o.writeUTF(p); o.writeInt(dirEntry[d]) }
            var live = 0
            for (i in 0 until count) if (flags[i].toInt() and 2 == 0) live++
            o.writeInt(live)
            for (i in 0 until count) {
                if (flags[i].toInt() and 2 != 0) continue
                o.writeInt(parent[i]); o.writeByte(flags[i].toInt()); o.writeLong(size[i]); o.writeLong(mtime[i]); o.writeLong(ctime[i])
                o.writeShort(nameLen[i]); for (k in 0 until nameLen[i]) o.writeChar(chars[nameOff[i] + k].code)
            }
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        fun load(f: File): IndexRoot? = try {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 20)).use { o ->
                if (o.readInt() != 0x46494458 || o.readInt() != 1) return null
                val r = IndexRoot(o.readUTF())
                r.builtAt = o.readLong()
                val dirs = o.readInt()
                r.dirPath.clear(); r.dirEntry.clear(); r.dirChildren.clear(); r.dirByPath.clear()
                repeat(dirs) { r.addDir(o.readUTF(), o.readInt()) }
                val n = o.readInt()
                while (r.parent.size < n) r.grow()
                val sb = CharArray(32768)
                // Entries saved without the deleted ones: directory → entry links are rebuilt from paths.
                val entryOfDirPath = HashMap<String, Int>()
                repeat(n) {
                    val p = o.readInt(); val fl = o.readByte(); val sz = o.readLong(); val mt = o.readLong(); val ct = o.readLong()
                    val len = o.readShort().toInt()
                    for (k in 0 until len) sb[k] = o.readChar()
                    val name = String(sb, 0, len)
                    r.lock.write {
                        if (r.charCount + len > r.chars.size) { val cap = maxOf(r.chars.size * 2, r.charCount + len); r.chars = r.chars.copyOf(cap); r.lower = r.lower.copyOf(cap) }
                        name.toCharArray(r.chars, r.charCount, 0, len)
                        for (k in 0 until len) r.lower[r.charCount + k] = Character.toLowerCase(r.chars[r.charCount + k])
                        val i = r.count++
                        r.parent[i] = p; r.nameOff[i] = r.charCount; r.nameLen[i] = len; r.charCount += len
                        r.flags[i] = fl; r.size[i] = sz; r.mtime[i] = mt; r.ctime[i] = ct
                        if (p in r.dirChildren.indices) r.dirChildren[p].add(i)
                        if (fl.toInt() and 1 != 0) entryOfDirPath[r.childPath(p, name).lowercase()] = i
                    }
                }
                for (d in r.dirPath.indices) entryOfDirPath[r.dirPath[d].lowercase()]?.let { r.dirEntry[d] = it }
                r
            }
        } catch (_: Exception) { null }
    }
}

/** The indexed folders (設定 → 搜尋), their saved files and their watchers. */
object SearchIndex {
    private val roots = java.util.concurrent.ConcurrentHashMap<String, IndexRoot>()
    private val watchers = java.util.concurrent.ConcurrentHashMap<String, Thread>()
    private fun fileFor(root: String) = File(AppDirs.cache, "index-" + Integer.toHexString(root.lowercase().hashCode()) + ".bin")

    fun get(root: String): IndexRoot? = roots[root.lowercase()]

    /** The index whose root contains [path] (the most specific one). */
    fun covering(path: String): IndexRoot? {
        val p = path.lowercase().trimEnd('\\')
        return roots.values.filter { r -> val rr = r.root.lowercase().trimEnd('\\'); p == rr || p.startsWith("$rr\\") }.maxByOrNull { it.root.length }
    }

    /** Loads the saved index (instant), then rebuilds in the background and watches for changes. */
    fun start(root: String, onStatus: (String, Int, Boolean) -> Unit) {
        if (roots.containsKey(root.lowercase())) return
        Thread.ofVirtual().start {
            IndexRoot.load(fileFor(root))?.let { roots[root.lowercase()] = it; onStatus(root, it.count - it.deleted, true) }
            rebuild(root, onStatus)
        }
    }

    fun rebuild(root: String, onStatus: (String, Int, Boolean) -> Unit) {
        Thread.ofVirtual().start {
            val fresh = IndexRoot(root)
            fresh.build(threads = 4, cancelled = { !watchersWanted(root) }) { n -> onStatus(root, n, false) }
            if (!watchersWanted(root)) return@start
            roots[root.lowercase()] = fresh
            onStatus(root, fresh.count, true)
            runCatching { fresh.save(fileFor(root)) }
            watch(root)
        }
    }

    private val wanted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun watchersWanted(root: String) = root.lowercase() in wanted

    fun want(rootsWanted: Collection<String>, onStatus: (String, Int, Boolean) -> Unit) {
        val lower = rootsWanted.map { it.lowercase() }.toSet()
        (wanted - lower).forEach { stop(it) }
        wanted.addAll(lower)
        rootsWanted.forEach { start(it, onStatus) }
    }

    fun stop(rootLower: String) {
        wanted.remove(rootLower)
        roots.remove(rootLower)
        watchers.remove(rootLower)?.interrupt()
        runCatching { fileFor(rootLower).delete() }
    }

    /** One recursive ReadDirectoryChangesW watch for the whole tree (FILE_TREE); overflow → rebuild. */
    private fun watch(root: String) {
        watchers.remove(root.lowercase())?.interrupt()
        val t = Thread.ofPlatform().daemon().priority(Thread.MIN_PRIORITY).name("watch-$root").start {
            try {
                FileSystems.getDefault().newWatchService().use { ws ->
                    val modifier = Class.forName("com.sun.nio.file.ExtendedWatchEventModifier").getField("FILE_TREE").get(null) as WatchEvent.Modifier
                    Paths.get(root).register(ws, arrayOf(StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY), modifier)
                    while (!Thread.currentThread().isInterrupted) {
                        val key = ws.take()
                        val idx = roots[root.lowercase()] ?: break
                        for (ev in key.pollEvents()) {
                            if (ev.kind() == StandardWatchEventKinds.OVERFLOW) { rebuild(root) { _, _, _ -> }; continue }
                            val full = Paths.get(root).resolve(ev.context() as Path).toString()
                            when (ev.kind()) {
                                StandardWatchEventKinds.ENTRY_CREATE -> idx.added(full)
                                StandardWatchEventKinds.ENTRY_DELETE -> idx.removed(full)
                                StandardWatchEventKinds.ENTRY_MODIFY -> idx.modified(full)
                            }
                        }
                        key.reset()
                    }
                }
            } catch (_: InterruptedException) {
            } catch (_: Throwable) {}
        }
        watchers[root.lowercase()] = t
    }
}
