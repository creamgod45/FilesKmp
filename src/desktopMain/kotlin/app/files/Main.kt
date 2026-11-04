package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Cursor
import java.awt.Dimension
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot

fun main(args: Array<String>) {
    AppDirs.init()
    // 錯誤報告: every uncaught error is written to %LOCALAPPDATA%\FilesKmp\Logs (this computer only).
    ErrorLog.install()
    // Tests and screenshots don't add to the person's error reports (except the error-report test itself).
    if (args.any { it.startsWith("--test") || it == "--screenshot" || it.startsWith("--bench") } && "--test-errorlog" !in args) ErrorLog.enabled = false
    // Test and screenshot runs never write settings, tags or the toolbar (session, remembered servers, reminders, …).
    if ("--test-titlebar" in args || "--screenshot" in args) Prefs.readOnly = true
    val shotIdx = args.indexOf("--screenshot")
    if (shotIdx >= 0 && shotIdx + 1 < args.size) {
        val start = args.filterIndexed { i, a -> i != shotIdx + 1 && !a.startsWith("--") && File(a).isDirectory }.firstOrNull()
        val layout = args.indexOf("--layout").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
            LayoutMode.entries.firstOrNull { it.name.equals(args[i + 1], true) }
        }
        val interact: (suspend (ImageComposeScene, AppState) -> Unit)? = when {
            "--test-checkbox" in args -> ::checkboxTest
            "--test-toolbar-drag" in args -> ::toolbarDragTest
            "--test-toolbar-delete" in args -> ::toolbarDeleteTest
            "--test-tab-drag" in args -> ::tabDragTest
            "--test-sidebar" in args -> ::sidebarTreeTest
            // --click x,y: one left click there (opens a menu or dialog for the picture).
            args.indexOf("--click") >= 0 -> args.getOrNull(args.indexOf("--click") + 1)?.split(',')?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 2 }?.let { (x, y) ->
                    { scene: ImageComposeScene, _: AppState ->
                        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                        run {
                            val at = androidx.compose.ui.geometry.Offset(x, y)
                            val P = androidx.compose.ui.input.pointer.PointerEventType
                            scene.sendPointerEvent(P.Move, at)
                            scene.sendPointerEvent(P.Press, at, buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true))
                            scene.sendPointerEvent(P.Release, at)
                        }
                    }
                }
            // --hover x,y: rest the pointer there for the picture (tooltips, hover states).
            args.indexOf("--hover") >= 0 -> args.getOrNull(args.indexOf("--hover") + 1)?.split(',')?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 2 }?.let { (x, y) ->
                    { scene: ImageComposeScene, _: AppState ->
                        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(x, y))
                    }
                }
            else -> null
        }
        screenshot(File(args[shotIdx + 1]), start, layout, "--info-pane" in args, interact) { s ->
            if ("--dual" in args) s.toggleDualPane()
            if ("--hsplit" in args) s.splitPane(PaneArrangement.Horizontal)
            if ("--sizes" in args) s.calculateFolderSizes = true
            // Any location, including ones that aren't local folders: ::network, \\server, smb://server/share.
            args.indexOf("--open").takeIf { it >= 0 && it + 1 < args.size }?.let { s.navigate(args[it + 1]) }
            args.firstOrNull { it.startsWith("--show-hidden=") }?.let { s.showHidden = it.endsWith("=1") }
            args.firstOrNull { it.startsWith("--show-system=") }?.let { s.showSystem = it.endsWith("=1") }
            if ("--customize" in args) s.customizeToolbarOpen = true
            if ("--settings" in args) s.settingsOpen = true
            if ("--settings-storage" in args) { s.settingsStorageRequest = true; s.settingsOpen = true }
            if ("--scan-cleanup" in args) s.scanCleanup()
            if ("--track-storage" in args) s.trackStorage = true
            if ("--theme-dark" in args) s.theme = ThemeMode.Dark
            if ("--analyze-profile" in args) s.analyzeProfile()
            if ("--demo-custom-toolbar" in args) demoCustomToolbar(s)
            // Preview only (read-only): opens the 預覽並清理 dialog for one clean-up target.
            args.indexOf("--plan-cleanup").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
                Cleanup.targets().firstOrNull { it.id == args[i + 1] }?.let { s.planCleanup(it) }
            }
            if ("--clone-dialog" in args) s.cloneDialogOpen = true
            if ("--empty-recycle-dialog" in args) s.confirmEmptyRecycle = true
            if ("--gradient" in args) s.gradientOn = true
            if ("--no-picture" in args) s.backgroundImageOpacity = 0f
            args.firstOrNull { it.startsWith("--columns=") }?.let { s.detailColumns = DetailCol.parse(it.substringAfter('=')) }
            // --quicklook <file>: the Space preview, once the folder has loaded.
            args.indexOf("--quicklook").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
                s.scope.launch { delay(1500.milliseconds); s.quickLook = args[i + 1] }
            }
            if ("--shortcuts" in args) s.shortcutsOpen = true
            if ("--updates-demo" in args) { s.updateReport = demoUpdateReport(); s.openUpdates() }
            if ("--welcome" in args) { s.updateReport = UpdateReport(null, AppInfo.version, true, emptyList(), null, ""); s.openUpdates() }
            if ("--keymap" in args) s.openKeymap()
            args.firstOrNull { it.startsWith("--keymap=") }?.let { s.openKeymap(it.substringAfter('=')) }
            if ("--flows" in args) s.flowsOpen = true
            args.firstOrNull { it.startsWith("--flows=") }?.let { s.flowsInitial = it.substringAfter('='); s.flowsOpen = true }
            if ("--delete-report" in args) s.deleteReport = DeleteReport(0, listOf(System.getProperty("user.home") + "\\example-folder"), emptyList())
            args.firstOrNull { it.startsWith("--search=") }?.let { q -> s.scope.launch { delay(1200.milliseconds); s.searchDeep(q.substringAfter('=')) } }
            // --batch-rename / --edit-meta: the dialogs for every item in the folder (preview only in screenshots).
            if ("--batch-rename" in args) s.scope.launch { delay(1500.milliseconds); s.batchRenameFor = s.tab.raw.filter { !it.isDir }.map { it.path } }
            if ("--edit-meta" in args) s.scope.launch { delay(1500.milliseconds); s.metadataFor = s.tab.raw.filter { !it.isDir }.map { it.path } }
            args.firstOrNull { it.startsWith("--shadow=") }?.let { a -> ShadowLevel.entries.firstOrNull { it.name.equals(a.substringAfter('='), true) }?.let { s.cardShadow = it } }
            // Git patch dialogs (they wait for the folder's repository to be found).
            if ("--git-create-patch" in args) s.scope.launch { delay(1500.milliseconds); s.gitCreatePatchOpen = true }
            args.indexOf("--git-apply-patch").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
                s.scope.launch { delay(1500.milliseconds); s.gitApplyPatchFor = args[i + 1] }
            }
            if ("--select-first" in args) s.scope.launch { delay(1200.milliseconds); s.tab.raw.firstOrNull()?.let { s.tab.selection = setOf(it.path) } }
        }
        exitProcess(0)
    }
    if ("--test-titlebar" in args) { titleBarTest(); exitProcess(0) }
    // The way NetworkFs lists a non-SMB host: the host passed as an environment variable.
    if ("--test-ps-env" in args) {
        val out = Fs.powershell("\$f = (New-Object -ComObject Shell.Application).NameSpace('\\\\' + \$env:FILESKMP_HOST); if (\$f) { \$f.Items() | ForEach-Object { \$_.Path } }",
            10_000, mapOf("FILESKMP_HOST" to "wsl.localhost"))
        println("PS ENV TEST: " + out?.lines()?.filter { it.isNotBlank() } + " valid(wsl$)=" + NetworkFs.isValidHost("wsl$") +
            " valid(evil)=" + NetworkFs.isValidHost("a‘;calc;‘"))
        exitProcess(0)
    }
    // --test-git-patch <empty dir>: commits, .patch from two of them, then --check and apply on a fresh branch.
    args.indexOf("--test-git-patch").takeIf { it >= 0 && it + 1 < args.size }?.let { gitPatchTest(File(args[it + 1])); exitProcess(0) }
    // --test-recycle <dir>: recycles a file this test creates, finds it in the bin, restores it. Touches nothing else.
    args.indexOf("--test-recycle").takeIf { it >= 0 && it + 1 < args.size }?.let {
        val f = File(args[it + 1], "fileskmp-recycle-test-${System.currentTimeMillis()}.txt").apply { parentFile.mkdirs(); writeText("restore me") }
        println("recycled=" + Fs.recycle(listOf(f.path)) + " existsAfterRecycle=" + f.exists())
        val hit = RecycleBin.list().firstOrNull { item -> RecycleBin.entry(item.path)?.originalPath.equals(f.path, true) }
        println("inBin=" + (hit != null) + " name=" + hit?.name + " from=" + hit?.typeLabel + " count=" + RecycleBin.query()?.first)
        if (hit != null) println("restoreFailures=" + RecycleBin.restore(listOf(hit.path)) + " existsAfterRestore=" + f.exists() + " text=" + f.takeIf { x -> x.exists() }?.readText())
        f.delete()
        exitProcess(0)
    }
    // --test-safe-delete <empty dir> / --test-rename <empty dir>: fixtures made and checked by the test itself.
    args.indexOf("--test-safe-delete").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
        val base = File(args[i + 1]).apply { mkdirs() }
        val outside = File(base, "outside").apply { mkdirs() }; File(outside, "keep.txt").writeText("must survive")
        val tree = File(base, "tree").apply { mkdirs() }
        File(tree, "ro.txt").apply { writeText("r"); setReadOnly() }
        var deep = File(tree, "deep"); repeat(14) { deep = File(deep, "level_${it}_xxxxxxxxxxxxxxxx") }
        java.nio.file.Files.createDirectories(deep.toPath()); java.nio.file.Files.writeString(File(deep, "deep-file.txt").toPath(), "d")
        println("deep path length=${File(deep, "deep-file.txt").path.length}")
        ProcessBuilder("cmd", "/c", "mklink", "/J", File(tree, "link").path, outside.path).redirectErrorStream(true).start().waitFor()
        val locked = File(tree, "sub/locked.txt").apply { parentFile.mkdirs(); writeText("l") }
        val ch = java.io.RandomAccessFile(locked, "rw")   // held open without delete sharing
        val r1 = SafeDelete.deleteTree(listOf(tree.path), toRecycleBin = false)
        println("pass 1: removed=${r1.removed} failures=" + r1.failures.map { File(it.path).name + " → " + it.reason })
        println("outside/keep.txt still there=${File(outside, "keep.txt").exists()} tree exists=${tree.exists()}")
        ch.close()
        val r2 = SafeDelete.deleteTree(listOf(tree.path), toRecycleBin = false)
        println("pass 2: removed=${r2.removed} failures=${r2.failures.size} tree exists=${tree.exists()} keep.txt=${File(outside, "keep.txt").exists()}")
        exitProcess(0)
    }
    args.indexOf("--test-rename").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
        Prefs.readOnly = true
        val dir = File(args[i + 1]).apply { mkdirs() }
        val names = listOf("a.txt", "b.txt", "IMG 0042.JPG", "my Report  final.docx", "CON.txt")
        names.forEach { File(dir, it).writeText(it) }
        val s = AppState(CoroutineScope(SupervisorJob()))
        val items = names.mapNotNull { FsExtra.item(File(dir, it).path) }
        val rules = listOf(
            RenameRule.Clean(), RenameRule.Replace("(\\d+)", "#$1", regex = true),
            RenameRule.ChangeCase(CaseMode.Kebab), RenameRule.ChangeCase(CaseMode.Lower, RenameTarget.Extension),
            RenameRule.Insert("{parent}_{n:00}_", at = -1),
        )
        RenameEngine.preview(s, items, rules, 1, 1).forEach { println("preview: ${it.item.name} → ${it.newName}" + (it.problem?.let { p -> "  [$p]" } ?: "")) }
        // a.txt ↔ b.txt in one batch.
        val swap = listOf(RenameRow(items[0], "b.txt", null), RenameRow(items[1], "a.txt", null))
        val (done, err) = RenameEngine.perform(swap)
        println("swap: done=${done.size} err=$err a.txt now holds '${File(dir, "a.txt").readText()}', b.txt holds '${File(dir, "b.txt").readText()}'")
        val back = done.map { (old, new) -> RenameRow(FsExtra.item(new)!!, File(old).name, null) }
        val (undone, err2) = RenameEngine.perform(back)
        println("undo: ${undone.size} err=$err2 a.txt holds '${File(dir, "a.txt").readText()}'")
        exitProcess(0)
    }
    // --test-meta <empty dir>: dates, attributes and a custom field on two files, read back.
    args.indexOf("--test-meta").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
        Prefs.readOnly = true
        val dir = File(args[i + 1]).apply { mkdirs() }
        val a = File(dir, "one.txt").apply { writeText("1") }; val b = File(dir, "two.txt").apply { writeText("2"); setReadOnly() }
        val items = listOf(a, b).mapNotNull { FsExtra.item(it.path) }
        val err = applyMetadataForTest(items, created = "2020-01-02 03:04:05", modified = "2021-06-07 08:09", hidden = true)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault())
        for (f in listOf(a, b)) {
            val at = java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.DosFileAttributes::class.java)
            println("${f.name}: created=${fmt.format(at.creationTime().toInstant())} modified=${fmt.format(at.lastModifiedTime().toInstant())} hidden=${at.isHidden} readOnly=${at.isReadOnly} err=$err")
        }
        MetaStore.addField("專案"); MetaStore.set(items.map { it.path }, "專案", "Alpha")
        println("field: " + items.map { MetaStore.get(it.path, "專案") })
        exitProcess(0)
    }
    args.indexOf("--export-flow-spec").takeIf { it >= 0 && it + 1 < args.size }?.let { File(args[it + 1]).writeText(FlowActions.specMarkdown(), Charsets.UTF_8); exitProcess(0) }
    args.indexOf("--test-flows").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
        Prefs.readOnly = true
        val dir = File(args[i + 1]).apply { mkdirs() }
        repeat(3) { n ->
            val surf = org.jetbrains.skia.Surface.makeRasterN32Premul(400 + n * 100, 300)
            surf.canvas.clear(0xFF3366CC.toInt() + n * 0x1000)
            File(dir, "pic$n.png").writeBytes(surf.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        }
        File(dir, "a.txt").writeText("hello world"); File(dir, "b.txt").writeText("hello there")
        // 1. Every sample survives JSON → Flow → JSON unchanged.
        FlowStore.samples().forEach { f -> val j = f.toJson(); println("roundtrip ${f.id}: " + (Flow.fromJson(f.id, j).toJson() == j)) }
        val s = AppState(CoroutineScope(SupervisorJob()))
        s.navigate(dir.path)
        suspend fun run(f: Flow, dry: Boolean, params: Map<String, String> = emptyMap()): List<String> {
            val r = FlowRun(s, f, emptyList(), params, dry, { null }, { println("   log: $it") })
            r.runSteps(f.steps); return r.items
        }
        runBlocking {
            val json = """{ "schema": "fileskmp.shortcut/v1", "name": "img", "steps": [
                { "action": "files.list", "args": { "folder": "${dir.path.replace("\\", "\\\\")}", "query": "ext:png" } },
                { "action": "files.sort" },
                { "action": "image.resize", "args": { "max": 64, "to": "{item.parent}\\small" } },
                { "action": "image.convert", "args": { "format": "webp", "quality": 80, "to": "{item.parent}" } },
                { "action": "file.rename", "args": { "name": "thumb_{item.index:00}.{item.ext}" } } ] }"""
            val out = run(Flow.fromJson("t1", json), dry = false)
            println("real run: " + out.map { File(it).name + " exists=" + File(it).exists() })
            val dryDest = File(dir, "sorted")
            s.tab.selection = dir.listFiles { f -> f.name.endsWith(".png") }!!.map { it.path }.toSet()
            run(FlowStore.samples().first { it.id == "builtin:photos-by-date" }.copy(steps = FlowStore.samples().first { it.id == "builtin:photos-by-date" }.steps.dropLast(1)),
                dry = true, params = mapOf("dest" to dryDest.path)).let { println("dry run: ${it.size} planned copies, e.g. ${it.firstOrNull()}; sorted folder created=${dryDest.exists()}") }
            val each = Flow.fromJson("t2", """{ "schema": "fileskmp.shortcut/v1", "name": "each", "steps": [
                { "action": "files.list", "args": { "folder": "${dir.path.replace("\\", "\\\\")}", "query": "ext:txt" } },
                { "action": "files.sort" },
                { "action": "each", "args": { "steps": [ { "action": "file.readText" }, { "action": "text.replace", "args": { "find": "hello", "replace": "bye #{var.index}" } } ] } },
                { "action": "text.join", "args": { "separator": " | " } } ] }""")
            println("each: " + run(each, dry = false))
            val ps = Flow.fromJson("t3", """{ "schema": "fileskmp.shortcut/v1", "name": "ps", "steps": [
                { "action": "text.set", "args": { "text": "x\ny\nz" } }, { "action": "text.split" },
                { "action": "shell.run", "args": { "shell": "powershell", "script": "(${'$'}env:FILES_ITEMS -split \"`n\").Count" } } ] }""")
            println("shell.run: " + run(ps, dry = false))
            println("bad schema: " + runCatching { Flow.fromJson("x", """{"schema":"other","steps":[]}""") }.exceptionOrNull()?.message)
        }
        exitProcess(0)
    }
    // --test-flows2 <empty dir>: the typed actions — text, lists, JSON, numbers, dates, Base64, control,
    // HTTP (local server), apps / CLI, read-only Windows calls, mail (MIME + local fake SMTP), speech to a file.
    args.indexOf("--test-flows2").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
        Prefs.readOnly = true
        val dir = File(args[i + 1]).apply { mkdirs() }
        val s = AppState(CoroutineScope(SupervisorJob()))
        s.navigate(dir.path)
        fun check(name: String, got: Any?, want: Any?) = println((if (got == want) "PASS " else "FAIL ") + name + ": " + got + if (got != want) "   (want $want)" else "")
        suspend fun run(steps: String, input: List<String> = emptyList(), dry: Boolean = false, ask: suspend (FlowPrompt) -> String? = { null }): FlowRun {
            val f = Flow.fromJson("t", """{ "schema": "fileskmp.shortcut/v1", "name": "t", "steps": [ $steps ] }""")
            val r = FlowRun(s, f, input, emptyMap(), dry, ask, { if (System.getenv("FK_LOG") != null) println("   log: $it") })
            r.runSteps(f.steps); return r
        }
        fun j(p: String) = p.replace("\\", "\\\\")
        // Registry: unique ids, every sample round-trips and type-checks cleanly.
        val ids = FlowActions.all.map { it.id }
        check("actions", ids.size, ids.distinct().size); println("     ${ids.size} actions in ${FlowActions.all.map { it.category }.distinct().size} categories")
        FlowStore.samples().forEach { f ->
            val jtext = f.toJson()
            check("roundtrip ${f.id}", Flow.fromJson(f.id, jtext).toJson() == jtext, true)
            val warns = FlowActions.typeFlow(f.steps, FlowActions.startType(f.accepts)).mapNotNull { it.warning }
            check("types ${f.id}", warns, emptyList<String>())
        }
        val bad = FlowActions.typeFlow(Flow.fromJson("x", """{"schema":"fileskmp.shortcut/v1","steps":[{"action":"text.set"},{"action":"file.hash"}]}""").steps, VType.None)
        check("type warning text→files", bad[1].warning != null, true)
        runBlocking {
            // text
            check("text.find regex group", run("""{"action":"list.make","args":{"items":"id=12 x\nid=7"}},{"action":"text.find","args":{"find":"id=(\\d+)","regex":true,"group":1}}""").items, listOf("12", "7"))
            check("text.test filter startsWith", run("""{"action":"list.make","args":{"items":"apple\nbanana\navocado"}},{"action":"text.test","args":{"op":"startsWith","value":"a"}}""").items, listOf("apple", "avocado"))
            check("text.test any endsWith", run("""{"action":"list.make","args":{"items":"a.txt\nb.md"}},{"action":"text.test","args":{"op":"endsWith","value":".md","mode":"any"}}""").items, listOf("true"))
            check("text.indexOf", run("""{"action":"text.set","args":{"text":"hello world"}},{"action":"text.indexOf","args":{"find":"o","from":"last"}}""").items, listOf("8"))
            check("text.substring", run("""{"action":"text.set","args":{"text":"hello world"}},{"action":"text.substring","args":{"start":-5}}""").items, listOf("world"))
            check("text.case snake", run("""{"action":"text.set","args":{"text":"Hello Big World"}},{"action":"text.case","args":{"to":"snake"}}""").items, listOf("hello_big_world"))
            check("text.split + length", run("""{"action":"text.set","args":{"text":"a,bb,ccc"}},{"action":"text.split","args":{"separator":","}},{"action":"text.length"}""").items, listOf("1", "2", "3"))
            // lists
            check("list.sort natural", run("""{"action":"list.make","args":{"items":"file10\nfile2\nfile1"}},{"action":"list.sort"}""").items, listOf("file1", "file2", "file10"))
            check("list.item -1", run("""{"action":"list.make","args":{"items":"a\nb\nc"}},{"action":"list.item","args":{"which":"index","index":-1}}""").items, listOf("c"))
            check("list.unique + count", run("""{"action":"list.make","args":{"items":"a\nb\na"}},{"action":"list.unique"},{"action":"list.count"}""").items, listOf("2"))
            // dictionaries / JSON
            check("json.make/get", run("""{"action":"json.make","args":{"entries":"name=Ann\nage=31\ntags=[\"x\",\"y\"]"}},{"action":"json.get","args":{"path":"tags[*]"}}""").items, listOf("x", "y"))
            check("json.set nested", run("""{"action":"text.set","args":{"text":"{}"}},{"action":"json.set","args":{"path":"a.b[1]","value":"5"}},{"action":"json.decode","args":{"as":"flatten"}}""").items, listOf("a.b[0]=", "a.b[1]=5"))
            check("json.encode/decode", run("""{"action":"list.make","args":{"items":"1\ntwo\ntrue"}},{"action":"json.encode","args":{"pretty":false}},{"action":"json.decode"}""").items, listOf("1", "two", "true"))
            // numbers, dates, base64
            check("number.calc", run("""{"action":"list.make","args":{"items":"3\n4"}},{"action":"number.calc","args":{"formula":"round(sqrt({first}^2 + {last}^2) * 1.5, 1)"}}""").items, listOf("7.5"))
            check("number.stats median", run("""{"action":"list.make","args":{"items":"5\n1\n9\n3"}},{"action":"number.stats","args":{"of":"median"}}""").items, listOf("4"))
            check("time.adjust", run("""{"action":"text.set","args":{"text":"2024-01-31 10:00"}},{"action":"time.adjust","args":{"amount":1,"unit":"months","format":"yyyy-MM-dd"}}""").items, listOf("2024-02-29"))
            check("time.diff days", run("""{"action":"text.set","args":{"text":"2024-01-01"}},{"action":"time.diff","args":{"to":"2024-03-01","unit":"days"}}""").items, listOf("60"))
            check("time.format epoch", run("""{"action":"text.set","args":{"text":"0"}},{"action":"time.format","args":{"format":"yyyy"}}""").items.first().length, 4)
            check("base64 round trip", run("""{"action":"text.set","args":{"text":"中文 ✓"}},{"action":"base64.encode"},{"action":"base64.decode"}""").items, listOf("中文 ✓"))
            val bin = File(dir, "bin.dat").apply { writeBytes(ByteArray(300) { it.toByte() }) }
            val dec = run("""{"action":"base64.encode","args":{"source":"file"}},{"action":"base64.decode","args":{"to":"file","path":"${j(dir.path)}\\out.dat"}}""", listOf(bin.path)).items
            check("base64 file round trip", File(dec.first()).readBytes().contentEquals(bin.readBytes()), true)
            // control: input sources, saveAs, if, repeat, flow.run, wait
            check("input var + saveAs", run("""{"action":"list.make","args":{"items":"a\nb"},"saveAs":"letters"},{"action":"text.set","args":{"text":"x"}},{"action":"list.count","input":"var:letters"}""").items, listOf("2"))
            check("input shortcut", run("""{"action":"text.set","args":{"text":"x"}},{"action":"list.count","input":"shortcut"}""", listOf("p", "q", "r")).items, listOf("3"))
            check("if else", run("""{"action":"text.set","args":{"text":"5"}},{"action":"if","args":{"op":">","value":"10","then":[{"action":"text.set","args":{"text":"big"}}],"else":[{"action":"text.set","args":{"text":"small"}}]}}""").items, listOf("small"))
            check("repeat", run("""{"action":"text.set","args":{"text":"x"}},{"action":"repeat","args":{"times":3,"steps":[{"action":"text.template","args":{"template":"{item}{var.index}"}}]}}""").items, listOf("x1", "x2", "x3"))
            FlowStore.testFlows = listOf(Flow.fromJson("child", """{"schema":"fileskmp.shortcut/v1","name":"Double","params":[{"id":"k","label":"k","default":"2"}],"steps":[{"action":"number.calc","args":{"formula":"{item} * {param.k}","perItem":true}}]}"""))
            check("flow.run with params", run("""{"action":"list.make","args":{"items":"1\n2"}},{"action":"flow.run","args":{"shortcut":"Double","params":"k=10"}}""").items, listOf("10", "20"))
            FlowStore.testFlows = listOf(Flow.fromJson("loop", """{"schema":"fileskmp.shortcut/v1","name":"Loop","steps":[{"action":"flow.run","args":{"shortcut":"loop"}}]}"""))
            check("flow.run depth limit", runCatching { run("""{"action":"flow.run","args":{"shortcut":"loop"}}""") }.exceptionOrNull()?.message?.contains("8"), true)
            FlowStore.testFlows = emptyList()
            val t0 = System.nanoTime()
            check("wait.skippable skipped", run("""{"action":"wait.skippable","args":{"seconds":30}},{"action":"var.get","args":{"name":"skipped"}}""", ask = { "skip" }).items, listOf("true"))
            check("wait 0.2 s", run("""{"action":"wait","args":{"seconds":0.2}}""").let { (System.nanoTime() - t0) / 1_000_000 in 150..5000 }, true)
            // files
            check("file.create + exists + detail", run("""{"action":"file.create","args":{"path":"${j(dir.path)}\\n.txt","content":"a\nb\nc"}},{"action":"file.detail","args":{"detail":"lines"}}""").items, listOf("3"))
            check("file.get missing filtered", run("""{"action":"text.set","args":{"text":"${j(dir.path)}\\nope.txt"}},{"action":"file.get"}""").items, emptyList<String>())
            // HTTP against a local server
            val srv = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            srv.createContext("/json") { ex -> val b = """{"repo":{"stars":42,"tags":["a","b"]}}""".toByteArray(); ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            srv.createContext("/echo") { ex -> val got = ex.requestBody.readAllBytes(); val b = ("${ex.requestMethod} ${ex.requestHeaders.getFirst("Content-Type")?.substringBefore(';')} ${got.size} " + String(got).contains("filename=\"bin.dat\"")).toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            srv.createContext("/missing") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
            srv.start()
            val base = "http://127.0.0.1:${srv.address.port}"
            check("http json + json.get", run("""{"action":"http.request","args":{"url":"$base/json","output":"json"}},{"action":"json.get","args":{"path":"repo.stars"}}""").items, listOf("42"))
            check("http multipart upload", run("""{"action":"http.request","args":{"method":"POST","url":"$base/echo","bodyType":"multipart","body":"note=hi"}}""", listOf(bin.path)).items.first().let { it.startsWith("POST multipart/form-data") && it.endsWith("true") }, true)
            check("http status 404 (no stop)", run("""{"action":"http.request","args":{"url":"$base/missing","output":"status","failOnError":false}}""").items, listOf("404"))
            check("http 404 stops", runCatching { run("""{"action":"http.request","args":{"url":"$base/missing"}}""") }.exceptionOrNull() is FlowStopped, true)
            check("http download", run("""{"action":"http.request","args":{"url":"$base/json","output":"file","saveTo":"${j(dir.path)}\\"}}""").items.map { File(it).name to File(it).length() }, listOf("json" to 38L))
            check("http POST skipped in dry run", run("""{"action":"http.request","args":{"method":"POST","url":"$base/echo","output":"status"}}""", dry = true).items, emptyList<String>())
            srv.stop(0)
            // apps
            var t = System.nanoTime()
            val cat = WinApps.catalog(refresh = true)
            println("     app catalog: ${cat.size} apps (" + cat.groupBy { it.kind }.map { "${it.key} ${it.value.size}" }.joinToString(", ") + ") in ${(System.nanoTime() - t) / 1_000_000} ms")
            check("catalog has notepad", WinApps.search("notepad").isNotEmpty(), true)
            check("subsystem where.exe", WinApps.subsystem(WinApps.onPath("where")!!), "console")
            check("subsystem notepad.exe", WinApps.subsystem(WinApps.onPath("notepad")!!), "gui")
            val opts = WinApps.parseOptions(WinApps.cliHelp("where"))
            println("     where /? options: " + opts.joinToString(" ") { it.name })
            check("cli options found", opts.size >= 3, true)
            check("gui app refused for cli", runCatching { WinApps.cliHelp("notepad") }.exceptionOrNull()?.message?.isNotEmpty(), true)
            check("app.launch capture", run("""{"action":"app.launch","args":{"app":"where","args":"cmd.exe","output":"output"}}""").items.any { it.endsWith("cmd.exe", true) }, true)
            val bat = File(dir, "echo arg.cmd").apply { writeText("@echo off\r\necho [%1]\r\n") }
            val marker = File(dir, "pwned.txt")
            check("batch arg with & stays text", run("""{"action":"app.launch","args":{"app":"${j(bat.path)}","args":"\"a&echo x>${j(marker.path)}\"","output":"output"}}""").items to marker.exists(),
                listOf("[\"a&echo x>${marker.path}\"]") to false)
            check("batch arg with % refused", runCatching { run("""{"action":"app.launch","args":{"app":"${j(bat.path)}","args":"%PATH%","output":"output"}}""") }.isFailure, true)
            check("app.running explorer", run("""{"action":"app.running","args":{"name":"explorer"}}""").items, listOf("true"))
            check("app.launch dry run", run("""{"action":"app.launch","args":{"app":"notepad"}}""", dry = true).items, emptyList<String>())
            // Windows, read only
            t = System.nanoTime()
            val vol = WinSys.volume()
            println("     volume: ${vol.first}% muted=${vol.second} (${(System.nanoTime() - t) / 1_000_000} ms)")
            check("volume in range", vol.first in 0..100, true)
            check("volume action get", run("""{"action":"volume"}""").items, listOf(vol.first.toString()))
            check("volume set skipped in dry run", run("""{"action":"volume","args":{"op":"set","value":3}}""", dry = true).items, listOf(vol.first.toString()))
            println("     battery: " + run("""{"action":"battery","args":{"get":"all"}}""").items.first().replace("\n", " "))
            println("     banners off (dnd): " + run("""{"action":"dnd"}""").items)
            val shot = run("""{"action":"screenshot","args":{"to":"${j(dir.path)}\\shot.png"}}""").items.first()
            val img = javax.imageio.ImageIO.read(File(shot))
            println("     screenshot ${img.width}x${img.height}, ${File(shot).length() / 1024} KiB")
            check("screenshot written", img.width > 100, true)
            check("dpapi round trip", WinSys.unprotect(WinSys.protect("p@ss 密碼")), "p@ss 密碼")
            // speech to a wav file (silent), sound through both players (a silent wav)
            val wav = run("""{"action":"speak","args":{"text":"FilesKmp","saveTo":"${j(dir.path)}\\speech.wav"}}""").items.first()
            check("speak to wav", File(wav).length() > 1000, true)
            val silent = File(dir, "silent.wav").apply {
                val pcm = ByteArray(8000); val o = java.io.ByteArrayOutputStream()
                javax.sound.sampled.AudioSystem.write(javax.sound.sampled.AudioInputStream(pcm.inputStream(), javax.sound.sampled.AudioFormat(8000f, 8, 1, true, false), pcm.size.toLong()), javax.sound.sampled.AudioFileFormat.Type.WAVE, o)
                writeBytes(o.toByteArray())
            }
            t = System.nanoTime(); WinSys.play(silent.path, wait = true); println("     PlaySound wav: ${(System.nanoTime() - t) / 1_000_000} ms")
            val mp = silent.copyTo(File(dir, "silent-mci.wma"), true)
            println("     MCI: " + runCatching { WinSys.play(mp.path, wait = true); "ok" }.getOrElse { it.message })
            // mail: MIME and a local fake SMTP server
            val attach = File(dir, "報告 1.txt").apply { writeText("hello") }
            val mime = String(Mailer.mime(Mailer.Mail("me@x.test", listOf("you@x.test"), emptyList(), emptyList(), "主旨 ✓", "內文", false, listOf(attach)), draft = true, includeBcc = true))
            check("mime draft", mime.contains("X-Unsent: 1") && mime.contains("filename*=UTF-8''%E5%A0%B1%E5%91%8A%201.txt") && mime.contains("Subject: =?UTF-8?B?"), true)
            val got = java.util.concurrent.CompletableFuture<String>()
            val ss = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
            Thread {
                ss.accept().use { c ->
                    val r = c.getInputStream().bufferedReader(); val w = c.getOutputStream().bufferedWriter()
                    fun say(x: String) { w.write(x + "\r\n"); w.flush() }
                    say("220 fake"); val log = StringBuilder()
                    while (true) {
                        val l = r.readLine() ?: break; log.append(l).append('\n')
                        when {
                            l.startsWith("EHLO") -> { say("250-fake"); say("250 AUTH PLAIN LOGIN") }
                            l.startsWith("AUTH PLAIN") -> say("235 ok")
                            l == "DATA" -> { say("354 go"); while (true) { val d = r.readLine() ?: break; if (d == ".") break; log.append(d).append('\n') }; say("250 queued") }
                            l == "QUIT" -> { say("221 bye"); break }
                            else -> say("250 ok")
                        }
                    }
                    got.complete(log.toString())
                }
            }.start()
            Mailer.smtp(Mailer.Mail("", listOf("you@x.test"), emptyList(), listOf("hidden@x.test"), "Hi", "Body", false, listOf(attach)),
                Mailer.SmtpConfig("127.0.0.1", ss.localPort, "none", "me@x.test", "secret", "Me <me@x.test>"))
            val conv = got.get(10, java.util.concurrent.TimeUnit.SECONDS)
            check("smtp conversation", listOf("MAIL FROM:<me@x.test>", "RCPT TO:<you@x.test>", "RCPT TO:<hidden@x.test>", "Content-Disposition: attachment").all { conv.contains(it) } && !conv.contains("Bcc:"), true)
            check("email dry run", run("""{"action":"email.send","args":{"to":"a@b.test","method":"smtp"}}""", listOf(attach.path), dry = true).items, listOf(attach.path))
            check("sms dry run", run("""{"action":"sms.send","args":{"to":"+886900000000","body":"hi"}}""", dry = true).items, emptyList<String>())
        }
        exitProcess(0)
    }
    // --bench-search <root>: index build, searches (index vs folder walk), save/load, live watch updates.
    args.indexOf("--bench-search").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
        val root = args[i + 1]
        fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
        var t0 = System.nanoTime()
        val idx = IndexRoot(root)
        idx.build(4, { false }) { }
        println("BENCH index build: ${idx.count} entries in ${ms(t0)} ms")
        val queries = listOf("mod7" to false, "*.json" to false, "ext:json pkg19" to false, "regex:^mod(1|2)\\.js$" to false, "pkg1999 | pkg42" to false, "mod4 !json" to false, "lib\\mod3" to false, "MOD1" to false)
        for ((q, rx) in queries) {
            val sq = SearchQuery.parse(q, rx, false)!!
            t0 = System.nanoTime(); val a = idx.search(sq, false, root, 1_000_000) { false }; val ia = ms(t0)
            t0 = System.nanoTime(); var b = 0; FsExtra.search(root, sq, false, 1_000_000, { false }) { b += it.size }; val wb = ms(t0)
            println("BENCH query [$q]: index ${a.size} hits in $ia ms; walk $b hits in $wb ms")
        }
        val f = File(AppDirs.cache, "bench-index.bin")
        t0 = System.nanoTime(); idx.save(f); println("BENCH save ${f.length() / 1024} KiB in ${ms(t0)} ms")
        t0 = System.nanoTime(); val loaded = IndexRoot.load(f)!!; println("BENCH load ${loaded.count} entries in ${ms(t0)} ms; same hits=" +
            (loaded.search(SearchQuery.parse("*.json")!!, false, root, 1_000_000) { false }.size == idx.search(SearchQuery.parse("*.json")!!, false, root, 1_000_000) { false }.size))
        f.delete()
        // Live updates through the watcher.
        SearchIndex.want(listOf(root)) { _, _, _ -> }
        while (SearchIndex.get(root) == null || SearchIndex.get(root)!!.builtAt == 0L) Thread.sleep(50)
        Thread.sleep(1500)
        val probe = File(root, "zz_watch_probe_${System.currentTimeMillis()}.txt")
        probe.writeText("x"); Thread.sleep(1500)
        val seen = SearchIndex.get(root)!!.search(SearchQuery.parse(probe.name)!!, false, root, 10) { false }.size
        probe.delete(); Thread.sleep(1500)
        val gone = SearchIndex.get(root)!!.search(SearchQuery.parse(probe.name)!!, false, root, 10) { false }.size
        println("BENCH watch: created seen=$seen (expect 1), deleted seen=$gone (expect 0)")
        exitProcess(0)
    }
    // --bench-list <dir>: how long listing, first chunk and sorting a big folder take.
    args.indexOf("--bench-list").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
        val dir = args[i + 1]
        fun timeIt(label: String, attrs: (java.nio.file.Path) -> Unit) {
            val t0 = System.nanoTime(); var n = 0
            java.nio.file.Files.newDirectoryStream(java.nio.file.Paths.get(dir)).use { st -> for (p in st) { attrs(p); n++ } }
            println("BENCH $label: $n in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        timeIt("names only") { }
        timeIt("basic") { java.nio.file.Files.readAttributes(it, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
        timeIt("dos") { java.nio.file.Files.readAttributes(it, java.nio.file.attribute.DosFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
        timeIt("native") { }
        val t0 = System.nanoTime()
        val nat = FastDir.list(dir)
        println("BENCH FastDir: ${nat?.size} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        repeat(2) { round ->
            var t0 = System.nanoTime()
            val all = Fs.list(dir)
            val listMs = (System.nanoTime() - t0) / 1_000_000
            t0 = System.nanoTime()
            var firstMs = -1L; var chunks = 0; var n = 0
            Fs.listChunked(dir, { false }) { chunk -> if (firstMs < 0) firstMs = (System.nanoTime() - t0) / 1_000_000; chunks++; n += chunk.size }
            val chunkedMs = (System.nanoTime() - t0) / 1_000_000
            t0 = System.nanoTime()
            Fs.sort(all, SortBy.Name, true)
            val sortMs = (System.nanoTime() - t0) / 1_000_000
            println("BENCH round $round: ${all.size} items, list ${listMs} ms; chunked ${chunkedMs} ms, first chunk after ${firstMs} ms, $chunks chunks ($n items); sort ${sortMs} ms")
        }
        exitProcess(0)
    }
    if ("--test-custom-persist" in args) { customPersistTest(write = "write" in args); exitProcess(0) }
    args.indexOf("--test-shell-menu").takeIf { it >= 0 && it + 1 < args.size }?.let { shellMenuTest(File(args[it + 1])); exitProcess(0) }
    args.indexOf("--test-errorlog").takeIf { it >= 0 && it + 1 < args.size }?.let { errorLogTest(File(args[it + 1])); exitProcess(0) }
    args.indexOf("--test-background").takeIf { it >= 0 && it + 1 < args.size }?.let { backgroundTest(File(args[it + 1])); exitProcess(0) }
    args.indexOf("--test-sha-flow").takeIf { it >= 0 && it + 2 < args.size }?.let { shaFlowTest(File(args[it + 1]), File(args[it + 2])); exitProcess(0) }
    if ("--test-tokens" in args) { tokensTest(); exitProcess(0) }
    args.indexOf("--test-keymap").takeIf { it >= 0 && it + 1 < args.size }?.let { keymapTest(File(args[it + 1])); exitProcess(0) }
    args.indexOf("--test-update-fresh").takeIf { it >= 0 && it + 1 < args.size }?.let { updateFreshTest(File(args[it + 1])); exitProcess(0) }
    args.indexOf("--test-custom-command").takeIf { it >= 0 && it + 1 < args.size }?.let { customCommandTest(File(args[it + 1])); exitProcess(0) }
    args.indexOf("--test-safe-cleaner").takeIf { it >= 0 && it + 1 < args.size }?.let { safeCleanerTest(File(args[it + 1])); exitProcess(0) }
    val benchIdx = args.indexOf("--bench-copy")
    if (benchIdx >= 0 && benchIdx + 1 < args.size) {
        Bench.run(args.drop(benchIdx + 1))
        exitProcess(0)
    }
    val startPath = args.firstOrNull { !it.startsWith("--") && File(it).isDirectory }
    val dark = systemUsesDarkTheme()
    val accent = systemAccent()
    // 半透明視窗: decided once per run, because a window is created see-through or not.
    val translucent = Prefs.bool("translucentWindow", false)
    val appIcon = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.svg")
        ?.use { loadSvgPainter(it, Density(1f)) }
    application {
        val scope = rememberCoroutineScope()
        val state = remember {
            AppState(scope).also { s ->
                s.translucentActive = translucent; startPath?.let { s.navigate(File(it).absolutePath) }
                // After an update (or on a new install): carry settings over and show 更新資訊 (Updates.kt).
                runCatching { Updates.onStart(s) }.onFailure { ErrorLog.record(it, "update check at start") }.getOrNull()?.let { r -> s.updateReport = r; s.openUpdates() }
                if (ErrorLog.takeLastCrash() != null) s.toast = tr("Files closed unexpectedly last time. The error was recorded in Settings › Error reports.",
                    "Files 上次意外結束，錯誤已記錄在「設定 › 錯誤報告」。")
                // The Windows 11 menu's app commands, read in the background for the first right-click.
                if (s.shellMenuMode == ShellMenuMode.Auto || s.shellMenuMode == ShellMenuMode.Modern) ModernHandlers.preload()
            }
        }
        // An error while drawing the window ends the app: record it first.
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        CompositionLocalProvider(androidx.compose.ui.window.LocalWindowExceptionHandlerFactory provides androidx.compose.ui.window.WindowExceptionHandlerFactory {
            androidx.compose.ui.window.WindowExceptionHandler { e -> ErrorLog.record(e, "window", fatal = true); throw e }
        }) {
        val windowState = rememberWindowState(size = DpSize(M.WINDOW_W.dp, M.WINDOW_H.dp), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Files",
            icon = appIcon,
            undecorated = true,
            transparent = translucent,
            resizable = true,
            onPreviewKeyEvent = { handleKey(state, it) },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(M.WINDOW_MIN_W, M.WINDOW_MIN_H)
                WindowCorners.round(window)
                if (translucent) {
                    state.applyWindowBlur = { WindowBlur.set(window, state.blurBehind) }
                    state.applyWindowBlur?.invoke()
                }
                ShellMenu.ownerHwnd = WindowCorners.hwnd(window) ?: 0L
                // Back from the taskbar: the background may change (設定 › 外觀 › 視窗從最小化還原時).
                launch {
                    var wasMinimized = windowState.isMinimized
                    snapshotFlow { windowState.isMinimized }.collect { m -> if (wasMinimized && !m) state.onWindowRestored(); wasMinimized = m }
                }
                // "全螢幕" (F11) toggles between full screen and the normal window.
                state.toggleFullScreen = {
                    windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                }
            }
            val controls = remember {
                WindowControls(
                    state = windowState,
                    minimize = { windowState.isMinimized = true },
                    close = ::exitApplication,
                    drag = @Composable { m, c -> TitleDragArea(window, windowState, m, c) },
                )
            }
            CompositionLocalProvider(LocalWindowControls provides controls) {
                // A see-through window gets no rounded frame from DWM: round the content itself.
                val rounded = translucent && windowState.placement == WindowPlacement.Floating
                Box(Modifier.fillMaxSize().then(if (rounded) Modifier.clip(RoundedCornerShape(8.dp)) else Modifier)) {
                    Root(state, dark, accent)
                    if (windowState.placement != WindowPlacement.Maximized) ResizeEdges(windowState)
                }
            }
        }
        }
    }
}

/**
 * The empty part of the tab strip, behaving like a Windows title bar: drag to move the window,
 * double-click to maximize and again to restore (Windows' double-click speed), drag a maximized
 * window to restore it under the pointer, and let go at the top of the screen to maximize.
 */
@Composable
fun TitleDragArea(window: java.awt.Window, ws: WindowState, modifier: Modifier, content: @Composable () -> Unit) {
    val lastPress = remember { longArrayOf(0L) }
    fun toggleMaximized() {
        ws.placement = if (ws.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
    }
    Box(modifier.pointerInput(Unit) {
        val doubleClickMs = (java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int ?: 500).toLong()
        awaitEachGesture {
            val down = awaitFirstDown()
            if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            if (down.uptimeMillis - lastPress[0] <= doubleClickMs) {
                lastPress[0] = 0L
                toggleMaximized()
                return@awaitEachGesture
            }
            lastPress[0] = down.uptimeMillis
            val start = java.awt.MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            var origin = window.location
            var moving = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                if (!change.pressed) break
                val now = java.awt.MouseInfo.getPointerInfo()?.location ?: continue
                if (!moving) {
                    if (kotlin.math.abs(now.x - start.x) + kotlin.math.abs(now.y - start.y) < 4) continue
                    moving = true
                    lastPress[0] = 0L
                    if (ws.placement == WindowPlacement.Maximized) {
                        // Restore, keeping the pointer at the same place across the title bar.
                        val ratio = (start.x - window.x).toDouble() / window.width.coerceAtLeast(1)
                        val floatingWidth = ws.size.width.value
                        ws.placement = WindowPlacement.Floating
                        origin = java.awt.Point((start.x - ratio * floatingWidth).toInt(), window.y)
                        ws.position = WindowPosition(origin.x.dp, origin.y.dp)
                    }
                }
                window.setLocation(origin.x + now.x - start.x, origin.y + now.y - start.y)
            }
            // Like Windows' snap: let go at the top edge of the screen to maximize.
            if (moving) {
                val p = java.awt.MouseInfo.getPointerInfo()?.location
                val screen = window.graphicsConfiguration?.bounds
                if (p != null && screen != null && p.y <= screen.y + 1) ws.placement = WindowPlacement.Maximized
            }
        }
    }) { content() }
}

@Composable
fun ResizeEdges(ws: WindowState) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.CenterEnd).width(5.dp).fillMaxHeight()
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); ws.size = DpSize((ws.size.width + d.x.toDp()).coerceAtLeast(M.WINDOW_MIN_W.dp), ws.size.height) } }
        )
        Box(
            Modifier.align(Alignment.BottomCenter).height(5.dp).fillMaxWidth()
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.S_RESIZE_CURSOR)))
                .pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); ws.size = DpSize(ws.size.width, (ws.size.height + d.y.toDp()).coerceAtLeast(M.WINDOW_MIN_H.dp)) } }
        )
        Box(
            Modifier.align(Alignment.BottomEnd).size(12.dp)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectDragGestures { c, d ->
                        c.consume()
                        ws.size = DpSize((ws.size.width + d.x.toDp()).coerceAtLeast(M.WINDOW_MIN_W.dp), (ws.size.height + d.y.toDp()).coerceAtLeast(M.WINDOW_MIN_H.dp))
                    }
                }
        )
    }
}

