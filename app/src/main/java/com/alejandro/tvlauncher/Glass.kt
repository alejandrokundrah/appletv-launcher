package com.alejandro.tvlauncher

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How "milky" the glass is. */
enum class GlassStyle(val label: String, val tint: Color, val tintAlpha: Float, val sheen: Float) {
    Clear("Clear", Color.White, 0.08f, 0.20f),
    Frosted("Frosted", Color.White, 0.18f, 0.26f),
    Dark("Dark", Color.Black, 0.32f, 0.14f),
}

/**
 * What glass surfaces need to know: the pre-blurred wallpaper (drawn behind every glass
 * surface, aligned to the screen so it looks like you're seeing the wallpaper through it),
 * the root size, and the chosen style.
 */
class GlassEnv(
    val blurred: ImageBitmap?,
    val width: Float,
    val height: Float,
    val style: GlassStyle,
)

val LocalGlass = compositionLocalOf { GlassEnv(null, 1920f, 1080f, GlassStyle.Frosted) }

/**
 * Liquid-glass surface: blurred wallpaper + tint + soft sheen + specular highlights + a bright,
 * light-catching rim. [emphasis] (0..1) brightens it and adds a soft glow, used for focus.
 * [darken] adds black inside the glass for surfaces that carry lots of text.
 */
fun Modifier.glass(
    corner: Dp,
    emphasis: Float = 0f,
    darken: Float = 0f,
    rim: Boolean = true,
): Modifier = composed {
    val env = LocalGlass.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind {
            drawGlassBody(env, origin, corner.toPx(), emphasis, darken)
            if (rim) drawGlassRim(corner.toPx())
        }
}

/** Just the light-catching edge. Put it on a top overlay so artwork underneath doesn't hide it. */
fun Modifier.glassRim(corner: Dp): Modifier = drawBehind { drawGlassRim(corner.toPx()) }

private fun roundedPath(w: Float, h: Float, r: Float): Path =
    Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r, r))) }

private fun DrawScope.drawGlassBody(env: GlassEnv, origin: Offset, r: Float, e: Float, darken: Float) {
    val w = size.width
    val h = size.height
    val style = env.style
    val path = roundedPath(w, h, r)

    // Soft outer glow when emphasised (focus)
    if (e > 0.01f) {
        for (i in 1..8) {
            val grow = i * 2.6.dp.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = 0.11f * e * (1f - i / 9f)),
                topLeft = Offset(-grow, -grow),
                size = Size(w + 2 * grow, h + 2 * grow),
                cornerRadius = CornerRadius(r + grow),
                style = Stroke(width = 2.6.dp.toPx()),
            )
        }
    }

    clipPath(path) {
        env.blurred?.let { bmp ->
            translate(-origin.x, -origin.y) {
                drawImage(
                    image = bmp,
                    dstSize = IntSize(env.width.roundToInt(), env.height.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
        }
        drawRect(style.tint.copy(alpha = (style.tintAlpha + 0.14f * e).coerceAtMost(0.9f)))
        if (darken > 0f) drawRect(Color.Black.copy(alpha = darken))
        // vertical sheen: lighter at the top, clearer at the bottom
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = style.sheen * (0.8f + 0.6f * e)),
                    Color.White.copy(alpha = 0.02f),
                ),
                startY = 0f,
                endY = h,
            )
        )
        // specular highlight, top-left
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(w * 0.18f, 0f),
                radius = w * 0.75f,
            )
        )
        // faint caustic, bottom-right
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(w * 0.92f, h),
                radius = w * 0.5f,
            )
        )
    }
}

private fun DrawScope.drawGlassRim(r: Float) {
    val w = size.width
    val h = size.height
    drawPath(
        path = roundedPath(w, h, r),
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.85f),
                Color.White.copy(alpha = 0.10f),
                Color.White.copy(alpha = 0.40f),
            ),
            start = Offset.Zero,
            end = Offset(w, h),
        ),
        style = Stroke(width = 1.6.dp.toPx()),
    )
    val inset = 3.dp.toPx()
    drawRoundRect(
        color = Color.White.copy(alpha = 0.08f),
        topLeft = Offset(inset, inset),
        size = Size(w - 2 * inset, h - 2 * inset),
        cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** Small text helper so call sites stay readable (no Material dependency). */
@Composable
fun GText(
    text: String,
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Color.White,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Start,
    shadow: Boolean = false,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontSize = size,
            fontWeight = weight,
            color = color,
            textAlign = align,
            shadow = if (shadow) Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 3f), 14f) else null,
        ),
    )
}
