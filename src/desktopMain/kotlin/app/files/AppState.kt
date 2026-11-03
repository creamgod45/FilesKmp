package app.files

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import java.io.File

enum class SortBy(val label: String) { Name("Name"), DateModified("Date modified"), DateCreated("Date created"), Size("Size"), Type("Type");
    val text: String get() = when (this) { Name -> S.name; DateModified -> S.dateModified; DateCreated -> S.dateCreated; Size -> S.size; Type -> S.type }
}
enum class LayoutMode(val label: String) { Details("Details"), List("List"), Cards("Cards"), Grid("Grid");
    val text: String get() = when (this) { Details -> S.details; List -> S.list; Cards -> S.cards; Grid -> S.grid }
}

const val HOME = "::home"
const val TAG_PREFIX = "::tag:"
const val SEARCH_PREFIX = "::search:"
private const val SEP = "\u0001"

fun searchLocation(root: String, query: String) = "$SEARCH_PREFIX$root$SEP$query"

/** One pane: its own history, listing and selection. */
class TabState(start: String) {
    val history = mutableStateListOf(start)
    var index by mutableStateOf(0)
    val location: String get() = history[index]
    val isHome: Boolean get() = location == HOME
    val isTag: Boolean get() = location.startsWith(TAG_PREFIX)
    val isSearch: Boolean get() = location.startsWith(SEARCH_PREFIX)
    /** Home, a tag view or search results: not a real folder you can create files in. */
    val isVirtual: Boolean get() = location.startsWith("::")
    val tagName: String get() = location.removePrefix(TAG_PREFIX)
    val searchRoot: String get() = location.removePrefix(SEARCH_PREFIX).substringBefore(SEP)
    val searchQuery: String get() = location.substringAfter(SEP, "")

    var raw by mutableStateOf<List<FileItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var selection by mutableStateOf<Set<String>>(emptySet())
    var anchor by mutableStateOf<String?>(null)
    var renaming by mutableStateOf<String?>(null)
    var loadJob: Job? = null

    val title: String
        get() = when {
            isHome -> S.home
            isTag -> "${S.tags}: $tagName"
            isSearch -> "${S.search}: $searchQuery"
            else -> Fs.displayName(location)
        }
    val canBack get() = index > 0
    val canForward get() = index < history.size - 1
}

/**
 * How two panes sit (the original's ShellPaneArrangement): Vertical = side by side with a
 * vertical divider (the default, GeneralSettingsService.cs:420-422); Horizontal = one above the other.
 */
enum class PaneArrangement { Vertical, Horizontal }

/** A tab holds one pane, or two (split vertically or horizontally). */
class Tab(first: TabState) {
    val left = first
    var right by mutableStateOf<TabState?>(null)
    var rightActive by mutableStateOf(false)
    var arrangement by mutableStateOf(PaneArrangement.Vertical)
    var split by mutableStateOf(0.5f)
    val active: TabState get() = if (rightActive) right ?: left else left
    val title get() = active.title
    val isHome get() = active.isHome
    val location get() = active.location
}

class Clipboard(val paths: List<String>, val cut: Boolean)

class AppState(val scope: CoroutineScope) {
    val tabs = mutableStateListOf(Tab(TabState(HOME)))
    var tabIndex by mutableStateOf(0)
    val currentTab: Tab get() = tabs[tabIndex.coerceIn(0, tabs.size - 1)]
    /** The active pane of the current tab; every command works on it. */
    val tab: TabState get() = currentTab.active

