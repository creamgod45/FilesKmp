package app.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

private class Checks {
    var pass = 0; var fail = 0
    fun check(name: String, ok: Boolean, info: Any? = null) { if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name ${info ?: ""}") } }
}

private fun dump(items: List<ShellItem>, indent: String = "   ") {
    items.forEach { i ->
        if (i.separator) println("$indent----")
        else println("$indent${i.label}  [verb=${i.verb}] ${if (!i.enabled) "(disabled) " else ""}${if (i.icon != null) "(icon) " else ""}${if (i.children.isNotEmpty()) "▸ ${i.children.size}" else ""}")
        if (i.children.isNotEmpty() && indent.length < 9) dump(i.children, "$indent   ")
    }
}

/**
 * --test-shell-menu <dir>: reads Windows' right-click menus for files this test makes (Windows 11 and
 * classic, a folder's empty space, and the fallback when the Windows 11 menu can't be read).
 * Only reads menus: nothing is invoked.
 */
fun shellMenuTest(dir: File) {
    val c = Checks()
    dir.mkdirs()
    val txt = File(dir, "note.txt").apply { writeText("hello") }
    val png = File(dir, "pic.png").apply { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 4, 2), "png", this) }
    val sub = File(dir, "sub").apply { mkdirs() }
    println("Windows build ${ShellMenu.build}, Windows 11: ${ShellMenu.isWindows11}")
    var t = System.nanoTime()
    val handlers = ModernHandlers.all()
    println("Windows 11 menu handlers: ${handlers.size} (${(System.nanoTime() - t) / 1_000_000} ms)")
    c.check("handlers read", !ShellMenu.isWindows11 || handlers.isNotEmpty())
    c.check("types: file", ModernHandlers.typesOf(txt.path) == setOf("*", ".txt"))
    c.check("types: folder", ModernHandlers.typesOf(sub.path) == setOf("directory"))
    c.check("types: drive", "drive" in ModernHandlers.typesOf("C:\\"))

    fun run(name: String, paths: List<String>, bg: String? = null, mode: ShellMenuMode = ShellMenuMode.Auto): ShellMenuResult {
        t = System.nanoTime()
        val r = ShellMenu.query(paths, bg, mode)
        println("\n== $name: ${r.source}, top ${r.top.size}, more ${r.more.size}, ${(System.nanoTime() - t) / 1_000_000} ms ${r.note ?: ""}")
        dump(r.top)
        if (r.more.isNotEmpty()) { println("   [Show more options]"); dump(r.more, "      ") }
        return r
    }
    val a = run("text file (auto)", listOf(txt.path))
    c.check("text file: Windows 11 menu", a.source == ShellMenuResult.Source.Modern || !ShellMenu.isWindows11, a.note)
    c.check("text file: classic menu under Show more options", a.more.size >= 3 || a.source == ShellMenuResult.Source.Classic)
    c.check("classic has Properties", (a.more + a.top).any { it.verb.equals("properties", true) })
    c.check("a submenu has items (filled when opened)", (a.more + a.top).any { it.children.size >= 2 })
    val b = run("picture", listOf(png.path))
    c.check("picture: Windows 11 menu", b.source == ShellMenuResult.Source.Modern || !ShellMenu.isWindows11)
    run("two files", listOf(txt.path, png.path))
    val f = run("folder", listOf(sub.path))
    c.check("folder: read", f.source != ShellMenuResult.Source.Unavailable, f.note)
    val bg = run("folder background", emptyList(), bg = dir.path)
    c.check("background: read", bg.source != ShellMenuResult.Source.Unavailable && (bg.top + bg.more).isNotEmpty(), bg.note)
    // Running an item inside a submenu: 新增 ▸ 資料夾 in this test's own folder (makes one empty folder there).
    val newMenu = (bg.top + bg.more).firstOrNull { i -> i.children.any { it.verb.equals("NewFolder", true) } }
    c.check("background: 新增 ▸ submenu read", newMenu != null)
    val before = dir.listFiles { f -> f.isDirectory }!!.map { it.name }.toSet()
    newMenu?.children?.first { it.verb.equals("NewFolder", true) }?.run?.invoke()
    var made: List<String> = emptyList()
    repeat(40) { if (made.isEmpty()) { Thread.sleep(100); made = dir.listFiles { f -> f.isDirectory }!!.map { it.name }.filter { it !in before } } }
    c.check("submenu item runs (a new folder appeared)", made.size == 1, made)
    val classic = run("classic only", listOf(txt.path), mode = ShellMenuMode.Classic)
    c.check("classic mode", classic.source == ShellMenuResult.Source.Classic && classic.top.size >= 3)
    c.check("classic submenus are read (Send to, Open with, 7-Zip…)", classic.top.count { it.children.size >= 2 } >= 2, classic.top.filter { it.children.isNotEmpty() }.map { it.label })

    // The Windows 11 menu can't be read (handlers registered, none can be created): fall back to classic.
    ModernHandlers.testSet(listOf(ModernHandlers.Handler("Broken", "*", "x", "00000000-1111-2222-3333-444444444444")))
    val fb = run("fallback", listOf(txt.path))
    c.check("fallback to classic", fb.source == ShellMenuResult.Source.Classic && fb.top.size >= 3 && fb.note != null, fb.source)
    val strict = run("Windows 11 only, broken", listOf(txt.path), mode = ShellMenuMode.Modern)
    c.check("Windows 11 only: says unavailable", strict.source == ShellMenuResult.Source.Unavailable)
    ModernHandlers.testSet(null)
    c.check("off", ShellMenu.query(listOf(txt.path), mode = ShellMenuMode.Off).source == ShellMenuResult.Source.Unavailable)

    // Names as people read them.
    listOf("開啟檔案(&H)" to "開啟檔案", "傳送到(&N)" to "傳送到", "Open wit&h..." to "Open with...", "&Copy\tCtrl+C" to "Copy",
        "Save && quit" to "Save & quit", "開啟檔案(&H)..." to "開啟檔案...").forEach { (raw, want) ->
        c.check("label '$raw'", ShellMenu.cleanLabel(raw) == want, ShellMenu.cleanLabel(raw))
    }
    // What the person chose to show where.
    ShellMenuPrefs.testReset()
    val a1 = ShellItem("Edit in Notepad", null); val open = ShellItem("Open", "open"); val b1 = ShellItem("7-Zip", "SevenZip")
    val sep = ShellItem("", null, separator = true); val c1 = ShellItem("Properties", "properties")
    ShellMenuPrefs.noteSeen(listOf(a1), listOf(open, b1, sep, c1))
    var (main, rest) = ShellMenuPrefs.arrange(listOf(a1, open), listOf(open, b1, sep, c1))
    c.check("default: Windows 11 items in the main menu, duplicates hidden there", main.map { it.label } == listOf("Edit in Notepad"), main.map { it.label })
    c.check("default: classic menu complete under more", rest.map { it.label } == listOf("Open", "7-Zip", "", "Properties"), rest.map { it.label })
    ShellMenuPrefs.set("7-Zip", ShellPlace.Top); ShellMenuPrefs.set("Edit in Notepad", ShellPlace.Hidden); ShellMenuPrefs.set("Properties", ShellPlace.Hidden)
    ShellMenuPrefs.arrange(listOf(a1), listOf(open, b1, sep, c1)).let { main = it.first; rest = it.second }
    c.check("pinned to main, hidden gone", main.map { it.label } == listOf("7-Zip") && rest.none { it.label == "Properties" || it.label == "Edit in Notepad" }, main.map { it.label } to rest.map { it.label })
    c.check("no separator left at the end", rest.lastOrNull()?.separator != true, rest.map { it.label })
    ShellMenuPrefs.changeShowMore(false)
    c.check("show more off", ShellMenuPrefs.arrange(listOf(a1), listOf(open, b1)).second.isEmpty())
    ShellMenuPrefs.testReset()
    println("\nSHELL MENU TEST: ${c.pass} passed, ${c.fail} failed")
}

