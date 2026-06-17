package com.tracktosearch.ui.screen.watchlist

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Logout
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
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tracktosearch.R
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.LoadingView
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.ScrollToTopButton
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onSearchClick: (keyword: String) -> Unit,
    onOpenWebView: (url: String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WatchlistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
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
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasResumedOnce by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && hasResumedOnce) {
                viewModel.refresh()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                hasResumedOnce = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabPagerState = rememberPagerState(initialPage = 0) { 2 }
    val tabScope = rememberCoroutineScope()

    // Pager 滑动 → 同步 selectedTab
    LaunchedEffect(tabPagerState.currentPage) {
        selectedTab = tabPagerState.currentPage
    }

    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                if (searchQuery.isBlank()) stringResource(R.string.watchlist_title)
                                else stringResource(R.string.search_placeholder)
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
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
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.watchlist_refresh))
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, contentDescription = stringResource(R.string.logout))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
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

            // 分类 Tab
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { tabScope.launch { tabPagerState.animateScrollToPage(0) } },
                    text = { Text("${stringResource(R.string.watchlist_tab_movies)}(${filteredMovies.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { tabScope.launch { tabPagerState.animateScrollToPage(1) } },
                    text = { Text("${stringResource(R.string.watchlist_tab_shows)}(${filteredShows.size})") }
                )
            }

            // 内容区域 - HorizontalPager 支持左右滑动
            HorizontalPager(
                state = tabPagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> MovieTabContent(
                        isLoading = uiState.isLoadingMovies,
                        isLoaded = uiState.moviesLoaded,
                        items = filteredMovies,
                        totalItems = uiState.movies.size,
                        searchQuery = searchQuery,
                        error = uiState.moviesError,
                        onItemClick = { onMovieClick(it.traktId, it.tmdbId, it.title, it.imdbId, it.traktRating) },
                        onRetry = { viewModel.loadMovies(forceReload = true) },
                        onOpenTrakt = openTraktExternal
                    )
                    1 -> ShowTabContent(
                        isLoading = uiState.isLoadingShows,
                        isLoaded = uiState.showsLoaded,
                        items = filteredShows,
                        totalItems = uiState.shows.size,
                        searchQuery = searchQuery,
                        error = uiState.showsError,
                        onItemClick = { onShowClick(it.traktId, it.tmdbId, it.title, it.imdbId, it.traktRating) },
                        onRetry = { viewModel.loadShows(forceReload = true) },
                        onOpenTrakt = openTraktExternal
                    )
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
    onOpenTrakt: () -> Unit
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
            LoadingView(message = stringResource(R.string.watchlist_loading))
        }
        items.isEmpty() && isLoaded -> {
            WatchlistEmptyState(
                isSearchResult = searchQuery.isNotBlank() && totalItems > 0,
                emptyText = if (searchQuery.isNotBlank() && totalItems > 0)
                    stringResource(R.string.watchlist_search_no_movies)
                else stringResource(R.string.watchlist_empty_movies),
                onOpenTrakt = if (totalItems == 0) onOpenTrakt else null
            )
        }
        else -> {
            MovieGrid(items = items, onItemClick = onItemClick)
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
    onOpenTrakt: () -> Unit
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
            LoadingView(message = stringResource(R.string.watchlist_loading))
        }
        items.isEmpty() && isLoaded -> {
            WatchlistEmptyState(
                isSearchResult = searchQuery.isNotBlank() && totalItems > 0,
                emptyText = if (searchQuery.isNotBlank() && totalItems > 0)
                    stringResource(R.string.watchlist_search_no_shows)
                else stringResource(R.string.watchlist_empty_shows),
                onOpenTrakt = if (totalItems == 0) onOpenTrakt else null
            )
        }
        else -> {
            ShowGrid(items = items, onItemClick = onItemClick)
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
    onItemClick: (MovieUiItem) -> Unit
) {
    val gridState = rememberLazyGridState()
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
                    onClick = { onItemClick(item) }
                )
            }
        }
        ScrollToTopButton(
            gridState = gridState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp)
        )
    }
}

@Composable
private fun ShowGrid(
    items: List<ShowUiItem>,
    onItemClick: (ShowUiItem) -> Unit
) {
    val gridState = rememberLazyGridState()
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
                    onClick = { onItemClick(item) }
                )
            }
        }
        ScrollToTopButton(
            gridState = gridState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp)
        )
    }
}

/** 想看列表空状态（区分搜索无结果 vs 列表为空，Trakt 超链接） */
@Composable
private fun WatchlistEmptyState(
    isSearchResult: Boolean,
    emptyText: String,
    onOpenTrakt: (() -> Unit)?
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
