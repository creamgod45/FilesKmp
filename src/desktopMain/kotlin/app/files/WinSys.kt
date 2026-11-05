package app.files

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.util.concurrent.TimeUnit

/**
 * Windows features the shortcut actions use, called through the documented Win32 / COM APIs
 * (java.lang.foreign) instead of scripts wherever one exists:
 *  - volume: Core Audio IAudioEndpointVolume (COM, default output device)
 *  - battery: GetSystemPowerStatus
 *  - sounds: MessageBeep, PlaySoundW (wav) and MCI (mp3, m4a, wma …)
 *  - secrets: DPAPI CryptProtectData (passwords for mail / SMS, readable only by this Windows user)
 *  - notification banners ("do not disturb"): the per-user toast switch
 *  - screenshots: java.awt.Robot at device resolution
 */
object WinSys {
    private val linker = Linker.nativeLinker()
    private val A = ValueLayout.ADDRESS
    private val I = ValueLayout.JAVA_INT
    private val F = ValueLayout.JAVA_FLOAT

    private fun fn(lib: String, name: String, desc: FunctionDescriptor, arena: Arena) =
        linker.downcallHandle(SymbolLookup.libraryLookup(lib, arena).find(name).orElseThrow(), desc)

    /** A GUID in memory from its "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" form. */
    private fun guid(arena: Arena, s: String): MemorySegment {
        val h = s.replace("-", "")
        val m = arena.allocate(16)
        m.set(ValueLayout.JAVA_INT_UNALIGNED, 0, h.substring(0, 8).toLong(16).toInt())
        m.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, h.substring(8, 12).toInt(16).toShort())
        m.set(ValueLayout.JAVA_SHORT_UNALIGNED, 6, h.substring(12, 16).toInt(16).toShort())
        for (k in 0 until 8) m.set(ValueLayout.JAVA_BYTE, 8L + k, h.substring(16 + k * 2, 18 + k * 2).toInt(16).toByte())
        return m
    }

    /** Calls method [index] of a COM interface pointer (the first argument is always `this`). */
    private fun com(obj: MemorySegment, index: Int, desc: FunctionDescriptor, vararg args: Any?): Any? {
        val vtbl = obj.reinterpret(8).get(A, 0).reinterpret(8L * (index + 1))
        val f = vtbl.getAtIndex(A, index.toLong())
        return linker.downcallHandle(f, desc).invokeWithArguments(obj, *args)
    }

    private fun release(obj: MemorySegment) { if (obj.address() != 0L) com(obj, 2, FunctionDescriptor.of(I, A)) }

    // ------------------------------------------------------------------ volume

    /** Runs [block] with the default speakers' IAudioEndpointVolume. */
    private fun <T> withEndpointVolume(block: (MemorySegment, Arena) -> T): T = Arena.ofConfined().use { arena ->
        val coInit = fn("ole32", "CoInitializeEx", FunctionDescriptor.of(I, A, I), arena)
        val coUninit = fn("ole32", "CoUninitialize", FunctionDescriptor.ofVoid(), arena)
        val create = fn("ole32", "CoCreateInstance", FunctionDescriptor.of(I, A, A, I, A, A), arena)
        val hr = coInit.invokeWithArguments(MemorySegment.NULL, 0) as Int // COINIT_MULTITHREADED
        try {
            val pp = arena.allocate(A)
            check(create.invokeWithArguments(guid(arena, "BCDE0395-E52F-467C-8E3D-C4579291692E"), MemorySegment.NULL, 23,
                guid(arena, "A95664D2-9614-4F35-A746-DE8DB63617E6"), pp) as Int == 0) { "MMDeviceEnumerator" }
            val enumerator = pp.get(A, 0)
            try {
                // IMMDeviceEnumerator::GetDefaultAudioEndpoint(eRender, eMultimedia, &device)
                check(com(enumerator, 4, FunctionDescriptor.of(I, A, I, I, A), 0, 1, pp) as Int == 0) { if (S.zh) "沒有音訊輸出裝置" else "No audio output device" }
                val device = pp.get(A, 0)
                try {
                    // IMMDevice::Activate(IID_IAudioEndpointVolume, CLSCTX_ALL, NULL, &vol)
                    check(com(device, 3, FunctionDescriptor.of(I, A, A, I, A, A), guid(arena, "5CDF2C82-841E-4546-9722-0CF74078229A"), 23, MemorySegment.NULL, pp) as Int == 0) { "IAudioEndpointVolume" }
                    val vol = pp.get(A, 0)
                    try { block(vol, arena) } finally { release(vol) }
                } finally { release(device) }
            } finally { release(enumerator) }
        } finally { if (hr == 0 || hr == 1) coUninit.invokeWithArguments() }
    }

    /** Master volume 0–100 and whether it is muted. */
    fun volume(): Pair<Int, Boolean> = withEndpointVolume { v, arena ->
        val f = arena.allocate(F); val b = arena.allocate(I)
        com(v, 9, FunctionDescriptor.of(I, A, A), f) // GetMasterVolumeLevelScalar
        com(v, 15, FunctionDescriptor.of(I, A, A), b) // GetMute
        Math.round(f.get(F, 0) * 100) to (b.get(I, 0) != 0)
    }

    fun setVolume(percent: Int? = null, mute: Boolean? = null) = withEndpointVolume { v, _ ->
        if (percent != null) com(v, 7, FunctionDescriptor.of(I, A, F, A), percent.coerceIn(0, 100) / 100f, MemorySegment.NULL) // SetMasterVolumeLevelScalar
        if (mute != null) com(v, 14, FunctionDescriptor.of(I, A, I, A), if (mute) 1 else 0, MemorySegment.NULL) // SetMute
    }

    // ------------------------------------------------------------------ battery

    class Power(val percent: Int?, val pluggedIn: Boolean?, val charging: Boolean, val hasBattery: Boolean, val saver: Boolean, val minutesLeft: Int?) {
        fun toMap() = linkedMapOf("percent" to percent, "pluggedIn" to pluggedIn, "charging" to charging, "hasBattery" to hasBattery, "batterySaver" to saver, "minutesLeft" to minutesLeft)
    }

    fun power(): Power = Arena.ofConfined().use { arena ->
        val m = arena.allocate(12)
        fn("kernel32", "GetSystemPowerStatus", FunctionDescriptor.of(I, A), arena).invokeWithArguments(m)
        val ac = m.get(ValueLayout.JAVA_BYTE, 0).toInt() and 0xFF
        val flag = m.get(ValueLayout.JAVA_BYTE, 1).toInt() and 0xFF
        val pct = m.get(ValueLayout.JAVA_BYTE, 2).toInt() and 0xFF
        val saver = m.get(ValueLayout.JAVA_BYTE, 3).toInt() and 0xFF
        val secs = m.get(ValueLayout.JAVA_INT_UNALIGNED, 4)
        Power(pct.takeIf { it != 255 }, when (ac) { 0 -> false; 1 -> true; else -> null }, flag and 8 != 0, flag and 128 == 0 && flag != 255, saver == 1, secs.takeIf { it >= 0 }?.let { it / 60 })
    }

    // ------------------------------------------------------------------ sounds

    val systemSounds = listOf("default", "asterisk", "exclamation", "hand", "question")

    fun beep(kind: String) = Arena.ofConfined().use { arena ->
        val type = when (kind) { "asterisk" -> 0x40; "exclamation" -> 0x30; "hand" -> 0x10; "question" -> 0x20; else -> 0 }
        fn("user32", "MessageBeep", FunctionDescriptor.of(I, I), arena).invokeWithArguments(type)
    }

    /** Plays a sound file: wav with PlaySoundW, anything else Windows can decode with MCI. Blocks when [wait]. */
    fun play(path: String, wait: Boolean) {
        val f = File(path)
        require(f.isFile) { (if (S.zh) "找不到聲音檔：" else "No such sound file: ") + path }
        val body = {
            Arena.ofConfined().use { arena ->
                if (f.extension.equals("wav", true)) {
                    // SND_FILENAME | SND_NODEFAULT, synchronous on this thread
                    fn("winmm", "PlaySoundW", FunctionDescriptor.of(I, A, A, I), arena).invokeWithArguments(arena.allocateFrom(f.absolutePath, Charsets.UTF_16LE), MemorySegment.NULL, 0x20000 or 0x2)
                } else {
                    val mci = fn("winmm", "mciSendStringW", FunctionDescriptor.of(I, A, A, I, A), arena)
                    fun send(cmd: String): Int = mci.invokeWithArguments(arena.allocateFrom(cmd + "\u0000", Charsets.UTF_16LE), MemorySegment.NULL, 0, MemorySegment.NULL) as Int
                    val alias = "fk" + System.nanoTime()
                    require(!f.absolutePath.contains('"')) { "Bad path" }
                    val err = send("open \"${f.absolutePath}\" type mpegvideo alias $alias")
                    require(err == 0) { (if (S.zh) "無法播放（MCI 錯誤 $err）：" else "Can't play (MCI error $err): ") + f.name }
                    try { send("play $alias wait") } finally { send("close $alias") }
                }
            }
        }
        if (wait) body() else Thread.ofVirtual().start { runCatching { body() } }
    }

    // ------------------------------------------------------------------ secrets (DPAPI)

    /** Encrypts for the current Windows user; the result is Base64 text safe to keep in the settings file. */
    fun protect(text: String): String = if (text.isEmpty()) "" else java.util.Base64.getEncoder().encodeToString(dpapi(text.toByteArray(Charsets.UTF_8), true))

    fun unprotect(b64: String): String = if (b64.isEmpty()) "" else runCatching { String(dpapi(java.util.Base64.getDecoder().decode(b64), false), Charsets.UTF_8) }.getOrDefault("")

    private fun dpapi(data: ByteArray, encrypt: Boolean): ByteArray = Arena.ofConfined().use { arena ->
        // DATA_BLOB { DWORD cbData; BYTE* pbData } — 16 bytes on x64
        val input = arena.allocate(16); val output = arena.allocate(16)
        val buf = arena.allocate(data.size.toLong().coerceAtLeast(1)); MemorySegment.copy(data, 0, buf, ValueLayout.JAVA_BYTE, 0, data.size)
        input.set(I, 0, data.size); input.set(A, 8, buf)
        val name = if (encrypt) "CryptProtectData" else "CryptUnprotectData"
        val ok = fn("crypt32", name, FunctionDescriptor.of(I, A, A, A, A, A, I, A), arena)
            .invokeWithArguments(input, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, 1 /* UI_FORBIDDEN */, output) as Int
        check(ok != 0) { "DPAPI" }
        val n = output.get(I, 0); val p = output.get(A, 8)
        val out = p.reinterpret(n.toLong()).toArray(ValueLayout.JAVA_BYTE)
        fn("kernel32", "LocalFree", FunctionDescriptor.of(A, A), arena).invokeWithArguments(p)
        out
    }

    // ------------------------------------------------------------------ notification banners

    private const val TOASTS_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Notifications\\Settings"
    private const val TOASTS_VALUE = "NOC_GLOBAL_SETTING_TOASTS_ENABLED"

    /** True when notification banners are off (勿擾). Windows has no public Focus / DND API; this is the per-user banner switch. */
    fun quiet(): Boolean = runCatching {
        val p = ProcessBuilder("reg.exe", "query", TOASTS_KEY, "/v", TOASTS_VALUE).redirectErrorStream(true).start()
        val out = String(p.inputStream.readAllBytes()); p.waitFor(5, TimeUnit.SECONDS)
        Regex("REG_DWORD\\s+0x([0-9a-fA-F]+)").find(out)?.groupValues?.get(1)?.toInt(16) == 0
    }.getOrDefault(false)

    fun setQuiet(on: Boolean) {
        val p = ProcessBuilder("reg.exe", "add", TOASTS_KEY, "/v", TOASTS_VALUE, "/t", "REG_DWORD", "/d", if (on) "0" else "1", "/f").redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor(5, TimeUnit.SECONDS)
        check(p.exitValue() == 0) { if (S.zh) "無法變更通知設定" else "Couldn't change the notification setting" }
    }

    // ------------------------------------------------------------------ screenshots

    /** Captures all screens (or the primary one) at full device resolution into a PNG. */
    fun screenshot(out: File, primaryOnly: Boolean, toClipboard: Boolean): File {
        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        val bounds = if (primaryOnly) ge.defaultScreenDevice.defaultConfiguration.bounds
            else ge.screenDevices.map { it.defaultConfiguration.bounds }.reduce { a, b -> a.union(b) }
        val shot = java.awt.Robot().createMultiResolutionScreenCapture(bounds)
        val img = shot.resolutionVariants.maxBy { it.getWidth(null) * it.getHeight(null) }
        val bi = img as? java.awt.image.BufferedImage ?: java.awt.image.BufferedImage(img.getWidth(null), img.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_RGB).also { it.graphics.drawImage(img, 0, 0, null) }
        out.parentFile?.mkdirs()
        javax.imageio.ImageIO.write(bi, "png", out)
        if (toClipboard) java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageSelection(bi), null)
        return out
    }

    private class ImageSelection(val img: java.awt.Image) : java.awt.datatransfer.Transferable {
        override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(f: java.awt.datatransfer.DataFlavor) = f == java.awt.datatransfer.DataFlavor.imageFlavor
        override fun getTransferData(f: java.awt.datatransfer.DataFlavor): Any = img
    }

    /** Puts files on the Windows clipboard (paste them in Explorer, mail, Phone Link …). */
    fun copyFiles(paths: List<String>) {
        val files = paths.map { File(it) }
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(f: java.awt.datatransfer.DataFlavor) = f == java.awt.datatransfer.DataFlavor.javaFileListFlavor
            override fun getTransferData(f: java.awt.datatransfer.DataFlavor): Any = files
        }, null)
    }

    /** Console output: UTF-8 when it is valid UTF-8, else the console code page (Big5 / CP950 on Traditional Chinese Windows). */
    fun decodeConsole(bytes: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse {
            String(bytes, runCatching { charset(System.getProperty("native.encoding") ?: "MS950") }.getOrDefault(charset("MS950")))
        }
    }
}

