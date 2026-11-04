package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Cursor
import java.awt.Dimension
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot

fun main(args: Array<String>) {
    AppDirs.init()
    // Test and screenshot runs never write settings, tags or the toolbar (session, remembered servers, reminders, …).
    if ("--test-titlebar" in args || "--screenshot" in args) Prefs.readOnly = true
    val shotIdx = args.indexOf("--screenshot")
    if (shotIdx >= 0 && shotIdx + 1 < args.size) {
        val start = args.filterIndexed { i, a -> i != shotIdx + 1 && !a.startsWith("--") && File(a).isDirectory }.firstOrNull()
        val layout = args.indexOf("--layout").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
            LayoutMode.entries.firstOrNull { it.name.equals(args[i + 1], true) }
        }
        val interact: (suspend (ImageComposeScene, AppState) -> Unit)? = when {
            "--test-checkbox" in args -> ::checkboxTest
            "--test-toolbar-drag" in args -> ::toolbarDragTest
            "--test-toolbar-delete" in args -> ::toolbarDeleteTest
            "--test-tab-drag" in args -> ::tabDragTest
            "--test-sidebar" in args -> ::sidebarTreeTest
            // --click x,y: one left click there (opens a menu or dialog for the picture).
            args.indexOf("--click") >= 0 -> args.getOrNull(args.indexOf("--click") + 1)?.split(',')?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 2 }?.let { (x, y) ->
                    { scene: ImageComposeScene, _: AppState ->
                        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                        run {
                            val at = androidx.compose.ui.geometry.Offset(x, y)
                            val P = androidx.compose.ui.input.pointer.PointerEventType
                            scene.sendPointerEvent(P.Move, at)
                            scene.sendPointerEvent(P.Press, at, buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true))
                            scene.sendPointerEvent(P.Release, at)
                        }
                    }
                }
            // --hover x,y: rest the pointer there for the picture (tooltips, hover states).
            args.indexOf("--hover") >= 0 -> args.getOrNull(args.indexOf("--hover") + 1)?.split(',')?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 2 }?.let { (x, y) ->
                    { scene: ImageComposeScene, _: AppState ->
                        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(x, y))
                    }
                }
            else -> null
        }
        screenshot(File(args[shotIdx + 1]), start, layout, "--info-pane" in args, interact) { s ->
            if ("--dual" in args) s.toggleDualPane()
            if ("--hsplit" in args) s.splitPane(PaneArrangement.Horizontal)
            if ("--sizes" in args) s.calculateFolderSizes = true
            // Any location, including ones that aren't local folders: ::network, \\server, smb://server/share.
            args.indexOf("--open").takeIf { it >= 0 && it + 1 < args.size }?.let { s.navigate(args[it + 1]) }
            args.firstOrNull { it.startsWith("--show-hidden=") }?.let { s.showHidden = it.endsWith("=1") }
            args.firstOrNull { it.startsWith("--show-system=") }?.let { s.showSystem = it.endsWith("=1") }
            if ("--customize" in args) s.customizeToolbarOpen = true
            if ("--settings" in args) s.settingsOpen = true
            if ("--settings-storage" in args) { s.settingsStorageRequest = true; s.settingsOpen = true }
            if ("--scan-cleanup" in args) s.scanCleanup()
            if ("--track-storage" in args) s.trackStorage = true
            if ("--theme-dark" in args) s.theme = ThemeMode.Dark
            if ("--analyze-profile" in args) s.analyzeProfile()
            if ("--demo-custom-toolbar" in args) demoCustomToolbar(s)
            // Preview only (read-only): opens the 預覽並清理 dialog for one clean-up target.
            args.indexOf("--plan-cleanup").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
                Cleanup.targets().firstOrNull { it.id == args[i + 1] }?.let { s.planCleanup(it) }
            }
            if ("--clone-dialog" in args) s.cloneDialogOpen = true
            if ("--select-first" in args) s.scope.launch { delay(1200.milliseconds); s.tab.raw.firstOrNull()?.let { s.tab.selection = setOf(it.path) } }
        }
        exitProcess(0)
    }
    if ("--test-titlebar" in args) { titleBarTest(); exitProcess(0) }
    if ("--test-custom-persist" in args) { customPersistTest(write = "write" in args); exitProcess(0) }
    args.indexOf("--test-custom-command").takeIf { it >= 0 && it + 1 < args.size }?.let { customCommandTest(File(args[it + 1])); exitProcess(0) }
    args.indexOf("--test-safe-cleaner").takeIf { it >= 0 && it + 1 < args.size }?.let { safeCleanerTest(File(args[it + 1])); exitProcess(0) }
    val benchIdx = args.indexOf("--bench-copy")
    if (benchIdx >= 0 && benchIdx + 1 < args.size) {
        Bench.run(args.drop(benchIdx + 1))
        exitProcess(0)
    }
    val startPath = args.firstOrNull { !it.startsWith("--") && File(it).isDirectory }
    val dark = systemUsesDarkTheme()
    val accent = systemAccent()
    val appIcon = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.svg")
        ?.use { loadSvgPainter(it, Density(1f)) }
    application {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope).also { s -> startPath?.let { s.navigate(File(it).absolutePath) } } }
        val windowState = rememberWindowState(size = DpSize(M.WINDOW_W.dp, M.WINDOW_H.dp), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Files",
            icon = appIcon,
            undecorated = true,
            resizable = true,
            onPreviewKeyEvent = { handleKey(state, it) },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(M.WINDOW_MIN_W, M.WINDOW_MIN_H)
                WindowCorners.round(window)
                // "全螢幕" (F11) toggles between full screen and the normal window.
                state.toggleFullScreen = {
                    windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                }
            }
            val controls = remember {
                WindowControls(
                    state = windowState,
                    minimize = { windowState.isMinimized = true },
                    close = ::exitApplication,
                    drag = @Composable { m, c -> TitleDragArea(window, windowState, m, c) },
                )
            }
            CompositionLocalProvider(LocalWindowControls provides controls) {
                Box(Modifier.fillMaxSize()) {
                    Root(state, dark, accent)
                    if (windowState.placement != WindowPlacement.Maximized) ResizeEdges(windowState)
                }
            }
        }
    }
}

