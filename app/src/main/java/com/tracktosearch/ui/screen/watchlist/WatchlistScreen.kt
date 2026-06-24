package com.tracktosearch.ui.screen.watchlist

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.focus.focusRequester
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
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.MovieCardSkeleton
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
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
    val tabPagerState = rememberPagerState(initialPage = 0) { 2 }
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

    // Pager 滑动 → 同步 selectedTab
    LaunchedEffect(tabPagerState.currentPage) {
        selectedTab = tabPagerState.currentPage
    }

    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val hazeState = remember { HazeState() }
    val movieGridState = rememberLazyGridState()
    val showGridState = rememberLazyGridState()
    val scrollToTopProvider = LocalScrollToTopProvider.current
    val gridCoroutineScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        scrollToTopProvider.register {
            val currentGridState = if (tabPagerState.currentPage == 0) movieGridState else showGridState
            gridCoroutineScope.launch {
                currentGridState.animateScrollToItem(0)
            }
        }
        onDispose {
            scrollToTopProvider.unregister()
        }
    }
    val currentTheme by viewModel.themeMode.collectAsState()

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

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(top = 65.dp + statusBarHeight)
            ) {

                // TMDB 不可用提示
            if (uiState.tmdbUnavailable) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
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
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { view.performHaptic(HapticType.CLICK); tabScope.launch { tabPagerState.animateScrollToPage(0) } },
                        text = { Text("${stringResource(R.string.watchlist_tab_movies)}($movieCount)") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { view.performHaptic(HapticType.CLICK); tabScope.launch { tabPagerState.animateScrollToPage(1) } },
                        text = { Text("${stringResource(R.string.watchlist_tab_shows)}($showCount)") }
                    )
                }

                // 内容区域 - HorizontalPager 支持左右滑动
                HorizontalPager(
                    state = tabPagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = hazeState)
                ) { page ->
                    when (page) {
                        0 -> if (selectedMode == 1) {
                            MovieTabContent(
                                isLoading = uiState.isLoadingHistoryMovies,
                                isLoaded = uiState.historyMoviesLoaded,
                                items = filteredHistoryMovies,
                                totalItems = uiState.historyMovies.size,
                                searchQuery = searchQuery,
                                error = uiState.historyMoviesError,
                                onItemClick = { onMovieClick(it.traktId, it.tmdbId, it.title, it.imdbId, it.traktRating, false, true) },
                                onRetry = { viewModel.loadHistoryMovies(forceReload = true) },
                                onOpenTrakt = null,
                                onTraktSearch = null,
                                emptyListText = stringResource(R.string.history_empty_movies),
                                hazeState = hazeState,
                                gridState = movieGridState
                            )
                        } else {
                            MovieTabContent(
                                isLoading = uiState.isLoadingMovies,
                                isLoaded = uiState.moviesLoaded,
                                items = filteredMovies,
                                totalItems = uiState.movies.size,
                                searchQuery = searchQuery,
                                error = uiState.moviesError,
                                onItemClick = { onMovieClick(it.traktId, it.tmdbId, it.title, it.imdbId, it.traktRating, true, false) },
                                onRetry = { viewModel.loadMovies(forceReload = true) },
                                onOpenTrakt = openTraktExternal,
                                onTraktSearch = { query -> onTraktSearch("movie", query) },
                                emptyListText = stringResource(R.string.watchlist_empty_movies),
                                hazeState = hazeState,
                                gridState = movieGridState
                            )
                        }
                        1 -> if (selectedMode == 1) {
                            ShowTabContent(
                                isLoading = uiState.isLoadingHistoryShows,
                                isLoaded = uiState.historyShowsLoaded,
                                items = filteredHistoryShows,
                                totalItems = uiState.historyShows.size,
                                searchQuery = searchQuery,
                                error = uiState.historyShowsError,
                                onItemClick = { onShowClick(it.traktId, it.tmdbId, it.title, it.imdbId, it.traktRating, false, true) },
                                onRetry = { viewModel.loadHistoryShows(forceReload = true) },
                                onOpenTrakt = null,
                                onTraktSearch = null,
                                emptyListText = stringResource(R.string.history_empty_shows),
                                hazeState = hazeState,
                                gridState = showGridState
                            )
                        } else {
                            ShowTabContent(
                                isLoading = uiState.isLoadingShows,
                                isLoaded = uiState.showsLoaded,
                                items = filteredShows,
                                totalItems = uiState.shows.size,
                                searchQuery = searchQuery,
                                error = uiState.showsError,
                                onItemClick = { onShowClick(it.traktId, it.tmdbId, it.title, it.imdbId, it.traktRating, true, false) },
                                onRetry = { viewModel.loadShows(forceReload = true) },
                                onOpenTrakt = openTraktExternal,
                                onTraktSearch = { query -> onTraktSearch("show", query) },
                                emptyListText = stringResource(R.string.watchlist_empty_shows),
                                hazeState = hazeState,
                                gridState = showGridState
                            )
                        }
                    }
                }
        }
            // Haze模糊渐变TopAppBar（含状态栏）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.thin()
                    )
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.50f))
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                            } else stringResource(R.string.search_placeholder)
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
                                    contentDescription = "清除",
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
            }
        }
    }
}

