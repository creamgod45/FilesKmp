package app.files

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.File

enum class SortBy(val label: String) { Name("Name"), DateModified("Date modified"), DateCreated("Date created"), Size("Size"), Type("Type") }
enum class LayoutMode(val label: String) { Details("Details"), List("List"), Cards("Cards"), Grid("Grid") }

const val HOME = "::home"

class TabState(start: String) {
    val history = mutableStateListOf(start)
    var index by mutableStateOf(0)
    val location: String get() = history[index]
    val isHome: Boolean get() = location == HOME

    var raw by mutableStateOf<List<FileItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var selection by mutableStateOf<Set<String>>(emptySet())
    var anchor by mutableStateOf<String?>(null)
    var renaming by mutableStateOf<String?>(null)
    var loadJob: Job? = null

    val title: String get() = if (isHome) "Home" else Fs.displayName(location)
    val canBack get() = index > 0
    val canForward get() = index < history.size - 1
}

class Clipboard(val paths: List<String>, val cut: Boolean)

class AppState(val scope: CoroutineScope) {
    val tabs = mutableStateListOf(TabState(HOME))
    var tabIndex by mutableStateOf(0)
    val tab: TabState get() = tabs[tabIndex.coerceIn(0, tabs.size - 1)]

    var layout by mutableStateOf(LayoutMode.Details)
    var sortBy by mutableStateOf(SortBy.Name)
    var sortAscending by mutableStateOf(true)
    var showHidden by mutableStateOf(false)            // FoldersSettingsService default
    var showExtensions by mutableStateOf(true)         // FoldersSettingsService default
    var sidebarWidth by mutableStateOf(M.SIDEBAR_WIDTH)
    var sidebarOpen by mutableStateOf(true)
    var infoPaneOpen by mutableStateOf(false)          // InfoPaneSettingsService default
    var statusCenterOpen by mutableStateOf(false)
    var editingPath by mutableStateOf(false)
    var searchText by mutableStateOf("")
    var searchRequest by mutableStateOf(0)
    var textFocus by mutableStateOf(false)
    var gridStep by mutableStateOf(M.GRID_DEFAULT_STEP)
    var detailsSize by mutableStateOf(M.DETAILS_DEFAULT_SIZE)
    var clipboard by mutableStateOf<Clipboard?>(null)
    var toast by mutableStateOf<String?>(null)
    var confirmDelete by mutableStateOf<List<String>?>(null)

    // Home data: loaded once, kept, and only refreshed on request (the original reloads
    // every widget each time Home is shown, which is a main cause of its slow Home page).
    var pinned by mutableStateOf<List<PinnedFolder>>(emptyList())
    var drives by mutableStateOf<List<DriveInfo>>(emptyList())
    var recent by mutableStateOf<List<RecentFile>>(emptyList())
    var pinnedLoaded by mutableStateOf(false)
    var drivesLoaded by mutableStateOf(false)
    var recentLoaded by mutableStateOf(false)
    var homeLoadMs by mutableStateOf<Long?>(null)

    init {
        CopyEngine.onJobFinished = { job ->
            scope.launch(Dispatchers.Main) {
                if (!tab.isHome && (tab.location == job.destDir || job.sources.any { Fs.parent(it) == tab.location })) refresh()
                if (job.state == JobState.Failed) toast = "Couldn't finish: ${job.error}"
            }
        }
        loadHome()
    }

    fun loadHome() {
        val started = System.nanoTime()
        // The three widgets load independently and in parallel, off the UI thread.
        val a = scope.launch(Dispatchers.IO) { val d = Fs.drives(); withContext(Dispatchers.Main) { drives = d; drivesLoaded = true } }
        val b = scope.launch(Dispatchers.IO) { val p = Fs.quickAccess(); withContext(Dispatchers.Main) { pinned = p; pinnedLoaded = true } }
        val c = scope.launch(Dispatchers.IO) { val r = Fs.recentFiles(); withContext(Dispatchers.Main) { recent = r; recentLoaded = true } }
        scope.launch {
            joinAll(a, b, c)
            homeLoadMs = (System.nanoTime() - started) / 1_000_000
        }
    }

    fun visibleItems(t: TabState): List<FileItem> {
        var list = t.raw
        if (!showHidden) list = list.filter { !it.hidden && !it.name.startsWith("\$") }
        if (searchText.isNotBlank()) list = list.filter { it.name.contains(searchText, ignoreCase = true) }
        return Fs.sort(list, sortBy, sortAscending)
    }

    fun displayName(item: FileItem): String =
        if (showExtensions || item.isDir || !item.name.contains('.')) item.name else item.name.substringBeforeLast('.')

    // ------------------------------------------------------------ navigation

    fun navigate(path: String, t: TabState = tab) {
        if (path != HOME && !File(path).isDirectory) {
            Fs.open(path); return
        }
        while (t.history.size > t.index + 1) t.history.removeAt(t.history.size - 1)
        t.history.add(path)
        t.index = t.history.size - 1
        searchText = ""
        load(t)
    }

    fun back(t: TabState = tab) { if (t.canBack) { t.index--; load(t) } }
    fun forward(t: TabState = tab) { if (t.canForward) { t.index++; load(t) } }
    fun up(t: TabState = tab) {
        if (t.isHome) return
        val p = Fs.parent(t.location)
        navigate(p ?: HOME, t)
    }

    fun refresh(t: TabState = tab) {
        if (t.isHome) loadHome() else load(t, keepSelection = true)
    }

