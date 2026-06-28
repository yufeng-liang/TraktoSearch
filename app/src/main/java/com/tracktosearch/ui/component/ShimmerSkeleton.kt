package com.tracktosearch.ui.component

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shimmer 动画刷子，用于骨架屏闪烁效果
 */
@Composable
fun rememberShimmerBrush(): Brush {
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
    val shimmerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)

    return Brush.linearGradient(
        colors = listOf(shimmerColor, highlightColor, shimmerColor),
        start = Offset(progress.value * 600f, 0f),
        end = Offset(progress.value * 600f + 300f, 0f)
    )
}

/**
 * 影视卡片骨架屏 - 用于想看列表加载态
 */
@Composable
fun MovieCardSkeleton(
    modifier: Modifier = Modifier
) {
    val brush = rememberShimmerBrush()
    val shape = RoundedCornerShape(12.dp)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(brush)
        )
        Column(modifier = Modifier.padding(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
        }
    }
}

/**
 * 榜单小卡片骨架屏 - 用于豆瓣热榜加载态
 */
@Composable
fun DoubanHotCardSkeleton(
    modifier: Modifier = Modifier
) {
    val brush = rememberShimmerBrush()
    val shape = RoundedCornerShape(10.dp)

    Column(modifier = modifier.width(105.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(brush)
        )
        Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
        }
    }
}