/**
 * The empty part of the tab strip, behaving like a Windows title bar: drag to move the window,
 * double-click to maximize and again to restore (Windows' double-click speed), drag a maximized
 * window to restore it under the pointer, and let go at the top of the screen to maximize.
 */
@Composable
fun TitleDragArea(window: java.awt.Window, ws: WindowState, modifier: Modifier, content: @Composable () -> Unit) {
    val lastPress = remember { longArrayOf(0L) }
    fun toggleMaximized() {
        ws.placement = if (ws.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
    }
    Box(modifier.pointerInput(Unit) {
        val doubleClickMs = (java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int ?: 500).toLong()
        awaitEachGesture {
            val down = awaitFirstDown()
            if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            if (down.uptimeMillis - lastPress[0] <= doubleClickMs) {
                lastPress[0] = 0L
                toggleMaximized()
                return@awaitEachGesture
            }
            lastPress[0] = down.uptimeMillis
            val start = java.awt.MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            var origin = window.location
            var moving = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                if (!change.pressed) break
                val now = java.awt.MouseInfo.getPointerInfo()?.location ?: continue
                if (!moving) {
                    if (kotlin.math.abs(now.x - start.x) + kotlin.math.abs(now.y - start.y) < 4) continue
                    moving = true
                    lastPress[0] = 0L
                    if (ws.placement == WindowPlacement.Maximized) {
                        // Restore, keeping the pointer at the same place across the title bar.
                        val ratio = (start.x - window.x).toDouble() / window.width.coerceAtLeast(1)
                        val floatingWidth = ws.size.width.value
                        ws.placement = WindowPlacement.Floating
                        origin = java.awt.Point((start.x - ratio * floatingWidth).toInt(), window.y)
                        ws.position = WindowPosition(origin.x.dp, origin.y.dp)
                    }
                }
                window.setLocation(origin.x + now.x - start.x, origin.y + now.y - start.y)
            }
            // Like Windows' snap: let go at the top edge of the screen to maximize.
            if (moving) {
                val p = java.awt.MouseInfo.getPointerInfo()?.location
                val screen = window.graphicsConfiguration?.bounds
                if (p != null && screen != null && p.y <= screen.y + 1) ws.placement = WindowPlacement.Maximized
            }
        }
    }) { content() }
}

@Composable
fun ResizeEdges(ws: WindowState) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.CenterEnd).width(5.dp).fillMaxHeight()
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); ws.size = DpSize((ws.size.width + d.x.toDp()).coerceAtLeast(M.WINDOW_MIN_W.dp), ws.size.height) } }
        )
        Box(
            Modifier.align(Alignment.BottomCenter).height(5.dp).fillMaxWidth()
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.S_RESIZE_CURSOR)))
                .pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); ws.size = DpSize(ws.size.width, (ws.size.height + d.y.toDp()).coerceAtLeast(M.WINDOW_MIN_H.dp)) } }
        )
        Box(
            Modifier.align(Alignment.BottomEnd).size(12.dp)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectDragGestures { c, d ->
                        c.consume()
                        ws.size = DpSize((ws.size.width + d.x.toDp()).coerceAtLeast(M.WINDOW_MIN_W.dp), (ws.size.height + d.y.toDp()).coerceAtLeast(M.WINDOW_MIN_H.dp))
                    }
                }
        )
    }
}

