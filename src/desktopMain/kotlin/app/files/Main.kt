package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
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

fun main(args: Array<String>) {
    AppDirs.init()
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
            "--test-sidebar" in args -> ::sidebarTreeTest
            else -> null
        }
        screenshot(File(args[shotIdx + 1]), start, layout, "--info-pane" in args, interact) { s ->
            if ("--dual" in args) s.toggleDualPane()
            if ("--hsplit" in args) s.splitPane(PaneArrangement.Horizontal)
            if ("--sizes" in args) s.calculateFolderSizes = true
            args.firstOrNull { it.startsWith("--show-hidden=") }?.let { s.showHidden = it.endsWith("=1") }
            args.firstOrNull { it.startsWith("--show-system=") }?.let { s.showSystem = it.endsWith("=1") }
            if ("--customize" in args) s.customizeToolbarOpen = true
            if ("--settings" in args) s.settingsOpen = true
            if ("--clone-dialog" in args) s.cloneDialogOpen = true
            if ("--select-first" in args) s.scope.launch { delay(1200.milliseconds); s.tab.raw.firstOrNull()?.let { s.tab.selection = setOf(it.path) } }
        }
        exitProcess(0)
    }
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
                    drag = @Composable { m, c -> this@Window.WindowDraggableArea(m) { c() } },
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
fun PanesArea(s: AppState, ct: Tab, modifier: Modifier) {
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
            ImageComposeScene(M.WINDOW_W, M.WINDOW_H, Density(1f)) { Root(state, dark, accent) }
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
        val image = withContext(Dispatchers.Swing) { scene.render(System.nanoTime() - startNs) }
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
        out.absoluteFile.parentFile?.mkdirs()
        out.writeBytes(data.bytes)
        withContext(Dispatchers.Swing) { scene.close() }
        scope.cancel()
        println("Saved ${out.absolutePath} after $frame frames (Home loaded in ${state.homeLoadMs} ms)")
    }
}
