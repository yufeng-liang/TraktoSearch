package com.tracktosearch.ui.screen.main

import com.tracktosearch.ui.util.showToast
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onTraktSearch: (type: String, query: String) -> Unit,
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
    val fabOffset by animateDpAsState(
        targetValue = if (isFabVisible > 0.5f) 0.dp else 100.dp,
        animationSpec = tween(durationMillis = 200),
        label = "fabOffset"
    )

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
                                    scope.launch { pagerState.scrollToPage(index) }
                                }
                            }
                        )
                    }
                }
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
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .weight(weight)
            .fillMaxSize()
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
            modifier = Modifier.size(22.dp)
        )
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