/** --test-errorlog <dir>: records, groups, limits, redacts and exports errors, all inside <dir>. */
fun errorLogTest(dir: File) {
    val c = Checks()
    val logs = File(dir, "Logs").apply { deleteRecursively(); mkdirs() }
    ErrorLog.testDir = logs; ErrorLog.enabled = true; ErrorLog.keepTrail = true
    ErrorLog.crumb("navigate " + System.getProperty("user.home") + "\\Documents")
    ErrorLog.crumb("key Ctrl+B → ToggleSidebar")
    fun boom(): Nothing = throw IllegalStateException("test failure in " + System.getProperty("user.home") + "\\file.txt")
    val r = runCatching { boom() }.exceptionOrNull()!!.let { ErrorLog.record(it, "test") }
    c.check("recorded", r != null && File(logs, "${r.id}.json").exists())
    val back = ErrorLog.reports().firstOrNull()
    c.check("read back", back != null && back.type == "java.lang.IllegalStateException" && back.trail.size == 2 && back.version == AppInfo.version && back.stack.contains("boom"))
    repeat(30) { runCatching { boom() }.exceptionOrNull()!!.let { ErrorLog.record(it, "test") } }
    c.check("same error kept at most 20 times", ErrorLog.reports().count { it.signature == r!!.signature } == 20)
    runCatching { listOf<Int>()[3] }.exceptionOrNull()!!.let { ErrorLog.record(it, "other") }
    c.check("different error, different group", ErrorLog.reports().map { it.signature }.distinct().size == 2)
    val red = ErrorLog.redact(back!!.text())
    c.check("redact hides the user folder", !red.contains(System.getProperty("user.home"), true) && red.contains("%USERPROFILE%"))
    val zip = ErrorLog.export(ErrorLog.reports(), dir, hideUser = true)
    val entries = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { e -> e.name to z.getInputStream(e).readBytes().decodeToString() } }
    c.check("export zip", entries.size == 21 && entries.none { it.second.contains(System.getProperty("user.home"), true) }, entries.size)
    // An uncaught error on another thread is recorded by the installed handler.
    ErrorLog.install()
    val th = Thread({ throw UnsupportedOperationException("uncaught test") }, "test-thread")
    th.start(); th.join()
    c.check("uncaught error recorded", ErrorLog.reports().any { it.type.endsWith("UnsupportedOperationException") && it.thread == "test-thread" })
    ErrorLog.record(RuntimeException("crash"), "window", fatal = true)
    c.check("crash noticed next start, once", ErrorLog.takeLastCrash() != null && ErrorLog.takeLastCrash() == null)
    ErrorLog.enabled = false
    c.check("off: nothing recorded", ErrorLog.record(RuntimeException("x"), "test") == null)
    ErrorLog.enabled = true
    ErrorLog.clear()
    c.check("clear", ErrorLog.reports().isEmpty())
    println("\nERROR LOG TEST: ${c.pass} passed, ${c.fail} failed")
}

/** --test-background <dir>: the picture triggers, random rounds and the migration from "bgMode". */
fun backgroundTest(dir: File) {
    val c = Checks()
    if (!Prefs.dir.canonicalPath.startsWith(dir.canonicalPath)) { println("REFUSED"); return }
    Prefs.readOnly = true
    val s = AppState(CoroutineScope(SupervisorJob()))
    s.bgImages.clear(); s.bgImages.addAll((1..5).map { "C:\\pics\\$it.jpg" }); s.bgIndex = 0; s.backgroundImage = s.bgImages[0]
    s.bgShuffle = false
    s.nextBackground(); c.check("list order", s.bgIndex == 1)
    s.bgShuffle = true
    var last = s.bgIndex
    val seen = ArrayList<Int>()
    var repeatsInRow = 0
    repeat(50) { s.nextBackground(); if (s.bgIndex == last) repeatsInRow++; last = s.bgIndex; seen += s.bgIndex }
    c.check("random: never the same picture twice in a row", repeatsInRow == 0)
    c.check("random: every picture once per round", seen.chunked(5).all { it.toSet().size == 5 }, seen)
    s.bgOnRestore = false; val before = s.bgIndex; s.onWindowRestored()
    c.check("restore: off → no change", s.bgIndex == before)
    s.bgOnRestore = true; s.onWindowRestored()
    c.check("restore: on → next picture", s.bgIndex != before)
    // Old settings: "bgMode" becomes the matching switch.
    val m = Updates.migrations.first { it.id == "1.2.0-background-triggers" }
    c.check("migrate Timer", m.apply(mapOf("bgMode" to "Timer")) == mapOf("bgOnTimer" to "true", "bgMode" to null))
    c.check("migrate OnStartup", m.apply(mapOf("bgMode" to "OnStartup")) == mapOf("bgOnStart" to "true", "bgMode" to null))
    c.check("migrate Single", m.apply(mapOf("bgMode" to "Single")) == mapOf("bgMode" to null))
    c.check("nothing to migrate", m.apply(emptyMap()).isEmpty())
    println("\nBACKGROUND TEST: ${c.pass} passed, ${c.fail} failed")
}

/**
 * --test-sha-flow <flow.fkflow.json> <dir>: runs a "verify SHA-256" shortcut (dry run: notifications are only
 * logged, the clipboard isn't touched) on a file it makes, with a matching and a wrong expected hash.
 */
fun shaFlowTest(flowFile: File, dir: File) {
    val c = Checks()
    if (!Prefs.dir.canonicalPath.startsWith(dir.canonicalPath)) { println("REFUSED"); return }
    Prefs.readOnly = true
    dir.mkdirs()
    val f = File(dir, "download.bin").apply { writeBytes(ByteArray(300_000) { (it * 31).toByte() }) }
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    // Why the original failed: the hash used to come with the file name, so "equals" never matched.
    c.check("old output (hash + name) never equals the bare hash", !FlowCompare.test("equals", "$hash  ${f.name}", hash))
    c.check("equals ignores case and the line break a copied hash ends with", FlowCompare.test("equals", hash, hash.uppercase() + "\r\n"))
    val flow = Flow.fromJson("test", flowFile.readText(Charsets.UTF_8))
    val s = AppState(CoroutineScope(SupervisorJob()))
    s.tab.selection = setOf(f.path)
    fun run(expected: String): List<String> {
        val log = java.util.Collections.synchronizedList(ArrayList<String>())
        val r = FlowRun(s, flow, listOf(f.path), mapOf("SHA" to expected), true, { null }, { log += it })
        kotlinx.coroutines.runBlocking { r.runSteps(flow.steps) }
        return log
    }
    val ok = run(hash.uppercase() + "\n")
    ok.forEach { println("   $it") }
    c.check("matching hash → ✅", ok.any { "✅" in it } && ok.none { "❌" in it })
    val bad = run("0".repeat(64))
    bad.filter { "🔔" in it }.forEach { println("   $it") }
    c.check("wrong hash → ❌ with both values", bad.any { "❌" in it && hash in it })
    s.tab.selection = emptySet()
    val none = runCatching { run(hash) }
    c.check("nothing selected → stops with a message", none.exceptionOrNull()?.message?.contains("選取") == true, none.exceptionOrNull()?.message)
    println("\nSHA FLOW TEST: ${c.pass} passed, ${c.fail} failed")
}

