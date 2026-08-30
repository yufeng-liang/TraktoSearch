package com.tracktosearch.ui.screen.swiftie

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.sin

/** 一团水彩：相对坐标 + 相对半径 + 颜色 + 视差层（0 = 慢远层，1 = 快近层）。 */
private data class Blot(
    val cx: Float, val cy: Float, val r: Float,
    val color: androidx.compose.ui.graphics.Color, val layer: Int
)

private val BLOTS = listOf(
    Blot(0.18f, 0.12f, 0.55f, SwiftiePalette.SkyBlue, 0),
    Blot(0.78f, 0.20f, 0.50f, SwiftiePalette.Lavender, 1),
    Blot(0.30f, 0.55f, 0.60f, SwiftiePalette.CloudPink, 1),
    Blot(0.92f, 0.62f, 0.45f, SwiftiePalette.PeachYellow, 0),
    Blot(0.55f, 0.88f, 0.58f, SwiftiePalette.PinkWhite, 0),
    Blot(0.08f, 0.80f, 0.40f, SwiftiePalette.CloudPink, 1)
)

@Composable
fun SwiftieWatercolorSky(
    modifier: Modifier = Modifier,
    progress: () -> Float = { 0f }
) {
    Canvas(
        modifier = modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    ) {
        val t = progress()
        // 底色打平，避免边缘露出透明
        drawRect(brush = Brush.linearGradient(
            colors = listOf(SwiftiePalette.PinkWhite, SwiftiePalette.CloudPink),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        ))
        BLOTS.forEach { blot ->
            // 两层不同速度错位平移：近层振幅 2.4 倍、相位偏移 0.35，形成视差
            val amp = if (blot.layer == 1) 0.030f else 0.012f
            val phase = if (blot.layer == 1) 0.35f else 0f
            val dx = sin((t + phase) * 2f * Math.PI.toFloat()) * amp
            val dy = sin((t + phase) * 2f * Math.PI.toFloat() * 0.7f) * amp * 0.6f
            val center = Offset((blot.cx + dx) * size.width, (blot.cy + dy) * size.height)
            val radius = blot.r * size.minDimension
            drawCircle(
                brush = Brush.radialGradient(
                    // 中心不到满不透明，多团叠加才有水彩的层次
                    colors = listOf(blot.color.copy(alpha = 0.85f), blot.color.copy(alpha = 0f)),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
    }
}