    // ---- settings (saved in %APPDATA%\FilesKmp, defaults from the original's settings services)
    var theme by mutableStateOf(runCatching { ThemeMode.valueOf(Prefs.str("theme", "System")) }.getOrDefault(ThemeMode.System))
    var layout by mutableStateOf(runCatching { LayoutMode.valueOf(Prefs.str("layout", "Details")) }.getOrDefault(LayoutMode.Details))
    var sortBy by mutableStateOf(runCatching { SortBy.valueOf(Prefs.str("sortBy", "Name")) }.getOrDefault(SortBy.Name))
    var sortAscending by mutableStateOf(Prefs.bool("sortAscending", true))
    var showHidden by mutableStateOf(Prefs.bool("showHidden", false))              // FoldersSettingsService:55
    var showExtensions by mutableStateOf(Prefs.bool("showExtensions", true))       // FoldersSettingsService:118
    var showCheckboxes by mutableStateOf(Prefs.bool("showCheckboxes", true))       // FoldersSettingsService:154
    var showThumbnails by mutableStateOf(Prefs.bool("showThumbnails", true))       // FoldersSettingsService:124
    var calculateFolderSizes by mutableStateOf(Prefs.bool("calculateFolderSizes", false)) // FoldersSettingsService:112
    var defaultArrangement by mutableStateOf(runCatching { PaneArrangement.valueOf(Prefs.str("paneArrangement", "Vertical")) }.getOrDefault(PaneArrangement.Vertical))
    // Personalization (AppearanceSettingsService): backdrop MicaAlt, no tint, no image, image fill UniformToFill, opacity 1, centred.
    var backdrop by mutableStateOf(runCatching { Backdrop.valueOf(Prefs.str("backdrop", "MicaAlt")) }.getOrDefault(Backdrop.MicaAlt))
    var backgroundTint by mutableStateOf(Prefs.str("backgroundTint", "#00000000"))
    var backgroundImage by mutableStateOf(Prefs.str("backgroundImage", ""))
    var backgroundImageFit by mutableStateOf(runCatching { ImageFit.valueOf(Prefs.str("backgroundImageFit", "UniformToFill")) }.getOrDefault(ImageFit.UniformToFill))
    var backgroundImageOpacity by mutableStateOf(Prefs.str("backgroundImageOpacity", "1").toFloatOrNull() ?: 1f)
    var backgroundImageAlign by mutableStateOf(runCatching { ImageAlign.valueOf(Prefs.str("backgroundImageAlign", "Center")) }.getOrDefault(ImageAlign.Center))

    // ---- background picture rotation (several pictures, switched on a timer / at each start, per-folder rules)
    val bgImages = mutableStateListOf<String>().apply { addAll(Prefs.str("bgImages", "").split(SEP).filter { it.isNotBlank() }) }
    var bgMode by mutableStateOf(runCatching { BgMode.valueOf(Prefs.str("bgMode", "Single")) }.getOrDefault(BgMode.Single))
    var bgShuffle by mutableStateOf(Prefs.bool("bgShuffle", false))
    var bgIntervalMin by mutableStateOf(Prefs.int("bgInterval", M.BG_DEFAULT_INTERVAL_MIN).coerceIn(1, 1440))
    var bgIndex by mutableStateOf(Prefs.int("bgIndex", 0))
    val bgRules = mutableStateListOf<BgRule>().apply {
        addAll(Prefs.str("bgRules", "").split(SEP).mapNotNull { r ->
            val p = r.split('\u0002'); if (p.size == 3) BgRule(p[0], p[1], p[2] == "1") else null
        })
    }

    /** Moves to the next picture in the list (or a random other one when shuffling). */
    fun nextBackground() {
        if (bgImages.isEmpty()) return
        bgIndex = if (bgShuffle && bgImages.size > 1) {
            var n: Int
            do { n = (bgImages.indices).random() } while (n == bgIndex)
            n
        } else (bgIndex + 1) % bgImages.size
        backgroundImage = bgImages[bgIndex]
    }

    /** The picture to show now: a matching folder rule (most specific folder wins), else the current one. */
    fun effectiveBackground(): String {
        val loc = tab.location
        if (!loc.startsWith("::")) {
            val hit = bgRules.filter { r ->
                loc.equals(r.folder, true) || (r.includeSubfolders && loc.startsWith(r.folder.trimEnd('\\') + "\\", true))
            }.maxByOrNull { it.folder.length }
            if (hit != null) return hit.image
        }
        return backgroundImage
    }