/** --test-tokens: what a shortcut field offers and flags ({var.x} set before, {param.x}, unknown tokens, a missing }). */
fun tokensTest() {
    val c = Checks()
    val steps = listOf(
        FlowStep("files.selected", saveAs = "FILES"),
        FlowStep("file.hash", saveAs = "FILESHA"),
        FlowStep("if", mapOf("then" to listOf(FlowStep("clipboard.get", saveAs = "INNER")))),
        FlowStep("wait.skippable"),
        FlowStep("notify"),
    )
    c.check("vars before step 1", FlowTokens.varsSetBy(steps.take(0)).isEmpty())
    c.check("vars before step 3", FlowTokens.varsSetBy(steps.take(2)) == listOf("FILES", "FILESHA"))
    c.check("vars set inside if count afterwards", "INNER" in FlowTokens.varsSetBy(steps.take(3)))
    c.check("wait.skippable sets skipped", "skipped" in FlowTokens.varsSetBy(steps.take(4)))
    val vars = listOf("FILES", "FILESHA"); val params = listOf("SHA")
    c.check("valid text has no problems", FlowTokens.check("{var.FILESHA} vs {param.SHA} {first} {date:yyyyMMdd} {item.name} {env.PATH}{newline}", params, vars).isEmpty())
    c.check("variable not set yet", FlowTokens.check("{var.COPYSHA}", params, vars).single().contains("COPYSHA"))
    c.check("unknown parameter", FlowTokens.check("{param.X}", params, vars).single().contains("X"))
    c.check("unknown token", FlowTokens.check("{frist}", params, vars).single().contains("frist"))
    c.check("missing }", FlowTokens.check("hash: {var.FILESHA", params, vars).isNotEmpty())
    c.check("plain braces in JSON are fine", FlowTokens.check("{\"a\": 1}", params, vars).isEmpty())
    c.check("typing after {", FlowTokens.typing("hash {va", 8) == "va" && FlowTokens.typing("x {", 3) == "" && FlowTokens.typing("{var.A} x", 9) == null)
    val cat = FlowTokens.catalog(params, vars)
    c.check("catalog: variables first, then parameters", cat[0].text == "{var.FILES}" && cat[2].text == "{param.SHA}")
    c.check("catalog has the built-ins", listOf("{first}", "{clipboard}", "{item.name}", "{newline}").all { t -> cat.any { it.text == t } })

    // Search syntax in query fields: suggestions while typing, and mistakes that would make a filter match nothing.
    c.check("typing a search term", SearchQuery.typingTerm("a.txt fi", 8) == "fi" && SearchQuery.typingTerm("!ext", 4) == "ext" && SearchQuery.typingTerm("x ", 2) == null)
    c.check("fi offers file: and field:", FlowTokens.searchMatches("fi").map { it.text } == listOf("file:", "field:狀態=待審"), FlowTokens.searchMatches("fi").map { it.text })
    c.check("f offers file:, folder: and field:", FlowTokens.searchMatches("f").map { it.text } == listOf("file:", "folder:", "field:狀態=待審"), FlowTokens.searchMatches("f").map { it.text })
    c.check("dm: offers its examples", FlowTokens.searchMatches("dm:").size >= 3 && FlowTokens.searchMatches("file:").isEmpty())
    c.check("valid queries have no problems", listOf("file:", "folder: !ext:tmp", "ext:jpg;png size:>10mb dm:<30d", "file: dm:{param.D}", "regex:^a|b$", "C:\\x *.log", "\"a b\" | c").all { SearchQuery.problems(it).isEmpty() })
    c.check("kind:file is flagged", SearchQuery.problems("kind:file").single().contains("kind:"))
    c.check("file:pdf is flagged", SearchQuery.problems("file:pdf").isNotEmpty())
    c.check("bad size / date / regex are flagged", SearchQuery.problems("size:big").isNotEmpty() && SearchQuery.problems("dm:someday").isNotEmpty() && SearchQuery.problems("regex:(").isNotEmpty())
    c.check("file: really keeps only files", SearchQuery.parse("file:")!!.let { q ->
        q.matches(SearchQuery.Candidate("a.txt", "a.txt", false, 1, 0, 0) { "C:\\a.txt" }) && !q.matches(SearchQuery.Candidate("d", "d", true, 0, 0, 0) { "C:\\d" })
    })

    // The AI's validator explains what the parser will do with the shortcut it drafted.
    fun aiCheck(steps: String) = AiTools.check("""{"schema":"fileskmp.shortcut/v1","name":"t","accepts":["files"],"steps":[$steps]}""")
    c.check("validator: kind:file", aiCheck("""{"action":"files.filter","args":{"query":"kind:file"}}""").any { "kind:" in it })
    c.check("validator: {item} outside a per-item argument", aiCheck("""{"action":"notify","args":{"title":"{item.name}"}}""").any { "per-item" in it })
    c.check("validator: {item} in a per-item argument is fine", aiCheck("""{"action":"file.rename","args":{"name":"{item.stem}-1.{item.ext}"}}""").none { "per-item" in it })
    c.check("validator: variable not set yet", aiCheck("""{"action":"notify","args":{"text":"{var.h}"}},{"action":"file.hash","saveAs":"h"}""").any { "“h”" in it || "「h」" in it })
    c.check("validator: variable set before is fine", aiCheck("""{"action":"file.hash","saveAs":"h"},{"action":"notify","args":{"text":"{var.h}"}}""").none { "Step 2" in it })
    c.check("validator: non-ASCII saveAs", aiCheck("""{"action":"file.hash","saveAs":"雜湊"}""").any { "saveAs" in it })
    c.check("validator: bad choice", aiCheck("""{"action":"stop.if","args":{"when":"nothing"}}""").any { "not one of" in it })
    c.check("validator: inside each, {var.item} is known", aiCheck("""{"action":"each","args":{"steps":[{"action":"notify","args":{"text":"{var.item} {var.index}"}}]}}""").none { "Step 1.steps" in it })
    val sampleProblems = FlowStore.samples().flatMap { f -> AiTools.check(f.toJson()).filter { !it.startsWith("Note") }.map { "${f.name}: $it" } }
    c.check("built-in samples pass the validator", sampleProblems.isEmpty(), sampleProblems.joinToString("\n"))
    FlowActions.recipes.forEach { (title, json) ->
        val p = AiTools.check(json).filter { !it.startsWith("Note") }
        c.check("recipe passes the validator: $title", p.isEmpty(), p.joinToString("\n"))
    }
    // An action's own defaults must never be flagged (the video actions' {item.stem} once was, and the AI gave up).
    val defaultProblems = FlowActions.all.flatMap { a ->
        val args = a.args.filter { it.type != "steps" && it.default is String && (it.default as String).isNotEmpty() }.associate { it.id to it.default }
        aiCheck(Json.write(mapOf("action" to a.id, "args" to args))).filter { "per-item" in it || "not one of" in it }.map { "${a.id}: $it" }
    }
    c.check("every action's defaults pass the validator", defaultProblems.isEmpty(), defaultProblems.joinToString("\n"))
    c.check("validator: video name/to take {item…} with a variable", aiCheck("""{"action":"text.set","args":{"text":"720p"},"saveAs":"res"},{"action":"video.convert","input":"shortcut","args":{"height":"{var.res}","name":"{item.stem}_{var.res}","to":"{item.parent}\\out"}}""").none { "per-item" in it || "not one of" in it })
    c.check("chat shows the problems, not just the heading", AiChat.errorPreview("Not proposed; fix these first:\nStep 3: a\nStep 4: b\nFix every line above and call propose_shortcut again").let { "Step 3: a" in it && "Step 4: b" in it && "Fix every" !in it })
    val spec = FlowActions.specMarkdown()
    c.check("spec documents the search syntax, per-item args and conditions", listOf("## Search syntax", "`file:`", "*per item*", "## Conditions", "stays exactly as typed").all { it in spec })
    println("\nTOKENS TEST: ${c.pass} passed, ${c.fail} failed")
}