@Composable
fun Root(s: AppState, systemDark: Boolean, accent: androidx.compose.ui.graphics.Color?) {
    // Theme setting: follow Windows (the original's default), or force light / dark.
    val dark = when (s.theme) { ThemeMode.System -> systemDark; ThemeMode.Light -> false; ThemeMode.Dark -> true }
    val base = if (dark) DarkPalette else LightPalette
    val palette = if (accent != null && !dark) base.copy(accent = accent) else base
    val menu = remember { mutableStateOf<MenuRequest?>(null) }
    val scheme = if (dark) darkColorScheme(surface = palette.flyout) else lightColorScheme(surface = palette.flyout, primary = palette.accent)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalPalette provides palette, LocalMenu provides menu, LocalAppState provides s) {
            Box(Modifier.fillMaxSize()) {
                BackdropLayer(s, dark)
                MainLayout(s)
                TabDragOverlay(s)
                if (s.statusCenterOpen) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = offsetDp(-8, M.TAB_STRIP_TOP_MARGIN + M.TAB_HEIGHT + M.ADDRESS_BAR_HEIGHT - 4),
                        onDismissRequest = { s.statusCenterOpen = false },
                        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
                    ) { StatusCenter(s) }
                }
                menu.value?.let { req -> ContextMenuPopup(req) { menu.value = null } }
                s.confirmDelete?.let { DeleteDialog(s, it) }
                if (s.confirmEmptyRecycle) EmptyRecycleDialog(s)
                s.tagEditorFor?.let { TagEditorDialog(s, it) }
                if (s.settingsOpen) SettingsPage(s)
                if (s.customizeToolbarOpen) CustomizeToolbarDialog(s)
                if (s.cloneDialogOpen) GitCloneDialog(s)
                if (s.gitBranchDialog) GitBranchDialog(s)
                s.gitApplyPatchFor?.let { GitApplyPatchDialog(s, it) }
                if (s.gitCreatePatchOpen) GitCreatePatchDialog(s)
                s.quickLook?.let { QuickLookDialog(s, it) }
                s.batchRenameFor?.let { BatchRenameDialog(s, it) }
                s.metadataFor?.let { MetadataDialog(s, it) }
                s.deleteReport?.let { DeleteProblemDialog(s, it) }
                if (s.flowsOpen) FlowsDialog(s)
                s.flowRun?.let { FlowRunPanel(s, it) }
                s.flowPrompt?.let { FlowPromptDialog(s, it) }
                if (s.shortcutsOpen) ShortcutsDialog(s)
                s.toast?.let { msg ->
                    LaunchedEffect(msg) { delay(2500.milliseconds); if (s.toast == msg) s.toast = null }
                    Box(Modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.shadow8().background(palette.flyout, RoundedCornerShape(8.dp)).border(1.dp, palette.controlStroke, RoundedCornerShape(8.dp)).padding(16.dp, 10.dp)) {
                            T(msg)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MainLayout(s: AppState) {
    val p = LocalPalette.current
    val t = s.tab
    val ct = s.currentTab
    val items = t.view
    Column(Modifier.fillMaxSize()) {
        TabStrip(s)
        AddressBar(s)
        Row(Modifier.weight(1f).fillMaxWidth().background(p.layer)) {
            if (s.sidebarOpen) {
                Sidebar(s, Modifier.width(s.sidebarWidth.dp))
                Splitter { s.sidebarWidth = (s.sidebarWidth + it / 1.25f).toInt().coerceIn(M.SIDEBAR_MIN, M.SIDEBAR_MAX) }
            }
            Column(Modifier.weight(1f).widthIn(min = M.CONTENT_MIN_WIDTH.dp).padding(end = 8.dp, bottom = 8.dp)) {
                // On Home the original shows no command toolbar (the page card starts under the address bar).
                if (s.showToolbar && !(t.isHome && ct.right == null)) {
                    CommandToolbar(s)
                    Spacer(Modifier.height(4.dp))
                }
                Row(Modifier.weight(1f)) {
                    PanesArea(s, ct, Modifier.weight(1f))
                    if (s.infoPaneOpen) {
                        Spacer(Modifier.width(8.dp))
                        InfoPane(s, items)
                    }
                }
            }
        }
    }
}


/**
 * One or two panes. Vertical arrangement = side by side, Horizontal = one above the other
 * (ShellPanesPage.ArrangePanes); the 8-px gap between them can be dragged to resize.
 */
@Composable
fun PanesArea(s: AppState, ct: Tab, outer: Modifier) {
    // Where a dragged tab can be dropped to split (TabDrag.kt).
    val modifier = outer.onGloballyPositioned { s.paneAreaRect = it.boundsInRoot() }
    val r = ct.right
    if (r == null) {
        PaneCard(s, ct.left, modifier, active = true)
        return
    }
    BoxWithConstraints(modifier) {
        val vertical = ct.arrangement == PaneArrangement.Vertical
        val total = if (vertical) maxWidth.value else maxHeight.value
        val first = (ct.split * (total - 8f)).coerceAtLeast(120f)
        val drag = Modifier.pointerInput(vertical, total) {
            detectDragGestures { c, d ->
                c.consume()
                val delta = (if (vertical) d.x else d.y) / density
                ct.split = ((ct.split * (total - 8f) + delta) / (total - 8f)).coerceIn(0.2f, 0.8f)
            }
        }
        if (vertical) Row(Modifier.fillMaxSize()) {
            PaneCard(s, ct.left, Modifier.width(first.dp), active = !ct.rightActive)
            Box(Modifier.width(8.dp).fillMaxHeight().pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))).then(drag))
            PaneCard(s, r, Modifier.weight(1f), active = ct.rightActive)
        } else Column(Modifier.fillMaxSize()) {
            PaneCard(s, ct.left, Modifier.height(first.dp).fillMaxWidth(), active = !ct.rightActive)
            Box(Modifier.height(8.dp).fillMaxWidth().pointerHoverIcon(PointerIcon(Cursor(Cursor.S_RESIZE_CURSOR))).then(drag))
            PaneCard(s, r, Modifier.weight(1f).fillMaxWidth(), active = ct.rightActive)
        }
    }
}

