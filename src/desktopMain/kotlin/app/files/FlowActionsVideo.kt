package app.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import app.files.FlowActions.CAT_VIDEO

/*
 * Video and sound conversion in shortcuts, done by FFmpeg: convert between formats (webm → mp4,
 * ts → mp4 without re-encoding when the streams fit), compress (by quality or to a target size),
 * scale, read video info, and run FFmpeg with arguments of your own. The MSI ships ffmpeg.exe next
 * to FilesKmp.exe; a development build, or a copy without it, uses one installed on the PC.
 */

/** Finds and runs ffmpeg.exe. */
internal object Ffmpeg {
    @Volatile private var found: File? = null

    /** The bundled ffmpeg, then FILESKMP_FFMPEG, PATH, and where winget, Scoop and Chocolatey put it. */
    fun locate(): File? {
        found?.takeIf { it.isFile }?.let { return it }
        val env = System.getenv()
        fun e(k: String) = env.entries.firstOrNull { it.key.equals(k, true) }?.value
        val cands = sequence {
            System.getProperty("jpackage.app-path")?.let { yield(File(File(it).parentFile, "ffmpeg\\ffmpeg.exe")) }
            e("FILESKMP_FFMPEG")?.let { yield(File(it)) }
            yield(File(System.getProperty("user.dir"), "build\\ffmpeg\\ffmpeg.exe"))
            e("PATH")?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.forEach { yield(File(it.trim('"'), "ffmpeg.exe")) }
            // A program started before winget changed PATH doesn't see the new folder: look where winget installs.
            e("LOCALAPPDATA")?.let { la ->
                yield(File(la, "Microsoft\\WinGet\\Links\\ffmpeg.exe"))
                File(la, "Microsoft\\WinGet\\Packages").listFiles { f -> f.name.contains("FFmpeg", true) }?.sortedByDescending { it.name }?.forEach { pkg ->
                    pkg.walkTopDown().maxDepth(3).filter { it.name.equals("ffmpeg.exe", true) }.forEach { yield(it) }
                }
            }
            e("USERPROFILE")?.let { yield(File(it, "scoop\\shims\\ffmpeg.exe")) }
            e("ProgramData")?.let { yield(File(it, "chocolatey\\bin\\ffmpeg.exe")) }
            yield(File("C:\\ffmpeg\\bin\\ffmpeg.exe"))
        }
        return cands.firstOrNull { it.isFile }?.also { found = it }
    }

    fun require(): File = locate() ?: throw FlowStopped(tr(
        "FFmpeg was not found. The installer includes it; for a build without it, install FFmpeg (winget install Gyan.FFmpeg) or set FILESKMP_FFMPEG to ffmpeg.exe.",
        "找不到 FFmpeg。安裝程式已內附；若是沒有內附的版本，請安裝 FFmpeg（winget install Gyan.FFmpeg），或把 FILESKMP_FFMPEG 設為 ffmpeg.exe 的路徑。"))