@Composable
fun Root(s: AppState, systemDark: Boolean, accent: androidx.compose.ui.graphics.Color?) {
    // Theme setting: follow Windows (the original's default), or force light / dark.
    val dark = when (s.theme) { ThemeMode.System -> systemDark; ThemeMode.Light -> false; ThemeMode.Dark -> true }
    val base = if (dark) DarkPalette else LightPalette
    val palette = if (accent != null && !dark) base.copy(accent = accent) else base
    val menu = remember { mutableStateOf<MenuRequest?>(null) }
    val scheme = if (dark) darkColorScheme(surface = palette.flyout) else lightColorScheme(surface = palette.flyout, primary = palette.accent)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalPalette provides palette, LocalMenu provides menu, LocalAppState provides s) {
            Box(Modifier.fillMaxSize()) {
                BackdropLayer(s, dark)
                MainLayout(s)
                TabDragOverlay(s)
                if (s.statusCenterOpen) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = offsetDp(-8, M.TAB_STRIP_TOP_MARGIN + M.TAB_HEIGHT + M.ADDRESS_BAR_HEIGHT - 4),
                        onDismissRequest = { s.statusCenterOpen = false },
                        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
                    ) { StatusCenter(s) }
                }
                menu.value?.let { req -> ContextMenuPopup(req) { menu.value = null } }
                s.confirmDelete?.let { DeleteDialog(s, it) }
                s.tagEditorFor?.let { TagEditorDialog(s, it) }
                if (s.settingsOpen) SettingsPage(s)
                if (s.customizeToolbarOpen) CustomizeToolbarDialog(s)
                if (s.cloneDialogOpen) GitCloneDialog(s)
                s.toast?.let { msg ->
                    LaunchedEffect(msg) { delay(2500.milliseconds); if (s.toast == msg) s.toast = null }
                    Box(Modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.shadow8().background(palette.flyout, RoundedCornerShape(8.dp)).border(1.dp, palette.controlStroke, RoundedCornerShape(8.dp)).padding(16.dp, 10.dp)) {
                            T(msg)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContextMenuPopup(req: MenuRequest, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Popup(
        offset = IntOffset(req.at.x.toInt(), req.at.y.toInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(IntrinsicSize.Max).widthIn(min = 240.dp).shadow8()
                .background(p.flyout, RoundedCornerShape(8.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(8.dp)).padding(4.dp)
        ) {
            req.entries.forEach { e ->
                if (e.separatorBefore) FlyoutSeparator()
                FlyoutItem(e.label, e.icon, e.hotkey, enabled = e.enabled, iconTint = if (e.icon == Icons.Filled.Folder) p.folder else null) {
                    onDismiss(); e.action()
                }
            }
        }
    }
}

@Composable
fun MainLayout(s: AppState) {
    val p = LocalPalette.current
    val t = s.tab
    val ct = s.currentTab
    val items = remember(t.raw, s.showHidden, s.showSystem, s.searchText, s.sortBy, s.sortAscending) { s.visibleItems(t) }
    Column(Modifier.fillMaxSize()) {
        TabStrip(s)
        AddressBar(s)
        Row(Modifier.weight(1f).fillMaxWidth().background(p.layer)) {
            if (s.sidebarOpen) {
                Sidebar(s, Modifier.width(s.sidebarWidth.dp))
                Splitter { s.sidebarWidth = (s.sidebarWidth + it / 1.25f).toInt().coerceIn(M.SIDEBAR_MIN, M.SIDEBAR_MAX) }
            }
            Column(Modifier.weight(1f).widthIn(min = M.CONTENT_MIN_WIDTH.dp).padding(end = 8.dp, bottom = 8.dp)) {
                // On Home the original shows no command toolbar (the page card starts under the address bar).
                if (s.showToolbar && !(t.isHome && ct.right == null)) {
                    CommandToolbar(s)
                    Spacer(Modifier.height(4.dp))
                }
                Row(Modifier.weight(1f)) {
                    PanesArea(s, ct, Modifier.weight(1f))
                    if (s.infoPaneOpen) {
                        Spacer(Modifier.width(8.dp))
                        InfoPane(s, items)
                    }
                }
            }
        }
    }
}


/**
 * One or two panes. Vertical arrangement = side by side, Horizontal = one above the other
 * (ShellPanesPage.ArrangePanes); the 8-px gap between them can be dragged to resize.
 */
@Composable
fun PanesArea(s: AppState, ct: Tab, outer: Modifier) {
    // Where a dragged tab can be dropped to split (TabDrag.kt).
    val modifier = outer.onGloballyPositioned { s.paneAreaRect = it.boundsInRoot() }
    val r = ct.right
    if (r == null) {
        PaneCard(s, ct.left, modifier, active = true)
        return
    }
    BoxWithConstraints(modifier) {
        val vertical = ct.arrangement == PaneArrangement.Vertical
        val total = if (vertical) maxWidth.value else maxHeight.value
        val first = (ct.split * (total - 8f)).coerceAtLeast(120f)
        val drag = Modifier.pointerInput(vertical, total) {
            detectDragGestures { c, d ->
                c.consume()
                val delta = (if (vertical) d.x else d.y) / density
                ct.split = ((ct.split * (total - 8f) + delta) / (total - 8f)).coerceIn(0.2f, 0.8f)
            }
        }
        if (vertical) Row(Modifier.fillMaxSize()) {
            PaneCard(s, ct.left, Modifier.width(first.dp), active = !ct.rightActive)
            Box(Modifier.width(8.dp).fillMaxHeight().pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))).then(drag))
            PaneCard(s, r, Modifier.weight(1f), active = ct.rightActive)
        } else Column(Modifier.fillMaxSize()) {
            PaneCard(s, ct.left, Modifier.height(first.dp).fillMaxWidth(), active = !ct.rightActive)
            Box(Modifier.height(8.dp).fillMaxWidth().pointerHoverIcon(PointerIcon(Cursor(Cursor.S_RESIZE_CURSOR))).then(drag))
            PaneCard(s, r, Modifier.weight(1f).fillMaxWidth(), active = ct.rightActive)
        }
    }
}

