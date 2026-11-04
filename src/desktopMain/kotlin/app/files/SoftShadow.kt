package app.files

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 卡片陰影 setting: how much the file area, toolbar and details pane stand off the background. */
enum class ShadowLevel(val elevation: Dp) { None(0.dp), Soft(6.dp), Medium(12.dp);
    val text: String get() = when (this) { None -> S.shadowNone; Soft -> S.shadowSoft; Medium -> S.shadowMedium }
}

/**
 * A feathered drop shadow drawn only *outside* [shape]: a wide, faint ambient glow plus a tighter,
 * slightly lower key shadow, both Gaussian-blurred so the edge fades out smoothly.
 * Compose's own shadow() also darkens the inside of a shape, which shows through the app's
 * translucent cards as a grey cast; clipping the card's own area out avoids that.
 */
fun Modifier.softShadow(shape: Shape, elevation: Dp = 6.dp, color: Color = Color.Black, strength: Float = 1f): Modifier {
    if (elevation.value <= 0f || strength <= 0f) return this
    return drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        val e = elevation.toPx()
        // (blur sigma, y offset, alpha): ambient first, then the key light from above.
        val layers = listOf(Triple(e * 0.9f, e * 0.15f, 0.055f), Triple(e * 0.35f, e * 0.3f, 0.07f))
        clipPath(path, ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                for ((sigma, dy, a) in layers) {
                    val paint = org.jetbrains.skia.Paint().apply {
                        isAntiAlias = true
                        this.color = color.copy(alpha = (a * strength).coerceIn(0f, 1f)).toArgb()
                        maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, sigma.coerceAtLeast(0.5f))
                    }
                    val shifted = Path().apply { addPath(path, Offset(0f, dy)) }
                    canvas.nativeCanvas.drawPath(shifted.asSkiaPath(), paint)
                }
            }
        }
    }
}
