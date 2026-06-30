package com.tracktosearch.ui.screen.discover

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedShowResponse
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktRecommendationShowResponse
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingListResponse
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingShowResponse
import com.tracktosearch.ui.component.DoubanHotCardSkeleton
import com.tracktosearch.ui.component.MarqueeText
import com.tracktosearch.ui.screen.search.DoubanHotAllSheet
import com.tracktosearch.ui.screen.search.DoubanHotCategorySection
import com.tracktosearch.ui.screen.settings.DiscoverSectionsDialog
import com.tracktosearch.ui.screen.settings.SettingsViewModel
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.showToast
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun DiscoverScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onOpenWebView: (url: String) -> Unit,
    onListClick: (slug: String, listName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sectionConfigs by viewModel.sectionConfigs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    LaunchedEffect(Unit) {
        viewModel.toastEvent.collect { resId ->
            context.showToast(context.getString(resId))
        }
    }
    var showDoubanAllDialog by remember { mutableStateOf<String?>(null) }
    var showPopularAll by remember { mutableStateOf(false) }
    var showUpcomingAll by remember { mutableStateOf(false) }
    var showRecommendationsAll by remember { mutableStateOf(false) }
    var showTrendingMoviesAll by remember { mutableStateOf(false) }
    var showTrendingShowsAll by remember { mutableStateOf(false) }
    var showAnticipatedAll by remember { mutableStateOf(false) }
    var showShowRecsAll by remember { mutableStateOf(false) }
    var showTrendingListsAll by remember { mutableStateOf(false) }
    var showDiscoverSectionsDialog by remember { mutableStateOf(false) }

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

    val discoverHazeState = remember { HazeState() }
    val discoverListState = rememberLazyListState()
    val scrollToTopProvider = LocalScrollToTopProvider.current
    val coroutineScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        scrollToTopProvider.register {
            coroutineScope.launch {
                discoverListState.animateScrollToItem(0)
            }
        }
        onDispose {
            scrollToTopProvider.unregister()
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
            LazyColumn(
                    state = discoverListState,
                    modifier = modifier
                        .fillMaxSize()
                        .hazeSource(state = discoverHazeState),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 80.dp + statusBarHeight,
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
                        // 趋势电影（原热门电影，含今日/本周切换）
                        "tmdb-popular" -> {
                            item(key = "tmdb_popular") {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.discover_trending),
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        // 紧凑胶囊分段器：今日 / 本周（宽度跟随文字）
                                        val isDay = uiState.trendingTimeWindow == "day"
                                        val dayLabel = stringResource(R.string.discover_trending_day)
                                        val weekLabel = stringResource(R.string.discover_trending_week)
                                        var dayTextWidth by remember { mutableStateOf(0f) }
                                        var weekTextWidth by remember { mutableStateOf(0f) }
                                        val tabPadding = 12.dp
                                        val tabHeight = 30.dp
                                        val dayTabWidthDp = with(LocalDensity.current) { dayTextWidth.toDp() + tabPadding * 2 }
                                        val weekTabWidthDp = with(LocalDensity.current) { weekTextWidth.toDp() + tabPadding * 2 }
                                        val indicatorOffset by animateDpAsState(
                                            targetValue = if (isDay) 0.dp else dayTabWidthDp,
                                            animationSpec = tween(200),
                                            label = "indicator"
                                        )
                                        val indicatorWidth by animateDpAsState(
                                            targetValue = if (isDay) dayTabWidthDp else weekTabWidthDp,
                                            animationSpec = tween(200),
                                            label = "indicatorWidth"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .height(tabHeight)
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            // 滑块指示器
                                            Box(
                                                modifier = Modifier
                                                    .offset(x = indicatorOffset)
                                                    .fillMaxHeight()
                                                    .width(indicatorWidth)
                                                    .padding(3.dp)
                                                    .clip(RoundedCornerShape(5.dp))
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                            // 文字选项
                                            Row {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .clickable { viewModel.switchTrendingTimeWindow("day") }
                                                        .padding(horizontal = tabPadding),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = dayLabel,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isDay) FontWeight.Medium else FontWeight.Normal,
                                                        color = if (isDay)
                                                            MaterialTheme.colorScheme.onPrimary
                                                        else
                                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                                        onTextLayout = { dayTextWidth = it.size.width.toFloat() }
                                                    )
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .clickable { viewModel.switchTrendingTimeWindow("week") }
                                                        .padding(horizontal = tabPadding),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = weekLabel,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (!isDay) FontWeight.Medium else FontWeight.Normal,
                                                        color = if (!isDay)
                                                            MaterialTheme.colorScheme.onPrimary
                                                        else
                                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                                        onTextLayout = { weekTextWidth = it.size.width.toFloat() }
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.weight(1f))
                                        if (uiState.tmdbPopularMovies.isNotEmpty()) {
                                            Row(
                                                modifier = Modifier
                                                    .clickable { showPopularAll = true }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.discover_view_all, uiState.tmdbPopularMovies.size),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                    TmdbMovieSection(
                                        title = "",
                                        movies = uiState.tmdbPopularMovies,
                                        isLoading = uiState.isLoadingPopular,
                                        error = uiState.popularError,
                                        resolvingItemId = uiState.resolvingTmdbId,
                                        onItemClick = { movie ->
                                            viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating ->
                                                onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                                            }
                                        },
                                        onRetry = { viewModel.loadTmdbPopular() },
                                        onViewAll = { showPopularAll = true }
                                    )
                                }
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
                                        viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                                        }
                                    },
                                    onRetry = { viewModel.loadTmdbUpcoming() },
                                    onViewAll = { showUpcomingAll = true }
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
                                        viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                                        }
                                    },
                                    onRetry = { viewModel.loadTraktRecommendations() },
                                    onViewAll = { showRecommendationsAll = true }
                                )
                            }
                        }
                        // Trakt 热门电影
                        "trakt-trending-movies" -> {
                            item(key = "trakt_trending_movies") {
                                TraktTrendingMovieSection(
                                    items = uiState.traktTrendingMovies,
                                    isLoading = uiState.isLoadingTrakt,
                                    resolvingItemId = uiState.resolvingTmdbId,
                                    totalCount = uiState.traktTrendingMovies.size,
                                    onItemClick = { movie ->
                                        viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                                        }
                                    },
                                    onViewAll = { showTrendingMoviesAll = true }
                                )
                            }
                        }
                        // Trakt 热门剧集
                        "trakt-trending-shows" -> {
                            item(key = "trakt_trending_shows") {
                                TraktTrendingShowSection(
                                    items = uiState.traktTrendingShows,
                                    isLoading = uiState.isLoadingTrakt,
                                    resolvingItemId = uiState.resolvingTmdbId,
                                    totalCount = uiState.traktTrendingShows.size,
                                    onItemClick = { show ->
                                        viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating ->
                                            onShowClick(traktId, tmdbId, title, imdbId, traktRating)
                                        }
                                    },
                                    onViewAll = { showTrendingShowsAll = true }
                                )
                            }
                        }
                        // Trakt 最受期待
                        "trakt-anticipated" -> {
                            item(key = "trakt_anticipated") {
                                TraktAnticipatedSection(
                                    anticipatedMovies = uiState.traktAnticipatedMovies,
                                    anticipatedShows = uiState.traktAnticipatedShows,
                                    isLoading = uiState.isLoadingTrakt,
                                    resolvingItemId = uiState.resolvingTmdbId,
                                    totalCount = uiState.traktAnticipatedMovies.size + uiState.traktAnticipatedShows.size,
                                    onMovieClick = { movie ->
                                        viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                                        }
                                    },
                                    onShowClick = { show ->
                                        viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating ->
                                            onShowClick(traktId, tmdbId, title, imdbId, traktRating)
                                        }
                                    },
                                    onViewAll = { showAnticipatedAll = true }
                                )
                            }
                        }
                        // 为你推荐剧集
                        "trakt-show-recommendations" -> {
                            item(key = "trakt_show_recommendations") {
                                TraktShowRecommendationSection(
                                    items = uiState.traktShowRecommendations,
                                    isLoading = uiState.isLoadingTrakt,
                                    resolvingItemId = uiState.resolvingTmdbId,
                                    totalCount = uiState.traktShowRecommendations.size,
                                    onItemClick = { show ->
                                        viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating ->
                                            onShowClick(traktId, tmdbId, title, imdbId, traktRating)
                                        }
                                    },
                                    onViewAll = { showShowRecsAll = true }
                                )
                            }
                        }
                        // 社区热门列表
                        "trakt-lists" -> {
                            item(key = "trakt_lists") {
                                Column {
                                    Text(
                                        text = stringResource(R.string.discover_trending_lists),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    if (uiState.isLoadingTraktLists) {
                                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        }
                                    } else if (uiState.trendingLists.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = stringResource(R.string.discover_list_empty),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            uiState.trendingLists.take(5).forEach { listResponse ->
                                                Card(modifier = Modifier.fillMaxWidth().clickable { onListClick(listResponse.list.ids.slug, listResponse.list.name) }) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = listResponse.list.name,
                                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                text = stringResource(R.string.discover_list_meta, listResponse.list.item_count, listResponse.list.user?.username ?: "", listResponse.like_count),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            if (uiState.trendingLists.isNotEmpty()) {
                                                TextButton(
                                                    onClick = { showTrendingListsAll = true },
                                                    modifier = Modifier.align(Alignment.End)
                                                ) {
                                                    Text(stringResource(R.string.common_view_all))
                                                    Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, modifier = Modifier.size(14.dp))
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
            // Haze模糊渐变TopAppBar（含状态栏）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeEffect(
                        state = discoverHazeState,
                        style = HazeMaterials.thin()
                    )
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.50f))
                    .clickable(enabled = false, onClick = {})
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = { showDiscoverSectionsDialog = true }) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = stringResource(R.string.settings_discover_sections),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
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
                    showDoubanAllDialog = null
                    coroutineScope.launch {
                        delay(300)
                        viewModel.resolveAndNavigate(item) { traktId, tmdbId, title, imdbId, traktRating ->
                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                        }
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
                showPopularAll = false
                coroutineScope.launch {
                    delay(300)
                    viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating ->
                        onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                    }
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

    // Trakt 热门电影全部弹窗
    if (showTrendingMoviesAll) {
        TraktMovieAllSheet(
            title = stringResource(R.string.discover_trakt_trending_movies),
            items = uiState.traktTrendingMovies.map { it.movie },
            isLoading = false,
            hasMore = false,
            currentPage = 1,
            onItemClick = { movie ->
                viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating ->
                    onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                }
            },
            onLoadMore = { },
            onDismiss = { showTrendingMoviesAll = false }
        )
    }

    // Trakt 热门剧集全部弹窗
    if (showTrendingShowsAll) {
        TraktShowAllSheet(
            title = stringResource(R.string.discover_trakt_trending_shows),
            items = uiState.traktTrendingShows.map { it.show },
            onItemClick = { show ->
                viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating ->
                    onShowClick(traktId, tmdbId, title, imdbId, traktRating)
                }
            },
            onDismiss = { showTrendingShowsAll = false }
        )
    }

    // Trakt 最受期待全部弹窗（电影+剧集混合）
    if (showAnticipatedAll) {
        TraktAnticipatedAllSheet(
            anticipatedMovies = uiState.traktAnticipatedMovies,
            anticipatedShows = uiState.traktAnticipatedShows,
            onMovieClick = { movie ->
                viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating ->
                    onMovieClick(traktId, tmdbId, title, imdbId, traktRating)
                }
            },
            onShowClick = { show ->
                viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating ->
                    onShowClick(traktId, tmdbId, title, imdbId, traktRating)
                }
            },
            onDismiss = { showAnticipatedAll = false }
        )
    }

    // 为你推荐剧集全部弹窗
    if (showShowRecsAll) {
        TraktShowAllSheet(
            title = stringResource(R.string.discover_trakt_recommendations_shows),
            items = uiState.traktShowRecommendations.map { it.show },
            onItemClick = { show ->
                viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating ->
                    onShowClick(traktId, tmdbId, title, imdbId, traktRating)
                }
            },
            onDismiss = { showShowRecsAll = false }
        )
    }

    // 社区热门列表全部弹窗
    if (showTrendingListsAll) {
        TrendingListsAllSheet(
            lists = uiState.trendingLists,
            onListClick = { slug, listName ->
                showTrendingListsAll = false
                onListClick(slug, listName)
            },
            onDismiss = { showTrendingListsAll = false }
        )
    }

    // 自定义发现页栏目弹窗
    if (showDiscoverSectionsDialog) {
        val settingsViewModel = hiltViewModel<SettingsViewModel>()
        DiscoverSectionsDialog(
            viewModel = settingsViewModel,
            onDismiss = { showDiscoverSectionsDialog = false }
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
        if (title.isNotEmpty()) {
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
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(movies, key = { index, movie -> "tmdb_movie_${index}_${movie.id}" }) { _, movie ->
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(movies, key = { index, movie -> "trakt_movie_${index}_${movie.ids.trakt}_${movie.ids.tmdb}" }) { _, movie ->
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
    subtitle: String? = null,
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
            .width(105.dp)
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
                MarqueeText(
                    text = title,
                    style = MaterialTheme.typography.bodySmall
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
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
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                itemsIndexed(items, key = { index, movie -> "all_tmdb_${index}_${movie.id}" }) { _, movie ->
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
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                itemsIndexed(items, key = { index, movie -> "all_trakt_${index}_${movie.ids.trakt}" }) { _, movie ->
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

/** Trakt 剧集全部弹窗（无分页，直接展示已加载数据） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraktShowAllSheet(
    title: String,
    items: List<TraktShow>,
    onItemClick: (TraktShow) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyGridState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                itemsIndexed(items, key = { index, show -> "all_trakt_show_${index}_${show.ids.trakt}" }) { _, show ->
                    MovieCard(
                        title = show.title,
                        posterPath = show.posterPath,
                        year = if (show.year > 0) show.year.toString() else "",
                        rating = if (show.rating > 0)
                            String.format("%.1f", show.rating) else null,
                        isResolving = false,
                        onClick = { onItemClick(show) }
                    )
                }
            }
        }
    }
}

/** Trakt 最受期待全部弹窗（电影+剧集混合，无分页） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraktAnticipatedAllSheet(
    anticipatedMovies: List<TraktAnticipatedMovieResponse>,
    anticipatedShows: List<TraktAnticipatedShowResponse>,
    onMovieClick: (TraktMovie) -> Unit,
    onShowClick: (TraktShow) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyGridState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                    text = stringResource(R.string.discover_trakt_anticipated),
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
                // 先展示电影，再展示剧集
                itemsIndexed(anticipatedMovies, key = { index, item -> "all_anticip_m_${index}_${item.movie.ids.trakt}" }) { _, item ->
                    MovieCard(
                        title = item.movie.title,
                        posterPath = item.movie.posterPath,
                        year = if (item.movie.year > 0) item.movie.year.toString() else "",
                        rating = if (item.movie.rating > 0) String.format("%.1f", item.movie.rating) else null,
                        subtitle = stringResource(R.string.discover_list_count, item.list_count),
                        isResolving = false,
                        onClick = { onMovieClick(item.movie) }
                    )
                }
                itemsIndexed(anticipatedShows, key = { index, item -> "all_anticip_s_${index}_${item.show.ids.trakt}" }) { _, item ->
                    MovieCard(
                        title = item.show.title,
                        posterPath = item.show.posterPath,
                        year = if (item.show.year > 0) item.show.year.toString() else "",
                        rating = if (item.show.rating > 0) String.format("%.1f", item.show.rating) else null,
                        subtitle = stringResource(R.string.discover_list_count, item.list_count),
                        isResolving = false,
                        onClick = { onShowClick(item.show) }
                    )
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

/** Trakt 热门电影栏目 */
@Composable
private fun TraktTrendingMovieSection(
    items: List<TraktTrendingMovieResponse>,
    isLoading: Boolean,
    resolvingItemId: Int?,
    totalCount: Int,
    onItemClick: (TraktMovie) -> Unit,
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
                text = stringResource(R.string.discover_trakt_trending_movies),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (totalCount > 0) {
                Row(
                    modifier = Modifier
                        .clickable { onViewAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_view_all, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            items.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(items, key = { index, item -> "trending_movie_${index}_${item.movie.ids.trakt}" }) { _, item ->
                        MovieCard(
                            title = item.movie.title,
                            posterPath = item.movie.posterPath,
                            year = if (item.movie.year > 0) item.movie.year.toString() else "",
                            rating = if (item.movie.rating > 0) String.format("%.1f", item.movie.rating) else null,
                            subtitle = stringResource(R.string.discover_watchers, item.watchers),
                            isResolving = resolvingItemId == item.movie.ids.tmdb,
                            onClick = { onItemClick(item.movie) }
                        )
                    }
                }
            }
        }
    }
}

/** Trakt 热门剧集栏目 */
@Composable
private fun TraktTrendingShowSection(
    items: List<TraktTrendingShowResponse>,
    isLoading: Boolean,
    resolvingItemId: Int?,
    totalCount: Int,
    onItemClick: (TraktShow) -> Unit,
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
                text = stringResource(R.string.discover_trakt_trending_shows),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (totalCount > 0) {
                Row(
                    modifier = Modifier
                        .clickable { onViewAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_view_all, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            items.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(items, key = { index, item -> "show_rec_${index}_${item.show.ids.trakt}" }) { _, item ->
                        MovieCard(
                            title = item.show.title,
                            posterPath = item.show.posterPath,
                            year = if (item.show.year > 0) item.show.year.toString() else "",
                            rating = if (item.show.rating > 0) String.format("%.1f", item.show.rating) else null,
                            subtitle = stringResource(R.string.discover_watchers, item.watchers),
                            isResolving = resolvingItemId == item.show.ids.tmdb,
                            onClick = { onItemClick(item.show) }
                        )
                    }
                }
            }
        }
    }
}

/** Trakt 最受期待栏目（电影+剧集混合） */
@Composable
private fun TraktAnticipatedSection(
    anticipatedMovies: List<TraktAnticipatedMovieResponse>,
    anticipatedShows: List<TraktAnticipatedShowResponse>,
    isLoading: Boolean,
    resolvingItemId: Int?,
    totalCount: Int,
    onMovieClick: (TraktMovie) -> Unit,
    onShowClick: (TraktShow) -> Unit,
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
                text = stringResource(R.string.discover_trakt_anticipated),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (totalCount > 0) {
                Row(
                    modifier = Modifier
                        .clickable { onViewAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_view_all, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            anticipatedMovies.isEmpty() && anticipatedShows.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // 先展示电影，再展示剧集
                    itemsIndexed(anticipatedMovies, key = { index, item -> "anticip_m_${index}_${item.movie.ids.trakt}" }) { _, item ->
                        MovieCard(
                            title = item.movie.title,
                            posterPath = item.movie.posterPath,
                            year = if (item.movie.year > 0) item.movie.year.toString() else "",
                            rating = if (item.movie.rating > 0) String.format("%.1f", item.movie.rating) else null,
                            subtitle = stringResource(R.string.discover_list_count, item.list_count),
                            isResolving = resolvingItemId == item.movie.ids.tmdb,
                            onClick = { onMovieClick(item.movie) }
                        )
                    }
                    itemsIndexed(anticipatedShows, key = { index, item -> "anticip_s_${index}_${item.show.ids.trakt}" }) { _, item ->
                        MovieCard(
                            title = item.show.title,
                            posterPath = item.show.posterPath,
                            year = if (item.show.year > 0) item.show.year.toString() else "",
                            rating = if (item.show.rating > 0) String.format("%.1f", item.show.rating) else null,
                            subtitle = stringResource(R.string.discover_list_count, item.list_count),
                            isResolving = resolvingItemId == item.show.ids.tmdb,
                            onClick = { onShowClick(item.show) }
                        )
                    }
                }
            }
        }
    }
}

/** 为你推荐剧集栏目（仅登录用户可见） */
@Composable
private fun TraktShowRecommendationSection(
    items: List<TraktRecommendationShowResponse>,
    isLoading: Boolean,
    resolvingItemId: Int?,
    totalCount: Int,
    onItemClick: (TraktShow) -> Unit,
    onViewAll: () -> Unit
) {
    if (items.isEmpty() && !isLoading) return // 未登录时无数据不显示
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.discover_trakt_recommendations_shows),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (totalCount > 0) {
                Row(
                    modifier = Modifier
                        .clickable { onViewAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_view_all, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            items.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(items, key = { index, item -> "show_rec_${index}_${item.show.ids.trakt}" }) { _, item ->
                        MovieCard(
                            title = item.show.title,
                            posterPath = item.show.posterPath,
                            year = if (item.show.year > 0) item.show.year.toString() else "",
                            rating = if (item.show.rating > 0) String.format("%.1f", item.show.rating) else null,
                            isResolving = resolvingItemId == item.show.ids.tmdb,
                            onClick = { onItemClick(item.show) }
                        )
                    }
                }
            }
        }
    }
}

/** 通用栏目标题 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

/** 社区热门列表全部弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrendingListsAllSheet(
    lists: List<TraktTrendingListResponse>,
    onListClick: (slug: String, listName: String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.discover_trending_lists),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(lists) { _, listResponse ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onListClick(listResponse.list.ids.slug, listResponse.list.name) }) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = listResponse.list.name,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.discover_list_meta, listResponse.list.item_count, listResponse.list.user?.username ?: "", listResponse.like_count),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (listResponse.list.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = listResponse.list.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