/**
 * The apps on this PC — Start menu shortcuts, App Paths, Store apps and command-line tools on PATH —
 * and how to start one, find its command-line interface (--help, -h, /?) and send it keys.
 */
object WinApps {
    /** kind: "desktop" (Start menu / App Paths), "store" (packaged app, target = AppUserModelID), "cli" (on PATH). */
    class Entry(val name: String, val target: String, val kind: String)

    @Volatile private var cache: List<Entry>? = null

    fun catalog(refresh: Boolean = false): List<Entry> {
        if (!refresh) cache?.let { return it }
        val out = ArrayList<Entry>()
        val script = """
            ${'$'}sh = New-Object -ComObject WScript.Shell
            foreach (${'$'}d in @([Environment]::GetFolderPath('CommonPrograms'), [Environment]::GetFolderPath('Programs'))) {
              Get-ChildItem -LiteralPath ${'$'}d -Recurse -Filter *.lnk -ErrorAction SilentlyContinue | ForEach-Object {
                try { ${'$'}t = ${'$'}sh.CreateShortcut(${'$'}_.FullName).TargetPath } catch { ${'$'}t = '' }
                if (${'$'}t -and ${'$'}t.ToLower().EndsWith('.exe')) { "D`t" + ${'$'}_.BaseName + "`t" + ${'$'}t }
              }
            }
            foreach (${'$'}k in @('HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths', 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths')) {
              Get-ChildItem ${'$'}k -ErrorAction SilentlyContinue | ForEach-Object {
                ${'$'}v = (Get-ItemProperty -LiteralPath ${'$'}_.PSPath -ErrorAction SilentlyContinue).'(default)'
                if (${'$'}v) { "D`t" + (${'$'}_.PSChildName -replace '\.exe${'$'}', '') + "`t" + ${'$'}v.Trim('"') }
              }
            }
            try { Get-StartApps | ForEach-Object { if (${'$'}_.AppID -match '!') { "S`t" + ${'$'}_.Name + "`t" + ${'$'}_.AppID } } } catch {}
        """.trimIndent()
        Fs.powershell(script, 60_000)?.lineSequence()?.forEach { line ->
            val parts = line.trim().split('\t')
            if (parts.size == 3 && parts[1].isNotBlank()) out += Entry(parts[1], parts[2], if (parts[0] == "S") "store" else "desktop")
        }
        val seen = HashSet<String>()
        for (dir in System.getenv("PATH").orEmpty().split(';').filter { it.isNotBlank() }) {
            File(dir).listFiles { f -> f.isFile && f.extension.lowercase() in setOf("exe", "cmd", "bat", "com") }?.forEach { f ->
                if (seen.add(f.nameWithoutExtension.lowercase())) out += Entry(f.nameWithoutExtension, f.path, "cli")
            }
        }
        val result = out.filter { File(it.target).exists() || it.kind == "store" }
            .distinctBy { it.kind + "|" + it.name.lowercase() + "|" + it.target.lowercase() }
            .sortedWith(compareBy<Entry>({ it.kind == "cli" }, { it.name.lowercase() }))
        cache = result
        return result
    }

