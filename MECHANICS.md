# Mechanics sheet — Files (Kotlin Multiplatform rebuild, Windows only)

Sources
- **Installed program**: `Files.exe` 4.3.0.0 (NativeAOT .NET, stripped, 51.7 MB). Strings confirm the
  settings names and the copy path (`ShellFileOperations2`, `IFileOperation`, `IFileOperationProgressSink`
  at 0x1413e6270 / 0x1413e80fa / 0x1413e68e3; `get_SidebarWidth` at 0x1415cd5b0; `ToggleShowHiddenItems`
  at 0x142bd9154). NativeAOT leaves no code references to these strings, so numeric defaults could not
  be read from the machine code; they are taken from the program's own open-source code (github.com/files-community/Files,
  default branch, read 2026-11-04), cited as `src:<file>:<line>`. Checked against the `v4.3` release tag
  (the installed version): every file these numbers come from is identical or only gained newer settings.
- The appinstaller only points at the download (version 4.0.28.0); the installed 4.3.0.0 was studied.

## Confirmed in the installed program's machine code (Files.exe 4.3.0.0)
| Rule | Value | Function / data |
|---|---|---|
| Sidebar width setting is read, clamped and written back: `width = min(max(Get("SidebarWidth", 255), 180), 500)` | default 255.0, min 180.0, max 500.0 | function 0x1401e4c80; doubles at 0x14255a7f0 / 0x14255a800 / 0x14255a810 |
| That clamp runs whenever the "SidebarWidth" setting changes | — | property-changed handler 0x14049c110 (compares the name with the string object at 0x142b952e0) |
Method: string literals are frozen objects 12 bytes before their characters; cross-references to the object
address lead to the code that uses each setting. Other defaults below come from the source code, which was
checked against the same release (v4.3).

## Window and layout
| Value | Number | Source |
|---|---|---|
| Minimum window size | 416 × 316 | src:MainWindow.xaml.cs:33-42 |
| Start window size | 1280 × 800 | estimated |
| Tab strip top margin / tab height | 10 / 32 | src:UserControls/TabBar/TabBar.xaml:77; WinUI default |
| Tab buttons | 30 × 30 | src:TabBar.xaml:99-113, 235-252 |
| Address bar height | 48 | src:UserControls/NavigationToolbar.xaml:67-71 |
| Nav buttons | 36 × 32, 14 px icons | src:NavigationToolbar.xaml:50-60,130 |
| Omnibar height / radius | 38 / 19 | src:Files.App.Controls/Omnibar/Omnibar.xaml:8-39 |
| Toolbar card radius / file pane radius | 8 / 8 | src:UserControls/Toolbar.xaml:57-63; Views/Shells/ModernShellPage.xaml:34-45 |
| Sidebar width default / min / max | 255 / 180 / 500 | src:Services/Settings/AppearanceSettingsService.cs:19; Constants.cs:77,79 |
| Sidebar row / pill / icon | 32 / 28 / 16 | src:Files.App.Controls/Sidebar/SidebarStyles.xaml:44-110 |
| Selection indicator | 3 × 16, accent | src:SidebarStyles.xaml:107 |
| Status bar min height | 32 | src:UserControls/StatusBar.xaml:29-34 |
| Info pane width (off by default) | 300 | src:Services/Settings/InfoPaneSettingsService.cs:10,16 |
| Content min width | 208 | src:Views/MainPage.xaml:168-191 |

## Layouts
| Value | Number | Source |
|---|---|---|
| Details row height (Compact..XL) | 28, 36*, 40, 44, 48 | src:Helpers/Layout/LayoutSizeKindHelper.cs:59-75 |
| Details icon | 16, 16*, 20, 24, 32 | LayoutSizeKindHelper.cs:20-24 |
| Details header | 40 | src:Views/Layouts/DetailsLayoutPage.xaml:263 |
| List row height | 24, 32*, 36, 40, 44 | LayoutSizeKindHelper.cs:121-137 |
| Cards icon | 64* | LayoutSizeKindHelper.cs:41-44 |
| Grid item width steps | 80…300 step 20, default 220 | LayoutSizeKindHelper.cs:83-113 |
| Columns: Name / Date modified / Type / Size | 240 / 200 / 140 / 100 | src:Services/Settings/LayoutSettingsService.cs:128-294 |
| Default sort | Name, ascending, folders first | LayoutSettingsService.cs:28-64 |
| Default layout | Adaptive (rebuild opens Details; Adaptive not built) | LayoutSettingsService.cs:22 |