    private val encoderCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** The encoders [exe] has (libx264, libvpx-vp9 …); empty when it can't be asked. */
    suspend fun encoders(exe: File): Set<String> = if (!exe.isFile) emptySet() else encoderCache[exe.path] ?: withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder(exe.path, "-hide_banner", "-nostdin", "-encoders").redirectErrorStream(true).start()
            p.outputStream.close()
            val text = String(p.inputStream.readAllBytes(), Charsets.UTF_8); p.waitFor()
            // " V....D libx264   libx264 H.264 …"
            Regex("^ [VAS][.FXBSD]{5} (\\S+)", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toSet()
        }.getOrDefault(emptySet()).also { if (it.isNotEmpty()) encoderCache[exe.path] = it }
    }

    /** What FFmpeg says about a file's streams. */
    class Info(val duration: Double, val video: String?, val audio: String?, val width: Int, val height: Int, val fps: Double?, val subtitles: Boolean)

    private val durationRx = Regex("Duration: (\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)")
    private val videoRx = Regex("Stream #\\d+:\\d+.*?: Video: (\\w+)(.*)")
    private val audioRx = Regex("Stream #\\d+:\\d+.*?: Audio: (\\w+)")
    private val sizeRx = Regex(", (\\d{2,5})x(\\d{2,5})[ ,]")
    private val fpsRx = Regex(", ([\\d.]+) fps")

    suspend fun probe(exe: File, f: File): Info = withContext(Dispatchers.IO) {
        val p = ProcessBuilder(exe.path, "-hide_banner", "-nostdin", "-i", f.path).redirectErrorStream(true).start()
        p.outputStream.close()
        val text = String(p.inputStream.readAllBytes(), Charsets.UTF_8)
        p.waitFor()
        val d = durationRx.find(text)?.destructured?.let { (h, m, s) -> h.toInt() * 3600 + m.toInt() * 60 + s.toDouble() } ?: 0.0
        // A cover picture in a music file is a "video" stream too: skip it.
        val v = videoRx.findAll(text).firstOrNull { "attached pic" !in it.value }
        val size = v?.let { sizeRx.find(it.groupValues[2]) }
        Info(d, v?.groupValues?.get(1), audioRx.find(text)?.groupValues?.get(1),
            size?.groupValues?.get(1)?.toInt() ?: 0, size?.groupValues?.get(2)?.toInt() ?: 0,
            v?.let { fpsRx.find(it.groupValues[2])?.groupValues?.get(1)?.toDoubleOrNull() }, "Subtitle:" in text)
    }

    /**
     * Runs ffmpeg with [args] (after the global options), logging progress every 10% when the
     * [duration] is known. Stopping the shortcut kills ffmpeg. Throws with ffmpeg's last lines on failure.
     */
    suspend fun run(exe: File, args: List<String>, duration: Double, label: String, log: (String) -> Unit, workDir: File? = null) = withContext(Dispatchers.IO) {
        val cmd = listOf(exe.path, "-hide_banner", "-nostdin", "-nostats", "-progress", "pipe:1", "-loglevel", "error") + args
        val p = ProcessBuilder(cmd).directory(workDir).start()
        p.outputStream.close()
        val errors = ArrayDeque<String>()
        val errThread = Thread {
            p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { synchronized(errors) { errors.addLast(it); if (errors.size > 12) errors.removeFirst() } }
        }.apply { isDaemon = true; start() }
        var shown = 0
        val outThread = Thread {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (duration > 0 && line.startsWith("out_time_us=")) {
                    val pct = ((line.substringAfter('=').toLongOrNull() ?: 0L) / 1e6 / duration * 100).toInt().coerceIn(0, 100)
                    if (pct / 10 > shown / 10 && pct < 100) { shown = pct; log("   $label  ${pct / 10 * 10}%") }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            while (p.isAlive) delay(150)
        } finally {
            if (p.isAlive) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
        }
        outThread.join(2000); errThread.join(2000)
        if (p.exitValue() != 0) {
            // "[in#0 @ 000001e8…] moov atom not found" → "moov atom not found".
            val msg = synchronized(errors) { errors.map { it.replace(Regex("^\\[[^]]*@ [^]]*] "), "") }.filter { it.isNotBlank() }.distinct().takeLast(3).joinToString("\n") }
            throw FlowStopped(tr("FFmpeg failed: ", "FFmpeg 失敗：") + msg.ifEmpty { "exit code ${p.exitValue()}" })
        }
    }

    /** Arguments split like a command line: spaces separate, "double quotes" group. */
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

    fun commandText(args: List<String>) = "ffmpeg " + args.joinToString(" ") { if (it.any { c -> c.isWhitespace() } || it.isEmpty()) "\"$it\"" else it }

    fun time(sec: Double): String {
        val t = sec.toLong(); return "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)
    }
}

/** Encoder settings shared by the video actions. */
private object Enc {
    /** Constant-quality value per codec for high / medium / small. */
    fun crf(codec: String, quality: String): Int {
        val i = when (quality) { "high" -> 0; "small" -> 2; else -> 1 }
        return when (codec) {
            "h265" -> intArrayOf(22, 27, 31)[i]
            "vp9" -> intArrayOf(28, 34, 40)[i]
            "av1" -> intArrayOf(28, 35, 42)[i]
            else -> intArrayOf(19, 23, 28)[i]
        }
    }

