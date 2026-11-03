package app.files

/**
 * Numbers and defaults taken from the original program (see MECHANICS.md for
 * where each one comes from). Kept in one place so mechanics.json and the
 * code agree.
 */
object M {
    // Window
    const val WINDOW_MIN_W = 416
    const val WINDOW_MIN_H = 316
    const val WINDOW_W = 1280            // estimated start size
    const val WINDOW_H = 800             // estimated start size

    // Title / tab strip
    const val TAB_STRIP_TOP_MARGIN = 10
    const val TAB_HEIGHT = 32
    const val TAB_BUTTON = 30

    // Address bar (navigation toolbar + omnibar)
    const val ADDRESS_BAR_HEIGHT = 48
    const val NAV_BUTTON_W = 36
    const val NAV_BUTTON_H = 32
    const val NAV_ICON = 14
    const val OMNIBAR_HEIGHT = 38
    const val OMNIBAR_RADIUS = 19

    // Customize toolbar window (ToolbarCustomizationDialog.cs:17-67)
    const val TOOLBAR_DIALOG_W = 760
    const val TOOLBAR_DIALOG_H = 560

    // Command toolbar card
    const val CARD_RADIUS = 8
    const val TOOLBAR_HEIGHT = 48        // estimated (WinUI CommandBar compact height + padding)

    // Sidebar
    const val SIDEBAR_WIDTH = 255
    const val SIDEBAR_MIN = 180
    const val SIDEBAR_MAX = 500
    const val SIDEBAR_ROW = 32
    const val SIDEBAR_PILL = 28
    const val SIDEBAR_ICON = 16
    const val SIDEBAR_INDICATOR_W = 3
    const val SIDEBAR_INDICATOR_H = 16
    const val CONTROL_RADIUS = 4

    // Status bar
    const val STATUS_BAR_HEIGHT = 32

    // Info (details) pane
    const val INFO_PANE_WIDTH = 300
    const val INFO_PANE_MIN = 100
    const val CONTENT_MIN_WIDTH = 208

    // Layouts
    val DETAILS_ROW = intArrayOf(28, 36, 40, 44, 48)   // Compact, Small(default), Medium, Large, ExtraLarge
    val DETAILS_ICON = intArrayOf(16, 16, 20, 24, 32)
    const val DETAILS_DEFAULT_SIZE = 1                  // Small
    const val DETAILS_HEADER = 40
    val LIST_ROW = intArrayOf(24, 32, 36, 40, 44)
    const val LIST_DEFAULT_SIZE = 1
    val CARDS_ICON = intArrayOf(64, 64, 64, 80, 96)     // index aligned with size kind; Small=64 default
    val GRID_ITEM_WIDTH = intArrayOf(80, 100, 120, 140, 160, 180, 200, 220, 240, 260, 280, 300)
    const val GRID_DEFAULT_STEP = 7                     // 220 = Large (default)

    // Details columns (default widths)
    const val COL_NAME = 240
    const val COL_DATE_MODIFIED = 200
    const val COL_TYPE = 140
    const val COL_SIZE = 100
    const val COL_TAG = 140                  // LayoutSettingsService.cs:128-294 (Tag column, shown by default)

    // Home widgets (UniformGridLayout settings in the widget XAML files)
    const val WIDGET_SPACING = 8f
    const val QA_MIN_W = 100            // QuickAccessWidget.xaml:16-23
    const val QA_H = 72
    const val QA_MAX_COLS = 6
    const val QA_ICON = 32
    const val TAG_MIN_W = 220           // FileTagsWidget.xaml:30-35
    const val TAG_H = 260
    const val TAG_MAX_COLS = 6
    const val DRIVE_TILE_MIN_W = 240
    const val DRIVE_TILE_MIN_H = 72
    const val DRIVE_ICON = 32
    const val LOW_SPACE_PERCENT = 90

    // Home loading (rebuild's own fix for the slow Home page)
    const val DRIVE_QUERY_TIMEOUT_MS = 1500L
    const val SHELL_QUERY_TIMEOUT_MS = 6000L
    const val RECENT_FILES_LIMIT = 50
    // Thumbnails (shell thumbnails like Explorer; ShowThumbnails on by default: FoldersSettingsService.cs:124)
    const val THUMB_SMALL = 96            // px requested for small views (estimated)
    const val THUMB_LARGE = 256           // px for grid / cards / preview (Constants.cs ShellIconSizes.Jumbo = 256)
    const val THUMB_RADIUS = 2            // App.xaml:23-24 thumbnail corner radius
    const val THUMB_WORKERS = 2           // helper processes (estimated)
    const val THUMB_MEMORY_ITEMS = 400    // thumbnails kept in memory (estimated)
    const val BG_DEFAULT_INTERVAL_MIN = 10 // background picture timer (estimated)
    const val MICA_BLUR = 18f             // blur on the 320-px wallpaper copy (estimated)
    const val FOLDER_SIZE_WORKERS = 2     // folders measured at once (estimated)
    const val PREVIEW_MAX_BYTES = 40L * 1024 * 1024   // pictures larger than this aren't decoded for preview (estimated)
    const val SEARCH_LIMIT = 5000          // deep search stops after this many results (estimated)
    const val TAG_WIDGET_FILES = 8         // files listed per tag card on Home (estimated)

    // Copy engine (rebuild's own, FastCopy-style)
    const val COPY_BUFFER_BYTES = 8 * 1024 * 1024
    const val COPY_IO_DEPTH = 4                         // reads/writes in flight per large file
    const val COPY_PREALLOCATE = true
    const val SSD_PARALLEL_JOBS = 4                     // tasks at once on one SSD; HDDs get 1
    const val DIRECT_IO_THRESHOLD = 64L * 1024 * 1024
    const val PROGRESS_INTERVAL_MS = 100L
}