@Composable
private fun MovieTabContent(
    isLoading: Boolean,
    isLoaded: Boolean,
    items: List<MovieUiItem>,
    totalItems: Int,
    searchQuery: String,
    error: String?,
    onItemClick: (MovieUiItem) -> Unit,
    onRetry: () -> Unit,
    onOpenTrakt: (() -> Unit)?,
    onTraktSearch: ((String) -> Unit)?,
    emptyListText: String,
    hazeState: HazeState,
    gridState: LazyGridState
) {
    when {
        error != null && items.isEmpty() -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                EmptyView(message = stringResource(R.string.watchlist_load_failed, error))
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.watchlist_retry))
                }
            }
        }
        isLoading && !isLoaded -> {
            SkeletonGrid()
        }
        items.isEmpty() && isLoaded -> {
            WatchlistEmptyState(
                isSearchResult = searchQuery.isNotBlank() && totalItems > 0,
                searchQuery = searchQuery,
                emptyText = if (searchQuery.isNotBlank() && totalItems > 0)
                    stringResource(R.string.watchlist_search_no_movies)
                else emptyListText,
                onOpenTrakt = if (totalItems == 0) onOpenTrakt else null,
                onTraktSearch = if (searchQuery.isNotBlank() && totalItems > 0) onTraktSearch else null
            )
        }
        else -> {
            MovieGrid(items = items, onItemClick = onItemClick, hazeState = hazeState, gridState = gridState)
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun ShowTabContent(
    isLoading: Boolean,
    isLoaded: Boolean,
    items: List<ShowUiItem>,
    totalItems: Int,
    searchQuery: String,
    error: String?,
    onItemClick: (ShowUiItem) -> Unit,
    onRetry: () -> Unit,
    onOpenTrakt: (() -> Unit)?,
    onTraktSearch: ((String) -> Unit)?,
    emptyListText: String,
    hazeState: HazeState,
    gridState: LazyGridState
) {
    when {
        error != null && items.isEmpty() -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                EmptyView(message = stringResource(R.string.watchlist_load_failed, error))
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.watchlist_retry))
                }
            }
        }
        isLoading && !isLoaded -> {
            SkeletonGrid()
        }
        items.isEmpty() && isLoaded -> {
            WatchlistEmptyState(
                isSearchResult = searchQuery.isNotBlank() && totalItems > 0,
                searchQuery = searchQuery,
                emptyText = if (searchQuery.isNotBlank() && totalItems > 0)
                    stringResource(R.string.watchlist_search_no_shows)
                else emptyListText,
                onOpenTrakt = if (totalItems == 0) onOpenTrakt else null,
                onTraktSearch = if (searchQuery.isNotBlank() && totalItems > 0) onTraktSearch else null
            )
        }
        else -> {
            ShowGrid(items = items, onItemClick = onItemClick, hazeState = hazeState, gridState = gridState)
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun MovieGrid(
    items: List<MovieUiItem>,
    onItemClick: (MovieUiItem) -> Unit,
    hazeState: HazeState,
    gridState: LazyGridState
) {
    val context = LocalContext.current
    val view = LocalView.current
    Box(modifier = Modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items.size, key = { items[it].traktId }, contentType = { "movie" }) { index ->
                val item = items[index]
                MovieCard(
                    title = item.displayTitle,
                    year = item.year,
                    genres = item.genres,
                    posterUrl = item.posterUrl,
                    tmdbId = item.tmdbId,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(300),
                        placementSpec = tween(300)
                    )
                )
            }
        }
        ScrollToTopButton(
            gridState = gridState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 96.dp, end = 16.dp),
            hazeState = hazeState
        )
    }
}

