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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Cursor
import java.awt.Dimension
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val shotIdx = args.indexOf("--screenshot")
    if (shotIdx >= 0 && shotIdx + 1 < args.size) {
        val start = args.filterIndexed { i, a -> i != shotIdx + 1 && !a.startsWith("--") && File(a).isDirectory }.firstOrNull()
        val layout = args.indexOf("--layout").takeIf { it >= 0 && it + 1 < args.size }?.let { i ->
            LayoutMode.entries.firstOrNull { it.name.equals(args[i + 1], true) }
        }
        screenshot(File(args[shotIdx + 1]), start, layout, "--info-pane" in args)
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
    application {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope).also { s -> startPath?.let { s.navigate(File(it).absolutePath) } } }
        val windowState = rememberWindowState(size = DpSize(M.WINDOW_W.dp, M.WINDOW_H.dp), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Files",
            undecorated = true,
            resizable = true,
            onPreviewKeyEvent = { handleKey(state, it) },
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(M.WINDOW_MIN_W, M.WINDOW_MIN_H) }
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
fun Root(s: AppState, dark: Boolean, accent: androidx.compose.ui.graphics.Color?) {
    val base = if (dark) DarkPalette else LightPalette
    val palette = if (accent != null && !dark) base.copy(accent = accent) else base
    val menu = remember { mutableStateOf<MenuRequest?>(null) }
    val scheme = if (dark) darkColorScheme(surface = palette.flyout) else lightColorScheme(surface = palette.flyout, primary = palette.accent)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalPalette provides palette, LocalMenu provides menu) {
            Box(Modifier.fillMaxSize().background(palette.window)) {
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
                s.toast?.let { msg ->
                    LaunchedEffect(msg) { delay(2500); if (s.toast == msg) s.toast = null }
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
    val items = remember(t.raw, s.showHidden, s.searchText, s.sortBy, s.sortAscending) { s.visibleItems(t) }
    Column(Modifier.fillMaxSize()) {
        TabStrip(s)
        AddressBar(s)
        Row(Modifier.weight(1f).fillMaxWidth().background(p.layer)) {
            if (s.sidebarOpen) {
                Sidebar(s, Modifier.width(s.sidebarWidth.dp))
                Splitter { s.sidebarWidth = (s.sidebarWidth + it / 1.25f).toInt().coerceIn(M.SIDEBAR_MIN, M.SIDEBAR_MAX) }
            }
            Column(Modifier.weight(1f).widthIn(min = M.CONTENT_MIN_WIDTH.dp).padding(end = 8.dp, bottom = 8.dp)) {
                CommandToolbar(s)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.weight(1f)) {
                    val shape = RoundedCornerShape(M.CARD_RADIUS.dp)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().shadow(2.dp, shape).clip(shape).background(p.fileArea, shape).border(1.dp, p.cardStroke, shape)
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (t.isHome) HomePage(s) else FolderView(s, t, items)
                        }
                        StatusBar(s, items)
                    }
                    if (s.infoPaneOpen) {
                        Spacer(Modifier.width(8.dp))
                        InfoPane(s, items)
                    }
                }
            }
        }
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
    when {
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
        shift && e.key == Key.Delete -> { val sel = s.tab.selection.toList(); if (sel.isNotEmpty() && !s.tab.isHome) s.doDelete(sel, true) }
        e.key == Key.Delete -> s.requestDelete()
        e.key == Key.F2 -> s.startRename()
        e.key == Key.F5 -> s.refresh()
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

/** Starts the app as normal (Home in one tab), draws about 120 frames, saves the picture, exits. */
fun screenshot(out: File, start: String? = null, layout: LayoutMode? = null, infoPane: Boolean = false) {
    val dark = systemUsesDarkTheme()
    val accent = systemAccent()
    runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val state = withContext(Dispatchers.Swing) {
            AppState(scope).also { s ->
                layout?.let { s.layout = it }
                s.infoPaneOpen = infoPane
                start?.let { s.navigate(File(it).absolutePath) }
            }
        }
        val scene = withContext(Dispatchers.Swing) {
            ImageComposeScene(M.WINDOW_W, M.WINDOW_H, Density(1f)) { Root(state, dark, accent) }
        }
        var frame = 0
        val startNs = System.nanoTime()
        var image: org.jetbrains.skia.Image? = null
        val deadline = System.currentTimeMillis() + 15_000
        while (frame < 120 || (!(state.pinnedLoaded && state.drivesLoaded && state.recentLoaded) && System.currentTimeMillis() < deadline)) {
            image = withContext(Dispatchers.Swing) { scene.render(System.nanoTime() - startNs) }
            frame++
            delay(16)
        }
        image = withContext(Dispatchers.Swing) { scene.render(System.nanoTime() - startNs) }
        val data = image!!.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
        out.absoluteFile.parentFile?.mkdirs()
        out.writeBytes(data.bytes)
        withContext(Dispatchers.Swing) { scene.close() }
        scope.cancel()
        println("Saved ${out.absolutePath} after $frame frames (Home loaded in ${state.homeLoadMs} ms)")
    }
}
