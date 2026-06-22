package com.tracktosearch.ui.screen.discover

import com.tracktosearch.ui.util.showToast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.ui.component.DoubanHotCardSkeleton
import com.tracktosearch.ui.screen.search.DoubanHotAllSheet
import com.tracktosearch.ui.screen.search.DoubanHotCategorySection
import com.tracktosearch.ui.util.performHapticClick
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding


@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun DiscoverScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onOpenWebView: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val sectionConfigs by viewModel.sectionConfigs.collectAsState()
    val context = LocalContext.current
    var showDoubanAllDialog by remember { mutableStateOf<String?>(null) }
    var showPopularAll by remember { mutableStateOf(false) }
    var showUpcomingAll by remember { mutableStateOf(false) }
    var showRecommendationsAll by remember { mutableStateOf(false) }

    LaunchedEffect(showDoubanAllDialog) {
        val catId = showDoubanAllDialog ?: return@LaunchedEffect
        viewModel.loadDoubanHotAll(catId, limit = 50)
    }
    LaunchedEffect(showPopularAll) {
        if (showPopularAll && uiState.popularAllItems.isEmpty()) {
            viewModel.loadPopularAll(page = 1)
        }
    }
    LaunchedEffect(showUpcomingAll) {
        if (showUpcomingAll && uiState.upcomingAllItems.isEmpty()) {
            viewModel.loadUpcomingAll(page = 1)
        }
    }
    LaunchedEffect(showRecommendationsAll) {
        if (showRecommendationsAll && uiState.recommendationsAllItems.isEmpty()) {
            viewModel.loadRecommendationsAll(page = 1)
        }
    }

    // 豆瓣热榜全部加载失败时（且至少有一个豆瓣栏目可见），显示整页重试
    val visibleDoubanIds = sectionConfigs.filter { it.visible && it.id.startsWith("douban-") }.map { it.id }
    val allDoubanFailed = visibleDoubanIds.isNotEmpty() &&
        uiState.doubanHotCategories.isNotEmpty() &&
        uiState.doubanHotCategories.filter { it.id in visibleDoubanIds }.all { it.error != null && it.items.isEmpty() }

    val discoverHazeState = remember { HazeState() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (allDoubanFailed) {
                Column(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.common_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.retryAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.error_retry))
                    }
                }
            } else {
                val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                LazyColumn(
                    modifier = modifier
                        .fillMaxSize()
                        .hazeSource(state = discoverHazeState),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 56.dp + statusBarHeight,
                        bottom = 80.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 根据用户设置（显示/隐藏 + 排序）渲染各栏目
                sectionConfigs.filter { it.visible }.forEach { config ->
                    when (config.id) {
                        // 豆瓣热榜各榜单
                        "douban-movie", "douban-weekly", "douban-top250", "douban-us-box" -> {
                            val category = uiState.doubanHotCategories.find { it.id == config.id }
                            if (category != null) {
                                item(key = config.id) {
                                    DoubanHotCategorySection(
                                        category = category,
                                        resolvingItemId = uiState.resolvingItemId,
                                        onItemClick = { item ->
                                            context.performHapticClick()
                                            val displayTitle = item.title
                                                .replace(Regex("【\\d+\\.?\\d*】\\s*"), "")
                                                .replace(Regex("^#\\d+\\s*"), "")
                                            copyToClipboard(context, displayTitle)
                                            viewModel.resolveAndNavigate(item) { traktId, tmdbId, title, imdbId, traktRating ->
                                                onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                                            }
                                        },
                                        onViewAll = { showDoubanAllDialog = category.id },
                                        onRetry = { viewModel.retryDoubanCategory(category.id) }
                                    )
                                }
                            }
                        }
                        // 热门电影
                        "tmdb-popular" -> {
                            item(key = "tmdb_popular") {
                                TmdbMovieSection(
                                    title = stringResource(R.string.discover_popular),
                                    movies = uiState.tmdbPopularMovies,
                                    isLoading = uiState.isLoadingPopular,
                                    error = uiState.popularError,
                                    resolvingItemId = uiState.resolvingTmdbId,
                                    onItemClick = { movie ->
                                        context.performHapticClick()
                                        viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                                        }
                                    },
                                    onRetry = { viewModel.loadTmdbPopular() },
                                    onViewAll = { context.performHapticClick(); showPopularAll = true }
                                )
                            }
                        }
                        // 即将上映
                        "tmdb-upcoming" -> {
                            item(key = "tmdb_upcoming") {
                                TmdbMovieSection(
                                    title = stringResource(R.string.discover_upcoming),
                                    movies = uiState.tmdbUpcomingMovies,
                                    isLoading = uiState.isLoadingUpcoming,
                                    error = uiState.upcomingError,
                                    resolvingItemId = uiState.resolvingTmdbId,
                                    onItemClick = { movie ->
                                        context.performHapticClick()
                                        viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                                        }
                                    },
                                    onRetry = { viewModel.loadTmdbUpcoming() },
                                    onViewAll = { context.performHapticClick(); showUpcomingAll = true }
                                )
                            }
                        }
                        // 为你推荐
                        "trakt-recommendations" -> {
                            item(key = "trakt_recommendations") {
                                TraktRecommendationSection(
                                    title = stringResource(R.string.discover_recommended),
                                    movies = uiState.traktRecommendations,
                                    isLoading = uiState.isLoadingRecommendations,
                                    error = uiState.recommendationsError,
                                    resolvingItemId = uiState.resolvingTmdbId,
                                    onItemClick = { movie ->
                                        context.performHapticClick()
                                        viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                                        }
                                    },
                                    onRetry = { viewModel.loadTraktRecommendations() },
                                    onViewAll = { context.performHapticClick(); showRecommendationsAll = true }
                                )
                            }
                        }
                    }
                }
            }
            }
            // Haze模糊渐变TopAppBar（含状态栏）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeEffect(
                        state = discoverHazeState,
                        style = HazeMaterials.thin()
                    ) {
                        progressive = HazeProgressive.verticalGradient(
                            startIntensity = 1f,
                            endIntensity = 0f
                        )
                    }
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.50f))
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.discover_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    // 豆瓣热榜全量弹窗
    showDoubanAllDialog?.let { catId ->
        val category = uiState.doubanHotCategories.find { it.id == catId }
        if (category != null) {
            DoubanHotAllSheet(
                category = category,
                resolvingItemId = uiState.resolvingItemId,
                onItemClick = { item ->
                    val displayTitle = item.title
                        .replace(Regex("【\\d+\\.?\\d*】\\s*"), "")
                        .replace(Regex("^#\\d+\\s*"), "")
                    copyToClipboard(context, displayTitle)
                    viewModel.resolveAndNavigate(item) { traktId, tmdbId, title, imdbId, traktRating ->
                        onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                    }
                },
                onLoadMore = {
                    viewModel.loadDoubanHotAll(catId, page = category.currentPage + 1, limit = 50)
                },
                onDismiss = { showDoubanAllDialog = null }
            )
        }
    }

    // 热门电影全部弹窗
    if (showPopularAll) {
        TmdbAllSheet(
            title = stringResource(R.string.discover_popular),
            items = uiState.popularAllItems,
            isLoading = uiState.isLoadingPopularAll,
            hasMore = uiState.popularAllHasMore,
            currentPage = uiState.popularAllPage,
            onItemClick = { movie ->
                viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating ->
                    onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                }
            },
            onLoadMore = { viewModel.loadPopularAll(page = uiState.popularAllPage + 1) },
            onDismiss = { showPopularAll = false }
        )
    }

    // 即将上映全部弹窗
    if (showUpcomingAll) {
        TmdbAllSheet(
            title = stringResource(R.string.discover_upcoming),
            items = uiState.upcomingAllItems,
            isLoading = uiState.isLoadingUpcomingAll,
            hasMore = uiState.upcomingAllHasMore,
            currentPage = uiState.upcomingAllPage,
            onItemClick = { movie ->
                viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating ->
                    onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                }
            },
            onLoadMore = { viewModel.loadUpcomingAll(page = uiState.upcomingAllPage + 1) },
            onDismiss = { showUpcomingAll = false }
        )
    }

    // 为你推荐全部弹窗
    if (showRecommendationsAll) {
        TraktMovieAllSheet(
            title = stringResource(R.string.discover_recommended),
            items = uiState.recommendationsAllItems,
            isLoading = uiState.isLoadingRecommendationsAll,
            hasMore = uiState.recommendationsAllHasMore,
            currentPage = uiState.recommendationsAllPage,
            onItemClick = { movie ->
                viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating ->
                    onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                }
            },
            onLoadMore = { viewModel.loadRecommendationsAll(page = uiState.recommendationsAllPage + 1) },
            onDismiss = { showRecommendationsAll = false }
        )
    }
}

