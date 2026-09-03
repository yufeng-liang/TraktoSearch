package com.tracktosearch.ui.screen.swiftie

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieErasData
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val TAU = 2f * PI.toFloat()

/**
 * 单团主色的不透明度。
 *
 * 12 团叠起来，中间重叠区实测能到 0.5 以上 —— 这一段底下要压签名、手链与定格合影
 * 三层浅色内容，再高就把它们糊掉了。
 */
private const val BLOOM_ALPHA = 0.15f

/** 环的半径占短边的比例。0.34 让相邻两团刚好互相咬住，环内不留空洞。 */
private const val RING_RADIUS = 0.34f

/** 单团的半径占短边的比例。 */
private const val BLOOM_RADIUS = 0.42f

/**
 * 终局那 18.6 秒的页面背景：12 个时代的主色化开成一环。
 *
 * 签名 / 手链 / 定格这三段**不属于任何一张专辑**，所以不能用
 * `SwiftieEraBackdropLayer` 的任何一档配色。这里把 12 张的主色按发行顺序摆成一环
 * 并各自化开 —— 语义上正是「12 个时代汇成这一个签名」，视觉上是一条被水化开的彩虹。
 *
 * 环极慢自转（[phase] 一圈约 30s，整段只转过 0.6 圈），慢到读作「在呼吸」而不是「在转」。
 *
 * @param progress 0f..1f 淡入，由调用方按时间轴给
 * @param phase 0f..1f 自转相位
 */
@Composable
fun SwiftieFinaleBackdrop(
    progress: () -> Float,
    phase: () -> Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.graphicsLayer {
            // 12 团 radialGradient 要在同一层里混色，不隔离就会各自与屏幕底混，
            // 边界上出现 12 道可见的接缝
            compositingStrategy = CompositingStrategy.Offscreen
            alpha = progress()
        }
    ) {
        // 底色打平，避免边缘露出透明
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(SwiftiePalette.PinkWhite, SwiftiePalette.CloudPink),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        )
        val spin = phase() * TAU
        val ringRadius = size.minDimension * RING_RADIUS
        val bloomRadius = size.minDimension * BLOOM_RADIUS
        SwiftieErasData.ALL.forEachIndexed { index, era ->
            val angle = spin + index / SwiftieErasData.ALL.size.toFloat() * TAU
            val center = Offset(
                x = size.width / 2f + cos(angle) * ringRadius,
                y = size.height / 2f + sin(angle) * ringRadius * 1.25f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        era.mainColor.copy(alpha = BLOOM_ALPHA),
                        Color.Transparent
                    ),
                    center = center,
                    radius = bloomRadius
                ),
                radius = bloomRadius,
                center = center
            )
        }
    }
}
