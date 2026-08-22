package com.tracktosearch.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/**
 * 「缩略图 → 全屏查看器」缩放转场的两个配套修饰符。
 *
 * 关键约束:同一个 key 在任一时刻只能有一侧是 target,否则
 * SharedTransitionStateMachine.updateTargetBoundsProvider() 取的是第一个 target
 * (注册更早的缩略图),打开时边界会从全屏动到缩略图,方向反了,看起来就是没有动画。
 *
 * 因此缩略图侧不用 AnimatedVisibilityScope(它挂在 NavHost 目的地上,恒为 Visible),
 * 改用 caller-managed visibility,由 [LocalFullscreenSharedKey] 决定本侧是否可见。
 */

/**
 * 缩略图/源侧修饰符:全屏查看器打开的正是本 key 时,本侧置为不可见。
 *
 * @param key 与全屏侧 [zoomSharedTarget] 完全一致的 key,为 null 表示本次不参与转场
 * @param clipShape 转场期间在 overlay 中的裁剪形状,null 表示沿用父级裁剪
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.zoomSharedSource(
    key: String?,
    clipShape: Shape? = null,
): Modifier {
    val scope = LocalSharedTransitionScope.current
    if (key == null || scope == null || !LocalSharedTransitionEnabled.current) return this
    val openedKey = LocalFullscreenSharedKey.current
    return with(scope) {
        val state = rememberSharedContentState(key = key)
        if (clipShape == null) {
            this@zoomSharedSource.sharedElementWithCallerManagedVisibility(
                state,
                visible = openedKey != key,
            )
        } else {
            this@zoomSharedSource.sharedElementWithCallerManagedVisibility(
                state,
                visible = openedKey != key,
                clipInOverlayDuringTransition = OverlayClip(clipShape),
            )
        }
    }
}

/**
 * 全屏/目标侧修饰符:由外层 AnimatedVisibility 驱动进出。
 *
 * @param key 与缩略图侧 [zoomSharedSource] 完全一致的 key,为 null 表示本次不参与转场
 * @param animatedVisibilityScope 全屏层自己的 AnimatedVisibility 作用域(不是 NavHost 的)
 * @param clipShape 转场期间在 overlay 中的裁剪形状,null 表示沿用父级裁剪
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.zoomSharedTarget(
    key: String?,
    animatedVisibilityScope: AnimatedVisibilityScope,
    clipShape: Shape? = null,
): Modifier {
    val scope = LocalSharedTransitionScope.current
    if (key == null || scope == null || !LocalSharedTransitionEnabled.current) return this
    return with(scope) {
        val state = rememberSharedContentState(key = key)
        if (clipShape == null) {
            this@zoomSharedTarget.sharedElement(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        } else {
            this@zoomSharedTarget.sharedElement(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                clipInOverlayDuringTransition = OverlayClip(clipShape),
            )
        }
    }
}