/** One pane's card: Home or a folder view plus its status bar. In dual pane the active one gets an accent outline. */
@Composable
fun PaneCard(s: AppState, pane: TabState, modifier: Modifier, active: Boolean) {
    val p = LocalPalette.current
    val dual = s.currentTab.right != null
    val items = remember(pane.raw, s.showHidden, s.showSystem, s.searchText, s.sortBy, s.sortAscending, s.tab === pane) { s.visibleItems(pane) }
    val shape = RoundedCornerShape(M.CARD_RADIUS.dp)
    Column(
        modifier.fillMaxHeight().shadow(2.dp, shape).clip(shape).background(p.fileArea, shape)
            .border(1.dp, if (dual && active) p.accent.copy(alpha = 0.6f) else p.cardStroke, shape)
    ) {
        Box(Modifier.weight(1f)) {
            if (pane.isHome) HomePage(s) else FolderView(s, pane, items)
        }
        if (s.showStatusBar) StatusBar(s, items, pane)
    }
}

// ====================================================================== keyboard

fun handleKey(s: AppState, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed
    val shift = e.isShiftPressed
    val alt = e.isAltPressed
    val editing = s.textFocus || s.editingPath || s.tab.renaming != null
    if (editing) {
        return false
    }
    if (s.confirmDelete != null) {
        if (e.key == Key.Escape) { s.confirmDelete = null; return true }
        if (e.key == Key.Enter) { s.doDelete(s.confirmDelete!!, false); return true }
        return false
    }
    if (s.settingsOpen || s.tagEditorFor != null || s.customizeToolbarOpen) {
        if (e.key == Key.Escape) { s.settingsOpen = false; s.tagEditorFor = null; s.customizeToolbarOpen = false; return true }
        return false
    }
    when {
        ctrl && shift && e.key == Key.S -> s.toggleDualPane()
        ctrl && shift && e.key == Key.T -> s.reopenClosedTab()
        ctrl && shift && e.key == Key.W -> s.closeAllTabs()
        ctrl && e.key == Key.Comma -> s.settingsOpen = true
        ctrl && shift && e.key == Key.N -> s.newFolder()
        ctrl && shift && e.key == Key.C -> s.copyPath()
        ctrl && shift && e.key == Key.K -> s.newTab(s.tab.location)
        ctrl && shift && e.key == Key.One -> s.layout = LayoutMode.Details
        ctrl && shift && e.key == Key.Two -> s.layout = LayoutMode.List
        ctrl && shift && e.key == Key.Three -> s.layout = LayoutMode.Cards
        ctrl && shift && e.key == Key.Four -> s.layout = LayoutMode.Grid
        ctrl && shift && e.key == Key.Tab -> s.tabIndex = (s.tabIndex - 1 + s.tabs.size) % s.tabs.size
        ctrl && alt && e.key == Key.I -> s.infoPaneOpen = !s.infoPaneOpen
        ctrl && e.key == Key.Tab -> s.tabIndex = (s.tabIndex + 1) % s.tabs.size
        ctrl && e.key == Key.C -> s.copy()
        ctrl && e.key == Key.X -> s.copy(cut = true)
        ctrl && e.key == Key.V -> s.paste()
        ctrl && e.key == Key.A -> s.selectAll()
        ctrl && e.key == Key.D -> s.requestDelete()
        ctrl && e.key == Key.T -> s.newTab()
        ctrl && (e.key == Key.W || e.key == Key.F4) -> s.closeTab(s.tabIndex)
        ctrl && e.key == Key.R -> s.refresh()
        ctrl && e.key == Key.H -> s.showHidden = !s.showHidden
        ctrl && e.key == Key.B -> s.sidebarOpen = !s.sidebarOpen
        ctrl && (e.key == Key.F || e.key == Key.E) -> s.searchRequest++
        ctrl && e.key == Key.L -> s.editingPath = true
        ctrl && (e.key == Key.Minus || e.key == Key.NumPadSubtract) -> s.gridSmaller()
        ctrl && (e.key == Key.Equals || e.key == Key.NumPadAdd || e.key == Key.Plus) -> s.gridLarger()
        ctrl && e.key.keyCode in Key.One.keyCode..Key.Nine.keyCode -> {
            val i = (e.key.keyCode - Key.One.keyCode).toInt()
            if (i < s.tabs.size) s.tabIndex = i
        }
        alt && e.key == Key.DirectionLeft -> s.back()
        alt && e.key == Key.DirectionRight -> s.forward()
        alt && e.key == Key.DirectionUp -> s.up()
        alt && e.key == Key.D -> s.editingPath = true
        alt && e.key == Key.Enter -> s.infoPaneOpen = true
        shift && e.key == Key.Delete -> { val sel = s.tab.selection.toList(); if (sel.isNotEmpty()) s.doDelete(sel, true) }
        e.key == Key.Delete -> s.requestDelete()
        e.key == Key.F2 -> s.startRename()
        e.key == Key.F5 -> s.refresh()
        e.key == Key.F11 -> s.toggleFullScreen?.invoke()
        e.key == Key.F3 -> s.searchRequest++
        e.key == Key.Backspace -> s.back()
        e.key == Key.Enter -> s.openSelected()
        e.key == Key.Escape -> s.clearSelection()
        e.key == Key.DirectionDown || e.key == Key.DirectionUp -> moveSelection(s, if (e.key == Key.DirectionDown) 1 else -1, shift)
        else -> return false
    }
    return true
}

