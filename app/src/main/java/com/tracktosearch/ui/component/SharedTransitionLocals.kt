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
 * 当前 Composable 子树是否处于"用户可见的当前 tab"。
 * 用于 HorizontalPager 等多页面常驻场景，避免非当前 tab 的同 tmdbId 海报参与 sharedElement 匹配。
 * 默认 true：非 MainScreen 的页面（如 Detail、TraktListDetail 等独立路由）都是当前可见页。
 */
val LocalIsCurrentTab = compositionLocalOf<Boolean> { true }
