package com.tracktosearch.ui.screen.main

import com.tracktosearch.ui.util.showToast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.screen.discover.DiscoverScreen
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.settings.SettingsScreen
import com.tracktosearch.ui.screen.watchlist.WatchlistScreen
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun MainScreen(
    initialTab: Int = 0,
    isLoggedIn: Boolean,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onSearchClick: (keyword: String) -> Unit,
    onOpenWebView: (url: String) -> Unit,
    onNavigateToLogin: () -> Unit,
    onStatisticsClick: () -> Unit,
    onLogout: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    val pagerState = rememberPagerState(initialPage = initialTab) { 4 }
    val scope = rememberCoroutineScope()

    // Pager 滑动 → 同步 selectedTab
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
    }

    // 获取屏幕宽度用于导航栏宽度计算
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp

    // 双击返回退出
    var lastBackTime by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
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
    val fabOffset by animateFloatAsState(
        targetValue = if (isFabVisible > 0.5f) 0f else 100f,
        animationSpec = tween(durationMillis = 200),
        label = "fabOffset"
    )

    val density = LocalDensity.current
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                val delta = available.y
                // 下滑（内容向上滚动）隐藏，上滑（内容向下滚动）显示
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
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
            ) { page ->
                when (page) {
                    0 -> SearchScreen(
                        initialKeyword = "",
                        onSearchClick = onSearchClick,
                        onOpenWebView = onOpenWebView,
                        onMovieClick = onMovieClick,
                        modifier = Modifier.fillMaxSize()
                    )
                    1 -> DiscoverScreen(
                        onMovieClick = onMovieClick,
                        onShowClick = onShowClick,
                        onOpenWebView = onOpenWebView,
                        modifier = Modifier.fillMaxSize()
                    )
                    2 -> {
                        if (isLoggedIn) {
                            WatchlistScreen(
                                onMovieClick = onMovieClick,
                                onShowClick = onShowClick,
                                onSearchClick = onSearchClick,
                                onOpenWebView = onOpenWebView,
                                onStatisticsClick = onStatisticsClick,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            LoginPromptScreen(
                                onNavigateToLogin = onNavigateToLogin,
                                onContinueAsGuest = { scope.launch { pagerState.animateScrollToPage(0) } },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    3 -> SettingsScreen(
                        onLogout = onLogout,
                        isLoggedIn = isLoggedIn,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 悬浮底部导航（4 Tab 毛玻璃）
            val navBarWidth = (screenWidthDp * 0.65).dp
            val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val navBarShape = RoundedCornerShape(28.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBarHeight + 8.dp)
                    .offset(y = with(density) { fabOffset.dp })
                    .width(navBarWidth)
                    .height(52.dp)
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
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tab 0: 搜索
                    NavTabItem(
                        icon = Icons.Default.Search,
                        label = stringResource(R.string.tab_search),
                        selected = selectedTab == 0,
                        weight = 1f,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                    )
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // Tab 1: 发现
                    NavTabItem(
                        icon = Icons.Default.Explore,
                        label = stringResource(R.string.tab_discover),
                        selected = selectedTab == 1,
                        weight = 1f,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } }
                    )
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // Tab 2: 我的
                    NavTabItem(
                        icon = Icons.Default.Person,
                        label = stringResource(R.string.tab_me),
                        selected = selectedTab == 2,
                        weight = 1f,
                        onClick = { scope.launch { pagerState.animateScrollToPage(2) } }
                    )
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // Tab 3: 设置
                    NavTabItem(
                        icon = Icons.Default.Settings,
                        label = stringResource(R.string.tab_settings),
                        selected = selectedTab == 3,
                        weight = 1f,
                        onClick = { scope.launch { pagerState.animateScrollToPage(3) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavTabItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    weight: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxSize()
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
