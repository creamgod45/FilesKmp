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
    println("\nTOKENS TEST: ${c.pass} passed, ${c.fail} failed")
}