/** A small uncompressed AVI (moving colour bars) for the media test. */
private fun writeTestAvi(f: File, w: Int = 64, h: Int = 48, frames: Int = 20, fps: Int = 10) {
    val frameBytes = w * 3 * h
    val b = java.io.ByteArrayOutputStream()
    fun i32(v: Int) { b.write(v and 255); b.write(v shr 8 and 255); b.write(v shr 16 and 255); b.write(v shr 24 and 255) }
    fun i16(v: Int) { b.write(v and 255); b.write(v shr 8 and 255) }
    fun cc(s: String) = b.write(s.toByteArray(Charsets.US_ASCII))
    val strl = 4 + (8 + 56) + (8 + 40)
    val hdrl = 4 + (8 + 56) + (8 + strl)
    val movi = 4 + frames * (8 + frameBytes)
    val idx = frames * 16
    cc("RIFF"); i32(4 + (8 + hdrl) + (8 + movi) + (8 + idx)); cc("AVI ")
    cc("LIST"); i32(hdrl); cc("hdrl")
    cc("avih"); i32(56); i32(1_000_000 / fps); i32(frameBytes * fps); i32(0); i32(0x10); i32(frames); i32(0); i32(1); i32(frameBytes); i32(w); i32(h); repeat(4) { i32(0) }
    cc("LIST"); i32(strl); cc("strl")
    cc("strh"); i32(56); cc("vids"); cc("DIB "); i32(0); i16(0); i16(0); i32(0); i32(1); i32(fps); i32(0); i32(frames); i32(frameBytes); i32(-1); i32(0); i16(0); i16(0); i16(w); i16(h)
    cc("strf"); i32(40); i32(40); i32(w); i32(h); i16(1); i16(24); i32(0); i32(frameBytes); i32(0); i32(0); i32(0); i32(0)
    cc("LIST"); i32(movi); cc("movi")
    repeat(frames) { n ->
        cc("00db"); i32(frameBytes)
        for (y in 0 until h) for (x in 0 until w) { val v = ((x + n * 3) * 4) and 255; b.write(v); b.write(255 - v); b.write((y * 5) and 255) }
    }
    cc("idx1"); i32(idx)
    repeat(frames) { n -> cc("00db"); i32(0x10); i32(4 + n * (8 + frameBytes)); i32(frameBytes) }
    f.writeBytes(b.toByteArray())
}

/**
 * --test-media <dir> [video.mp4]: plays a WAV and an AVI this test writes (and the given video, read
 * only) with Windows' Media Foundation, muted, in an invisible off-screen window.
 */
fun mediaTest(dir: File, extra: File?) {
    val c = Checks()
    dir.mkdirs()
    val wav = File(dir, "tone.wav")
    run {
        val rate = 44100f; val secs = 2
        val pcm = ByteArray((rate * secs).toInt() * 2) { 0 }
        for (i in 0 until pcm.size / 2) { val v = (Math.sin(i * 2 * Math.PI * 440 / rate) * 8000).toInt(); pcm[i * 2] = v.toByte(); pcm[i * 2 + 1] = (v shr 8).toByte() }
        val fmt = javax.sound.sampled.AudioFormat(rate, 16, 1, true, false)
        javax.sound.sampled.AudioSystem.write(javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(pcm), fmt, (pcm.size / 2).toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE, wav)
    }
    val avi = File(dir, "bars.avi").also { writeTestAvi(it) }
    // An invisible host window for the video (off screen, no taskbar button).
    val host = java.awt.Window(null as java.awt.Frame?).apply { setBounds(-4000, -4000, 320, 240); isVisible = true }
    val canvas = java.awt.Canvas().apply { setSize(320, 240) }
    host.add(canvas); host.validate()
    Thread.sleep(300)
    val hwnd = WindowCorners.hwnd(canvas) ?: 0L
    c.check("video surface has a window handle", hwnd != 0L)

    fun exercise(name: String, f: File, h: Long, expectSize: Pair<Int, Int>?, expectLen: Double?) {
        val pl = MediaEngine.open(f.path, h, autoPlay = false)
        c.check("$name: opened", pl != null)
        if (pl == null) return
        pl.mute(true); pl.volume(0f); pl.play()
        var len = 0.0
        repeat(40) { if (len <= 0.0) { Thread.sleep(100); len = pl.duration() } }
        println("   $name: length ${"%.2f".format(len)} s, state ${pl.state()}, size ${pl.videoSize()}")
        c.check("$name: length known", len > 0 && (expectLen == null || kotlin.math.abs(len - expectLen) < 0.3), len)
        if (expectSize != null) c.check("$name: picture size", pl.videoSize() == expectSize, pl.videoSize())
        val p0 = pl.position(); Thread.sleep(700); val p1 = pl.position()
        c.check("$name: plays (position moves)", pl.state() == MediaEngine.State.Playing && p1 > p0, "$p0 → $p1")
        pl.pause(); Thread.sleep(200)
        c.check("$name: pause", pl.state() == MediaEngine.State.Paused)
        pl.seek(len / 2); Thread.sleep(300)
        c.check("$name: seek", kotlin.math.abs(pl.position() - len / 2) < 0.4, pl.position())
        pl.close()
        c.check("$name: closed", pl.state() == MediaEngine.State.Closed)
    }
    exercise("wav", wav, 0L, null, 2.0)
    exercise("avi", avi, hwnd, 64 to 48, 2.0)
    if (extra != null && extra.isFile) exercise("mp4 (H.264)", extra, hwnd, null, null)
    c.check("not a media file: refused or fails cleanly", runCatching {
        val pl = MediaEngine.open(File(dir, "tone.wav").path + ".missing", 0L, autoPlay = false); pl?.close(); pl == null
    }.getOrDefault(false))
    host.dispose()
    println("\nMEDIA TEST: ${c.pass} passed, ${c.fail} failed")
}