private fun moveSelection(s: AppState, delta: Int, shift: Boolean) {
    val t = s.tab
    if (t.isHome) return
    val items = s.visibleItems(t)
    if (items.isEmpty()) return
    val cur = items.indexOfLast { it.path in t.selection }
    val next = (if (cur < 0) 0 else cur + delta).coerceIn(0, items.size - 1)
    s.click(items[next], ctrl = false, shift = shift, items = items)
}

// ====================================================================== --screenshot

/**
 * Self-test of the checkbox rule with simulated mouse clicks on the Details list:
 * click row 1, tick row 2's checkbox (both stay selected), then click row 3 (only it stays).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
suspend fun checkboxTest(scene: ImageComposeScene, s: AppState) {
    val rows = s.visibleItems(s.tab)
    fun y(i: Int) = 206f + 36f * i
    val checkX = 227f
    val nameX = 420f
    suspend fun click(x: Float, yy: Float) {
        val pos = androidx.compose.ui.geometry.Offset(x, yy)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, pos)
        scene.render(System.nanoTime()); delay(30.milliseconds)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, pos, buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true))
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, pos)
        scene.render(System.nanoTime()); delay(500.milliseconds)   // past the double-click window
    }
    click(nameX, y(0))
    val a = s.tab.selection.size
    click(checkX, y(1))
    val b = s.tab.selection.size
    click(nameX, y(2))
    val c = s.tab.selection.size
    click(checkX, y(3))
    val d = s.tab.selection.size
    println("CHECKBOX TEST rows=${rows.size}: click row1 -> $a selected (expect 1); tick row2 -> $b (expect 2); click row3 -> $c (expect 1); tick row4 -> $d (expect 2)")
}

/**
 * Self-test of the 自訂工具列 window: drag 剪下 by its handle to the end of the list, press 儲存,
 * print the saved order, then put the person's previous toolbar back.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
suspend fun toolbarDragTest(scene: ImageComposeScene, s: AppState) {
    val before = s.toolbarStore.layout
    val P = androidx.compose.ui.input.pointer.PointerEventType
    val down = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
    suspend fun frame() { scene.render(System.nanoTime()); delay(16.milliseconds) }
    fun at(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
    // drag the gripper of row 3 (剪下) to below the last row
    scene.sendPointerEvent(P.Move, at(546f, 352f)); frame()
    scene.sendPointerEvent(P.Press, at(546f, 352f), buttons = down); frame()
    var y = 352f
    while (y < 548f) { y += 14f; scene.sendPointerEvent(P.Move, at(548f, y), buttons = down); frame() }
    scene.sendPointerEvent(P.Release, at(548f, 548f)); frame(); frame()
    // 儲存
    scene.sendPointerEvent(P.Move, at(904f, 652f)); frame()
    scene.sendPointerEvent(P.Press, at(904f, 652f), buttons = down); frame()
    scene.sendPointerEvent(P.Release, at(904f, 652f)); frame(); frame()
    val after = s.toolbarStore.layout[TbContext.AlwaysVisible]?.map { it.id }
    println("TOOLBAR DRAG TEST saved=${!s.customizeToolbarOpen} order=$after (expect CutItem last)")
    s.toolbarStore.save(before)
}

/**
 * Screenshot-mode regression test: in 自訂工具列, remove rows from the middle of 新增選項 with ×.
 * The preview toolbar below used to crash with "Index n out of bounds for length n".
 * Nothing is saved: the dialog stays open for the picture and the run then exits.
 */
