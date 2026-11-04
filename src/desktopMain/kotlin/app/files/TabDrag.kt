package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HorizontalSplit
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Dragging a tab: sideways within the tab strip reorders it; down into the file area splits the
 * current tab, with a preview of where the dragged tab's pane will go (left / right = side by side,
 * top / bottom = one above the other).
 */
class TabDrag(val tab: Tab, val title: String) {
    /** Pointer position in root (window) coordinates. */
    var pos by mutableStateOf(Offset.Zero)
}

/** Which half of the file area a dropped tab takes. */
enum class SplitZone(val arrangement: PaneArrangement, val first: Boolean) {
    Left(PaneArrangement.Vertical, true), Right(PaneArrangement.Vertical, false),
    Top(PaneArrangement.Horizontal, true), Bottom(PaneArrangement.Horizontal, false),
}

/** The edge [pos] is nearest to, when it lies inside [area] (the whole area is a drop target). */
fun splitZoneAt(pos: Offset, area: Rect): SplitZone? {
    if (area.isEmpty || !area.contains(pos)) return null
    val fx = (pos.x - area.left) / area.width
    val fy = (pos.y - area.top) / area.height
    // Distance to each edge, as a fraction of the area: the smallest wins.
    return listOf(SplitZone.Left to fx, SplitZone.Right to 1 - fx, SplitZone.Top to fy, SplitZone.Bottom to 1 - fy)
        .minBy { it.second }.first
}

/** The half of [area] a pane dropped in [zone] will fill. */
fun SplitZone.previewRect(area: Rect): Rect = when (this) {
    SplitZone.Left -> Rect(area.left, area.top, area.center.x, area.bottom)
    SplitZone.Right -> Rect(area.center.x, area.top, area.right, area.bottom)
    SplitZone.Top -> Rect(area.left, area.top, area.right, area.center.y)
    SplitZone.Bottom -> Rect(area.left, area.center.y, area.right, area.bottom)
}

/** Drag gesture for one tab in the strip; [coords] are the tab's own layout coordinates. */
fun Modifier.tabDragSource(s: AppState, t: Tab, coords: () -> LayoutCoordinates?): Modifier = pointerInput(t) {
    fun root(local: Offset) = coords()?.localToRoot(local) ?: local
    detectDragGestures(
        onDragStart = { o -> s.tabDrag = TabDrag(t, t.title).also { it.pos = root(o) } },
        onDrag = { change, _ ->
            change.consume()
            val d = s.tabDrag ?: return@detectDragGestures
            d.pos = root(change.position)
            // Within the strip: move the tab to the slot under the pointer.
            if (d.pos.y <= s.tabStripRect.bottom) {
                val target = s.tabRects.entries.firstOrNull { (tab, r) -> tab !== t && tab in s.tabs && d.pos.x in r.left..r.right }?.key
                if (target != null) s.moveTab(t, s.tabs.indexOf(target))
            }
        },
        onDragEnd = {
            val d = s.tabDrag
            s.tabDrag = null
            if (d != null) splitZoneAt(d.pos, s.paneAreaRect)?.let { s.dropTabIntoPanes(d.tab, it) }
        },
        onDragCancel = { s.tabDrag = null },
    )
}

/** Moves [t] to [index] in the strip, keeping the same tab selected. */
fun AppState.moveTab(t: Tab, index: Int) {
    val from = tabs.indexOf(t)
    if (from < 0 || index < 0 || index >= tabs.size || from == index) return
    val selected = currentTab
    tabs.removeAt(from)
    tabs.add(index, t)
    tabIndex = tabs.indexOf(selected)
}

/**
 * Drops tab [dragged] on the current tab's file area: the current tab splits ([zone] says how and
 * on which side) and the dragged tab's active pane moves in with its history. Dragging the current
 * tab onto itself opens a second pane at the same folder. A pane pushed out of an already split tab
 * becomes a tab of its own, so nothing is lost.
 */
fun AppState.dropTabIntoPanes(dragged: Tab, zone: SplitZone) {
    val target = currentTab
    val moving: TabState
    if (dragged === target) {
        if (target.right != null) return
        moving = TabState(target.left.location).also { load(it) }
    } else {
        moving = dragged.active
        // The dragged tab gives up its active pane; a second pane it had stays behind as a tab.
        val rest = if (dragged.right == null) null else if (dragged.rightActive) dragged.left else dragged.right
        val i = tabs.indexOf(dragged)
        if (rest != null) tabs[i] = Tab(rest) else tabs.removeAt(i)
    }
    val existing = target.right
    val (first, second, pushedOut) = when {
        existing == null -> if (zone.first) Triple(moving, target.left, null) else Triple(target.left, moving, null)
        zone.first -> Triple(moving, existing, target.left)
        else -> Triple(target.left, moving, existing)
    }
    val merged = Tab(first).also {
        it.right = second; it.arrangement = zone.arrangement; it.split = 0.5f; it.rightActive = !zone.first
    }
    val ti = tabs.indexOf(target)
    tabs[ti] = merged
    if (pushedOut != null) tabs.add(ti + 1, Tab(pushedOut))
    tabIndex = ti
}

/** Drawn over everything while a tab is dragged: the split preview and a chip under the pointer. */
@Composable
fun TabDragOverlay(s: AppState) {
    val d = s.tabDrag ?: return
    val p = LocalPalette.current
    val density = LocalDensity.current
    val zone = splitZoneAt(d.pos, s.paneAreaRect)
    if (zone != null) {
        val r = zone.previewRect(s.paneAreaRect)
        with(density) {
            Box(
                Modifier.offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                    .size(r.width.toDp(), r.height.toDp()).padding(4.dp)
                    .background(p.accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                    .border(2.dp, p.accent, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.background(p.flyout, RoundedCornerShape(6.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val vertical = zone.arrangement == PaneArrangement.Vertical
                    Icon(if (vertical) Icons.Outlined.VerticalSplit else Icons.Outlined.HorizontalSplit, null, Modifier.size(16.dp), tint = p.accent)
                    Spacer(Modifier.width(8.dp))
                    T(if (vertical) S.splitVertical else S.splitHorizontal, size = 13.sp)
                }
            }
        }
    }
    // The dragged tab follows the pointer.
    Box(
        Modifier.offset { IntOffset(d.pos.x.roundToInt() + 12, d.pos.y.roundToInt() + 12) }
            .background(p.tabSelected, RoundedCornerShape(6.dp)).border(1.dp, p.controlStroke, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) { T(d.title, size = 12.sp) }
}
