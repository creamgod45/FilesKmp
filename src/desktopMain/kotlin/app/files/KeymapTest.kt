package app.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * --test-keymap <dir>: keys, customizing, conflicts, and a simulated update (rename, split, removal,
 * new defaults, a settings migration), its backup and a restore. Runs only when APPDATA points inside
 * <dir>, so it never touches the person's settings.
 */
fun keymapTest(dir: File) {
    dir.mkdirs()
    if (!Prefs.dir.canonicalPath.startsWith(dir.canonicalPath)) {
        println("REFUSED: settings folder ${Prefs.dir} is not inside $dir (set APPDATA to a folder inside it)"); return
    }
    var pass = 0; var fail = 0
    fun check(name: String, ok: Boolean, info: Any? = null) {
        if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name ${info ?: ""}") }
    }

    // --- Parsing
    listOf("ctrl+shift+n" to "Ctrl+Shift+N", "Shift+Control+n" to "Ctrl+Shift+N", "Ctrl++" to "Ctrl++", "Ctrl+Shift++" to "Ctrl++",
        "Ctrl+=" to "Ctrl++", "alt+left" to "Alt+Left", "f11" to "F11", "Ctrl+`" to "Ctrl+`", "Ctrl+," to "Ctrl+,", "esc" to "Esc",
        "Ctrl+\\" to "Ctrl+\\", "Win+E" to null, "Ctrl+" to null, "" to null, "Ctrl+Alt+Shift+PgDn" to "Ctrl+Alt+Shift+PageDown",
    ).forEach { (i, o) -> check("normalize '$i'", Hotkeys.normalize(i) == o, Hotkeys.normalize(i)) }
    check("reserved Down / Shift+Down", Hotkeys.isReserved("Down") && Hotkeys.isReserved("Shift+Down"))
    check("not reserved Alt+Left / Ctrl+Home", !Hotkeys.isReserved("Alt+Left") && !Hotkeys.isReserved("Ctrl+Home"))

    // --- Defaults
    Keymap.testReset()
    val builtIn = Keymap.actions().filter { it.builtIn }
    val defConflicts = Keymap.conflicts(builtIn)
    check("no default conflicts", defConflicts.isEmpty(), defConflicts.map { it.key + "=" + it.actions.map { a -> a.id } })
    // Every key that used to be wired by hand in handleKey still does the same thing.
    listOf("Ctrl+C" to "CopyItem", "Ctrl+X" to "CutItem", "Ctrl+V" to "PasteItem", "Ctrl+A" to "SelectAll", "Ctrl+D" to "DeleteItem",
        "Delete" to "DeleteItem", "Shift+Delete" to "DeleteItemPermanently", "F2" to "Rename", "F5" to "RefreshItems", "Ctrl+R" to "RefreshItems",
        "F11" to "EnterFullScreen", "F3" to "Search", "Ctrl+F" to "Search", "Ctrl+E" to "Search", "Backspace" to "NavigateBack",
        "Alt+Left" to "NavigateBack", "Alt+Right" to "NavigateForward", "Alt+Up" to "NavigateUp", "Enter" to "OpenItem", "Alt+Enter" to "OpenProperties",
        "Ctrl+L" to "EditPath", "Alt+D" to "EditPath", "Ctrl+T" to "NewTab", "Ctrl+W" to "CloseSelectedTab", "Ctrl+F4" to "CloseSelectedTab",
        "Ctrl+Tab" to "NextTab", "Ctrl+Shift+Tab" to "PreviousTab", "Ctrl+Shift+T" to "ReopenClosedTab", "Ctrl+Shift+W" to "CloseAllTabs",
        "Ctrl+," to "OpenSettings", "Ctrl+Shift+N" to "CreateFolder", "Ctrl+Shift+C" to "CopyPath", "Ctrl+Shift+K" to "DuplicateCurrentTab",
        "Ctrl+Shift+1" to "LayoutDetails", "Ctrl+Shift+2" to "LayoutList", "Ctrl+Shift+3" to "LayoutCards", "Ctrl+Shift+4" to "LayoutGrid",
        "Ctrl+Alt+I" to "ToggleInfoPane", "Ctrl+H" to "ToggleShowHiddenItems", "Ctrl+B" to "ToggleSidebar", "Ctrl+-" to "LayoutDecreaseSize",
        "Ctrl+=" to "LayoutIncreaseSize", "Ctrl+1" to "GoToTab1", "Ctrl+9" to "GoToTab9", "Space" to "QuickLook", "F1" to "ShowShortcuts",
        "Ctrl+/" to "ShowShortcuts", "Ctrl+Z" to "RestoreItems", "Ctrl+Shift+S" to "SplitPaneVertically",
    ).forEach { (k, id) -> val got = Keymap.actionsFor(k, builtIn).map { it.id }; check("$k → $id", got == listOf(id), got) }

    // --- Customizing
    val acts = Keymap.actions()
    fun act(id: String) = acts.first { it.id == id }
    val copy = act("CopyItem")
    Keymap.add(copy, "ctrl+insert")
    check("add key", Keymap.bindings(copy) == listOf("Ctrl+C", "Ctrl+Insert") && Keymap.isModified(copy), Keymap.bindings(copy))
    check("saved to keymap.json", File(Prefs.dir, "keymap.json").readText().contains("Ctrl+Insert"))
    check("menu shows first key", TbCommands.all.first { it.id == "CopyItem" }.hotkey == "Ctrl+C")
    Keymap.remove(copy, "Ctrl+C")
    check("remove key", Keymap.bindings(copy) == listOf("Ctrl+Insert") && TbCommands.all.first { it.id == "CopyItem" }.hotkey == "Ctrl+Insert")
    Keymap.clear(copy)
    check("clear: no keys, Ctrl+C does nothing", Keymap.bindings(copy).isEmpty() && Keymap.actionsFor("Ctrl+C", acts).isEmpty() &&
        TbCommands.all.first { it.id == "CopyItem" }.hotkey == "")
    check("cleared is remembered as []", Keymap.parse(File(Prefs.dir, "keymap.json").readText())["CopyItem"] == emptyList<String>())
    Keymap.reset(copy)
    check("reset to default", Keymap.bindings(copy) == listOf("Ctrl+C") && !Keymap.isModified(copy) && "CopyItem" !in Keymap.overrides)
    Keymap.set(copy, listOf("Ctrl+Shift+Y")); Keymap.reload()
    check("reload keeps keys", Keymap.bindings(copy) == listOf("Ctrl+Shift+Y"))
    Keymap.set(copy, listOf("Ctrl+C"))
    check("setting the defaults again = not modified", "CopyItem" !in Keymap.overrides)

    // --- Conflicts and which one runs
    val theme = act("ToggleTheme"); val sidebar = act("ToggleSidebar")
    Keymap.set(theme, listOf("Ctrl+B"))
    val c = Keymap.conflicts(acts).firstOrNull { it.key == "Ctrl+B" }
    check("conflict found", c != null && c.actions.map { it.id }.toSet() == setOf("ToggleTheme", "ToggleSidebar"))
    check("your key wins over a default", c?.actions?.first()?.id == "ToggleTheme")
    val s = AppState(CoroutineScope(SupervisorJob()))
    val themeBefore = s.theme; val sideBefore = s.sidebarOpen
    Keymap.dispatch(s, "Ctrl+B")
    check("dispatch runs the winner", s.theme != themeBefore && s.sidebarOpen == sideBefore)
    s.theme = themeBefore
    Keymap.remove(sidebar, "Ctrl+B")
    check("fix: conflict gone", Keymap.conflicts(acts).none { it.key == "Ctrl+B" })
    Keymap.resetAll()
    Keymap.dispatch(s, "Ctrl+B")
    check("after reset Ctrl+B toggles the sidebar again", s.sidebarOpen != sideBefore)
    s.sidebarOpen = sideBefore
    check("unknown key not handled", !Keymap.dispatch(s, "Ctrl+Alt+Shift+F12"))
    Keymap.clear(sidebar)
    check("cleared key not handled", !Keymap.dispatch(s, "Ctrl+B") && s.sidebarOpen == sideBefore)
    Keymap.resetAll()

    // --- A simulated update: what the last version remembered, and the person's keys written then.
    val history = Keymap.History(renamed = mapOf("LegacyCopy" to "CopyItem"), split = mapOf("LegacyView" to listOf("LayoutDetails", "LayoutList")))
    val (curDefaults, curLabels) = Keymap.snapshot(acts)
    val oldDefaults = curDefaults.toMutableMap().apply {
        remove("CopyItem"); remove("LayoutDetails"); remove("LayoutList"); remove("OpenFlows")
        put("LegacyCopy", listOf("Ctrl+C")); put("LegacyView", listOf("Ctrl+Shift+1"))
        put("RefreshItems", listOf("F5")); put("SelectAll", listOf("Ctrl+Shift+A")); put("GoneCommand", listOf("Ctrl+Alt+G"))
    }
    val old = VersionState("1.0.0", oldDefaults, curLabels + mapOf("LegacyCopy" to "Copy (old)", "LegacyView" to "View", "GoneCommand" to "Gone"),
        mapOf("showHidden" to "true", "sortBy" to "Size", "theme" to "System"), emptySet())
    val oldOverrides = mapOf("LegacyCopy" to listOf("Ctrl+Insert"), "LegacyView" to listOf("Ctrl+Alt+L"), "SelectAll" to listOf("Ctrl+Alt+A"),
        "GoneCommand" to listOf("Ctrl+Alt+J"), "ToggleTheme" to listOf("Ctrl+Alt+S"))
    val migration = SettingMigration("test-rename-oldKey", { "oldKey → newKey" }) { p -> if ("oldKey" in p) mapOf("newKey" to p["oldKey"], "oldKey" to null) else emptyMap() }
    val out = Updates.compare(old, acts, oldOverrides, mapOf("sortBy" to "Type", "oldKey" to "42"),
        mapOf("showHidden" to "false", "sortBy" to "Name", "theme" to "System"), history, listOf(migration))
    fun kinds(k: ChangeKind) = out.items.filter { it.kind == k }
    check("renamed: keys follow", out.overrides["CopyItem"] == listOf("Ctrl+Insert") && "LegacyCopy" !in out.overrides && kinds(ChangeKind.Renamed).size == 1)
    check("split: keys go to the first part", out.overrides["LayoutDetails"] == listOf("Ctrl+Alt+L") && "LayoutList" !in out.overrides && kinds(ChangeKind.Split).size == 1,
        kinds(ChangeKind.Split).map { it.detail })
    check("default changed (not customized)", kinds(ChangeKind.DefaultChanged).any { it.target == "RefreshItems" && it.keys == listOf("F5", "Ctrl+R") })
    check("default changed but yours kept", kinds(ChangeKind.DefaultKept).any { it.target == "SelectAll" } && out.overrides["SelectAll"] == listOf("Ctrl+Alt+A"))
    check("removed command: its key is freed", kinds(ChangeKind.Removed).any { it.keys == listOf("Ctrl+Alt+J") } && "GoneCommand" !in out.overrides)
    check("new command listed", kinds(ChangeKind.NewKey).any { it.target == "OpenFlows" } && kinds(ChangeKind.NewKey).none { it.target == "CopyItem" || it.target == "LayoutList" })
    check("new conflict from the update", kinds(ChangeKind.Conflict).map { it.keys }.flatten() == listOf("Ctrl+Alt+S"), kinds(ChangeKind.Conflict).map { it.title })
    check("settings migration", out.settings == mapOf("newKey" to "42", "oldKey" to null) && "test-rename-oldKey" in out.applied && kinds(ChangeKind.Migrated).size == 1)
    check("setting default changed", kinds(ChangeKind.SettingChanged).size == 1 && kinds(ChangeKind.SettingKept).size == 1, out.items.map { it.kind.name + ":" + it.title })
    out.items.forEach { println("   [${it.kind.text}] ${it.title} — ${it.detail}") }

    // --- The same, through the files: version.json, keymap.json and settings.properties as the old version left them.
    File(Prefs.dir, "version.json").writeText(old.toJson())
    File(Prefs.dir, "keymap.json").writeText(Keymap.toJson(oldOverrides))
    Prefs.put(mapOf("oldKey" to "42", "sortBy" to "Type"))
    Keymap.history = history; Updates.migrations = listOf(migration)
    Keymap.reload()
    check("load applies renames quietly", Keymap.overrides["CopyItem"] == listOf("Ctrl+Insert"))
    val report = Updates.onStart(s)
    check("update detected", report != null && report.from == "1.0.0" && report.to == AppInfo.version && !report.fresh, report?.from)
    val backup = report?.backup?.let { File(it) }
    check("backup made before changes", backup != null && File(backup, "keymap.json").readText().contains("LegacyCopy") && File(backup, "settings.properties").exists())
    check("keymap.json migrated", Keymap.loadRaw()["CopyItem"] == listOf("Ctrl+Insert") && "LegacyCopy" !in Keymap.loadRaw() && "GoneCommand" !in Keymap.loadRaw())
    check("settings migrated", Prefs.raw("newKey") == "42" && Prefs.raw("oldKey") == null)
    val vs = VersionState.parse(File(Prefs.dir, "version.json").readText())
    check("version.json updated", vs?.version == AppInfo.version && "test-rename-oldKey" in vs.applied && vs.keyDefaults == Keymap.snapshot().first)
    val again = Updates.last()
    check("report saved and readable", again != null && again.items.size == report!!.items.size && again.items.map { it.kind } == report.items.map { it.kind })
    check("second start: nothing to report", Updates.onStart(s) == null)

    // --- Restore the backup
    Keymap.set(act("SelectAll"), listOf("Ctrl+Alt+Q"))
    File(Prefs.dir, "made-after-update.txt").writeText("x")
    val before = backup?.let { SettingsBackup.restore(it) }
    check("restore: old keymap back", File(Prefs.dir, "keymap.json").readText() == File(backup!!, "keymap.json").readText())
    check("restore: version.json kept (no second migration)", VersionState.parse(File(Prefs.dir, "version.json").readText())?.version == AppInfo.version)
    check("restore: current settings saved first", before != null && File(before, "keymap.json").readText().contains("Ctrl+Alt+Q") && File(before, "made-after-update.txt").exists())
    check("restore: newer file moved out", !File(Prefs.dir, "made-after-update.txt").exists())
    check("restore: nothing more written until restart", Prefs.readOnly)
    check("restored keys load with renames", Keymap.applyHistory(Keymap.loadRaw())["CopyItem"] == listOf("Ctrl+Insert"))

    println("\nKEYMAP TEST: $pass passed, $fail failed")
}

