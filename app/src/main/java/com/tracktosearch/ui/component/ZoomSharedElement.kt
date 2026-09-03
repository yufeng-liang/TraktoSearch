package com.tracktosearch.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 「缩略图 → 全屏查看器」缩放转场的两个配套修饰符。
 *
 * 两者都是 [appSharedBounds] / [appSharedSource] 的薄封装，只多做一件事：把调用点用的字符串
 * key 包成 [SharedKey]。这一族不需要 origin 消歧 —— 同一时刻只可能开着一个查看器，
 * 由 [LocalFullscreenSharedElement] 指定哪一侧是 target，且字符串 key 里已经带了页面与索引。
 *
 * 关键约束仍然成立：同一个 key 在任一时刻只能有一侧是 target，否则
 * SharedTransitionStateMachine.updateTargetBoundsProvider() 取的是先注册的那一侧（缩略图），
 * 打开时边界会从全屏动到缩略图，方向反了，看起来就是没有动画。
 *
 * 因此缩略图侧不用 AnimatedVisibilityScope（它挂在 NavHost 目的地上，恒为 Visible），
 * 改用 caller-managed visibility，由 [LocalFullscreenSharedElement] 决定本侧是否可见。
 */
/**
 * 把全屏查看器用的字符串 key 包成 [SharedKey]。
 *
 * 提供 [LocalFullscreenSharedElement] 的页面也用这个函数构造，保证两侧完全同构 ——
 * 源侧判断「查看器打开的是不是我」靠的是 [SharedKey] 的相等性，三段里任何一段构造方式不一致
 * 都会让判断恒为 false，表现为打开时缩略图没有隐藏、转场方向反转。
 */
internal fun fullscreenSharedElementKey(key: String?): SharedKey? = key?.let {
    SharedKey(id = it, origin = SharedOrigin.FULLSCREEN_VIEWER, type = SharedElementType.Image)
}

/**
 * 缩略图/源侧修饰符：全屏查看器打开的正是本 key 时，本侧置为不可见。
 *
 * @param key 与全屏侧 [zoomSharedTarget] 完全一致的 key，为 null 表示本次不参与转场
 * @param clipRadius 转场期间在 overlay 中的圆角，null 表示沿用父级裁剪
 */
@Composable
internal fun Modifier.zoomSharedSource(
    key: String?,
    clipRadius: Dp? = null,
): Modifier = appSharedSource(
    key = fullscreenSharedElementKey(key),
    corner = clipRadius?.let { SharedCorner.uniform(it) },
)

/**
 * 全屏/目标侧修饰符：由外层 AnimatedVisibility 驱动进出。
 *
 * resizeMode 显式钉成 RemeasureToBounds，与迁移前 sharedElement 的行为一致。
 * 图片查看器换 scaleToBounds 是后续独立一步的事，那里要按配对两端的宽高比逐对定。
 *
 * @param key 与缩略图侧 [zoomSharedSource] 完全一致的 key，为 null 表示本次不参与转场
 * @param animatedVisibilityScope 全屏层自己的 AnimatedVisibility 作用域（不是 NavHost 的）
 * @param clipRadius 转场期间在 overlay 中的圆角，null 表示沿用父级裁剪
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.zoomSharedTarget(
    key: String?,
    animatedVisibilityScope: AnimatedVisibilityScope,
    clipRadius: Dp? = null,
): Modifier = appSharedBounds(
    key = fullscreenSharedElementKey(key),
    animatedVisibilityScope = animatedVisibilityScope,
    corner = clipRadius?.let { SharedCorner.uniform(it) },
    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
)
