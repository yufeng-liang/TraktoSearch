package com.tracktosearch.ui.screen.swiftie

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import com.tracktosearch.ui.theme.AmbientMeshBackground
import com.tracktosearch.ui.theme.MeshPreset
import kotlin.math.hypot
import kotlin.math.max

/** 原点到四角的最远距离 —— 圆扩到这个半径才算真的铺满，不会在角上留缺口。 */
private fun maxCornerDistance(origin: Offset, size: Size): Float {
    val dx = max(origin.x, size.width - origin.x)
    val dy = max(origin.y, size.height - origin.y)
    return hypot(dx, dy)
}

/**
 * 以 [origin] 为原点圆形扩张的水彩天空。[progress] 0f..1f。
 *
 * `origin` / `progress` 都是 lambda：值在**绘制阶段**才读，所以每帧只失效 draw，
 * 不会把整棵子树重组一遍。[origin] 返回 [Offset.Unspecified] 时退回画面中心
 * （键盘还没上报坐标的极端情况）。
 */
@Composable
fun SwiftieDiffusion(
    origin: () -> Offset,
    progress: () -> Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                val p = progress().coerceIn(0f, 1f)
                if (p <= 0f) return@drawWithContent
                val center = origin().takeIf { it.isSpecified } ?: this.center
                val radius = maxCornerDistance(center, size) * p
                clipPath(
                    path = Path().apply {
                        addOval(
                            Rect(
                                left = center.x - radius,
                                top = center.y - radius,
                                right = center.x + radius,
                                bottom = center.y + radius
                            )
                        )
                    }
                ) {
                    this@drawWithContent.drawContent()
                }
            }
    ) {
        SwiftieWatercolorSky(
            modifier = Modifier
                .matchParentSize()
                // 从 1.25 收到 1.0，以原点为轴心：圆在长大、画面在拉近，像被点开
                .graphicsLayer {
                    val p = progress().coerceIn(0f, 1f)
                    val scale = 1.25f - 0.25f * p
                    scaleX = scale
                    scaleY = scale
                    val pivot = origin().takeIf { it.isSpecified }
                    transformOrigin = TransformOrigin(
                        pivotFractionX = if (pivot != null && size.width > 0f) {
                            pivot.x / size.width
                        } else 0.5f,
                        pivotFractionY = if (pivot != null && size.height > 0f) {
                            pivot.y / size.height
                        } else 0.5f
                    )
                },
            // 扩散段自己驱动天空视差，与灯箱那份互不干扰
            progress = { progress() * 0.35f }
        )
    }
}

/**
 * NEBULA mesh 的预热层：真实绘制一次，把 AGSL 编译开销提前付在 T0–400 的确认窗口里
 * （Spec §5 约束 2）。
 *
 * **alpha 不能写 0f** —— 渲染器会整层跳过，shader 根本不会编译，预热就白做了。
 * 0.004f 在任何屏幕上都看不见，但走的是真实绘制路径。
 */
@Composable
fun SwiftieMeshPreheat(motionActive: () -> Boolean) {
    AmbientMeshBackground(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 0.004f },
        preset = MeshPreset.NEBULA,
        enabled = true,
        motionActive = motionActive
    )
}
