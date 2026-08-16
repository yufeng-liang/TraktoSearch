package com.tracktosearch.ui.screen.discover

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.local.DiscoverSectionStorage
import com.tracktosearch.ui.animation.fadeSlideIn
import com.tracktosearch.ui.component.AppIconButton
import com.tracktosearch.ui.component.AppVisualSurface
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.LocalActivePosterClickSetter
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.LocalActivePosterClickToken
import com.tracktosearch.ui.component.LocalActivePosterTmdbId
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.rememberCachedPosterAmbientColor
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.VisualSurfaceKind
import com.tracktosearch.ui.screen.search.DoubanHotAllSheet
import com.tracktosearch.ui.screen.search.DoubanHotCategorySection
import com.tracktosearch.ui.screen.settings.DiscoverSectionsDialog
import com.tracktosearch.ui.screen.settings.SettingsViewModel
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.ToastEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun DiscoverScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onListClick: (listId: Int, listName: String) -> Unit = { _, _ -> },
    onFilterDiscoverClick: () -> Unit = {},
    onDoubanLoginClick: () -> Unit = {},
    onTraktLoginClick: () -> Unit = {},
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

    ToastEffect(viewModel.toastEvent)
    // 延迟加载 Trakt 栏目，避免与首屏豆瓣/TMDB 竞争网络带宽
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(800)
        viewModel.loadRemainingSections()
    }
    // 页面恢复可见时刷新想看/已看缓存快照（从详情页标记后返回时触发）
    LifecycleResumeEffect(Unit) {
        viewModel.refreshWatchlistWatchedIds()
        viewModel.refreshDoubanRecommendOnResume()
        onPauseOrDispose { }
    }
    var showDoubanAllDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showPopularAll by rememberSaveable { mutableStateOf(false) }
    var showUpcomingAll by rememberSaveable { mutableStateOf(false) }
    var showRecommendationsAll by rememberSaveable { mutableStateOf(false) }
    var showTrendingMoviesAll by rememberSaveable { mutableStateOf(false) }
    var showTrendingShowsAll by rememberSaveable { mutableStateOf(false) }
    var showAnticipatedAll by rememberSaveable { mutableStateOf(false) }
    var showShowRecsAll by rememberSaveable { mutableStateOf(false) }
    var showTrendingListsAll by rememberSaveable { mutableStateOf(false) }
    var showDiscoverSectionsDialog by rememberSaveable { mutableStateOf(false) }

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
    // HazeMaterials.thin() 读取 MaterialTheme.colorScheme，是 @Composable 函数，不能用 remember 缓存
    // DiscoverScreen 仅在 uiState 变化时重组，主题不变时 HazeStyle 开销可接受
    val discoverHazeStyle = HazeMaterials.thin()
    // rememberSaveable + Saver：进入详情页（MAIN 整体销毁）返回后恢复原滚动位置，
    // 不再回到顶部（与 Watchlist 的 grid 状态策略一致）
    val discoverListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val discoverHasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = discoverListState.layoutInfo.visibleItemsInfo.firstOrNull()?.index,
                firstVisibleItemScrollOffsetPx = discoverListState.firstVisibleItemScrollOffset
            )
        }
    }
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

    val isDark = isAppDarkTheme()
    // 派生聚合仅依赖 uiState：包进 remember 避免滚动/点击等无关重组时反复 sumOf/flatMap 跨 10 个列表计算
    val discoverContentCount = remember(uiState) {
        uiState.doubanHotCategories.sumOf { it.items.size } +
            uiState.tmdbPopularMovies.size +
            uiState.tmdbUpcomingMovies.size +
            uiState.traktRecommendations.size +
            uiState.traktTrendingMovies.size +
            uiState.traktTrendingShows.size +
            uiState.traktAnticipatedMovies.size +
            uiState.traktAnticipatedShows.size +
            uiState.traktShowRecommendations.size +
            uiState.trendingLists.size
    }
    // 海报路径列表同样只在 uiState 变化时重建；内容未变时保持实例稳定，
    // 使 rememberCachedPosterAmbientColor 内部 remember(posterUrls) 命中缓存
    val discoverPosterPaths = remember(uiState) {
        listOf(
            uiState.doubanHotCategories.flatMap { category -> category.items.mapNotNull { it.cover } },
            uiState.tmdbPopularMovies.mapNotNull { it.poster_path },
            uiState.tmdbUpcomingMovies.mapNotNull { it.poster_path },
            uiState.traktRecommendations.mapNotNull { it.posterPath },
            uiState.traktTrendingMovies.mapNotNull { it.movie.posterPath },
            uiState.traktTrendingShows.mapNotNull { it.show.posterPath },
            uiState.traktAnticipatedMovies.mapNotNull { it.movie.posterPath },
            uiState.traktAnticipatedShows.mapNotNull { it.show.posterPath },
            uiState.traktShowRecommendations.mapNotNull { it.show.posterPath }
        ).flatten()
    }
    val discoverAmbientColor = rememberCachedPosterAmbientColor(
        posterUrls = discoverPosterPaths,
        fallback = MaterialTheme.colorScheme.background
    )
    val discoverLoadingCount = remember(uiState) {
        uiState.doubanHotCategories.count { it.isLoading } +
            listOf(
                uiState.isLoadingPopular,
                uiState.isLoadingUpcoming,
                uiState.isLoadingRecommendations,
                uiState.isLoadingTrakt,
                uiState.isLoadingTraktLists
            ).count { it }
    }
    val discoverGlassScene = glassSceneForContent(
        contentCount = discoverContentCount,
        readabilityDemand = when {
            discoverLoadingCount > 0 -> 0.78f
            discoverContentCount == 0 -> 0.30f
            else -> 0.62f
        },
        ambientColor = discoverAmbientColor,
        contentCapacity = 72,
        loadingCount = discoverLoadingCount,
        loadingItemWeight = 4
    )
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent
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
                // 顶部 Hero 分类快捷入口：由栏目设置（显示/隐藏 + 排序）驱动
                item(key = "discover_hero_categories") {
                    // 缓存按主题生成的 5 个渐变 Brush，避免每次重组创建新实例
                    val popularGradient = remember(isDark) {
                        Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(Color(0xFFE05A2D), Color(0xFFD89C2C))
                            } else {
                                listOf(Color(0xFFF06A2F), Color(0xFFE6AA35))
                            },
                            start = Offset(0f, Float.POSITIVE_INFINITY),
                            end = Offset(Float.POSITIVE_INFINITY, 0f)
                        )
                    }
                    val upcomingGradient = remember(isDark) {
                        Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(Color(0xFFC26391), Color(0xFF9968CC))
                            } else {
                                listOf(Color(0xFFCB6C98), Color(0xFF9C6BD1))
                            },
                            start = Offset(0f, Float.POSITIVE_INFINITY),
                            end = Offset(Float.POSITIVE_INFINITY, 0f)
                        )
                    }
                    val recommendGradient = remember(isDark) {
                        Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(Color(0xFF5587E0), Color(0xFF36A9C4))
                            } else {
                                listOf(Color(0xFF5C89E0), Color(0xFF36AFC7))
                            },
                            start = Offset(0f, Float.POSITIVE_INFINITY),
                            end = Offset(Float.POSITIVE_INFINITY, 0f)
                        )
                    }
                    val doubanGradient = remember(isDark) {
                        Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(Color(0xFF43B77A), Color(0xFF3CABBE))
                            } else {
                                listOf(Color(0xFF42B87C), Color(0xFF43A9C2))
                            },
                            start = Offset(0f, Float.POSITIVE_INFINITY),
                            end = Offset(Float.POSITIVE_INFINITY, 0f)
                        )
                    }
                    val listsGradient = remember(isDark) {
                        Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(Color(0xFFD86182), Color(0xFFB96D62))
                            } else {
                                listOf(Color(0xFFE56B89), Color(0xFFC67A6B))
                            },
                            start = Offset(0f, Float.POSITIVE_INFINITY),
                            end = Offset(Float.POSITIVE_INFINITY, 0f)
                        )
                    }
                    val doubanMovieCategory = uiState.doubanHotCategories
                        .firstOrNull { it.id == "douban-movie" }
                    // 栏目 id -> Hero 卡片定义（标题/渐变/点击/数据），仅包含需要展示为 Hero 的栏目
                    val heroCategoryDefs = mapOf<String, HeroCategory>(
                        "tmdb-popular" to HeroCategory(
                            id = "tmdb-popular",
                            title = stringResource(R.string.discover_trending),
                            count = uiState.tmdbPopularMovies.size,
                            gradient = popularGradient,
                            onClick = { showPopularAll = true }
                        ),
                        "tmdb-upcoming" to HeroCategory(
                            id = "tmdb-upcoming",
                            title = stringResource(R.string.discover_upcoming),
                            count = uiState.tmdbUpcomingMovies.size,
                            gradient = upcomingGradient,
                            onClick = { showUpcomingAll = true }
                        ),
                        "trakt-recommendations" to HeroCategory(
                            id = "trakt-recommendations",
                            title = stringResource(R.string.discover_recommended),
                            count = uiState.traktRecommendations.size,
                            gradient = recommendGradient,
                            onClick = { showRecommendationsAll = true }
                        ),
                        "douban-movie" to HeroCategory(
                            id = "douban-movie",
                            title = stringResource(R.string.discover_douban_new_movies),
                            count = doubanMovieCategory?.let { category ->
                                category.total.takeIf { it > 0 } ?: category.items.size
                            } ?: 0,
                            gradient = doubanGradient,
                            onClick = { showDoubanAllDialog = "douban-movie" }
                        ),
                        "trakt-lists" to HeroCategory(
                            id = "trakt-lists",
                            title = stringResource(R.string.discover_trending_lists),
                            count = uiState.trendingLists.size,
                            gradient = listsGradient,
                            onClick = { showTrendingListsAll = true }
                        )
                    )
                    // 按栏目设置顺序过滤可见且存在 Hero 定义的栏目，实现 Hero 卡片排序与显隐联动
                    // 依赖 sectionConfigs（显隐/顺序）与 uiState（各栏目 count），包进 remember 避免无关重组时重建列表
                    val heroCategories = remember(sectionConfigs, uiState) {
                        sectionConfigs
                            .filter { it.visible && it.id in heroCategoryDefs }
                            .mapNotNull { heroCategoryDefs[it.id] }
                    }
                    if (heroCategories.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            heroCategories.forEachIndexed { index, category ->
                                Box(modifier = Modifier.fadeSlideIn(index)) {
                                    CategoryHeroCard(
                                        title = category.title,
                                        count = category.count,
                                        gradient = category.gradient,
                                        onClick = category.onClick,
                                        isDark = isDark
                                    )
                                }
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
                                // 今日/本周各自独立的滚动状态，互不影响；
                                // rememberSaveable：item 滚出视口回收或进详情页返回后位置不重置
                                val trendingDayListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
                                val trendingWeekListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
                                val trendingListState = if (uiState.trendingTimeWindow == "day") trendingDayListState else trendingWeekListState
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.discover_trending),
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
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
                                            val actionColor = MaterialTheme.colorScheme.primary
                                            Row(
                                                modifier = Modifier
                                                    .clickable { showPopularAll = true }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.discover_view_all, uiState.tmdbPopularMovies.size),
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = actionColor
                                                )
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                                                    contentDescription = stringResource(R.string.content_desc_view_all),
                                                    modifier = Modifier.size(12.dp),
                                                    tint = actionColor
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
                                        onViewAll = { showPopularAll = true },
                                        lazyListState = trendingListState
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
                                    isLoggedIn = uiState.traktRecommendationsLoggedIn,
                                    watchlistWatchedIds = watchlistWatchedIds,
                                    onItemClick = { movie ->
                                        viewModel.navigateTraktMovie(movie) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                            onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                                        }
                                    },
                                    onRetry = { viewModel.loadTraktRecommendations() },
                                    onViewAll = { showRecommendationsAll = true },
                                    onLoginClick = onTraktLoginClick
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
                                    isLoggedIn = uiState.traktShowRecommendationsLoggedIn,
                                    watchlistWatchedIds = watchlistWatchedIds,
                                    error = uiState.traktShowRecommendationsError,
                                    onItemClick = { show ->
                                        viewModel.navigateTraktShow(show) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                                            onShowClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                                        }
                                    },
                                    onViewAll = { showShowRecsAll = true },
                                    onRetry = { viewModel.loadTraktData() },
                                    onLoginClick = onTraktLoginClick
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
                                                val interactionSource = remember { MutableInteractionSource() }
                                                val isPressed by interactionSource.collectIsPressedAsState()
                                                val cardScale by animateFloatAsState(
                                                    targetValue = if (isPressed) 0.98f else 1f,
                                                    label = "trakt_list_card_scale_${index}"
                                                )
                                                Box(modifier = Modifier.fillMaxWidth().then(listCardModifier).fadeSlideIn(index)) {
                                                    AppVisualSurface(
                                                        kind = VisualSurfaceKind.Content,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .scale(cardScale)
                                                            .clickable(
                                                                interactionSource = interactionSource,
                                                                indication = null,
                                                                onClick = { onListClick(listResponse.list.ids.trakt, listResponse.list.name) }
                                                            ),
                                                        shape = RoundedCornerShape(18.dp),
                                                        backgroundColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                                        borderColor = if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
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
                                                                    color = MaterialTheme.colorScheme.onSurface,
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

                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 底部：去影视筛选页入口卡片
                item(key = "discover_filter_entry") {
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val cardScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.97f else 1f,
                        label = "discover_filter_entry_scale"
                    )
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
                    val shape = RoundedCornerShape(20.dp)
                    // 浅玫瑰紫渐变（与「去豆瓣登录」卡片样式统一，仅渐变配色不同）
                    val gradient = remember(isDark) {
                        Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(Color(0xFFA85BC2), Color(0xFFD45F7B))
                            } else {
                                listOf(Color(0xFFAD65BF), Color(0xFFE26C87))
                            },
                            start = Offset(0f, Float.POSITIVE_INFINITY),
                            end = Offset(Float.POSITIVE_INFINITY, 0f)
                        )
                    }
                    AppVisualSurface(
                        kind = VisualSurfaceKind.Content,
                        modifier = cardModifier
                            .scale(cardScale)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                activeFilterEntry = "card"
                                onFilterDiscoverClick()
                            },
                        shape = shape,
                        backgroundColor = Color.Transparent,
                        borderColor = Color.Transparent
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(gradient)
                                .border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.30f),
                                    shape = shape
                                )
                                .padding(24.dp),
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
            // 毛玻璃吸顶标题栏（仅 thin 模糊，不叠 surface 背景，更通透）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeTopBar(
                        state = discoverHazeState,
                        style = discoverHazeStyle,
                        blurRadius = 24.dp,
                        isContentUnderTopBar = discoverHasContentUnderTopBar,
                        scene = discoverGlassScene
                    )
            ) {
                Column {
                    Spacer(modifier = Modifier.statusBarsPadding())
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.discover_title),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // 圆形操作按钮在 Glass 下使用轻量光学层，在 Blur 下沿用拟态按钮。
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
                        AppIconButton(
                            onClick = {
                                activeFilterEntry = "icon"
                                onFilterDiscoverClick()
                            },
                            modifier = iconModifier,
                            isDark = isDark,
                            hazeState = discoverHazeState,
                            role = GlassSurfaceRole.CircularControl,
                            scene = discoverGlassScene
                        ) {
                            Icon(
                                Icons.Rounded.FilterList,
                                contentDescription = stringResource(R.string.discover_filter_title),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        AppIconButton(
                            onClick = { showDiscoverSectionsDialog = true },
                            isDark = isDark,
                            hazeState = discoverHazeState,
                            role = GlassSurfaceRole.CircularControl,
                            scene = discoverGlassScene
                        ) {
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

    // 趋势电影全部弹窗
    if (showPopularAll) {
        TmdbAllSheet(
            title = stringResource(R.string.discover_trending),
            items = uiState.popularAllItems,
            isLoading = uiState.isLoadingPopularAll,
            hasMore = uiState.popularAllHasMore,
            currentPage = uiState.popularAllPage,
            watchlistWatchedIds = watchlistWatchedIds,
            selectedTimeWindow = uiState.trendingTimeWindow,
            onTimeWindowChange = { timeWindow ->
                viewModel.switchTrendingTimeWindow(timeWindow)
            },
            onItemClick = { movie ->
                viewModel.resolveTmdbAndNavigate(movie.id, movie.title) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                    showPopularAll = false
                    onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                }
            },
            onLoadMore = { viewModel.loadPopularAll(page = uiState.popularAllPage + 1) },
            onDismiss = { showPopularAll = false },
            errorMessage = uiState.popularAllError,
            onRetry = { viewModel.loadPopularAll(page = uiState.popularAllPage + 1) }
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
            onDismiss = { showUpcomingAll = false },
            errorMessage = uiState.upcomingAllError,
            onRetry = { viewModel.loadUpcomingAll(page = uiState.upcomingAllPage + 1) }
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
            onDismiss = { showRecommendationsAll = false },
            errorMessage = uiState.recommendationsAllError,
            onRetry = { viewModel.loadRecommendationsAll(page = uiState.recommendationsAllPage + 1) }
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
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    val shape = RoundedCornerShape(22.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "category_hero_scale"
    )
    AppVisualSurface(
        kind = VisualSurfaceKind.Content,
        modifier = modifier
            .width(160.dp)
            .height(75.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = shape,
        backgroundColor = Color.Transparent,
        borderColor = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.32f),
                    shape = shape
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            if (count != null && count > 0) {
                Text(
                    text = stringResource(R.string.discover_view_all, count),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp
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