/** --test-xls <file.xls>...: reads old Excel workbooks (read only) and prints their sheets. */
fun xlsTest(files: List<File>) {
    val c = Checks()
    files.forEach { f ->
        val r = runCatching { Xls.read(f) }
        c.check("${f.name}: read", r.isSuccess, r.exceptionOrNull())
        r.getOrNull()?.forEach { sh ->
            println("   [${sh.name}] ${sh.total} rows")
            sh.rows.filter { row -> row.any { it.isNotBlank() } }.take(4).forEach { row -> println("      " + row.joinToString(" | ").take(160)) }
        }
        c.check("${f.name}: has sheets with cells", r.getOrNull().orEmpty().any { sh -> sh.rows.any { row -> row.any { it.isNotBlank() } } })
    }
    println("\nXLS TEST: ${c.pass} passed, ${c.fail} failed")
}

/** --test-archives <file>...: lists archives (read only) and prints a summary of each. */
fun archivesTest(files: List<File>) {
    val c = Checks()
    files.forEach { f ->
        val r = runCatching { Archives.list(f) }
        val l = r.getOrNull()
        println("   ${f.name}: ${l?.format} ${l?.entries?.size} entries ${l?.note ?: ""} ${r.exceptionOrNull()?.message ?: ""}")
        l?.entries?.take(6)?.forEach { e -> println("      ${if (e.dir) "[D]" else "   "} ${e.path}  ${e.size}  packed ${e.packed}  ${if (e.modified > 0) java.time.Instant.ofEpochMilli(e.modified) else ""}") }
        c.check("${f.name}: listed", l != null && l.entries.isNotEmpty(), r.exceptionOrNull())
    }
    println("\nARCHIVES TEST: ${c.pass} passed, ${c.fail} failed")
}

/** A one-page PDF with a line of text (offsets in the xref table computed). */
private fun writeTestPdf(f: File) {
    val objs = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        "<< /Length 44 >>\nstream\nBT /F1 24 Tf 40 100 Td (FilesKmp PDF) Tj ET\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    )
    val sb = StringBuilder("%PDF-1.4\n")
    val offsets = ArrayList<Int>()
    objs.forEachIndexed { i, o -> offsets += sb.length; sb.append("${i + 1} 0 obj\n$o\nendobj\n") }
    val xref = sb.length
    sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
    offsets.forEach { sb.append("%010d 00000 n \n".format(it)) }
    sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    f.writeText(sb.toString(), Charsets.ISO_8859_1)
}

/** A minimal Word document (one paragraph). */
private fun writeTestDocx(f: File) {
    java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
        fun put(n: String, t: String) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
        put("word/document.xml", """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>FilesKmp 預覽測試</w:t></w:r></w:p></w:body></w:document>""")
    }
}

/**
 * --test-shell-preview <dir> [file...]: opens a PDF and a .docx this test writes (and any files given,
 * read only) with Windows' preview handlers in an invisible off-screen window.
 */
fun shellPreviewTest(dir: File, extra: List<File>) {
    val c = Checks()
    dir.mkdirs()
    val pdf = File(dir, "test.pdf").also { writeTestPdf(it) }
    val docx = File(dir, "test.docx").also { writeTestDocx(it) }
    val findChild = java.lang.foreign.Linker.nativeLinker().downcallHandle(
        java.lang.foreign.SymbolLookup.libraryLookup("user32", java.lang.foreign.Arena.global()).find("FindWindowExW").orElseThrow(),
        java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS))
    listOf("txt", "csv").forEach { e -> c.check("own previewer kept for .$e", ShellPreview.handlerFor(e) == null) }
    (listOf(pdf, docx) + extra).forEach { f ->
        val ext = f.name.substringAfterLast('.').lowercase()
        val clsid = ShellPreview.handlerFor(ext)
        println("   .$ext handler: $clsid")
        c.check("${f.name}: handler registered", clsid != null)
        if (clsid == null) return@forEach
        val host = java.awt.Window(null as java.awt.Frame?).apply { setBounds(-4000, -4000, 640, 480); isVisible = true }
        val canvas = java.awt.Canvas().apply { setSize(640, 480) }
        host.add(canvas); host.validate(); Thread.sleep(300)
        val hwnd = WindowCorners.hwnd(canvas) ?: 0L
        val t0 = System.nanoTime()
        val r = runCatching { ShellPreview.open(f.path, clsid, hwnd, 640, 480) }
        println("   ${f.name}: ${if (r.isSuccess) "shown" else r.exceptionOrNull()?.message} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        c.check("${f.name}: preview opened", r.isSuccess, r.exceptionOrNull())
        var child = 0L
        repeat(50) { if (child == 0L) { Thread.sleep(100); child = (findChild.invokeWithArguments(java.lang.foreign.MemorySegment.ofAddress(hwnd), java.lang.foreign.MemorySegment.NULL, java.lang.foreign.MemorySegment.NULL, java.lang.foreign.MemorySegment.NULL) as java.lang.foreign.MemorySegment).address() } }
        c.check("${f.name}: the handler drew its window inside ours", child != 0L)
        r.getOrNull()?.let { h -> h.resize(800, 600); h.close() }
        host.dispose()
    }
    println("\nSHELL PREVIEW TEST: ${c.pass} passed, ${c.fail} failed")
}

/**
 * --test-meta-flow <empty dir>: the custom-field, date and attribute actions and the field: filter, run for
 * real on files made here. Settings are read-only, so the real metadata.tsv is never written.
 */