    fun search(query: String, kind: String = "all"): List<Entry> {
        val q = query.trim().lowercase()
        return catalog().filter { (kind == "all" || it.kind == kind) && (q.isEmpty() || it.name.lowercase().contains(q) || File(it.target).name.lowercase().contains(q)) }
            .sortedBy { if (it.name.equals(q, true)) 0 else if (it.name.lowercase().startsWith(q)) 1 else 2 }
    }

    /** How an app reference is started: a file to run, or a Store app id. */
    class Target(val exe: String?, val appId: String?, val label: String)

    /** Resolves a path, a Store AppUserModelID, an app name from the catalog, or a command on PATH. */
    fun resolve(app: String): Target {
        val a = app.trim().trim('"')
        require(a.isNotEmpty()) { if (S.zh) "沒有指定應用程式" else "No app given" }
        if (File(a).isFile) return Target(if (a.endsWith(".lnk", true)) linkTarget(a) ?: a else a, null, File(a).name)
        if (a.contains('!') && !a.contains('\\')) return Target(null, a, a)
        onPath(a)?.let { return Target(it, null, a) }
        val hit = search(a).firstOrNull { it.name.equals(a, true) } ?: search(a).firstOrNull()
            ?: throw IllegalArgumentException((if (S.zh) "找不到應用程式：" else "No app named: ") + a)
        return if (hit.kind == "store") Target(null, hit.target, hit.name) else Target(hit.target, null, hit.name)
    }

