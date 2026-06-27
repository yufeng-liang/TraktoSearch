package com.tracktosearch.ui.screen.watchlist

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource

import com.tracktosearch.R
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.rememberShimmerBrush
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class, ExperimentalFoundationApi::class)
@Composable
fun WatchlistScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onSearchClick: (keyword: String) -> Unit,
    onOpenWebView: (url: String) -> Unit,
    onStatisticsClick: () -> Unit,
    onTraktSearch: (type: String, query: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WatchlistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    // 用外置浏览器打开 Trakt，共享外置浏览器登录态（内置 WebView 有独立 CookieJar 不共享）
    val openTraktExternal: () -> Unit = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://trakt.tv/watchlist")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    LaunchedEffect(Unit) {
        viewModel.loadMovies()
        viewModel.loadShows()
    }
    // 从外置浏览器（如"去 Trakt 添加想看的"）返回时，自动刷新列表
    var hasResumedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(hasResumedOnce) {
        if (hasResumedOnce) {
            viewModel.refreshIfLoaded(silent = true)
        } else {
            hasResumedOnce = true
        }
        onPauseOrDispose { /* no-op */ }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 0=想看, 1=已看历史
    var selectedMode by rememberSaveable { mutableIntStateOf(0) }
    val tabScope = rememberCoroutineScope()

    // 切换模式时触发加载
    LaunchedEffect(selectedMode) {
        when (selectedMode) {
            1 -> {
                viewModel.loadHistoryMovies()
                viewModel.loadHistoryShows()
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val hazeState = remember { HazeState() }
    val singleModeGridState = rememberLazyGridState()
    val historyModeGridState = rememberLazyGridState()
    val scrollToTopProvider = LocalScrollToTopProvider.current
    val gridCoroutineScope = rememberCoroutineScope()

    // 根据 selectedMode 选择对应的 gridState
    val currentGridState = if (selectedMode == 0) singleModeGridState else historyModeGridState

    DisposableEffect(Unit) {
        scrollToTopProvider.register {
            gridCoroutineScope.launch {
                currentGridState.animateScrollToItem(0)
            }
        }
        onDispose {
            scrollToTopProvider.unregister()
        }
    }

    // 长按多选状态
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateMapOf<Int, Boolean>() }
    // 退出多选模式时清空选中
    LaunchedEffect(isMultiSelectMode) {
        if (!isMultiSelectMode) selectedItems.clear()
    }
    BackHandler(enabled = isMultiSelectMode) {
        isMultiSelectMode = false
    }

    // 根据搜索关键词过滤当前 Tab 的列表
    val filteredMovies = remember(uiState.movies, searchQuery) {
        if (searchQuery.isBlank()) uiState.movies
        else uiState.movies.filter {
            it.displayTitle.contains(searchQuery, ignoreCase = true) ||
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }
    val filteredShows = remember(uiState.shows, searchQuery) {
        if (searchQuery.isBlank()) uiState.shows
        else uiState.shows.filter {
            it.displayTitle.contains(searchQuery, ignoreCase = true) ||
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }
    val filteredHistoryMovies = remember(uiState.historyMovies, searchQuery) {
        if (searchQuery.isBlank()) uiState.historyMovies
        else uiState.historyMovies.filter {
            it.displayTitle.contains(searchQuery, ignoreCase = true) ||
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }
    val filteredHistoryShows = remember(uiState.historyShows, searchQuery) {
        if (searchQuery.isBlank()) uiState.historyShows
        else uiState.historyShows.filter {
            it.displayTitle.contains(searchQuery, ignoreCase = true) ||
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }

    // 获取当前 tab 对应的 items（用于多选操作）
    val currentItems = when {
        selectedMode == 0 && selectedTab == 0 -> filteredMovies
        selectedMode == 0 && selectedTab == 1 -> filteredShows
        selectedMode == 1 && selectedTab == 0 -> filteredHistoryMovies
        else -> filteredHistoryShows
    }

    // 切换 tab 时退出多选
    LaunchedEffect(selectedTab, selectedMode) {
        isMultiSelectMode = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

            // 内容区域 - LazyVerticalGrid 直接作为 hazeSource
            LazyVerticalGrid(
                state = currentGridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 180.dp + statusBarHeight,
                    bottom = if (isMultiSelectMode) 80.dp else 80.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
            ) {
                // 根据 selectedMode 和 selectedTab 渲染对应列表
                val items = currentItems
                items(items.size, key = { items[it].traktId }) { index ->
                    val item = items[index]
                    val isSelected = selectedItems[item.traktId] == true
                    Box {
                        MovieCard(
                            title = item.displayTitle,
                            year = item.year,
                            genres = item.genres,
                            posterUrl = item.posterUrl,
                            tmdbId = item.tmdbId,
                            onClick = {
                                if (isMultiSelectMode) {
                                    // 多选模式：切换选中状态
                                    if (isSelected) selectedItems.remove(item.traktId)
                                    else selectedItems[item.traktId] = true
                                    // 如果选中项为空，退出多选
                                    if (selectedItems.isEmpty()) isMultiSelectMode = false
                                } else {
                                    val inWatchlist = selectedMode == 0
                                    val isWatched = selectedMode == 1
                                    if (selectedTab == 0) {
                                        onMovieClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating, inWatchlist, isWatched)
                                    } else {
                                        onShowClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating, inWatchlist, isWatched)
                                    }
                                }
                            },
                            onLongClick = {
                                if (!isMultiSelectMode) {
                                    isMultiSelectMode = true
                                }
                                selectedItems[item.traktId] = true
                            },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(300),
                                placementSpec = tween(300)
                            )
                        )
                        // 多选模式下显示选中图标
                        if (isMultiSelectMode) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(24.dp)
                            )
                        }
                    }
                }
            }

            ScrollToTopButton(
                gridState = currentGridState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = if (isMultiSelectMode) 72.dp else 16.dp, end = 16.dp),
                hazeState = hazeState
            )

            // Haze 模糊覆盖层 - 搜索框 + SegmentedButtonRow + PrimaryTabRow
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.thin()
                    )
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.50f))
            ) {
                // 状态栏 Spacer - 点击回顶
                Spacer(
                    modifier = Modifier
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .clickable {
                            gridCoroutineScope.launch {
                                currentGridState.animateScrollToItem(0)
                            }
                        }
                )
                // 搜索栏 Row - 防穿透
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false, onClick = {}) // 防穿透
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                if (searchQuery.isBlank()) {
                                    when (selectedMode) {
                                        0 -> stringResource(R.string.watchlist_search_watchlist)
                                        else -> stringResource(R.string.watchlist_search_history)
                                    }
                                } else stringResource(R.string.search_placeholder_watchlist)
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
                                // 搜索无结果时，自动跳转 Trakt 搜索（仅想看列表模式）
                                if (searchQuery.isNotBlank() && selectedMode == 0) {
                                    val noResults = if (selectedTab == 0) filteredMovies.isEmpty() else filteredShows.isEmpty()
                                    if (noResults) {
                                        onTraktSearch(if (selectedTab == 0) "movie" else "show", searchQuery)
                                    }
                                }
                            }
                        ),
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.content_desc_clear),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.watchlist_search))
                            }
                        }
                    )
                    IconButton(onClick = onStatisticsClick) {
                        Icon(Icons.Default.BarChart, contentDescription = stringResource(R.string.statistics_title))
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.watchlist_refresh))
                    }
                }

                // 想看 / 已看 模式切换
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 0.dp)
                ) {
                    SegmentedButton(
                        selected = selectedMode == 0,
                        onClick = { view.performHaptic(HapticType.CLICK); selectedMode = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.watchlist_mode_watchlist))
                    }
                    SegmentedButton(
                        selected = selectedMode == 1,
                        onClick = { view.performHaptic(HapticType.CLICK); selectedMode = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(stringResource(R.string.watchlist_tab_history))
                    }
                }

                // 分类 Tab
                val movieCount = if (selectedMode == 1) filteredHistoryMovies.size else filteredMovies.size
                val showCount = if (selectedMode == 1) filteredHistoryShows.size else filteredShows.size
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { view.performHaptic(HapticType.CLICK); selectedTab = 0 },
                        text = { Text("${stringResource(R.string.watchlist_tab_movies)}($movieCount)") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { view.performHaptic(HapticType.CLICK); selectedTab = 1 },
                        text = { Text("${stringResource(R.string.watchlist_tab_shows)}($showCount)") }
                    )
                }
            }

            // TMDB 不可用提示
            if (uiState.tmdbUnavailable) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.watchlist_poster_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // 多选模式底部操作栏
            if (isMultiSelectMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.watchlist_selected_count, selectedItems.size),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Row {
                            Button(
                                onClick = {
                                    val ids = selectedItems.keys.toList()
                                    if (selectedMode == 0) {
                                        viewModel.batchRemoveFromWatchlist(
                                            ids,
                                            if (selectedTab == 0) MediaType.MOVIE else MediaType.SHOW
                                        )
                                    } else {
                                        viewModel.batchRemoveFromHistory(
                                            ids,
                                            if (selectedTab == 0) MediaType.MOVIE else MediaType.SHOW
                                        )
                                    }
                                    isMultiSelectMode = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(
                                    if (selectedMode == 0) stringResource(R.string.watchlist_remove_watchlist)
                                    else stringResource(R.string.watchlist_remove_history)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(onClick = { isMultiSelectMode = false }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    }
                }
            }

            // 骨架屏：首次加载时显示
            val isLoading = if (selectedMode == 0) {
                if (selectedTab == 0) uiState.isLoadingMovies && !uiState.moviesLoaded
                else uiState.isLoadingShows && !uiState.showsLoaded
            } else {
                if (selectedTab == 0) uiState.isLoadingHistoryMovies && !uiState.historyMoviesLoaded
                else uiState.isLoadingHistoryShows && !uiState.historyShowsLoaded
            }
            if (isLoading) {
                WatchlistSkeletonGrid(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 180.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                )
            }
        }
    }
}

/** 骨架屏网格 - 3列，海报占位 + 标题条 + 类型条，呼吸动画 */
@Composable
private fun WatchlistSkeletonGrid(modifier: Modifier = Modifier) {
    val brush = rememberShimmerBrush()
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        items(9) {
            Column {
                // 海报占位：2:3 圆角矩形
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(brush)
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 标题条
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 类型条
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
            }
        }
    }
}