/** One pane's card: Home or a folder view plus its status bar. In dual pane the active one gets an accent outline. */
@Composable
fun PaneCard(s: AppState, pane: TabState, modifier: Modifier, active: Boolean) {
    val p = LocalPalette.current
    val dual = s.currentTab.right != null
    // Sorted and filtered off the UI thread (AppState.startViewUpdater).
    val items = pane.view
    val shape = RoundedCornerShape(M.CARD_RADIUS.dp)
    Column(
        modifier.fillMaxHeight().cardShadow(s, shape).clip(shape).background(p.fileArea, shape)
            .border(1.dp, if (dual && active) p.accent.copy(alpha = 0.6f) else p.cardStroke, shape)
    ) {
        Box(Modifier.weight(1f)) {
            when {
                pane.isHome -> HomePage(s)
                pane.isUpdates -> UpdatesPage(s)
                else -> FolderView(s, pane, items)
            }
            // Split view: each pane has its own close button floating in its top-left corner.
            if (dual) PaneCloseButton(Modifier.align(Alignment.TopStart).padding(6.dp)) { s.closePane(pane) }
        }
        if (s.showStatusBar) StatusBar(s, items, pane)
    }
}

/** The round × floating over a split pane's top-left corner; fades in fully on hover. */
@Composable
fun PaneCloseButton(modifier: Modifier, onClose: () -> Unit) {
    val p = LocalPalette.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = androidx.compose.foundation.shape.CircleShape
    Box(
        modifier.size(24.dp).softShadow(shape, elevation = 4.dp)
            .background(if (hovered) p.danger else p.flyout.copy(alpha = 0.92f), shape)
            .border(1.dp, if (hovered) p.danger else p.controlStroke, shape)
            .hoverable(src).clickable(src, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Close, S.closeThisPane, Modifier.size(12.dp),
            tint = if (hovered) androidx.compose.ui.graphics.Color.White else p.textSecondary)
    }
}