/**
 * Screenshot-mode test for tab dragging: drag a second tab onto the right edge of the file area
 * (= split side by side), print the result, then hold a drag over the bottom edge so the picture
 * shows the 水平分割 preview.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
suspend fun tabDragTest(scene: ImageComposeScene, s: AppState) {
    val P = androidx.compose.ui.input.pointer.PointerEventType
    val down = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
    suspend fun frame() { scene.render(System.nanoTime()); delay(16.milliseconds) }
    suspend fun drag(from: androidx.compose.ui.geometry.Offset, to: androidx.compose.ui.geometry.Offset, release: Boolean) {
        scene.sendPointerEvent(P.Move, from); frame()
        scene.sendPointerEvent(P.Press, from, buttons = down); frame()
        for (k in 1..20) {
            val f = k / 20f
            scene.sendPointerEvent(P.Move, from + (to - from) * f, buttons = down); frame()
        }
        if (release) { scene.sendPointerEvent(P.Release, to); frame(); frame() }
    }
    s.newTab(System.getProperty("user.home")); s.tabIndex = 0; repeat(10) { frame() }
    val second = s.tabs.last()
    val before = s.tabs.size
    val area = s.paneAreaRect
    drag(s.tabRects[second]!!.center, androidx.compose.ui.geometry.Offset(area.right - 30f, area.center.y), release = true)
    val ct = s.currentTab
    println("TAB DRAG TEST tabs $before -> ${s.tabs.size} split=${ct.right != null} arrangement=${ct.arrangement} " +
        "left=${ct.left.location} right=${ct.right?.location} (expect one tab fewer, split=true Vertical, right = home folder)")
    repeat(10) { frame() }
    // Hold a second drag over the bottom edge for the picture.
    drag(s.tabRects[s.tabs[0]]!!.center, androidx.compose.ui.geometry.Offset(area.center.x, area.bottom - 30f), release = false)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
suspend fun toolbarDeleteTest(scene: ImageComposeScene, s: AppState) {
    val P = androidx.compose.ui.input.pointer.PointerEventType
    val down = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
    suspend fun frame() { scene.render(System.nanoTime()); delay(16.milliseconds) }
    suspend fun click(x: Float, y: Float) {
        val o = androidx.compose.ui.geometry.Offset(x, y)
        scene.sendPointerEvent(P.Move, o); frame()
        scene.sendPointerEvent(P.Press, o, buttons = down); frame()
        scene.sendPointerEvent(P.Release, o); frame(); frame()
    }
    val before = s.toolbarStore.layout[TbContext.AlwaysVisible]?.size ?: 0
    // × of the third row, three times: each removal shifts the rows below it up.
    repeat(3) { click(992f, 357f) }
    frame(); frame()
    println("TOOLBAR DELETE TEST rows before=$before (no crash = pass)")
}

/**
 * Title-bar test without a visible window: TitleDragArea on a hidden frame gets timed presses.
 * Two presses within Windows' double-click time maximize, two more restore, slow ones do nothing.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun titleBarTest() = runBlocking(Dispatchers.Swing) {
    val frame = java.awt.Frame()
    val ws = WindowState(placement = WindowPlacement.Floating, size = DpSize(800.dp, 600.dp))
    val scene = ImageComposeScene(400, 60, Density(1f)) { TitleDragArea(frame, ws, Modifier.fillMaxSize()) {} }
    val P = androidx.compose.ui.input.pointer.PointerEventType
    val down = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
    val at = androidx.compose.ui.geometry.Offset(200f, 30f)
    val gap = (java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int ?: 500).toLong()
    var t = 1_000_000L
    fun click(afterMs: Long) {
        t += afterMs
        scene.sendPointerEvent(P.Press, at, buttons = down, timeMillis = t); scene.render(t * 1_000_000)
        scene.sendPointerEvent(P.Release, at, timeMillis = t + 30); scene.render((t + 30) * 1_000_000)
    }
    scene.render(0)
    click(0); click(120); val afterFirst = ws.placement
    click(gap * 4); click(120); val afterSecond = ws.placement
    click(gap * 4); click(gap * 3); val afterSlow = ws.placement
    println("TITLEBAR TEST double-click=$afterFirst, again=$afterSecond, two slow clicks=$afterSlow " +
        "(expect Maximized, Floating, Floating; double-click time $gap ms)")
    scene.close(); frame.dispose()
}

/**
 * SafeCleaner on a prepared fixture folder (see the test notes): [dir]\cache holds old.tmp, new.tmp
 * and a junction "link" to [dir]ictim; [dir]ootlink is a junction to victim used as a root.
 * Prints what was planned and what is left.
 */
