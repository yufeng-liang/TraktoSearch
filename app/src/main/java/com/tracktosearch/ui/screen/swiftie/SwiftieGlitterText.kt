package com.tracktosearch.ui.screen.swiftie

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import kotlin.math.sin
import kotlin.random.Random

/** 一颗亮点：相对坐标 + 相位 + 基准半径（px）。 */
internal class Sparkle(val x: Float, val y: Float, val phase: Float, val radius: Float)

/** 固定种子，重组不会让亮点跳位。13 是 Taylor 的幸运数。 */
internal fun buildSparkles(count: Int): List<Sparkle> {
    val random = Random(13)
    return List(count) {
        Sparkle(
            x = random.nextFloat(),
            y = random.nextFloat(),
            phase = random.nextFloat(),
            radius = 0.8f + random.nextFloat() * 1.8f
        )
    }
}

/**
 * 量一段马克笔文字的排版结果。
 *
 * `internal` 是为了让灯箱能拿它给 `?` 位预留固定宽度 —— 不必真画一份透明文字占位
 * （那会白跑一遍闪粉 mask 与 60 颗亮点）。
 */
@Composable
internal fun rememberMarkerLayout(text: String, fontSize: TextUnit): TextLayoutResult {
    val measurer = rememberTextMeasurer()
    val style = remember(fontSize) {
        TextStyle(fontFamily = SwiftieFonts.Marker, fontSize = fontSize)
    }
    return remember(text, style, measurer) { measurer.measure(text, style) }
}

internal fun DrawScope.drawGlitterBody(time: State<Float>, sparkles: List<Sparkle>) {
    val t = time.value
    // 底层箔面渐变极慢平移；Mirror 让往复无缝，不会在回头那帧跳一下
    val shift = t * size.width
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                SwiftiePalette.GlitterDeep,
                SwiftiePalette.Glitter,
                SwiftiePalette.GlitterLight,
                SwiftiePalette.Glitter,
                SwiftiePalette.GlitterDeep
            ),
            start = Offset(shift - size.width, 0f),
            end = Offset(shift, size.height),
            tileMode = TileMode.Mirror
        )
    )
    val tau = 2f * Math.PI.toFloat()
    sparkles.forEach { sparkle ->
        val pulse = sin((t + sparkle.phase) * tau) * 0.5f + 0.5f
        if (pulse <= 0.05f) return@forEach
        drawCircle(
            color = Color.White,
            radius = sparkle.radius * (0.55f + 0.45f * pulse),
            center = Offset(sparkle.x * size.width, sparkle.y * size.height),
            alpha = pulse * 0.9f
        )
    }
}

/**
 * 闪粉的共用时钟。2600ms 一圈：慢到不刺眼，又看得出在动。
 *
 * 抽出来是为了让灯箱与签名读同一个相位源 —— 各自起一条无限动画会闪得不同步。
 */
@Composable
internal fun rememberGlitterTime(): State<Float> {
    val transition = rememberInfiniteTransition(label = "swiftieGlitter")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glitterPhase"
    )
}

/**
 * 闪粉文字：箔面渐变 + 亮点脉动，用字形 mask 裁形。
 *
 * 低端机亮点 60 → 24（Spec §11.2）。字形 mask 缓存在 [drawWithCache] 里，
 * 只有 text / 尺寸变化才重建。
 */
@Composable
fun SwiftieGlitterText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val layout = rememberMarkerLayout(text, fontSize)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val sparkleCount = if (rememberIsLowRamDevice()) 24 else 60
    val sparkles = remember(sparkleCount) { buildSparkles(sparkleCount) }

    val time = rememberGlitterTime()

    Spacer(
        modifier = modifier
            .size(
                width = with(density) { layout.size.width.toDp() },
                height = with(density) { layout.size.height.toDp() }
            )
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val mask = ImageBitmap(
                    width = size.width.toInt().coerceAtLeast(1),
                    height = size.height.toInt().coerceAtLeast(1)
                )
                CanvasDrawScope().draw(this, layoutDirection, Canvas(mask), size) {
                    drawText(layout, color = Color.White)
                }
                onDrawBehind {
                    drawGlitterBody(time, sparkles)
                    drawImage(mask, blendMode = BlendMode.DstIn)
                }
            }
    )
}

/** 纯色马克笔文字。与 [SwiftieGlitterText] 同度量，唯一区别是不闪。 */
@Composable
fun SwiftieMarkerText(
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    drawStyle: DrawStyle = Fill
) {
    val layout = rememberMarkerLayout(text, fontSize)
    val density = LocalDensity.current
    Spacer(
        modifier = modifier
            .size(
                width = with(density) { layout.size.width.toDp() },
                height = with(density) { layout.size.height.toDp() }
            )
            .drawWithCache {
                onDrawBehind { drawText(layout, color = color, drawStyle = drawStyle) }
            }
    )
}
