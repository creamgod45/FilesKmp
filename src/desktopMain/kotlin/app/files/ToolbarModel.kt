package app.files

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File

/**
 * Toolbar contexts (ToolbarSections.cs:11-21). "通用工具列" always shows; the others show when
 * the selection makes them useful; "其他內容" shows when no specific context is active.
 */
enum class TbContext(private val zhName: String, private val enName: String) {
    AlwaysVisible("通用工具列", "General toolbar"),
    ArchiveFiles("壓縮檔案", "Archives"),
    BatchFiles("批次檔案", "Batch files"),
    PowerShellFiles("PowerShell 檔案", "PowerShell files"),
    ImageFiles("圖片檔", "Images"),
    PatchFiles("修補檔", "Patch files"),
    MediaFiles("多媒體檔案", "Media files"),
    OtherContexts("其他內容", "Other contexts");
    val text get() = if (S.zh) zhName else enName
}

/** Command categories as the 可用選項 tree shows them (ActionCategoryConverter). */
enum class TbCategory(private val zhName: String, private val enName: String) {
    Theme("主題", "Theme"), Ui("介面", "Interface"), Panes("分割畫面", "Panes"), Run("執行", "Run"),
    Archive("壓縮", "Archive"), Media("媒體", "Media"), Navigation("導覽", "Navigation"), Image("影像", "Image"),
    Sort("排序", "Sort"), Create("新增", "New"), FileOps("檔案操作", "File operations"), Layout("版面配置", "Layout"),
    Window("視窗", "Window"), Selection("選取", "Selection"), Open("開啟", "Open"), Git("Git", "Git"),
    /** The person's own commands and folders (ToolbarCustom.kt). */
    Custom("自訂", "Custom");
    val text get() = if (S.zh) zhName else enName
}

/** One toolbar command (or command group, shown as a drop-down "Name ..."). */
class TbCmd(
    val id: String,
    private val zhLabel: String,
    private val enLabel: String,
    val category: TbCategory,
    val icon: ImageVector?,
    val context: TbContext = TbContext.AlwaysVisible,
    val hotkey: String = "",
    val enabled: (AppState) -> Boolean = { true },
    val toggled: ((AppState) -> Boolean)? = null,
    val groupItems: ((AppState) -> List<MenuEntry>)? = null,
    val run: (AppState) -> Unit = {},
) {
    val label get() = if (S.zh) zhLabel else enLabel
    val isGroup get() = groupItems != null
    /** Tree / list text: groups get " ..." like the original. */
    val listLabel get() = if (isGroup) "$label ..." else label
}

const val SEPARATOR = "Separator"

private fun sel(s: AppState) = s.tab.selection.isNotEmpty()
private fun git(s: AppState) = s.tab.git != null && s.gitBusy == null
private fun selExt(s: AppState, exts: Set<String>) = s.tab.selection.any { it.substringAfterLast('.', "").lowercase() in exts }
private val audioExt = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma")
private val imageExtSet get() = FsExtra.imageExt + setOf("heic", "tif", "tiff")