fun safeCleanerTest(dir: File) {
    val cache = File(dir, "cache"); val victim = File(dir, "victim")
    val target = CleanupTarget("test", "test", CleanupAudience.Developer, listOf(cache), "",
        safeDelete = SafeDeleteRule(listOf(cache, File(dir, "rootlink")), olderThanDays = 7))
    val plan = SafeCleaner.plan(target)
    println("SAFE CLEANER plan: files=${plan.files.map { it.fileName }} links=${plan.skippedLinks} recent=${plan.skippedRecent}")
    val (n, _) = SafeCleaner.execute(plan)
    fun list(f: File) = f.walkTopDown().filter { it != f }.map { it.relativeTo(dir).path }.sorted().toList()
    println("SAFE CLEANER removed=$n cache-left=${list(cache).filter { !it.contains("link\\") }} victim-left=${list(victim)} " +
        "rootlink-exists=${File(dir, "rootlink").exists()} link-exists=${File(cache, "link").exists()}")
}

/**
 * Runs a PowerShell and a cmd custom command in [dir] (a folder with a space and a quote in its
 * name), with two selected files; each writes what it received to out-ps.txt / out-cmd.txt.
 */
fun customCommandTest(dir: File) = runBlocking(Dispatchers.Swing) {
    Prefs.readOnly = true
    val s = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Swing))
    s.navigate(dir.path)
    val a = File(dir, "a file.txt").path; val b = File(dir, "b'q.txt").path
    s.tab.selection = setOf(a, b)
    val ps = CustomCommand("cmd:testps", "PS test", ShellKind.PowerShell,
        "function Show { \$args -join '|' }\n" +
            "\"folder=\" + (Show {folder}) + \"`nfile=\" + (Show {file}) + \"`nselected=\" + (Show {selected}) + " +
            "\"`nenv=\$env:FILES_FOLDER`npwd=\$((Get-Location).Path)\" | Set-Content -Encoding utf8 out-ps.txt", keepOpen = false)
    val cmd = CustomCommand("cmd:testcmd", "CMD test", ShellKind.Cmd,
        "echo folder={folder}> out-cmd.txt\necho file={file}>> out-cmd.txt\necho env=%FILES_FOLDER%>> out-cmd.txt\necho pwd=%CD%>> out-cmd.txt", keepOpen = false)
    TbCustom.run(s, ps); TbCustom.run(s, cmd)
    println("CUSTOM COMMAND TEST started; toast=${s.toast}")
}

