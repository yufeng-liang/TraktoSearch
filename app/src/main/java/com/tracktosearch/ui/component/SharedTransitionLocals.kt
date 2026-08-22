package com.tracktosearch.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.compositionLocalOf

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 共享元素转场动画是否启用(全局开关,默认 false)。
 * 关闭时所有 sharedElement/sharedBounds 使用点不附加修饰符,降级为 NavHost 默认过渡。
 * 各使用点应在判空条件中加 `&& LocalSharedTransitionEnabled.current`。
 */
val LocalSharedTransitionEnabled = compositionLocalOf<Boolean> { false }

/**
 * 当前活跃的海报 tmdbId,用于确保只有用户点击的卡片参与共享元素转场,避免跨页面/同页面重复海报 key 冲突。
 * - -1:哨兵值(默认),所有卡片都不启用 sharedElement(页面初始状态,点击前)
 * - 具体 tmdbId:只有 tmdbId 匹配的卡片启用 sharedElement(用户点击后)
 *
 * 每个使用 MovieCard 的页面都应通过 CompositionLocalProvider 显式提供活跃状态和 setter。
 *
 * 注意:仅靠 tmdbId 仍无法区分同一页面不同栏目下的同 tmdbId 海报,
 * 配合 [LocalActivePosterClickToken] 使用 — 每次点击生成新的 token,
 * 只有"被点击的那个卡片实例"的 token 与活跃 token 匹配才启用 sharedElement。
 */
val LocalActivePosterTmdbId = compositionLocalOf<Int> { -1 }

/** 设置当前活跃海报 tmdbId 的回调,默认空函数(未设置的页面点击不会激活转场) */
val LocalActivePosterTmdbIdSetter = compositionLocalOf<(Int) -> Unit> { {} }

/**
 * 当前活跃的点击 token,每次用户点击海报时递增。
 * 与 [LocalActivePosterTmdbId] 配合,精确匹配"被点击的那个卡片实例"。
 * - 0:默认值(未点击过)
 * - 正整数:每次点击递增的 token
 *
 * 用法:MovieCard 在 remember 中保存自己的 clickToken(由 setActivePosterTmdbId 回调返回),
 * 与 LocalActivePosterClickToken 比对,只有匹配的卡片才启用 sharedElement。
 */
val LocalActivePosterClickToken = compositionLocalOf<Int> { 0 }

/**
 * 设置活跃 tmdbId 并返回新 token 的回调。
 * 调用方(MovieCard)应在点击时调用此回调,并把返回值保存到自己的 state 中,
 * 用于与 [LocalActivePosterClickToken] 比对。
 */
val LocalActivePosterClickSetter = compositionLocalOf<(Int) -> Int> { { _ -> 0 } }

/**
 * 当前被全屏查看器打开的共享元素 key(形如 "backdrop-zoom-123-4"),未打开时为 null。
 *
 * 缩略图侧据此把自己置为「不可见」,保证同一 key 下同时只有一个 target。
 * 必须如此:缩略图侧挂的是 NavHost 目的地的 AnimatedVisibilityScope,停在该页面期间恒为
 * EnterExitState.Visible,若全屏侧打开时两侧 target 同时为 true,
 * SharedTransitionStateMachine 会取「先注册」的缩略图作为目标边界提供者,
 * 打开时边界从全屏动到缩略图(方向反了),观感上等于没有缩放动画。
 *
 * 由 [zoomSharedSource] 读取,各页面在顶层用 CompositionLocalProvider 提供。
 */
val LocalFullscreenSharedKey = compositionLocalOf<String?> { null }

/**
 * 当前 Composable 子树是否处于"用户可见的当前 tab"。
 * 用于 HorizontalPager 等多页面常驻场景，避免非当前 tab 的同 tmdbId 海报参与 sharedElement 匹配。
 * 默认 true：非 MainScreen 的页面（如 Detail、TraktListDetail 等独立路由）都是当前可见页。
 */
val LocalIsCurrentTab = compositionLocalOf<Boolean> { true }