// ====================================================================== keyboard

fun handleKey(s: AppState, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed
    val shift = e.isShiftPressed
    val alt = e.isAltPressed
    // 鍵盤快速鍵 editor recording a key: the next key (not a lone modifier) is the answer; Esc cancels.
    s.keyCapture?.let { done ->
        if (Hotkeys.isModifier(e)) return true
        s.keyCapture = null
        done(if (e.key == Key.Escape && !ctrl && !alt && !shift) null else Hotkeys.specOf(e))
        return true
    }
    // 捷徑「等待（按任意鍵略過）」: any key continues.
    s.flowPrompt?.takeIf { it.kind == "wait" }?.let { q -> s.flowPrompt = null; q.answer.complete("skip"); return true }
    val editing = s.textFocus || s.editingPath || s.tab.renaming != null
    if (editing) {
        return false
    }
    // 快速預覽 has its own keys.
    if (s.quickLook != null) {
        when (e.key) {
            Key.Escape, Key.Spacebar -> s.quickLook = null
            Key.DirectionLeft, Key.DirectionUp -> s.quickLookStep(-1)
            Key.DirectionRight, Key.DirectionDown -> s.quickLookStep(1)
            Key.Enter, Key.NumPadEnter -> { val p = s.quickLook!!; s.quickLook = null; if (File(p).isDirectory) s.openFolder(p) else Fs.open(p) }
            else -> return false
        }
        return true
    }
    if (s.confirmDelete != null) {
        if (e.key == Key.Escape) { s.confirmDelete = null; return true }
        if (e.key == Key.Enter) { s.doDelete(s.confirmDelete!!, s.tab.isRecycleBin); return true }
        return false
    }
    if (s.confirmEmptyRecycle) {
        if (e.key == Key.Escape) { s.confirmEmptyRecycle = false; return true }
        if (e.key == Key.Enter) { s.emptyRecycleBin(); return true }
        return false
    }
    if (s.dialogOpen) {
        if (e.key == Key.Escape) return s.closeTopDialog()
        val key = Hotkeys.specOf(e)
        if (key != null && Keymap.actionsFor(key).firstOrNull()?.id == "ShowShortcuts") { s.shortcutsOpen = !s.shortcutsOpen; return true }
        return false
    }
    // Every key the keymap knows (the person's own keys, then the defaults; Keymap.kt).
    if (Hotkeys.dispatch(s, e)) return true
    // Moving around the list: these keys are fixed (Hotkeys.isReserved).
    when {
        ctrl || alt -> return false
        e.key == Key.MoveHome || e.key == Key.MoveEnd -> jumpSelection(s, e.key == Key.MoveEnd, shift)
        e.key == Key.DirectionLeft && s.layout == LayoutMode.Grid -> moveSelection(s, -1, shift)
        e.key == Key.DirectionRight && s.layout == LayoutMode.Grid -> moveSelection(s, 1, shift)
        e.key == Key.Escape -> if (!s.closeTopDialog()) s.clearSelection()
        e.key == Key.DirectionDown || e.key == Key.DirectionUp -> moveSelection(s, if (e.key == Key.DirectionDown) 1 else -1, shift)
        else -> return false
    }
    return true
}