## Home page look (person's screenshots of the original + widget XAML)
| Value | Number | Source |
|---|---|---|
| Quick access grid | min 100 wide, 72 tall, max 6 per row, stretched to fill, 8 spacing, 32 px icon, pin icon top-right | src:UserControls/Widgets/QuickAccessWidget.xaml:16-23,60-61 |
| Drives grid | 240 × 72, not stretched, 8 spacing, 32 px icon, "剩餘 {0}，共 {1}" | src:DrivesWidget.xaml:18-22; Strings/zh-Hant DriveFreeSpaceAndCapacity |
| Tags grid | min 220 wide, 260 tall, max 6 per row, stretched | src:FileTagsWidget.xaml:30-35 |
| Default tags | Home #0072BD, Work #D95319, Photos #EDB120, Important #77AC30 | src:Services/Settings/FileTagsSettingsService.cs:28-34 |
| No command toolbar on Home | — | person's screenshot |
| Sidebar sections | 首頁, 已釘選, 磁碟機, 雲端硬碟 (iCloud Drive, iCloud Photos), 網路, WSL (wsl -l), 標籤, 設定 at the bottom | person's screenshot; src:SidebarViewModel.cs:91-98 |
| Size units | KiB / MiB / GiB / TiB | person's screenshot |
| Light colours | window #EFEDED, file area #F8F7F7, cards #FDFDFD | measured from the screenshot (the original's Mica follows the wallpaper, so this is an approximation) |

## Tags, settings and extra features (round 3)
| Feature | Rule | Source |
|---|---|---|
| Default tags | Home #0072BD, Work #D95319, Photos #EDB120, Important #77AC30; unknown tag colour #9ea3a1 | src:FileTagsSettingsService.cs:28-34,83 |
| Tagging | right-click → Edit tags (tick on/off, make new tag with a colour); stored by path in %APPDATA%\FilesKmp; renames/moves in the app carry tags along | original stores by file ID in a database (FileTagsDatabase.cs) — rebuild uses paths |
| Tag column | 140 wide, shown by default in Details | src:LayoutSettingsService.cs:128-294 |
| Tag views | sidebar tag or Home "···" lists every file with that tag | src:SidebarViewModel tag section; FileTagsWidget |
| Settings | General (continue session, open folders in new tab, Home widgets), Appearance (theme, toolbar, status bar, sidebar width 180–500), Layout (default layout, sort), Files & folders (hidden items, extensions, checkboxes, ask before delete), Tags (add/rename/recolour/delete), About; saved at once to %APPDATA%\FilesKmp\settings.properties | defaults from src:*SettingsService.cs as listed below |
| Dual pane | Ctrl+Shift+S; each pane has its own history and selection; the active one has an accent outline | src:Actions ToggleDualPane (Ctrl+Shift+S) |
| Deep search | Enter in the search box searches the folder and every subfolder; results stream in; stops at 5000 (estimated) | original uses Windows Search; rebuild walks folders |
| Zip | Compress to ZIP / Extract here (never writes outside the target folder) | original: context menu Compress / Extract (7-Zip) |
| Pin / unpin | uses Windows' own Quick access verbs (pintohome / unpinfromhome), like the original | src:QuickAccessService |
| Preview | details pane draws pictures (< 40 MB, estimated) and the first 40 lines of text files | original preview pane |
| Reopen last tabs | on by default (ContinueLastSessionOnStartUp) | src:GeneralSettingsService.cs:28 |
| Shortcuts added | Ctrl+Shift+S dual pane, Ctrl+, settings | src:Actions HotKeys |

