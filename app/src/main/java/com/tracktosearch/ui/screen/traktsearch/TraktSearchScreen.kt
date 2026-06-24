package com.tracktosearch.ui.screen.traktsearch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.MovieCardSkeleton
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraktSearchScreen(
    initialQuery: String,
    type: MediaType,
    onBack: () -> Unit,
    onItemClick: (type: MediaType, traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onPersonClick: (tmdbId: Int, name: String, profileUrl: String) -> Unit = { _, _, _ -> },
    inlineMode: Boolean = false,
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
    val initialPage = when (type) {
        MediaType.MOVIE -> 0
        MediaType.SHOW -> 1
        MediaType.PERSON -> 2
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { 3 }

    val movieGridState = rememberLazyGridState()
    val showGridState = rememberLazyGridState()
    val personGridState = rememberLazyGridState()
    val scrollToTopProvider = LocalScrollToTopProvider.current
    val traktCoroutineScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        scrollToTopProvider.register {
            val currentGridState = when (pagerState.currentPage) {
                0 -> movieGridState
                1 -> showGridState
                else -> personGridState
            }
            traktCoroutineScope.launch {
                currentGridState.animateScrollToItem(0)
            }
        }
        onDispose {
            scrollToTopProvider.unregister()
        }
    }

    // 滑动 → 同步 ViewModel
    LaunchedEffect(pagerState.currentPage) {
        val targetTab = when (pagerState.currentPage) {
            0 -> MediaType.MOVIE
            1 -> MediaType.SHOW
            else -> MediaType.PERSON
        }
        if (uiState.selectedTab != targetTab) {
            viewModel.switchTab(targetTab)
        }
    }

    // ViewModel tab 变化 → 同步 Pager（仅点击 Tab 时触发）
    LaunchedEffect(uiState.selectedTab) {
        val targetPage = when (uiState.selectedTab) {
            MediaType.MOVIE -> 0
            MediaType.SHOW -> 1
            MediaType.PERSON -> 2
        }
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    Scaffold(
        contentWindowInsets = if (inlineMode) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                            when (uiState.selectedTab) {
                                MediaType.MOVIE -> stringResource(R.string.trakt_search_hint_movies)
                                MediaType.SHOW -> stringResource(R.string.trakt_search_hint_shows)
                                MediaType.PERSON -> stringResource(R.string.trakt_search_hint_persons)
                            }
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
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 电影/电视剧/人物 Tab
            val selectedTabIndex = when (uiState.selectedTab) {
                MediaType.MOVIE -> 0
                MediaType.SHOW -> 1
                MediaType.PERSON -> 2
            }
            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
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
                    onClick = { view.performHaptic(HapticType.CLICK); viewModel.switchTab(MediaType.SHOW) },
                    text = {
                        val count = uiState.showState.totalCount
                        if (count > 0 && uiState.showState.hasSearched) {
                            Text(stringResource(R.string.trakt_search_tab_shows_count, count))
                        } else {
                            Text(stringResource(R.string.trakt_search_tab_shows))
                        }
                    }
                )
                Tab(
                    selected = uiState.selectedTab == MediaType.PERSON,
                    onClick = { view.performHaptic(HapticType.CLICK); viewModel.switchTab(MediaType.PERSON) },
                    text = {
                        val count = uiState.personState.totalCount
                        if (count > 0 && uiState.personState.hasSearched) {
                            Text(stringResource(R.string.trakt_search_tab_persons_count, count))
                        } else {
                            Text(stringResource(R.string.trakt_search_tab_persons))
                        }
                    }
                )
            }

            // 左右滑动切换 Tab 内容
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val pageType = when (page) {
                    0 -> MediaType.MOVIE
                    1 -> MediaType.SHOW
                    else -> MediaType.PERSON
                }
                val tabState = when (pageType) {
                    MediaType.MOVIE -> uiState.movieState
                    MediaType.SHOW -> uiState.showState
                    MediaType.PERSON -> uiState.personState
                }

                when {
                    tabState.isLoading -> {
                        if (pageType == MediaType.PERSON) {
                            // 人物骨架屏
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(9) { PersonCardSkeleton() }
                            }
                        } else {
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
                                text = when (pageType) {
                                    MediaType.MOVIE -> stringResource(R.string.trakt_search_no_movies)
                                    MediaType.SHOW -> stringResource(R.string.trakt_search_no_shows)
                                    MediaType.PERSON -> stringResource(R.string.trakt_search_no_persons)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                    else -> {
                        val gridState = when (pageType) {
                            MediaType.MOVIE -> movieGridState
                            MediaType.SHOW -> showGridState
                            MediaType.PERSON -> personGridState
                        }

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

                        if (pageType == MediaType.PERSON) {
                            // 人物搜索结果 - 使用列表布局
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                state = gridState,
                                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(tabState.results.size, key = { "${tabState.results[it].traktId}_$it" }) { index ->
                                    val item = tabState.results[index]
                                    PersonSearchCard(
                                        name = item.displayTitle,
                                        profileUrl = item.posterUrl,
                                        knownForDepartment = item.knownForDepartment,
                                        onClick = {
                                            if (item.tmdbId > 0) {
                                                onPersonClick(item.tmdbId, item.title, item.posterUrl ?: "")
                                            }
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
                        } else {
                            // 电影/电视剧搜索结果
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                state = gridState,
                                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(tabState.results.size, key = { "${tabState.results[it].traktId}_$it" }) { index ->
                                    val item = tabState.results[index]
                                    MovieCard(
                                        title = item.displayTitle,
                                        year = item.year,
                                        genres = item.genres,
                                        posterUrl = item.posterUrl,
                                        tmdbId = item.tmdbId,
                                        onClick = {
                                            onItemClick(pageType, item.traktId, item.tmdbId, item.displayTitle, item.imdbId, item.traktRating)
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
}

@Composable
private fun PersonSearchCard(
    name: String,
    profileUrl: String?,
    knownForDepartment: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
                contentAlignment = Alignment.Center
            ) {
                if (profileUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(profileUrl)
                            .size(300)
                            .crossfade(true)
                            .build(),
                        contentDescription = name,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = name.take(1),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.padding(6.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (knownForDepartment.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = knownForDepartment,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonCardSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {}
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(12.dp),
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {}
    }
}