private fun jumpSelection(s: AppState, end: Boolean, shift: Boolean) {
    val t = s.tab
    if (t.isHome) return
    val items = s.visibleItems(t)
    if (items.isEmpty()) return
    s.click(if (end) items.last() else items.first(), ctrl = false, shift = shift, items = items)
}

private fun moveSelection(s: AppState, delta: Int, shift: Boolean) {
    val t = s.tab
    if (t.isHome) return
    val items = s.visibleItems(t)
    if (items.isEmpty()) return
    val cur = items.indexOfLast { it.path in t.selection }
    val next = (if (cur < 0) 0 else cur + delta).coerceIn(0, items.size - 1)
    s.click(items[next], ctrl = false, shift = shift, items = items)
}

// ====================================================================== --screenshot

/**
 * Self-test of the checkbox rule with simulated mouse clicks on the Details list:
 * click row 1, tick row 2's checkbox (both stay selected), then click row 3 (only it stays).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
suspend fun checkboxTest(scene: ImageComposeScene, s: AppState) {
    val rows = s.visibleItems(s.tab)
    fun y(i: Int) = 206f + 36f * i
    // From the pane's real position (it moves with the sidebar width setting): list padding 8 + row padding 8 + half the 20-dp box.
    val checkX = s.paneAreaRect.left + 26f
    val nameX = s.paneAreaRect.left + 200f
    suspend fun click(x: Float, yy: Float) {
        val pos = androidx.compose.ui.geometry.Offset(x, yy)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, pos)
        scene.render(System.nanoTime()); delay(30.milliseconds)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, pos, buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true))
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, pos)
        scene.render(System.nanoTime()); delay(500.milliseconds)   // past the double-click window
    }
    click(nameX, y(0))
    val a = s.tab.selection.size
    click(checkX, y(1))
    val b = s.tab.selection.size
    click(nameX, y(2))
    val c = s.tab.selection.size
    click(checkX, y(3))
    val d = s.tab.selection.size
    println("CHECKBOX TEST rows=${rows.size}: click row1 -> $a selected (expect 1); tick row2 -> $b (expect 2); click row3 -> $c (expect 1); tick row4 -> $d (expect 2)")
}

/**
 * Self-test of the 自訂工具列 window: drag 剪下 by its handle to the end of the list, press 儲存,
 * print the saved order, then put the person's previous toolbar back.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
suspend fun toolbarDragTest(scene: ImageComposeScene, s: AppState) {
    val before = s.toolbarStore.layout
    val P = androidx.compose.ui.input.pointer.PointerEventType
    val down = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
    suspend fun frame() { scene.render(System.nanoTime()); delay(16.milliseconds) }
    fun at(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
    // drag the gripper of row 3 (剪下) to below the last row
    scene.sendPointerEvent(P.Move, at(546f, 352f)); frame()
    scene.sendPointerEvent(P.Press, at(546f, 352f), buttons = down); frame()
    var y = 352f
    while (y < 548f) { y += 14f; scene.sendPointerEvent(P.Move, at(548f, y), buttons = down); frame() }
    scene.sendPointerEvent(P.Release, at(548f, 548f)); frame(); frame()
    // 儲存
    scene.sendPointerEvent(P.Move, at(904f, 652f)); frame()
    scene.sendPointerEvent(P.Press, at(904f, 652f), buttons = down); frame()
    scene.sendPointerEvent(P.Release, at(904f, 652f)); frame(); frame()
    val after = s.toolbarStore.layout[TbContext.AlwaysVisible]?.map { it.id }
    println("TOOLBAR DRAG TEST saved=${!s.customizeToolbarOpen} order=$after (expect CutItem last)")
    s.toolbarStore.save(before)
}

/**
 * Screenshot-mode test for tab dragging: drag a second tab onto the right edge of the file area
 * (= split side by side), print the result, then hold a drag over the bottom edge so the picture
 * shows the 水平分割 preview.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
suspend fun tabDragTest(scene: ImageComposeScene, s: AppState) {
    val P = androidx.compose.ui.input.pointer.PointerEventType
    val down = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
    suspend fun frame() { scene.render(System.nanoTime()); delay(16.milliseconds) }
    suspend fun drag(from: androidx.compose.ui.geometry.Offset, to: androidx.compose.ui.geometry.Offset, release: Boolean) {
        scene.sendPointerEvent(P.Move, from); frame()
        scene.sendPointerEvent(P.Press, from, buttons = down); frame()
        for (k in 1..20) {
            val f = k / 20f
            scene.sendPointerEvent(P.Move, from + (to - from) * f, buttons = down); frame()
        }
        if (release) { scene.sendPointerEvent(P.Release, to); frame(); frame() }
    }
    s.newTab(System.getProperty("user.home")); s.tabIndex = 0; repeat(10) { frame() }
    val second = s.tabs.last()
    val before = s.tabs.size
    val area = s.paneAreaRect
    val from = s.tabRects[second]?.center ?: run { println("TAB DRAG TEST skipped: tab not laid out"); return }
    drag(from, androidx.compose.ui.geometry.Offset(area.right - 30f, area.center.y), release = true)
    val ct = s.currentTab
    println("TAB DRAG TEST tabs $before -> ${s.tabs.size} split=${ct.right != null} arrangement=${ct.arrangement} " +
        "left=${ct.left.location} right=${ct.right?.location} (expect one tab fewer, split=true Vertical, right = home folder)")
    repeat(10) { frame() }
    // Hold a second drag over the bottom edge for the picture.
    val held = s.tabRects[s.tabs[0]]?.center ?: return
    drag(held, androidx.compose.ui.geometry.Offset(area.center.x, area.bottom - 30f), release = false)
}

/**
 * Screenshot-mode regression test: in 自訂工具列, remove rows from the middle of 新增選項 with ×.
 * The preview toolbar below used to crash with "Index n out of bounds for length n".
 * Nothing is saved: the dialog stays open for the picture and the run then exits.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
suspend fun toolbarDeleteTest(scene: ImageComposeScene, s: AppState) {
    val P = androidx.compose.ui.input.pointer.PointerEventType
    val down = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
    suspend fun frame() { scene.render(System.nanoTime()); delay(16.milliseconds) }
    suspend fun click(x: Float, y: Float) {
        val o = androidx.compose.ui.geometry.Offset(x, y)
        scene.sendPointerEvent(P.Move, o); frame()
        scene.sendPointerEvent(P.Press, o, buttons = down); frame()
        scene.sendPointerEvent(P.Release, o); frame(); frame()
    }
    val before = s.toolbarStore.layout[TbContext.AlwaysVisible]?.size ?: 0
    // × of the third row, three times: each removal shifts the rows below it up.
    repeat(3) { click(992f, 357f) }
    frame(); frame()
    println("TOOLBAR DELETE TEST rows before=$before (no crash = pass)")
}

/**
 * Title-bar test without a visible window: TitleDragArea on a hidden frame gets timed presses.
 * Two presses within Windows' double-click time maximize, two more restore, slow ones do nothing.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun titleBarTest() = runBlocking(Dispatchers.Swing) {
    val frame = java.awt.Frame()
    val ws = WindowState(placement = WindowPlacement.Floating, size = DpSize(800.dp, 600.dp))
    val scene = ImageComposeScene(400, 60, Density(1f)) { TitleDragArea(frame, ws, Modifier.fillMaxSize()) {} }
    val P = androidx.compose.ui.input.pointer.PointerEventType
    val down = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
    val at = androidx.compose.ui.geometry.Offset(200f, 30f)
    val gap = (java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int ?: 500).toLong()
    var t = 1_000_000L
    fun click(afterMs: Long) {
        t += afterMs
        scene.sendPointerEvent(P.Press, at, buttons = down, timeMillis = t); scene.render(t * 1_000_000)
        scene.sendPointerEvent(P.Release, at, timeMillis = t + 30); scene.render((t + 30) * 1_000_000)
    }
    scene.render(0)
    click(0); click(120); val afterFirst = ws.placement
    click(gap * 4); click(120); val afterSecond = ws.placement
    click(gap * 4); click(gap * 3); val afterSlow = ws.placement
    println("TITLEBAR TEST double-click=$afterFirst, again=$afterSecond, two slow clicks=$afterSlow " +
        "(expect Maximized, Floating, Floating; double-click time $gap ms)")
    scene.close(); frame.dispose()
}

/**
 * SafeCleaner on a prepared fixture folder (see the test notes): [dir]\cache holds old.tmp, new.tmp
 * and a junction "link" to [dir]ictim; [dir]
ootlink is a junction to victim used as a root.
 * Prints what was planned and what is left.
 */