fun metaFlowTest(dir: File) {
    Prefs.readOnly = true
    val c = Checks()
    dir.mkdirs()
    val a = File(dir, "a.jpg").apply { writeText("a") }; val b = File(dir, "b.jpg").apply { writeText("b") }; val t = File(dir, "c.txt").apply { writeText("c") }
    val all = listOf(a, b, t).map { it.path }
    val s = AppState(CoroutineScope(SupervisorJob()))
    fun run(input: List<String>, dry: Boolean = false, vararg steps: FlowStep): List<String> {
        val flow = Flow("t", "t", steps = steps.toList())
        val r = FlowRun(s, flow, input, emptyMap(), dry, { null }, { println("      $it") })
        val e = runCatching { kotlinx.coroutines.runBlocking { r.runSteps(flow.steps) } }.exceptionOrNull()
        e?.let { println("      ERROR: ${it.message}") }
        return r.items
    }
    fun names(l: List<String>) = l.map { File(it).name }.sorted()

    run(listOf(a.path, b.path), false, FlowStep("meta.set", mapOf("field" to "狀態", "value" to "待審")))
    run(all, false, FlowStep("meta.set", mapOf("field" to "類型", "value" to "{item.ext}")))
    c.check("meta.set: fixed value on two files", MetaStore.get(a.path, "狀態") == "待審" && MetaStore.get(b.path, "狀態") == "待審" && MetaStore.get(t.path, "狀態") == "")
    c.check("meta.set: per-item value from a token", MetaStore.get(t.path, "類型") == "txt" && MetaStore.get(a.path, "類型") == "jpg")
    c.check("meta.set: creates the field", "狀態" in MetaStore.fields && "類型" in MetaStore.fields)
    fun filter(q: String) = names(run(all, false, FlowStep("files.filter", mapOf("query" to q))))
    c.check("field:狀態=待審", filter("field:狀態=待審") == listOf("a.jpg", "b.jpg"), filter("field:狀態=待審"))
    c.check("!field:狀態 (no value)", filter("!field:狀態") == listOf("c.txt"), filter("!field:狀態"))
    c.check("field:狀態=待* (wildcard), field:類型=TXT (ignores case)", filter("field:狀態=待*") == listOf("a.jpg", "b.jpg") && filter("field:類型=TXT") == listOf("c.txt"))
    c.check("field: combined with ext:", filter("ext:jpg !field:狀態") == emptyList<String>())
    val detail = run(all, false, FlowStep("file.detail", mapOf("detail" to "field", "field" to "狀態")))
    c.check("file.detail field", detail == listOf("待審", "待審", ""), detail)

    run(listOf(a.path), false, FlowStep("file.setDates", mapOf("modified" to "2020-01-02 03:04:05", "created" to "2019-12-31 23:59")))
    val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    fun at(ms: Long) = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault()).format(fmt)
    val attrsA = java.nio.file.Files.readAttributes(a.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
    c.check("file.setDates: modified and created written", at(attrsA.lastModifiedTime().toMillis()) == "2020-01-02 03:04:05" && at(attrsA.creationTime().toMillis()) == "2019-12-31 23:59:00",
        "${at(attrsA.lastModifiedTime().toMillis())} / ${at(attrsA.creationTime().toMillis())}")
    // A date token: item tokens turn ':' into '_' (12_30), which the date parser takes back.
    run(listOf(b.path), false, FlowStep("file.setDates", mapOf("modified" to "{item.created:yyyy-MM-dd HH:mm:ss}")))
    val attrsB = java.nio.file.Files.readAttributes(b.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
    c.check("file.setDates: modified := created via a token", at(attrsB.lastModifiedTime().toMillis()) == at(attrsB.creationTime().toMillis()), "${at(attrsB.lastModifiedTime().toMillis())} vs ${at(attrsB.creationTime().toMillis())}")
    val before = t.lastModified()
    run(listOf(t.path), true, FlowStep("file.setDates", mapOf("modified" to "2001-01-01 00:00")))
    c.check("file.setDates: dry run changes nothing", t.lastModified() == before)
    run(listOf(t.path), false, FlowStep("file.setDates", mapOf("modified" to "not a date")))
    c.check("file.setDates: a bad date stops, file unchanged", t.lastModified() == before)

    fun dos(f: File) = java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.DosFileAttributes::class.java)
    run(listOf(t.path), true, FlowStep("file.setAttributes", mapOf("hidden" to "on")))
    c.check("file.setAttributes: dry run changes nothing", !dos(t).isHidden)
    run(listOf(t.path), false, FlowStep("file.setAttributes", mapOf("hidden" to "on", "readOnly" to "on", "system" to "on")))
    c.check("file.setAttributes: hidden, read-only, system on", dos(t).let { it.isHidden && it.isReadOnly && it.isSystem })
    val flags = run(listOf(t.path), false, FlowStep("file.detail", mapOf("detail" to "system")))
    c.check("file.detail system", flags == listOf("true"), flags)
    run(listOf(t.path), false, FlowStep("file.setAttributes", mapOf("hidden" to "off", "readOnly" to "off", "system" to "off", "archive" to "off")))
    c.check("file.setAttributes: all off again", dos(t).let { !it.isHidden && !it.isReadOnly && !it.isSystem && !it.isArchive })
    run(listOf(a.path), false, FlowStep("meta.set", mapOf("field" to "狀態", "value" to "")))
    c.check("meta.set: empty value clears", MetaStore.get(a.path, "狀態") == "" && MetaStore.get(b.path, "狀態") == "待審")

    fun aiCheck(steps: String) = AiTools.check("""{"schema":"fileskmp.shortcut/v1","name":"t","accepts":["files"],"steps":[$steps]}""").filter { !it.startsWith("Note") }
    val ok = aiCheck("""{"action":"files.filter","args":{"query":"field:狀態=待審"}},{"action":"meta.set","args":{"field":"狀態","value":"{item.folder}"}},{"action":"file.setDates","args":{"modified":"{item.taken:yyyy-MM-dd HH:mm:ss}"}},{"action":"file.setAttributes","args":{"hidden":"on"}}""")
    c.check("validator: the new actions and field: pass", ok.isEmpty(), ok.joinToString("\n"))
    c.check("validator: an unknown field is pointed out", aiCheck("""{"action":"files.filter","args":{"query":"field:不存在=1"}}""").any { "不存在" in it })
    c.check("spec documents field: and the new actions", FlowActions.specMarkdown().let { sp -> listOf("field:", "`meta.set`", "`file.setDates`", "`file.setAttributes`").all { it in sp } })
    println("\nMETA FLOW TEST: ${c.pass} passed, ${c.fail} failed")
}

/** --test-menu: where the right-click menu goes in a short window (down, up, shifted, scrolling). */
fun menuTest() {
    val c = Checks()
    val win = androidx.compose.ui.unit.IntSize(800, 600); val m = 8
    fun at(x: Int, y: Int, w: Int, h: Int) = menuPlacement(androidx.compose.ui.unit.IntOffset(x, y), androidx.compose.ui.unit.IntSize(w, h), win, m)
    c.check("fits below: opens down at the pointer", at(100, 100, 200, 300) == androidx.compose.ui.unit.IntOffset(100, 100), at(100, 100, 200, 300))
    c.check("near the bottom: opens up, bottom edge at the pointer", at(100, 500, 200, 300).y == 200, at(100, 500, 200, 300))
    c.check("fits neither way: as close to the pointer as fits", at(100, 300, 200, 500).y == 92, at(100, 300, 200, 500))
    val cap = menuMaxHeight(600, m)
    c.check("taller than the window: capped to the window minus margins (scrolls)", cap == 584, cap)
    c.check("capped menu sits inside the margins", at(100, 300, 200, cap).y == 8, at(100, 300, 200, cap))
    c.check("near the right edge: opens to the left", at(700, 100, 200, 300).x == 500, at(700, 100, 200, 300))
    c.check("wider than the room on both sides: kept inside", at(400, 100, 700, 300).x.let { it >= 8 && it + 700 <= 792 }, at(400, 100, 700, 300))
    val tiny = menuPlacement(androidx.compose.ui.unit.IntOffset(10, 10), androidx.compose.ui.unit.IntSize(200, menuMaxHeight(40, m)), androidx.compose.ui.unit.IntSize(300, 40), m)
    c.check("tiny window: never negative", tiny.x >= 0 && tiny.y >= 0, tiny)
    println("\nMENU TEST: ${c.pass} passed, ${c.fail} failed")
}