/** Every command this app can run, with the original's Traditional Chinese labels (zh-Hant resources). */
object TbCommands {
    val all: List<TbCmd> = listOf(
        // 主題
        TbCmd("ToggleTheme", "切換主題", "Toggle theme", TbCategory.Theme, Icons.Outlined.Contrast, hotkey = "Ctrl+Alt+T", run = { it.toggleTheme() }),
        TbCmd("DarkTheme", "深色主題", "Dark theme", TbCategory.Theme, Icons.Outlined.DarkMode, toggled = { it.theme == ThemeMode.Dark }, run = { it.theme = ThemeMode.Dark }),
        TbCmd("LightTheme", "淺色主題", "Light theme", TbCategory.Theme, Icons.Outlined.LightMode, toggled = { it.theme == ThemeMode.Light }, run = { it.theme = ThemeMode.Light }),
        TbCmd("SystemTheme", "系統主題", "System theme", TbCategory.Theme, Icons.Outlined.SettingsBrightness, toggled = { it.theme == ThemeMode.System }, run = { it.theme = ThemeMode.System }),
        TbCmd("NextBackground", "下一張背景圖片", "Next background picture", TbCategory.Theme, Icons.Outlined.Wallpaper, enabled = { it.bgImages.size > 1 }, run = { it.nextBackground() }),
        // 介面
        TbCmd("ToggleToolbar", "切換工具列", "Toggle toolbar", TbCategory.Ui, null, hotkey = "Ctrl+Shift+B", run = { it.showToolbar = !it.showToolbar }),
        TbCmd("ToggleInfoPane", "切換詳細資料視窗", "Toggle details pane", TbCategory.Ui, Icons.Outlined.ViewSidebar, hotkey = "Ctrl+Alt+I", toggled = { it.infoPaneOpen }, run = { it.infoPaneOpen = !it.infoPaneOpen }),
        TbCmd("ToggleShowFileExtensions", "副檔名", "File extensions", TbCategory.Ui, Icons.Outlined.Extension, toggled = { it.showExtensions }, run = { it.showExtensions = !it.showExtensions }),
        TbCmd("ToggleSidebar", "開啟/關閉側邊欄", "Toggle sidebar", TbCategory.Ui, Icons.Outlined.VerticalSplit, hotkey = "Ctrl+B", toggled = { it.sidebarOpen }, run = { it.sidebarOpen = !it.sidebarOpen }),
        TbCmd("ToggleShowHiddenItems", "隱藏的項目", "Hidden items", TbCategory.Ui, Icons.Outlined.VisibilityOff, hotkey = "Ctrl+H", toggled = { it.showHidden }, run = { it.showHidden = !it.showHidden }),
        TbCmd("ToggleShowSystemItems", "系統檔案", "System files", TbCategory.Ui, Icons.Outlined.Shield, toggled = { it.showSystem }, run = { it.showSystem = !it.showSystem }),
        TbCmd("ToggleThumbnails", "顯示縮圖", "Show thumbnails", TbCategory.Ui, Icons.Outlined.Image, toggled = { it.showThumbnails }, run = { it.showThumbnails = !it.showThumbnails }),
        // 分割畫面
        TbCmd("SplitPaneHorizontally", "上下分割", "Split horizontally", TbCategory.Panes, Icons.Outlined.Splitscreen, run = { it.splitPane(PaneArrangement.Horizontal) }),
        TbCmd("SplitPaneVertically", "左右分割", "Split vertically", TbCategory.Panes, Icons.Outlined.VerticalSplit, hotkey = "Ctrl+Shift+S", run = { it.splitPane(PaneArrangement.Vertical) }),
        TbCmd("FocusOtherPane", "切換焦點到另一個分割畫面", "Focus other pane", TbCategory.Panes, Icons.Outlined.SwapHoriz, enabled = { it.currentTab.right != null }, run = { it.focusOtherPane() }),
        TbCmd("ArrangePanesHorizontally", "調整為上下分割模式", "Arrange panes horizontally", TbCategory.Panes, null, enabled = { it.currentTab.right != null },
            toggled = { it.currentTab.right != null && it.currentTab.arrangement == PaneArrangement.Horizontal }, run = { it.arrangePanes(PaneArrangement.Horizontal) }),
        TbCmd("ArrangePanesVertically", "調整為左右分割模式", "Arrange panes vertically", TbCategory.Panes, null, enabled = { it.currentTab.right != null },
            toggled = { it.currentTab.right != null && it.currentTab.arrangement == PaneArrangement.Vertical }, run = { it.arrangePanes(PaneArrangement.Vertical) }),
        TbCmd("CloseActivePane", "關閉分割視窗", "Close active pane", TbCategory.Panes, Icons.Outlined.Close, enabled = { it.currentTab.right != null }, run = { it.closeActivePane() }),
        TbCmd("ToggleDualPane", "顯示/隱藏分割畫面", "Show/hide split pane", TbCategory.Panes, Icons.Outlined.VerticalSplit, toggled = { it.currentTab.right != null }, run = { it.toggleDualPane() }),
        TbCmd("OpenInOtherPane", "在分割畫面另一側開啟", "Open in other pane", TbCategory.Panes, null, enabled = { s -> s.tab.selection.any { File(it).isDirectory } },
            run = { s -> val f = s.tab.selection.firstOrNull { File(it).isDirectory } ?: return@TbCmd
                if (s.currentTab.right == null) s.splitPane(s.defaultArrangement); s.focusOtherPane(); s.navigate(f) }),
        // 執行
        TbCmd("RunWithPowershell", "以 PowerShell 執行", "Run with PowerShell", TbCategory.Run, Icons.Outlined.Terminal, TbContext.PowerShellFiles,
            enabled = { selExt(it, setOf("ps1")) }, run = { s -> s.tab.selection.filter { it.endsWith(".ps1", true) }.forEach { p ->
                runCatching { ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "powershell.exe", "-NoExit", "-ExecutionPolicy", "Bypass", "-File", p).start() } } }),
        // 壓縮
        TbCmd("CompressIntoZip", "建立 zip 壓縮檔案", "Create zip archive", TbCategory.Archive, Icons.Outlined.FolderZip, enabled = { sel(it) && !it.tab.isHome }, run = { it.compressSelected() }),
        TbCmd("DecompressArchiveHere", "解壓縮至此", "Extract here", TbCategory.Archive, Icons.Outlined.Unarchive, TbContext.ArchiveFiles, enabled = { selExt(it, setOf("zip")) }, run = { it.extractHere() }),
        TbCmd("DecompressArchiveToChildFolder", "解壓縮至 \\", "Extract to folder", TbCategory.Archive, Icons.Outlined.Unarchive, TbContext.ArchiveFiles, enabled = { selExt(it, setOf("zip")) }, run = { it.extractSelected() }),
        TbCmd("Extract", "解壓縮", "Extract", TbCategory.Archive, Icons.Outlined.Unarchive, TbContext.ArchiveFiles, enabled = { selExt(it, setOf("zip")) },
            groupItems = { s -> listOf(
                MenuEntry(if (S.zh) "解壓縮至此" else "Extract here", Icons.Outlined.Unarchive) { s.extractHere() },
                MenuEntry(if (S.zh) "解壓縮至 \\" else "Extract to folder", Icons.Outlined.DriveFolderUpload) { s.extractSelected() },
            ) }),
        // 媒體
        TbCmd("PlayAll", "播放", "Play", TbCategory.Media, Icons.Outlined.PlayArrow, TbContext.MediaFiles, enabled = { selExt(it, Thumbnails.videoExt + audioExt) },
            run = { s -> s.tab.selection.forEach { Fs.open(it) } }),
        // 導覽
        TbCmd("NavigateBack", "返回/上一頁", "Back", TbCategory.Navigation, Icons.Outlined.ArrowBack, hotkey = "Alt+Left", enabled = { it.tab.canBack }, run = { it.back() }),
        TbCmd("NavigateForward", "前進/下一頁", "Forward", TbCategory.Navigation, Icons.Outlined.ArrowForward, hotkey = "Alt+Right", enabled = { it.tab.canForward }, run = { it.forward() }),
        TbCmd("NavigateUp", "返回上層", "Up", TbCategory.Navigation, Icons.Outlined.ArrowUpward, hotkey = "Alt+Up", enabled = { !it.tab.isHome }, run = { it.up() }),
        TbCmd("NavigateHome", "首頁", "Home", TbCategory.Navigation, Icons.Outlined.Home, run = { it.navigate(HOME) }),
        TbCmd("NewTab", "新索引標籤", "New tab", TbCategory.Navigation, Icons.Outlined.Add, hotkey = "Ctrl+T", run = { it.newTab() }),
        TbCmd("DuplicateCurrentTab", "複製標籤頁", "Duplicate tab", TbCategory.Navigation, Icons.Outlined.Tab, hotkey = "Ctrl+Shift+K", run = { it.newTab(it.tab.location) }),
        TbCmd("OpenInNewTab", "在新索引標籤中開啟", "Open in new tab", TbCategory.Navigation, Icons.Outlined.OpenInNew, enabled = { s -> s.tab.selection.any { File(it).isDirectory } },
            run = { s -> s.tab.selection.filter { File(it).isDirectory }.forEach { s.newTab(it) } }),
        TbCmd("NextTab", "下一分頁", "Next tab", TbCategory.Navigation, null, hotkey = "Ctrl+Tab", run = { it.nextTab() }),
        TbCmd("PreviousTab", "上一分頁", "Previous tab", TbCategory.Navigation, null, hotkey = "Ctrl+Shift+Tab", run = { it.prevTab() }),
        TbCmd("ReopenClosedTab", "重新開啟已關閉的分頁", "Reopen closed tab", TbCategory.Navigation, Icons.Outlined.History, hotkey = "Ctrl+Shift+T", enabled = { it.closedTabs.isNotEmpty() }, run = { it.reopenClosedTab() }),
        TbCmd("CloseSelectedTab", "關閉分頁", "Close tab", TbCategory.Navigation, Icons.Outlined.Close, hotkey = "Ctrl+W", run = { it.closeTab(it.tabIndex) }),
        TbCmd("CloseOtherTabsCurrent", "關閉其他索引標籤", "Close other tabs", TbCategory.Navigation, null, enabled = { it.tabs.size > 1 }, run = { it.closeOtherTabs() }),
        TbCmd("CloseTabsToTheRightCurrent", "關閉右邊的索引標籤", "Close tabs to the right", TbCategory.Navigation, null, enabled = { it.tabIndex < it.tabs.size - 1 }, run = { it.closeTabsRight() }),
        TbCmd("CloseTabsToTheLeftCurrent", "關閉左邊的索引標籤", "Close tabs to the left", TbCategory.Navigation, null, enabled = { it.tabIndex > 0 }, run = { it.closeTabsLeft() }),
        TbCmd("CloseAllTabs", "關閉所有分頁", "Close all tabs", TbCategory.Navigation, null, hotkey = "Ctrl+Shift+W", run = { it.closeAllTabs() }),
        // 影像
        TbCmd("SetAsWallpaperBackground", "設為桌面背景", "Set as desktop background", TbCategory.Image, Icons.Outlined.Wallpaper, TbContext.ImageFiles, enabled = { selExt(it, imageExtSet) }, run = { it.setDesktopWallpaper() }),
        TbCmd("SetAsAppBackground", "設定為應用程式背景", "Set as app background", TbCategory.Image, Icons.Outlined.Wallpaper, TbContext.ImageFiles, enabled = { selExt(it, imageExtSet) }, run = { it.setAppBackground() }),
        TbCmd("SetAs", "設為背景", "Set as", TbCategory.Image, Icons.Outlined.Wallpaper, TbContext.ImageFiles, enabled = { selExt(it, imageExtSet) },
            groupItems = { s -> listOf(
                MenuEntry(if (S.zh) "桌面" else "Desktop", Icons.Outlined.DesktopWindows) { s.setDesktopWallpaper() },
                MenuEntry(if (S.zh) "應用程式" else "App", Icons.Outlined.Wallpaper) { s.setAppBackground() },
            ) }),
        // 排序
        TbCmd("SortByName", "按名稱排序", "Sort by name", TbCategory.Sort, null, toggled = { it.sortBy == SortBy.Name }, run = { it.sortBy = SortBy.Name }),
        TbCmd("SortByDateModified", "依修改日期將項目排序", "Sort by date modified", TbCategory.Sort, null, toggled = { it.sortBy == SortBy.DateModified }, run = { it.sortBy = SortBy.DateModified }),
        TbCmd("SortByDateCreated", "依建立日期將項目排序", "Sort by date created", TbCategory.Sort, null, toggled = { it.sortBy == SortBy.DateCreated }, run = { it.sortBy = SortBy.DateCreated }),
        TbCmd("SortBySize", "按大小排序", "Sort by size", TbCategory.Sort, null, toggled = { it.sortBy == SortBy.Size }, run = { it.sortBy = SortBy.Size }),
        TbCmd("SortByType", "按類型排序", "Sort by type", TbCategory.Sort, null, toggled = { it.sortBy == SortBy.Type }, run = { it.sortBy = SortBy.Type }),
        TbCmd("SortAscending", "遞增", "Ascending", TbCategory.Sort, Icons.Outlined.ArrowUpward, toggled = { it.sortAscending }, run = { it.sortAscending = true }),
        TbCmd("SortDescending", "遞減", "Descending", TbCategory.Sort, Icons.Outlined.ArrowDownward, toggled = { !it.sortAscending }, run = { it.sortAscending = false }),
        TbCmd("ToggleSortDirection", "排序方向", "Sort direction", TbCategory.Sort, Icons.Outlined.SwapVert, run = { it.sortAscending = !it.sortAscending }),
        // 新增
        TbCmd("NewItem", "新增", "New", TbCategory.Create, Icons.Outlined.AddCircleOutline, enabled = { it.canCreateHere },
            groupItems = { s -> listOf(
                MenuEntry(S.folder, Icons.Filled.Folder, "Ctrl+Shift+N") { s.newFolder() },
                MenuEntry(S.textDocument, Icons.Outlined.Description) { s.newFile() },
            ) }),
        TbCmd("CreateFolder", "資料夾", "Folder", TbCategory.Create, Icons.Outlined.CreateNewFolder, hotkey = "Ctrl+Shift+N", enabled = { it.canCreateHere }, run = { it.newFolder() }),
        TbCmd("CreateFile", "檔案", "File", TbCategory.Create, Icons.Outlined.NoteAdd, enabled = { it.canCreateHere }, run = { it.newFile() }),
        // 檔案操作
        TbCmd("CutItem", "剪下", "Cut", TbCategory.FileOps, Icons.Outlined.ContentCut, hotkey = "Ctrl+X", enabled = { sel(it) }, run = { it.copy(cut = true) }),
        TbCmd("CopyItem", "複製", "Copy", TbCategory.FileOps, Icons.Outlined.ContentCopy, hotkey = "Ctrl+C", enabled = { sel(it) }, run = { it.copy() }),
        TbCmd("PasteItem", "貼上", "Paste", TbCategory.FileOps, Icons.Outlined.ContentPaste, hotkey = "Ctrl+V", enabled = { it.clipboard != null && it.canCreateHere }, run = { it.paste() }),
        TbCmd("Rename", "重新命名", "Rename", TbCategory.FileOps, Icons.Outlined.DriveFileRenameOutline, hotkey = "F2", enabled = { sel(it) }, run = { it.startRename() }),
        TbCmd("BatchRename", "批次重新命名", "Batch rename", TbCategory.FileOps, Icons.Outlined.DriveFileRenameOutline, hotkey = "Ctrl+Shift+F2", enabled = { sel(it) && !it.tab.isRecycleBin }, run = { it.openBatchRename() }),
        TbCmd("EditProperties", "編輯屬性與中繼資料", "Edit properties", TbCategory.FileOps, Icons.Outlined.EditNote, hotkey = "Ctrl+Shift+M", enabled = { sel(it) && !it.tab.isRecycleBin }, run = { it.openMetadata() }),
        TbCmd("DeleteItem", "刪除", "Delete", TbCategory.FileOps, Icons.Outlined.Delete, hotkey = "Delete", enabled = { sel(it) }, run = { it.requestDelete() }),
        TbCmd("DeleteItemPermanently", "永久刪除", "Delete permanently", TbCategory.FileOps, Icons.Outlined.DeleteForever, hotkey = "Shift+Delete", enabled = { sel(it) },
            run = { s -> s.doDelete(s.tab.selection.toList(), true) }),
        TbCmd("CopyPath", "複製路徑", "Copy path", TbCategory.FileOps, Icons.Outlined.Link, hotkey = "Ctrl+Shift+C", enabled = { !it.tab.isHome }, run = { it.copyPath() }),
        TbCmd("CopyPathWithQuotes", "複製帶引號的路徑", "Copy path with quotes", TbCategory.FileOps, Icons.Outlined.Link, enabled = { !it.tab.isHome }, run = { it.copyPath() }),
        TbCmd("EditTags", "編輯檔案標籤", "Edit tags", TbCategory.FileOps, Icons.Outlined.LocalOffer, enabled = { sel(it) },
            groupItems = { s -> s.tagStore.defs.map { d ->
                val on = s.tab.selection.isNotEmpty() && s.tab.selection.all { d.name in (s.tagStore.byPath[it] ?: emptySet()) }
                MenuEntry(d.name, if (on) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank) { s.tagStore.set(s.tab.selection, d.name, !on) }
            } + MenuEntry(S.editTags + "…", Icons.Outlined.Edit, separatorBefore = true) { s.editTags() } }),
        TbCmd("RefreshItems", "重新整理", "Refresh", TbCategory.FileOps, Icons.Outlined.Refresh, hotkey = "F5", run = { it.refresh() }),
        TbCmd("PinToSidebar", "釘選至側邊欄", "Pin to sidebar", TbCategory.FileOps, Icons.Outlined.PushPin,
            enabled = { s -> !s.tab.isVirtual || s.tab.selection.any { File(it).isDirectory } },
            run = { s -> (s.tab.selection.filter { File(it).isDirectory }.ifEmpty { listOf(s.tab.location) }).forEach { s.pin(it) } }),
        // 版面配置
        TbCmd("LayoutDetails", "詳細資料", "Details", TbCategory.Layout, Icons.Outlined.ViewHeadline, hotkey = "Ctrl+Shift+1", toggled = { it.layout == LayoutMode.Details }, run = { it.layout = LayoutMode.Details }),
        TbCmd("LayoutList", "清單", "List", TbCategory.Layout, Icons.Outlined.ViewList, hotkey = "Ctrl+Shift+2", toggled = { it.layout == LayoutMode.List }, run = { it.layout = LayoutMode.List }),
        TbCmd("LayoutCards", "卡片", "Cards", TbCategory.Layout, Icons.Outlined.ViewAgenda, hotkey = "Ctrl+Shift+3", toggled = { it.layout == LayoutMode.Cards }, run = { it.layout = LayoutMode.Cards }),
        TbCmd("LayoutGrid", "圖示", "Grid", TbCategory.Layout, Icons.Outlined.GridView, hotkey = "Ctrl+Shift+4", toggled = { it.layout == LayoutMode.Grid }, run = { it.layout = LayoutMode.Grid }),
        TbCmd("LayoutIncreaseSize", "放大", "Increase size", TbCategory.Layout, Icons.Outlined.ZoomIn, hotkey = "Ctrl++", run = { it.gridLarger() }),
        TbCmd("LayoutDecreaseSize", "縮小", "Decrease size", TbCategory.Layout, Icons.Outlined.ZoomOut, hotkey = "Ctrl+-", run = { it.gridSmaller() }),
        // 視窗
        TbCmd("EnterFullScreen", "全螢幕", "Full screen", TbCategory.Window, Icons.Outlined.Fullscreen, hotkey = "F11", run = { it.toggleFullScreen?.invoke() }),
        // 選取
        TbCmd("SelectAll", "選取全部", "Select all", TbCategory.Selection, Icons.Outlined.SelectAll, hotkey = "Ctrl+A", enabled = { !it.tab.isHome }, run = { it.selectAll() }),
        TbCmd("InvertSelection", "反選", "Invert selection", TbCategory.Selection, Icons.Outlined.FlipToBack, enabled = { !it.tab.isHome }, run = { it.invertSelection() }),
        TbCmd("ClearSelection", "清除選擇", "Clear selection", TbCategory.Selection, Icons.Outlined.Deselect, enabled = { sel(it) }, run = { it.clearSelection() }),
        // 開啟
        TbCmd("OpenItem", "開啟", "Open", TbCategory.Open, Icons.Outlined.FileOpen, hotkey = "Enter", enabled = { sel(it) }, run = { it.openSelected() }),
        TbCmd("OpenFileLocation", "開啟檔案位置", "Open file location", TbCategory.Open, Icons.Outlined.FolderOpen, enabled = { sel(it) }, run = { it.openFileLocation() }),
        TbCmd("OpenProperties", "內容", "Properties", TbCategory.Open, Icons.Outlined.Build, hotkey = "Alt+Enter", enabled = { sel(it) }, run = { it.infoPaneOpen = true }),
        TbCmd("OpenTerminal", "在 Windows 終端機開啟", "Open in Windows Terminal", TbCategory.Open, Icons.Outlined.Terminal, hotkey = "Ctrl+`", enabled = { !it.tab.isVirtual },
            run = { Fs.openInTerminal(it.tab.location) }),
        TbCmd("EditInNotepad", "在記事本中編輯", "Edit in Notepad", TbCategory.Open, Icons.Outlined.EditNote, TbContext.BatchFiles, enabled = { selExt(it, setOf("bat", "cmd")) },
            run = { s -> s.tab.selection.forEach { p -> runCatching { ProcessBuilder("notepad.exe", p).start() } } }),
        TbCmd("Search", "搜尋", "Search", TbCategory.Open, Icons.Outlined.Search, hotkey = "Ctrl+F", run = { it.searchRequest++ }),
        TbCmd("EditPath", "編輯路徑", "Edit path", TbCategory.Open, null, hotkey = "Ctrl+L", run = { it.editingPath = true }),
        TbCmd("OpenSettings", "設定", "Settings", TbCategory.Open, Icons.Outlined.Settings, hotkey = "Ctrl+,", run = { it.settingsOpen = true }),
        // Git (the old fixed Git button, now a toolbar item like any other)
        TbCmd("GitMenu", "Git", "Git", TbCategory.Git, Icons.Outlined.AccountTree, enabled = { !it.tab.isVirtual }, groupItems = { it.gitMenuEntries() }),
        TbCmd("GitFetch", "取得遠端變更", "Fetch", TbCategory.Git, Icons.Outlined.CloudDownload, enabled = { git(it) }, run = { it.gitFetch() }),
        TbCmd("GitPull", "拉取", "Pull", TbCategory.Git, Icons.Outlined.ArrowDownward, enabled = { git(it) }, run = { it.gitPull() }),
        TbCmd("GitPush", "推送", "Push", TbCategory.Git, Icons.Outlined.ArrowUpward, enabled = { git(it) }, run = { it.gitPush() }),
        TbCmd("GitSync", "同步", "Sync", TbCategory.Git, Icons.Outlined.Sync, enabled = { git(it) }, run = { it.gitSync() }),
        TbCmd("GitApplyPatch", "套用修補檔", "Apply patch", TbCategory.Git, Icons.Outlined.Healing, TbContext.PatchFiles, hotkey = "Ctrl+Alt+A", enabled = { git(it) }, run = { it.openApplyPatch() }),
        TbCmd("GitCreatePatch", "從提交紀錄建立 .patch", "Create .patch from history", TbCategory.Git, Icons.Outlined.History, hotkey = "Ctrl+Alt+P", enabled = { git(it) }, run = { it.gitCreatePatchOpen = true }),
        TbCmd("GitCreateBranch", "建立分支", "Create branch", TbCategory.Git, Icons.Outlined.Add, enabled = { git(it) }, run = { it.gitBranchDialog = true }),
        TbCmd("GitClone", "複製儲存庫", "Clone repository", TbCategory.Git, Icons.Outlined.ContentCopy, enabled = { !it.tab.isVirtual && it.gitBusy == null }, run = { it.cloneDialogOpen = true }),
        // 資源回收筒
        TbCmd("OpenRecycleBin", "資源回收筒", "Recycle Bin", TbCategory.FileOps, Icons.Outlined.Delete, hotkey = "Ctrl+Alt+R", run = { it.navigate(RECYCLE_BIN) }),
        TbCmd("RestoreItems", "還原", "Restore", TbCategory.FileOps, Icons.Outlined.RestoreFromTrash, enabled = { it.tab.isRecycleBin && sel(it) }, run = { it.restoreSelected() }),
        TbCmd("EmptyRecycleBin", "清空資源回收筒", "Empty Recycle Bin", TbCategory.FileOps, Icons.Outlined.DeleteSweep, hotkey = "Ctrl+Shift+Delete",
            enabled = { (it.recycleInfo?.first ?: 1L) > 0 && !it.emptyingRecycle }, run = { it.confirmEmptyRecycle = true }),
        // 捷徑
        TbCmd("Flows", "捷徑", "Shortcuts", TbCategory.Run, Icons.Outlined.Bolt,
            groupItems = { s -> FlowStore.all().map { f -> MenuEntry(f.name, Icons.Outlined.Bolt, f.hotkey) { s.runFlow(f) } } +
                MenuEntry(if (S.zh) "管理捷徑…" else "Manage shortcuts…", Icons.Outlined.Tune, "Ctrl+Alt+S", separatorBefore = true) { s.flowsOpen = true } }),
        TbCmd("OpenFlows", "管理捷徑", "Manage shortcuts", TbCategory.Run, Icons.Outlined.Tune, hotkey = "Ctrl+Alt+S", run = { it.flowsOpen = true }),
        // 預覽與說明
        TbCmd("QuickLook", "快速預覽", "Quick preview", TbCategory.Open, Icons.Outlined.Preview, hotkey = "Space", enabled = { sel(it) }, run = { it.toggleQuickLook() }),
        TbCmd("ShowShortcuts", "鍵盤快速鍵", "Keyboard shortcuts", TbCategory.Open, Icons.Outlined.Keyboard, hotkey = "F1", run = { it.shortcutsOpen = true }),
        TbCmd("CustomizeToolbar", "自訂工具列", "Customize toolbar", TbCategory.Open, null, run = { it.customizeToolbarOpen = true }),
    )

    private val byId = all.associateBy { it.id }
    /** A built-in command, or one of the person's own commands / folders. */
    fun get(id: String): TbCmd? = byId[id] ?: TbCustom.get(id)
}