    fun onPath(name: String): String? {
        if (name.contains('\\') || name.contains('/')) return null
        val exts = if (name.contains('.')) listOf("") else listOf(".exe", ".cmd", ".bat", ".com")
        for (dir in System.getenv("PATH").orEmpty().split(';').filter { it.isNotBlank() }) for (e in exts) {
            val f = File(dir, name + e); if (f.isFile) return f.path
        }
        return null
    }

    private fun linkTarget(lnk: String): String? = Fs.powershell("(New-Object -ComObject WScript.Shell).CreateShortcut(${'$'}env:FK_LNK).TargetPath", 15_000, mapOf("FK_LNK" to lnk))?.trim()?.takeIf { it.isNotEmpty() }

    /** "gui", "console" or null (not a PE file) — from the PE header's Subsystem field. */
    fun subsystem(exe: String): String? = runCatching {
        java.io.RandomAccessFile(exe, "r").use { f ->
            if (f.readUnsignedByte() != 'M'.code || f.readUnsignedByte() != 'Z'.code) return null
            f.seek(0x3C); val pe = Integer.reverseBytes(f.readInt()).toLong()
            f.seek(pe + 0x5C); val sub = java.lang.Short.reverseBytes(f.readShort()).toInt()
            when (sub) { 2 -> "gui"; 3 -> "console"; else -> null }
        }
    }.getOrNull()

