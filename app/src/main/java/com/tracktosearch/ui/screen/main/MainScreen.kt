package com.tracktosearch.ui.screen.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.screen.search.SearchScreen
import com.tracktosearch.ui.screen.watchlist.WatchlistScreen
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R

@Composable
fun MainScreen(
    initialTab: Int = 0,
    isLoggedIn: Boolean,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onSearchClick: (keyword: String) -> Unit,
    onNavigateToLogin: () -> Unit,
    onLogout: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }

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

    Scaffold { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
        ) {
            when (selectedTab) {
                0 -> SearchScreen(
                    initialKeyword = "",
                    onSearchClick = onSearchClick,
                    modifier = Modifier.fillMaxSize()
                )
                1 -> {
                    if (isLoggedIn) {
                        WatchlistScreen(
                            onMovieClick = onMovieClick,
                            onShowClick = onShowClick,
                            onSearchClick = onSearchClick,
                            onLogout = onLogout,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LoginPromptScreen(
                            onNavigateToLogin = onNavigateToLogin,
                            onContinueAsGuest = { selectedTab = 0 },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // 悬浮底部导航（半透明毛玻璃效果）
            val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBarHeight + 8.dp)
                    .offset(y = with(density) { fabOffset.dp })
                    .shadow(elevation = 12.dp, shape = RoundedCornerShape(28.dp))
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.tab_search),
                            tint = if (selectedTab == 0) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = stringResource(R.string.tab_me),
                            tint = if (selectedTab == 1) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
