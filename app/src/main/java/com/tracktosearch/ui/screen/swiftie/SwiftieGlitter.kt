package com.tracktosearch.ui.screen.swiftie

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.imageResource
import com.tracktosearch.R
import kotlin.math.roundToInt
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
 * 程序化箔面：渐变 + 亮点脉动。**只剩落款签名在用**。
 *
 * 出题页的算式已经换成从原图挖出来的闪粉贴图（见 [rememberGlitterBrush]）——
 * 这条渐变在算式那个字号下读出来是一片平粉，跟真闪粉差得远。签名那一段没跟着换：
 * 它是另一个画面、另一段动画，不在这次复刻的范围里。
 */
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
 * 抽出来是为了让海报与签名读同一个相位源 —— 各自起一条无限动画会闪得不同步。
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

/** 贴图在原图（2160×3840）里的采样边长，用来把它缩回屏幕上应有的颗粒大小。 */
private const val GLITTER_SOURCE_HEIGHT = 3840f

/**
 * 真闪粉的画刷：`swiftie_glitter.webp` 镜像平铺。
 *
 * 贴图是从原海报 `100` 的笔画里挖出来的 100×100 正方形（那是笔画里能容下的最大内切
 * 正方形），所以颜色和颗粒不是调出来的，是原图本身 —— 实测亮度跨 46–232，正是这个
 * 跨度让它读起来像闪粉，而不是一块粉色。
 *
 * **贴图在这里就按目标尺寸缩好，不靠 shader 的 local matrix 缩** ——
 * shader 每帧缩小采样等于每帧对随机颗粒做点采样，颗粒会闪烁跳动；
 * 一次性 `createScaledBitmap` 走双线性，之后每帧都是 1:1 取样。
 *
 * `TileMode.Mirror` 省掉了做无缝贴图这件事：镜像接缝在随机颗粒上看不出来。
 *
 * @param posterHeightPx 海报在屏幕上的实际高度（px）。贴图按 `它 / 3840` 缩，
 *   于是屏幕上的颗粒和原图里的颗粒是同一个视觉大小
 */
@Composable
internal fun rememberGlitterBrush(posterHeightPx: Float): Brush {
    val source = ImageBitmap.imageResource(R.drawable.swiftie_glitter)
    return remember(source, posterHeightPx) {
        val scale = (posterHeightPx / GLITTER_SOURCE_HEIGHT).coerceIn(0.15f, 1f)
        val side = (source.width * scale).roundToInt().coerceAtLeast(8)
        val tile = if (side == source.width) {
            source
        } else {
            Bitmap.createScaledBitmap(source.asAndroidBitmap(), side, side, true).asImageBitmap()
        }
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader =
                ImageShader(tile, TileMode.Mirror, TileMode.Mirror)
        }
    }
}