/**
 * One toolbar slot: a command or a separator, with its 圖示 / 文字標籤 switches (ToolbarItemSettingsEntry).
 * [label] is the person's own name for a built-in command (重新命名); null keeps the command's name.
 */
data class TbEntry(val id: String, val showIcon: Boolean = true, val showLabel: Boolean = false, val label: String? = null) {
    val isSeparator get() = id == SEPARATOR
}

/** The name a slot shows: the person's rename, a custom item's own name, or the command's. */
fun TbEntry.displayLabel(cmd: TbCmd?): String = label ?: cmd?.label ?: id

/** The original's ToolbarDefaultsTemplate (ToolbarSections.cs:23-57), limited to commands this app has. */
fun toolbarDefaults(): Map<TbContext, List<TbEntry>> = mapOf(
    TbContext.AlwaysVisible to listOf(
        TbEntry("NewItem", true, true), TbEntry(SEPARATOR, false, false),
        TbEntry("CutItem"), TbEntry("CopyItem"), TbEntry("PasteItem"), TbEntry("Rename"),
        TbEntry("DeleteItem"), TbEntry("OpenProperties"), TbEntry(SEPARATOR, false, false), TbEntry("GitMenu", true, true), TbEntry("Flows", true, true),
    ),
    TbContext.ArchiveFiles to listOf(TbEntry("Extract", true, true)),
    TbContext.BatchFiles to listOf(TbEntry("EditInNotepad", true, true)),
    TbContext.PowerShellFiles to listOf(TbEntry("RunWithPowershell", true, true)),
    TbContext.ImageFiles to listOf(TbEntry("SetAs", true, true)),
    TbContext.PatchFiles to listOf(TbEntry("GitApplyPatch", true, true)),
    TbContext.MediaFiles to listOf(TbEntry("PlayAll", true, true)),
    TbContext.OtherContexts to emptyList(),
)