    /** Folder sizes worked out on request ("檢視大小") or automatically when that setting is on. */
    val folderSizes = mutableStateMapOf<String, Long>()
    val folderSizing = mutableStateMapOf<String, Boolean>()
    var confirmDeleteAlways by mutableStateOf(Prefs.bool("confirmDelete", true))   // FoldersSettingsService:130
    var openFoldersInNewTab by mutableStateOf(Prefs.bool("openFoldersInNewTab", false)) // FoldersSettingsService:100
    var continueSession by mutableStateOf(Prefs.bool("continueSession", true))    // GeneralSettingsService:28
    var showQuickAccessWidget by mutableStateOf(Prefs.bool("w.quickAccess", true))
    var showDrivesWidget by mutableStateOf(Prefs.bool("w.drives", true))
    var showNetworkWidget by mutableStateOf(Prefs.bool("w.network", true))
    var showTagsWidget by mutableStateOf(Prefs.bool("w.tags", true))
    var showRecentWidget by mutableStateOf(Prefs.bool("w.recent", true))
    var showToolbar by mutableStateOf(Prefs.bool("showToolbar", true))
    var showStatusBar by mutableStateOf(Prefs.bool("showStatusBar", true))
    // Same rule as the original (function 0x1401e4c80): stored width clamped to 180..500, default 255.
    var sidebarWidth by mutableStateOf(Prefs.int("sidebarWidth", M.SIDEBAR_WIDTH).coerceIn(M.SIDEBAR_MIN, M.SIDEBAR_MAX))

    var sidebarOpen by mutableStateOf(true)
    var infoPaneOpen by mutableStateOf(false)          // InfoPaneSettingsService default
    var statusCenterOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var tagEditorFor by mutableStateOf<List<String>?>(null)
    var editingPath by mutableStateOf(false)
    var searchText by mutableStateOf("")
    var searchRequest by mutableStateOf(0)
    var textFocus by mutableStateOf(false)
    var gridStep by mutableStateOf(M.GRID_DEFAULT_STEP)
    var detailsSize by mutableStateOf(M.DETAILS_DEFAULT_SIZE)
    var clipboard by mutableStateOf<Clipboard?>(null)
    var toast by mutableStateOf<String?>(null)
    var confirmDelete by mutableStateOf<List<String>?>(null)

    val tagStore = TagStore()

    // Home data: loaded once, kept, and only refreshed on request (the original reloads
    // every widget each time Home is shown, which is a main cause of its slow Home page).
    var pinned by mutableStateOf<List<PinnedFolder>>(emptyList())
    var drives by mutableStateOf<List<DriveInfo>>(emptyList())
    var recent by mutableStateOf<List<RecentFile>>(emptyList())
    var pinnedLoaded by mutableStateOf(false)
    var drivesLoaded by mutableStateOf(false)
    var recentLoaded by mutableStateOf(false)
    var homeLoadMs by mutableStateOf<Long?>(null)
    var cloud by mutableStateOf<List<CloudDrive>>(emptyList())
    var wsl by mutableStateOf<List<String>>(emptyList())

    init {
        CopyEngine.onJobFinished = { job ->
            scope.launch(Dispatchers.Main) {
                if (job.kind == OpKind.Move && job.state == JobState.Done) {
                    job.sources.forEach { src -> tagStore.movePath(src, File(job.destDir, File(src).name).path) }
                }
                allPanes().filter { !it.isVirtual && (it.location == job.destDir || job.sources.any { s -> Fs.parent(s) == it.location }) }
                    .forEach { load(it, keepSelection = true) }
                if (job.state == JobState.Failed) toast = (if (S.zh) "無法完成：" else "Couldn't finish: ") + job.error
            }
        }
        loadHome()
        // Sidebar extras, each in the background with its own time limit.
        scope.launch(Dispatchers.IO) { val c = Fs.cloudDrives(); withContext(Dispatchers.Main) { cloud = c } }
        scope.launch(Dispatchers.IO) { val w = Fs.wslDistros(); withContext(Dispatchers.Main) { wsl = w } }
        // Reopen the last session's tabs (ContinueLastSessionOnStartUp, on by default in the original).
        if (continueSession) {
            val saved = Prefs.str("session", "").split(SEP).filter { it.isNotBlank() && (it == HOME || File(it).isDirectory) }
            if (saved.isNotEmpty()) {
                tabs.clear()
                saved.forEach { tabs.add(Tab(TabState(it))) }
                tabIndex = Prefs.int("sessionIndex", 0).coerceIn(0, tabs.size - 1)
                tabs.forEach { load(it.left) }
            }
        }
        // Background rotation: a new picture at each start, and/or on a timer.
        if (bgMode == BgMode.OnStartup && bgImages.size > 1) nextBackground()
        else if (bgImages.isNotEmpty() && backgroundImage.isBlank()) backgroundImage = bgImages[bgIndex.coerceIn(0, bgImages.size - 1)]
        scope.launch {
            while (true) {
                delay(bgIntervalMin * 60_000L)
                if (bgMode == BgMode.Timer && bgImages.size > 1) nextBackground()
            }
        }
        // Save settings and the open tabs whenever they change.
        scope.launch {
            snapshotFlow { settingsSnapshot() }.drop(1).debounce(400).collect { Prefs.put(it) }
        }
    }

