package com.tracktosearch.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.dp

/**
 * Shimmer 动画状态：进度 + 明暗两档颜色。
 *
 * 一个骨架块共享一份，避免每个方块各跑一条无限动画。进度只由 [Modifier.shimmer]
 * 在绘制阶段读取，所以动画每帧只失效绘制，不会触发重组。
 */
@Stable
class ShimmerState internal constructor(
    private val progress: State<Float>,
    private val baseColor: Color,
    private val highlightColor: Color
) {
    private var cachedProgress = Float.NaN
    private var cachedBrush: Brush? = null

    /**
     * 当前帧的渐变刷子。
     *
     * 同一帧内所有方块拿到同一个 Brush 实例，让 ShaderBrush 内部的 shader 缓存生效；
     * 每个方块各建一个 Brush 会导致每帧新建几十次原生 shader。
     */
    internal fun brush(): Brush {
        val value = progress.value
        cachedBrush?.let { if (value == cachedProgress) return it }
        val brush = Brush.linearGradient(
            colors = listOf(baseColor, highlightColor, baseColor),
            start = Offset(value * 600f, 0f),
            end = Offset(value * 600f + 300f, 0f)
        )
        cachedProgress = value
        cachedBrush = brush
        return brush
    }
}

/** 骨架块共享的 shimmer 动画状态，配合 [Modifier.shimmer] 使用。 */
@Composable
fun rememberShimmer(): ShimmerState {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )
    val baseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    return remember(progress, baseColor, highlightColor) {
        ShimmerState(progress, baseColor, highlightColor)
    }
}

/**
 * 骨架屏 shimmer 背景。
 *
 * [shape] 直接参与绘制，不需要额外的 [Modifier.clip] 图层。
 */
fun Modifier.shimmer(
    state: ShimmerState,
    shape: Shape = RectangleShape
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    onDrawBehind { drawOutline(outline = outline, brush = state.brush()) }
}

/**
 * 影视卡片骨架屏 - 用于想看列表加载态
 *
 * 一屏能排下十来个，所以走 [ShimmerState]：动画只失效绘制，不会让每个骨架卡每帧重组。
 * [shimmer] 传 null 时各自建一份动画，页面里骨架多就在调用方 [rememberShimmer] 提一份共享。
 */
@Composable
fun MovieCardSkeleton(
    modifier: Modifier = Modifier,
    shimmer: ShimmerState? = null
) {
    val state = shimmer ?: rememberShimmer()
    val shape = RoundedCornerShape(13.dp)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .shimmer(state, shape)
        )
        Column(modifier = Modifier.padding(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .shimmer(state, RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(10.dp)
                    .shimmer(state, RoundedCornerShape(4.dp))
            )
        }
    }
}

/**
 * 榜单小卡片骨架屏 - 用于豆瓣热榜加载态
 *
 * 与 [MovieCardSkeleton] 同走 [ShimmerState]：动画只失效绘制；调用方骨架多时共享一份 [shimmer]。
 */
@Composable
fun DoubanHotCardSkeleton(
    modifier: Modifier = Modifier,
    shimmer: ShimmerState? = null
) {
    val state = shimmer ?: rememberShimmer()
    // 与加载完成后的豆瓣卡片（DoubanHotCard）统一 13.dp 圆角
    val shape = RoundedCornerShape(13.dp)

    Column(modifier = modifier.width(105.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .shimmer(state, shape)
        )
        Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(10.dp)
                    .shimmer(state, RoundedCornerShape(4.dp))
            )
        }
    }
}
