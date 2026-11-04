package app.files

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Network browsing inside the app (instead of handing "網路" to Explorer):
 * - [NETWORK]: servers opened before, LAN hosts answering on SMB (445) or NFS (2049), and the
 *   file servers Windows' own Network folder knows about;
 * - `\\server`: its SMB shares (NetShareEnum) and, with Windows' NFS client, its NFS exports;
 * - `\\server\share\…`: an ordinary folder, read through Windows (SMB, or NFS via the NFS client).
 * smb://server/share and nfs://server/export typed in the address bar become UNC paths.
 */
object NetworkFs {
    private const val SMB_PORT = 445
    private const val NFS_PORT = 2049

    // ------------------------------------------------------------ paths

    /** `\\server` (or `\\server\`): a server, not a folder. */
    fun isServer(path: String) = Regex("""^\\\\[^\\/]+\\?$""").matches(path)

    /** `\\server\share` (the top of a share). */
    fun isShareRoot(path: String) = Regex("""^\\\\[^\\/]+\\[^\\/]+\\?$""").matches(path)

    fun isUnc(path: String) = path.startsWith("\\\\") && !path.startsWith("\\\\?\\") && !path.startsWith("\\\\.\\")

    /** `\\wsl.localhost` / `\\wsl$`: WSL's own file system (not SMB); its "shares" are the distributions. */
    fun isWslHost(host: String) = host.equals("wsl.localhost", true) || host.equals("wsl$", true)

