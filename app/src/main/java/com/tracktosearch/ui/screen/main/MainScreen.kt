@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.screen.main

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.local.CloudPermissionStorage
import com.tracktosearch.data.local.OnboardingStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.ui.component.LocalIsCurrentTab
import com.tracktosearch.ui.component.AppGlassStyles
import com.tracktosearch.ui.component.appVisualEffect
import com.tracktosearch.ui.component.NeumorphicActiveTab
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.OnboardingOverlay
import com.tracktosearch.ui.component.PageBackground
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.screen.discover.DiscoverScreen
import com.tracktosearch.ui.screen.feedback.FeedbackViewModel
import com.tracktosearch.ui.screen.search.CloudThemeProvider
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.search.SearchSourceType
import com.tracktosearch.ui.screen.settings.AccentColorDialog
import com.tracktosearch.ui.screen.settings.SettingsScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchViewModel
import com.tracktosearch.ui.screen.watchlist.MediaUiItem
import com.tracktosearch.ui.screen.watchlist.WatchlistScreen
import com.tracktosearch.ui.navigation.NotificationNavigator
import com.tracktosearch.ui.navigation.SearchNavigator
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.showToast
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSampling
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.where
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** EntryPoint 用于在非 ViewModel 场景获取 TraktRepository（读取用户头像缓存） */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TraktRepositoryEntryPoint {
    fun traktRepository(): TraktRepository
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThemeStorageEntryPoint {
    fun themeStorage(): ThemeStorage
}

/** EntryPoint 用于在 MainScreen 读取豆瓣登录态/资料（豆瓣独立模式下显示头像） */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DoubanAuthStorageEntryPoint {
    fun doubanAuthStorage(): com.tracktosearch.data.local.DoubanAuthStorage
}

@Composable
fun MainScreen(
    initialTab: Int = 0,
    isLoggedIn: Boolean,
    /**
     * Trakt 是否已连接(独立于豆瓣登录态)。
     * 用于 AccountItem 精确判断 Trakt 行显示登录还是登出,避免被综合 isLoggedIn(= isTraktConnected || isDoubanLoggedIn) 误判。
     */
    isTraktConnected: Boolean = false,
    /**
     * 是否处于豆瓣独立模式（激活网关 + 已登录豆瓣 + 未连 trakt）。
     * 影响「我的」tab 头像来源：豆瓣模式下取 DoubanAuthStorage.doubanProfile.avatarUrl，
     * trakt 模式下沿用 TraktRepository.getUserProfile。
     */
    isDoubanMode: Boolean = false,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    // Watchlist 条目统一回调，保留豆瓣 ID 和媒体类型。
    onMediaItemClick: ((item: MediaUiItem, inWatchlist: Boolean, isWatched: Boolean) -> Unit)? = null,
    onSearchClick: (keyword: String) -> Unit,
    onNavigateToLogin: () -> Unit,
    onTraktLogin: () -> Unit = { onNavigateToLogin() },
    onStatisticsClick: () -> Unit,
    onMarkRecordsClick: () -> Unit = {},
    onTraktSearch: (type: String, query: String) -> Unit,
    onPersonClick: (tmdbId: Int, name: String, profileUrl: String?, avatarColor: Color?) -> Unit = { _, _, _, _ -> },
    onListClick: (listId: Int, listName: String) -> Unit = { _, _ -> },
    onLogout: () -> Unit,
    onHelpClick: () -> Unit,
    onRestartOnboarding: () -> Unit,
    onFilterDiscoverClick: () -> Unit = {},
    onDoubanResync: () -> Unit = {},
    onNavigateToDoubanLogin: () -> Unit = {},
    onSpiderTest: () -> Unit = {},
    onFeedbackClick: () -> Unit = {},
    onMessagesClick: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    val feedbackViewModel: FeedbackViewModel = hiltViewModel()
    val unreadCount by feedbackViewModel.unreadCount.collectAsState()
    LaunchedEffect(Unit) { feedbackViewModel.fetchUnreadCount() }
    var searchSourceType by rememberSaveable { mutableStateOf(SearchSourceType.MOVIE) }
    var traktSearchQuery by rememberSaveable { mutableStateOf("") }
    var traktSearchType by rememberSaveable { mutableStateOf(SearchSourceType.MOVIE) }
    var showTraktSearch by rememberSaveable { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = initialTab) { 4 }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 新手引导
    val onboardingStorage = remember { OnboardingStorage(context.applicationContext) }
    val onboardingCompleted by onboardingStorage.isCompleted.collectAsState(initial = true)
    val themeSelectionCompleted by onboardingStorage.isThemeSelectionCompleted.collectAsState(initial = true)
    val themeStorage = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ThemeStorageEntryPoint::class.java).themeStorage()
    }
    val currentAccent by themeStorage.accentColor.collectAsState()
    var showAccentOnboarding by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    val tabRects = remember { mutableStateOf<List<Rect>>(emptyList()) }
    val density = LocalDensity.current

    // 白云主题管理器（用于新手引导完成后触发权限提示）
    val cloudThemeManager = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, CloudThemeProvider::class.java).cloudThemeManager()
    }
    val cloudPermissionStorage = remember {
        CloudPermissionStorage(context.applicationContext)
    }

    // 用户头像：登录后从对应来源获取
    // - 豆瓣模式：从 DoubanAuthStorage.doubanProfile.avatarUrl 读取（StateFlow 直读，登录后即可拿到）
    // - trakt 模式：从 TraktRepository.getUserProfile() 获取（带永久缓存）
    // - GUEST 模式：不显示头像（isLoggedIn=false 时占位）
    val traktRepository = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            TraktRepositoryEntryPoint::class.java
        ).traktRepository()
    }
    val traktProfile by traktRepository.userProfile.collectAsState()
    val doubanAuthStorage = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, DoubanAuthStorageEntryPoint::class.java).doubanAuthStorage()
    }
    val doubanProfile by doubanAuthStorage.doubanProfile.collectAsState()
    LaunchedEffect(isLoggedIn, isDoubanMode) {
        if (isLoggedIn) {
            if (isDoubanMode) {
                // 豆瓣模式：avatarUrl 由 doubanProfile StateFlow 实时驱动（在 NavTabItem 中读取），
                // 这里不触发 Trakt 刷新。
            } else {
                // trakt 模式：从 TraktRepository 获取头像
                traktRepository.getUserProfile()
            }
        }
    }

    // 悬浮导航显隐状态(提前声明,供 LaunchedEffect(onboardingCompleted) 使用)
    var isFabVisible by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(onboardingCompleted, themeSelectionCompleted) {
        if (onboardingCompleted == false) {
            showAccentOnboarding = !themeSelectionCompleted
            showOnboarding = themeSelectionCompleted
            // 重置到搜索页，确保新手引导从搜索页开始
            // 同时强制显示底部导航(若设置页滚动时把它隐藏了)
            isFabVisible = 1f
            scope.launch {
                pagerState.scrollToPage(0)
                selectedTab = 0
            }
        } else if (onboardingCompleted == true && themeSelectionCompleted == false) {
            // 半完成状态卡死修复: onboarding 已完成但主题选择未完成(如进程被杀打断)
            // 仍需触发主题选择对话框,否则用户再也看不到入口
            showAccentOnboarding = true
            showOnboarding = false
        }
    }

    if (showAccentOnboarding) {
        AccentColorDialog(
            currentAccent = currentAccent,
            onAccentSelected = { accent ->
                scope.launch {
                    themeStorage.setAccentColor(accent)
                    onboardingStorage.setThemeSelectionCompleted(true)
                    showAccentOnboarding = false
                    showOnboarding = true
                }
            },
            onDismiss = {
                scope.launch {
                    onboardingStorage.setThemeSelectionCompleted(true)
                    showAccentOnboarding = false
                    showOnboarding = true
                }
            },
            dialogTitle = stringResource(R.string.onboarding_choose_theme)
        )
    }

    // Pager 滑动 → 同步 selectedTab
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
    }

    // 通知点击可能发生在 Activity 已经打开时，主动切到 Watchlist 页。
    LaunchedEffect(Unit) {
        NotificationNavigator.pendingTarget.collect { target ->
            if (target != null) {
                pagerState.animateScrollToPage(2)
                selectedTab = 2
            }
        }
    }

    // Widget 点击只保留一个待处理请求，进入主页后切到统一搜索页并消费。
    LaunchedEffect(Unit) {
        SearchNavigator.pending.collect { pending ->
            if (pending) {
                pagerState.scrollToPage(0)
                selectedTab = 0
                SearchNavigator.consume()
            }
        }
    }

    // 获取屏幕宽度用于导航栏宽度计算
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp

    // 双击返回退出
    var lastBackTime by remember { mutableLongStateOf(0L) }
    val view = LocalView.current
    val pressBackAgainText = stringResource(R.string.press_back_again)
    BackHandler(enabled = true) {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < 2000) {
            (context as? android.app.Activity)?.finish()
        } else {
            lastBackTime = now
            context.showToast(pressBackAgainText)
        }
    }

    val fabOffset by animateDpAsState(
        targetValue = if (isFabVisible > 0.5f) 0.dp else 100.dp,
        animationSpec = tween(durationMillis = 200),
        label = "fabOffset"
    )
    // 进入/返回搜索结果页时重置底部导航为可见
    LaunchedEffect(showTraktSearch) {
        isFabVisible = 1f
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // 搜索页（tab 0）且非搜索结果页时始终显示底部导航
                if (pagerState.currentPage == 0 && !showTraktSearch) return androidx.compose.ui.geometry.Offset.Zero
                val delta = available.y
                if (delta < -10 && isFabVisible != 0f) {
                    isFabVisible = 0f
                } else if (delta > 10 && isFabVisible != 1f) {
                    isFabVisible = 1f
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    // Haze 毛玻璃状态
    val hazeState = remember { HazeState() }

    // Tab 数据
    val tabs = listOf(
        TabData(Icons.Rounded.Search, R.string.tab_search),
        TabData(Icons.Rounded.Explore, R.string.tab_discover),
        TabData(Icons.Rounded.Person, R.string.tab_me),
        TabData(Icons.Rounded.Settings, R.string.tab_settings)
    )
    val isDarkTheme = isAppDarkTheme()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(nestedScrollConnection)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState, zIndex = 0f)
            ) {
                // 跨页面共享背景（渐变 + 彩色光晕，光晕在宽画布上连续运动）
                PageBackground(
                    currentPage = selectedTab,
                    pageCount = 4,
                    isDark = isDarkTheme,
                    showColorGlow = !isDarkTheme || selectedTab == 0,
                    modifier = Modifier.fillMaxSize()
                )

                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = false,
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                // 只有当前可见 tab 的 MovieCard 参与 sharedElement 转场，避免 HorizontalPager 常驻的其他 tab 同 tmdbId 海报冲突
                CompositionLocalProvider(LocalIsCurrentTab provides (page == pagerState.currentPage)) {
                when (page) {
                    0 -> {
                        if (showTraktSearch) {
                            val mediaType = when (traktSearchType) {
                                SearchSourceType.MOVIE -> MediaType.MOVIE
                                SearchSourceType.SHOW -> MediaType.SHOW
                                SearchSourceType.PERSON -> MediaType.PERSON
                                SearchSourceType.DISK -> MediaType.DISK
                            }
                            key(traktSearchType, traktSearchQuery) {
                                val viewModel: TraktSearchViewModel = hiltViewModel()
                                BackHandler {
                                    showTraktSearch = false
                                }
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    TraktSearchScreen(
                                        initialQuery = traktSearchQuery,
                                        type = mediaType,
                                        onBack = { showTraktSearch = false },
                                        onItemClick = { type, traktId, tmdbId, title, imdbId, traktRating ->
                                            when (type) {
                                                MediaType.MOVIE -> onMovieClick(traktId, tmdbId, title, imdbId, traktRating, false, false)
                                                MediaType.SHOW -> onShowClick(traktId, tmdbId, title, imdbId, traktRating, false, false)
                                                else -> {}
                                            }
                                        },
                                        onPersonClick = { tmdbId, name, profileUrl, avatarColor ->
                                            onPersonClick(tmdbId, name, profileUrl, avatarColor)
                                        },
                                        viewModel = viewModel,
                                        inlineMode = true
                                    )
                                }
                            }
                        } else {
                            SearchScreen(
                                initialKeyword = "",
                                onSearchClick = onSearchClick,
                                onTraktSearch = { type, query ->
                                    traktSearchType = type
                                    traktSearchQuery = query
                                    showTraktSearch = true
                                },
                                onSpiderTest = onSpiderTest,
                                onMovieClick = onMovieClick,
                                searchSourceType = searchSourceType,
                                onSearchSourceTypeChange = { searchSourceType = it },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    1 -> DiscoverScreen(
                        onMovieClick = onMovieClick,
                        onShowClick = onShowClick,
                        onListClick = onListClick,
                        onFilterDiscoverClick = onFilterDiscoverClick,
                        onDoubanLoginClick = onNavigateToDoubanLogin,
                        onTraktLoginClick = onTraktLogin,
                        modifier = Modifier.fillMaxSize()
                    )
                    2 -> {
                        WatchlistScreen(
                            onMovieClick = onMovieClick,
                            onShowClick = onShowClick,
                            onMediaItemClick = onMediaItemClick,
                            onSearchClick = onSearchClick,
                            onTraktSearch = onTraktSearch,
                            onDiscoverClick = { scope.launch { pagerState.scrollToPage(1) } },
                            onNavigateToDoubanLogin = onNavigateToDoubanLogin,
                            onNavigateToLogin = onNavigateToLogin,
                            onTraktLogin = onTraktLogin,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    3 -> SettingsScreen(
                        onLogout = onLogout,
                        isLoggedIn = isLoggedIn,
                        // 传入 Trakt 连接态(独立于综合 isLoggedIn),供 AccountItem 精确判断 Trakt 行
                        isTraktConnected = isTraktConnected,
                        onHelpClick = onHelpClick,
                        onRestartOnboarding = onRestartOnboarding,
                        onDoubanResync = {
                            // 触发同步后切换到 Watchlist tab，让用户通过横幅查看进度
                            // 同时启动前台 Service 进入后台模式（通知栏显示进度）
                            // 同步弹窗在 Watchlist 页点击横幅打开，转后台时消除弹窗
                            com.tracktosearch.service.DoubanSyncService.start(context)
                            scope.launch { pagerState.scrollToPage(2) }
                        },
                        onNavigateToDoubanLogin = onNavigateToDoubanLogin,
                        onNavigateToLogin = onNavigateToLogin,
                        onTraktLogin = onTraktLogin,
                        onStatisticsClick = onStatisticsClick,
                        onMarkRecordsClick = onMarkRecordsClick,
                        onFeedbackClick = onFeedbackClick,
                        onMessagesClick = onMessagesClick,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                    } // CompositionLocalProvider
                }
            }

            // 悬浮底部导航（C 方案：毛玻璃 + 强拟态双向阴影 + 选中凹陷药丸）
            val navBarWidthFraction = 0.92f
            val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val navBarShape = RoundedCornerShape(31.dp)
            val isDark = isAppDarkTheme()
            // 降低表面染色强度，让模糊后的页面主色透过导航栏。
            val navHazeStyle = HazeMaterials.thin(
                MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.08f else 0.03f)
            )
            NeumorphicFrostedSurface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBarHeight + 8.dp)
                    .offset(y = fabOffset)
                    .fillMaxWidth(navBarWidthFraction)
                    .height(62.dp)
                    .hazeSource(state = hazeState, zIndex = 1f),
                    // 让底部导航作为前景层，effect 明确采样 zIndex=0 的页面内容。
                isDark = isDark,
                shape = navBarShape,
                elevation = 8.dp,
                blurRadius = 22.dp,
                shadowOffset = 6.dp,
                backgroundColor = Color.Transparent,
                borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.45f),
                darkShadowAlpha = if (isDark) 0.38f else 0.16f,
                lightShadowAlpha = 0f,
                hazeState = hazeState,
                hazeStyle = navHazeStyle,
                glassStyle = AppGlassStyles.bottomNavigation(
                    tint = MaterialTheme.colorScheme.surface.copy(
                        alpha = if (isDark) 0.10f else 0.06f
                    ),
                    shape = navBarShape
                ),
                hazeBlurRadius = 40.dp,
                // 底部导航自身作为 zIndex=1 的 source，effect 只采样 zIndex=0 的页面内容，
                // 避免导航栏模糊自身导致重复模糊与无谓开销（Haze 最重的叠加场景）
                sourceSelection = HazeSourceSelection.Behind.where { source -> source.zIndex < 1f },
                showHighlight = false
            ) {
                val tabCount = tabs.size
                val rowPadding = 8.dp
                val navBarWidth = screenWidthDp.dp * navBarWidthFraction
                val tabWidth = (navBarWidth - rowPadding * 2) / tabCount
                val indicatorOffsetX by animateDpAsState(
                    targetValue = rowPadding + tabWidth * selectedTab,
                    animationSpec = tween(durationMillis = 200),
                    label = "indicatorOffset"
                )

                // 选中项凹陷药丸（绘制在 Tab 图标下方）
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffsetX)
                        .align(Alignment.CenterStart)
                        .width(tabWidth)
                        .height(48.dp)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    NeumorphicActiveTab(
                        modifier = Modifier.fillMaxSize(),
                        isDark = isDark,
                        shape = RoundedCornerShape(24.dp)
                    )
                }

                // Tab 内容（绘制在药丸上方）
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = rowPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val isSelected = selectedTab == index
                        // "我的"tab(index=2)登录后显示用户头像：
                        // - 豆瓣模式：doubanProfile.avatarUrl（StateFlow 实时）
                        // - trakt 模式：TraktRepository 缓存资料
                        val avatarUrl = if (index == 2 && isLoggedIn) {
                            if (isDoubanMode) {
                                doubanProfile?.avatarUrl
                            } else {
                                traktProfile?.images?.avatar?.full?.takeIf { it.isNotBlank() }
                            }
                        } else null
                        NavTabItem(
                            icon = tab.icon,
                            labelRes = tab.labelRes,
                            selected = isSelected,
                            weight = 1f,
                            hazeState = hazeState,
                            avatarUrl = avatarUrl,
                            badgeCount = if (index == 3) unreadCount else 0,
                            onClick = {
                                if (selectedTab != index) {
                                    view.performHaptic(HapticType.CLICK)
                                    scope.launch { pagerState.scrollToPage(index) }
                                }
                            },
                            onPositioned = { rect ->
                                val current = tabRects.value.toMutableList()
                                while (current.size <= index) current.add(Rect.Zero)
                                current[index] = rect
                                tabRects.value = current
                            }
                        )
                    }
                }
            }

            // 新手引导遮罩：搜索、发现、我的三个 Tab 高亮
            val onboardingTabMap = listOf(0, 1, 2)
            if (showOnboarding && tabRects.value.size == 4) {
                OnboardingOverlay(
                    targetRects = tabRects.value.take(3),
                    titles = listOf(
                        stringResource(R.string.onboarding_step1_title),
                        stringResource(R.string.onboarding_step2_title),
                        stringResource(R.string.onboarding_step3_title)
                    ),
                    descriptions = listOf(
                        stringResource(R.string.onboarding_step1_desc),
                        stringResource(R.string.onboarding_step2_desc),
                        stringResource(R.string.onboarding_step3_desc)
                    ),
                    onComplete = {
                        showOnboarding = false
                        scope.launch { onboardingStorage.setCompleted(true) }
                        // 完成后跳转到搜索页
                        scope.launch {
                            pagerState.scrollToPage(0)
                            selectedTab = 0
                        }
                        // 新手引导完成 1.2s 后弹出位置权限提示（仅当未授权且未取消过时）
                        scope.launch {
                            delay(1200)
                            val hasPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            val dismissed = cloudPermissionStorage.isDismissed.first()
                            if (!hasPermission && !dismissed) {
                                cloudThemeManager.requestPermissionPrompt()
                            }
                        }
                    },
                    onSkip = {
                        showOnboarding = false
                        scope.launch { onboardingStorage.setCompleted(true) }
                    },
                    onStepChanged = { step ->
                        val targetTab = onboardingTabMap.getOrNull(step)
                        if (targetTab != null && targetTab >= 0) {
                            scope.launch { pagerState.scrollToPage(targetTab) }
                            selectedTab = targetTab
                        }
                    }
                )
            }

        }
    }
}

