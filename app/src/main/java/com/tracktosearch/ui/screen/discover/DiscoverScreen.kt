package com.tracktosearch.ui.screen.discover

import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.local.DiscoverSectionStorage
import com.tracktosearch.ui.animation.fadeSlideIn
import com.tracktosearch.ui.component.LocalActivePosterTmdbId
import com.tracktosearch.ui.component.LocalActivePosterClickSetter
import com.tracktosearch.ui.component.LocalActivePosterClickToken
import com.tracktosearch.ui.component.LocalActivePosterTmdbIdSetter
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
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


@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun DiscoverScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onListClick: (listId: Int, listName: String) -> Unit = { _, _ -> },
    onFilterDiscoverClick: () -> Unit = {},
    onDoubanLoginClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sectionConfigs by viewModel.sectionConfigs.collectAsStateWithLifecycle()
    val watchlistWatchedIds by viewModel.watchlistWatchedIds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 共享元素转场 scope（用于底部入口卡片和右上角筛选图标与影视筛选页配对）
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    // 记录进入影视筛选页的入口来源（"card"=底部卡片 / "icon"=右上角图标），返回时据此决定哪个入口参与转场
    var activeFilterEntry by rememberSaveable { mutableStateOf<String?>(null) }
    // 当前活跃的海报 tmdbId(-1=初始无活跃 / 具体值=被点击的海报),确保同页面多栏目相同海报只有被点击的参与转场
    var activePosterTmdbId by rememberSaveable { mutableStateOf(-1) }
    // 每次点击递增的 token,用于精确匹配被点击的卡片实例(避免同 tmdbId 海报跨栏目飘错)
    var activeClickToken by rememberSaveable { mutableStateOf(0) }

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
    // 页面恢复可见时刷新想看/已看缓存快照（从详情页标记后返回时触发）
    LifecycleResumeEffect(Unit) {
        viewModel.refreshWatchlistWatchedIds()
        onPauseOrDispose { }
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

    CompositionLocalProvider(
        LocalActivePosterTmdbId provides activePosterTmdbId,
        LocalActivePosterClickSetter provides { id ->
            activePosterTmdbId = id
            activeClickToken += 1
            activeClickToken  // 返回新 token 给调用方保存
        },
        LocalActivePosterClickToken provides activeClickToken
    ) {
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
                        bottom = 112.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 顶部 Hero 分类快捷入口
                item(key = "discover_hero_categories") {
                    val heroCategories = listOf(
                        HeroCategory(
                            id = "tmdb-popular",
                            title = stringResource(R.string.discover_trending),
                            count = uiState.tmdbPopularMovies.size,
                            gradient = Brush.linearGradient(
                                listOf(Color(0xFFE8534F), Color(0xFFF4A460))
                            ),
                            onClick = { showPopularAll = true }
                        ),
                        HeroCategory(
                            id = "tmdb-upcoming",
                            title = stringResource(R.string.discover_upcoming),
                            count = uiState.tmdbUpcomingMovies.size,
                            gradient = Brush.linearGradient(
                                listOf(Color(0xFF4A6FA5), Color(0xFF8A7AC4))
                            ),
                            onClick = { showUpcomingAll = true }
                        ),
                        HeroCategory(
                            id = "trakt-recommendations",
                            title = stringResource(R.string.discover_recommended),
                            count = uiState.traktRecommendations.size,
                            gradient = Brush.linearGradient(
                                listOf(Color(0xFF6B3FA0), Color(0xFFC88AB8))
                            ),
                            onClick = { showRecommendationsAll = true }
                        ),
                        HeroCategory(
                            id = "douban-hot",
                            title = stringResource(R.string.discover_douban_new_movies),
                            count = uiState.doubanHotCategories.sumOf { it.items.size },
                            gradient = Brush.linearGradient(
                                listOf(Color(0xFF2E7D52), Color(0xFF4DB6AC))
                            ),
                            onClick = {
                                val firstCategory = uiState.doubanHotCategories.firstOrNull()
                                showDoubanAllDialog = firstCategory?.id ?: "douban-movie"
                            }
                        ),
                        HeroCategory(
                            id = "trakt-lists",
                            title = stringResource(R.string.discover_trending_lists),
                            count = uiState.trendingLists.size,
                            gradient = Brush.linearGradient(
                                listOf(Color(0xFF374151), Color(0xFF60A5FA))
                            ),
                            onClick = { showTrendingListsAll = true }
                        )
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        itemsIndexed(heroCategories, key = { _, category -> category.id }) { index, category ->
                            Box(modifier = Modifier.fadeSlideIn(index)) {
                                CategoryHeroCard(
                                    title = category.title,
                                    count = category.count,
                                    gradient = category.gradient,
                                    onClick = category.onClick
                                )
                            }
                        }
                    }
                }

                // 根据用户设置（显示/隐藏 + 排序）渲染各栏目
                sectionConfigs.filter { it.visible }.forEach { config ->
                    when (config.id) {
                        // 豆瓣「猜你喜欢」（电影/电视剧，需登录豆瓣）
                        DiscoverSectionStorage.SECTION_ID_DOUBAN_RECOMMEND -> {
                            item(key = config.id) {
                                DoubanRecommendSection(
                                    state = uiState.doubanRecommendState,
                                    resolvingItemId = uiState.resolvingRecommendItemId,
                                    onItemClick = { item ->
                                        val isMovieTab = uiState.doubanRecommendState.let {
                                            it is DoubanRecommendState.Success && it.currentTab == RecommendTab.MOVIE
                                        }
                                        viewModel.resolveAndNavigateRecommend(item, isMovieTab) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                            if (isMovieTab) {
                                                onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                                            } else {
                                                onShowClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                                            }
                                        }
                                    },
                                    onRetry = { viewModel.retryDoubanRecommend() },
                                    onLoginClick = onDoubanLoginClick,
                                    onTabSelected = { tab -> viewModel.switchRecommendTab(tab) }
                                )
                            }
                        }
                        // 豆瓣热榜各榜单
                        "douban-movie", "douban-weekly", "douban-top250", "douban-nowplaying" -> {
                            val category = uiState.doubanHotCategories.find { it.id == config.id }
                            if (category != null) {
                                item(key = config.id) {
                                    DoubanHotCategorySection(
                                        category = category,
                                        resolvingItemId = uiState.resolvingItemId,
                                        onItemClick = { item ->
                                            viewModel.resolveAndNavigate(item) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                                onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
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
                                        // 今日/本周切换条（统一组件）
                                        val isDay = uiState.trendingTimeWindow == "day"
                                        CapsuleTabSelector(
                                            tabs = listOf(
                                                stringResource(R.string.discover_trending_day),
                                                stringResource(R.string.discover_trending_week)
                                            ),
                                            selectedIndex = if (isDay) 0 else 1,
                                            onTabSelected = { index ->
                                                viewModel.switchTrendingTimeWindow(if (index == 0) "day" else "week")
                                            }
                                        )
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
                                        watchlistWatchedIds = watchlistWatchedIds,
                                        onItemClick = { movie ->
                                            viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                                onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
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
                                    watchlistWatchedIds = watchlistWatchedIds,
                                    onItemClick = { movie ->
                                        viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
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
                                    watchlistWatchedIds = watchlistWatchedIds,
                                    onItemClick = { movie ->
                                        viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
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
                                    watchlistWatchedIds = watchlistWatchedIds,
                                    error = uiState.traktTrendingMoviesError,
                                    onItemClick = { movie ->
                                        viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                                        }
                                    },
                                    onViewAll = { showTrendingMoviesAll = true },
                                    onRetry = { viewModel.loadTraktData() }
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
                                    watchlistWatchedIds = watchlistWatchedIds,
                                    error = uiState.traktTrendingShowsError,
                                    onItemClick = { show ->
                                        viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                            onShowClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                                        }
                                    },
                                    onViewAll = { showTrendingShowsAll = true },
                                    onRetry = { viewModel.loadTraktData() }
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
                                    watchlistWatchedIds = watchlistWatchedIds,
                                    error = uiState.traktAnticipatedError,
                                    onMovieClick = { movie ->
                                        viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                                        }
                                    },
                                    onShowClick = { show ->
                                        viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                            onShowClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                                        }
                                    },
                                    onViewAll = { showAnticipatedAll = true },
                                    onRetry = { viewModel.loadTraktData() }
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
                                    watchlistWatchedIds = watchlistWatchedIds,
                                    error = uiState.traktShowRecommendationsError,
                                    onItemClick = { show ->
                                        viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                            onShowClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                                        }
                                    },
                                    onViewAll = { showShowRecsAll = true },
                                    onRetry = { viewModel.loadTraktData() }
                                )
                            }
                        }
                        // 社区热门列表
                        "trakt-lists" -> {
                            item(key = "trakt_lists") {
                                Column {
                                    com.tracktosearch.ui.component.SectionHeader(
                                        title = stringResource(R.string.discover_trending_lists),
                                        actionText = if (uiState.trendingLists.isNotEmpty()) stringResource(R.string.common_view_all) else null,
                                        onActionClick = if (uiState.trendingLists.isNotEmpty()) { { showTrendingListsAll = true } } else null
                                    )
                                    val listsError = uiState.trendingListsError
                                    if (uiState.isLoadingTraktLists) {
                                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        }
                                    } else if (listsError != null) {
                                        ErrorRetryRow(error = listsError, onRetry = { viewModel.loadTraktLists() })
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
                                            uiState.trendingLists.take(5).forEachIndexed { index, listResponse ->
                                                // 社区列表卡片与详情页标题栏整体配对（sharedBounds 转场）
                                                val listCardModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
                                                    with(sharedTransitionScope) {
                                                        Modifier.sharedBounds(
                                                            sharedContentState = rememberSharedContentState(key = "trakt-list-card-${listResponse.list.ids.trakt}"),
                                                            animatedVisibilityScope = animatedVisibilityScope
                                                        )
                                                    }
                                                } else { Modifier }
                                                Box(modifier = Modifier.fillMaxWidth().then(listCardModifier).fadeSlideIn(index)) {
                                                    Card(
    modifier = Modifier.fillMaxWidth().clickable { onListClick(listResponse.list.ids.trakt, listResponse.list.name) },
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
) {
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
                                            }
                                            if (uiState.trendingLists.isNotEmpty()) {
                                                TextButton(
                                                    onClick = { showTrendingListsAll = true },
                                                    modifier = Modifier.align(Alignment.End)
                                                ) {
                                                    Text(stringResource(R.string.common_view_all))
                                                    Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, contentDescription = null, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 底部：去影视筛选页入口卡片
                item(key = "discover_filter_entry") {
                    // 当从底部卡片进入筛选页时（activeFilterEntry == "card"），给卡片加 sharedElement 与筛选页根容器配对
                    val cardModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && activeFilterEntry == "card" && LocalSharedTransitionEnabled.current) {
                        with(sharedTransitionScope) {
                            Modifier
                                .fillMaxWidth()
                                .sharedElement(
                                    rememberSharedContentState(key = "discover-filter-entry-card"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                        }
                    } else {
                        Modifier.fillMaxWidth()
                    }
                    Box(
                        modifier = cardModifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                    )
                                )
                            )
                            .clickable {
                                activeFilterEntry = "card"
                                onFilterDiscoverClick()
                            }
                            .padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.discover_filter_more_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            Text(
                                text = stringResource(R.string.discover_filter_more_button),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.White
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 当从右上角图标进入筛选页时（activeFilterEntry == "icon"），给图标加 sharedElement 与筛选页返回箭头配对
                        val iconModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && activeFilterEntry == "icon" && LocalSharedTransitionEnabled.current) {
                            with(sharedTransitionScope) {
                                Modifier.sharedElement(
                                    rememberSharedContentState(key = "discover-filter-entry-icon"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                            }
                        } else {
                            Modifier
                        }
                        IconButton(
                            onClick = {
                                activeFilterEntry = "icon"
                                onFilterDiscoverClick()
                            },
                            modifier = iconModifier
                        ) {
                            Icon(
                                Icons.Rounded.FilterList,
                                contentDescription = stringResource(R.string.discover_filter_title),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { showDiscoverSectionsDialog = true }) {
                            Icon(
                                Icons.Rounded.FormatListNumbered,
                                contentDescription = stringResource(R.string.settings_discover_sections),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
    } // CompositionLocalProvider

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
                        viewModel.resolveAndNavigate(item) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
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
            watchlistWatchedIds = watchlistWatchedIds,
            onItemClick = { movie ->
                viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                    showPopularAll = false
                    onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
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
            watchlistWatchedIds = watchlistWatchedIds,
            onItemClick = { movie ->
                viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                    showUpcomingAll = false
                    onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
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
            watchlistWatchedIds = watchlistWatchedIds,
            onItemClick = { movie ->
                viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                    showRecommendationsAll = false
                    onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
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
            watchlistWatchedIds = watchlistWatchedIds,
            onItemClick = { movie ->
                viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                    showTrendingMoviesAll = false
                    onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
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
            watchlistWatchedIds = watchlistWatchedIds,
            onItemClick = { show ->
                viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                    showTrendingShowsAll = false
                    onShowClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
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
            watchlistWatchedIds = watchlistWatchedIds,
            onMovieClick = { movie ->
                viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                    showAnticipatedAll = false
                    onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                }
            },
            onShowClick = { show ->
                viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                    showAnticipatedAll = false
                    onShowClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
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
            watchlistWatchedIds = watchlistWatchedIds,
            onItemClick = { show ->
                viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                    showShowRecsAll = false
                    onShowClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                }
            },
            onDismiss = { showShowRecsAll = false }
        )
    }

    // 社区热门列表全部弹窗
    if (showTrendingListsAll) {
        TrendingListsAllSheet(
            lists = uiState.trendingLists,
            onListClick = { listId, listName ->
                showTrendingListsAll = false
                onListClick(listId, listName)
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

/**
 * Hero 分类项数据类
 */
private data class HeroCategory(
    val id: String,
    val title: String,
    val count: Int,
    val gradient: Brush,
    val onClick: () -> Unit
)

/**
 * Hero 分类卡片
 *
 * 用于发现页顶部分类快捷入口，大圆角 + 渐变背景。
 */
@Composable
private fun CategoryHeroCard(
    title: String,
    count: Int?,
    gradient: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(150.dp)
            .height(95.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(gradient)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomStart),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            if (count != null && count > 0) {
                Text(
                    text = stringResource(R.string.discover_view_all, count),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

// 拆分说明：
// 原 DiscoverScreen.kt（约1683行）已拆分为以下文件：
// - DiscoverScreen.kt：主函数 DiscoverScreen
// - DiscoverComponents.kt：MovieCard, ErrorRetryRow, EmptyRow, SectionHeader, doubanCategoryLabel
// - DiscoverSections.kt：TmdbMovieSection, TraktRecommendationSection, TraktTrendingMovieSection, TraktTrendingShowSection, TraktAnticipatedSection, TraktShowRecommendationSection
// - DiscoverSheets.kt：TmdbAllSheet, TraktMovieAllSheet, TraktShowAllSheet, TraktAnticipatedAllSheet, TrendingListsAllSheet