@Composable
private fun TmdbMovieSection(
    title: String,
    movies: List<TmdbSearchResult>,
    isLoading: Boolean,
    error: String?,
    resolvingItemId: Int?,
    onItemClick: (TmdbSearchResult) -> Unit,
    onRetry: () -> Unit,
    onViewAll: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (movies.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clickable { onViewAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_view_all, movies.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            error != null -> {
                ErrorRetryRow(error = error, onRetry = onRetry)
            }
            movies.isEmpty() -> {
                EmptyRow()
            }
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(movies, key = { it.id }) { movie ->
                        MovieCard(
                            title = movie.title,
                            posterPath = movie.poster_path,
                            year = movie.release_date.take(4),
                            rating = null,
                            isResolving = resolvingItemId == movie.id,
                            onClick = { onItemClick(movie) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TraktRecommendationSection(
    title: String,
    movies: List<TraktMovie>,
    isLoading: Boolean,
    error: String?,
    resolvingItemId: Int?,
    onItemClick: (TraktMovie) -> Unit,
    onRetry: () -> Unit,
    onViewAll: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (movies.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clickable { onViewAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_view_all, movies.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            error != null -> {
                ErrorRetryRow(error = error, onRetry = onRetry)
            }
            movies.isEmpty() -> {
                EmptyRow()
            }
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(movies, key = { "${it.ids.trakt}_${it.ids.tmdb}_${it.title}" }) { movie ->
                        MovieCard(
                            title = movie.title,
                            posterPath = movie.posterPath,
                            year = if (movie.year > 0) movie.year.toString() else "",
                            rating = if (movie.rating > 0)
                                String.format("%.1f", movie.rating) else null,
                            isResolving = resolvingItemId == movie.ids.tmdb,
                            onClick = { onItemClick(movie) }
                        )
                    }
                }
            }
        }
    }
}

/** 通用电影卡片（复用豆瓣卡片样式） */
@Composable
private fun MovieCard(
    title: String,
    posterPath: String?,
    year: String,
    rating: String?,
    isResolving: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val posterUrl = posterPath?.let {
        if (it.startsWith("http")) it
        else if (it.toIntOrNull() != null) null // TMDB ID 无法直接拼海报 URL，需要通过详情接口获取
        else "https://image.tmdb.org/t/p/w500$it"
    }

    Card(
        modifier = Modifier
            .width(99.dp)
            .clickable(enabled = !isResolving) { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            ) {
                if (posterUrl != null) {
                    val imageRequest = remember(posterUrl) {
                        ImageRequest.Builder(context)
                            .data(posterUrl)
                            .size(300)
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title.take(2),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
                if (rating != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF68BD5B)
                    ) {
                        Text(
                            text = rating,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                if (isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    }
                }
                // 年份角标（海报右下角）
                if (year.isNotEmpty()) {
                    Text(
                        text = year,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.Black,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.9f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ErrorRetryRow(error: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.common_load_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.error_retry), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.empty_default),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("影片名", text))
    context.showToast("已复制: $text")
}

/** TMDB 电影全部弹窗（分页加载） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TmdbAllSheet(
    title: String,
    items: List<TmdbSearchResult>,
    isLoading: Boolean,
    hasMore: Boolean,
    currentPage: Int,
    onItemClick: (TmdbSearchResult) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyGridState()
    // 滚动到底部时自动加载更多
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null &&
                    lastVisibleIndex >= items.size - 5 &&
                    hasMore &&
                    !isLoading
                ) {
                    onLoadMore()
                }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = listState,
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { movie ->
                    MovieCard(
                        title = movie.title,
                        posterPath = movie.poster_path,
                        year = movie.release_date.take(4),
                        rating = null,
                        isResolving = false,
                        onClick = { onItemClick(movie) }
                    )
                }
                if (isLoading) {
                    item(span = { GridItemSpan(3) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Trakt 推荐全部弹窗（分页加载） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraktMovieAllSheet(
    title: String,
    items: List<TraktMovie>,
    isLoading: Boolean,
    hasMore: Boolean,
    currentPage: Int,
    onItemClick: (TraktMovie) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyGridState()
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null &&
                    lastVisibleIndex >= items.size - 5 &&
                    hasMore &&
                    !isLoading
                ) {
                    onLoadMore()
                }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = listState,
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { "${it.ids.trakt}_${it.ids.tmdb}_${it.title}" }) { movie ->
                    MovieCard(
                        title = movie.title,
                        posterPath = movie.posterPath,
                        year = if (movie.year > 0) movie.year.toString() else "",
                        rating = if (movie.rating > 0)
                            String.format("%.1f", movie.rating) else null,
                        isResolving = false,
                        onClick = { onItemClick(movie) }
                    )
                }
                if (isLoading) {
                    item(span = { GridItemSpan(3) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun doubanCategoryLabel(categoryId: String): String = when (categoryId) {
    "douban-movie" -> stringResource(R.string.discover_douban_new_movies)
    "douban-weekly" -> stringResource(R.string.discover_douban_weekly)
    "douban-top250" -> stringResource(R.string.discover_douban_top250)
    "douban-us-box" -> stringResource(R.string.discover_douban_us_box)
    else -> categoryId
}
