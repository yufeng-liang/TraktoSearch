package com.tracktosearch.ui.screen.detail

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.AppErrorVariant
import com.tracktosearch.ui.component.AppPullToRefreshIndicator
import com.tracktosearch.ui.component.DetailTopBarIcon
import com.tracktosearch.ui.component.EmptyStateCard
import com.tracktosearch.ui.component.LoadMoreFooter
import com.tracktosearch.ui.component.LoadMoreFooterState
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalBackdrop
import com.tracktosearch.ui.component.LocalFullscreenSharedElement
import com.tracktosearch.ui.component.fullscreenSharedElementKey
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.MovieCardSkeleton
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.NeumorphicIconButtonStyle
import com.tracktosearch.ui.component.ResourceItemCard
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.detailTopBarIconColor
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.rememberAppPullToRefreshState
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.screen.ai.AiSceneEvent
import com.tracktosearch.ui.screen.ai.AiSpriteAnchor
import com.tracktosearch.ui.screen.ai.AiSpriteMotion
import com.tracktosearch.ui.screen.ai.AiSpriteViewModel
import com.tracktosearch.ui.screen.ai.automaticSpriteArt
import com.tracktosearch.ui.screen.ai.rememberSharedAiSpriteViewModel
import com.tracktosearch.ui.screen.ai.sceneArtFor
import com.tracktosearch.ui.screen.ai.shouldShowWatchlistAddedScene
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.ToastEffect
import com.tracktosearch.ui.util.copyResourceLink
import com.tracktosearch.ui.util.openResourceLink
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.component.SharedOrigin
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun DetailScreen(
    traktId: Int,
    tmdbId: Int,
    title: String,
    mediaType: MediaType,
    year: Int? = null,
    imdbId: String = "",
    traktRating: Double = 0.0,
    initialInWatchlist: Boolean = false,
    initialIsWatched: Boolean = false,
    doubanId: String? = null,
    /** 首帧种子海报：列表卡片已知的海报 URL，用于消除进入详情页时的空白期 */
    seedPosterUrl: String? = null,
    onBack: (watchlistChanged: Boolean, watchedChanged: Boolean) -> Unit = { _, _ -> },
    onPersonClick: (personId: Int, personName: String, profileUrl: String?, avatarColor: Color?) -> Unit = { _, _, _, _ -> },
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit = { _, _, _, _, _ -> },
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit = { _, _, _, _, _ -> },
    onTraktLogin: () -> Unit = {},
    onDoubanLogin: () -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spriteViewModel: AiSpriteViewModel = rememberSharedAiSpriteViewModel()
    val spriteState by spriteViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    var showRatingDialog by remember { mutableStateOf(false) }
    var detailHeaderBounds by remember { mutableStateOf<Rect?>(null) }
    var showWatchlistScene by remember { mutableStateOf(false) }
    var handledWatchlistRevision by remember(traktId, tmdbId) { mutableStateOf(0L) }

    LaunchedEffect(uiState.watchlistAddedRevision, traktId, tmdbId) {
        if (shouldShowWatchlistAddedScene(handledWatchlistRevision, uiState.watchlistAddedRevision)) {
            handledWatchlistRevision = uiState.watchlistAddedRevision
            showWatchlistScene = true
        }
    }

    // 详情页没有激活入口，但加入看单的场景需要激活态。只恢复激活态，
    // 不走 ensureLoaded()——那条路径会拉角色目录并播试听，详情页不该冒出语音。
    LaunchedEffect(Unit) { spriteViewModel.restoreActivation() }

    // 拦截系统返回手势/返回键，统一走 onBack 回调以传递变更状态
    BackHandler(enabled = true) {
        onBack(uiState.watchlistChanged, uiState.watchedChanged)
    }

    LaunchedEffect(traktId, tmdbId, title, doubanId) {
        viewModel.loadDetail(
            traktId,
            tmdbId,
            title,
            mediaType,
            year,
            imdbId,
            traktRating,
            inWatchlist = initialInWatchlist,
            isWatched = initialIsWatched,
            doubanId = doubanId,
            seedPosterUrl = seedPosterUrl
        )
    }

    // 豆瓣同步 Toast 提示（成功/失败/ID未就绪）
    ToastEffect(viewModel.toastEvent)

    // 标记想看/取消看过：给一条带「撤销」的 Snackbar。误点之前只能自己再点回去。
    val markSnackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.common_undo)
    val watchlistAddedMsg = stringResource(R.string.detail_watchlist_added_toast)
    val watchlistRemovedMsg = stringResource(R.string.detail_watchlist_removed_toast)
    val watchedRemovedMsg = stringResource(R.string.detail_watched_removed_toast)
    LaunchedEffect(viewModel) {
        viewModel.markEvent.collect { event ->
            val message = when {
                event.kind == DetailMarkKind.WATCHLIST && event.added -> watchlistAddedMsg
                event.kind == DetailMarkKind.WATCHLIST -> watchlistRemovedMsg
                else -> watchedRemovedMsg
            }
            val result = markSnackbarHostState.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                // 撤销就是再调一次同一个 toggle：两个 toggle 都是幂等的状态翻转
                when (event.kind) {
                    DetailMarkKind.WATCHLIST -> viewModel.toggleWatchlist()
                    DetailMarkKind.WATCHED -> viewModel.toggleWatched()
                }
            }
        }
    }

    val listState = rememberLazyListState()
    // 下拉刷新：重拉评分/演职员/季/短评/推荐这几个分区（资源区代价太高，另有自己的重搜入口）
    var detailRefreshPending by remember { mutableStateOf(false) }
    val detailPullToRefreshState = rememberAppPullToRefreshState {
        viewModel.pullToRefresh()
        detailRefreshPending = true
    }
    LaunchedEffect(detailRefreshPending) {
        if (!detailRefreshPending) return@LaunchedEffect
        // 分区被 sectionVisible 关闭时对应 loading 标志永不置 true（如豆瓣模式关掉评论模块），
        // 空等会一直挂起拖满 10s 超时；进入等待前先按可见性过滤，只等可能进入 loading 的分区
        val waitComments = uiState.sectionVisible.comments
        val waitRecommendations = uiState.sectionVisible.recommendations
        if (waitComments || waitRecommendations) {
            withTimeoutOrNull(10_000) {
                snapshotFlow {
                    (waitComments && uiState.isLoadingComments) ||
                        (waitRecommendations && uiState.isLoadingRecommendations)
                }
                    .dropWhile { !it }
                    .first { !it }
            }
        }
        detailPullToRefreshState.finishRefresh()
        detailRefreshPending = false
    }
    val scrollToTopProvider = LocalScrollToTopProvider.current
    val detailCoroutineScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        scrollToTopProvider.register {
            detailCoroutineScope.launch {
                listState.animateScrollToItem(0)
            }
        }
        onDispose {
            scrollToTopProvider.unregister()
        }
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showPosterFullscreen by remember { mutableStateOf(false) }
    var playingVideoKey by remember { mutableStateOf<String?>(null) }
    var selectedBackdropIndex by remember { mutableIntStateOf(-1) }
    // 截图查看器是否由「全部预告片与截图」sheet 触发：
    // sheet 是独立 Dialog 窗口，内联层会被它遮住且返回键被它吃掉，
    // 因此该来源要改走 Dialog 包裹（代价是跨窗口无法共享元素，用 scale+fade 近似）
    var backdropFromSheet by remember { mutableStateOf(false) }
    var showAllVideos by remember { mutableStateOf(false) }

    // 内容就绪状态:沉浸背景优先显示,其他内容(cast/视频/简介/tab)淡入
    // 至少等主导航/共享元素转场的重负载帧结束，再组合非首屏内容。
    // 颜色命中走 320ms，未命中走 480ms；最后再跨一帧，避免与路由出场节点销毁同帧发生。
    var contentReady by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.posterDominantColor) {
        if (contentReady) return@LaunchedEffect // 已就绪则不重复触发淡入(#26)
        delay(if (uiState.posterDominantColor != null) 320 else 480)
        withFrameNanos { }
        contentReady = true
    }
    // 头部下方内容淡入：animateFloatAsState 做真实渐显（0→1 约 220ms），
    // 旧实现是 derivedStateOf 的 0/1 直切，注释宣称淡入但实际没有过渡
    val contentAlpha by animateFloatAsState(
        targetValue = if (contentReady) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "detailContentAlpha"
    )

    // Coil 内存缓存兜底：PosterColorCache miss 时从 Coil 内存缓存取 bitmap 提取主色
    // 列表页 MovieCard 已用 size(264) 加载海报，详情页进入时 Coil 内存缓存大概率命中 → 秒提取
    // 只查内存缓存，network/disk 均 disable 避免重复网络请求
    val coilContext = LocalContext.current
    LaunchedEffect(uiState.posterUrl, uiState.posterDominantColor) {
        val url = uiState.posterUrl ?: return@LaunchedEffect
        // 已有主色就不做兜底（ViewModel 的 prefetchPosterColor 或缓存命中已设置）
        if (uiState.posterDominantColor != null) return@LaunchedEffect
        val request = ImageRequest.Builder(coilContext)
            .data(url)
            .size(264)
            .networkCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = coilContext.imageLoader.execute(request)
        if (result is SuccessResult) {
            val bitmap = result.drawable.toBitmap()
            val argb = viewModel.posterColorExtractor.extractDominantColor(url, bitmap)
            if (argb != 0L) {
                viewModel.updatePosterColor(Color(argb))
            }
        }
    }

    // 评论翻译映射
    val translatedMap = remember(uiState.translatedComments) {
        uiState.translatedComments.associateBy { it.id }
    }

    // 资源分页加载
    val initialCount = 30
    var displayedCount by remember { mutableIntStateOf(initialCount.coerceAtMost(uiState.resources.size)) }
    LaunchedEffect(uiState.resources.size) {
        if (displayedCount > uiState.resources.size) {
            displayedCount = uiState.resources.size
        } else if (uiState.resources.isNotEmpty() && displayedCount < uiState.resources.size) {
            // 数据增长时，至少显示 initialCount 条（避免先到源只有少量结果导致卡住）
            displayedCount = maxOf(displayedCount, initialCount).coerceAtMost(uiState.resources.size)
        }
    }

    // 滚动到底部自动加载更多（资源/评论共用）
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(shouldLoadMore, selectedTab) {
        if (shouldLoadMore) {
            if (selectedTab == 0 && displayedCount < uiState.resources.size) {
                displayedCount = (displayedCount + 30).coerceAtMost(uiState.resources.size)
            } else if (selectedTab == 1 && uiState.sectionVisible.comments && uiState.hasMoreComments && !uiState.isLoadingMoreComments) {
                viewModel.loadMoreComments()
            }
        }
    }

    // Haze 毛玻璃状态
    val detailHazeState = remember { HazeState() }
    val detailIsDark = isAppDarkTheme()
    val detailGlassScene = glassSceneForContent(
        contentCount = uiState.resources.size + uiState.comments.size +
            uiState.recommendations.size + uiState.videos.size,
        readabilityDemand = when {
            uiState.isSearching || uiState.isTranslating -> 0.96f
            selectedTab == 1 && uiState.comments.isNotEmpty() -> 0.90f
            uiState.resources.isNotEmpty() -> 0.84f
            else -> 0.68f
        },
        ambientColor = uiState.posterDominantColor ?: MaterialTheme.colorScheme.background,
        contentCapacity = 72,
        loadingCount = listOf(
            uiState.isLoading,
            uiState.isSearching,
            uiState.isTranslating,
            uiState.isLoadingComments,
            uiState.isLoadingMoreComments,
            uiState.isLoadingVideosImages,
            uiState.isLoadingRecommendations,
            uiState.isMarkingWatched,
            uiState.isMarkingWatchlist,
            uiState.isRating,
            uiState.isRatingLoading,
            uiState.isDoubanSyncing
        ).count { it },
        loadingItemWeight = 4
    )
    // NavHost 进入/返回时旧页与新页会短暂同时绘制。详情页在这段窗口内暂停
    // 非首屏内容与 Haze source，避免正文、图片和模糊采样与共享海报叠加到同一帧。
    // 不读取 isRunning：它随动画每帧变化，会让详情正文整棵树反复重组。
    // 端点状态只在转场开始/结束时变化，足以控制首屏内容和 Haze source。
    val isNavigationTransitionRunning = LocalAnimatedVisibilityScope.current?.transition?.let { transition ->
        transition.currentState != transition.targetState
    } == true
    val contentReadyForTransition = contentReady && !isNavigationTransitionRunning


    // 顶栏与吸顶 Tab 栏共用一条实色底（取色见 DetailVisuals.detailBarColor）。
    // 提到 Scaffold 之外算：状态栏条、吸顶栏、标题淡入三处要用同一份 isPinned。
    val isPinned by remember {
        derivedStateOf { listState.firstVisibleItemIndex >= 1 }
    }
    val pinnedBarColor = detailBarColor()
    val barColor by animateColorAsState(
        targetValue = if (isPinned) pinnedBarColor else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "detailBarColor"
    )
    val topBarTitleAlpha by animateFloatAsState(
        targetValue = if (isPinned) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "detailTopBarTitleAlpha"
    )

    Scaffold(
        modifier = Modifier.testTag("detail_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            // onDraw 是非 Composable 的 DrawScope，背景色需在组合期预计算。
            // 悬浮按钮统一走 Haze 实时采样（LocalBackdrop=null），直接随滚动内容刷新，
            // 不再用 Backdrop 静态快照（其坐标映射在滚动/吸顶时不稳，会造成白底/透明）。
            val immersiveBackgroundModifier = uiState.posterDominantColor?.let { color ->
                Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            color.copy(alpha = 0.70f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
            } ?: Modifier

            // Blur 与 Glass 都注册同一个 Haze source：Blur 直接用其采样，Glass 的悬浮按钮
            // 用同一 Haze 状态做实时采样，保证沉浸渐变与滚动内容都能被按钮实时捕到。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isNavigationTransitionRunning) Modifier
                        else Modifier.hazeSource(state = detailHazeState, zIndex = 0f)
                    )
                    .then(immersiveBackgroundModifier)
            ) {
            // 全屏查看器打开时，把「正在被查看」的 key 广播给缩略图源侧，让源侧置不可见。
            // 同一 key 若两侧同时是 target，SharedTransitionStateMachine 会取先注册的源侧作为
            // 目标边界提供者，打开时边界从全屏动到缩略图（方向反了），观感上等于没有缩放动画。
            // 从 sheet 打开走的是跨窗口 Dialog，本就无法共享元素，因此不隐藏横向栏缩略图。
            val fullscreenSharedKey = when {
                showPosterFullscreen -> "poster-zoom-bounds-$tmdbId"
                selectedBackdropIndex >= 0 && !backdropFromSheet ->
                    "backdrop-zoom-$tmdbId-$selectedBackdropIndex"
                else -> null
            }
            CompositionLocalProvider(
                LocalFullscreenSharedElement provides fullscreenSharedElementKey(fullscreenSharedKey)
            ) {

            // 状态栏条：与顶栏同色同步淡入，让顶栏在视觉上延伸到状态栏底下。
            // 原先这里铺的是掺了海报色的沉浸实色，且不吸顶时才透明——顶栏、Tab 栏、
            // 状态栏三段颜色各算一套，滚动后顶部是三条深浅不一的横带。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(barColor)
                    .align(Alignment.TopCenter)
            )

            // 回调 lambda remember 化:避免每次重组都新建实例(与 MovieCard 一致),
            // 减少分配并为后续按字段跳过重组打基础。置于 LazyColumn 之前(@Composable 上下文)。
            val onToggleWatched = remember { { viewModel.toggleWatched() } }
            val onToggleWatchlist = remember { { viewModel.toggleWatchlist() } }
            // 评分入口先判断登录态，避免用户填完评分才被告知要登录；
            // 由 ViewModel 根据当前条目选择 Trakt 或豆瓣，不能统一送回激活页。
            val onShowRatingDialog = remember(uiState.isLoggedIn) {
                {
                    if (uiState.isLoggedIn) {
                        showRatingDialog = true
                    } else {
                        viewModel.requestLoginForCurrentItem()
                    }
                }
            }
            val onPosterClick = remember { { showPosterFullscreen = true } }
            val onToggleSeason = remember { { season: Int -> viewModel.toggleSeason(season) } }
            val onToggleEpisodeWatched = remember { { season: Int, episode: Int, traktId: Int -> viewModel.toggleEpisodeWatched(season, episode, traktId) } }
            val onVideoClick = remember { { video: TmdbVideo -> playingVideoKey = video.key } }
            val onBackdropClick = remember { { index: Int -> backdropFromSheet = false; selectedBackdropIndex = index } }
            val onShowAllVideos = remember { { showAllVideos = true } }
            val onCollectionMovieClick = remember(onMovieClick) { { movieTmdbId: Int, movieTitle: String -> onMovieClick(0, movieTmdbId, movieTitle, "", 0.0) } }
            // Tab 数量计算（置于 LazyColumn 之前的 @Composable 上下文，并用副作用修正 selectedTab 范围）
            val showCommentsTab = uiState.sectionVisible.comments
            val showRecommendationsTab = uiState.sectionVisible.recommendations
            val tabCount = 1 + (if (showCommentsTab) 1 else 0) + (if (showRecommendationsTab) 1 else 0)
            LaunchedEffect(tabCount) {
                if (selectedTab > tabCount - 1) selectedTab = tabCount - 1
            }
            // 单 LazyColumn：头部(item) + 顶栏/TabRow(stickyHeader) + 内容(根据Tab切换)
            // 吸顶栏不再染沉浸色，内部搜索源/网盘类型/找到xx个资源等文字统一走主题色
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) {
            // 将详情内容整体作为唯一内容 source，避免 LazyColumn 自身的绘制层影响 Haze 采样。
            AppPullToRefreshIndicator(
                state = detailPullToRefreshState,
                contentTop = 8.dp,
                modifier = Modifier.statusBarsPadding()
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .nestedScroll(detailPullToRefreshState.connection)
                    .graphicsLayer { translationY = detailPullToRefreshState.offset.floatValue },
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // 头部信息（随内容滚动）
                item(key = "detail_header") {
                    DetailHeaderContent(
                        uiState = uiState,
                        tmdbId = tmdbId,
                        isMarkedWatched = uiState.isMarkedWatched,
                        isMarkingWatched = uiState.isMarkingWatched,
                        onToggleWatched = onToggleWatched,
                        isMarkedWatchlist = uiState.isMarkedWatchlist,
                        isMarkingWatchlist = uiState.isMarkingWatchlist,
                        onToggleWatchlist = onToggleWatchlist,
                        onShowRatingDialog = onShowRatingDialog,
                        onPosterClick = onPosterClick,
                        onPersonClick = onPersonClick,
                        onToggleSeason = onToggleSeason,
                        onToggleEpisodeWatched = onToggleEpisodeWatched,
                        onRetryRatings = viewModel::retryRatings,
                        onRetryCredits = viewModel::retryCredits,
                        onRetrySeasons = viewModel::retrySeasons,
                        onRetryVideos = viewModel::retryVideos,
                        onVideoClick = onVideoClick,
                        onBackdropClick = onBackdropClick,
                        onShowAllVideos = onShowAllVideos,
                        onCollectionMovieClick = onCollectionMovieClick,
                        posterColorExtractor = viewModel.posterColorExtractor,
                        onPosterColorExtracted = viewModel::updatePosterColor,
                        sectionVisible = uiState.sectionVisible,
                        contentReady = contentReadyForTransition,
                        // 头部下方内容(cast/视频/简介/季集)淡入,海报+标题+按钮始终可见
                        contentAlpha = if (contentReadyForTransition) contentAlpha else 0f,
                        onHeaderAnchorBoundsChanged = { detailHeaderBounds = it }
                    )
                }

                // 转场期间(contentReady=false)跳过 Tab 行和所有 Tab 内容组合,
                // 首帧只组合 header(海报+标题+按钮),大幅降低转场期间首帧工作量。
                // contentReady 由 posterDominantColor 就绪或 400ms 兜底触发,转场结束后即 true。
                if (contentReadyForTransition) {
                // 顶栏 + Tab 行（吸顶，共用同一个）
                stickyHeader(key = "tab_row") {
                    // 标题行与 Tab 行同在一个 Column 里、共用一次铺底、中间不加分隔线，
                    // 吸顶后就是一整条。标题行高度恒定（不吸顶时只是透明占位），
                    // 否则吸顶瞬间 sticky item 长高会把下方内容整体往下推一截。
                    Column(
                        modifier = Modifier
                            .alpha(contentAlpha)
                            .background(barColor)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(DETAIL_TOP_BAR_HEIGHT)
                                // 左右各让出 64dp 给悬浮的返回/分享按钮，标题居中不被压在按钮下
                                .padding(horizontal = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = uiState.displayTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer { alpha = topBarTitleAlpha }
                            )
                        }
                        PrimaryTabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { view.performHaptic(HapticType.CLICK); selectedTab = 0 },
                                text = { Text("${stringResource(R.string.detail_tab_resources)}(${uiState.resources.size})", maxLines = 1) }
                            )
                            if (showCommentsTab) {
                                Tab(
                                    selected = selectedTab == 1,
                                    onClick = { view.performHaptic(HapticType.CLICK); selectedTab = 1 },
                                    text = { Text("${stringResource(R.string.detail_tab_comments)}(${uiState.comments.size})", maxLines = 1) }
                                )
                            }
                            if (showRecommendationsTab) {
                                val recTabIndex = if (showCommentsTab) 2 else 1
                                Tab(
                                    selected = selectedTab == recTabIndex,
                                    onClick = { view.performHaptic(HapticType.CLICK); selectedTab = recTabIndex },
                                    text = { Text("${stringResource(R.string.detail_tab_recommendations)}(${uiState.recommendations.size})", maxLines = 1) }
                                )
                            }
                        }
                    }
                }

                // ===== 资源 Tab 内容 =====
                if (selectedTab == 0) {
                    // 筛选器
                    item(key = "filter_section") {
                        Box(modifier = Modifier.alpha(contentAlpha)) {
                            FilterSection(
                                availableSources = uiState.availableSources,
                                enabledSources = uiState.enabledSources,
                                customSourceNames = uiState.customSourceNames,
                                enabledDiskTypes = uiState.enabledDiskTypes,
                                onToggleSource = { viewModel.toggleSource(it) },
                                onToggleDiskType = { viewModel.toggleDiskType(it) },
                                relevanceEnabled = uiState.title.isNotBlank(),
                                showHighRelevanceOnly = uiState.showHighRelevanceOnly,
                                onToggleShowHighRelevanceOnly = { viewModel.toggleShowHighRelevanceOnly() }
                            )
                        }
                    }

                    // 低相关隐藏提示（开启"仅显示高相关"且有被隐藏项时）
                    if (uiState.showHighRelevanceOnly && uiState.lowRelevanceHiddenCount > 0) {
                        item(key = "low_relevance_hint") {
                            Box(
                                modifier = Modifier
                                    .alpha(contentAlpha)
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clickable { viewModel.toggleShowHighRelevanceOnly() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.detail_hidden_low_relevance,
                                        uiState.lowRelevanceHiddenCount
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // 季/集信息（仅电视剧）— 移到简介下方
                    // 已移至 DetailHeaderContent 中

                    // 资源列表
                    val items = uiState.resources
                    when {
                        uiState.isSearching -> {
                            item(key = "searching") {
                                Box(modifier = Modifier.alpha(contentAlpha)) {
                                    SearchingState(
                                        completedSources = uiState.completedSources,
                                        totalSources = uiState.totalSources
                                    )
                                }
                            }
                        }
                        items.isEmpty() && uiState.searchAttempted -> {
                            item(key = "empty") {
                                Box(modifier = Modifier.alpha(contentAlpha)) {
                                    EmptyState(onRetry = { viewModel.searchResources() })
                                }
                            }
                        }
                        else -> {
                            itemsIndexed(items.take(displayedCount), key = { _, item -> item.url }, contentType = { _, _ -> "resource" }) { index, item ->
                                ResourceItemCard(
                                    item = item,
                                    sourceName = uiState.customSourceNames[item.source],
                                    isViewed = item.url in uiState.viewedUrls,
                                    onClick = {
                                        viewModel.markResourceViewed(item.url)
                                        openResourceLink(context, item)
                                    },
                                    onLongClick = {
                                        view.performHaptic(HapticType.HEAVY_CLICK)
                                        copyResourceLink(context, item)
                                    },
                                    index = index
                                )
                            }
                            if (displayedCount < items.size) {
                                // 原先这里是隐形的 Spacer(56.dp)：滚到底只看到一片空白，
                                // 分不清是还在加载还是列表已经到底。同为 56dp 高，不影响
                                // 上面 shouldLoadMore 按 index 判定的触发时机。
                                item(key = "load_more") {
                                    LoadMoreFooter(
                                        state = LoadMoreFooterState.Loading,
                                        onRetry = {},
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else if (items.size > initialCount) {
                                item(key = "all_loaded") {
                                    LoadMoreFooter(
                                        state = LoadMoreFooterState.Complete,
                                        onRetry = {},
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                // ===== 评论 Tab 内容 =====
                if (uiState.sectionVisible.comments && selectedTab == 1) {
                    val ownComment = uiState.userComment?.takeIf { it.isNotBlank() }
                    val commentsToShow = uiState.comments.filter { it.id != uiState.traktCommentId }
                    // 豆瓣评论基本都是中文，无需翻译，只有存在非豆瓣评论时才显示全部翻译
                    val translatableComments = commentsToShow.filter { it.source != DOUBAN_COMMENT_SOURCE }
                    // 评论区工具行：「全部翻译」原先是右上角一枚孤立悬着的胶囊，
                    // 与下方卡片的左边界对不上，也看不出属于哪一段。改成左对齐的一条
                    // 工具行，与资源 Tab 的筛选器同一位置、同一内边距。
                    if (translatableComments.isNotEmpty() && uiState.translatedComments.size < translatableComments.size) {
                        item(key = "comments_translate") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Surface(
                                    onClick = { viewModel.translateComments() },
                                    enabled = !uiState.isTranslating,
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        if (uiState.isTranslating) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(13.dp),
                                                strokeWidth = 1.5.dp
                                            )
                                        }
                                        Text(
                                            text = if (uiState.isTranslating) {
                                                uiState.translationProgress?.let {
                                                    stringResource(R.string.detail_translating_progress, it)
                                                } ?: stringResource(R.string.detail_translating)
                                            } else stringResource(R.string.detail_translate_all),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "own_comment") {
                        if (ownComment == null || uiState.isEditingOwnComment) {
                            OwnCommentComposer(
                                initialComment = ownComment.orEmpty(),
                                targets = uiState.ownCommentTargets,
                                isSaving = uiState.isSavingOwnComment,
                                isEditing = uiState.isEditingOwnComment,
                                onSubmit = viewModel::submitOwnComment,
                                onCancelEdit = viewModel::cancelOwnCommentEdit,
                                scene = detailGlassScene
                            )
                        } else {
                            OwnCommentCard(
                                comment = ownComment,
                                targets = uiState.ownCommentTargets,
                                retryTargets = uiState.retryOwnCommentTargets,
                                isSaving = uiState.isSavingOwnComment,
                                onEdit = viewModel::beginOwnCommentEdit,
                                onRetry = viewModel::retryOwnCommentSync,
                                scene = detailGlassScene
                            )
                        }
                    }

                    if (uiState.isLoadingComments) {
                        item(key = "comments_loading") {
                            // 骨架而非转圈：短评是定高卡片流，骨架能预告条目排布，
                            // 数据到达时不整块跳变。三条共享一份 shimmer 动画。
                            val commentShimmer = rememberShimmer()
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                repeat(3) {
                                    CommentSkeleton(shimmer = commentShimmer)
                                }
                            }
                        }
                    }

                    if (!uiState.isLoadingComments && uiState.commentsError && commentsToShow.isEmpty()) {
                        item(key = "comments_error") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AppErrorState(
                                    message = stringResource(R.string.detail_load_error),
                                    onRetry = viewModel::retryComments,
                                    variant = AppErrorVariant.Inline,
                                    inlineLabel = stringResource(R.string.detail_load_error),
                                    showDetail = false
                                )
                            }
                        }
                    }

                    if (!uiState.isLoadingComments && !uiState.commentsError && commentsToShow.isEmpty() && ownComment == null) {
                        item(key = "comments_empty") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                EmptyStateCard(
                                    isDark = detailIsDark,
                                    icon = Icons.Rounded.ChatBubbleOutline,
                                    title = stringResource(R.string.detail_no_comments)
                                )
                            }
                        }
                    }

                    items(
                        items = commentsToShow,
                        key = { it.id }
                    ) { comment ->
                        CommentItem(
                            comment = comment,
                            translatedText = translatedMap[comment.id]?.comment,
                            onTranslate = { commentId ->
                                if (commentId == -1) viewModel.translateComments()
                                else viewModel.translateSingleComment(commentId)
                            },
                            isTranslating = uiState.isTranslating,
                            isThisTranslating = (uiState.translatingCommentId == comment.id),
                            scene = detailGlassScene
                        )
                    }

                    if (commentsToShow.isNotEmpty()) {
                        item(key = "load_more_comments") {
                            LoadMoreFooter(
                                state = when {
                                    uiState.isLoadingMoreComments -> LoadMoreFooterState.Loading
                                    uiState.commentsError -> LoadMoreFooterState.Error
                                    !uiState.hasMoreComments && commentsToShow.size > 5 -> LoadMoreFooterState.Complete
                                    else -> LoadMoreFooterState.Hidden
                                },
                                onRetry = viewModel::loadMoreComments,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // ===== 推荐 Tab 内容 =====
                if (uiState.sectionVisible.recommendations && selectedTab == (if (uiState.sectionVisible.comments) 2 else 1)) {
                    val recommendations = uiState.recommendations
                    when {
                        uiState.isLoadingRecommendations -> {
                            // 骨架按真实 3 列网格铺，与下方 MovieCard 同宽同比例，
                            // 数据到达时列宽不变；三行共享一份 shimmer 动画。
                            item(key = "rec_loading") {
                                val recShimmer = rememberShimmer()
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    repeat(3) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            repeat(3) {
                                                MovieCardSkeleton(
                                                    modifier = Modifier.weight(1f),
                                                    shimmer = recShimmer
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        uiState.recommendationsError -> {
                            item(key = "rec_error") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AppErrorState(
                                        message = stringResource(R.string.detail_load_error),
                                        onRetry = viewModel::retryRecommendations,
                                        variant = AppErrorVariant.Inline,
                                        inlineLabel = stringResource(R.string.detail_load_error),
                                        showDetail = false
                                    )
                                }
                            }
                        }
                        recommendations.isEmpty() -> {
                            item(key = "rec_empty") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // 原先是一句裸 Text，跟同页短评空态的 EmptyStateCard 完全两种画法
                                    EmptyStateCard(
                                        isDark = detailIsDark,
                                        icon = Icons.Rounded.Movie,
                                        title = stringResource(R.string.detail_no_recommendations)
                                    )
                                }
                            }
                        }
                        else -> {
                            // 相关推荐标题已移除
                            recommendations.chunked(3).forEachIndexed { rowIndex, rowItems ->
                                item(key = "rec_row_$rowIndex") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowItems.forEach { item ->
                                            MovieCard(
                                                title = item.displayTitle.ifEmpty { item.title },
                                                year = item.year,
                                                genres = item.genres,
                                                posterUrl = item.posterUrl,
                                                tmdbId = item.tmdbId,
                                                origin = SharedOrigin.of(SharedOrigin.DETAIL, tmdbId.toString()),
                                                onClick = {
                                                    if (mediaType == MediaType.MOVIE) {
                                                        onMovieClick(item.traktId, item.tmdbId, item.displayTitle.ifEmpty { item.title }, item.imdbId, item.traktRating)
                                                    } else {
                                                        onShowClick(item.traktId, item.tmdbId, item.displayTitle.ifEmpty { item.title }, item.imdbId, item.traktRating)
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                isInWatchlist = item.isInWatchlist,
                                                isWatched = item.isWatched
                                            )
                                        }
                                        repeat(3 - rowItems.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                } // end if (contentReady)
                }
            } // CompositionLocalProvider(LocalFullscreenSharedElement)
            } // hazeSource Box 结束：采样源只包住状态栏底色 + 滚动内容

            // 悬浮控件与全屏覆盖层必须与 hazeSource 保持兄弟关系。
            // Haze 2.x 的默认 HazeSourceSelection.Behind 会先找 hazeBlur 节点最近的祖先
            // hazeSource（同一 HazeState），再只保留 zIndex 小于它的源；顶栏按钮/回顶按钮
            // 一旦落在采样源子树内，祖先源 zIndex=0 会把自己过滤掉（0 < 0 不成立），
            // 结果 blur/glass 两种模式下模糊都静默失效（源列表为空，不报错也不模糊）。
            AiSpriteMotion(
                characterId = spriteState.activatedCharacterId.orEmpty(),
                anchor = AiSpriteAnchor.DetailHeader,
                anchorBounds = detailHeaderBounds,
                visible = showWatchlistScene &&
                    detailHeaderBounds != null &&
                    spriteState.activatedCharacterId?.let { automaticSpriteArt(it) != null } == true,
                onClick = {},
                onFinished = { showWatchlistScene = false },
                modifier = Modifier.zIndex(5f),
                sceneRes = sceneArtFor(AiSceneEvent.DETAIL_WATCHLIST_ADDED).drawableRes,
                // 加入看单的庆祝插画，不该变成盖在详情页上的可点区域
                interactive = false
            )

            // 顶栏按钮与回顶按钮置于采样源之外(LocalBackdrop=null)：glass 模式下由
            // appVisualEffect/GlassIconButton 退化到 Haze 实时采样，随滚动内容实时刷新，
            // 避免 Backdrop 静态快照停在渐变区时无法实时捕到按钮正后方的内容。
            // 且显式用 HazeSourceSelection.All 采样全部源：这些控件与 hazeSource 是兄弟关系、
            // 无同 state 祖先源，若未来被重新嵌回源子树，Behind 会因 0<0 静默丢源导致模糊失效，
            // All 直接强制采样内容源，blur/glass 两种模式都稳定生效。
            CompositionLocalProvider(LocalBackdrop provides null) {
            // 滚动后淡入的标题栏已并进吸顶 stickyHeader（与 Tab 行共用一次铺底），
            // 这里只剩返回/分享等悬浮圆按钮，正好压在标题行两侧留出的 64dp 上。

            // 返回按钮：与详情页其他操作统一使用拟态玻璃，并保留真实 Haze 背景采样。
            NeumorphicIconButton(
                onClick = { view.performHaptic(HapticType.TICK); onBack(uiState.watchlistChanged, uiState.watchedChanged) },
                isDark = detailIsDark,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 4.dp)
                    .align(Alignment.TopStart),
                hazeState = detailHazeState,
                hazeStyle = HazeMaterials.ultraThin(),
                size = 40.dp,
                buttonStyle = NeumorphicIconButtonStyle.DetailTopBar,
                scene = detailGlassScene,
                sourceSelection = HazeSourceSelection.All
            ) {
                DetailTopBarIcon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.detail_back),
                    size = 24.dp
                )
            }

            // 分享按钮 + 豆瓣同步重试按钮
            val context = LocalContext.current
            val shareLinksLabel = stringResource(R.string.detail_share_links)
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(end = 12.dp, top = 4.dp)
                    .align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 豆瓣同步重试按钮（仅在 doubanSyncRetryable=true 时显示）
                if (uiState.doubanSyncRetryable) {
                    val view = LocalView.current
                    NeumorphicIconButton(
                        onClick = {
                            view.performHaptic(HapticType.CLICK)
                            viewModel.retryDoubanSync()
                        },
                        isDark = detailIsDark,
                        enabled = !uiState.isDoubanSyncing,
                        hazeState = detailHazeState,
                        hazeStyle = HazeMaterials.ultraThin(),
                        size = 40.dp,
                        buttonStyle = NeumorphicIconButtonStyle.DetailTopBar,
                        scene = detailGlassScene,
                        sourceSelection = HazeSourceSelection.All
                    ) {
                        if (uiState.isDoubanSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = detailTopBarIconColor()
                            )
                        } else {
                            DetailTopBarIcon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.detail_douban_sync_retry)
                            )
                        }
                    }
                }

                // 分享按钮：与返回按钮使用同一拟态玻璃组件和 Haze 状态。
                NeumorphicIconButton(
                    onClick = {
                        val shareText = buildString {
                            append(uiState.title)
                            if (uiState.year != null) append(" (${uiState.year})")
                            append("\n")
                            if (uiState.overview.isNotBlank()) {
                                append(uiState.overview)
                                append("\n")
                            }
                            // 附带前两个资源搜索结果的网盘链接
                            val topResources = uiState.resources.take(2)
                            if (topResources.isNotEmpty()) {
                                append("\n" + shareLinksLabel + "\n")
                                topResources.forEachIndexed { index, item ->
                                    append("${index + 1}. ${item.name}\n${item.url}\n")
                                }
                            }
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    },
                    isDark = detailIsDark,
                    hazeState = detailHazeState,
                    hazeStyle = HazeMaterials.ultraThin(),
                    size = 40.dp,
                    buttonStyle = NeumorphicIconButtonStyle.DetailTopBar,
                    scene = detailGlassScene,
                    sourceSelection = HazeSourceSelection.All
                ) {
                    DetailTopBarIcon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.detail_share)
                    )
                }
            }

            ScrollToTopButton(
                listState = listState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp),
                hazeState = detailHazeState,
                hazeStyle = HazeMaterials.ultraThin(),
                sourceSelection = HazeSourceSelection.All,
                scene = detailGlassScene
            )

            // 标记操作的撤销 Snackbar：贴底显示，避开回顶按钮
            SnackbarHost(
                hostState = markSnackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )
            } // CompositionLocalProvider

            // 海报大图查看
            val posterUrl = uiState.posterUrl
            if (posterUrl != null) {
                PosterFullscreenOverlay(
                    visible = showPosterFullscreen,
                    posterUrl = posterUrl,
                    title = uiState.displayTitle,
                    sharedKeyPrefix = "poster-zoom-bounds-$tmdbId",
                    onDismiss = { showPosterFullscreen = false }
                )
            }

            // YouTube 内置播放器（用 Dialog 包裹以确保覆盖在 ModalBottomSheet 之上）
            playingVideoKey?.let { key ->
                Dialog(
                    onDismissRequest = { playingVideoKey = null },
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false
                    )
                ) {
                    YouTubePlayerOverlay(
                        videoKey = key,
                        videoTitle = "",
                        onDismiss = { playingVideoKey = null }
                    )
                }
            }

            // 截图滑动查看（从详情页横向栏打开：内联，不用 Dialog，共享转场需同 window）
            BackdropPagerOverlay(
                visible = selectedBackdropIndex >= 0 && !backdropFromSheet && uiState.backdrops.isNotEmpty(),
                backdrops = uiState.backdrops,
                initialIndex = selectedBackdropIndex.coerceAtLeast(0),
                sharedKeyPrefix = "backdrop-zoom-$tmdbId",
                onDismiss = { selectedBackdropIndex = -1 }
            )

            // 截图滑动查看（从 sheet 内打开：Dialog 盖在 ModalBottomSheet 之上，
            // sheet 保持打开，返回只关查看器，sheet 仍停在原滚动位置）
            if (backdropFromSheet && selectedBackdropIndex >= 0 && uiState.backdrops.isNotEmpty()) {
                // 首帧后再翻 true，AnimatedVisibility 才会播进入动画（初始即 true 不播）
                var sheetViewerVisible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { sheetViewerVisible = true }
                val closeSheetViewer: () -> Unit = {
                    detailCoroutineScope.launch {
                        sheetViewerVisible = false
                        delay(220) // 等退出动画播完再移除 Dialog 窗口
                        selectedBackdropIndex = -1
                        backdropFromSheet = false
                    }
                }
                Dialog(
                    onDismissRequest = closeSheetViewer,
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false
                    )
                ) {
                    BackdropPagerOverlay(
                        visible = sheetViewerVisible,
                        backdrops = uiState.backdrops,
                        initialIndex = selectedBackdropIndex.coerceAtLeast(0),
                        sharedKeyPrefix = null, // 跨窗口无法配对共享元素
                        onDismiss = closeSheetViewer,
                        enter = scaleIn(initialScale = 0.85f, animationSpec = tween(220)) +
                            fadeIn(animationSpec = tween(220)),
                        exit = scaleOut(targetScale = 0.85f, animationSpec = tween(200)) +
                            fadeOut(animationSpec = tween(200))
                    )
                }
            }

            // 全部预告片与截图弹窗
            if (showAllVideos) {
                FullVideosImagesSheet(
                    videos = uiState.videos,
                    backdrops = uiState.backdrops,
                    onDismiss = { showAllVideos = false },
                    onVideoClick = { video ->
                        playingVideoKey = video.key
                    },
                    onBackdropClick = { index ->
                        // 不关 sheet：查看器走 Dialog 盖在 sheet 之上，返回后仍在 sheet 原位
                        backdropFromSheet = true
                        selectedBackdropIndex = index
                    }
                )
            }

            // 未登录用户引导登录弹窗：确认后直达当前条目对应的平台网页登录流程。
            if (uiState.showLoginPrompt) {
                val loginTarget = uiState.loginTarget
                val dismissLoginPrompt: () -> Unit = viewModel::dismissLoginPrompt
                AlertDialog(
                    onDismissRequest = dismissLoginPrompt,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    title = { Text(stringResource(R.string.detail_login_required_title)) },
                    text = { Text(stringResource(R.string.detail_login_required_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            dismissLoginPrompt()
                            when (loginTarget) {
                                DetailLoginTarget.TRAKT -> onTraktLogin()
                                DetailLoginTarget.DOUBAN -> onDoubanLogin()
                                null -> Unit
                            }
                        }) {
                            Text(stringResource(R.string.detail_login_go))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = dismissLoginPrompt) {
                            // 用应用内资源而非 android.R.string.cancel（平台串随系统语言变化）
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
            }

            // 电视剧标记已看弹窗（季/集勾选）
            if (uiState.showMarkWatchedDialog) {
                MarkWatchedDialog(
                    seasons = uiState.seasons,
                    episodes = uiState.episodes,
                    watchedEpisodeNumbers = uiState.watchedEpisodeNumbers,
                    onDismiss = { viewModel.dismissMarkWatchedDialog() },
                    onSubmit = { selectedIds -> viewModel.submitMarkWatched(selectedIds) },
                    onLoadEpisodes = { seasonNumber -> viewModel.loadEpisodesForMarkWatched(seasonNumber) }
                )
            }

            // 评分弹窗（标记已看后自动弹出，或点击评分区域弹出）
            if (showRatingDialog || uiState.showRatingDialog) {
                RatingDialog(
                    initialRating = uiState.userRating,
                    initialComment = uiState.pendingOwnComment ?: uiState.userComment,
                    isSubmitting = uiState.isRating,
                    onDismiss = {
                        showRatingDialog = false
                        viewModel.dismissRatingDialog()
                    },
                    onConfirm = { rating, comment ->
                        showRatingDialog = false
                        view.performHaptic(HapticType.HEAVY_CLICK)
                        // 不调用 dismissRatingDialog():setRatingWithComment/removeRating 内部会关闭弹窗并处理豆瓣同步
                        // 否则会先 syncDoubanMark(COLLECT) 再 syncDoubanMarkWithRating,导致两次豆瓣同步 toast
                        viewModel.confirmRatingWithComment(rating, comment)
                    }
                )
            }
        }
    }
    }
}

// ==================== 搜索状态 ====================

/**
 * 资源搜索中状态。
 *
 * 原先是个不确定进度圈——但 completedSources / totalSources 本来就已知，
 * 转圈把「10 个源已回 8 个」这条信息白白扔掉了。改成确定进度条，
 * 进度值带 [animateFloatAsState] 避免每回一个源就跳一格。
 */
@Composable
private fun SearchingState(completedSources: Int, totalSources: Int) {
    val rawProgress = if (totalSources > 0) {
        (completedSources.toFloat() / totalSources).coerceIn(0f, 1f)
    } else 0f
    val progress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = 320),
        label = "searchProgress"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.detail_searching),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (totalSources > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    strokeCap = StrokeCap.Round
                )
            } else {
                // 源总数还没确定（初始化阶段），此时确实没有进度可言
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    strokeCap = StrokeCap.Round
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.detail_searching_info, completedSources, totalSources),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState(onRetry: () -> Unit) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        EmptyStateCard(
            isDark = isAppDarkTheme(),
            icon = Icons.Rounded.Search,
            title = stringResource(R.string.detail_no_resources),
            actions = {
                OutlinedButton(onClick = { view.performHaptic(HapticType.CLICK); onRetry() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.detail_retry))
                }
            }
        )
    }
}

// 以下 Composable 已拆分到独立文件：
// - DetailHeaderContent.kt: DetailHeaderContent, ExpandableText
// - DetailRatingsDialog.kt: RatingsRow, RatingBadge, UserRatingBar, RatingDialog
// - DetailCrewSection.kt: CrewSection, CastCard, FullCastCrewSheet, FullCastGroupHeader, FullCastItem
// - DetailSeasonsSection.kt: SeasonBadge, WatchedProgressBar, SeasonsSection, EpisodeRow, CollectionSection
// - DetailMarkWatchedDialog.kt: MarkWatchedDialog
// - DetailFilterSection.kt: FilterSection
// - DetailComments.kt: CommentItem
// - DetailVideosImages.kt: VideosAndImagesSection, VideoCard, BackdropCard, FullVideosImagesSheet, FullVideoItem, FullBackdropItem, YouTubePlayerOverlay, BackdropPagerOverlay
// - DetailPosterOverlay.kt: PosterFullscreenOverlay