/** Saved toolbar layout per context (%APPDATA%\FilesKmp\toolbar.txt: context, id, icon, label per line). */
class ToolbarStore {
    private val file = File(Prefs.dir, "toolbar.txt")
    /** Set by [load] when it added the Git menu to an older saved layout; saved once below. */
    private var migrated = false
    var layout by mutableStateOf(load())
        private set

    init {
        if (migrated) save(layout)
    }

    private fun load(): Map<TbContext, List<TbEntry>> {
        if (!file.exists()) return toolbarDefaults()
        return try {
            val map = LinkedHashMap<TbContext, MutableList<TbEntry>>()
            TbContext.entries.forEach { map[it] = ArrayList() }
            file.readLines(Charsets.UTF_8).forEach { l ->
                val p = l.split('\t')
                if (p.size == 4 || p.size == 5) {
                    // Unknown contexts fold into 其他內容; unknown commands are dropped (ToolbarSections.cs:124-131).
                    val ctx = runCatching { TbContext.valueOf(p[0]) }.getOrDefault(TbContext.OtherContexts)
                    // Optional 5th column: the person's name for the slot (base64, UTF-8).
                    val label = p.getOrNull(4)?.takeIf { it.isNotEmpty() }
                        ?.let { runCatching { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
                    if (p[1] == SEPARATOR || TbCommands.get(p[1]) != null) map[ctx]!!.add(TbEntry(p[1], p[2] == "1", p[3] == "1", label))
                }
            }
            if (!Prefs.bool("tb.gitMenuAdded", false)) {
                if (map.values.none { l -> l.any { it.id.startsWith("Git") } }) {
                    map[TbContext.AlwaysVisible]!!.add(TbEntry("GitMenu", true, true))
                    migrated = true
                }
                if (!Prefs.readOnly) Prefs.put(mapOf("tb.gitMenuAdded" to true))
            }
            map
        } catch (_: Exception) { toolbarDefaults() }
    }

    fun save(newLayout: Map<TbContext, List<TbEntry>>) {
        layout = newLayout
        if (Prefs.readOnly) return
        try {
            file.writeText(TbContext.entries.flatMap { c ->
                (newLayout[c] ?: emptyList()).map {
                    "${c.name}\t${it.id}\t${if (it.showIcon) 1 else 0}\t${if (it.showLabel) 1 else 0}" +
                        (it.label?.let { l -> "\t" + java.util.Base64.getEncoder().encodeToString(l.toByteArray(Charsets.UTF_8)) } ?: "")
                }
            }.joinToString("\n"), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    /** "取消釘選" on a toolbar button: removes it and saves at once. */
    fun unpin(ctx: TbContext, index: Int) {
        val m = layout.toMutableMap()
        m[ctx] = (m[ctx] ?: emptyList()).filterIndexed { i, _ -> i != index }
        save(m)
    }
}

/** Which contexts show for the current selection (Toolbar.xaml.cs:150-191). */
fun activeContexts(s: AppState): List<TbContext> {
    val specific = TbContext.entries.filter { c ->
        c != TbContext.AlwaysVisible && c != TbContext.OtherContexts && when (c) {
            TbContext.ArchiveFiles -> selExt(s, setOf("zip"))
            TbContext.BatchFiles -> selExt(s, setOf("bat", "cmd"))
            TbContext.PowerShellFiles -> selExt(s, setOf("ps1"))
            TbContext.ImageFiles -> selExt(s, imageExtSet)
            TbContext.PatchFiles -> s.tab.git != null && selExt(s, setOf("patch", "diff"))
            TbContext.MediaFiles -> selExt(s, Thumbnails.videoExt + audioExt)
            else -> false
        }
    }
    return listOf(TbContext.AlwaysVisible) + specific.ifEmpty { listOf(TbContext.OtherContexts) }
}

/** Separators at the start, at the end and doubled ones are dropped (Toolbar.xaml.cs:444-463). */
fun cleanSeparators(items: List<Pair<TbContext, Pair<Int, TbEntry>>>): List<Pair<TbContext, Pair<Int, TbEntry>>> {
    val out = ArrayList<Pair<TbContext, Pair<Int, TbEntry>>>()
    for (it in items) {
        if (it.second.second.isSeparator && (out.isEmpty() || out.last().second.second.isSeparator)) continue
        out += it
    }
    while (out.isNotEmpty() && out.last().second.second.isSeparator) out.removeAt(out.size - 1)
    return out
}