    private fun isScript(exe: String) = exe.substringAfterLast('.').lowercase() in setOf("cmd", "bat")

    /** Splits a Windows command line into arguments ("a b" stays one). */
    fun splitArgs(s: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = false; var any = false
        for (c in s) when {
            c == '"' -> { q = !q; any = true }
            c.isWhitespace() && !q -> { if (any || cur.isNotEmpty()) out += cur.toString(); cur.clear(); any = false }
            else -> cur.append(c)
        }
        if (any || cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /**
     * A command line through cmd.exe with the program and every argument in quotes, so &, | and <
     * in file names stay text; " and % (which cmd would still interpret) are refused.
     */
    private fun viaCmd(exe: String, args: List<String>, start: Boolean): List<String> {
        (listOf(exe) + args).forEach { require(!it.contains('"') && !it.contains('%')) { (if (S.zh) "經由 cmd 執行時參數不能包含 \" 或 %：" else "Arguments run through cmd can't contain \" or %: ") + it } }
        val inner = (if (start) "start \"\" " else "") + "\"$exe\"" + args.joinToString("") { " \"$it\"" }
        return listOf("cmd.exe", "/d", "/c", "\"$inner\"")
    }

    /** The command for a file target: programs directly, batch files through cmd. */
    fun command(exe: String, args: List<String>): List<String> = if (isScript(exe)) viaCmd(exe, args, start = false) else listOf(exe) + args

    class Result(val exitCode: Int?, val output: String, val pid: Long?)

    /**
     * Starts an app. [wait] waits up to [timeoutSec] and returns its output (console apps);
     * [admin] asks for elevation (no output then); Store apps open through shell:AppsFolder.
     */
    fun launch(app: String, args: List<String>, workDir: String?, wait: Boolean, admin: Boolean, timeoutSec: Long): Result {
        val t = resolve(app)
        if (t.appId != null) {
            ProcessBuilder("explorer.exe", "shell:AppsFolder\\" + t.appId).start()
            return Result(null, "", null)
        }
        val exe = t.exe!!
        if (admin) {
            val ps = "${'$'}a = @(); if (${'$'}env:FK_ARGS) { ${'$'}a = ${'$'}env:FK_ARGS -split \"`n\" }; " +
                "${'$'}p = Start-Process -FilePath ${'$'}env:FK_EXE -ArgumentList ${'$'}a -Verb RunAs -PassThru" + (if (wait) " -Wait; ${'$'}p.ExitCode" else "; ${'$'}p.Id")
            val out = Fs.powershell(ps, if (wait) timeoutSec * 1000 else 60_000, mapOf("FK_EXE" to exe, "FK_ARGS" to args.joinToString("\n") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }))?.trim().orEmpty()
            return Result(if (wait) out.toIntOrNull() else null, "", if (!wait) out.toLongOrNull() else null)
        }
        // A console program started without waiting gets its own window; GUI programs show theirs anyway.
        val cmd = if (!wait && (isScript(exe) || subsystem(exe) == "console")) viaCmd(exe, args, start = true) else command(exe, args)
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        workDir?.takeIf { it.isNotBlank() && File(it).isDirectory }?.let { pb.directory(File(it)) }
        val p = pb.start()
        if (!wait) { p.outputStream.close(); Thread.ofVirtual().start { runCatching { p.inputStream.readAllBytes() } }; return Result(null, "", p.pid()) }
        p.outputStream.close()
        val buf = java.io.ByteArrayOutputStream()
        val reader = Thread.ofVirtual().start { runCatching { p.inputStream.transferTo(buf) } }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly()
            throw IllegalStateException((if (S.zh) "逾時（$timeoutSec 秒）已結束：" else "Timed out after $timeoutSec s: ") + t.label)
        }
        reader.join(2000)
        return Result(p.exitValue(), WinSys.decodeConsole(buf.toByteArray()), p.pid())
    }