    /**
     * A plain host name or IPv4 address (letters, digits, '.', '-', '_', and a trailing '$' as in wsl$).
     * Anything else is never remembered or handed to a shell.
     */
    fun isValidHost(host: String) = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,252}\$?$""").matches(host)

    /** `\\server\share\dir` → `server`. */
    fun hostOf(path: String) = path.removePrefix("\\\\").substringBefore('\\').substringBefore('/')

    fun serverPath(host: String) = "\\\\$host"

    /**
     * smb://server/share/dir and nfs://server/export/dir → `\\server\share\dir`; `//server/x` and
     * `\\server\` are tidied. Windows reaches NFS exports by UNC too, once its NFS client is on.
     */
    fun normalize(input: String): String {
        val s = input.trim()
        val m = Regex("""^(?i)(smb|cifs|nfs)://([^/]+)(/.*)?$""").find(s)
        val unc = when {
            m != null -> "\\\\" + m.groupValues[2] + m.groupValues[3].replace('/', '\\')
            s.startsWith("//") -> s.replace('/', '\\')
            else -> s
        }
        return if (isUnc(unc)) unc.trimEnd('\\').let { if (it.count { c -> c == '\\' } < 2) s else it } else unc
    }

    fun isNfsUrl(input: String) = input.trim().startsWith("nfs://", ignoreCase = true)

    /** Windows' "Client for NFS" (Windows features → Services for NFS) brings showmount.exe and mount.exe. */
    val nfsClientInstalled: Boolean by lazy {
        File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\showmount.exe").isFile
    }

    // ------------------------------------------------------------ remembered servers

    private const val SEP = "\u0001"

    // WSL is never a 網路 server (it has its own sidebar section), even if an older build saved it.
    fun rememberedServers(): List<String> = Prefs.str("netServers", "").split(SEP).filter { isValidHost(it) && !isWslHost(it) }

    fun remember(host: String) {
        if (isWslHost(host) || !isValidHost(host)) return
        val list = (listOf(host) + rememberedServers().filter { !it.equals(host, true) }).take(20)
        Prefs.put(mapOf("netServers" to list.joinToString(SEP)))
    }

    fun forget(host: String) {
        Prefs.put(mapOf("netServers" to rememberedServers().filter { !it.equals(host, true) }.joinToString(SEP)))
    }

    // ------------------------------------------------------------ the 網路 page

    private fun serverItem(host: String, label: String) =
        FileItem(serverPath(host), host, isDir = true, size = 0, modified = 0, created = 0, hidden = false, typeLabel = label)

    /**
     * Fills the 網路 page in steps, calling [onUpdate] with the growing list: remembered servers at
     * once, then the LAN scan (a couple of seconds), then Windows' Network folder (can take ~30 s).
     */
    fun discover(cancelled: () -> Boolean, onUpdate: (List<FileItem>) -> Unit) {
        val found = LinkedHashMap<String, FileItem>()
        fun add(host: String, label: String) {
            val key = host.lowercase()
            val old = found[key]
            found[key] = if (old == null || old.typeLabel == S.netRemembered) serverItem(host, label) else old
        }
        fun publish() { if (!cancelled()) onUpdate(found.values.toList()) }

        rememberedServers().forEach { add(it, S.netRemembered) }
        publish()

        val pool = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val windows = CompletableFuture.supplyAsync({ windowsNetworkServers() }, pool)
            for ((host, kinds) in scanLan(pool, cancelled)) {
                if (cancelled()) return
                add(host, kinds)
            }
            publish()
            runCatching { windows.get(M.NET_WINDOWS_DISCOVERY_MS, TimeUnit.MILLISECONDS) }.getOrNull()
                ?.forEach { add(it, S.netSmbServer) }
            publish()
        } finally {
            pool.shutdownNow()
        }
    }

    /** Hosts in the ARP table (machines this PC talked to recently) that answer on SMB or NFS. */
    private fun scanLan(pool: java.util.concurrent.ExecutorService, cancelled: () -> Boolean): List<Pair<String, String>> {
        val ips = arpNeighbours()
        val tasks = ips.map { ip ->
            pool.submit(Callable {
                val smb = portOpen(ip, SMB_PORT); val nfs = portOpen(ip, NFS_PORT)
                if (!smb && !nfs) null else {
                    val name = runCatching {
                        CompletableFuture.supplyAsync({ InetAddress.getByName(ip).hostName }, pool).get(1500, TimeUnit.MILLISECONDS)
                    }.getOrNull()?.takeIf { it != ip }?.substringBefore('.')?.uppercase() ?: ip
                    name to when {
                        smb && nfs -> S.netSmbNfsServer
                        smb -> S.netSmbServer
                        else -> S.netNfsServer
                    }
                }
            })
        }
        return tasks.mapNotNull { if (cancelled()) null else runCatching { it.get(3, TimeUnit.SECONDS) }.getOrNull() }
    }

    private fun portOpen(host: String, port: Int) = try {
        Socket().use { it.connect(InetSocketAddress(host, port), M.NET_PROBE_TIMEOUT_MS); true }
    } catch (_: Exception) { false }

    /** IPv4 neighbours from `arp -a` (unicast entries only; the column titles vary by language, the numbers don't). */
    private fun arpNeighbours(): List<String> = try {
        val p = ProcessBuilder("arp", "-a").redirectErrorStream(true).start()
        p.outputStream.close()
        val text = CompletableFuture.supplyAsync { String(p.inputStream.readAllBytes(), Charsets.ISO_8859_1) }
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); emptyList() }
        else Regex("""(\d{1,3}(?:\.\d{1,3}){3})\s+([0-9a-fA-F]{2}(?:-[0-9a-fA-F]{2}){5})""").findAll(text.get())
            .filter { m ->
                val ip = m.groupValues[1]; val mac = m.groupValues[2].lowercase()
                val first = ip.substringBefore('.').toInt()
                mac != "ff-ff-ff-ff-ff-ff" && !mac.startsWith("01-00-5e") && first in 1..223 && !ip.endsWith(".255")
            }
            .map { it.groupValues[1] }.distinct().toList()
    } catch (_: Exception) { emptyList() }

    /** File servers in Windows' Network folder (WSD / NetBIOS discovery, the same list Explorer shows). */
    private fun windowsNetworkServers(): List<String> {
        val script = "(New-Object -ComObject Shell.Application).NameSpace(18).Items() | " +
            "Where-Object { \$_.Path -like '\\\\*' } | ForEach-Object { \$_.Path }"
        return Fs.powershell(script, M.NET_WINDOWS_DISCOVERY_MS)?.lines()
            ?.map { it.trim() }?.filter { isServer(it) }?.map { hostOf(it) }.orEmpty()
    }

    // ------------------------------------------------------------ a server's shares and exports

    /**
     * What `\\server` offers: SMB disk shares (admin shares like C$ only when 系統檔案 are shown)
     * and NFS exports when Windows' NFS client is installed. Throws when the server can't be read.
     */
    fun serverContents(host: String, includeAdminShares: Boolean): List<FileItem> {
        if (isWslHost(host)) return Fs.wslDistros().map {
            FileItem("\\\\$host\\$it", it, isDir = true, size = 0, modified = 0, created = 0, hidden = false, typeLabel = S.wslDistro)
        }
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val smb = pool.submit(Callable { smbShares(host) })
            val nfs = pool.submit(Callable { if (nfsClientInstalled) nfsExports(host) else emptyList() })
            val smbResult = runCatching { smb.get(M.NET_SHARE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            val nfsList = runCatching { nfs.get(M.NET_SHARE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
            val out = ArrayList<FileItem>()
            smbResult.getOrNull()?.filter { it.isDisk && (includeAdminShares || !it.special) }?.forEach {
                out += FileItem("\\\\$host\\${it.name}", it.name, isDir = true, size = 0, modified = 0, created = 0,
                    hidden = false, system = it.special, typeLabel = S.netSmbShare)
            }
            nfsList.forEach { export ->
                val unc = "\\\\$host" + export.replace('/', '\\')
                out += FileItem(unc, export, isDir = true, size = 0, modified = 0, created = 0, hidden = false, typeLabel = S.netNfsExport)
            }
            if (out.isEmpty() && smbResult.isFailure && nfsList.isEmpty()) {
                // Not an SMB server (Remote Desktop's \\tsclient, VirtualBox's \\VBoxSvr, …): ask the
                // Windows Shell, which lists any network provider's top level.
                shellChildren(host)?.let { return it }
                val e = smbResult.exceptionOrNull()
                throw IllegalStateException((e as? java.util.concurrent.ExecutionException)?.cause?.message ?: e?.message ?: S.netUnreachable)
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /** The folders the Windows Shell shows under `\\host` (null when it can't open the host). */
    private fun shellChildren(host: String): List<FileItem>? {
        if (!isValidHost(host)) return null
        // The host reaches PowerShell as an environment variable and is never part of the script text
        // (quoting alone isn't enough: PowerShell also treats the Unicode quotes ‘ ’ ‚ ‛ as quotes).
        val script = "\$f = (New-Object -ComObject Shell.Application).NameSpace('\\\\' + \$env:FILESKMP_HOST); " +
            "if (\$f) { 'OK'; \$f.Items() | Where-Object { \$_.IsFolder -and \$_.Path -like '\\\\*' } | ForEach-Object { \$_.Name + [char]9 + \$_.Path } }"
        val out = Fs.powershell(script, M.NET_SHARE_TIMEOUT_MS, mapOf("FILESKMP_HOST" to host))?.lines()?.map { it.trim() } ?: return null
        if (out.firstOrNull() != "OK") return null
        return out.drop(1).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2 && parts[1].startsWith("\\\\")) FileItem(parts[1], parts[0], isDir = true, size = 0, modified = 0,
                created = 0, hidden = false, typeLabel = S.netShare) else null
        }
    }

    class Share(val name: String, val type: Int) {
        val isDisk get() = (type and 0xFF) == 0          // STYPE_DISKTREE
        val special get() = (type and 0x80000000.toInt()) != 0 // STYPE_SPECIAL: C$, ADMIN$, IPC$
    }

    /** NetShareEnum level 1 (netapi32), through the JDK's foreign-function API: Unicode, no text parsing. */
    fun smbShares(host: String): List<Share> = Arena.ofConfined().use { arena ->
        val netapi = SymbolLookup.libraryLookup("netapi32", arena)
        val linker = Linker.nativeLinker()
        val enum = linker.downcallHandle(
            netapi.find("NetShareEnum").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
        val free = linker.downcallHandle(
            netapi.find("NetApiBufferFree").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
        val server = arena.allocateFrom("\\\\$host", Charsets.UTF_16LE)
        val buf = arena.allocate(ValueLayout.ADDRESS)
        val read = arena.allocate(ValueLayout.JAVA_INT)
        val total = arena.allocate(ValueLayout.JAVA_INT)
        val resume = arena.allocate(ValueLayout.JAVA_INT).also { it.set(ValueLayout.JAVA_INT, 0, 0) }
        val out = ArrayList<Share>()
        // MAX_PREFERRED_LENGTH (-1) normally returns everything at once; ERROR_MORE_DATA (234) is still
        // followed with the resume handle, so a server with many shares is never cut short.
        do {
            val status = enum.invokeWithArguments(server, 1, buf, -1, read, total, resume) as Int
            if (status != 0 && status != 234) throw IllegalStateException(netError(status))
            val base = buf.get(ValueLayout.ADDRESS, 0)
            try {
                val n = read.get(ValueLayout.JAVA_INT, 0)
                // SHARE_INFO_1 on x64: LPWSTR shi1_netname; DWORD shi1_type; (pad); LPWSTR shi1_remark = 24 bytes
                val entries = base.reinterpret(24L * n)
                for (i in 0 until n) {
                    val name = entries.get(ValueLayout.ADDRESS, i * 24L).reinterpret(Long.MAX_VALUE).getString(0, Charsets.UTF_16LE)
                    out += Share(name, entries.get(ValueLayout.JAVA_INT, i * 24L + 8))
                }
            } finally {
                if (base != MemorySegment.NULL) free.invokeWithArguments(base)
            }
        } while (status == 234 && read.get(ValueLayout.JAVA_INT, 0) > 0)
        out
    }

    private fun netError(code: Int) = when (code) {
        5 -> S.netAccessDenied
        53, 1231, 2250 -> S.netUnreachable
        1326, 86 -> S.netAccessDenied
        else -> (if (S.zh) "網路錯誤 " else "Network error ") + code
    }

    /** `showmount -e host` (part of Windows' NFS client): one export path per line after the title. */
    private fun nfsExports(host: String): List<String> = try {
        val p = ProcessBuilder("showmount", "-e", host).redirectErrorStream(true).start()
        p.outputStream.close()
        val text = CompletableFuture.supplyAsync { String(p.inputStream.readAllBytes()) }
        if (!p.waitFor(M.NET_SHARE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); emptyList() }
        else text.get().lines().drop(1).map { it.trim().substringBefore(' ') }.filter { it.startsWith("/") }
    } catch (_: Exception) { emptyList() }
}
