package com.tracktosearch.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.compositionLocalOf

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 当前活跃的海报 tmdbId，用于确保只有用户点击的卡片参与共享元素转场，避免跨页面/同页面重复海报 key 冲突。
 * - -1：哨兵值（默认），所有卡片都不启用 sharedElement（页面初始状态，点击前）
 * - 具体 tmdbId：只有 tmdbId 匹配的卡片启用 sharedElement（用户点击后）
 * 每个使用 MovieCard 的页面都应通过 CompositionLocalProvider 显式提供活跃状态和 setter。
 */
val LocalActivePosterTmdbId = compositionLocalOf<Int> { -1 }

/** 设置当前活跃海报 tmdbId 的回调，默认空函数（未设置的页面点击不会激活转场） */
val LocalActivePosterTmdbIdSetter = compositionLocalOf<(Int) -> Unit> { {} }

/**
 * 当前 Composable 子树是否处于"用户可见的当前 tab"。
 * 用于 HorizontalPager 等多页面常驻场景，避免非当前 tab 的同 tmdbId 海报参与 sharedElement 匹配。
 * 默认 true：非 MainScreen 的页面（如 Detail、TraktListDetail 等独立路由）都是当前可见页。
 */
val LocalIsCurrentTab = compositionLocalOf<Boolean> { true }
