package app.files

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

enum class SortBy(val label: String) { Name("Name"), DateModified("Date modified"), DateCreated("Date created"), Size("Size"), Type("Type");
    val text: String get() = when (this) { Name -> S.name; DateModified -> S.dateModified; DateCreated -> S.dateCreated; Size -> S.size; Type -> S.type }
}
enum class LayoutMode(val label: String) { Details("Details"), List("List"), Cards("Cards"), Grid("Grid");
    val text: String get() = when (this) { Details -> S.details; List -> S.list; Cards -> S.cards; Grid -> S.grid }
}

const val HOME = "::home"
const val TAG_PREFIX = "::tag:"
const val SEARCH_PREFIX = "::search:"
/** The 網路 page: servers found on the network (see NetworkFs). */
const val NETWORK = "::network"
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
    val isNetwork: Boolean get() = location == NETWORK
    /** `\\server`: the list of a server's shares, not a folder. */
    val isServer: Boolean get() = NetworkFs.isServer(location)
    /** Home, a tag view, search results, 網路 or a server: not a real folder you can create files in. */
    val isVirtual: Boolean get() = location.startsWith("::") || isServer
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
    /** Git repository this folder is in (null when it isn't one). */
    var git by mutableStateOf<GitInfo?>(null)

    val title: String
        get() = when {
            isHome -> S.home
            isTag -> "${S.tags}: $tagName"
            isSearch -> "${S.search}: $searchQuery"
            isNetwork -> S.network
            isServer -> NetworkFs.hostOf(location).let { if (NetworkFs.isWslHost(it)) S.wsl else it }
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
    // Separate from showHidden: items with the System attribute (or a "$" name) stay hidden unless this is on.
    var showSystem by mutableStateOf(Prefs.bool("showSystem", false))
    // ---- storage statistics (StorageStats.kt / StorageUi.kt); recording is off by default
    var trackStorage by mutableStateOf(Prefs.bool("trackStorage", false))
    var storageHistoryLoaded by mutableStateOf(false)
    var storageSamples by mutableStateOf<List<StorageSample>>(emptyList())
    var storageReminder by mutableStateOf<String?>(null)
    var profileEntries by mutableStateOf<List<ProfileEntry>>(emptyList())
    var profileProgress by mutableStateOf<Pair<Int, Int>?>(null)
    var cleanupResults by mutableStateOf<List<CleanupResult>?>(null)
    var cleanupScanning by mutableStateOf(false)
    /** 預覽並清理: the plan being shown (SafeCleaner), the target being planned, and a running clean. */
    var cleanPlan by mutableStateOf<CleanPlan?>(null)
    var cleanPlanning by mutableStateOf<String?>(null)
    var cleaning by mutableStateOf(false)
    /** Opens 設定 at 儲存空間 (screenshot mode, --settings-storage). */
    var settingsStorageRequest by mutableStateOf(false)
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
    var showStorageWidget by mutableStateOf(Prefs.bool("w.storage", true))
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
    // ---- tab dragging (TabDrag.kt): where things are on screen, in window coordinates
    var tabDrag by mutableStateOf<TabDrag?>(null)
    val tabRects = mutableStateMapOf<Tab, androidx.compose.ui.geometry.Rect>()
    var tabStripRect by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    var paneAreaRect by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
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
    /** Icons WSL registered per distribution (see Fs.wslIconFiles); missing ones get a drawn glyph. */
    var wslIcons by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())

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
        scope.launch(Dispatchers.IO) {
            val w = Fs.wslDistros(); withContext(Dispatchers.Main) { wsl = w }
            val icons = Fs.wslIconFiles().filterKeys { it in w }.mapNotNull { (name, file) ->
                runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()?.let { name to it }
            }.toMap()
            withContext(Dispatchers.Main) { wslIcons = icons }
        }
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
                delay(bgIntervalMin.minutes)
                if (bgMode == BgMode.Timer && bgImages.size > 1) nextBackground()
            }
        }
        // Disk usage readings while 記錄磁碟空間使用量 is on: at once, then every 30 minutes.
        scope.launch {
            snapshotFlow { trackStorage }.collectLatest { on ->
                if (!on) { storageReminder = null; return@collectLatest }
                // Not at start-up: Home loads first.
                delay(M.STORAGE_FIRST_SAMPLE_DELAY_MS)
                while (true) {
                    recordStorage()
                    delay(M.STORAGE_SAMPLE_MINUTES.minutes)
                }
            }
        }
        // Save settings and the open tabs whenever they change.
        scope.launch {
            snapshotFlow { settingsSnapshot() }.drop(1).debounce(400.milliseconds).collect { Prefs.put(it) }
        }
    }

    private fun settingsSnapshot(): Map<String, Any?> = mapOf(
        "theme" to theme, "layout" to layout, "sortBy" to sortBy, "sortAscending" to sortAscending,
        "showHidden" to showHidden, "showSystem" to showSystem, "trackStorage" to trackStorage, "showExtensions" to showExtensions, "showCheckboxes" to showCheckboxes, "showThumbnails" to showThumbnails,
        "confirmDelete" to confirmDeleteAlways, "openFoldersInNewTab" to openFoldersInNewTab, "continueSession" to continueSession,
        "w.quickAccess" to showQuickAccessWidget, "w.drives" to showDrivesWidget, "w.network" to showNetworkWidget,
        "w.tags" to showTagsWidget, "w.recent" to showRecentWidget, "w.storage" to showStorageWidget, "showToolbar" to showToolbar, "showStatusBar" to showStatusBar,
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
        val b = scope.launch(Dispatchers.IO) { val p = withUserHome(Fs.quickAccess()); withContext(Dispatchers.Main) { pinned = p; pinnedLoaded = true } }
        val c = scope.launch(Dispatchers.IO) { val r = Fs.recentFiles(); withContext(Dispatchers.Main) { recent = r; recentLoaded = true } }
        scope.launch {
            joinAll(a, b, c)
            homeLoadMs = (System.nanoTime() - started) / 1_000_000
        }
    }

    /** System items follow 顯示受保護的作業系統檔案, other hidden items follow 顯示隱藏的項目. */
    fun isShown(f: FileItem) = when {
        f.system -> showSystem
        f.hidden -> showHidden
        else -> true
    }

    fun visibleItems(t: TabState): List<FileItem> {
        var list = t.raw
        if (!showHidden || !showSystem) list = list.filter { isShown(it) }
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

    fun navigate(target: String, t: TabState = tab) {
        if (NetworkFs.isNfsUrl(target) && !NetworkFs.nfsClientInstalled) { toast = S.netNfsMissing; return }
        val path = NetworkFs.normalize(target)
        // Network paths always open here (an unreachable one shows its error in the pane) instead
        // of falling back to Explorer; only files, and local paths that aren't folders, go to Windows.
        val network = NetworkFs.isUnc(path)
        if (!path.startsWith("::") && (if (network) File(path).isFile else !File(path).isDirectory)) {
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
        if (t.isServer) { navigate(if (NetworkFs.isWslHost(NetworkFs.hostOf(t.location))) HOME else NETWORK, t); return }
        if (t.isVirtual) { navigate(if (t.isSearch) t.searchRoot else HOME, t); return }
        val p = Fs.parent(t.location)
        navigate(p ?: HOME, t)
    }

    fun refresh(t: TabState = tab) {
        if (t.isHome) loadHome() else load(t, keepSelection = true)
        treeExpanded.filterValues { it }.keys.toList().forEach { loadTreeChildren(it) }
    }

    fun searchDeep(query: String) {
        val t = tab
        if (query.isBlank() || t.isHome) return
        val root = if (t.isSearch) t.searchRoot else if (t.isTag) return else t.location
        navigate(searchLocation(root, query.trim()), t)
    }

    fun load(t: TabState, keepSelection: Boolean = false) {
        t.loadJob?.cancel()
        t.git = null
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
        if (t.isNetwork) {
            t.raw = emptyList()
            t.loadJob = scope.launch {
                withContext(Dispatchers.IO) {
                    NetworkFs.discover(cancelled = { !isActive || t.location != path }) { list ->
                        launch(Dispatchers.Main) { if (t.location == path) t.raw = list }
                    }
                }
                if (t.location == path) t.loading = false
            }
            return
        }
        t.loadJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        t.isTag -> tagStore.filesWith(t.tagName).mapNotNull { FsExtra.item(it) }
                        t.isServer -> NetworkFs.serverContents(NetworkFs.hostOf(path), includeAdminShares = true)
                        else -> Fs.list(path)
                    }
                }
            }
            if (t.location != path) return@launch
            // A server that answered is worth listing on the 網路 page next time.
            if (result.isSuccess && NetworkFs.isUnc(path) && !NetworkFs.isWslHost(NetworkFs.hostOf(path))) withContext(Dispatchers.IO) { NetworkFs.remember(NetworkFs.hostOf(path)) }
            result.onSuccess { t.raw = it }.onFailure {
                t.raw = emptyList()
                t.error = (if (S.zh) "無法開啟此資料夾：" else "Can't open this folder: ") + it.message
            }
            if (keepSelection) t.selection = t.selection.filter { s -> t.raw.any { it.path == s } }.toSet()
            t.loading = false
            if (!t.isVirtual) refreshGit(t)
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
        scope.launch { delay(300.milliseconds); t.selection = setOf(p); t.anchor = p }
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

    // ------------------------------------------------------------ Git

    /** Message of the Git operation running now (shown in the status bar), or null. */
    var gitBusy by mutableStateOf<String?>(null)
    var cloneDialogOpen by mutableStateOf(false)

    /** Finds the repository of [t]'s folder and reads branch, ahead/behind and file status, in the background. */
    fun refreshGit(t: TabState = tab) {
        val path = t.location
        if (t.isVirtual) { t.git = null; return }
        scope.launch {
            val info = withContext(Dispatchers.IO) { Git.rootOf(path)?.let { Git.info(it) } }
            if (t.location == path) t.git = info
        }
    }

    private fun gitOp(running: String, done: String, failed: String, op: (GitInfo) -> GitResult) {
        val t = tab
        val g = t.git ?: return
        if (gitBusy != null) return
        gitBusy = running
        scope.launch {
            val r = withContext(Dispatchers.IO) { op(g) }
            gitBusy = null
            toast = if (r.ok) done else "$failed\n${r.output.lines().takeLast(3).joinToString("\n")}"
            allPanes().filter { it.git?.root == g.root }.forEach { load(it, keepSelection = true) }
        }
    }

    private fun repoName(g: GitInfo) = File(g.root).name

    fun gitFetch() = tab.git?.let { g ->
        gitOp(if (S.zh) "正在取得「${repoName(g)}」的遠端變更…" else "Fetching remote changes for \"${repoName(g)}\"…",
            if (S.zh) "已取得「${repoName(g)}」的遠端變更" else "Fetched remote changes for \"${repoName(g)}\"",
            if (S.zh) "無法取得遠端變更" else "Failed to fetch remote changes") { Git.fetch(it.root) }
    }

    fun gitPull() = tab.git?.let { g ->
        gitOp(if (S.zh) "正在將遠端變更拉取到「${repoName(g)}」…" else "Pulling remote changes into \"${repoName(g)}\"…",
            if (S.zh) "已將遠端變更拉取到「${repoName(g)}」" else "Pulled remote changes into \"${repoName(g)}\"",
            if (S.zh) "Pull 失敗" else "Failed to pull") { Git.pull(it.root) }
    }

    fun gitPush() = tab.git?.let { g ->
        gitOp(if (S.zh) "正在推送「${repoName(g)}」的本地 commit…" else "Pushing local commits from \"${repoName(g)}\"…",
            if (S.zh) "已推送「${repoName(g)}」的本地 commit" else "Pushed local commits from \"${repoName(g)}\"",
            if (S.zh) "Push 失敗" else "Failed to push") { Git.push(it.root, it.branch, it.hasUpstream, it.remotes.firstOrNull()) }
    }

    /** "同步": git pull, then git push (GitSyncDescription). */
    fun gitSync() = tab.git?.let { g ->
        gitOp(if (S.zh) "正在同步「${repoName(g)}」的本地與遠端 commit…" else "Syncing local and remote commits for \"${repoName(g)}\"…",
            if (S.zh) "已同步「${repoName(g)}」" else "Synced \"${repoName(g)}\"",
            if (S.zh) "同步失敗" else "Failed to sync") { gi ->
            val p = if (gi.hasUpstream) Git.pull(gi.root) else GitResult(true, "")
            if (!p.ok) p else Git.push(gi.root, gi.branch, gi.hasUpstream, gi.remotes.firstOrNull())
        }
    }

    fun gitCheckout(branch: String, remote: Boolean = false) = tab.git?.let { g ->
        gitOp(if (S.zh) "正在切換到「$branch」…" else "Switching to \"$branch\"…",
            if (S.zh) "已切換到「$branch」" else "Switched to \"$branch\"",
            if (S.zh) "無法切換到「$branch」" else "Failed to switch to \"$branch\"") {
            if (remote) Git.checkoutRemote(it.root, branch) else Git.checkout(it.root, branch)
        }
    }

    fun gitCreateBranch(name: String) {
        val n = name.trim()
        if (!Git.isValidBranchName(n)) { toast = if (S.zh) "無效的分支名稱" else "Invalid branch name"; return }
        gitOp(if (S.zh) "正在建立分支「$n」…" else "Creating branch \"$n\"…",
            if (S.zh) "已切換至新分支「$n」" else "Switched to new branch \"$n\"",
            if (S.zh) "無法建立分支" else "Couldn't create the branch") { Git.createBranch(it.root, n) }
    }

    fun gitDeleteBranch(name: String) = gitOp(if (S.zh) "正在刪除分支「$name」…" else "Deleting branch \"$name\"…",
        if (S.zh) "已刪除分支「$name」" else "Deleted branch \"$name\"",
        if (S.zh) "無法刪除分支" else "Couldn't delete the branch") { Git.deleteBranch(it.root, name) }

    /** "初始化 Git 儲存庫" for the current folder. */
    fun gitInit() {
        val t = tab
        if (t.isVirtual || t.git != null || gitBusy != null) return
        val path = t.location
        gitBusy = if (S.zh) "正在初始化儲存庫…" else "Initializing repository…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { Git.init(path) }
            gitBusy = null
            toast = if (r.ok) (if (S.zh) "已將此資料夾初始化為 Git 儲存庫" else "Initialized a Git repository here")
                    else (if (S.zh) "Files 無法將此目錄初始化為 Git 儲存庫" else "Can't initialize this folder as a Git repository")
            load(t, keepSelection = true)
        }
    }

    /** "複製儲存庫": git clone [url] into the current folder. */
    fun gitClone(url: String) {
        val t = tab
        val u = url.trim()
        if (u.isEmpty() || t.isVirtual || gitBusy != null) return
        val destination = t.location
        val name = u.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
        gitBusy = if (S.zh) "正在將儲存庫「$name」複製到「${Fs.displayName(t.location)}」…" else "Cloning \"$name\" to \"${Fs.displayName(t.location)}\"…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { Git.clone(u, destination) }
            gitBusy = null
            toast = if (r.ok) (if (S.zh) "已成功將儲存庫「$name」複製到「${Fs.displayName(t.location)}」" else "Cloned \"$name\" to \"${Fs.displayName(t.location)}\"")
                    else (if (S.zh) "無法建立儲存庫副本\n" else "Cannot clone repo\n") + r.output.lines().takeLast(3).joinToString("\n")
            load(t, keepSelection = true)
        }
    }

    // ------------------------------------------------------------ sidebar folder tree

    /** Which sidebar folders are expanded, and their subfolders once read. */
    val treeExpanded = mutableStateMapOf<String, Boolean>()
    val treeChildren = mutableStateMapOf<String, List<String>>()

    fun toggleTree(path: String) {
        val open = treeExpanded[path] != true
        treeExpanded[path] = open
        if (open) loadTreeChildren(path)
    }

    fun loadTreeChildren(path: String) {
        scope.launch {
            val kids = withContext(Dispatchers.IO) {
                runCatching {
                    Fs.list(path).filter { it.isDir && isShown(it) }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).map { it.path }
                }.getOrDefault(emptyList())
            }
            treeChildren[path] = kids
        }
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

    /** Servers and share roots are places, not files: never cut, delete or rename them. */
    private fun isNetworkPlace(path: String) = NetworkFs.isServer(path) || NetworkFs.isShareRoot(path)

    fun copy(cut: Boolean = false) {
        val sel = tab.selection.toList().filter { !cut || !isNetworkPlace(it) }
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
        if (sel.size == 1 && !isNetworkPlace(sel.first())) tab.renaming = sel.first()
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
        val sel = tab.selection.toList().filter { !isNetworkPlace(it) }
        if (sel.isEmpty()) return
        if (confirmDeleteAlways) confirmDelete = sel else doDelete(sel, false)
    }

    fun doDelete(all: List<String>, permanent: Boolean) {
        confirmDelete = null
        val paths = all.filter { !isNetworkPlace(it) }
        if (paths.isEmpty()) return
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

    // ------------------------------------------------------------ storage statistics

    private suspend fun recordStorage() {
        storageSamples = withContext(Dispatchers.IO) { StorageHistory.append(StorageHistory.readDrives()) }
        storageHistoryLoaded = true
        checkStorageReminder()
    }

    /** Once a day at most: a drive that grew 5 GB+ in 7 days, or has less than 10 % free. */
    private fun checkStorageReminder() {
        val week = 7L * 24 * 3600 * 1000
        val latest = storageSamples.filter { it.key != PROFILE_KEY }.groupBy { it.key }.mapValues { it.value.maxBy { s -> s.time } }
        // Low space first (most urgent), then the drive that grew the most.
        val low = latest.values.filter { it.total > 0 && (it.total - it.used) * 100 / it.total < M.STORAGE_LOW_FREE_PERCENT }
            .minByOrNull { (it.total - it.used).toDouble() / it.total }
            ?.let { S.storageLowFree(it.key, ((it.total - it.used) * 100 / it.total).toInt()) }
        val grew = latest.keys.mapNotNull { k -> StorageHistory.growth(storageSamples, k, week)?.let { k to it } }
            .filter { it.second >= M.STORAGE_GROWTH_ALERT_BYTES }.maxByOrNull { it.second }
            ?.let { S.storageGrewBy(it.first, Fs.sizeText(it.second)) }
        storageReminder = low ?: grew
        val today = java.time.LocalDate.now().toString()
        if (storageReminder != null && Prefs.str("storageReminderDay", "") != today) {
            Prefs.put(mapOf("storageReminderDay" to today))
            toast = storageReminder + (if (S.zh) "（設定 → 儲存空間）" else " (Settings → Storage)")
        }
    }

    /** Home → 儲存空間分析, once it is its turn: the recorded history and the clean-up sizes. */
    fun loadStorageOverview() {
        if (trackStorage && !storageHistoryLoaded) scope.launch {
            storageSamples = withContext(Dispatchers.IO) { StorageHistory.load() }
            storageHistoryLoaded = true
            checkStorageReminder()
        }
        if (cleanupResults == null) scanCleanup()
    }

    /** 分析使用者資料夾: sizes each top-level folder of C:\Users\name; read-only. */
    fun analyzeProfile() {
        if (profileProgress != null) return
        profileProgress = 0 to 1
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                scanProfile(cancelled = { !isActive }) { list, done, total ->
                    launch(Dispatchers.Main) { profileEntries = list; profileProgress = done to total }
                }
            }
            profileEntries = result
            profileProgress = null
            if (trackStorage) storageSamples = withContext(Dispatchers.IO) {
                StorageHistory.append(listOf(StorageSample(System.currentTimeMillis(), PROFILE_KEY, result.sumOf { it.bytes }, 0)))
            }
        }
    }

    /** 掃描清理建議: sizes the known caches present on this PC; read-only. */
    fun scanCleanup() {
        if (cleanupScanning) return
        cleanupScanning = true
        scope.launch {
            cleanupResults = withContext(Dispatchers.IO) { Cleanup.measure() }
            cleanupScanning = false
        }
    }

    /** 預覽並清理, step 1: find what would be removed (read-only) and show it. */
    fun planCleanup(target: CleanupTarget) {
        if (cleanPlanning != null || cleaning) return
        val blocker = target.safeDelete?.blockedBy
        if (blocker != null && Cleanup.isRunning(blocker)) { toast = S.closeFirst(blocker); return }
        cleanPlanning = target.id
        scope.launch {
            cleanPlan = withContext(Dispatchers.IO) { SafeCleaner.plan(target) }
            cleanPlanning = null
        }
    }

    /** Step 2, after the person confirms: remove the planned files, then rescan. */
    fun runCleanPlan() {
        val plan = cleanPlan ?: return
        cleanPlan = null
        val blocker = plan.target.safeDelete?.blockedBy
        if (blocker != null && Cleanup.isRunning(blocker)) { toast = S.closeFirst(blocker); return }
        cleaning = true
        scope.launch {
            val (n, freed) = withContext(Dispatchers.IO) { SafeCleaner.execute(plan) }
            cleaning = false
            toast = S.cleanedResult(n, Fs.sizeText(freed))
            scanCleanup()
        }
    }

    /**
     * 已釘選 = Windows' Quick access pins, with the user's own folder added first by default.
     * That entry belongs to this app (Explorer's Quick access is left alone); unpinning it is remembered.
     */
    private fun withUserHome(list: List<PinnedFolder>): List<PinnedFolder> =
        if (!Prefs.bool("pinUserHome", true) || list.any { isUserHome(it.path) }) list
        else listOf(PinnedFolder(Fs.displayName(userHome), userHome)) + list

    fun pin(path: String) {
        scope.launch {
            if (isUserHome(path)) Prefs.put(mapOf("pinUserHome" to true))
            else withContext(Dispatchers.IO) { FsExtra.pin(path) }
            val p = withContext(Dispatchers.IO) { withUserHome(Fs.quickAccess()) }
            pinned = p
        }
    }

    fun unpin(path: String) {
        scope.launch {
            if (isUserHome(path)) Prefs.put(mapOf("pinUserHome" to false))
            withContext(Dispatchers.IO) { FsExtra.unpin(path) }
            val p = withContext(Dispatchers.IO) { withUserHome(Fs.quickAccess()) }
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