private data class TabData(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val labelRes: Int
)

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavTabItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    selected: Boolean,
    weight: Float,
    onClick: () -> Unit,
    hazeState: HazeState,
    avatarUrl: String? = null,
    badgeCount: Int = 0,
    onPositioned: (Rect) -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val visualEffectMode = LocalVisualEffectMode.current
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val selectedScale by animateFloatAsState(
        targetValue = when {
            visualEffectMode != VisualEffectMode.GLASS -> 1f
            selected -> 1.06f
            isHovered || isFocused -> 1.04f
            else -> 1f
        },
        animationSpec = tween(durationMillis = 180),
        label = "navTabScale"
    )
    // 浅色模式下 primary 偏暗（Red700），选中态提亮饱和度与亮度，提升鲜亮感
    val selectedColor = if (selected) {
        val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        val base = MaterialTheme.colorScheme.primary
        if (isLight) {
            // RGB→HSL 手动转换，提亮饱和度与亮度后转回
            val r = base.red; val g = base.green; val b = base.blue
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            val l = (max + min) / 2f
            val s = if (max == min) 0f else {
                val d = max - min
                if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            }
            val h = when {
                max == min -> 0f
                max == r -> (((g - b) / (max - min)) + (if (g < b) 6f else 0f)) * 60f
                max == g -> (((b - r) / (max - min)) + 2f) * 60f
                else -> (((r - g) / (max - min)) + 4f) * 60f
            }
            // 提亮后的 HSL → RGB
            val newS = (s + 0.14f).coerceIn(0f, 1f)
            val newL = (l + 0.07f).coerceIn(0f, 1f)
            val c = (1f - kotlin.math.abs(2f * newL - 1f)) * newS
            val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
            val m = newL - c / 2f
            val (r1, g1, b1) = when {
                h < 60f -> Triple(c, x, 0f)
                h < 120f -> Triple(x, c, 0f)
                h < 180f -> Triple(0f, c, x)
                h < 240f -> Triple(0f, x, c)
                h < 300f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            Color(r1 + m, g1 + m, b1 + m, base.alpha)
        } else {
            base
        }
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .weight(weight)
            .fillMaxSize()
            .scale(selectedScale)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                onPositioned(bounds)
            }
            .clip(RoundedCornerShape(24.dp))
            .then(
                if (visualEffectMode == VisualEffectMode.GLASS) {
                    Modifier.appVisualEffect(
                        input = HazeInput.Sources(
                            state = hazeState,
                            selection = HazeSourceSelection.Behind.where { source -> source.zIndex < 1f }
                        ),
                        hazeStyle = HazeMaterials.thin(),
                        glassStyle = AppGlassStyles.bottomNavigationItem(
                            tint = MaterialTheme.colorScheme.surface.copy(
                                alpha = if (selected) 0.08f else 0.03f
                            )
                        ),
                        blurSampling = HazeSampling.Adaptive,
                        interactionSource = interactionSource
                    )
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (avatarUrl != null) {
            // 登录后"我的"tab 显示用户头像
            AsyncImage(
                model = ImageRequest.Builder(context).data(avatarUrl).crossfade(true).build(),
                contentDescription = stringResource(labelRes),
                modifier = Modifier
                    .size(24.dp)
                    .offset(y = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            BadgedBox(badge = { if (badgeCount > 0) { Badge { Text(if (badgeCount > 99) "99+" else badgeCount.toString()) } } }) {
                Icon(imageVector = icon, contentDescription = stringResource(labelRes), tint = selectedColor, modifier = Modifier.size(24.dp).offset(y = 3.dp))
            }
        }
        Spacer(modifier = Modifier.height(0.dp))
        Text(
            text = stringResource(labelRes),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = selectedColor,
            maxLines = 1
        )
    }
}