    private fun settingsSnapshot(): Map<String, Any?> = mapOf(
        "theme" to theme, "layout" to layout, "sortBy" to sortBy, "sortAscending" to sortAscending,
        "showHidden" to showHidden, "showExtensions" to showExtensions, "showCheckboxes" to showCheckboxes, "showThumbnails" to showThumbnails,
        "confirmDelete" to confirmDeleteAlways, "openFoldersInNewTab" to openFoldersInNewTab, "continueSession" to continueSession,
        "w.quickAccess" to showQuickAccessWidget, "w.drives" to showDrivesWidget, "w.network" to showNetworkWidget,
        "w.tags" to showTagsWidget, "w.recent" to showRecentWidget, "showToolbar" to showToolbar, "showStatusBar" to showStatusBar,
        "sidebarWidth" to sidebarWidth,
        "calculateFolderSizes" to calculateFolderSizes, "paneArrangement" to defaultArrangement,
        "backdrop" to backdrop, "backgroundTint" to backgroundTint, "backgroundImage" to backgroundImage,
        "backgroundImageFit" to backgroundImageFit, "backgroundImageOpacity" to backgroundImageOpacity,
        "backgroundImageAlign" to backgroundImageAlign,
        "bgImages" to bgImages.joinToString(SEP), "bgMode" to bgMode, "bgShuffle" to bgShuffle, "bgInterval" to bgIntervalMin,
        "bgIndex" to bgIndex, "bgRules" to bgRules.joinToString(SEP) { "${it.folder}\u0002${it.image}\u0002${if (it.includeSubfolders) 1 else 0}" },
        "session" to tabs.map { it.left.location }.filter { !it.startsWith("::") || it == HOME }.joinToString(SEP),
        "sessionIndex" to tabIndex,
    )