fun safeCleanerTest(dir: File) {
    val cache = File(dir, "cache"); val victim = File(dir, "victim")
    val target = CleanupTarget("test", "test", CleanupAudience.Developer, listOf(cache), "",
        safeDelete = SafeDeleteRule(listOf(cache, File(dir, "rootlink")), olderThanDays = 7))
    val plan = SafeCleaner.plan(target)
    println("SAFE CLEANER plan: files=${plan.files.map { it.fileName }} links=${plan.skippedLinks} recent=${plan.skippedRecent}")
    val (n, _) = SafeCleaner.execute(plan)
    fun list(f: File) = f.walkTopDown().filter { it != f }.map { it.relativeTo(dir).path }.sorted().toList()
    println("SAFE CLEANER removed=$n cache-left=${list(cache).filter { !it.contains("link\\") }} victim-left=${list(victim)} " +
        "rootlink-exists=${File(dir, "rootlink").exists()} link-exists=${File(cache, "link").exists()}")
}

/**
 * Runs a PowerShell and a cmd custom command in [dir] (a folder with a space and a quote in its
 * name), with two selected files; each writes what it received to out-ps.txt / out-cmd.txt.
 */
fun customCommandTest(dir: File) = runBlocking(Dispatchers.Swing) {
    Prefs.readOnly = true
    val s = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Swing))
    s.navigate(dir.path)
    val a = File(dir, "a file.txt").path; val b = File(dir, "b'q.txt").path
    s.tab.selection = setOf(a, b)
    val ps = CustomCommand("cmd:testps", "PS test", ShellKind.PowerShell,
        "function Show { \$args -join '|' }\n" +
            "\"folder=\" + (Show {folder}) + \"`nfile=\" + (Show {file}) + \"`nselected=\" + (Show {selected}) + " +
            "\"`nenv=\$env:FILES_FOLDER`npwd=\$((Get-Location).Path)\" | Set-Content -Encoding utf8 out-ps.txt", keepOpen = false)
    val cmd = CustomCommand("cmd:testcmd", "CMD test", ShellKind.Cmd,
        "echo folder={folder}> out-cmd.txt\necho file={file}>> out-cmd.txt\necho env=%FILES_FOLDER%>> out-cmd.txt\necho pwd=%CD%>> out-cmd.txt", keepOpen = false)
    TbCustom.run(s, ps); TbCustom.run(s, cmd)
    println("CUSTOM COMMAND TEST started; toast=${s.toast}")
}