    fun video(codec: String, quality: String, container: String): List<String> = when (codec) {
        "h265" -> listOf("-c:v", "libx265", "-preset", "medium", "-crf", crf(codec, quality).toString(), "-pix_fmt", "yuv420p") +
            (if (container == "mp4" || container == "mov") listOf("-tag:v", "hvc1") else emptyList())     // plays in Windows and Apple players
        "vp9" -> listOf("-c:v", "libvpx-vp9", "-crf", crf(codec, quality).toString(), "-b:v", "0", "-deadline", "good", "-cpu-used", "4", "-row-mt", "1")
        "av1" -> listOf("-c:v", "libsvtav1", "-preset", "8", "-crf", crf(codec, quality).toString())
        else -> listOf("-c:v", "libx264", "-preset", "medium", "-crf", crf(codec, quality).toString(), "-pix_fmt", "yuv420p")
    }

    private val library = mapOf("h264" to "libx264", "h265" to "libx265", "vp9" to "libvpx-vp9", "av1" to "libsvtav1")

    /**
     * [codec], or one this ffmpeg can encode instead: builds differ (the ARM64 one has no libvpx), and webm
     * takes AV1 as well as VP9. [have] empty = unknown (dry run without ffmpeg): asked for as is.
     */
    fun pick(codec: String, have: Set<String>, log: (String) -> Unit): String {
        if (have.isEmpty() || library[codec] in have) return codec
        val alt = when (codec) { "vp9" -> "av1"; "av1" -> "vp9"; "h265" -> "h264"; else -> null }?.takeIf { library[it] in have } ?: return codec
        log("   " + tr("This FFmpeg can't encode $codec; using $alt.", "這個 FFmpeg 不能編碼 $codec，改用 $alt。"))
        return alt
    }

    /** The codec a container gets when nothing else is asked for. */
    fun defaultCodec(container: String) = if (container == "webm") "vp9" else "h264"

    /** Streams each container takes as they are (so they can be copied instead of re-encoded). */
    fun videoFits(container: String, codec: String?) = codec == null || when (container) {
        "mp4", "mov" -> codec in setOf("h264", "hevc", "mpeg4", "av1")
        "webm" -> codec in setOf("vp8", "vp9", "av1")
        "mkv" -> true
        else -> false
    }
    fun audioFits(container: String, codec: String?) = codec == null || when (container) {
        "mp4", "mov" -> codec in setOf("aac", "mp3", "ac3", "eac3", "alac")
        "webm" -> codec in setOf("opus", "vorbis")
        "mkv" -> true
        else -> false
    }

    fun audio(container: String, src: String?): List<String> = when {
        src == null -> emptyList()
        audioFits(container, src) && container != "avi" -> listOf("-c:a", "copy")
        container == "webm" -> listOf("-c:a", "libopus", "-b:a", "128k")
        container == "avi" -> listOf("-c:a", "libmp3lame", "-b:a", "192k")
        else -> listOf("-c:a", "aac", "-b:a", "192k")
    }

    /** Which streams go into the output: the first video, all sound, and subtitles where the container keeps them. */
    fun maps(container: String, info: Ffmpeg.Info) = listOf("-map", "0:v:0?", "-map", "0:a?") +
        (if (container == "mkv" && info.subtitles) listOf("-map", "0:s?", "-c:s", "copy") else emptyList()) +
        listOf("-dn") + (if (container == "mp4" || container == "mov" || container == "m4a") listOf("-movflags", "+faststart") else emptyList())

    val audioFormats = mapOf(
        "mp3" to listOf("-c:a", "libmp3lame", "-q:a", "2"), "m4a" to listOf("-c:a", "aac", "-b:a", "192k"),
        "wav" to listOf("-c:a", "pcm_s16le"), "flac" to listOf("-c:a", "flac"), "opus" to listOf("-c:a", "libopus", "-b:a", "128k"),
    )
}