    fun allPanes(): List<TabState> = tabs.flatMap { listOfNotNull(it.left, it.right) }

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
        if (searchText.isNotBlank() && !t.isSearch) list = list.filter { it.name.contains(searchText, ignoreCase = true) }
        return Fs.sort(list, sortBy, sortAscending)
    }

    fun displayName(item: FileItem): String =
        if (showExtensions || item.isDir || !item.name.contains('.')) item.name else item.name.substringBeforeLast('.')

    // ------------------------------------------------------------ panes

    fun activate(t: TabState) {
        val ct = currentTab
        if (ct.right === t) ct.rightActive = true else if (ct.left === t) ct.rightActive = false
    }

    /** Ctrl+Shift+S: open a second pane with the default arrangement, or close it. */
    fun toggleDualPane() {
        if (currentTab.right == null) splitPane(defaultArrangement) else closeActivePane()
    }

    /** "Split pane vertically / horizontally": opens the second pane at the current folder (SplitPane*Action). */
    fun splitPane(arrangement: PaneArrangement) {
        val ct = currentTab
        ct.arrangement = arrangement
        if (ct.right == null) {
            val r = TabState(tab.location)
            ct.right = r; ct.rightActive = true; ct.split = 0.5f; load(r)
        }
    }

    /** "Arrange panes vertically / horizontally" (ArrangePanes*Action). */
    fun arrangePanes(arrangement: PaneArrangement) { currentTab.arrangement = arrangement }

    /** "Close active pane": the other pane stays. */
    fun closeActivePane() {
        val ct = currentTab
        val r = ct.right ?: return
        if (ct.rightActive) { ct.right = null; ct.rightActive = false }
        else {
            // Keep the right pane by moving it into this tab's first slot.
            tabs[tabIndex] = Tab(r).also { it.arrangement = ct.arrangement }
        }
    }

    fun focusOtherPane() {
        val ct = currentTab
        if (ct.right != null) ct.rightActive = !ct.rightActive
    }

    // ------------------------------------------------------------ navigation

    fun navigate(path: String, t: TabState = tab) {
        if (!path.startsWith("::") && !File(path).isDirectory) {
            Fs.open(path); return
        }
        while (t.history.size > t.index + 1) t.history.removeAt(t.history.size - 1)
        t.history.add(path)
        t.index = t.history.size - 1
        if (!path.startsWith(SEARCH_PREFIX)) searchText = ""
        load(t)
    }

    fun openFolder(path: String) { if (openFoldersInNewTab) newTab(path) else navigate(path) }

    fun back(t: TabState = tab) { if (t.canBack) { t.index--; load(t) } }
    fun forward(t: TabState = tab) { if (t.canForward) { t.index++; load(t) } }
    fun up(t: TabState = tab) {
        if (t.isHome) return
        if (t.isVirtual) { navigate(if (t.isSearch) t.searchRoot else HOME, t); return }
        val p = Fs.parent(t.location)
        navigate(p ?: HOME, t)
    }

    fun refresh(t: TabState = tab) {
        if (t.isHome) loadHome() else load(t, keepSelection = true)
    }

    fun searchDeep(query: String) {
        val t = tab
        if (query.isBlank() || t.isHome) return
        val root = if (t.isSearch) t.searchRoot else if (t.isTag) return else t.location
        navigate(searchLocation(root, query.trim()), t)
    }

    fun load(t: TabState, keepSelection: Boolean = false) {
        t.loadJob?.cancel()
        if (!keepSelection) t.selection = emptySet()
        t.renaming = null
        t.error = null
        if (t.isHome) { t.raw = emptyList(); t.loading = false; return }
        val path = t.location
        t.loading = true
        if (t.isSearch) {
            t.raw = emptyList()
            val job = scope.launch {
                val acc = ArrayList<FileItem>()
                withContext(Dispatchers.IO) {
                    FsExtra.search(t.searchRoot, t.searchQuery, M.SEARCH_LIMIT, cancelled = { !isActive || t.location != path }) { batch ->
                        acc += batch
                        val snapshot = acc.toList()
                        launch(Dispatchers.Main) { if (t.location == path) t.raw = snapshot }
                    }
                }
                if (t.location == path) { t.raw = acc.toList(); t.loading = false }
            }
            t.loadJob = job
            return
        }
        t.loadJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (t.isTag) tagStore.filesWith(t.tagName).mapNotNull { FsExtra.item(it) } else Fs.list(path)
                }
            }
            if (t.location != path) return@launch
            result.onSuccess { t.raw = it }.onFailure {
                t.raw = emptyList()
                t.error = (if (S.zh) "無法開啟此資料夾：" else "Can't open this folder: ") + it.message
            }
            if (keepSelection) t.selection = t.selection.filter { s -> t.raw.any { it.path == s } }.toSet()
            t.loading = false
        }
    }

    fun newTab(path: String = HOME) {
        val t = TabState(path)
        tabs.add(Tab(t))
        tabIndex = tabs.size - 1
        load(t)
    }

    fun closeTab(i: Int) {
        tabs.getOrNull(i)?.let { closedTabs.add(it.location) }
        if (tabs.size == 1) {
            tabs[0] = Tab(TabState(HOME)); load(tabs[0].left); return
        }
        tabs.removeAt(i)
        if (tabIndex >= tabs.size) tabIndex = tabs.size - 1 else if (i < tabIndex) tabIndex--
    }

    /** Locations of closed tabs, newest last ("重新開啟已關閉的分頁"). */
    val closedTabs = mutableStateListOf<String>()

    fun reopenClosedTab() { closedTabs.removeLastOrNull()?.let { newTab(it) } }
    fun closeOtherTabs() { val keep = currentTab; tabs.filter { it !== keep }.forEach { closedTabs.add(it.location) }; tabs.retainAll { it === keep }; tabIndex = 0 }
    fun closeTabsRight() { val keep = tabs.take(tabIndex + 1); tabs.drop(tabIndex + 1).forEach { closedTabs.add(it.location) }; tabs.retainAll { it in keep } }
    fun closeTabsLeft() { val cur = currentTab; tabs.take(tabIndex).forEach { closedTabs.add(it.location) }; tabs.retainAll { tabs.indexOf(it) >= tabs.indexOf(cur) }; tabIndex = 0 }
    fun closeAllTabs() { tabs.forEach { closedTabs.add(it.location) }; tabs.clear(); tabs.add(Tab(TabState(HOME))); tabIndex = 0; load(tabs[0].left) }
    fun nextTab() { tabIndex = (tabIndex + 1) % tabs.size }
    fun prevTab() { tabIndex = (tabIndex - 1 + tabs.size) % tabs.size }

    /** Toolbar customization (the original's CustomToolbarItems) and the 自訂工具列 window. */
    val toolbarStore = ToolbarStore()
    var customizeToolbarOpen by mutableStateOf(false)
    /** Set by the window so the "全螢幕" command can reach it. */
    var toggleFullScreen: (() -> Unit)? = null

    /** "開啟檔案位置": go to the item's folder and select it (useful in search and tag views). */
    fun openFileLocation() {
        val p = tab.selection.firstOrNull() ?: return
        val parent = Fs.parent(p) ?: return
        val t = tab
        navigate(parent, t)
        scope.launch { delay(300); t.selection = setOf(p); t.anchor = p }
    }

    /** "解壓縮至此": unpack straight into the current folder. */
    fun extractHere() {
        val zips = tab.selection.filter { it.endsWith(".zip", true) }
        if (zips.isEmpty()) return
        val t = tab
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { zips.forEach { FsExtra.extractInto(File(it), File(it).parentFile) } } }
            toast = r.fold({ if (S.zh) "已解壓縮" else "Extracted" }, { (if (S.zh) "解壓縮失敗：" else "Extract failed: ") + it.message })
            load(t, keepSelection = true)
        }
    }

    /** "設為桌面背景": Windows wallpaper via SystemParametersInfo (SPI_SETDESKWALLPAPER). */
    fun setDesktopWallpaper() {
        val img = tab.selection.firstOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            val ps = "Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public static class W { [DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] public static extern int SystemParametersInfo(int a, int b, string c, int d); }'; " +
                "[W]::SystemParametersInfo(20, 0, '${img.replace("'", "''")}', 3) | Out-Null"
            Fs.powershell(ps, 10_000)
            withContext(Dispatchers.Main) { toast = if (S.zh) "已設為桌面背景" else "Set as desktop background" }
        }
    }

    /** "設定為應用程式背景": adds the picture(s) to this app's background images. */
    fun setAppBackground() {
        val imgs = tab.selection.filter { it.substringAfterLast('.', "").lowercase() in FsExtra.imageExt }
        if (imgs.isEmpty()) return
        backgroundImage = imgs.first()
        bgImages.addAll(imgs.filter { it !in bgImages })
        toast = if (S.zh) "已設定為應用程式背景" else "Set as app background"
    }

    fun toggleTheme() { theme = if (theme == ThemeMode.Dark) ThemeMode.Light else ThemeMode.Dark }

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

    /** Checkbox click: add or remove this one item, keeping the rest of the selection. */
    fun toggleSelect(item: FileItem) {
        val t = tab
        t.renaming = null
        t.selection = if (item.path in t.selection) t.selection - item.path else t.selection + item.path
        t.anchor = item.path
    }
    fun clearSelection() { tab.selection = emptySet() }
    fun invertSelection() { val all = visibleItems(tab).map { it.path }.toSet(); tab.selection = all - tab.selection }

    // ------------------------------------------------------------ file actions

    /** Creating and pasting need a real folder. */
    val canCreateHere get() = !tab.isVirtual

    fun copy(cut: Boolean = false) {
        val sel = tab.selection.toList()
        if (sel.isEmpty()) return
        clipboard = Clipboard(sel, cut)
        toast = if (S.zh) "已${if (cut) "剪下" else "複製"} ${sel.size} 個項目" else "${sel.size} item${if (sel.size == 1) "" else "s"} ${if (cut) "cut" else "copied"}"
    }

    fun paste() {
        val clip = clipboard ?: return
        if (!canCreateHere) return
        CopyEngine.submit(if (clip.cut) OpKind.Move else OpKind.Copy, clip.paths, tab.location)
        statusCenterOpen = true
        if (clip.cut) clipboard = null
    }

    fun newFolder() {
        if (!canCreateHere) return
        afterCreate(Fs.newFolder(tab.location, S.newFolder))
    }

    fun newFile() {
        if (!canCreateHere) return
        afterCreate(Fs.newFile(tab.location, if (S.zh) "新文字文件" else "New Text Document"))
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
        if (result == null) toast = (if (S.zh) "無法重新命名為「$newName」" else "Couldn't rename to \"$newName\"") else {
            tagStore.movePath(path, result)
            load(t)
            t.selection = setOf(result)
        }
    }

    fun requestDelete() {
        val sel = tab.selection.toList()
        if (sel.isEmpty()) return
        if (confirmDeleteAlways) confirmDelete = sel else doDelete(sel, false)
    }

    fun doDelete(paths: List<String>, permanent: Boolean) {
        confirmDelete = null
        val t = tab
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (permanent) { Fs.deletePermanently(paths); true } else Fs.recycle(paths)
            }
            tagStore.clear(paths.filter { !File(it).exists() })
            if (!ok) toast = if (S.zh) "部分項目無法移到資源回收筒" else "Some items couldn't be moved to the Recycle Bin"
            load(t)
        }
    }

    fun openSelected() {
        val t = tab
        val items = visibleItems(t).filter { it.path in t.selection }
        if (items.size == 1 && items[0].isDir) openFolder(items[0].path)
        else items.forEach { if (it.isDir) newTab(it.path) else Fs.open(it.path) }
    }

    fun copyPath() {
        val sel = tab.selection.ifEmpty { if (tab.isVirtual) emptySet() else setOf(tab.location) }
        if (sel.isEmpty()) return
        val text = sel.joinToString("\n") { "\"$it\"" }
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
        toast = if (S.zh) "已複製路徑" else "Path copied"
    }

    // ------------------------------------------------------------ zip

    fun compressSelected() {
        val sel = tab.selection.toList()
        if (sel.isEmpty()) return
        val dir = File(sel[0]).parentFile ?: return
        val base = if (sel.size == 1) File(sel[0]).nameWithoutExtension.ifEmpty { File(sel[0]).name } else dir.name.ifEmpty { "Archive" }
        val zip = FsExtra.uniqueSibling(dir, base, ".zip")
        val t = tab
        toast = if (S.zh) "正在壓縮…" else "Compressing…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FsExtra.compress(sel, zip) } }
            toast = r.fold({ if (S.zh) "已建立 ${zip.name}" else "Created ${zip.name}" }, { (if (S.zh) "壓縮失敗：" else "Compress failed: ") + it.message })
            load(t, keepSelection = true)
        }
    }

    fun extractSelected() {
        val zips = tab.selection.filter { it.endsWith(".zip", true) }
        if (zips.isEmpty()) return
        val t = tab
        toast = if (S.zh) "正在解壓縮…" else "Extracting…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { zips.map { FsExtra.extract(File(it)) } } }
            toast = r.fold({ if (S.zh) "已解壓縮到 ${it.joinToString { f -> f.name }}" else "Extracted to ${it.joinToString { f -> f.name }}" },
                { (if (S.zh) "解壓縮失敗：" else "Extract failed: ") + it.message })
            load(t, keepSelection = true)
        }
    }

    // ------------------------------------------------------------ pin / tags

    fun pin(path: String) {
        scope.launch {
            withContext(Dispatchers.IO) { FsExtra.pin(path) }
            val p = withContext(Dispatchers.IO) { Fs.quickAccess() }
            pinned = p
        }
    }

    fun unpin(path: String) {
        scope.launch {
            withContext(Dispatchers.IO) { FsExtra.unpin(path) }
            val p = withContext(Dispatchers.IO) { Fs.quickAccess() }
            pinned = p
        }
    }

    fun editTags() {
        val sel = tab.selection.toList()
        if (sel.isNotEmpty()) tagEditorFor = sel
    }

    // ------------------------------------------------------------ folder sizes

    private val folderSizeIo = Dispatchers.IO.limitedParallelism(M.FOLDER_SIZE_WORKERS)

    /** Adds up everything inside [path] in the background ("檢視大小" / Calculate size). */
    fun calcFolderSize(path: String) {
        if (folderSizing[path] == true) return
        folderSizing[path] = true
        scope.launch {
            val size = withContext(folderSizeIo) { FsExtra.folderSize(path) }
            folderSizes[path] = size
            folderSizing.remove(path)
        }
    }

    fun sizeOf(item: FileItem): Long? = if (item.isDir) folderSizes[item.path] else item.size

    fun gridLarger() { if (layout == LayoutMode.Grid) gridStep = (gridStep + 1).coerceAtMost(M.GRID_ITEM_WIDTH.size - 1) else detailsSize = (detailsSize + 1).coerceAtMost(4) }
    fun gridSmaller() { if (layout == LayoutMode.Grid) gridStep = (gridStep - 1).coerceAtLeast(0) else detailsSize = (detailsSize - 1).coerceAtLeast(0) }
}