/**
 * --test-maximize: an undecorated window (opaque and see-through, like the app's) maximized with
 * WindowWorkArea must fill the monitor's work area and leave the taskbar visible.
 */
fun maximizeTest() {
    val c = Checks()
    for (seeThrough in listOf(false, true)) {
        val f = javax.swing.JFrame("max").apply {
            isUndecorated = true
            if (seeThrough) background = java.awt.Color(0, 0, 0, 0)
            setBounds(200, 200, 600, 400); isVisible = true
        }
        javax.swing.SwingUtilities.invokeAndWait { WindowWorkArea.install(f) }
        javax.swing.SwingUtilities.invokeAndWait { f.extendedState = java.awt.Frame.MAXIMIZED_BOTH }
        Thread.sleep(1200)
        val gc = f.graphicsConfiguration
        val screen = gc.bounds; val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
        val want = java.awt.Rectangle(screen.x + ins.left, screen.y + ins.top, screen.width - ins.left - ins.right, screen.height - ins.top - ins.bottom)
        val got = f.bounds
        val label = if (seeThrough) "see-through" else "opaque"
        println("   $label: screen $screen insets $ins scale ${gc.defaultTransform.scaleX} → window $got, work area $want")
        c.check("$label: maximized", f.extendedState and java.awt.Frame.MAXIMIZED_BOTH == java.awt.Frame.MAXIMIZED_BOTH)
        c.check("$label: fills the work area, taskbar visible", kotlin.math.abs(got.x - want.x) <= 1 && kotlin.math.abs(got.y - want.y) <= 1 &&
            kotlin.math.abs(got.width - want.width) <= 2 && got.height <= want.height && want.height - got.height <= 2, "$got vs $want")
        f.dispose()
    }
    println("\nMAXIMIZE TEST: ${c.pass} passed, ${c.fail} failed")
}

/** --test-docs <file>...:reads Word / OpenDocument / PowerPoint files the way 快速預覽 does without a preview handler. */
fun docsTest(files: List<File>) {
    val c = Checks()
    files.forEach { f ->
        val ext = f.name.substringAfterLast('.').lowercase()
        val r = runCatching { OfficeDocs.read(f, ext, 2000, 12) }
        c.check("${f.name}: read", r.isSuccess, r.exceptionOrNull())
        when (val q = r.getOrNull()) {
            is QlContent.Document -> q.blocks.take(12).forEach { b -> println("   " + when (b) {
                is MdBlock.Heading -> "H${b.level} ${b.text}"; is MdBlock.Paragraph -> "P  ${b.text}"
                is MdBlock.ListItem -> "LI${b.depth} ${b.text}"; is MdBlock.Table -> "T  ${b.header.joinToString(" | ")} (+${b.rows.size} rows)"
                else -> b.toString() }.take(160)) }
            is QlContent.Table -> q.sheets.forEach { sh -> println("   [${sh.name}] ${sh.total} rows"); sh.rows.take(4).forEach { println("      " + it.joinToString(" | ").take(160)) } }
            is QlContent.Message -> println("   message: ${q.text}")
            else -> {}
        }
        c.check("${f.name}: shows content", r.getOrNull() is QlContent.Document || r.getOrNull() is QlContent.Table)
    }
    println("\nDOCS TEST: ${c.pass} passed, ${c.fail} failed")
}

/**
 * --test-video <dir>: makes short clips with FFmpeg (a VP9/Opus webm, an H.264/AAC ts) and runs the
 * video actions on them for real: convert (re-encode and stream copy), compress (quality and size),
 * scale, info, sound only, gif, custom arguments, and a dry run that must leave no file.
 * Run with APPDATA pointing inside <dir> (settings stay out of the real profile).
 */
