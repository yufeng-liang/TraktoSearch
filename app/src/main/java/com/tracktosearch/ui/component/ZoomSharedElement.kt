package com.tracktosearch.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp

/*
 * 「缩略图 → 全屏查看器」缩放转场的配套件：一个源侧修饰符、一个目标侧修饰符，
 * 外加开合阶段判定与裁剪方式枚举。
 *
 * 两个修饰符都是 appSharedBounds / appSharedSource 的薄封装，只多做一件事：把调用点用的字符串
 * key 包成 SharedKey。这一族不需要 origin 消歧 —— 同一时刻只可能开着一个查看器，
 * 由 LocalFullscreenSharedElement 指定哪一侧是 target，且字符串 key 里已经带了页面与索引。
 *
 * 关键约束仍然成立：同一个 key 在任一时刻只能有一侧是 target，否则
 * SharedTransitionStateMachine.updateTargetBoundsProvider() 取的是先注册的那一侧（缩略图），
 * 打开时边界会从全屏动到缩略图，方向反了，看起来就是没有动画。
 *
 * 因此缩略图侧不用 AnimatedVisibilityScope（它挂在 NavHost 目的地上，恒为 Visible），
 * 改用 caller-managed visibility，由 LocalFullscreenSharedElement 决定本侧是否可见。
 */

/**
 * 全屏查看器开合动画的当前阶段。
 *
 * @param running 开或合的动画仍在进行。用来锁手势与翻页：转场期图片正被抬进 overlay 飞行，
 *   此时放手让 telephoto 缩放或让 pager 翻页会把配对的目标页换掉。
 * @param opening 且方向是「打开」。关闭方向不该延迟挂载 telephoto —— 它已经挂着，
 *   卸掉会在缩回的第一帧闪一下。
 */
@Immutable
internal data class ZoomTransitionPhase(val running: Boolean, val opening: Boolean)

/**
 * 读出查看器开合动画的阶段。
 *
 * 两个信号取并集，任一还在跑就算转场中：
 * - [SharedTransitionScope.isTransitionActive]：共享元素边界的 spring，约 480ms 落定；
 * - AnimatedVisibility 自己的端点差异：查看器的 enter 是 `fadeIn(snap())`，一帧就结束。
 *
 * 只看后者会在打开动画的第二帧就判定「转场结束」，于是手势提前解锁、telephoto 提前挂载，
 * 等于这两个防护完全没生效。只看前者在未发生共享边界动画的开合（如无源图配对、纯 fade）时会漏判，也不对。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun zoomTransitionPhase(animatedVisibilityScope: AnimatedVisibilityScope): ZoomTransitionPhase {
    val transition = animatedVisibilityScope.transition
    val running = isAppSharedTransitionActive() || transition.currentState != transition.targetState
    return ZoomTransitionPhase(
        running = running,
        opening = running && transition.targetState == EnterExitState.Visible,
    )
}

/**
 * 缩略图侧的裁剪方式。决定全屏侧用哪种 resizeMode —— 这是本族唯一需要逐对定的东西。
 *
 * 两端画的是同一张图，但构图不同：缩略图是一个固定比例的小框，全屏侧是 [ContentScale.Fit]
 * 之后带黑边的整屏。转场期间要把「整屏那份构图」映射进「缩略图那个框」，映射方式必须和缩略图
 * 自己的 contentScale 一致，否则落地那一帧图片会跳一下。
 */
internal enum class ZoomThumbnailCrop {
    /**
     * 缩略图用 [ContentScale.Crop]：框里看到的是图片中心的一块，没有黑边。
     *
     * 这时全屏侧用 `scaleToBounds(Crop, Center)`：只在 lookahead（全屏）尺寸上量一次，
     * 之后靠 graphicsLayer 缩放。对 telephoto 尤其重要 —— RemeasureToBounds 会用逐帧的
     * `Constraints.fixed` 重新测量，而 telephoto 的子采样分块是按视口尺寸算的，
     * 一次转场能触发几十轮重新分块解码，正是打开大图卡顿的主因。
     */
    Crop,

    /**
     * 缩略图用 [ContentScale.Fit]：框里是完整图片加黑边，黑边比例与全屏侧那份不同。
     *
     * 这时只能用 `RemeasureToBounds` 逐帧重新测量，让黑边在每个中间尺寸上都算对；
     * 用 scaleToBounds 会把全屏那份黑边一起缩进小框里，看到的是「图外面还有一圈边」。
     * 代价可以接受：走这条路的都是 32-64dp 的反馈截图缩略图，重新测量一个 Image 节点很便宜。
     */
    Fit,
}

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
 * @param key 与缩略图侧 [zoomSharedSource] 完全一致的 key，为 null 表示本次不参与转场
 * @param animatedVisibilityScope 全屏层自己的 AnimatedVisibility 作用域（不是 NavHost 的）
 * @param clipRadius 转场期间在 overlay 中的圆角，null 表示沿用父级裁剪
 * @param thumbnailCrop 缩略图侧的裁剪方式，见 [ZoomThumbnailCrop]
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.zoomSharedTarget(
    key: String?,
    animatedVisibilityScope: AnimatedVisibilityScope,
    clipRadius: Dp? = null,
    thumbnailCrop: ZoomThumbnailCrop = ZoomThumbnailCrop.Crop,
): Modifier = appSharedBounds(
    key = fullscreenSharedElementKey(key),
    animatedVisibilityScope = animatedVisibilityScope,
    corner = clipRadius?.let { SharedCorner.uniform(it) },
    resizeMode = when (thumbnailCrop) {
        ZoomThumbnailCrop.Crop ->
            SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.Center)
        ZoomThumbnailCrop.Fit -> SharedTransitionScope.ResizeMode.RemeasureToBounds
    },
)
