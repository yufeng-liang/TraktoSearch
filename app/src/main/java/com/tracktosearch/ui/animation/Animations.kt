package com.tracktosearch.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/** 入场模式：DEFAULT=B 错峰淡入上滑；EMPHASIS=E 弹性上滑强调态。 */
enum class EnterMode { DEFAULT, EMPHASIS }

/** 过冲缓动，y 控制点 >1 产生回弹（弹性上滑）。 */
private val EMPHASIS_EASING = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** 错峰延迟：按行内位置 (index%3) 各延 0/30/60ms。 */
fun enterStaggerDelayMs(index: Int): Int = (index % 3) * 30

/** 默认动画是否仍需播放：id 不在已播集合中才播（一生一次）。 */
fun shouldPlayDefault(id: Long, played: Set<Long>): Boolean = !played.contains(id)

/**
 * watchlist 卡片入场动画。
 * - DEFAULT：淡入 + 上滑 16dp，260ms，按 enterStaggerDelayMs 微错峰（B）。
 * - EMPHASIS：弹性上滑 40dp→0，380ms 过冲（E），用于切 tab / 下拉刷新。
 * animatedIds 记录已播过的 id，保证每张卡默认动画一生只播一次。
 */
fun Modifier.cardEnter(
    id: Long,
    index: Int,
    enterMode: EnterMode,
    animatedIds: MutableState<MutableSet<Long>>,
): Modifier = composed {
    val alreadyPlayed = animatedIds.value.contains(id)
    val alpha = remember(id) { Animatable(if (alreadyPlayed) 1f else 0f) }
    val offsetY = remember(id) { Animatable(if (alreadyPlayed) 0f else 16f) }

    LaunchedEffect(enterMode, id) {
        if (enterMode == EnterMode.EMPHASIS) {
            alpha.snapTo(0f)
            offsetY.snapTo(40f)
            launch { alpha.animateTo(1f, tween(380)) }
            offsetY.animateTo(0f, tween(380, easing = EMPHASIS_EASING))
            val set = animatedIds.value.toMutableSet().apply { add(id) }
            animatedIds.value = set
        } else if (shouldPlayDefault(id, animatedIds.value)) {
            val delayMs = enterStaggerDelayMs(index)
            val set = animatedIds.value.toMutableSet().apply { add(id) }
            animatedIds.value = set
            launch { alpha.animateTo(1f, tween(260, delayMillis = delayMs)) }
            offsetY.animateTo(0f, tween(260, delayMillis = delayMs))
        }
    }
    graphicsLayer(alpha = alpha.value, translationY = offsetY.value)
}

/**
 * 列表 item 入场动画：fade + 轻微上滑（保留给 discover / search 等页面使用）。
 */
fun Modifier.fadeSlideIn(index: Int = 0): Modifier = composed {
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