/** Runs [work] for each input file with its output path, logging failures and carrying on with the others. */
private suspend fun FlowRun.eachVideo(st: FlowStep, ext: (File) -> String, work: suspend (exe: File, src: File, out: File) -> Unit) {
    val exe = if (dryRun) Ffmpeg.locate() else Ffmpeg.require()
    val srcs = files().map { File(it) }.filter { it.isFile }
    val done = ArrayList<String>()
    var failed = 0
    srcs.forEachIndexed { i, src ->
        val dir = targetDir(rawArg(st, "to"), src.path, i)
        val stem = expand(rawArg(st, "name"), src.path, i).replace(Regex("[<>:\"/\\\\|?*]"), "_").ifBlank { src.nameWithoutExtension }
        val out = freeName(File(dir, stem + "." + ext(src)))
        try {
            work(exe ?: File("ffmpeg.exe"), src, out)
            done += out.path
        } catch (e: FlowStopped) {
            if (srcs.size == 1) { out.delete(); throw e }
            failed++; out.delete(); log("   ! ${src.name}: ${e.message}")
        }
    }
    if (failed > 0 && done.isEmpty()) throw FlowStopped(tr("No file could be converted.", "沒有任何檔案轉換成功。"))
    items = done
}

/** Logs the command in a dry run, or runs it. */
private suspend fun FlowRun.ffmpeg(exe: File, args: List<String>, duration: Double, src: File, out: File, workDir: File? = null) {
    if (dryRun) { log("   ${src.name} → ${out.path}"); log("     " + Ffmpeg.commandText(args)); return }
    log("   ${src.name} → ${out.name}")
    Ffmpeg.run(exe, args, duration, src.name, ::log, workDir)
    log("   ✓ ${out.name}  ${Fs.sizeText(src.length())} → ${Fs.sizeText(out.length())}")
}

private val nameArgHint = "（不含副檔名，可用標記）" to " (without extension; tokens allowed)"
private val convertFormats = listOf("mp4", "mkv", "mov", "webm", "avi", "gif", "mp3", "m4a", "wav", "flac", "opus")
private val heights = listOf("keep", "2160", "1440", "1080", "720", "480", "360")

