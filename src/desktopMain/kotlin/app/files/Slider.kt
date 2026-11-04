package app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A Fluent (WinUI) style slider: thin track, accent fill, round thumb that grows on hover.
 * Click or drag anywhere on it, scroll the mouse wheel over it, or use ←/→ once focused.
 * [steps] > 0 snaps to that many intervals.
 */
@Composable
fun FluentSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    width: Dp = 160.dp,
    /** Called once a drag (or a key / wheel step) is done: for work too heavy for every move, like seeking. */
    onChangeFinished: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    var widthPx by remember { mutableStateOf(1) }
    val thumbR = with(LocalDensity.current) { 10.dp.toPx() }
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val span = range.endInclusive - range.start
    fun snap(v: Float): Float {
        val c = v.coerceIn(range.start, range.endInclusive)
        if (steps <= 0) return c
        val step = span / steps
        return range.start + ((c - range.start) / step).roundToInt() * step
    }
    fun fromX(x: Float) = snap(range.start + ((x - thumbR) / (widthPx - 2 * thumbR).coerceAtLeast(1f)) * span)
    val stepSize = if (steps > 0) span / steps else span / 20f
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    Box(
        modifier.width(width).height(24.dp).hoverable(src)
            .onSizeChanged { widthPx = it.width }
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false
                else when (it.key) {
                    Key.DirectionLeft, Key.DirectionDown -> { change(snap(current - stepSize)); finished?.invoke(); true }
                    Key.DirectionRight, Key.DirectionUp -> { change(snap(current + stepSize)); finished?.invoke(); true }
                    Key.MoveHome -> { change(range.start); finished?.invoke(); true }
                    Key.MoveEnd -> { change(range.endInclusive); finished?.invoke(); true }
                    else -> false
                }
            }
            .focusTarget()
            .pointerInput(range, steps) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    change(fromX(down.position.x))
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        c.consume()
                        change(fromX(c.position.x))
                    }
                    finished?.invoke()
                }
            }
            .pointerInput(range, steps) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll) {
                            val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy != 0f) { change(snap(current + if (dy < 0) stepSize else -stepSize)); finished?.invoke(); e.changes.forEach { it.consume() } }
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val track = 4.dp
        Box(Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(track).background(p.progressTrack, RoundedCornerShape(2.dp)))
        val fillW = with(LocalDensity.current) { (frac * (widthPx - 2 * thumbR)).coerceAtLeast(0f).toDp() }
        Box(Modifier.padding(start = 10.dp).width(fillW).height(track).background(p.accent, RoundedCornerShape(2.dp)))
        val x = with(LocalDensity.current) { (thumbR + frac * (widthPx - 2 * thumbR) - thumbR).toDp() }
        Box(
            Modifier.offset(x = x).size(20.dp).background(if (p.dark) Color45 else androidx.compose.ui.graphics.Color.White, CircleShape)
                .border(1.dp, p.controlStroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(if (hovered) 12.dp else 10.dp).background(p.accent, CircleShape))
        }
    }
}

private val Color45 = androidx.compose.ui.graphics.Color(0xFF454545)
