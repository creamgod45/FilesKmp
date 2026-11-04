package app.files

import androidx.compose.runtime.*
import java.io.File

internal fun tr(en: String, zh: String) = if (S.zh) zh else en

/**
 * One thing a key can do: a toolbar command, a custom command, one of the person's shortcuts (捷徑),
 * or "go to tab N". [defaults] are the keys it has out of the box (already normalized).
 */
class KeyAction(
    val id: String,
    private val labelOf: () -> String,
    private val categoryOf: () -> String,
    val defaults: List<String>,
    val enabled: (AppState) -> Boolean,
    val run: (AppState) -> Unit,
    /** Built into the app (its defaults are compared across versions); false for custom commands and shortcuts. */
    val builtIn: Boolean = true,
) {
    val label get() = labelOf()
    val category get() = categoryOf()
}

/** A key given to more than one action. [actions] is in priority order: the first enabled one runs. */
class KeyConflict(val key: String, val actions: List<KeyAction>)

/**
 * 鍵盤快速鍵: every key the app answers to, and the person's changes to them.
 *
 * The defaults live in code (toolbar commands' own shortcut, plus [extraDefaults]). Only what the
 * person changed is saved, in %APPDATA%\FilesKmp\keymap.json:
 *
 * ```json
 * { "schema": 1, "bindings": { "CopyItem": ["Ctrl+C", "Ctrl+Insert"], "DeleteItem": [] } }
 * ```
 * A list replaces the action's defaults; an empty list means "no key" (cleared).
 * Actions renamed or split by an update are carried over on load ([history]).
 */
object Keymap {
    private const val SCHEMA = 1
    private val file get() = File(Prefs.dir, "keymap.json")

    /** Keys a toolbar command answers to besides its own shortcut (they used to be wired by hand). */
    val extraDefaults: Map<String, List<String>> = mapOf(
        "DeleteItem" to listOf("Ctrl+D"),
        "CloseSelectedTab" to listOf("Ctrl+F4"),
        "RefreshItems" to listOf("Ctrl+R"),
        "Search" to listOf("F3", "Ctrl+E"),
        "EditPath" to listOf("Alt+D"),
        "NavigateBack" to listOf("Backspace"),
        "ShowShortcuts" to listOf("Ctrl+/"),
        "RestoreItems" to listOf("Ctrl+Z"),
    )

    /**
     * How actions changed between versions, so a person's keys follow them.
     * - renamed: old id → new id (the keys move over)
     * - split: old id → new ids (the keys go to the first; the others start with their defaults)
     * Add an entry here whenever a release renames or splits a command.
     */
    class History(val renamed: Map<String, String> = emptyMap(), val split: Map<String, List<String>> = emptyMap())
    internal var history = History()

    private val tabActions: List<KeyAction> = (1..9).map { n ->
        KeyAction("GoToTab$n", { tr("Go to tab $n", "切換到第 $n 個分頁") }, { TbCategory.Navigation.text }, listOf("Ctrl+$n"),
            { it.tabs.size >= n }, { it.tabIndex = n - 1 })
    }

    /** Every action, in the order shown in the editor. */
    fun actions(): List<KeyAction> {
        val cmds = TbCommands.all.filter { !it.isGroup }.map { c ->
            KeyAction(c.id, { c.label }, { c.category.text },
                (listOf(c.defaultHotkey) + extraDefaults[c.id].orEmpty()).mapNotNull { Hotkeys.normalize(it) }.distinct(), c.enabled, c.run)
        }
        val custom = TbCustom.all.filter { !it.isGroup }.map { c ->
            KeyAction(c.id, { c.label }, { c.category.text }, emptyList(), c.enabled, c.run, builtIn = false)
        }
        val flows = FlowStore.all().map { f ->
            KeyAction("flow:${f.id}", { f.name }, { tr("Shortcuts", "捷徑") }, listOfNotNull(Hotkeys.normalize(f.hotkey)),
                { true }, { it.runFlow(f) }, builtIn = false)
        }
        return cmds + tabActions + custom + flows
    }

    /** The person's changes: action id → its keys (normalized). */
    var overrides by mutableStateOf(load())
        private set

    fun bindings(a: KeyAction): List<String> = overrides[a.id] ?: a.defaults
    fun isModified(a: KeyAction) = overrides[a.id]?.let { it != a.defaults } ?: false

    /** Keys of [a] the person chose themselves (they win over defaults when two actions share a key). */
    private fun personal(a: KeyAction, key: String) = !a.builtIn || (overrides[a.id] != null && key !in a.defaults)