    /** One option or subcommand found in an app's help text. */
    class CliOption(val name: String, val help: String)

    /** Runs the app's help (--help, -h, /?, help) and returns the first that prints something; GUI programs aren't started. */
    fun cliHelp(app: String): String {
        val t = resolve(app)
        val exe = t.exe ?: throw IllegalArgumentException(if (S.zh) "市集應用程式沒有命令列介面" else "Store apps have no command line")
        if (!isScript(exe) && subsystem(exe) == "gui") throw IllegalArgumentException(if (S.zh) "「${t.label}」是視窗程式，不提供命令列說明" else "${t.label} is a windowed program without command-line help")
        var best = ""
        for (h in listOf("--help", "-h", "/?", "help")) {
            val r = runCatching { launch(exe, listOf(h), null, wait = true, admin = false, timeoutSec = 8) }.getOrNull() ?: continue
            val text = r.output.trim()
            if (text.lines().size > best.lines().size) best = text
            if (text.lines().size >= 4) break
        }
        if (best.isEmpty()) throw IllegalStateException(if (S.zh) "「${t.label}」沒有輸出說明" else "${t.label} printed no help")
        return best
    }

    private val optionRx = Regex("^\\s{0,10}((?:--?|/)[A-Za-z0-9?][\\w?.:-]*(?:[ =]<?[\\w.|-]+>?|\\[[^\\]]*])?(?:\\s*[,|]\\s*(?:--?|/)[A-Za-z0-9?][\\w?.:-]*(?:[ =]<?[\\w.|-]+>?)?)*)(?:\\s{2,}|\\t|\\s*:\\s+)(\\S.*)?$")
    private val commandRx = Regex("^\\s{2,8}([a-z][a-z0-9-]{1,30})(?:,\\s*[a-z][a-z0-9-]*)*\\s{2,}(\\S.*)$")