## Thumbnails (round 4)
| Rule | Value | Source |
|---|---|---|
| Show thumbnails | on by default; switch in Settings → Files & folders and in the Layout menu | src:FoldersSettingsService.cs:124 (ShowThumbnails = true) |
| Where they come from | Windows shell thumbnail (IShellItemImageFactory, "thumbnail only"), the same image Explorer shows — videos get a frame, photos keep their rotation; if Windows has none, the file icon stays | original asks the shell too (FileThumbnailHelper / IconService) |
| Sizes | 96 px for small views, 256 px for grid, cards and the details pane | 256 = ShellIconSizes.Jumbo (Constants.cs); 96 estimated |
| Corner radius | 2 | src:App.xaml:23-24 |
| Loading | 2 background helpers, newest request first, requests for rows scrolled away are dropped; cached in memory (400) and on disk (%LOCALAPPDATA%\FilesKmp\thumbs, keyed by path + size + date) | rebuild's own (estimated values) |
| Fallback | if the shell helper can't start, PNG/JPG/GIF/BMP/WebP are shrunk directly | rebuild's own |

## Panes, personalization, folder sizes, dates, checkboxes (round 5)
| Rule | Value | Source |
|---|---|---|
| Split pane vertically / horizontally | opens a second pane at the current folder; Vertical = side by side, Horizontal = one above the other | src:Actions/Navigation/SplitPaneVertically/HorizontallyAction.cs:40; ShellPanesPage.xaml.cs:302-345 |
| Default arrangement | Vertical | src:GeneralSettingsService.cs:420-422 |
| Arrange panes / close active pane / focus other pane | menu items in the 窗格 button; divider between panes 8 px, draggable 20–80 % | src:ArrangePanes*Action.cs, CloseActivePaneAction.cs, FocusOtherPane.cs |
| Backdrop | Solid / Mica / Mica Alt (default) / Acrylic / Thin acrylic — drawn as the blurred desktop wallpaper under the theme colour at 86 / 74 / 62 / 48 % cover (estimated) | src:AppearanceSettingsService.cs:93-95; BackdropMaterialType.cs |
| Background colour | 20 swatches, each tint at alpha 0x32 (Default = transparent) | src:Data/Factories/AppThemeResourcesFactory.cs:8-110 |
| Background image | file, fit UniformToFill (default) / Uniform / Fill / None, opacity 100 % default, centred | src:AppearanceSettingsService.cs:100-130 |
| Translucent surfaces | tab #B3FFFFFF, file area #C0FCFCFC, cards #B3FFFFFF (light) | src:App.xaml:120-155 |
| Folder size | "檢視大小" link (ItemSizeNotCalculated) adds the folder up on click; "Calculate folder sizes" (off by default) does it for every folder shown, 2 at a time | src:ListedItem.cs:283; FoldersSettingsService.cs:112 |
| Selection size in status bar | files plus measured folders | — |
| Dates | ≥ 7 days: long date ("D", e.g. 2026年7月6日 星期一); ≥ 2 days "N 天前"; ≥ 1 day "1 天前"; then hours, minutes, seconds; < 1 s "剛剛" | src:Services/DateTimeFormatter/ApplicationDateTimeFormatter.cs:24-46 |
| Type of folders | 資料夾 / Folder | person's screenshot |
| Tag column | right after Name, titled 檔案標籤 | person's screenshot |
| Checkboxes | shown on hover and on selected items; ticking one adds/removes only that item; header box selects all / clears; a plain click selects only that item and the click no longer reaches the list background (that was clearing the selection) | src:FoldersSettingsService.cs:154; verified with a simulated-click test (4/4) |