    fun set(a: KeyAction, keys: List<String>) {
        val n = keys.mapNotNull { Hotkeys.normalize(it) }.distinct()
        overrides = if (n == a.defaults) overrides - a.id else overrides + (a.id to n)
        save()
    }
    fun add(a: KeyAction, key: String) { val k = Hotkeys.normalize(key) ?: return; if (k !in bindings(a)) set(a, bindings(a) + k) }
    fun remove(a: KeyAction, key: String) = set(a, bindings(a) - key)
    fun clear(a: KeyAction) = set(a, emptyList())
    fun reset(a: KeyAction) { overrides = overrides - a.id; save() }
    fun resetAll() { overrides = emptyMap(); save() }

    /** Effective keys of every action: id → keys. */
    fun effective(acts: List<KeyAction> = actions()): Map<String, List<String>> = acts.associate { it.id to bindings(it) }

    /** Keys given to two or more actions (each action counted once per key). */
    fun conflicts(acts: List<KeyAction> = actions()): List<KeyConflict> {
        val byKey = LinkedHashMap<String, MutableList<KeyAction>>()
        acts.forEach { a -> bindings(a).distinct().forEach { k -> byKey.getOrPut(k) { mutableListOf() } += a } }
        return byKey.filterValues { it.size > 1 }.map { (k, list) -> KeyConflict(k, prioritized(list, k)) }
    }

    /** Conflicting keys in a plain id → keys map (for comparing before and after an update). */
    fun conflictKeys(bindings: Map<String, List<String>>): Set<String> =
        bindings.flatMap { (id, keys) -> keys.distinct().map { it to id } }.groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }.keys

    /** The person's own keys first, then the defaults, each in list order. */
    private fun prioritized(list: List<KeyAction>, key: String) = list.sortedBy { if (personal(it, key)) 0 else 1 }

    /** The actions [key] would run, in priority order. */
    fun actionsFor(key: String, acts: List<KeyAction> = actions()): List<KeyAction> {
        val k = Hotkeys.normalize(key) ?: return emptyList()
        return prioritized(acts.filter { k in bindings(it) }, k)
    }

    /** Runs the first enabled action for [key]. True when [key] belongs to some action (even if none can run now). */
    fun dispatch(s: AppState, key: String): Boolean {
        val list = actionsFor(key)
        if (list.isEmpty()) return false
        list.firstOrNull { it.enabled(s) }?.run?.invoke(s)
        return true
    }

    /** What a toolbar menu shows next to a command: its first key, or nothing. */
    fun display(id: String, default: String): String {
        val o = overrides[id] ?: return default.ifEmpty { extraDefaults[id]?.firstOrNull().orEmpty() }
        return o.firstOrNull().orEmpty()
    }

    /** Built-in actions' defaults and names, remembered so the next version can tell what changed. */
    fun snapshot(acts: List<KeyAction> = actions()): Pair<Map<String, List<String>>, Map<String, String>> {
        val b = acts.filter { it.builtIn }
        return b.associate { it.id to it.defaults } to b.associate { it.id to it.label }
    }

    // ------------------------------------------------------------------ storage

    private fun load(): Map<String, List<String>> = applyHistory(loadRaw())

    /** keymap.json as written, with the ids it was written with (an update compares these). */
    fun loadRaw(): Map<String, List<String>> = try {
        if (!file.exists()) emptyMap() else parse(file.readText(Charsets.UTF_8))
    } catch (_: Exception) { emptyMap() }

    /** Moves keys of renamed or split actions to their new ids (the same rule as an update, without a report). */
    fun applyHistory(o: Map<String, List<String>>): Map<String, List<String>> {
        val m = o.toMutableMap()
        history.renamed.forEach { (from, to) -> m.remove(from)?.let { if (to !in m) m[to] = it } }
        history.split.forEach { (from, parts) -> m.remove(from)?.let { k -> parts.firstOrNull()?.let { p -> if (p !in m) m[p] = k } } }
        return m
    }

    fun parse(text: String): Map<String, List<String>> {
        val m = Json.parse(text) as? Map<*, *> ?: return emptyMap()
        val b = m["bindings"] as? Map<*, *> ?: return emptyMap()
        return b.entries.mapNotNull { (k, v) ->
            val keys = (v as? List<*>)?.mapNotNull { (it as? String)?.let { s -> Hotkeys.normalize(s) } }?.distinct() ?: return@mapNotNull null
            k.toString() to keys
        }.toMap()
    }

    fun toJson(o: Map<String, List<String>> = overrides): String =
        Json.write(linkedMapOf("schema" to SCHEMA, "bindings" to o.toSortedMap()))

    private fun save() {
        if (Prefs.readOnly) return
        try { file.writeText(toJson(), Charsets.UTF_8) } catch (_: Exception) {}
    }

    /** Re-reads keymap.json (after a migration or a restore wrote it). */
    fun reload() { overrides = load() }

    /** Replaces the person's changes (a migration) and saves them. */
    internal fun replace(o: Map<String, List<String>>) { overrides = o; save() }

    /** Tests only: start from these changes without reading or writing the file. */
    internal fun testReset(o: Map<String, List<String>> = emptyMap()) { overrides = o }
}
