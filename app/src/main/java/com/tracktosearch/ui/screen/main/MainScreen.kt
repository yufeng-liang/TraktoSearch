package com.tracktosearch.ui.screen.main

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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
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
import com.tracktosearch.R
import com.tracktosearch.data.local.CloudPermissionStorage
import com.tracktosearch.data.local.OnboardingStorage
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.CloudThemeManager
import com.tracktosearch.ui.component.OnboardingOverlay
import com.tracktosearch.ui.screen.discover.DiscoverScreen
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.search.SearchSourceType
import com.tracktosearch.ui.screen.search.CloudThemeProvider
import com.tracktosearch.ui.screen.settings.SettingsScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchScreen
import com.tracktosearch.ui.screen.traktsearch.TraktSearchViewModel
import com.tracktosearch.ui.screen.watchlist.WatchlistScreen
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.showToast
import dagger.hilt.android.EntryPointAccessors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    onTraktSearch: (type: String, query: String) -> Unit,
    onPersonClick: (tmdbId: Int, name: String, profileUrl: String) -> Unit = { _, _, _ -> },
    onListClick: (listId: Int, listName: String) -> Unit = { _, _ -> },
    onLogout: () -> Unit,
    onHelpClick: () -> Unit,
    onRestartOnboarding: () -> Unit
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

    LaunchedEffect(onboardingCompleted) {
        if (onboardingCompleted == false) {
            showOnboarding = true
            // 重置到搜索页，确保新手引导从搜索页开始
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

    // 悬浮导航显隐状态
    var isFabVisible by remember { mutableFloatStateOf(1f) }
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
                val delta = available.y
                if (delta < -10) {
                    isFabVisible = 0f
                } else if (delta > 10) {
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
        TabData(Icons.Default.Search, R.string.tab_search),
        TabData(Icons.Default.Explore, R.string.tab_discover),
        TabData(Icons.Default.Person, R.string.tab_me),
        TabData(Icons.Default.Settings, R.string.tab_settings)
    )

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(nestedScrollConnection)
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                beyondViewportPageCount = 3,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
            ) { page ->
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
                                        onPersonClick = { tmdbId, name, profileUrl ->
                                            onPersonClick(tmdbId, name, profileUrl)
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
                                onMovieClick = { traktId, tmdbId, title, imdbId, traktRating -> onMovieClick(traktId, tmdbId, title, imdbId, traktRating, false, false) },
                                searchSourceType = searchSourceType,
                                onSearchSourceTypeChange = { searchSourceType = it },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    1 -> DiscoverScreen(
                        onMovieClick = { traktId, tmdbId, title, imdbId, traktRating -> onMovieClick(traktId, tmdbId, title, imdbId, traktRating, false, false) },
                        onShowClick = { traktId, tmdbId, title, imdbId, traktRating -> onShowClick(traktId, tmdbId, title, imdbId, traktRating, false, false) },
                        onListClick = onListClick,
                        modifier = Modifier.fillMaxSize()
                    )
                    2 -> {
                        if (isLoggedIn) {
                            WatchlistScreen(
                                onMovieClick = onMovieClick,
                                onShowClick = onShowClick,
                                onSearchClick = onSearchClick,
                                onStatisticsClick = onStatisticsClick,
                                onTraktSearch = onTraktSearch,
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
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 悬浮底部导航（4 Tab 毛玻璃 + 选中背景高亮动效）
            val navBarWidth = (screenWidthDp * 0.80).dp
            val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val navBarShape = RoundedCornerShape(28.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBarHeight + 8.dp)
                    .offset(y = fabOffset)
                    .width(navBarWidth)
                    .height(64.dp)
                    .shadow(elevation = 16.dp, shape = navBarShape)
                    .hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.thin()
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = navBarShape
                    )
            ) {
                // 滑动高亮指示器（药丸形背景，先绘制在底层）
                val tabCount = tabs.size
                val rowPadding = 8.dp
                val tabWidth = (navBarWidth - rowPadding * 2) / tabCount
                val indicatorOffsetX by animateDpAsState(
                    targetValue = rowPadding + tabWidth * selectedTab,
                    animationSpec = tween(durationMillis = 200),
                    label = "indicatorOffset"
                )
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffsetX)
                        .align(Alignment.CenterStart)
                        .padding(vertical = 8.dp)
                        .width(tabWidth)
                        .height(48.dp)
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(16.dp)
                        )
                )

                // Tab 内容（绘制在指示器上方）
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val isSelected = selectedTab == index
                        NavTabItem(
                            icon = tab.icon,
                            labelRes = tab.labelRes,
                            selected = isSelected,
                            weight = 1f,
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
            // 步骤→Tab页映射：搜索(0)→发现(1)→我的(2)→设置(3)→我的(2)→我的(2)→不切换(-1)
            val onboardingTabMap = listOf(0, 1, 2, 3, 2, 2, -1)
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
    onPositioned: (Rect) -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val density = LocalDensity.current
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
        Icon(
            imageVector = icon,
            contentDescription = stringResource(labelRes),
            tint = if (selected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .offset(y = 3.dp)
        )
        Spacer(modifier = Modifier.height(0.dp))
        Text(
            text = stringResource(labelRes),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
