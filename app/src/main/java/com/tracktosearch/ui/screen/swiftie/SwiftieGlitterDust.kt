package com.tracktosearch.ui.screen.swiftie

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 一粒浮尘。
 *
 * @param x 起始横向位置（0..1）
 * @param drift 一整圈里横向漂移的幅度（占宽度比例，可正可负）
 * @param sway 横向摆动幅度（占宽度比例）
 * @param speed 相对基准周期的快慢，0.6..1.5
 * @param phase 各自的起始相位，让它们不同时出现、不同时消失
 * @param radius 半径（px）
 * @param warm true 走暖白，false 走冷白 —— 全同一个白会显得像噪点图层
 */
private class Mote(
    val x: Float,
    val drift: Float,
    val sway: Float,
    val speed: Float,
    val phase: Float,
    val radius: Float,
    val warm: Boolean
)

/** 基准周期。40s 慢到看不出「又来一轮」，这正是要替掉的那种廉价感。 */
private const val DUST_CYCLE_MS = 40_000

/** 浮尘数量。低端机减到 14（Spec §11.2 的同一档策略）。 */
private const val DUST_COUNT = 34
private const val DUST_COUNT_LOW_RAM = 14

private val DUST_WARM = Color(0xFFFFF3DC)
private val DUST_COOL = Color(0xFFF4FBFF)

private fun buildMotes(count: Int): List<Mote> {
    // 87 是 Travis 的球衣号，和 buildSparkles 的 13 凑成这道题的两个数
    val random = Random(87)
    return List(count) {
        Mote(
            x = random.nextFloat(),
            drift = (random.nextFloat() - 0.35f) * 0.22f,
            sway = 0.004f + random.nextFloat() * 0.018f,
            speed = 0.6f + random.nextFloat() * 0.9f,
            phase = random.nextFloat(),
            radius = 0.9f + random.nextFloat() * 1.7f,
            warm = random.nextBoolean()
        )
    }
}

/**
 * 空气里的闪粉浮尘。
 *
 * 替掉原先那 8 颗循环上浮的爱心 —— **原图里一颗爱心都没有**，那是唯一不属于这幅画的
 * 东西。浮尘不是新加的装饰：它就是闪粉海报在光下本来的样子，是贴图那一层的自然延伸。
 *
 * 反「廉价」的三条：
 * 1. 周期 40s，而不是 11s。慢到看不出在循环。
 * 2. 每粒自带相位与速度倍率，所以没有「一批一起出画、一批一起进画」那种节拍。
 * 3. 亮度用 `sin²` 包络，进出都是渐显渐隐，没有任何一粒在边缘被硬切掉。
 *
 * 逐帧的量只在 `onDrawBehind` 里读，组合期一次都不失效。
 */
@Composable
fun SwiftieGlitterDust(modifier: Modifier = Modifier) {
    val count = if (rememberIsLowRamDevice()) DUST_COUNT_LOW_RAM else DUST_COUNT
    val motes = remember(count) { buildMotes(count) }
    val transition = rememberInfiniteTransition(label = "swiftieDust")
    val clock = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = DUST_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dustClock"
    )

    Spacer(
        modifier = modifier.drawWithCache {
            val tau = 2f * PI.toFloat()
            onDrawBehind {
                val t = clock.value
                motes.forEach { mote ->
                    // 各自的进度：相位错开 + 速度倍率，取小数部分回绕
                    val progress = ((t * mote.speed) + mote.phase).mod(1f)
                    // sin² 包络：0 和 1 处都是 0，所以回绕那一帧看不见
                    val envelope = sin(progress * PI.toFloat()).let { it * it }
                    if (envelope <= 0.02f) return@forEach
                    val x = mote.x + mote.drift * progress +
                        sin((progress + mote.phase) * tau) * mote.sway
                    // 自下往上飘完整屏，起落都在画外
                    val y = 1.08f - progress * 1.16f
                    drawCircle(
                        color = if (mote.warm) DUST_WARM else DUST_COOL,
                        radius = mote.radius * (0.7f + 0.3f * envelope),
                        center = Offset(x * size.width, y * size.height),
                        alpha = envelope * 0.55f
                    )
                }
            }
        }
    )
}