## Customizable toolbar (round 6, from the person's 32 screenshots + source)
| Rule | Value | Source |
|---|---|---|
| Open 自訂工具列 | Settings → 外觀 → 自訂工具列按鈕; right-click empty toolbar → 自訂工具列; right-click a button → 自訂工具列 / 取消釘選 (removes it at once) | src:AppearancePage.xaml:278-298; Toolbar.xaml:71-75; Toolbar.xaml.cs:465-487 |
| Window | 760 × 560; 選擇欲自訂的工具列模式 (240-wide picker); 可用選項 tree + 搜尋; 新增選項 list with 操作 / 圖示 / 文字標籤 / ×; preview strip; 重設為預設值 · 儲存 (only after a change) · 取消 | src:ToolbarCustomizationDialog.cs:17-67; ToolbarCustomizationPage.xaml |
| Adding / ordering | drag from the tree into the list (drop marker shows where), drag the ⠿ handle to reorder; rebuild also offers a "+" on each tree row | src:ToolbarCustomizationPage.xaml.cs:166-204 |
| Tree order | 分隔線 first, then categories and commands in plain character order | src:ToolbarItemDescriptor.cs:76-88 |
| Search | case-insensitive substring of the full label, flat list | src:ToolbarCustomizationViewModel.cs:86-92,127-140 |
| Per item | 圖示 (disabled if the command has no icon), 文字標籤; both off = hidden but kept | src:ToolbarItemSettingsEntry.cs:9-30; Toolbar.xaml.cs:206,267,299 |
| Contexts | 通用工具列 always; 壓縮檔案 / 批次檔案 / PowerShell 檔案 / 圖片檔 / 多媒體檔案 when the selection matches, each after a separator; 其他內容 when none match | src:ToolbarSections.cs:11-21,59-64; Toolbar.xaml.cs:150-191 |
| Defaults | 通用工具列: 新增 … (icon+label), 分隔線, 剪下, 複製, 貼上, 重新命名, 刪除, 內容 (icon only); 壓縮檔案: 解壓縮 …; 批次檔案: 在記事本中編輯; PowerShell 檔案: 以 PowerShell 執行; 圖片檔: 設為背景 …; 多媒體檔案: 播放 | src:ToolbarSections.cs:23-57 (分享 left out: Windows share sheet not available here) |
| Separators | none at the start/end, no doubles | src:Toolbar.xaml.cs:444-463 |
| Overflow | buttons that don't fit go into a "…" menu | src:Toolbar.xaml:82 |
| Storage | %APPDATA%\FilesKmp\toolbar.txt (context, command, icon, label per line); unknown contexts → 其他內容, unknown commands dropped | src:AppearanceSettingsService.cs:177-181; ToolbarSections.cs:124-131 |
| Commands available | about 90 across 主題, 介面, 分割畫面, 執行, 壓縮, 媒體, 導覽, 影像, 排序, 新增, 檔案操作, 版面配置, 視窗, 選取, 開啟 — only ones this app can run (Git, 7z, install font/driver/certificate, run as admin, Start menu, recycle-bin and grouping commands are not included) | ToolbarModel.kt |
| Verified | simulated drag of 剪下 to the end + 儲存 saved the new order | --test-toolbar-drag |

## Background picture rotation (round 6, person's request)
| Rule | Value |
|---|---|
| Pictures | a list (add files, or every picture in a folder); "使用" picks the current one |
| 切換方式 | 固定一張 / 定時切換 (every N minutes, default 10, 1–1440) / 每次開啟時切換 |
| 隨機順序 | optional shuffle (never the same picture twice in a row) |
| 依資料夾顯示背景 | folder → picture rules, optionally including subfolders; the most specific folder wins; Home, tag and search views use the normal picture |
| Toolbar | 下一張背景圖片 command (主題); 設定為應用程式背景 on pictures adds them to the list |