/** Saving and loading the person's own toolbar items and a renamed slot (run with APPDATA pointing at a test folder). */
fun customPersistTest(write: Boolean) {
    if (write) {
        TbCustom.upsert(CustomCommand("cmd:p1", "建置 \"專案\"\ttab", ShellKind.Cmd, "echo 1\necho 2", keepOpen = false))
        TbCustom.upsert(CustomFolder("folder:p2", "我的工具", listOf("cmd:p1", "OpenTerminal")))
        ToolbarStore().save(mapOf(TbContext.AlwaysVisible to listOf(TbEntry("folder:p2", true, true), TbEntry("OpenProperties", true, true, "屬性 x"))))
        println("CUSTOM PERSIST wrote to ${Prefs.dir}")
    } else {
        val c = TbCustom.command("cmd:p1"); val f = TbCustom.folder("folder:p2")
        val l = ToolbarStore().layout[TbContext.AlwaysVisible]
        println("CUSTOM PERSIST read: cmd=${c?.name?.replace("\t", "<TAB>")}|${c?.shell}|${c?.script?.replace("\n", "<NL>")}|keepOpen=${c?.keepOpen} folder=${f?.name}${f?.children} " +
            "slots=${l?.map { "${it.id}:${it.label}" }} folderLabel=${TbCommands.get("folder:p2")?.label}")
    }
}

