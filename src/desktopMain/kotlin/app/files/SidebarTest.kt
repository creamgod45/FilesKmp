package app.files

import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Screenshot-mode regression test: expanding a drive must not navigate the active pane. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
suspend fun sidebarTreeTest(scene: ImageComposeScene, s: AppState) {
    withTimeout(10.seconds) { while (!s.drivesLoaded) delay(50.milliseconds) }
    val root = s.drives.first { it.ready }.root
    val location = s.tab.location
    suspend fun click(x: Float, y: Float) {
        val position = androidx.compose.ui.geometry.Offset(x, y)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, position)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, position,
            buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true))
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, position)
        scene.render(System.nanoTime())
        delay(150.milliseconds)
    }
    // The default screenshot sidebar begins below the title and address bars.
    val driveY = M.TAB_HEIGHT + M.TAB_STRIP_TOP_MARGIN + M.ADDRESS_BAR_HEIGHT +
        6 + M.SIDEBAR_ROW * (s.pinned.size + 3) + M.SIDEBAR_ROW / 2
    click(32f, driveY.toFloat())
    withTimeout(10.seconds) { while (s.treeChildren[root] == null) delay(50.milliseconds) }
    check(s.treeExpanded[root] == true)
    check(s.tab.location == location)
    val child = s.treeChildren[root]!!.firstOrNull()
    if (child != null) {
        s.toggleTree(child)
        withTimeout(10.seconds) { while (s.treeChildren[child] == null) delay(50.milliseconds) }
        check(s.treeExpanded[child] == true)
    }
    s.toggleTree(root)
    check(s.treeExpanded[root] == false)
    s.toggleTree(root)
    scene.render(System.nanoTime())
    click(940f, 114f)
    println("PASS: sidebar drive expansion, child loading, collapse, independent navigation")
}