fun videoTest(dir: File) {
    val c = Checks()
    if (!Prefs.dir.canonicalPath.startsWith(dir.canonicalPath)) { println("REFUSED: set APPDATA inside $dir"); return }
    Prefs.readOnly = true
    val exe = Ffmpeg.locate()
    c.check("ffmpeg found", exe != null, exe)
    if (exe == null) { println("\nVIDEO TEST: ${c.pass} passed, ${c.fail} failed"); return }
    println("   ffmpeg: $exe")
    val src = File(dir, "src").apply { deleteRecursively(); mkdirs() }
    val out = File(dir, "out").apply { deleteRecursively(); mkdirs() }
    fun make(name: String, vararg enc: String): File {
        val f = File(src, name)
        val p = ProcessBuilder(listOf(exe.path, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=4",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=4") + enc + f.path).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText(); p.waitFor()
        c.check("make $name", f.isFile && f.length() > 0, log.take(300))
        return f
    }
    val have = kotlinx.coroutines.runBlocking { Ffmpeg.encoders(exe) }
    println("   VP9 encoder: ${"libvpx-vp9" in have}, AV1 encoder: ${"libsvtav1" in have}")
    // The ARM64 build has no libvpx: its webm gets AV1, which webm takes as well.
    val webm = if ("libvpx-vp9" in have) make("clip one.webm", "-c:v", "libvpx-vp9", "-b:v", "1M", "-deadline", "realtime", "-cpu-used", "8", "-c:a", "libopus")
        else make("clip one.webm", "-c:v", "libsvtav1", "-preset", "12", "-c:a", "libopus")
    val webmCodec = if ("libvpx-vp9" in have) "vp9" else "av1"
    val ts = make("clip.ts", "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-c:a", "aac")
    val s = AppState(CoroutineScope(SupervisorJob()))
    fun runWith(input: File, dry: Boolean, ask: suspend (FlowPrompt) -> String?, steps: List<FlowStep>): Pair<List<String>, List<String>> {
        val log = java.util.Collections.synchronizedList(ArrayList<String>())
        val flow = Flow("t", "t", steps = steps)
        val r = FlowRun(s, flow, listOf(input.path), emptyMap(), dry, ask, { log += it })
        val t0 = System.nanoTime()
        val res = runCatching { kotlinx.coroutines.runBlocking { r.runSteps(flow.steps) } }
        log += "   (${(System.nanoTime() - t0) / 1_000_000} ms)"
        log.forEach { println("      $it") }
        res.exceptionOrNull()?.let { println("      ERROR: ${it.message}") }
        return r.items to log
    }
    fun run(input: File, dry: Boolean = false, vararg steps: FlowStep) = runWith(input, dry, { null }, steps.toList())
    fun probe(p: String?) = p?.let { kotlinx.coroutines.runBlocking { Ffmpeg.probe(exe, File(it)) } }
    val to = mapOf("to" to out.path)

    println("\n-- webm → mp4 (re-encode)")
    run(webm, false, FlowStep("video.convert", to + mapOf("format" to "mp4"))).first.singleOrNull().let { p ->
        val i = probe(p); c.check("webm → mp4: H.264 + AAC, 640x360", i?.video == "h264" && i.audio == "aac" && i.width == 640 && i.height == 360, "${i?.video} ${i?.audio} ${i?.width}x${i?.height}")
        c.check("webm → mp4: name kept", p?.let { File(it).name } == "clip one.mp4", p)
    }
    println("\n-- ts → mp4 (stream copy)")
    run(ts, false, FlowStep("video.convert", to + mapOf("format" to "mp4"))).let { (items, log) ->
        val i = probe(items.singleOrNull())
        c.check("ts → mp4: streams copied (h264/aac)", i?.video == "h264" && i.audio == "aac", "${i?.video} ${i?.audio}")
        c.check("ts → mp4: duration kept (~4 s)", i != null && kotlin.math.abs(i.duration - 4.0) < 0.3, i?.duration)
    }
    println("\n-- compress by quality (small)")
    run(ts, false, FlowStep("video.compress", to + mapOf("quality" to "small"))).first.singleOrNull().let { p ->
        c.check("compress (small): smaller than the source", p != null && File(p).length() < ts.length(), "${p?.let { File(it).length() }} vs ${ts.length()}")
    }
    println("\n-- compress to 0.15 MB (two-pass)")
    run(ts, false, FlowStep("video.compress", to + mapOf("method" to "size", "sizeMB" to 0.15))).first.singleOrNull().let { p ->
        val len = p?.let { File(it).length() } ?: 0
        c.check("compress (size): within 0.15 MB +15%", len in 1..(0.15 * 1048576 * 1.15).toLong(), len)
        c.check("compress (size): pass logs removed", AppDirs.cache.listFiles { f -> f.name.startsWith("ffmpeg2pass") }.isNullOrEmpty())
    }
    run(ts, false, FlowStep("video.compress", to + mapOf("method" to "size", "sizeMB" to 0.2, "codec" to "h265", "name" to "{item.stem}_hevc"))).first.singleOrNull().let { p ->
        val i = probe(p); val len = p?.let { File(it).length() } ?: 0
        c.check("compress (size, H.265): hevc within 0.2 MB +15%", i?.video == "hevc" && len in 1..(0.2 * 1048576 * 1.15).toLong(), "${i?.video} $len")
        c.check("compress (size, H.265): stats files removed", AppDirs.cache.listFiles { f -> f.name.startsWith("ffmpeg2pass") }.isNullOrEmpty())
    }
    println("\n-- convert with a resolution (one encode)")
    run(ts, false, FlowStep("video.convert", to + mapOf("format" to "mkv", "height" to "240p", "name" to "{item.stem}_240"))).first.singleOrNull().let { p ->
        val i = probe(p); c.check("convert + height 240p: 426x240 h264 mkv", i?.width == 426 && i.height == 240 && i.video == "h264" && p!!.endsWith("clip_240.mkv"), "${i?.width}x${i?.height} ${i?.video} $p")
    }
    run(ts, false, FlowStep("video.convert", to + mapOf("format" to "mp4", "height" to "1080", "name" to "{item.stem}_noup"))).first.singleOrNull().let { p ->
        val i = probe(p); c.check("convert + height 1080 on a 360p clip: not upscaled", i?.height == 360, "${i?.width}x${i?.height}")
    }
    // The recipe the assistant is given, run for real (minus files.selected: the test passes the file in), answering its two questions.
    println("\n-- recipe: chosen resolution and format")
    val recipe = Flow.fromJson("r", FlowActions.recipes.first().second).steps.filter { it.action != "files.selected" }
    runWith(webm, false, { q -> if ("480p" in q.options) "480p" else if ("mov" in q.options) "mov" else null }, recipe).first.singleOrNull().let { p ->
        val i = probe(p); c.check("recipe: 480p choice keeps a 360p clip, mov, named _480p", i?.height == 360 && p!!.endsWith("clip one_480p.mov"), "${i?.width}x${i?.height} $p")
    }
    println("\n-- scale 50% / fit 200x200")
    run(ts, false, FlowStep("video.scale", to + mapOf("percent" to 50))).first.singleOrNull().let { p ->
        val i = probe(p); c.check("scale 50%: 320x180, same container (ts → mp4)", i?.width == 320 && i.height == 180 && p!!.endsWith(".mp4"), "${i?.width}x${i?.height} $p")
    }
    run(webm, false, FlowStep("video.scale", to + mapOf("mode" to "fit", "width" to 200, "height" to 200))).first.singleOrNull().let { p ->
        val i = probe(p); c.check("fit 200x200: 200x112, webm stays $webmCodec", i?.width == 200 && i.height == 112 && i.video == webmCodec, "${i?.width}x${i?.height} ${i?.video}")
    }
    run(ts, false, FlowStep("video.convert", to + mapOf("format" to "webm"))).first.singleOrNull().let { p ->
        val i = probe(p); c.check("ts → webm: $webmCodec + opus (whatever this FFmpeg can encode)", i?.video == webmCodec && i.audio == "opus", "${i?.video} ${i?.audio}")
    }
    println("\n-- info, mp3, gif, custom")
    run(ts, false, FlowStep("video.info")).first.singleOrNull().let { line ->
        c.check("info: name, length, 640x360, 30 fps, h264, aac", line == "clip.ts\t0:00:04\t640x360\t30\th264\taac", line)
    }
    run(webm, false, FlowStep("video.convert", to + mapOf("format" to "mp3"))).first.singleOrNull().let { p ->
        val i = probe(p); c.check("convert to mp3: sound only", i?.audio == "mp3" && i.video == null, "${i?.video} ${i?.audio}")
    }
    run(ts, false, FlowStep("video.convert", to + mapOf("format" to "gif"))).first.singleOrNull().let { p ->
        c.check("convert to gif", p != null && File(p).readBytes().take(3).toByteArray().decodeToString() == "GIF", p)
    }
    run(webm, false, FlowStep("ffmpeg.run", to + mapOf("args" to "-i {in} -t 1.5 -c:v libx264 -an {out}", "output" to "{item.stem} cut.mp4"))).first.singleOrNull().let { p ->
        val i = probe(p); c.check("custom args: 1.5 s, no sound, path with spaces", i != null && kotlin.math.abs(i.duration - 1.5) < 0.2 && i.audio == null, "${i?.duration} ${i?.audio} $p")
    }
    println("\n-- dry run")
    val before = out.list()!!.toSet()
    run(webm, true, FlowStep("video.compress", to + mapOf("method" to "size", "sizeMB" to 1))).second.let { log ->
        c.check("dry run: logs the ffmpeg commands", log.count { "ffmpeg " in it } >= 2)
        c.check("dry run: writes nothing", out.list()!!.toSet() == before)
    }
    println("\n-- errors")
    val bogus = File(src, "not video.mp4").apply { writeText("hello") }
    run(bogus, false, FlowStep("video.convert", to + mapOf("format" to "webm"))).let { (_, log) ->
        c.check("not a video: stops with FFmpeg's message, no file left", out.list()!!.none { it.startsWith("not video") })
    }
    c.check("spec lists the video actions", FlowActions.specMarkdown().let { sp -> listOf("video.convert", "video.compress", "video.scale", "video.info", "ffmpeg.run").all { "`$it`" in sp } })
    println("\nVIDEO TEST: ${c.pass} passed, ${c.fail} failed")
}