    fun parseOptions(help: String): List<CliOption> {
        val out = ArrayList<CliOption>()
        for (line in help.lines()) {
            val m = optionRx.find(line) ?: commandRx.find(line) ?: continue
            out += CliOption(m.groupValues[1].trim(), m.groupValues.getOrNull(2)?.trim().orEmpty())
        }
        return out.distinctBy { it.name }
    }

    /** Process names (without .exe) of the running apps. */
    fun running(): List<String> = ProcessHandle.allProcesses().toList().mapNotNull { h -> h.info().command().orElse(null)?.let { File(it).nameWithoutExtension } }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /** Asks an app to close (like clicking ×); [force] ends it at once. Returns how many processes were asked. */
    fun quit(name: String, force: Boolean): Int {
        val n = File(name.trim()).nameWithoutExtension
        require(n.isNotBlank()) { if (S.zh) "沒有指定應用程式" else "No app given" }
        val procs = ProcessHandle.allProcesses().toList().filter { h -> h.info().command().orElse(null)?.let { File(it).nameWithoutExtension.equals(n, true) } == true }
        if (procs.isEmpty()) return 0
        val args = arrayListOf("taskkill.exe"); if (force) args += "/F"; procs.forEach { args += listOf("/PID", it.pid().toString()) }
        ProcessBuilder(args).redirectErrorStream(true).start().also { it.inputStream.readAllBytes(); it.waitFor(10, TimeUnit.SECONDS) }
        return procs.size
    }

    /** Brings a window forward (by window title or process name) and types keys in SendKeys syntax ({ENTER}, ^s, %{F4} …). */
    fun sendKeys(window: String, keys: String) {
        val script = "${'$'}w = New-Object -ComObject WScript.Shell; ${'$'}t = ${'$'}env:FK_WIN; " +
            "${'$'}p = Get-Process -Name (${'$'}t -replace '\\.exe${'$'}', '') -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.MainWindowHandle -ne 0 } | Select-Object -First 1; " +
            "if (${'$'}p) { ${'$'}ok = ${'$'}w.AppActivate(${'$'}p.Id) } else { ${'$'}ok = ${'$'}w.AppActivate(${'$'}t) }; " +
            "if (-not ${'$'}ok) { 'NOWINDOW'; exit }; Start-Sleep -Milliseconds 300; ${'$'}w.SendKeys(${'$'}env:FK_KEYS); 'OK'"
        val out = Fs.powershell(script, 30_000, mapOf("FK_WIN" to window, "FK_KEYS" to keys))?.trim()
        check(out?.endsWith("OK") == true) { (if (S.zh) "找不到視窗：" else "No window: ") + window }
    }
}