internal fun videoActions(): List<FlowAction> = listOf(
    FlowAction("video.convert", "影片轉檔", "Convert video", CAT_VIDEO,
        "把影片或聲音轉成 mp4、mkv、mov、webm、avi、gif，或抽出聲音存成 mp3、m4a、wav、flac、opus。編碼「自動」時，串流合乎新格式就直接複製（ts → mp4 幾秒完成、畫質不變），否則重新編碼（webm → mp4 用 H.264 + AAC）。「解析度」可在同一次轉檔中縮小到指定高度（1080／720／480…，變數給的「720p」也可以）。",
        "Converts video or sound to mp4, mkv, mov, webm, avi or gif, or saves just the sound as mp3, m4a, wav, flac or opus. With codec \"auto\", streams that fit the new format are copied (ts → mp4 in seconds, no quality loss); others are re-encoded (webm → mp4 becomes H.264 + AAC). \"Resolution\" also scales the video down to that height in the same pass (1080/720/480…, or \"720p\" from a variable).",
        listOf(ArgSpec("format", "格式", "Format", "choice", "mp4", convertFormats),
            ArgSpec("codec", "影片編碼", "Video codec", "choice", "auto", listOf("auto", "copy", "h264", "h265", "vp9", "av1")),
            ArgSpec("quality", "畫質", "Quality", "choice", "medium", listOf("high", "medium", "small")),
            ArgSpec("height", "解析度（輸出高度，只縮小不放大）", "Resolution (output height; only ever scaled down)", "choice", "keep", heights),
            ArgSpec("name", "檔名" + nameArgHint.first, "File name" + nameArgHint.second, "text", "{item.stem}"),
            ArgSpec("to", "目的資料夾", "Destination", "folder", "{item.parent}")),
        changes = true, input = VType.Files, output = VType.Files) { st ->
        val fmt = arg(st, "format").lowercase(Locale.ROOT).trim().trim('.')
        val codec = arg(st, "codec").lowercase(Locale.ROOT).trim()
        val quality = arg(st, "quality").lowercase(Locale.ROOT).trim()
        // "720", "720p" or "keep"; a value from a variable may be any of these.
        val heightText = arg(st, "height").trim().lowercase(Locale.ROOT)
        val height = heightText.removeSuffix("p").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.coerceIn(16, 16384)
        if (height == null && heightText !in setOf("", "keep", "original"))
            throw FlowStopped(tr("Resolution \"$heightText\" isn't a height like 720 or 720p.", "解析度「$heightText」不是像 720 或 720p 這樣的高度。"))
        if (fmt !in convertFormats) throw FlowStopped(tr("Unknown format \"$fmt\"; use one of ${convertFormats.joinToString()}.", "不認得格式「$fmt」；可用 ${convertFormats.joinToString()}。"))
        // Never upscale; -2 keeps the width even, as the encoders need.
        val scale = height?.let { "scale=-2:'min($it,ih)'" }
        eachVideo(st, { fmt }) { exe, src, out ->
            val info = if (dryRun && !exe.isFile) Ffmpeg.Info(0.0, null, null, 0, 0, null, false) else Ffmpeg.probe(exe, src)
            val args = ArrayList<String>(listOf("-i", src.path))
            when {
                fmt in Enc.audioFormats -> { if (info.audio == null && info.duration > 0) throw FlowStopped(tr("No sound in this file.", "這個檔案沒有聲音。"))
                    args += listOf("-vn", "-map", "0:a:0") + Enc.audioFormats.getValue(fmt) }
                fmt == "gif" -> args += listOf("-an", "-vf", "fps=12," + (if (height != null) "scale=-2:'min($height,ih)'" else "scale='min(640,iw)':-2") +
                    ":flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse", "-loop", "0")
                else -> {
                    args += Enc.maps(fmt, info)
                    // Scaling needs a re-encode, so it overrides copying.
                    if (scale != null && codec == "copy") log("   " + tr("Changing the resolution needs re-encoding; codec \"copy\" is ignored.", "改變解析度必須重新編碼，忽略「直接複製」。"))
                    val copyAll = scale == null && (codec == "copy" || (codec == "auto" && Enc.videoFits(fmt, info.video) && Enc.audioFits(fmt, info.audio) && fmt != "avi"))
                    if (copyAll) args += listOf("-c:v", "copy", "-c:a", "copy")
                    else {
                        if (scale != null) args += listOf("-vf", scale)
                        args += Enc.video(Enc.pick(if (codec == "auto" || codec == "copy") Enc.defaultCodec(fmt) else codec, Ffmpeg.encoders(exe), ::log), quality, fmt)
                        args += Enc.audio(fmt, info.audio)
                    }
                }
            }
            args += out.path
            ffmpeg(exe, args, info.duration, src, out)
        }
    },
    FlowAction("video.compress", "壓縮影片", "Compress video", CAT_VIDEO,
        "把影片重新編碼成較小的 mp4：依畫質（高／中／小），或指定目標大小（MB，用兩階段編碼盡量貼近，例如 25 MB 以內方便傳送）。",
        "Re-encodes a video into a smaller mp4: by quality (high / medium / small), or to a target size in MB (two-pass, to land close to it — e.g. under 25 MB for sending).",
        listOf(ArgSpec("method", "方式", "Method", "choice", "quality", listOf("quality", "size")),
            ArgSpec("quality", "畫質", "Quality", "choice", "medium", listOf("high", "medium", "small")),
            ArgSpec("sizeMB", "目標大小（MB）", "Target size (MB)", "number", 25),
            ArgSpec("codec", "影片編碼", "Video codec", "choice", "h264", listOf("h264", "h265")),
            ArgSpec("name", "檔名" + nameArgHint.first, "File name" + nameArgHint.second, "text", "{item.stem}_compressed"),
            ArgSpec("to", "目的資料夾", "Destination", "folder", "{item.parent}")),
        changes = true, input = VType.Files, output = VType.Files) { st ->
        val codec = arg(st, "codec").lowercase(Locale.ROOT)
        val bySize = arg(st, "method") == "size"
        val sizeMb = arg(st, "sizeMB").trim().toDoubleOrNull()?.takeIf { it > 0 } ?: 25.0
        eachVideo(st, { "mp4" }) { exe, src, out ->
            val info = if (dryRun && !exe.isFile) Ffmpeg.Info(60.0, "h264", "aac", 0, 0, null, false) else Ffmpeg.probe(exe, src)
            if (info.video == null) throw FlowStopped(tr("No video in this file.", "這個檔案沒有影片。"))
            val maps = Enc.maps("mp4", info)
            if (!bySize) {
                val args = listOf("-i", src.path) + maps + Enc.video(Enc.pick(codec, Ffmpeg.encoders(exe), ::log), arg(st, "quality"), "mp4") +
                    (if (info.audio != null) listOf("-c:a", "aac", "-b:a", "128k") else emptyList()) + out.path
                ffmpeg(exe, args, info.duration, src, out)
            } else {
                if (info.duration <= 0) throw FlowStopped(tr("Can't tell this video's length, so no size can be aimed for.", "無法得知影片長度，不能指定目標大小。"))
                val audioK = if (info.audio == null) 0 else if (sizeMb * 8192 / info.duration < 800) 64 else 128
                // 1 MB = 8192 kbit; keep 3% for the container.
                val videoK = (sizeMb * 8192 * 0.97 / info.duration - audioK).toInt()
                if (videoK < 50) throw FlowStopped(tr("$sizeMb MB is too small for ${Ffmpeg.time(info.duration)} of video.", "${Ffmpeg.time(info.duration)} 的影片無法壓到 $sizeMb MB。"))
                val enc = if (codec == "h265") listOf("-c:v", "libx265", "-preset", "medium", "-tag:v", "hvc1") else listOf("-c:v", "libx264", "-preset", "medium")
                val rate = listOf("-b:v", "${videoK}k", "-pix_fmt", "yuv420p")
                // The pass log lives in the cache folder, named relative to it (x265's options can't hold "C:").
                val work = AppDirs.cache.apply { mkdirs() }
                val passLog = "ffmpeg2pass-${System.nanoTime()}"
                val passArgs = if (codec == "h265") { n: Int -> listOf("-x265-params", "pass=$n:stats=$passLog.log") }
                    else { n: Int -> listOf("-pass", "$n", "-passlogfile", passLog) }
                val pass1 = listOf("-y", "-i", src.path, "-map", "0:v:0") + enc + rate + passArgs(1) + listOf("-an", "-f", "null", "NUL")
                val pass2 = listOf("-i", src.path) + maps + enc + rate + passArgs(2) +
                    (if (info.audio != null) listOf("-c:a", "aac", "-b:a", "${audioK}k") else emptyList()) + out.path
                try {
                    if (dryRun) { log("     " + Ffmpeg.commandText(pass1)) } else { log("   ${src.name}: " + tr("pass 1 of 2", "第 1／2 階段")); Ffmpeg.run(exe, pass1, info.duration, src.name, ::log, work) }
                    ffmpeg(exe, pass2, info.duration, src, out, work)
                } finally {
                    work.listFiles { f -> f.name.startsWith(passLog) }?.forEach { it.delete() }
                }
            }
            if (!dryRun && out.length() >= src.length()) log("   " + tr("Note: the result isn't smaller than the original.", "注意：結果沒有比原檔小。"))
        }
    },
    FlowAction("video.scale", "縮放影片", "Scale video", CAT_VIDEO,
        "改變影片解析度：依百分比、指定寬度或高度（另一邊等比例），或放進指定的寬×高範圍內。聲音盡量直接複製。",
        "Changes a video's resolution: by percent, to a width or a height (the other side keeps the proportions), or to fit inside a width × height box. Sound is copied when possible.",
        listOf(ArgSpec("mode", "方式", "Mode", "choice", "percent", listOf("percent", "width", "height", "fit")),
            ArgSpec("percent", "百分比", "Percent", "number", 50),
            ArgSpec("width", "寬度（px）", "Width (px)", "number", 1280),
            ArgSpec("height", "高度（px）", "Height (px)", "number", 720),
            ArgSpec("quality", "畫質", "Quality", "choice", "high", listOf("high", "medium", "small")),
            ArgSpec("name", "檔名" + nameArgHint.first, "File name" + nameArgHint.second, "text", "{item.stem}_scaled"),
            ArgSpec("to", "目的資料夾", "Destination", "folder", "{item.parent}")),
        changes = true, input = VType.Files, output = VType.Files) { st ->
        val w = int(st, "width", 1280).coerceIn(16, 16384); val h = int(st, "height", 720).coerceIn(16, 16384)
        val pct = (arg(st, "percent").trim().toDoubleOrNull() ?: 50.0).coerceIn(1.0, 1000.0)
        val filter = when (arg(st, "mode")) {
            "width" -> "scale=$w:-2"
            "height" -> "scale=-2:$h"
            "fit" -> "scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2"
            else -> "scale=trunc(iw*${numText(pct)}/200)*2:trunc(ih*${numText(pct)}/200)*2"
        }
        fun container(src: File) = src.extension.lowercase(Locale.ROOT).takeIf { it in setOf("mp4", "mkv", "mov", "webm") } ?: "mp4"
        eachVideo(st, ::container) { exe, src, out ->
            val info = if (dryRun && !exe.isFile) Ffmpeg.Info(0.0, "h264", "aac", 0, 0, null, false) else Ffmpeg.probe(exe, src)
            if (info.video == null) throw FlowStopped(tr("No video in this file.", "這個檔案沒有影片。"))
            val c = container(src)
            val args = listOf("-i", src.path) + Enc.maps(c, info) + listOf("-vf", filter) +
                Enc.video(Enc.pick(Enc.defaultCodec(c), Ffmpeg.encoders(exe), ::log), arg(st, "quality"), c) + Enc.audio(c, info.audio) + out.path
            ffmpeg(exe, args, info.duration, src, out)
        }
    },
    FlowAction("video.info", "影片資訊", "Video info", CAT_VIDEO,
        "每個影片或聲音檔的長度、解析度、影格率與編碼（以 Tab 分隔：名稱、長度、寬×高、fps、影片編碼、聲音編碼）。",
        "Length, resolution, frame rate and codecs of each video or sound file (tab separated: name, length, width×height, fps, video codec, audio codec).",
        input = VType.Files, output = VType.Text) {
        val exe = Ffmpeg.require()
        items = files().map { File(it) }.filter { it.isFile }.map { f ->
            val i = Ffmpeg.probe(exe, f)
            listOf(f.name, Ffmpeg.time(i.duration), if (i.width > 0) "${i.width}x${i.height}" else "", i.fps?.let { numText(it) } ?: "", i.video ?: "", i.audio ?: "").joinToString("\t")
        }
    },
    FlowAction("ffmpeg.run", "FFmpeg 自訂轉換", "FFmpeg (custom)", CAT_VIDEO,
        "用自己的 FFmpeg 參數處理每個檔案：{in} 是來源檔，{out} 是輸出檔（依「輸出檔名」產生，不會覆寫既有檔案）；也可用 {item.stem} 等標記。例：-i {in} -ss 00:00:10 -t 30 -c copy {out}",
        "Runs FFmpeg with your own arguments for each file: {in} is the source and {out} the output (from \"Output name\"; existing files are never overwritten); tokens like {item.stem} work too. Example: -i {in} -ss 00:00:10 -t 30 -c copy {out}",
        listOf(ArgSpec("args", "FFmpeg 參數", "FFmpeg arguments", "multiline", "-i {in} -c:v libx264 -crf 23 -preset medium -c:a aac -b:a 160k {out}"),
            ArgSpec("output", "輸出檔名（含副檔名）", "Output name (with extension)", "text", "{item.stem}_out.mp4"),
            ArgSpec("to", "目的資料夾", "Destination", "folder", "{item.parent}")),
        changes = true, input = VType.Files, output = VType.Files) { st ->
        val template = Ffmpeg.splitArgs(rawArg(st, "args").replace('\n', ' '))
        if ("{in}" !in template.joinToString(" ")) throw FlowStopped(tr("The arguments need {in} (the source file).", "參數需要包含 {in}（來源檔）。"))
        val exe = if (dryRun) Ffmpeg.locate() ?: File("ffmpeg.exe") else Ffmpeg.require()
        val srcs = files().map { File(it) }.filter { it.isFile }
        items = srcs.mapIndexed { i, src ->
            val dir = targetDir(rawArg(st, "to"), src.path, i)
            val name = expand(rawArg(st, "output"), src.path, i).replace(Regex("[<>:\"/\\\\|?*]"), "_")
            val out = freeName(File(dir, name.ifBlank { src.nameWithoutExtension + "_out." + src.extension }))
            val usesOut = template.any { "{out}" in it }
            // Tokens are filled in after splitting, so paths with spaces stay one argument; -n: never overwrite.
            val args = listOf("-n") + template.map { expand(it.replace("{in}", src.path).replace("{out}", out.path), src.path, i) } +
                (if (usesOut) emptyList() else listOf(out.path))
            val duration = if (dryRun || !exe.isFile) 0.0 else runCatching { Ffmpeg.probe(exe, src).duration }.getOrDefault(0.0)
            ffmpeg(exe, args, duration, src, out)
            out.path
        }
    },
)
