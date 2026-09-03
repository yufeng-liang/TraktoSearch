package com.tracktosearch.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全 App 共享元素转场的统一入口。
 *
 * 设计要点：
 * - 配对键是结构化的 [SharedKey]，由「是谁」「来自哪个集合」「是什么部件」三段组成。
 *   源侧因此只需声明静态事实，不再需要点击 token、活跃 id 一类的运行期状态来消除重复海报的歧义。
 * - 动画规格集中在本文件，[BoundsTransform] 刻意不作为参数暴露：全 App 的边界动画必须同速，
 *   否则同一次导航里不同部件各自弹一套节奏，观感比不做转场更差。
 * - 默认 resizeMode 由 [SharedElementType] 推导，只有配对两端宽高比不同的少数场合才需要显式覆盖。
 */

/** 共享元素的语义类型，决定默认的 resizeMode。 */
enum class SharedElementType {
    /** 整块容器（卡片放大成整页）。 */
    Bounds,

    /** 位图内容，海报、剧照、头像。 */
    Image,

    /** 文本，两端字号不同也不能重排。 */
    Title,

    /** 小图标，等比缩放不裁切。 */
    Icon,

    /** 背景层，渐变或纯色，允许非等比拉伸。 */
    Background,
}

/**
 * 共享元素配对键。两端只有三段全部相等才会配对。
 *
 * @param id 被共享的对象标识，通常是 tmdbId、列表 id 或图片 url 的稳定部分。
 * @param origin 该元素所属的集合或页面实例，见 [SharedOrigin]。同一个 id 在同一屏出现多次
 *   （追踪页和推荐栏都有同一部片子）时，靠 origin 区分谁是真正被点击的那一个：
 *   目标侧携带的 origin 来自导航参数，只与来源那一侧相等。
 * @param type 部件类型，见 [SharedElementType]。同一个 id 的海报、标题、背景各自独立配对。
 */
@Immutable
data class SharedKey(
    val id: String,
    val origin: String,
    val type: SharedElementType,
)

/**
 * origin 取值。一个屏幕内有多个可能撞 id 的列表时，按列表而不是按屏幕细分。
 * 只在此处声明跨页面配对用到的公共值；某个屏幕私有的多列表细分值就近声明在该屏幕文件里。
 */
object SharedOrigin {
    const val WATCHLIST = "watchlist"
    const val DETAIL = "detail"
    const val SETTINGS = "settings"
    const val STATISTICS = "statistics"
    const val TRAKT_LIST = "trakt-list"
    const val DISCOVER_FILTER = "discover-filter"
}

/**
 * 转场期间的圆角。
 *
 * [rest] 是本侧稳定态的圆角，[peer] 是配对另一侧稳定态的圆角。转场期间圆角在两者之间插值，
 * 进入方向从 [peer] 收敛到 [rest]，退出方向反向。两侧各自填写自己的 rest 与对端的 peer，
 * 因此同一对元素的两个调用点参数互为镜像。
 *
 * 传 null 表示不接管裁剪，沿用父级。
 */
@Immutable
data class SharedCorner(val rest: Dp, val peer: Dp) {
    companion object {
        /** 两端圆角相同：转场期不变形，但仍然接管 overlay 裁剪，避免直角穿出。 */
        fun uniform(radius: Dp) = SharedCorner(radius, radius)

        /** 本侧无圆角，对端有：卡片放大成整页最常见的一对。 */
        fun flattenFrom(peer: Dp) = SharedCorner(rest = 0.dp, peer = peer)
    }
}

/**
 * 边界动画规格：临界阻尼、无回弹。
 *
 * 位置和尺寸这类空间属性用偏软的 spring，落定约 480ms；阻尼比取 1 是因为海报和整页容器
 * 过冲之后会先越过目标边界再退回来，在有明确矩形轮廓的元素上非常显眼。
 */
private val AppBoundsSpec: FiniteAnimationSpec<Rect> = spring(
    dampingRatio = 1f,
    stiffness = 380f,
    visibilityThreshold = Rect.VisibilityThreshold,
)

@OptIn(ExperimentalSharedTransitionApi::class)
private val AppBoundsTransform = BoundsTransform { _, _ -> AppBoundsSpec }

/**
 * 透明度这类非空间属性用硬得多的 spring，落定约 200ms。
 *
 * 与边界动画的 0.42 倍时长比是刻意的：淡入先结束、形变后结束，观感上是「一个东西在移动」
 * 而不是「一个东西在淡入的同时还在变形」。
 */
private val AppFadeSpec: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1600f)

internal val AppSharedEnter: EnterTransition = fadeIn(AppFadeSpec)
internal val AppSharedExit: ExitTransition = fadeOut(AppFadeSpec)

/**
 * 按部件类型推导 resizeMode。
 *
 * 一律优先 scaleToBounds：它只在 lookahead 尺寸上量一次，之后靠 graphicsLayer 缩放，
 * 而 RemeasureToBounds 会用逐帧的 `Constraints.fixed` 重新测量子树。后者对内部含有
 * 派生自尺寸的状态的组件（分块解码的图片、按视口生成的网格）是灾难，一次转场里能触发
 * 几十次重新计算。只有两端宽高比差得多、裁切会露馅时才值得付这个代价，那种场合在调用点显式覆盖。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private fun SharedElementType.defaultResizeMode(): SharedTransitionScope.ResizeMode = when (this) {
    // 容器变形从顶部揭示：整页内容的视觉重心在顶部，从中心揭示会让顶栏先被压扁再弹开。
    SharedElementType.Bounds ->
        SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter)
    SharedElementType.Image ->
        SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.Center)
    SharedElementType.Title ->
        SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.CenterStart)
    SharedElementType.Icon ->
        SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.Center)
    SharedElementType.Background ->
        SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillBounds, Alignment.Center)
}

/** 圆角动画与边界动画同规格：圆角先于边界摊平会让方角提前露出来。 */
private val AppCornerSpec: FiniteAnimationSpec<Dp> = spring(
    dampingRatio = 1f,
    stiffness = 380f,
    visibilityThreshold = Dp.VisibilityThreshold,
)

