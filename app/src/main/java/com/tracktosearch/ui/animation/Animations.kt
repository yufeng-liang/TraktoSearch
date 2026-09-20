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
import com.tracktosearch.ui.component.LocalFastScrollMode
import com.tracktosearch.ui.component.LocalIsCurrentTab
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
 *
 * 性能说明：
 * - "本卡默认动画已播过"由 rememberSaveable 的 played 标记记录，随 LazyGrid item key
 *   保存，滚动回收再回来不会重播；读写只影响本卡，不再牵动屏幕级 animatedIds 集合
 *   （原实现每次动画结束都会整集拷贝 MutableSet 并写状态，N 张卡 = O(N²) 拷贝 + 重组风暴）。
 * - animatedIds 参数仅用于 EMPHASIS 结束后兼容性同步（无卡片在组合期读取它，写入不再引发重组）。
 * - graphicsLayer 用 lambda 块读取动画值，状态采样推迟到绘制阶段，每帧只重绘不重组。
 */
fun Modifier.cardEnter(
    id: Long,
    index: Int,
    enterMode: EnterMode,
    animatedIds: MutableState<MutableSet<Long>>,
): Modifier = composed {
    val isCurrentTab = LocalIsCurrentTab.current
    // 本卡默认动画是否已播过：rememberSaveable 随 item key 保留，回收再回来不重播。
    val played = rememberSaveable { mutableStateOf(false) }
    val alpha = remember(id) { Animatable(if (played.value) 1f else 0f) }
    val offsetY = remember(id) { Animatable(if (played.value) 0f else 16f) }

    LaunchedEffect(enterMode, id, isCurrentTab) {
        if (!isCurrentTab) return@LaunchedEffect
        if (enterMode == EnterMode.EMPHASIS) {
            // EMPHASIS 不受 played 标记影响，照常重播；错峰避免全部卡片同一帧同时弹 380ms。
            val delayMs = enterStaggerDelayMs(index)
            alpha.snapTo(0f)
            offsetY.snapTo(40f)
            launch { alpha.animateTo(1f, tween(380, delayMillis = delayMs)) }
            offsetY.animateTo(0f, tween(380, delayMillis = delayMs, easing = EMPHASIS_EASING))
            // 兼容性同步：写 animatedIds 供外部状态保持一致（如 WatchlistScreen 的下拉刷新重置）。
            // 由于已无卡片在组合期读取它，写入不会触发卡片重组风暴。
            played.value = true
            if (id !in animatedIds.value) {
                animatedIds.value = animatedIds.value.toMutableSet().apply { add(id) }
            }
        } else if (!played.value) {
            // DEFAULT 一生只播一次：先标记再播放，避免 enterMode 变化导致 LaunchedEffect 重跑时重复播放。
            played.value = true
            val delayMs = enterStaggerDelayMs(index)
            launch { alpha.animateTo(1f, tween(260, delayMillis = delayMs)) }
            offsetY.animateTo(0f, tween(260, delayMillis = delayMs))
        }
    }
    graphicsLayer {
        // lambda 块读取：状态值延迟到绘制阶段采样，动画每帧不再触发组合期读取/重组。
        this.alpha = alpha.value
        this.translationY = offsetY.value
    }
}

/**
 * 列表 item 入场动画：fade + 轻微上滑（保留给 discover / search 等页面使用）。
 */
fun Modifier.fadeSlideIn(index: Int = 0): Modifier = composed {
    val hasAnimated = rememberSaveable { mutableStateOf(false) }
    val alpha = remember { Animatable(if (hasAnimated.value) 1f else 0f) }
    val offsetY = remember { Animatable(if (hasAnimated.value) 0f else 16f) }
    val isFastScrolling = LocalFastScrollMode.current
    LaunchedEffect(isFastScrolling) {
        if (isFastScrolling) {
            // 滚动中新进入视口的卡片直接落定，避免动画协程与列表 fling 争抢帧预算。
            alpha.snapTo(1f)
            offsetY.snapTo(0f)
            hasAnimated.value = true
            return@LaunchedEffect
        }
        if (hasAnimated.value) return@LaunchedEffect
        launch { alpha.animateTo(1f, animationSpec = tween(220)) }
        launch { offsetY.animateTo(0f, animationSpec = tween(220)) }
        hasAnimated.value = true
    }
    graphicsLayer {
        // lambda 块读取：动画每帧只重绘，不触发组合期状态读取/重组。
        this.alpha = alpha.value
        this.translationY = offsetY.value
    }
}