@Composable
private fun ShowGrid(
    items: List<ShowUiItem>,
    onItemClick: (ShowUiItem) -> Unit,
    hazeState: HazeState,
    gridState: LazyGridState
) {
    val context = LocalContext.current
    val view = LocalView.current
    Box(modifier = Modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items.size, key = { items[it].traktId }, contentType = { "show" }) { index ->
                val item = items[index]
                MovieCard(
                    title = item.displayTitle,
                    year = item.year,
                    genres = item.genres,
                    posterUrl = item.posterUrl,
                    tmdbId = item.tmdbId,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(300),
                        placementSpec = tween(300)
                    )
                )
            }
        }
        ScrollToTopButton(
            gridState = gridState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp),
            hazeState = hazeState
        )
    }
}

/** 骨架屏网格 - 想看列表加载态 */
@Composable
private fun SkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(9) {
            MovieCardSkeleton()
        }
    }
}

/** 想看列表空状态（区分搜索无结果 vs 列表为空，Trakt 超链接） */
@Composable
private fun WatchlistEmptyState(
    isSearchResult: Boolean,
    searchQuery: String = "",
    emptyText: String,
    onOpenTrakt: (() -> Unit)?,
    onTraktSearch: ((String) -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        // 搜索无结果时，显示"在Trakt上搜索"按钮
        if (isSearchResult && onTraktSearch != null) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { onTraktSearch(searchQuery) },
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.watchlist_search_on_trakt))
            }
        }
        if (!isSearchResult && onOpenTrakt != null) {
            Spacer(modifier = Modifier.height(4.dp))
            val annotatedString = buildAnnotatedString {
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append("去 ")
                }
                withStyle(style = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )) {
                    append("Trakt 添加")
                }
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(" 一些想看的影视吧")
                }
            }
            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable { onOpenTrakt() }
            )
        }
    }
}

/** 主题选择对话框 */
@Composable
private fun ThemeSelectionDialog(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column {
                ThemeOptionRow(
                    label = stringResource(R.string.theme_system),
                    selected = currentTheme == ThemeStorage.MODE_SYSTEM,
                    onClick = { onThemeSelected(ThemeStorage.MODE_SYSTEM) }
                )
                ThemeOptionRow(
                    label = stringResource(R.string.theme_dark),
                    selected = currentTheme == ThemeStorage.MODE_DARK,
                    onClick = { onThemeSelected(ThemeStorage.MODE_DARK) }
                )
                ThemeOptionRow(
                    label = stringResource(R.string.theme_light),
                    selected = currentTheme == ThemeStorage.MODE_LIGHT,
                    onClick = { onThemeSelected(ThemeStorage.MODE_LIGHT) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

@Composable
private fun ThemeOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}
