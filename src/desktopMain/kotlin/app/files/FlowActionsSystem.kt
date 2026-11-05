package app.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import app.files.FlowActions.CAT_APPS
import app.files.FlowActions.CAT_MEDIA
import app.files.FlowActions.CAT_SHARE
import app.files.FlowActions.CAT_SYSTEM
import app.files.FlowActions.CAT_WEB

/*
 * Shortcut actions that reach outside the app: HTTP, other programs (find, start, read their
 * command-line help, quit, type into), sound and speech, Windows settings (volume, screenshots,
 * battery, notification banners), mail and text messages.
 */

private val http: HttpClient by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build() }

/** A multipart/form-data body: text fields, then files. */
private fun multipart(fields: List<Pair<String, String>>, fileField: String, files: List<File>, boundary: String): HttpRequest.BodyPublisher {
    val parts = ArrayList<ByteArray>()
    fun text(s: String) { parts += s.toByteArray(Charsets.UTF_8) }
    fields.forEach { (k, v) -> text("--$boundary\r\nContent-Disposition: form-data; name=\"${k.replace("\"", "")}\"\r\n\r\n$v\r\n") }
    files.forEach { f ->
        val type = runCatching { java.nio.file.Files.probeContentType(f.toPath()) }.getOrNull() ?: "application/octet-stream"
        text("--$boundary\r\nContent-Disposition: form-data; name=\"${fileField.replace("\"", "")}\"; filename=\"${f.name.replace("\"", "")}\"\r\nContent-Type: $type\r\n\r\n")
        parts += f.readBytes(); text("\r\n")
    }
    text("--$boundary--\r\n")
    return HttpRequest.BodyPublishers.ofByteArrays(parts)
}