/** Saving and loading the person's own toolbar items and a renamed slot (run with APPDATA pointing at a test folder). */
fun customPersistTest(write: Boolean) {
    if (write) {
        TbCustom.upsert(CustomCommand("cmd:p1", "建置 \"專案\"\ttab", ShellKind.Cmd, "echo 1\necho 2", keepOpen = false))
        TbCustom.upsert(CustomFolder("folder:p2", "我的工具", listOf("cmd:p1", "OpenTerminal")))
        ToolbarStore().save(mapOf(TbContext.AlwaysVisible to listOf(TbEntry("folder:p2", true, true), TbEntry("OpenProperties", true, true, "屬性 x"))))
        println("CUSTOM PERSIST wrote to ${Prefs.dir}")
    } else {
        val c = TbCustom.command("cmd:p1"); val f = TbCustom.folder("folder:p2")
        val l = ToolbarStore().layout[TbContext.AlwaysVisible]
        println("CUSTOM PERSIST read: cmd=${c?.name?.replace("\t", "<TAB>")}|${c?.shell}|${c?.script?.replace("\n", "<NL>")}|keepOpen=${c?.keepOpen} folder=${f?.name}${f?.children} " +
            "slots=${l?.map { "${it.id}:${it.label}" }} folderLabel=${TbCommands.get("folder:p2")?.label}")
    }
}

/** In memory only (settings are read-only in screenshot runs): a folder, a command and a renamed button. */
fun demoCustomToolbar(s: AppState) {
    val c = CustomCommand("cmd:demo1", if (S.zh) "建置專案" else "Build project", ShellKind.PowerShell, "./gradlew.bat build")
    val c2 = CustomCommand("cmd:demo2", if (S.zh) "清除暫存" else "Clean temp", ShellKind.Cmd, "del /q *.tmp")
    TbCustom.upsert(c); TbCustom.upsert(c2)
    TbCustom.upsert(CustomFolder("folder:demo", if (S.zh) "我的工具" else "My tools", listOf("cmd:demo1", "cmd:demo2", "OpenTerminal")))
    val l = s.toolbarStore.layout.toMutableMap()
    val general = (l[TbContext.AlwaysVisible] ?: emptyList()).toMutableList()
    general.add(TbEntry("folder:demo", true, true))
    general.add(TbEntry("cmd:demo1", true, true))
    val i = general.indexOfFirst { it.id == "OpenProperties" }
    if (i >= 0) general[i] = general[i].copy(label = if (S.zh) "屬性" else "Props", showLabel = true)
    l[TbContext.AlwaysVisible] = general
    s.toolbarStore.save(l)
}

/** Starts the app as normal (Home in one tab), draws about 120 frames, saves the picture, exits. */
fun screenshot(
    out: File, start: String? = null, layout: LayoutMode? = null, infoPane: Boolean = false,
    interact: (suspend (ImageComposeScene, AppState) -> Unit)? = null,
    setup: (AppState) -> Unit = {},
) {
    val dark = systemUsesDarkTheme()
    val accent = systemAccent()
    runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val state = withContext(Dispatchers.Swing) {
            AppState(scope).also { s ->
                layout?.let { s.layout = it }
                s.infoPaneOpen = infoPane
                start?.let { s.navigate(File(it).absolutePath) }
                setup(s)
            }
        }
        val scene = withContext(Dispatchers.Swing) {
            // fileskmp.shotHeight: a taller picture for tests of long pages.
            ImageComposeScene(M.WINDOW_W, Integer.getInteger("fileskmp.shotHeight", M.WINDOW_H), Density(1f)) { Root(state, dark, accent) }
        }
        var frame = 0
        val startNs = System.nanoTime()
        val deadline = System.currentTimeMillis() + 15_000
        while (frame < 120 || ((!(state.pinnedLoaded && state.drivesLoaded && state.recentLoaded) || state.tab.loading || Thumbnails.busy() || state.folderSizing.isNotEmpty()) && System.currentTimeMillis() < deadline)) {
            withContext(Dispatchers.Swing) { scene.render(System.nanoTime() - startNs) }
            frame++
            if (frame == 90) interact?.let { withContext(Dispatchers.Swing) { it(scene, state) } }
            delay(16.milliseconds)
        }
        // fileskmp.shotWaitMs (tests): keep drawing a while longer, e.g. for widgets that load late.
        val extraUntil = System.currentTimeMillis() + Integer.getInteger("fileskmp.shotWaitMs", 0)
        while (System.currentTimeMillis() < extraUntil) {
            withContext(Dispatchers.Swing) { scene.render(System.nanoTime() - startNs) }
            delay(16.milliseconds)
        }
        val image = withContext(Dispatchers.Swing) { scene.render(System.nanoTime() - startNs) }
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
        out.absoluteFile.parentFile?.mkdirs()
        out.writeBytes(data.bytes)
        withContext(Dispatchers.Swing) { scene.close() }
        scope.cancel()
        println("Saved ${out.absolutePath} after $frame frames (Home loaded in ${state.homeLoadMs} ms)")
    }
}
