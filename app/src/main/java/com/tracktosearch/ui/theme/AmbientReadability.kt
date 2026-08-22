package com.tracktosearch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow

/**
 * 直接压在背景光晕上的文字（页面大标题、分区小标题等）用的柔光描边。
 *
 * 颜色取 background：浅色主题下正文是深色、背景是浅色，描边就能把深色字从任何一块
 * 深色光斑里拎出来；深色主题反之。因此两种主题共用一套，不必分别配色。
 * 背景光晕关闭时描边与底色同色，等于不可见，所以不需要额外开关。
 *
 * 为什么不用 BlendMode.Difference 做"实时反色"：
 * Compose 1.11 的 `Modifier.graphicsLayer { blendMode = ... }` 只有当该图层被绘制进另一个
 * 离屏图层时才生效。要让文字对背景反色，必须把背景层和整个内容层裹进同一个全屏 offscreen
 * buffer —— 这跟 Haze 的实时模糊（同样要读背景）互相打架，还要多付一整块全屏缓冲的带宽。
 * 更关键的是背景是多色缓动的，反出来的字色会跟着背景一直漂移，盯着读比固定色更累。
 * 柔光描边成本几乎为零、颜色稳定，是这个场景更实用的解法。
 */
@Composable
@ReadOnlyComposable
fun ambientTextHalo(
    alpha: Float = 0.85f,
    blurRadius: Float = 14f,
): Shadow = Shadow(
    color = MaterialTheme.colorScheme.background.copy(alpha = alpha),
    offset = Offset.Zero,
    blurRadius = blurRadius,
)