internal fun systemActions(): List<FlowAction> = listOf(
    // ================================================================ web
    FlowAction("http.request", "HTTP 請求", "HTTP request", CAT_WEB,
        "呼叫網址（GET / POST / PUT / PATCH / DELETE），可帶標頭、JSON、表單、檔案；結果可以是內容、JSON、狀態碼、標頭或下載的檔案。試執行時只送 GET。",
        "Calls a URL (GET / POST / PUT / PATCH / DELETE) with headers, JSON, a form or files; the result is the body, JSON, status, headers or a downloaded file. A dry run only sends GET.",
        listOf(ArgSpec("method", "方法", "Method", "choice", "GET", listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")),
            ArgSpec("url", "網址", "URL", "text", "https://api.github.com/repos/creamgod45/FilesKmp"),
            ArgSpec("headers", "標頭（名稱: 值，每行一個）", "Headers (Name: value per line)", "multiline", ""),
            ArgSpec("bodyType", "內容類型", "Body", "choice", "none", listOf("none", "json", "text", "form", "file", "multipart")),
            ArgSpec("body", "內容（JSON / 文字 / 表單欄位 key=value）", "Body (JSON / text / form fields key=value)", "multiline", ""),
            ArgSpec("fileField", "檔案欄位名稱（multipart）", "File field (multipart)", "text", "file"),
            ArgSpec("output", "結果", "Result", "choice", "body", listOf("body", "json", "status", "headers", "file", "all")),
            ArgSpec("saveTo", "下載到（結果為 file 時）", "Save to (result = file)", "path", "{downloads}"),
            ArgSpec("timeout", "逾時（秒）", "Timeout (s)", "number", 30), ArgSpec("failOnError", "4xx / 5xx 時停止", "Stop on 4xx / 5xx", "bool", true)),
        input = VType.Anything,
        outputFor = { st -> when (st.args["output"] ?: "body") { "json", "headers", "all" -> VType.Json; "status" -> VType.Number; "file" -> VType.Files; else -> VType.Text } }) { st ->
        val method = arg(st, "method").uppercase(); val url = arg(st, "url").trim(); val out = arg(st, "output")
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { (if (S.zh) "網址必須是 http:// 或 https://：" else "The URL must be http:// or https://: ") + url }
        if (dryRun && method !in setOf("GET", "HEAD")) { log("   $method $url"); return@FlowAction }
        val b = HttpRequest.newBuilder(URI(url.replace(" ", "%20"))).timeout(Duration.ofSeconds(int(st, "timeout", 30).toLong().coerceIn(1, 3600)))
        keyValues(arg(st, "headers")).forEach { (k, v) -> b.header(k, v) }
        val bodyText = arg(st, "body")
        // HEAD never carries a body (RFC 9110 §9.3.2: content in a HEAD request has no defined meaning).
        val publisher: HttpRequest.BodyPublisher = if (method == "HEAD") HttpRequest.BodyPublishers.noBody() else when (arg(st, "bodyType")) {
            "json" -> { b.header("Content-Type", "application/json; charset=utf-8"); HttpRequest.BodyPublishers.ofString(bodyText.ifBlank { Json.write(items) }) }
            "text" -> { b.header("Content-Type", "text/plain; charset=utf-8"); HttpRequest.BodyPublishers.ofString(bodyText.ifEmpty { items.joinToString("\n") }) }
            "form" -> { b.header("Content-Type", "application/x-www-form-urlencoded"); HttpRequest.BodyPublishers.ofString(keyValues(bodyText).joinToString("&") { (k, v) -> java.net.URLEncoder.encode(k, Charsets.UTF_8) + "=" + java.net.URLEncoder.encode(v, Charsets.UTF_8) }) }
            "file" -> { val f = files().firstOrNull { File(it).isFile } ?: throw FlowStopped(if (S.zh) "沒有可上傳的檔案" else "No file to upload")
                b.header("Content-Type", runCatching { java.nio.file.Files.probeContentType(File(f).toPath()) }.getOrNull() ?: "application/octet-stream"); HttpRequest.BodyPublishers.ofFile(File(f).toPath()) }
            "multipart" -> { val bd = "----FilesKmp" + System.nanoTime(); b.header("Content-Type", "multipart/form-data; boundary=$bd")
                multipart(keyValues(bodyText), arg(st, "fileField").ifBlank { "file" }, files().map { File(it) }.filter { it.isFile }, bd) }
            else -> HttpRequest.BodyPublishers.noBody()
        }
        b.method(method, publisher)
        log("   $method $url")
        val resp = withContext(Dispatchers.IO) { http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()) }
        val code = resp.statusCode()
        log("   HTTP $code · ${Fs.sizeText(resp.body().size.toLong())}")
        if (bool(st, "failOnError") && code >= 400) throw FlowStopped("HTTP $code: " + String(resp.body(), Charsets.UTF_8).take(300))
        val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(resp.headers().firstValue("content-type").orElse(""))?.groupValues?.get(1)
        fun text() = runCatching { String(resp.body(), charset(charset ?: "UTF-8")) }.getOrElse { String(resp.body(), Charsets.UTF_8) }
        fun headers() = LinkedHashMap<String, Any?>().apply { resp.headers().map().forEach { (k, v) -> if (!k.startsWith(":")) put(k, if (v.size == 1) v[0] else v) } }
        items = when (out) {
            "status" -> listOf(code.toString())
            "headers" -> listOf(Json.write(headers()))
            "json" -> listOf(Json.write(JsonPath.parse(text())))
            "all" -> listOf(Json.write(linkedMapOf("status" to code, "headers" to headers(), "body" to (runCatching { Json.parse(text()) }.getOrNull() ?: text()))))
            "file" -> {
                var dest = File(arg(st, "saveTo").ifBlank { expand("{downloads}") })
                if (dest.isDirectory || arg(st, "saveTo").endsWith("\\")) {
                    val cd = resp.headers().firstValue("content-disposition").orElse("")
                    val name = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
                        ?: Regex("filename=\"?([^\";]+)").find(cd)?.groupValues?.get(1)
                        ?: URI(url).path.substringAfterLast('/').ifEmpty { "download" }
                    dest = File(dest, name.replace(Regex("[<>:\"/\\\\|?*]"), "_"))
                }
                dest = freeName(dest)
                if (dryRun) log("   → ${dest.path}") else { dest.parentFile?.mkdirs(); dest.writeBytes(resp.body()) }
                listOf(dest.path)
            }
            else -> listOf(text())
        }
    },

    // ================================================================ apps
    FlowAction("app.find", "尋找應用程式", "Find apps", CAT_APPS, "搜尋這台電腦的應用程式：開始功能表、App Paths、市集應用程式、PATH 中的命令列工具。", "Searches the apps on this PC: Start menu, App Paths, Store apps and command-line tools on PATH.",
        listOf(ArgSpec("query", "名稱包含", "Name contains", "text", ""), ArgSpec("kind", "種類", "Kind", "choice", "all", listOf("all", "desktop", "store", "cli")),
            ArgSpec("output", "結果", "Result", "choice", "name", listOf("name", "target", "json")), ArgSpec("refresh", "重新掃描", "Scan again", "bool", false)),
        input = VType.None, outputFor = { st -> if ((st.args["output"] ?: "name") == "json") VType.Json else VType.Text }) { st ->
        if (bool(st, "refresh")) withContext(Dispatchers.IO) { WinApps.catalog(refresh = true) }
        val found = withContext(Dispatchers.IO) { WinApps.search(arg(st, "query"), arg(st, "kind")) }
        items = when (arg(st, "output")) {
            "target" -> found.map { it.target }
            "json" -> listOf(Json.write(found.map { linkedMapOf("name" to it.name, "target" to it.target, "kind" to it.kind) }))
            else -> found.map { it.name }
        }
    },
    FlowAction("app.launch", "啟動應用程式", "Start app", CAT_APPS,
        "啟動程式（名稱、路徑或市集應用程式）並傳參數；可等它結束並取得輸出（命令列工具）、結束代碼或處理程序編號。參數中的 {input} 代表所有值，勾選「逐項」時每個值各執行一次。",
        "Starts a program (name, path or Store app) with arguments; can wait and capture its output (CLI tools), exit code or process id. {input} in the arguments stands for all values; Once per value runs it for each.",
        listOf(ArgSpec("app", "應用程式", "App", "app", "notepad"), ArgSpec("args", "參數", "Arguments", "text", "{input}"),
            ArgSpec("perItem", "每個值各執行一次", "Once per value", "bool", false), ArgSpec("wait", "等待結束", "Wait until it ends", "bool", false),
            ArgSpec("output", "結果", "Result", "choice", "none", listOf("none", "output", "exitCode", "pid")),
            ArgSpec("timeout", "等待上限（秒）", "Time limit (s)", "number", 120), ArgSpec("workDir", "工作資料夾", "Working folder", "folder", ""),
            ArgSpec("admin", "以系統管理員執行", "Run as administrator", "bool", false)),
        changes = true, input = VType.Anything,
        outputFor = { st -> when (st.args["output"] ?: "none") { "output" -> VType.Text; "exitCode", "pid" -> VType.Number; else -> VType.Same } }) { st ->
        val app = arg(st, "app"); val template = WinApps.splitArgs(rawArg(st, "args")); val out = arg(st, "output")
        val wait = bool(st, "wait") || out == "output" || out == "exitCode"
        fun argsFor(item: String?, i: Int): List<String> = template.flatMap { a -> if (a == "{input}") items else listOf(expand(a, item, i)) }
        val runs = if (bool(st, "perItem")) items.mapIndexed { i, v -> argsFor(v, i) } else listOf(argsFor(items.firstOrNull(), 0))
        if (dryRun) { runs.forEach { log("   ▷ $app " + it.joinToString(" ") { a -> if (a.contains(' ')) "\"$a\"" else a }) }; return@FlowAction }
        val results = withContext(Dispatchers.IO) {
            runs.map { a -> WinApps.launch(app, a, arg(st, "workDir"), wait, bool(st, "admin"), int(st, "timeout", 120).toLong().coerceIn(1, 24 * 3600)) }
        }
        results.forEach { r -> r.exitCode?.let { log("   exit $it") } }
        items = when (out) {
            "output" -> results.flatMap { it.output.lines().map { l -> l.trimEnd() } }.dropLastWhile { it.isEmpty() }
            "exitCode" -> results.map { it.exitCode?.toString() ?: "" }
            "pid" -> results.map { it.pid?.toString() ?: "" }
            else -> items
        }
    },
    FlowAction("app.cli", "取得命令列介面", "Get command-line options", CAT_APPS,
        "執行程式的說明（--help、-h、/?、help），列出它的選項與子命令，或取得完整說明文字。不會啟動視窗程式。",
        "Runs the program's help (--help, -h, /?, help) and lists its options and subcommands, or returns the whole help text. Windowed programs aren't started.",
        listOf(ArgSpec("app", "應用程式", "App", "app", "winget"), ArgSpec("output", "結果", "Result", "choice", "options", listOf("options", "text"))),
        input = VType.None, output = VType.Text) { st ->
        val help = withContext(Dispatchers.IO) { runCatching { WinApps.cliHelp(arg(st, "app")) }.getOrElse { throw FlowStopped(it.message ?: "help") } }
        items = if (arg(st, "output") == "text") listOf(help) else WinApps.parseOptions(help).map { if (it.help.isEmpty()) it.name else it.name + "\t" + it.help }
    },
    FlowAction("app.running", "應用程式是否在執行", "Is app running", CAT_APPS, "指定名稱時得到是/否；不指定時列出所有執行中的程式。", "With a name: true/false. Without: every running program.",
        listOf(ArgSpec("name", "程式名稱（例如 notepad）", "Program (e.g. notepad)", "text", "")), input = VType.None,
        outputFor = { st -> if ((st.args["name"] ?: "").toString().isBlank()) VType.Text else VType.Bool }) { st ->
        val n = File(arg(st, "name").trim()).nameWithoutExtension
        val all = withContext(Dispatchers.IO) { WinApps.running() }
        items = if (n.isBlank()) all else listOf(all.any { it.equals(n, true) }.toString())
    },
    FlowAction("app.quit", "結束應用程式", "Quit app", CAT_APPS, "請程式關閉（像按 ×）；勾選強制則立即結束。", "Asks a program to close (like clicking ×); Force ends it at once.",
        listOf(ArgSpec("name", "程式名稱（例如 notepad）", "Program (e.g. notepad)", "text", ""), ArgSpec("force", "強制結束", "Force", "bool", false)),
        changes = true, input = VType.Anything, output = VType.Same) { st ->
        val n = arg(st, "name")
        if (dryRun) { log("   ✕ $n"); return@FlowAction }
        val c = withContext(Dispatchers.IO) { WinApps.quit(n, bool(st, "force")) }
        log("   " + (if (S.zh) "已通知 $c 個處理程序" else "$c process(es) asked to quit"))
    },
    FlowAction("app.sendKeys", "對應用程式按鍵", "Type into app", CAT_APPS,
        "把視窗（標題或程式名稱）帶到前面並輸入按鍵：一般文字、{ENTER}、{TAB}、^s（Ctrl+S）、%{F4}（Alt+F4）、+（Shift）。",
        "Brings a window (title or program name) forward and types keys: text, {ENTER}, {TAB}, ^s (Ctrl+S), %{F4} (Alt+F4), + (Shift).",
        listOf(ArgSpec("window", "視窗標題或程式名稱", "Window title or program", "text", "notepad"), ArgSpec("keys", "按鍵", "Keys", "text", "{first}{ENTER}")),
        changes = true, input = VType.Anything, output = VType.Same) { st ->
        val w = arg(st, "window"); val k = arg(st, "keys")
        if (dryRun) log("   ⌨ $w ← $k") else withContext(Dispatchers.IO) { WinApps.sendKeys(w, k) }
    },

    // ================================================================ sound & speech
    FlowAction("sound.play", "播放聲音", "Play sound", CAT_MEDIA, "播放系統音效或聲音檔（wav、mp3、m4a、wma…）。", "Plays a system sound or a sound file (wav, mp3, m4a, wma…).",
        listOf(ArgSpec("sound", "聲音", "Sound", "choice", "file", listOf("file") + WinSys.systemSounds), ArgSpec("file", "聲音檔", "Sound file", "path", "{first}"),
            ArgSpec("wait", "播完再繼續", "Wait until it ends", "bool", true)), input = VType.Anything, output = VType.Same) { st ->
        val kind = arg(st, "sound")
        if (kind != "file") { if (dryRun) log("   🔔 $kind") else WinSys.beep(kind); return@FlowAction }
        val f = arg(st, "file")
        if (dryRun) log("   ♪ $f") else withContext(Dispatchers.IO) { WinSys.play(f, bool(st, "wait")) }
    },
    FlowAction("speak", "朗讀文字", "Speak", CAT_MEDIA, "以 Windows 語音朗讀；可選語音（名稱或 zh-TW 等語言）、速度、音量，或存成 wav 檔。", "Reads text aloud with a Windows voice (name or language like en-US), speed and volume, or saves a wav file.",
        listOf(ArgSpec("text", "文字", "Text", "multiline", "{input}"), ArgSpec("voice", "語音（名稱或語言，空白 = 預設）", "Voice (name or language; empty = default)", "text", ""),
            ArgSpec("rate", "速度（-10 到 10）", "Speed (-10 to 10)", "number", 0), ArgSpec("volume", "音量（0–100）", "Volume (0–100)", "number", 100),
            ArgSpec("wait", "唸完再繼續", "Wait until done", "bool", true), ArgSpec("saveTo", "存成 wav（空白 = 直接朗讀）", "Save as wav (empty = speak)", "path", "")),
        input = VType.Text, outputFor = { st -> if ((st.args["saveTo"] ?: "").toString().isBlank()) VType.Same else VType.Files }) { st ->
        val text = arg(st, "text"); val save = arg(st, "saveTo").trim()
        if (dryRun) { log("   🔊 " + text.take(100)); if (save.isNotEmpty()) items = listOf(save); return@FlowAction }
        val script = "Add-Type -AssemblyName System.Speech; ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
            "if (${'$'}env:FK_VOICE) { ${'$'}v = ${'$'}s.GetInstalledVoices() | ForEach-Object { ${'$'}_.VoiceInfo } | Where-Object { ${'$'}_.Name -like ('*' + ${'$'}env:FK_VOICE + '*') -or ${'$'}_.Culture.Name -eq ${'$'}env:FK_VOICE } | Select-Object -First 1; if (${'$'}v) { ${'$'}s.SelectVoice(${'$'}v.Name) } }; " +
            "${'$'}s.Rate = [int]${'$'}env:FK_RATE; ${'$'}s.Volume = [int]${'$'}env:FK_VOL; if (${'$'}env:FK_OUT) { ${'$'}s.SetOutputToWaveFile(${'$'}env:FK_OUT) }; ${'$'}s.Speak(${'$'}env:FK_TEXT); ${'$'}s.Dispose(); 'OK'"
        val env = mapOf("FK_TEXT" to text, "FK_VOICE" to arg(st, "voice").trim(), "FK_RATE" to int(st, "rate", 0).coerceIn(-10, 10).toString(),
            "FK_VOL" to int(st, "volume", 100).coerceIn(0, 100).toString(), "FK_OUT" to save)
        if (save.isNotEmpty()) File(save).parentFile?.mkdirs()
        if (bool(st, "wait") || save.isNotEmpty()) withContext(Dispatchers.IO) { Fs.powershell(script, 30 * 60_000L, env) }
        else Thread.ofVirtual().start { Fs.powershell(script, 30 * 60_000L, env) }
        if (save.isNotEmpty()) items = listOf(save)
    },
    FlowAction("speak.voices", "列出語音", "List voices", CAT_MEDIA, "這台電腦安裝的朗讀語音（名稱與語言）。", "The speech voices installed on this PC (name and language).", input = VType.None, output = VType.Text) {
        items = withContext(Dispatchers.IO) {
            Fs.powershell("Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices() | ForEach-Object { ${'$'}_.VoiceInfo.Name + \"`t\" + ${'$'}_.VoiceInfo.Culture.Name }", 30_000)
        }.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
    },

    // ================================================================ Windows
    FlowAction("volume", "音量", "Volume", CAT_SYSTEM, "讀取或設定喇叭音量、調大調小、靜音；結果是調整後的音量（0–100）。", "Gets or sets the speaker volume, turns it up/down, mutes; the result is the volume afterwards (0–100).",
        listOf(ArgSpec("op", "動作", "Do", "choice", "get", listOf("get", "set", "up", "down", "mute", "unmute", "toggleMute")),
            ArgSpec("value", "音量 / 增減量", "Level / step", "number", 50)), input = VType.Anything, output = VType.Number) { st ->
        val op = arg(st, "op"); val v = int(st, "value", 50)
        val now = withContext(Dispatchers.IO) { WinSys.volume() }
        if (op != "get") {
            if (dryRun) log("   🔈 $op $v")
            else withContext(Dispatchers.IO) {
                when (op) {
                    "set" -> WinSys.setVolume(percent = v); "up" -> WinSys.setVolume(percent = now.first + v); "down" -> WinSys.setVolume(percent = now.first - v)
                    "mute" -> WinSys.setVolume(mute = true); "unmute" -> WinSys.setVolume(mute = false); "toggleMute" -> WinSys.setVolume(mute = !now.second)
                }
            }
        }
        val after = withContext(Dispatchers.IO) { WinSys.volume() }
        vars["muted"] = listOf(after.second.toString())
        items = listOf(after.first.toString())
    },
    FlowAction("screenshot", "螢幕截圖", "Screenshot", CAT_SYSTEM, "擷取所有螢幕或主螢幕（原始解析度）存成 PNG，可同時複製到剪貼簿。", "Captures all screens or the main one at full resolution as PNG, optionally to the clipboard too.",
        listOf(ArgSpec("area", "範圍", "Area", "choice", "all", listOf("all", "primary")), ArgSpec("delay", "延遲（秒）", "Delay (s)", "number", 0),
            ArgSpec("to", "存到", "Save to", "path", "{pictures}\\Screenshots\\FilesKmp {date:yyyy-MM-dd HHmmss}.png"), ArgSpec("copy", "也複製到剪貼簿", "Also copy", "bool", false)),
        changes = true, input = VType.None, output = VType.Files) { st ->
        val out = freeName(File(arg(st, "to")).let { if (it.extension.isEmpty()) File(it.path + ".png") else it })
        if (dryRun) { log("   📷 ${out.path}"); items = listOf(out.path); return@FlowAction }
        delay((arg(st, "delay").trim().toDoubleOrNull() ?: 0.0).times(1000).toLong().coerceIn(0, 600_000))
        items = listOf(withContext(Dispatchers.IO) { WinSys.screenshot(out, arg(st, "area") == "primary", bool(st, "copy")) }.path)
    },
    FlowAction("battery", "電池狀態", "Battery", CAT_SYSTEM, "電量百分比、是否充電、是否接上電源，或全部（字典）。", "Battery percent, charging, plugged in, or everything (a dictionary).",
        listOf(ArgSpec("get", "取得", "Get", "choice", "percent", listOf("percent", "charging", "pluggedIn", "all"))), input = VType.None,
        outputFor = { st -> when (st.args["get"] ?: "percent") { "percent" -> VType.Number; "all" -> VType.Json; else -> VType.Bool } }) { st ->
        val p = WinSys.power()
        items = listOf(when (arg(st, "get")) {
            "charging" -> p.charging.toString(); "pluggedIn" -> (p.pluggedIn == true).toString()
            "all" -> Json.write(p.toMap()); else -> (p.percent ?: 100).toString()
        })
    },
    FlowAction("dnd", "勿擾（通知橫幅）", "Do not disturb", CAT_SYSTEM,
        "開關 Windows 通知橫幅（Windows 沒有公開的「勿擾 / 專注」API，這裡切換的是同一個「通知」總開關）；結果為目前是否勿擾。",
        "Turns Windows notification banners off/on (Windows has no public Focus / DND API; this is the main Notifications switch); the result is whether it's quiet now.",
        listOf(ArgSpec("op", "動作", "Do", "choice", "status", listOf("status", "on", "off", "toggle"))), input = VType.Anything, output = VType.Bool) { st ->
        val op = arg(st, "op")
        if (op != "status") {
            val want = when (op) { "on" -> true; "off" -> false; else -> !WinSys.quiet() }
            if (dryRun) log("   🌙 " + (if (want) "on" else "off")) else withContext(Dispatchers.IO) { WinSys.setQuiet(want) }
        }
        items = listOf(WinSys.quiet().toString())
    },

    // ================================================================ mail & messages
    FlowAction("email.send", "寄送電子郵件", "Send email", CAT_SHARE,
        "寄信並附上目前的檔案。方式：draft（建立草稿用預設郵件程式開啟，最通用）、outlook（傳統版 Outlook 顯示或直接寄出）、smtp（以「帳戶」中設定的信箱直接寄出）。",
        "Mails the current files as attachments. How: draft (an .eml opened in your mail app — works everywhere), outlook (classic Outlook shows or sends it), smtp (sends with the account set up under Accounts).",
        listOf(ArgSpec("to", "收件者（逗號分隔）", "To (comma separated)", "text", ""), ArgSpec("cc", "副本", "Cc", "text", ""), ArgSpec("bcc", "密件副本", "Bcc", "text", ""),
            ArgSpec("subject", "主旨", "Subject", "text", "{count} 個檔案"), ArgSpec("body", "內文", "Message", "multiline", ""),
            ArgSpec("html", "內文是 HTML", "Message is HTML", "bool", false), ArgSpec("attach", "附加目前的檔案", "Attach the current files", "bool", true),
            ArgSpec("method", "方式", "How", "choice", "draft", listOf("draft", "outlook", "smtp")), ArgSpec("sendNow", "直接寄出（outlook）", "Send at once (outlook)", "bool", false)),
        changes = true, input = VType.Anything, output = VType.Same) { st ->
        val att = if (bool(st, "attach")) files().map { File(it) }.filter { it.isFile } else emptyList()
        val total = att.sumOf { it.length() }
        require(total <= 100L shl 20) { (if (S.zh) "附件太大：" else "Attachments too large: ") + Fs.sizeText(total) }
        val m = Mailer.Mail(MailAccounts.smtpFrom, Mailer.addresses(arg(st, "to")), Mailer.addresses(arg(st, "cc")), Mailer.addresses(arg(st, "bcc")),
            arg(st, "subject"), arg(st, "body"), bool(st, "html"), att)
        val method = arg(st, "method")
        if (dryRun) { log("   ✉ $method → ${m.to.joinToString()} · ${att.size} " + (if (S.zh) "個附件" else "attachments")); return@FlowAction }
        withContext(Dispatchers.IO) {
            when (method) {
                "smtp" -> { Mailer.smtp(m); log(if (S.zh) "   已寄出" else "   sent") }
                "outlook" -> log("   Outlook: " + Mailer.outlook(m, bool(st, "sendNow")))
                else -> log("   " + (if (S.zh) "草稿：" else "draft: ") + Mailer.draft(m, File(AppDirs.cache, "mail")).path)
            }
        }
    },
    FlowAction("sms.send", "傳送簡訊", "Send text message", CAT_SHARE,
        "phone：以 Windows 的簡訊程式（手機連結）開啟對話並填好內容，附件會放到剪貼簿讓你貼上。twilio：以「帳戶」中的 Twilio 直接寄出，圖片需為公開網址（MMS）。",
        "phone: opens the Windows SMS app (Phone Link) with the text filled in; attachments go on the clipboard to paste. twilio: sends with the Twilio account under Accounts; pictures must be public URLs (MMS).",
        listOf(ArgSpec("to", "電話號碼", "Phone number", "text", ""), ArgSpec("body", "內容", "Message", "multiline", "{input}"),
            ArgSpec("attach", "附加目前的檔案 / 網址", "Attach the current files / URLs", "bool", false),
            ArgSpec("method", "方式", "How", "choice", "phone", listOf("phone", "twilio"))),
        changes = true, input = VType.Anything, output = VType.Same) { st ->
        val to = arg(st, "to").trim(); val body = arg(st, "body")
        require(to.any { it.isDigit() }) { if (S.zh) "沒有電話號碼" else "No phone number" }
        val attach = bool(st, "attach")
        if (dryRun) { log("   💬 ${arg(st, "method")} → $to: " + body.take(80)); return@FlowAction }
        when (arg(st, "method")) {
            "twilio" -> {
                val urls = if (attach) items.filter { it.startsWith("https://", true) || it.startsWith("http://", true) } else emptyList()
                if (attach && files().isNotEmpty()) log(if (S.zh) "   ! Twilio 只能附加公開網址，本機檔案已略過" else "   ! Twilio only attaches public URLs; local files skipped")
                log("   Twilio: " + withContext(Dispatchers.IO) { Sms.twilio(to, body, urls) })
            }
            else -> {
                val fs = if (attach) files().filter { File(it).isFile } else emptyList()
                withContext(Dispatchers.IO) { Sms.phone(to, body, fs) }
                if (fs.isNotEmpty()) log(if (S.zh) "   已把 ${fs.size} 個附件放到剪貼簿，在簡訊視窗按 Ctrl+V" else "   ${fs.size} attachment(s) on the clipboard — press Ctrl+V in the message window")
            }
        }
    },
)