## Behaviour defaults
| Setting | Default | Source |
|---|---|---|
| Show hidden items | off | src:Services/Settings/FoldersSettingsService.cs:55 |
| Show file extensions | on | FoldersSettingsService.cs:118 |
| Checkboxes on selected items | on | FoldersSettingsService.cs:154 |
| Delete confirmation | always | FoldersSettingsService.cs:130 |
| Size units | binary (1024), shown as KB/MB | FoldersSettingsService.cs:161; Extensions/StringExtensions.cs:90 |
| Home widgets shown | Quick access, Drives, Network locations (collapsed), Recent files | src:Services/Settings/GeneralSettingsService.cs:110-166 |
| Low-space warning | ≥ 90 % used | src:Constants.cs:117 |
| Shortcuts | Ctrl+C/X/V, Del/Ctrl+D, Shift+Del, F2, Ctrl+Shift+N, Ctrl+T, Ctrl+W, F5/Ctrl+R, Alt+←/→/↑, Backspace, Ctrl+A, Ctrl+H, Ctrl+L/Alt+D, Ctrl+F/F3, Ctrl+Shift+1..4, Ctrl+1..9, Ctrl+Alt+I, Ctrl+B, Ctrl+Shift+C | src:Actions/** HotKey definitions |

## Why the original's Home page is slow (from its code)
1. Every time Home is shown (each tab switch, Back, Refresh) all five widgets fully reload: pinned folders, free space of every drive, Recent items with `refresh: true`, tags DB — src:Views/HomePage.xaml.cs:32-38, ViewModels/HomeViewModel.cs:193-195,279-283.
2. The reload is started on the UI thread (`DispatcherQueue.EnqueueOrInvokeAsync`) and Quick access does its whole refresh inside it — HomeViewModel.cs:193; QuickAccessWidgetViewModel.cs:75-128.
3. Drive cards are added and their thumbnails awaited one by one on the UI thread; free-space queries have no timeout, so a sleeping hard disk or offline share holds up the whole page — DrivesWidgetViewModel.cs:60-64,252-263; DriveItem.cs:338.
4. Recent files: no count limit, list rebuilt with one icon request per item, nested list not virtualised — RecentFilesWidgetViewModel.cs:186-222; RecentFilesWidget.xaml:36-48.
5. Discarded Home pages stay bound to shared widget collections (only Quick access detaches), so it gets slower the longer the app runs — QuickAccessWidget.xaml.cs:49-54.
On this PC: Desktop/Documents/Pictures live in OneDrive, and F: (SATA) and I: (USB) are spinning hard disks that sleep — exactly the cases 3 hits.

**Rebuild:** Home data loads once at start in three parallel background jobs and is kept; switching tabs or going back to Home never reloads; Refresh reloads on purpose. Each drive query has its own 1.5 s limit (a sleeping disk shows "not responding" instead of freezing the page). Recent files capped at 50.

## Why the original's copies are slow with several tasks (from its code)
- Copies are handed to the Windows shell copy engine (`IFileOperation`, out-of-process) on a new STA thread per task, with **no queue and no per-disk limit** — src:Utils/Shell/ShellFileOperations2.cs:31; Utils/Storage/Operations/FileOperationsHelpers.cs:565-580; FilesystemHelpers.cs:310-330.
- Each task also starts a parallel size scan that opens every source file while the copy runs — Utils/Storage/FileSizeCalculator.cs:30,118-125; FileOperationsHelpers.cs:553.
- Per-file callbacks (tags database, file ID lookup, shell item) run synchronously inside the copy — FileOperationsHelpers.cs:612-642, 994-1003.

**Rebuild copy engine (CopyEngine.kt):**
- One task at a time per *hard disk*; up to 4 at once per SSD (disk type from Windows' disk list); tasks on different disks run together.
- Sizes counted in the same pass that builds the plan (no competing scan); no per-file database work; UI reads progress every 100 ms.
- Large files (≥ 64 MB) touching a hard disk: 8 MB unbuffered chunks, 4 in flight, destination sized up front. SSD→SSD and small files use Windows' copy routine, which measured fastest there.
- Same-drive moves are renames.

Measured on this PC (2026-11-04), NVMe C: → C:, 3 × 2 GB:
| Test | Result |
|---|---|
| 1 file, Windows copy routine | 1.0–1.9 s (1100–2100 MB/s, varies run to run) |
| 1 file, own unbuffered engine | 1.4–1.8 s (1150–1500 MB/s) — slower on NVMe, so not used for SSD→SSD |
| 3 at once, uncoordinated (original's pattern) | 2.6–3.3 s (1870–2390 MB/s) |
| 3 via engine with SSD parallelism | 3.0–3.4 s (1800–2030 MB/s) |
Final run with every time including the flush to disk (FlushFileBuffers), 2026-11-04:
| Test | SSD C: → SATA hard disk F:, 3 × 512 MB | NVMe C: → C:, 3 × 1 GB |
|---|---|---|
| 1 file, Windows copy routine | 3.23 s, 158 MB/s | 0.41 s, 2481 MB/s |
| 1 file, this app | 3.29 s, 156 MB/s | 0.47 s, 2178 MB/s |
| 3 at once, uncoordinated (original's pattern) | **18.08 s, 85 MB/s** | 0.98 s, 3122 MB/s |
| 3 tasks, this app's engine | **9.84 s, 156 MB/s** | 1.04 s, 2957 MB/s |
All 8 + 8 copies verified byte-for-byte. Note: without the flush, Windows' routine looks 3 GB/s fast onto a hard disk because it
returns while data is still in memory; the disk then stays busy writing it afterwards.
The original app's own UI was not driven; its pattern (shell engine, unlimited simultaneous tasks) was reproduced from its code.
