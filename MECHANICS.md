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
