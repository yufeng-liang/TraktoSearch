package com.tracktosearch.ui.screen.watchlist

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.rememberShimmerBrush
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 0=想看, 1=已看历史
    var selectedMode by rememberSaveable { mutableIntStateOf(0) }
    val tabScope = rememberCoroutineScope()

    // 保存每个 (mode, tab) 组合的滚动位置
    val savedScrollPositions = remember { mutableMapOf<String, Pair<Int, Int>>() }

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
    var isRemoving by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateMapOf<Int, Boolean>() }
    // 退出多选模式时清空选中
    LaunchedEffect(isMultiSelectMode) {
        if (!isMultiSelectMode) selectedItems.clear()
    }
    BackHandler(enabled = isMultiSelectMode) {
        isMultiSelectMode = false
        isRemoving = false
    }

    // 监听 tab 切换，保存/恢复滚动位置 + 退出多选
    var prevTabKey by remember { mutableStateOf("${selectedMode}_${selectedTab}") }
    LaunchedEffect(selectedMode, selectedTab) {
        val currentKey = "${selectedMode}_${selectedTab}"
        // 保存旧 tab 的位置
        savedScrollPositions[prevTabKey] = Pair(
            currentGridState.firstVisibleItemIndex,
            currentGridState.firstVisibleItemScrollOffset
        )
        // 恢复新 tab 的位置
        savedScrollPositions[currentKey]?.let { (index, offset) ->
            currentGridState.scrollToItem(index, offset)
        }
        prevTabKey = currentKey
        // 退出多选模式
        isMultiSelectMode = false
        isRemoving = false
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

    // 监听列表变化，移除完成后关闭多选模式
    LaunchedEffect(currentItems.size, isRemoving) {
        if (isRemoving) {
            val remainingIds = currentItems.map { it.traktId }.toSet()
            val selectedIds = selectedItems.keys.toSet()
            if (selectedIds.none { it in remainingIds }) {
                isMultiSelectMode = false
                isRemoving = false
            }
        }
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
                    top = 173.dp + statusBarHeight,
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
                items(items.size, key = { items[it].traktId }, contentType = { "media_card" }) { index ->
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
                            }
                        )
                        // 多选模式下显示选中遮罩 + 打勾图标
                        if (isMultiSelectMode) {
                            // 选中时加半透明主色蒙版
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                )
                            }
                            // 选中状态：实心圆+打勾图标；未选中：空心圆环
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(28.dp)
                                    .then(
                                        if (isSelected) {
                                            Modifier
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                        } else {
                                            Modifier
                                                .clip(CircleShape)
                                                .background(Color.Transparent)
                                                .border(
                                                    BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)),
                                                    CircleShape
                                                )
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ScrollToTopButton(
                gridState = currentGridState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 100.dp, end = 16.dp),
                hazeState = hazeState
            )

            // Haze 模糊覆盖层 - 搜索框 + SegmentedButtonRow + PrimaryTabRow 或 多选操作栏
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.thin()
                    )
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.50f))
            ) {
                // 搜索框 + Tab 栏（非多选模式时显示）
                AnimatedVisibility(
                    visible = !isMultiSelectMode,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
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

                        // TMDB 不可用提示 — 在 tab 栏下方
                        if (uiState.tmdbUnavailable) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.watchlist_poster_error),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // 多选操作栏（多选模式时显示）
                AnimatedVisibility(
                    visible = isMultiSelectMode,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        // 状态栏 Spacer
                        Spacer(modifier = Modifier.statusBarsPadding())
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = false, onClick = {}) // 防穿透
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(onClick = { isMultiSelectMode = false }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.common_cancel),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                            text = stringResource(R.string.watchlist_selected_count, selectedItems.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row {
                            Button(
                                onClick = {
                                    isRemoving = true
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
                                },
                                enabled = !isRemoving,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                if (isRemoving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onError
                                    )
                                } else {
                                    Text(
                                        if (selectedMode == 0) stringResource(R.string.watchlist_remove_watchlist)
                                        else stringResource(R.string.watchlist_remove_history)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(onClick = { isMultiSelectMode = false; isRemoving = false }) {
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
                        .padding(top = 165.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                )
            }
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
