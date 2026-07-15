package com.tracktosearch.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/**
 * 列表 item 入场动画：fade + 轻微上滑。
 *
 * 优化点：
 * 1. 用 [rememberSaveable] 保存"已播放"标记，跨 Composable 重建（如从详情页返回）保留状态，
 *    避免每次返回都重新播放动画。
 * 2. 去掉按 index 递增的延迟，所有可见 item 同时播放，避免"先空白再一个个出现"的空白状态。
 * 3. 持续时间缩短为 220ms，让列表快速呈现。
 */
fun Modifier.fadeSlideIn(index: Int = 0): Modifier = composed {
    // 跨 Composable 重建保留"已播放"标记，避免返回时重复播放动画
    val hasAnimated = rememberSaveable { mutableStateOf(false) }
    val alpha = remember { Animatable(if (hasAnimated.value) 1f else 0f) }
    val offsetY = remember { Animatable(if (hasAnimated.value) 0f else 16f) }
    LaunchedEffect(Unit) {
        if (hasAnimated.value) return@LaunchedEffect
        launch { alpha.animateTo(1f, animationSpec = tween(220)) }
        launch { offsetY.animateTo(0f, animationSpec = tween(220)) }
        hasAnimated.value = true
    }
    graphicsLayer(
        alpha = alpha.value,
        translationY = offsetY.value
    )
}

/** 错峰延迟：按行内位置 (index%3) 各延 0/30/60ms，避免深列表累计延迟爆炸。 */
fun enterStaggerDelayMs(index: Int): Int = (index % 3) * 30

/** 默认动画是否仍需播放：id 不在已播集合中才播（一生一次）。 */
fun shouldPlayDefault(id: Long, played: Set<Long>): Boolean = !played.contains(id)
