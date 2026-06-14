package com.tracktosearch.ui.screen.watchlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.LazyGridScrollbar
import com.tracktosearch.ui.component.LoadingView
import com.tracktosearch.ui.component.MovieCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onSearchClick: (keyword: String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WatchlistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadMovies()
        viewModel.loadShows()
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var lastExpandTime by remember { mutableLongStateOf(0L) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            lastExpandTime = System.currentTimeMillis()
            focusRequester.requestFocus()
        } else {
            focusManager.clearFocus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchExpanded) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    // 展开后 800ms 内不因失焦收起（防止 requestFocus 异步导致立刻收起）
                                    if (!focusState.isFocused && searchQuery.isEmpty()
                                        && System.currentTimeMillis() - lastExpandTime > 800
                                    ) {
                                        isSearchExpanded = false
                                    }
                                },
                            placeholder = { Text(stringResource(R.string.search_placeholder)) },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (searchQuery.isNotBlank()) {
                                        onSearchClick(searchQuery)
                                        searchQuery = ""
                                        isSearchExpanded = false
                                    }
                                }
                            ),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    TextButton(onClick = {
                                        onSearchClick(searchQuery)
                                        searchQuery = ""
                                        isSearchExpanded = false
                                    }) {
                                        Text(stringResource(R.string.search_button))
                                    }
                                }
                            }
                        )
                    } else {
                        Text(stringResource(R.string.watchlist_title))
                    }
                },
                actions = {
                    if (!isSearchExpanded) {
                        IconButton(onClick = { isSearchExpanded = true }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.watchlist_search))
                        }
                    }
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
                    onClick = { selectedTab = 0 },
                    text = { Text("${stringResource(R.string.watchlist_tab_movies)}(${uiState.movies.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("${stringResource(R.string.watchlist_tab_shows)}(${uiState.shows.size})") }
                )
            }

            // 内容区域 - 区分加载中/错误/空/有数据 四种状态
            when (selectedTab) {
                0 -> MovieTabContent(
                    isLoading = uiState.isLoadingMovies,
                    isLoaded = uiState.moviesLoaded,
                    items = uiState.movies,
                    error = uiState.moviesError,
                    onItemClick = { onMovieClick(it.traktId, it.tmdbId, it.title, it.imdbId, it.traktRating) },
                    onRetry = { viewModel.loadMovies(forceReload = true) }
                )
                1 -> ShowTabContent(
                    isLoading = uiState.isLoadingShows,
                    isLoaded = uiState.showsLoaded,
                    items = uiState.shows,
                    error = uiState.showsError,
                    onItemClick = { onShowClick(it.traktId, it.tmdbId, it.title, it.imdbId, it.traktRating) },
                    onRetry = { viewModel.loadShows(forceReload = true) }
                )
            }
        }
    }
}

@Composable
private fun MovieTabContent(
    isLoading: Boolean,
    isLoaded: Boolean,
    items: List<MovieUiItem>,
    error: String?,
    onItemClick: (MovieUiItem) -> Unit,
    onRetry: () -> Unit
) {
    // 状态优先级：错误 > 首次加载中 > 空 > 数据
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
            EmptyView(message = stringResource(R.string.watchlist_empty_movies))
        }
        else -> {
            MovieGrid(
                items = items,
                onItemClick = onItemClick
            )
            // 如果还在加载更多，底部显示小 loading
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
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
    error: String?,
    onItemClick: (ShowUiItem) -> Unit,
    onRetry: () -> Unit
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
            EmptyView(message = stringResource(R.string.watchlist_empty_shows))
        }
        else -> {
            ShowGrid(
                items = items,
                onItemClick = onItemClick
            )
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
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
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.traktId }) { item ->
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
        LazyGridScrollbar(
            state = gridState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
        )
    }
}

@Composable
private fun ShowGrid(
    items: List<ShowUiItem>,
    onItemClick: (ShowUiItem) -> Unit
) {
    val gridState = rememberLazyGridState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.traktId }) { item ->
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
        LazyGridScrollbar(
            state = gridState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
        )
    }
}
