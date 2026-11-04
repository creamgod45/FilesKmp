package app.files

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** This build's version (written into the jar by the build: fileskmp-version.txt). */
object AppInfo {
    val version: String = System.getProperty("fileskmp.version")
        ?: AppInfo::class.java.getResourceAsStream("/fileskmp-version.txt")?.use { it.readBytes().decodeToString().trim() }?.takeIf { it.isNotEmpty() }
        ?: "dev"

    /** Compares "1.2.10" with "1.10": numerically, part by part. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

/** What kind of change a report line is about (and the badge it gets). */
enum class ChangeKind(private val en: String, private val zh: String) {
    Conflict("Key conflict", "按鍵衝突"),
    Split("Split", "拆分"),
    Renamed("Renamed", "改名"),
    Removed("Removed", "移除"),
    DefaultChanged("New default key", "預設按鍵變更"),
    DefaultKept("Your key kept", "保留你的按鍵"),
    NewKey("New command", "新增指令"),
    SettingChanged("New default", "預設值變更"),
    SettingKept("Your setting kept", "保留你的設定"),
    Migrated("Moved over", "已遷移");
    val text get() = tr(en, zh)
    val isKeys get() = this in setOf(Split, Renamed, Removed, DefaultChanged, DefaultKept, NewKey)
}

/** One line of an update report. [target] is what "adjust" opens: a key or a command id in the keymap editor. */
class ReportItem(val kind: ChangeKind, val title: String, val detail: String, val keys: List<String> = emptyList(), val target: String? = null)

/** What an update changed for this person. Saved as last-update-report.json so it can be opened again. */
class UpdateReport(
    val from: String?, val to: String,
    /** First run on this computer: there was nothing to update. */
    val fresh: Boolean,
    val items: List<ReportItem>,
    /** Copy of the settings folder made before anything changed. */
    val backup: String?,
    val at: String,
) {
    /** Keys that only started to clash with this update. */
    val newConflicts: Set<String> get() = items.filter { it.kind == ChangeKind.Conflict }.flatMap { it.keys }.toSet()

    fun toJson(): String = Json.write(linkedMapOf(
        "from" to from, "to" to to, "fresh" to fresh, "backup" to backup, "at" to at,
        "items" to items.map { linkedMapOf("kind" to it.kind.name, "title" to it.title, "detail" to it.detail, "keys" to it.keys, "target" to it.target) },
    ))

    companion object {
        fun parse(text: String): UpdateReport? = runCatching {
            val m = Json.parse(text) as Map<*, *>
            UpdateReport(m["from"] as? String, m["to"] as? String ?: "", m["fresh"] as? Boolean ?: false,
                (m["items"] as? List<*>).orEmpty().mapNotNull { x ->
                    val i = x as? Map<*, *> ?: return@mapNotNull null
                    val kind = ChangeKind.entries.firstOrNull { it.name == i["kind"] } ?: return@mapNotNull null
                    ReportItem(kind, i["title"] as? String ?: "", i["detail"] as? String ?: "",
                        (i["keys"] as? List<*>).orEmpty().map { it.toString() }, i["target"] as? String)
                }, m["backup"] as? String, m["at"] as? String ?: "")
        }.getOrNull()
    }
}

/**
 * A change to settings.properties made by an update (a key renamed, a value converted).
 * [apply] gets the person's current values and returns the changes (key → new value, or null to remove).
 * Each one runs once: its [id] is remembered in version.json.
 */
class SettingMigration(val id: String, val title: () -> String, val apply: (Map<String, String>) -> Map<String, String?>)

/** What version.json remembers about the last run, to compare the next version with. */
class VersionState(
    val version: String,
    val keyDefaults: Map<String, List<String>>,
    val keyLabels: Map<String, String>,
    val settingDefaults: Map<String, String>,
    val applied: Set<String>,
) {
    fun toJson(): String = Json.write(linkedMapOf(
        "schema" to 1, "version" to version, "keymapDefaults" to keyDefaults.toSortedMap(), "keymapLabels" to keyLabels.toSortedMap(),
        "settingDefaults" to settingDefaults.toSortedMap(), "appliedMigrations" to applied.sorted(),
    ))

    companion object {
        fun parse(text: String): VersionState? = runCatching {
            val m = Json.parse(text) as Map<*, *>
            fun strMap(v: Any?) = (v as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value.toString() }
            VersionState(m["version"] as? String ?: "1.0.0",
                (m["keymapDefaults"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k.toString() to (v as? List<*>).orEmpty().map { it.toString() } },
                strMap(m["keymapLabels"]), strMap(m["settingDefaults"]),
                (m["appliedMigrations"] as? List<*>).orEmpty().map { it.toString() }.toSet())
        }.getOrNull()
    }
}

/**
 * Updates: on start, compares this version with what the last run remembered (version.json) and,
 * when something changed, backs up the settings folder, carries the person's keys and settings over,
 * and writes a report that the 更新資訊 tab shows.
 */
object Updates {
    private val stateFile get() = File(Prefs.dir, "version.json")
    private val reportFile get() = File(Prefs.dir, "last-update-report.json")

    /** Settings migrations, oldest first. Add one whenever a release renames or reshapes a setting. */
    internal var migrations: List<SettingMigration> = listOf(
        // 1.2.0: "How pictures change" was one choice (one picture / timer / each start); now each trigger is its own switch.
        SettingMigration("1.2.0-background-triggers", { tr("Background pictures: “How pictures change” became separate switches you can combine",
            "背景圖片：「切換方式」改成可以同時開啟的多個選項") }) { p ->
            when (p["bgMode"]) {
                null -> emptyMap()
                "Timer" -> mapOf("bgOnTimer" to "true", "bgMode" to null)
                "OnStartup" -> mapOf("bgOnStart" to "true", "bgMode" to null)
                else -> mapOf("bgMode" to null)
            }
        },
    )

    /** Settings people see (and whose defaults an update may change), with the names the settings page uses. */
    private val settingLabels: Map<String, () -> String> = mapOf(
        "showHidden" to { S.showHidden }, "showSystem" to { S.showSystem }, "showExtensions" to { S.fileExtensions },
        "showThumbnails" to { S.showThumbnails }, "calculateFolderSizes" to { S.calcFolderSizes }, "showCheckboxes" to { S.showCheckboxes },
        "confirmDelete" to { S.confirmDelete }, "continueSession" to { S.continueSession }, "openFoldersInNewTab" to { S.openFoldersNewTab },
        "theme" to { S.theme }, "backdrop" to { S.backdropMaterial }, "cardShadow" to { S.cardShadow }, "layout" to { S.defaultLayout },
        "sortBy" to { S.sortItemsBy }, "sortAscending" to { S.sort }, "paneArrangement" to { S.paneArrangement },
        "showToolbar" to { S.showToolbar }, "showStatusBar" to { S.showStatusBar }, "sidebarWidth" to { S.sidebarWidth },
        "translucentWindow" to { S.translucentWindow }, "w.quickAccess" to { S.quickAccess }, "w.drives" to { S.drives },
        "w.network" to { S.networkLocations }, "w.tags" to { S.tags }, "w.recent" to { S.recentFiles }, "w.storage" to { S.storageAnalysis },
        "bgOnStart" to { tr("Background: change each time Files opens", "背景：每次開啟時切換") },
        "bgOnTimer" to { tr("Background: change on a timer", "背景：定時切換") },
        "bgOnRestore" to { tr("Background: change when restored from minimized", "背景：從最小化還原時切換") },
        "bgShuffle" to { tr("Background: random order", "背景：隨機順序") },
        "shellMenu" to { tr("Windows right-click menu", "Windows 右鍵選單") },
    )

    private fun value(v: String?) = when (v) { null, "" -> tr("(empty)", "（空白）"); "true" -> tr("On", "開"); "false" -> tr("Off", "關"); else -> v }
    private fun keys(list: List<String>?) = if (list.isNullOrEmpty()) tr("no key", "無按鍵") else list.joinToString(" / ")
    private fun q(s: String) = if (S.zh) "「$s」" else "“$s”"

    /** Result of comparing two versions: the report lines, and the person's keys and settings to keep. */
    class Outcome(val items: List<ReportItem>, val overrides: Map<String, List<String>>, val settings: Map<String, String?>, val applied: Set<String>)

    /**
     * The comparison itself (no files): [old] is what the last run remembered, [overrides] the person's
     * saved keys as written then (old ids), [props] their settings. Pure, so tests and screenshots use it too.
     */
    fun compare(
        old: VersionState,
        acts: List<KeyAction>,
        overrides: Map<String, List<String>>,
        props: Map<String, String>,
        settingDefaults: Map<String, String>,
        history: Keymap.History = Keymap.history,
        migrations: List<SettingMigration> = this.migrations,
    ): Outcome {
        val items = ArrayList<ReportItem>()
        val byId = acts.associateBy { it.id }
        val builtIn = acts.filter { it.builtIn }.map { it.id }.toSet()
        fun label(id: String) = byId[id]?.label ?: old.keyLabels[id] ?: id
        val o = overrides.toMutableMap()
        val handledOld = HashSet<String>(); val handledNew = HashSet<String>()

        // Renamed commands: the person's keys follow the new name.
        history.renamed.forEach { (from, to) ->
            if (from !in old.keyDefaults && from !in overrides) return@forEach
            handledOld += from; handledNew += to
            val mine = o.remove(from)
            if (mine != null && to !in o) o[to] = mine
            items += ReportItem(ChangeKind.Renamed, tr("${q(label(from))} is now ${q(label(to))}", "${q(label(from))} 改名為 ${q(label(to))}"),
                if (mine != null) tr("Your keys moved with it: ${keys(mine)}.", "你的按鍵跟著移過去：${keys(mine)}。")
                else tr("Keys: ${keys(byId[to]?.defaults)}.", "按鍵：${keys(byId[to]?.defaults)}。"), mine ?: byId[to]?.defaults.orEmpty(), to)
        }
        // Split commands: the person's keys go to the first part; the others start with their defaults.
        history.split.forEach { (from, parts) ->
            if (from !in old.keyDefaults && from !in overrides) return@forEach
            handledOld += from; handledNew += parts
            val mine = o.remove(from)
            val first = parts.firstOrNull() ?: return@forEach
            if (mine != null && first !in o) o[first] = mine
            val partText = parts.joinToString(if (S.zh) "、" else ", ") { p -> "${q(label(p))}（${keys(if (p == first && mine != null) mine else byId[p]?.defaults)}）" }
            items += ReportItem(ChangeKind.Split,
                tr("${q(label(from))} was split into ${parts.size} commands", "${q(label(from))} 拆分成 ${parts.size} 個指令"),
                (if (mine != null) tr("Your keys (${keys(mine)}) went to ${q(label(first))}; the others use their defaults. ",
                    "你原本的按鍵（${keys(mine)}）套用到 ${q(label(first))}，其他的使用預設。") else "") + partText,
                mine ?: byId[first]?.defaults.orEmpty(), first)
        }
        // Changed, new and removed defaults.
        old.keyDefaults.forEach { (id, was) ->
            if (id in handledOld) return@forEach
            val now = byId[id]
            if (now == null || !now.builtIn) {
                val mine = o.remove(id)
                items += ReportItem(ChangeKind.Removed, tr("${q(label(id))} was removed", "${q(label(id))} 已移除"),
                    if (mine != null) tr("Your keys for it (${keys(mine)}) no longer do anything and are now free.", "你為它設定的按鍵（${keys(mine)}）已失效，現在可以給其他指令使用。")
                    else tr("Its key ${keys(was)} is free now.", "它原本的按鍵 ${keys(was)} 現在空出來了。"), mine ?: was)
                return@forEach
            }
            if (was == now.defaults) return@forEach
            val mine = o[id]
            items += if (mine != null) ReportItem(ChangeKind.DefaultKept, tr("${q(now.label)}: new default ${keys(now.defaults)}", "${q(now.label)}：新的預設是 ${keys(now.defaults)}"),
                tr("You had set ${keys(mine)}; that's kept. Reset it in the keymap to use the new default.", "你自訂的 ${keys(mine)} 保持不變；想改用新預設，可在快速鍵設定按「重設」。"), mine, id)
            else ReportItem(ChangeKind.DefaultChanged, tr("${q(now.label)}: ${keys(was)} → ${keys(now.defaults)}", "${q(now.label)}：${keys(was)} → ${keys(now.defaults)}"),
                tr("You hadn't changed this key, so it follows the new default.", "你沒改過這個按鍵，所以跟著新預設。"), now.defaults, id)
        }
        acts.filter { it.builtIn && it.id !in old.keyDefaults && it.id !in handledNew && it.defaults.isNotEmpty() }.forEach { a ->
            items += ReportItem(ChangeKind.NewKey, tr("New: ${q(a.label)} (${keys(a.defaults)})", "新增：${q(a.label)}（${keys(a.defaults)}）"), a.category, a.defaults, a.id)
        }
        // Keys that clash now but didn't before the update.
        val nowEff = acts.associate { it.id to (o[it.id] ?: it.defaults) }
        val beforeEff = old.keyDefaults.mapValues { (id, d) -> overrides[id] ?: d } + acts.filter { !it.builtIn }.associate { it.id to (overrides[it.id] ?: it.defaults) }
        val before = Keymap.conflictKeys(beforeEff)
        Keymap.conflictKeys(nowEff).filter { it !in before }.forEach { k ->
            val who = acts.filter { k in nowEff[it.id].orEmpty() }
            fun personal(a: KeyAction) = !a.builtIn || (o[a.id] != null && k !in a.defaults)
            val ordered = who.sortedBy { if (personal(it)) 0 else 1 }
            items += ReportItem(ChangeKind.Conflict, tr("$k is given to ${ordered.size} commands", "$k 同時指定給 ${ordered.size} 個指令"),
                ordered.joinToString(if (S.zh) "、" else ", ") { q(it.label) } + tr(". Pressing it runs ${q(ordered.first().label)}.", "。按下時執行 ${q(ordered.first().label)}。"),
                listOf(k), k)
        }
        // Settings: migrations first, then defaults that changed.
        val settings = LinkedHashMap<String, String?>()
        val applied = old.applied.toMutableSet()
        var current = props
        migrations.filter { it.id !in old.applied }.forEach { m ->
            val ch = runCatching { m.apply(current) }.getOrDefault(emptyMap())
            applied += m.id
            if (ch.isEmpty()) return@forEach
            settings += ch
            current = (current + ch.filterValues { it != null }.mapValues { it.value!! }) - ch.filterValues { it == null }.keys
            items += ReportItem(ChangeKind.Migrated, m.title(), ch.entries.joinToString("; ") { (k, v) ->
                if (v == null) tr("$k removed (was ${value(props[k])})", "已移除 $k（原本是 ${value(props[k])}）")
                else if (props[k] == null) "${settingLabels[k]?.invoke() ?: k} = ${value(v)}"
                else "${settingLabels[k]?.invoke() ?: k}: ${value(props[k])} → ${value(v)}"
            })
        }
        settingLabels.forEach { (k, name) ->
            val was = old.settingDefaults[k] ?: return@forEach
            val now = settingDefaults[k] ?: return@forEach
            if (was == now) return@forEach
            val mine = current[k]
            items += if (mine != null) ReportItem(ChangeKind.SettingKept, tr("${q(name())}: new default ${value(now)}", "${q(name())}：新預設為 ${value(now)}"),
                tr("You had chosen ${value(mine)}; that's kept.", "你選的 ${value(mine)} 保持不變。"))
            else ReportItem(ChangeKind.SettingChanged, tr("${q(name())}: ${value(was)} → ${value(now)}", "${q(name())}：${value(was)} → ${value(now)}"),
                tr("You hadn't changed this, so it follows the new default.", "你沒改過這個設定，所以跟著新預設。"))
        }
        return Outcome(items, o, settings, applied)
    }

    /**
     * Called once at start (not in tests or screenshots). Returns the report to show, or null when
     * nothing changed since the last run.
     */
    fun onStart(s: AppState): UpdateReport? {
        if (Prefs.readOnly) return null
        val acts = Keymap.actions()
        val (defaults, labels) = Keymap.snapshot(acts)
        val settingDefaults = Prefs.requestedDefaults.filterKeys { it in settingLabels }
        val old = runCatching { stateFile.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.let { VersionState.parse(it) } }.getOrNull()
        val now = AppInfo.version
        fun remember(prev: VersionState?, applied: Set<String>) = runCatching {
            stateFile.writeText(VersionState(now, defaults, labels, (prev?.settingDefaults.orEmpty()) + settingDefaults, applied).toJson(), Charsets.UTF_8)
        }
        if (old == null && !Prefs.existedAtStart) {
            // A fresh install: nothing to carry over. Show the introduction once.
            remember(null, migrations.map { it.id }.toSet())
            return UpdateReport(null, now, true, emptyList(), null, stamp()).also { save(it) }
        }
        // Settings from before versions were tracked (1.0.0): assume its keys were today's defaults.
        val prev = old ?: VersionState("1.0.0", defaults, labels, settingDefaults, emptySet())
        val pending = migrations.any { it.id !in prev.applied }
        val changed = prev.version != now || prev.keyDefaults != defaults || pending ||
            settingLabels.keys.any { k -> prev.settingDefaults[k] != null && settingDefaults[k] != null && prev.settingDefaults[k] != settingDefaults[k] }
        if (!changed) {
            if (old == null) remember(prev, prev.applied)
            return null
        }
        val backup = SettingsBackup.create(prev.version)
        val props = Prefs.keys().associateWith { Prefs.raw(it)!! }
        val outcome = compare(prev, acts, Keymap.loadRaw(), props, settingDefaults)
        if (outcome.overrides != Keymap.overrides) Keymap.replace(outcome.overrides)
        if (outcome.settings.isNotEmpty()) Prefs.put(outcome.settings)
        remember(prev, outcome.applied)
        return UpdateReport(prev.version, now, false, outcome.items, backup?.path, stamp()).also { save(it) }
    }

    private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private fun save(r: UpdateReport) { if (!Prefs.readOnly) runCatching { reportFile.writeText(r.toJson(), Charsets.UTF_8) } }

    /** The last update's report (for the 更新資訊 tab opened later). */
    fun last(): UpdateReport? = runCatching { reportFile.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.let { UpdateReport.parse(it) } }.getOrNull()

    /** Starts this program again (after a restore) and ends this run. */
    fun restart() {
        val h = ProcessHandle.current().info()
        val cmd = h.command().orElse(null)
        if (cmd != null) runCatching { ProcessBuilder(listOf(cmd) + h.arguments().orElse(emptyArray()).toList()).start() }
        kotlin.system.exitProcess(0)
    }
}

/**
 * Copies of %APPDATA%\FilesKmp made before an update changes anything, in %APPDATA%\FilesKmp\backups.
 * Nothing here deletes a backup.
 */
object SettingsBackup {
    val root get() = File(Prefs.dir, "backups")
    /** Files that describe this install rather than the person's settings: never restored over the current ones. */
    private val ownFiles = setOf("version.json", "last-update-report.json")

    private fun copyTree(from: File, to: File, skip: (File) -> Boolean) {
        from.listFiles()?.forEach { f ->
            if (skip(f)) return@forEach
            val t = File(to, f.name)
            if (f.isDirectory) { t.mkdirs(); copyTree(f, t) { false } } else f.copyTo(t, overwrite = true)
        }
    }

    /** Copies the settings folder into backups\<label>-<time>. Null when it couldn't. */
    fun create(label: String): File? = runCatching {
        if (Prefs.readOnly) return null
        val name = "v${label.replace(Regex("[^0-9A-Za-z.\\-]"), "_")}-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        var dir = File(root, name); var n = 2
        while (dir.exists()) dir = File(root, "$name-${n++}")
        dir.mkdirs()
        copyTree(Prefs.dir, dir) { it.name == "backups" }
        dir
    }.getOrNull()

    /** Every backup, newest first. */
    fun list(): List<File> = root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }.orEmpty()

    /**
     * Puts [backup] back: first backs up the current settings (so this can be undone too), then copies
     * the backup over them. Settings made after the backup are moved into that new copy. The app must
     * restart afterwards; until then nothing more is written.
     */
    fun restore(backup: File): File? {
        if (!backup.isDirectory) return null
        val before = create("before-restore") ?: return null
        Prefs.dir.listFiles()?.forEach { f ->
            if (f.name == "backups" || f.name in ownFiles) return@forEach
            if (!File(backup, f.name).exists()) { if (f.isDirectory) f.deleteRecursively() else f.delete() }   // kept in [before]
        }
        copyTree(backup, Prefs.dir) { it.name in ownFiles || it.name == "backups" }
        Prefs.readOnly = true
        return before
    }
}