/** --test-update-fresh <dir>: a first run with no settings shows the introduction once. Run twice with a new APPDATA. */
fun updateFreshTest(dir: File) {
    if (!Prefs.dir.canonicalPath.startsWith(dir.canonicalPath)) { println("REFUSED"); return }
    val s = AppState(CoroutineScope(SupervisorJob()))
    val r = Updates.onStart(s)
    println("existedAtStart=${Prefs.existedAtStart} report=${r?.let { "fresh=${it.fresh} to=${it.to} items=${it.items.size}" }} versionJson=${File(Prefs.dir, "version.json").exists()}")
}

/** Screenshots only (read-only): a report like a real update would make, with a key conflict to fix. */
fun demoUpdateReport(): UpdateReport {
    val acts = Keymap.actions()
    val (cur, labels) = Keymap.snapshot(acts)
    val old = VersionState("1.0.0", cur.toMutableMap().apply {
        remove("CopyItem"); remove("LayoutDetails"); remove("LayoutList"); remove("OpenFlows")
        put("LegacyCopy", listOf("Ctrl+C")); put("LegacyView", listOf("Ctrl+Shift+1")); put("RefreshItems", listOf("F5")); put("SelectAll", listOf("Ctrl+Shift+A"))
    }, labels + mapOf("LegacyCopy" to tr("Copy item", "複製項目"), "LegacyView" to tr("View", "檢視")), mapOf("showHidden" to "true"), emptySet())
    val overrides = mapOf("LegacyCopy" to listOf("Ctrl+Insert"), "LegacyView" to listOf("Ctrl+Alt+L"), "SelectAll" to listOf("Ctrl+Alt+A"), "ToggleTheme" to listOf("Ctrl+Alt+S"))
    val out = Updates.compare(old, acts, overrides, emptyMap(), mapOf("showHidden" to "false"),
        Keymap.History(mapOf("LegacyCopy" to "CopyItem"), mapOf("LegacyView" to listOf("LayoutDetails", "LayoutList"))), emptyList())
    Keymap.testReset(out.overrides)
    return UpdateReport("1.0.0", AppInfo.version, false, out.items, null, "2026-11-05 09:30")
}