/** In memory only (settings are read-only in screenshot runs): a folder, a command and a renamed button. */
fun demoCustomToolbar(s: AppState) {
    val c = CustomCommand("cmd:demo1", if (S.zh) "建置專案" else "Build project", ShellKind.PowerShell, "./gradlew.bat build")
    val c2 = CustomCommand("cmd:demo2", if (S.zh) "清除暫存" else "Clean temp", ShellKind.Cmd, "del /q *.tmp")
    TbCustom.upsert(c); TbCustom.upsert(c2)
    TbCustom.upsert(CustomFolder("folder:demo", if (S.zh) "我的工具" else "My tools", listOf("cmd:demo1", "cmd:demo2", "OpenTerminal")))
    val l = s.toolbarStore.layout.toMutableMap()
    val general = (l[TbContext.AlwaysVisible] ?: emptyList()).toMutableList()
    general.add(TbEntry("folder:demo", true, true))
    general.add(TbEntry("cmd:demo1", true, true))
    val i = general.indexOfFirst { it.id == "OpenProperties" }
    if (i >= 0) general[i] = general[i].copy(label = if (S.zh) "屬性" else "Props", showLabel = true)
    l[TbContext.AlwaysVisible] = general
    s.toolbarStore.save(l)
}

fun gitPatchTest(dir: File) {
    dir.mkdirs()
    fun git(vararg a: String) = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + a)
        .directory(dir).redirectErrorStream(true).start().let { p -> val o = p.inputStream.readAllBytes().toString(Charsets.UTF_8); p.waitFor(); o.trim() }
    git("init", "-q", "-b", "main")
    File(dir, "a.txt").writeText("one\n"); git("add", "."); git("commit", "-qm", "first")
    File(dir, "a.txt").writeText("one\ntwo\n"); git("commit", "-qam", "second 中文")
    File(dir, "b.txt").writeText("bee\n"); git("add", "."); git("commit", "-qm", "third")
    val log = Git.log(dir.path)
    println("log: " + log.map { it.short + " " + it.subject })
    val picked = log.take(2).reversed().map { it.hash }   // second + third, oldest first
    val out = File(dir, "out/range.patch")
    val (r, files) = Git.formatPatch(dir.path, picked, out, perCommit = false)
    println("format ok=${r.ok} files=${files.map { it.name }} size=${out.length()} subjects=${out.readLines().filter { it.startsWith("Subject:") }}")
    val (r2, files2) = Git.formatPatch(dir.path, picked, File(dir, "out/each"), perCommit = true)
    println("per-commit ok=${r2.ok} files=${files2.map { it.name }}")
    git("checkout", "-q", "-b", "replay", log.last().hash)
    val check = Git.applyPatch(dir.path, out.path, check = true, threeWay = false)
    println("check ok=${check.ok} changed=${File(dir, "b.txt").exists()} out=${check.output.lines()}")
    val apply = Git.applyPatch(dir.path, out.path, check = false, threeWay = false)
    println("apply ok=${apply.ok} a=${File(dir, "a.txt").readText().trim().replace("\n", "|")} b=${File(dir, "b.txt").takeIf { it.exists() }?.readText()?.trim()}")
    val again = Git.applyPatch(dir.path, out.path, check = true, threeWay = false)
    println("re-check ok=${again.ok} (expected false) out=${again.output.lines().take(2)}")
}

/** Starts the app as normal (Home in one tab), draws about 120 frames, saves the picture, exits. */
fun screenshot(
    out: File, start: String? = null, layout: LayoutMode? = null, infoPane: Boolean = false,
    interact: (suspend (ImageComposeScene, AppState) -> Unit)? = null,
    setup: (AppState) -> Unit = {},
) {
    val dark = systemUsesDarkTheme()
    val accent = systemAccent()
    runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val state = withContext(Dispatchers.Swing) {
            AppState(scope).also { s ->
                layout?.let { s.layout = it }
                s.infoPaneOpen = infoPane
                start?.let { s.navigate(File(it).absolutePath) }
                setup(s)
            }
        }
        val scene = withContext(Dispatchers.Swing) {
            // fileskmp.shotHeight: a taller picture for tests of long pages.
            ImageComposeScene(M.WINDOW_W, Integer.getInteger("fileskmp.shotHeight", M.WINDOW_H), Density(1f)) { Root(state, dark, accent) }
        }
        var frame = 0
        val startNs = System.nanoTime()
        val deadline = System.currentTimeMillis() + 15_000
        while (frame < 120 || ((!(state.pinnedLoaded && state.drivesLoaded && state.recentLoaded) || state.tab.loading || Thumbnails.busy() || state.folderSizing.isNotEmpty()) && System.currentTimeMillis() < deadline)) {
            withContext(Dispatchers.Swing) { scene.render(System.nanoTime() - startNs) }
            frame++
            if (frame == 90) interact?.let { withContext(Dispatchers.Swing) { it(scene, state) } }
            delay(16.milliseconds)
        }
        // fileskmp.shotWaitMs (tests): keep drawing a while longer, e.g. for widgets that load late.
        val extraUntil = System.currentTimeMillis() + Integer.getInteger("fileskmp.shotWaitMs", 0)
        while (System.currentTimeMillis() < extraUntil) {
            withContext(Dispatchers.Swing) { scene.render(System.nanoTime() - startNs) }
            delay(16.milliseconds)
        }
        val image = withContext(Dispatchers.Swing) { scene.render(System.nanoTime() - startNs) }
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
        out.absoluteFile.parentFile?.mkdirs()
        out.writeBytes(data.bytes)
        withContext(Dispatchers.Swing) { scene.close() }
        scope.cancel()
        println("Saved ${out.absolutePath} after $frame frames (Home loaded in ${state.homeLoadMs} ms)")
    }
}
