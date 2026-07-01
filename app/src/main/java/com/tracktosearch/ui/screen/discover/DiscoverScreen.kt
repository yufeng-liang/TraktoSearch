package com.tracktosearch.ui.screen.discover

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
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
    // 延迟加载 Trakt 栏目，避免与首屏豆瓣/TMDB 竞争网络带宽
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(800)
        viewModel.loadRemainingSections()
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
                                        val tabPadding = 12.dp
                                        val tabHeight = 30.dp
                                        // 用 TextMeasurer 同步测量文字宽度，避免 onTextLayout 异步回调导致切回页面时宽度跳变
                                        val textMeasurer = rememberTextMeasurer()
                                        val dayTextWidthPx = remember(dayLabel) {
                                            textMeasurer.measure(
                                                text = dayLabel,
                                                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            ).size.width
                                        }
                                        val weekTextWidthPx = remember(weekLabel) {
                                            textMeasurer.measure(
                                                text = weekLabel,
                                                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            ).size.width
                                        }
                                        val density = LocalDensity.current
                                        val dayTabWidthDp = with(density) { dayTextWidthPx.toDp() + tabPadding * 2 }
                                        val weekTabWidthDp = with(density) { weekTextWidthPx.toDp() + tabPadding * 2 }
                                        val capsuleWidth = dayTabWidthDp + weekTabWidthDp
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
                                                .width(capsuleWidth)
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            // 滑块指示器
                                            Box(
                                                modifier = Modifier
                                                    .offset(x = indicatorOffset)
                                                    .width(indicatorWidth)
                                                    .fillMaxHeight()
                                                    .padding(3.dp)
                                                    .clip(RoundedCornerShape(5.dp))
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                            // 文字选项
                                            Row {
                                                Box(
                                                    modifier = Modifier
                                                        .width(dayTabWidthDp)
                                                        .fillMaxHeight()
                                                        .clickable(
                                                            interactionSource = remember { MutableInteractionSource() },
                                                            indication = null
                                                        ) { viewModel.switchTrendingTimeWindow("day") },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = dayLabel,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isDay) FontWeight.Medium else FontWeight.Normal,
                                                        color = if (isDay)
                                                            MaterialTheme.colorScheme.onPrimary
                                                        else
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .width(weekTabWidthDp)
                                                        .fillMaxHeight()
                                                        .clickable(
                                                            interactionSource = remember { MutableInteractionSource() },
                                                            indication = null
                                                        ) { viewModel.switchTrendingTimeWindow("week") },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = weekLabel,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (!isDay) FontWeight.Medium else FontWeight.Normal,
                                                        color = if (!isDay)
                                                            MaterialTheme.colorScheme.onPrimary
                                                        else
                                                            MaterialTheme.colorScheme.onSurfaceVariant
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

// 拆分说明：
// 原 DiscoverScreen.kt（约1683行）已拆分为以下文件：
// - DiscoverScreen.kt：主函数 DiscoverScreen
// - DiscoverComponents.kt：MovieCard, ErrorRetryRow, EmptyRow, SectionHeader, doubanCategoryLabel
// - DiscoverSections.kt：TmdbMovieSection, TraktRecommendationSection, TraktTrendingMovieSection, TraktTrendingShowSection, TraktAnticipatedSection, TraktShowRecommendationSection
// - DiscoverSheets.kt：TmdbAllSheet, TraktMovieAllSheet, TraktShowAllSheet, TraktAnticipatedAllSheet, TrendingListsAllSheet