    fun load(t: TabState, keepSelection: Boolean = false) {
        t.loadJob?.cancel()
        if (!keepSelection) t.selection = emptySet()
        t.renaming = null
        t.error = null
        if (t.isHome) { t.raw = emptyList(); t.loading = false; return }
        val path = t.location
        t.loading = true
        t.loadJob = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Fs.list(path) } }
            if (t.location != path) return@launch
            result.onSuccess { t.raw = it }.onFailure { t.raw = emptyList(); t.error = "Can't open this folder: ${it.message}" }
            if (keepSelection) t.selection = t.selection.filter { s -> t.raw.any { it.path == s } }.toSet()
            t.loading = false
        }
    }

    fun newTab(path: String = HOME) {
        val t = TabState(path)
        tabs.add(t)
        tabIndex = tabs.size - 1
        load(t)
    }

    fun closeTab(i: Int) {
        if (tabs.size == 1) { tabs[0].history.clear(); tabs[0].history.add(HOME); tabs[0].index = 0; load(tabs[0]); return }
        tabs.removeAt(i)
        if (tabIndex >= tabs.size) tabIndex = tabs.size - 1 else if (i < tabIndex) tabIndex--
    }

    // ------------------------------------------------------------ selection

    fun click(item: FileItem, ctrl: Boolean, shift: Boolean, items: List<FileItem>) {
        val t = tab
        t.renaming = null
        when {
            shift && t.anchor != null -> {
                val a = items.indexOfFirst { it.path == t.anchor }
                val b = items.indexOf(item)
                if (a >= 0 && b >= 0) {
                    val range = items.subList(minOf(a, b), maxOf(a, b) + 1).map { it.path }
                    t.selection = if (ctrl) t.selection + range else range.toSet()
                }
            }
            ctrl -> {
                t.selection = if (item.path in t.selection) t.selection - item.path else t.selection + item.path
                t.anchor = item.path
            }
            else -> { t.selection = setOf(item.path); t.anchor = item.path }
        }
    }

    fun selectAll() { tab.selection = visibleItems(tab).map { it.path }.toSet() }
    fun clearSelection() { tab.selection = emptySet() }
    fun invertSelection() { val all = visibleItems(tab).map { it.path }.toSet(); tab.selection = all - tab.selection }

    // ------------------------------------------------------------ file actions

    private val canAct get() = !tab.isHome

    fun copy(cut: Boolean = false) {
        val sel = tab.selection.toList()
        if (sel.isEmpty()) return
        clipboard = Clipboard(sel, cut)
        toast = "${sel.size} item${if (sel.size == 1) "" else "s"} ${if (cut) "cut" else "copied"}"
    }

    fun paste() {
        val clip = clipboard ?: return
        if (!canAct) return
        CopyEngine.submit(if (clip.cut) OpKind.Move else OpKind.Copy, clip.paths, tab.location)
        statusCenterOpen = true
        if (clip.cut) clipboard = null
    }

    fun newFolder() {
        if (!canAct) return
        val created = Fs.newFolder(tab.location)
        afterCreate(created)
    }

    fun newFile() {
        if (!canAct) return
        val created = Fs.newFile(tab.location)
        afterCreate(created)
    }

    private fun afterCreate(created: String) {
        val t = tab
        scope.launch {
            val list = withContext(Dispatchers.IO) { Fs.list(t.location) }
            t.raw = list
            t.selection = setOf(created)
            t.renaming = created
        }
    }

    fun startRename() {
        val sel = tab.selection
        if (sel.size == 1) tab.renaming = sel.first()
    }

    fun commitRename(path: String, newName: String) {
        val t = tab
        t.renaming = null
        if (newName.isBlank() || newName == Fs.displayName(path)) return
        val result = Fs.rename(path, newName.trim())
        if (result == null) toast = "Couldn't rename to \"$newName\"" else {
            load(t)
            t.selection = setOf(result)
        }
    }

    fun requestDelete() {
        val sel = tab.selection.toList()
        if (sel.isNotEmpty() && canAct) confirmDelete = sel   // DeleteConfirmationPolicy = Always
    }

    fun doDelete(paths: List<String>, permanent: Boolean) {
        confirmDelete = null
        val t = tab
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (permanent) { Fs.deletePermanently(paths); true } else Fs.recycle(paths)
            }
            if (!ok) toast = "Some items couldn't be moved to the Recycle Bin"
            load(t)
        }
    }

    fun openSelected() {
        val t = tab
        val items = visibleItems(t).filter { it.path in t.selection }
        if (items.size == 1 && items[0].isDir) navigate(items[0].path)
        else items.forEach { if (it.isDir) newTab(it.path) else Fs.open(it.path) }
    }

    fun copyPath() {
        val sel = tab.selection.ifEmpty { if (tab.isHome) emptySet() else setOf(tab.location) }
        if (sel.isEmpty()) return
        val text = sel.joinToString("\n") { "\"$it\"" }
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
        toast = "Path copied"
    }

    fun gridLarger() { if (layout == LayoutMode.Grid) gridStep = (gridStep + 1).coerceAtMost(M.GRID_ITEM_WIDTH.size - 1) else detailsSize = (detailsSize + 1).coerceAtMost(4) }
    fun gridSmaller() { if (layout == LayoutMode.Grid) gridStep = (gridStep - 1).coerceAtLeast(0) else detailsSize = (detailsSize - 1).coerceAtLeast(0) }
}