/**
 * 共享边界修饰符：全 App 的共享元素都走这一个入口。
 *
 * 一律用 sharedBounds 而不是 sharedElement —— 后者恒等于 RemeasureToBounds，没有 resizeMode 参数，
 * 无法把逐帧重新测量换成「量一次 + graphicsLayer 缩放」。
 *
 * @param key 为 null 表示本次不参与转场，原样返回。
 * @param animatedVisibilityScope 默认取当前导航目的地的作用域。全屏查看器这类自带 AnimatedVisibility
 *   的场合必须显式传自己的作用域，否则配对到的是页面进出而不是查看器开合。
 * @param corner 转场期间的圆角，null 表示不接管裁剪、沿用父级。
 * @param resizeMode 默认按 [SharedKey.type] 推导，只有配对两端宽高比不同时才需要覆盖。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.appSharedBounds(
    key: SharedKey?,
    animatedVisibilityScope: AnimatedVisibilityScope? = LocalAnimatedVisibilityScope.current,
    corner: SharedCorner? = null,
    resizeMode: SharedTransitionScope.ResizeMode? = null,
    enter: EnterTransition = AppSharedEnter,
    exit: ExitTransition = AppSharedExit,
): Modifier {
    val scope = LocalSharedTransitionScope.current
    if (key == null || scope == null || animatedVisibilityScope == null ||
        !LocalSharedTransitionEnabled.current
    ) {
        return this
    }
    // 同一个圆角值同时喂 clip 与 OverlayClip：转场期元素被抬到 overlay 里绘制，
    // 两处裁剪必须同形状，否则落地那一帧圆角会跳一下。
    val shape = corner?.let { c ->
        val radius = animatedVisibilityScope.transition.animateDp(
            transitionSpec = { AppCornerSpec },
            label = "sharedCorner",
        ) { state ->
            when (state) {
                EnterExitState.Visible -> c.rest
                EnterExitState.PreEnter, EnterExitState.PostExit -> c.peer
            }
        }.value
        RoundedCornerShape(radius)
    }
    val mode = resizeMode ?: key.type.defaultResizeMode()
    return with(scope) {
        val state = rememberSharedContentState(key = key)
        val bounds = if (shape == null) {
            this@appSharedBounds.sharedBounds(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                enter = enter,
                exit = exit,
                boundsTransform = AppBoundsTransform,
                resizeMode = mode,
            )
        } else {
            this@appSharedBounds.sharedBounds(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                enter = enter,
                exit = exit,
                boundsTransform = AppBoundsTransform,
                resizeMode = mode,
                clipInOverlayDuringTransition = OverlayClip(shape),
            )
        }
        if (shape == null) bounds else bounds.clip(shape)
    }
}

/**
 * 当前被全屏查看器打开的共享元素，未打开时为 null。
 *
 * 缩略图侧据此把自己置为「不可见」，保证同一 key 下同时只有一个 target。必须如此：缩略图侧挂的是
 * NavHost 目的地的 AnimatedVisibilityScope，停在该页面期间恒为 Visible。若全屏侧打开时两侧同时是
 * target，状态机会取「先注册」的缩略图作为目标边界提供者，打开时边界从全屏动到缩略图，方向反了，
 * 观感上等于没有缩放动画。
 */
val LocalFullscreenSharedElement = compositionLocalOf<SharedKey?> { null }

/**
 * 缩略图/源侧修饰符：全屏查看器打开的正是本 key 时，本侧置为不可见。
 *
 * 走 caller-managed visibility 而不是 sharedBounds，因此没有 resizeMode 可选（API 不暴露该参数），
 * 恒为逐帧重新测量。源侧尺寸小，且转场期的边界由目标侧提供，这个代价可以接受。
 *
 * @param corner 只取 [SharedCorner.rest]：本侧没有 EnterExitState 可驱动插值，圆角是静态的。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.appSharedSource(
    key: SharedKey?,
    corner: SharedCorner? = null,
): Modifier {
    val scope = LocalSharedTransitionScope.current
    if (key == null || scope == null || !LocalSharedTransitionEnabled.current) return this
    val openedKey = LocalFullscreenSharedElement.current
    return with(scope) {
        val state = rememberSharedContentState(key = key)
        val shape = corner?.let { RoundedCornerShape(it.rest) }
        if (shape == null) {
            this@appSharedSource.sharedElementWithCallerManagedVisibility(
                state,
                visible = openedKey != key,
                boundsTransform = AppBoundsTransform,
            )
        } else {
            this@appSharedSource.sharedElementWithCallerManagedVisibility(
                state,
                visible = openedKey != key,
                boundsTransform = AppBoundsTransform,
                clipInOverlayDuringTransition = OverlayClip(shape),
            )
        }
    }
}
