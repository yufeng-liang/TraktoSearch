package com.tracktosearch.ui.screen.swiftie

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.sin
import kotlin.random.Random

/** 一颗心：相对 x、相位、相对边长、是否近层。 */
private class Heart(val x: Float, val phase: Float, val scale: Float, val near: Boolean)

/** 0..1 单位方框里的心形。 */
private fun unitHeartPath(): Path = Path().apply {
    moveTo(0.5f, 0.92f)
    cubicTo(-0.18f, 0.52f, 0.16f, 0.02f, 0.5f, 0.30f)
    cubicTo(0.84f, 0.02f, 1.18f, 0.52f, 0.5f, 0.92f)
    close()
}

/**
 * 8 颗心分布在左右两侧（Spec §4.1「四角」），中间让给算式与标题。
 * 固定种子，重组不跳位。
 */
private fun buildHearts(): List<Heart> {
    val random = Random(87)
    return List(8) { index ->
        val onLeft = index % 2 == 0
        val near = index < 4
        Heart(
            x = if (onLeft) 0.04f + random.nextFloat() * 0.20f
            else 0.76f + random.nextFloat() * 0.20f,
            phase = index / 8f + random.nextFloat() * 0.06f,
            scale = if (near) 0.085f + random.nextFloat() * 0.035f
            else 0.045f + random.nextFloat() * 0.020f,
            near = near
        )
    }
}

private fun DrawScope.drawHearts(time: State<Float>, hearts: List<Heart>, path: Path) {
    val t = time.value
    val tau = 2f * Math.PI.toFloat()
    hearts.forEach { heart ->
        // 近层快 1.75 倍。取模让每颗心各自循环，不会同时消失
        val speed = if (heart.near) 1.75f else 1f
        val travel = ((t * speed + heart.phase) % 1f + 1f) % 1f
        // 1.06 起飞、-1.24 的行程：出画后才折返，看不到接缝
        val cy = (1.06f - travel * 1.24f) * size.height
        val sway = sin(travel * tau) * 0.02f
        val cx = (heart.x + sway) * size.width
        val side = heart.scale * size.minDimension
        // 出画前 18% 行程淡出，避免在上缘硬切
        val fade = ((1f - travel) / 0.18f).coerceIn(0f, 1f)
        val alpha = (if (heart.near) 0.90f else 0.42f) * fade
        if (alpha <= 0.01f) return@forEach
        withTransform({
            translate(cx - side / 2f, cy - side / 2f)
            scale(side, side, pivot = Offset.Zero)
        }) {
            drawPath(
                path = path,
                brush = if (heart.near) {
                    // 近层：实心箔面，边缘清晰
                    Brush.linearGradient(
                        colors = listOf(SwiftiePalette.GlitterLight, SwiftiePalette.Glitter),
                        start = Offset.Zero,
                        end = Offset(1f, 1f)
                    )
                } else {
                    // 远层：中心亮、边缘散掉，读作散焦
                    Brush.radialGradient(
                        colors = listOf(
                            SwiftiePalette.Glitter,
                            SwiftiePalette.Glitter.copy(alpha = 0.15f)
                        ),
                        center = Offset(0.5f, 0.5f),
                        radius = 0.75f
                    )
                },
                alpha = alpha
            )
        }
    }
}

/**
 * 灯箱下缘上浮的 8 颗亮粉爱心，分近/远两个景深层（Spec §4.1）。
 *
 * 铺满给定的框，行程本身超出上下缘，调用方只要裁剪就行，不需要额外留白。
 */
@Composable
fun SwiftieGlitterHearts(modifier: Modifier = Modifier) {
    val hearts = remember { buildHearts() }
    val path = remember { unitHeartPath() }
    val transition = rememberInfiniteTransition(label = "swiftieHearts")
    // 11s 一圈：慢到算「缓慢上浮」，又快到进页面几秒内必有心飘过
    val time = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "heartsPhase"
    )
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache { onDrawBehind { drawHearts(time, hearts, path) } }
    )
}
