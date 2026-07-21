package com.tracktosearch.ui.screen.main

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.ui.component.LocalIsCurrentTab
import com.tracktosearch.ui.component.NeumorphicActiveTab
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.OnboardingOverlay
import com.tracktosearch.ui.component.PageBackground
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.screen.discover.DiscoverScreen
import com.tracktosearch.ui.screen.search.CloudThemeProvider
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.search.SearchSourceType
import com.tracktosearch.ui.screen.settings.SettingsScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchViewModel
import com.tracktosearch.ui.screen.watchlist.WatchlistScreen
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.showToast
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** EntryPoint 用于在非 ViewModel 场景获取 TraktRepository（读取用户头像缓存） */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TraktRepositoryEntryPoint {
    fun traktRepository(): TraktRepository
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun MainScreen(
    initialTab: Int = 0,
    isLoggedIn: Boolean,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onSearchClick: (keyword: String) -> Unit,
    onNavigateToLogin: () -> Unit,
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
    onDoubanFailures: () -> Unit = {},
    onNavigateToDoubanLogin: () -> Unit = {},
    onSpiderTest: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
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

    // 用户头像：登录后从 TraktRepository 获取（带永久缓存，仅登出才清除）
    var userAvatarUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            val traktRepository = EntryPointAccessors.fromApplication(
                context.applicationContext, TraktRepositoryEntryPoint::class.java
            ).traktRepository()
            traktRepository.getUserProfile().onSuccess { profile ->
                userAvatarUrl = profile.images.avatar.full.takeIf { it.isNotBlank() }
            }
        } else {
            userAvatarUrl = null
        }
    }

    // 悬浮导航显隐状态(提前声明,供 LaunchedEffect(onboardingCompleted) 使用)
    var isFabVisible by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(onboardingCompleted) {
        if (onboardingCompleted == false) {
            showOnboarding = true
            // 重置到搜索页，确保新手引导从搜索页开始
            // 同时强制显示底部导航(若设置页滚动时把它隐藏了)
            isFabVisible = 1f
            scope.launch {
                pagerState.scrollToPage(0)
                selectedTab = 0
            }
        }
    }

    // Pager 滑动 → 同步 selectedTab
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
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
            // 跨页面共享背景（渐变 + 彩色光晕，光晕在宽画布上连续运动）
            PageBackground(
                currentPage = selectedTab,
                pageCount = 4,
                isDark = isAppDarkTheme(),
                modifier = Modifier.fillMaxSize()
            )

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
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
                        onTraktLoginClick = onNavigateToLogin,
                        modifier = Modifier.fillMaxSize()
                    )
                    2 -> {
                        if (isLoggedIn) {
                            WatchlistScreen(
                                onMovieClick = onMovieClick,
                                onShowClick = onShowClick,
                                onSearchClick = onSearchClick,
                                onTraktSearch = onTraktSearch,
                                onDiscoverClick = { scope.launch { pagerState.scrollToPage(1) } },
                                onNavigateToDoubanLogin = onNavigateToDoubanLogin,
                                onNavigateToLogin = onNavigateToLogin,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            LoginPromptScreen(
                                onNavigateToLogin = onNavigateToLogin,
                                onContinueAsGuest = { scope.launch { pagerState.scrollToPage(0) } },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    3 -> SettingsScreen(
                        onLogout = onLogout,
                        isLoggedIn = isLoggedIn,
                        onHelpClick = onHelpClick,
                        onRestartOnboarding = onRestartOnboarding,
                        onDoubanResync = {
                            // 触发同步后切换到 Watchlist tab，让用户通过横幅查看进度
                            // 同时启动前台 Service 进入后台模式（通知栏显示进度）
                            // 同步弹窗在 Watchlist 页点击横幅打开，转后台时消除弹窗
                            com.tracktosearch.service.DoubanSyncService.start(context)
                            scope.launch { pagerState.scrollToPage(2) }
                        },
                        onDoubanFailures = onDoubanFailures,
                        onNavigateToDoubanLogin = onNavigateToDoubanLogin,
                        onNavigateToLogin = onNavigateToLogin,
                        onStatisticsClick = onStatisticsClick,
                        onMarkRecordsClick = onMarkRecordsClick,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                } // CompositionLocalProvider
            }

            // 悬浮底部导航（C 方案：毛玻璃 + 强拟态双向阴影 + 选中凹陷药丸）
            val navBarWidthFraction = 0.92f
            val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val navBarShape = RoundedCornerShape(31.dp)
            val isDark = isAppDarkTheme()
            // 底部导航毛玻璃：thick 强模糊 + 50% 染色
            val navHazeStyle = HazeStyle(
                backgroundColor = Color.Transparent,
                tints = listOf(HazeTint(color = if (isDark) Color.Black.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.50f)))
            )
            NeumorphicFrostedSurface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBarHeight + 8.dp)
                    .offset(y = fabOffset)
                    .fillMaxWidth(navBarWidthFraction)
                    .height(62.dp),
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
                hazeStyle = navHazeStyle
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
                        // "我的"tab(index=2)登录后显示用户头像
                        val avatarUrl = if (index == 2 && isLoggedIn) userAvatarUrl else null
                        NavTabItem(
                            icon = tab.icon,
                            labelRes = tab.labelRes,
                            selected = isSelected,
                            weight = 1f,
                            avatarUrl = avatarUrl,
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

            // 新手引导遮罩（4个Tab高亮 + 3个纯信息提示）
            // 步骤→Tab页映射：搜索(0)→发现(1)→我的(2)→设置(3)→设置(3,观看统计入口在设置页第一行)→我的(2)→不切换(-1)
            val onboardingTabMap = listOf(0, 1, 2, 3, 3, 2, -1)
            if (showOnboarding && tabRects.value.size == 4) {
                OnboardingOverlay(
                    targetRects = tabRects.value + listOf(Rect.Zero, Rect.Zero, Rect.Zero),
                    titles = listOf(
                        stringResource(R.string.onboarding_step1_title),
                        stringResource(R.string.onboarding_step2_title),
                        stringResource(R.string.onboarding_step3_title),
                        stringResource(R.string.onboarding_step4_title),
                        stringResource(R.string.onboarding_step5_title),
                        stringResource(R.string.onboarding_step6_title),
                        stringResource(R.string.onboarding_step7_title)
                    ),
                    descriptions = listOf(
                        stringResource(R.string.onboarding_step1_desc),
                        stringResource(R.string.onboarding_step2_desc),
                        stringResource(R.string.onboarding_step3_desc),
                        stringResource(R.string.onboarding_step4_desc),
                        stringResource(R.string.onboarding_step5_desc),
                        stringResource(R.string.onboarding_step6_desc),
                        stringResource(R.string.onboarding_step7_desc)
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
    avatarUrl: String? = null,
    onPositioned: (Rect) -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val context = LocalContext.current
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
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                onPositioned(bounds)
            }
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
            Icon(
                imageVector = icon,
                contentDescription = stringResource(labelRes),
                tint = selectedColor,
                modifier = Modifier
                    .size(24.dp)
                    .offset(y = 3.dp)
            )
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


