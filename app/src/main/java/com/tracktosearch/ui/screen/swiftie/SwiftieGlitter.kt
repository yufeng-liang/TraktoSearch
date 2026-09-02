package com.tracktosearch.ui.screen.swiftie

import android.graphics.Matrix
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.imageResource
import com.tracktosearch.R
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

/**
 * 真闪粉的画刷：`swiftie_glitter.webp` **按位置贴上去，不平铺**。
 *
 * 贴板是原海报上整条算式那一块（640×1064，含四周出血），非glitter 的像素已经用邻近的
 * 闪粉补过。这里把它按 [SwiftiePosterInk.GLITTER_PLATE] 映射回算式在海报里的位置 ——
 * 我们的字形和原图是同一套字体、拟合的是同一批实测包围盒，所以**每个数字取到的正是
 * 当初印在这个数字里的闪粉**，连「`100` 的下缘更亮」这种大尺度变化都对得上。
 *
 * 之前试过两版平铺，都在真机上翻车，记下来免得再走一遍：
 *
 * 1. 「取笔画里最大的内切正方形再镜像平铺」：找正方形用的掩膜做过 25px 闭运算，把两个
 *    `0` 的字腔一起填上了，于是最大内切正方形落在**天空**上 —— 每个数字上都横着几道
 *    淡紫竖带。
 * 2. 改成按连通域面积填洞（字腔再窄也填不上）之后正方形只有 42px。42px 的贴图镜像平铺
 *    到 500px 宽的数字上，读出来是壁纸花纹，不是闪粉。
 *
 * 贴板没有重复，所以这两个问题都不存在；`TileMode.Clamp` 保证轻微超出的字形取到边缘那
 * 圈补过的闪粉，而不是透明。
 */
@Composable
internal fun rememberGlitterBrush(poster: Size): Brush {
    val plate = ImageBitmap.imageResource(R.drawable.swiftie_glitter)
    return remember(plate, poster) {
        val box = SwiftiePosterInk.GLITTER_PLATE
        val matrix = Matrix().apply {
            setScale(
                box.width * poster.width / plate.width,
                box.height * poster.height / plate.height
            )
            postTranslate(box.left * poster.width, box.top * poster.height)
        }
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader =
                ImageShader(plate, TileMode.Clamp, TileMode.Clamp).apply {
                    setLocalMatrix(matrix)
                }
        }
    }
}
