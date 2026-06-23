package com.tracktosearch.ui.screen.traktsearch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tracktosearch.R
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.MovieCardSkeleton
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.HapticType
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraktSearchScreen(
    initialQuery: String,
    type: MediaType,
    onBack: () -> Unit,
    onItemClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    viewModel: TraktSearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by rememberSaveable { mutableStateOf(initialQuery) }

    LaunchedEffect(initialQuery, type) {
        viewModel.initSearch(initialQuery, type)
    }

    // Pager 状态，与 ViewModel 的 selectedTab 双向同步
    val pagerState = rememberPagerState(initialPage = if (type == MediaType.SHOW) 1 else 0) { 2 }

    // 滑动 → 同步 ViewModel
    LaunchedEffect(pagerState.currentPage) {
        val targetTab = if (pagerState.currentPage == 0) MediaType.MOVIE else MediaType.SHOW
        if (uiState.selectedTab != targetTab) {
            viewModel.switchTab(targetTab)
        }
    }

    // ViewModel tab 变化 → 同步 Pager（仅点击 Tab 时触发）
    LaunchedEffect(uiState.selectedTab) {
        val targetPage = if (uiState.selectedTab == MediaType.MOVIE) 0 else 1
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
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
                                if (uiState.selectedTab == MediaType.MOVIE) stringResource(R.string.trakt_search_hint_movies)
                                else stringResource(R.string.trakt_search_hint_shows)
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                if (searchQuery.isNotBlank()) {
                                    viewModel.search(searchQuery)
                                }
                            }
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                if (searchQuery.isNotBlank()) {
                                    viewModel.search(searchQuery)
                                }
                            }) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.watchlist_search))
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 电影/电视剧 Tab
            PrimaryTabRow(
                selectedTabIndex = if (uiState.selectedTab == MediaType.MOVIE) 0 else 1,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = uiState.selectedTab == MediaType.MOVIE,
                    onClick = { view.performHaptic(HapticType.TICK); viewModel.switchTab(MediaType.MOVIE) },
                    text = {
                        val count = uiState.movieState.totalCount
                        if (count > 0 && uiState.movieState.hasSearched) {
                            Text(stringResource(R.string.trakt_search_tab_movies_count, count))
                        } else {
                            Text(stringResource(R.string.trakt_search_tab_movies))
                        }
                    }
                )
                Tab(
                    selected = uiState.selectedTab == MediaType.SHOW,
                    onClick = { view.performHaptic(HapticType.TICK); viewModel.switchTab(MediaType.SHOW) },
                    text = {
                        val count = uiState.showState.totalCount
                        if (count > 0 && uiState.showState.hasSearched) {
                            Text(stringResource(R.string.trakt_search_tab_shows_count, count))
                        } else {
                            Text(stringResource(R.string.trakt_search_tab_shows))
                        }
                    }
                )
            }

            // 左右滑动切换 Tab 内容
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val pageType = if (page == 0) MediaType.MOVIE else MediaType.SHOW
                val tabState = if (pageType == MediaType.MOVIE) uiState.movieState else uiState.showState

                when {
                    tabState.isLoading -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(9) { MovieCardSkeleton() }
                        }
                    }
                    tabState.error != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyView(message = tabState.error!!)
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { viewModel.search(uiState.query, pageType) }) {
                                    Text(stringResource(R.string.watchlist_retry))
                                }
                            }
                        }
                    }
                    tabState.results.isEmpty() && tabState.hasSearched -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (pageType == MediaType.MOVIE)
                                    stringResource(R.string.trakt_search_no_movies)
                                else
                                    stringResource(R.string.trakt_search_no_shows),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                    else -> {
                        val gridState = remember(pageType) { androidx.compose.foundation.lazy.grid.LazyGridState() }

                        // 自动触发加载更多
                        LaunchedEffect(gridState, tabState.hasMore, tabState.isLoadingMore) {
                            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                                .collect { lastVisibleIndex ->
                                    if (lastVisibleIndex != null &&
                                        lastVisibleIndex >= tabState.results.size - 6 &&
                                        tabState.hasMore && !tabState.isLoadingMore
                                    ) {
                                        viewModel.loadMore()
                                    }
                                }
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(tabState.results.size, key = { tabState.results[it].traktId }) { index ->
                                val item = tabState.results[index]
                                MovieCard(
                                    title = item.displayTitle,
                                    year = item.year,
                                    genres = item.genres,
                                    posterUrl = item.posterUrl,
                                    tmdbId = item.tmdbId,
                                    onClick = {
                                        view.performHaptic(HapticType.CLICK)
                                        onItemClick(item.traktId, item.tmdbId, item.displayTitle, item.imdbId, item.traktRating)
                                    }
                                )
                            }
                            // 加载更多指示器
                            if (tabState.isLoadingMore) {
                                item(span = { GridItemSpan(3) }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
