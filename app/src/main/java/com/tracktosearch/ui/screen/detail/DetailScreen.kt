package com.tracktosearch.ui.screen.detail

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.tracktosearch.ui.component.LocalActivePosterClickSetter
import com.tracktosearch.ui.component.LocalActivePosterClickToken
import com.tracktosearch.ui.component.LocalActivePosterTmdbId
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.NeumorphicIconButtonStyle
import com.tracktosearch.ui.component.DetailTopBarIcon
import com.tracktosearch.ui.component.ResourceItemCard
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.detailTopBarIconColor
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.ToastEffect
import com.tracktosearch.ui.util.copyResourceLink
import com.tracktosearch.ui.util.openResourceLink
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.screen.ai.AiSceneEvent
import com.tracktosearch.ui.screen.ai.AiSpriteAnchor
import com.tracktosearch.ui.screen.ai.AiSpriteMotion
import com.tracktosearch.ui.screen.ai.AiSpriteViewModel
import com.tracktosearch.ui.screen.ai.rememberSharedAiSpriteViewModel
import com.tracktosearch.ui.screen.ai.automaticSpriteArt
import com.tracktosearch.ui.screen.ai.sceneArtFor
import com.tracktosearch.ui.screen.ai.shouldShowWatchlistAddedScene

import com.tracktosearch.ui.component.LocalBackdrop
import com.tracktosearch.ui.component.LocalFullscreenSharedKey
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onNavigateToLogin: () -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spriteViewModel: AiSpriteViewModel = rememberSharedAiSpriteViewModel()
    val spriteState by spriteViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    // 当前活跃海报 tmdbId（-1=都不启用），确保只有用户点击的推荐卡片参与共享元素转场
    var activePosterTmdbId by rememberSaveable { mutableIntStateOf(-1) }
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

    val listState = rememberLazyListState()
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
    // posterDominantColor 就绪 → 立即标记就绪;未就绪(首次访问无缓存) → 400ms 后兜底就绪
    var contentReady by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.posterDominantColor) {
        if (contentReady) return@LaunchedEffect // 已就绪则不重复触发淡入(#26)
        if (uiState.posterDominantColor != null) {
            // 颜色就绪后短暂延迟,让背景渐变先渲染出来再淡入内容
            delay(80)
        } else {
            // 首次访问无缓存主色,400ms 后兜底显示内容,避免长时间空白
            delay(400)
        }
        contentReady = true
    }
    val contentAlpha by remember(contentReady) {
        derivedStateOf { if (contentReady) 1f else 0f }
    }

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
            lastVisibleItem != null && lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 2
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

    // 点击 token,确保只有被点击的卡片参与转场(避免同 tmdbId 海报跨栏目飘错)
    var activeClickToken by remember { mutableStateOf(0) }

    CompositionLocalProvider(
        LocalActivePosterTmdbId provides activePosterTmdbId,
        LocalActivePosterClickSetter provides { id ->
            activePosterTmdbId = id
            activeClickToken += 1
            activeClickToken
        },
        LocalActivePosterClickToken provides activeClickToken
    ) {
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
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
                    .hazeSource(state = detailHazeState, zIndex = 0f)
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
            CompositionLocalProvider(LocalFullscreenSharedKey provides fullscreenSharedKey) {
            // Tab 栏底色/文字颜色计算(在 LazyColumn 之外定义,让内容区也能用)
            // - 非吸顶(tab 还在海报下方):底色透明,文字按海报主色亮度自适应
            // - 吸顶(tab 滚动到顶部固定):底色为沉浸色与白色 0.5 混合,文字按底色亮度自适应
            //   不透明:tab 吸顶后使用实色底色,不与下方内容透叠
            val isPinned by remember {
                derivedStateOf { listState.firstVisibleItemIndex >= 1 }
            }
            val tabContainerColor = if (isPinned) {
                uiState.posterDominantColor?.let { c ->
                    lerp(MaterialTheme.colorScheme.background, c, 0.635f)
                } ?: MaterialTheme.colorScheme.surface
            } else {
                Color.Transparent
            }
            val tabContentColor = when {
                isPinned && tabContainerColor.luminance() <= 0.5f -> Color.White
                !isPinned -> {
                    // 非吸顶时 tab 在渐变中段,用该位置混合色亮度判断文字颜色
                    val midColor = uiState.posterDominantColor?.let { c ->
                        lerp(c, MaterialTheme.colorScheme.background, 0.8f)
                    } ?: MaterialTheme.colorScheme.background
                    if (midColor.luminance() <= 0.5f) Color.White else MaterialTheme.colorScheme.onSurface
                }
                else -> MaterialTheme.colorScheme.onSurface
            }

            // 吸顶时状态栏区域背景与 tabContainerColor 一致,非吸顶透明(透出渐变)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(tabContainerColor)
                    .align(Alignment.TopCenter)
            )

            // 回调 lambda remember 化:避免每次重组都新建实例(与 MovieCard 一致),
            // 减少分配并为后续按字段跳过重组打基础。置于 LazyColumn 之前(@Composable 上下文)。
            val onToggleWatched = remember { { viewModel.toggleWatched() } }
            val onToggleWatchlist = remember { { viewModel.toggleWatchlist() } }
            val onShowRatingDialog = remember { { showRatingDialog = true } }
            val onDismissRatingDialog = remember { { showRatingDialog = false; viewModel.dismissRatingDialog() } }
            val onRatingSelected = remember { { rating: Int? -> if (rating == null || rating == 0) viewModel.removeRating() else viewModel.setRating(rating) } }
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
            // 单 LazyColumn：头部(item) + TabRow(stickyHeader) + 内容(根据Tab切换)
            // 通过 LocalContentColor 把 tabContentColor 传下去,内部搜索源/网盘类型/找到xx个资源等文字可自适应
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides tabContentColor
            ) {
            // 将详情内容整体作为唯一内容 source，避免 LazyColumn 自身的绘制层影响 Haze 采样。
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
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
                        onDismissRatingDialog = onDismissRatingDialog,
                        onRatingSelected = onRatingSelected,
                        onPosterClick = onPosterClick,
                        onPersonClick = onPersonClick,
                        onToggleSeason = onToggleSeason,
                        onToggleEpisodeWatched = onToggleEpisodeWatched,
                        onRetryRatings = viewModel::retryRatings,
                        onRetryCredits = viewModel::retryCredits,
                        onRetrySeasons = viewModel::retrySeasons,
                        onVideoClick = onVideoClick,
                        onBackdropClick = onBackdropClick,
                        onShowAllVideos = onShowAllVideos,
                        onCollectionMovieClick = onCollectionMovieClick,
                        posterColorExtractor = viewModel.posterColorExtractor,
                        onPosterColorExtracted = viewModel::updatePosterColor,
                        sectionVisible = uiState.sectionVisible,
                        hazeState = detailHazeState,
                        // 头部下方内容(cast/视频/简介/季集)淡入,海报+标题+按钮始终可见
                        contentAlpha = contentAlpha,
                        onHeaderAnchorBoundsChanged = { detailHeaderBounds = it }
                    )
                }

                // 转场期间(contentReady=false)跳过 Tab 行和所有 Tab 内容组合,
                // 首帧只组合 header(海报+标题+按钮),大幅降低转场期间首帧工作量。
                // contentReady 由 posterDominantColor 就绪或 400ms 兜底触发,转场结束后即 true。
                if (contentReady) {
                // Tab 行（吸顶，共用同一个）
                stickyHeader(key = "tab_row") {
                    // isPinned / tabContainerColor / tabContentColor 在 LazyColumn 外已计算
                    // 吸顶时 Tab 栏使用实色背景,indicator 保持主题色
                    PrimaryTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = tabContainerColor,
                        contentColor = tabContentColor,
                        modifier = Modifier
                            .alpha(contentAlpha)
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
                                item(key = "load_more") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.detail_load_more_text, displayedCount, items.size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else if (items.size > initialCount) {
                                item(key = "all_loaded") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.detail_all_loaded, items.size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
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
                    // 评论标题已移除，翻译按钮单独右对齐显示
                    if (translatableComments.isNotEmpty() && uiState.translatedComments.size < translatableComments.size) {
                        item(key = "comments_translate") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
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
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 3.dp
                                )
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
                                Text(
                                    text = stringResource(R.string.detail_no_comments),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

                    if (uiState.hasMoreComments || uiState.isLoadingMoreComments) {
                        item(key = "load_more_comments") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isLoadingMoreComments) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                                } else {
                                    Text(
                                        text = stringResource(R.string.detail_load_more_comments),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (commentsToShow.size > 5) {
                        item(key = "all_comments_loaded") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_all_comments_loaded, commentsToShow.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ===== 推荐 Tab 内容 =====
                if (uiState.sectionVisible.recommendations && selectedTab == (if (uiState.sectionVisible.comments) 2 else 1)) {
                    val recommendations = uiState.recommendations
                    when {
                        uiState.isLoadingRecommendations -> {
                            item(key = "rec_loading") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
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
                                    Text(
                                        text = stringResource(R.string.detail_no_recommendations),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            } // CompositionLocalProvider
            } // CompositionLocalProvider(LocalFullscreenSharedKey)
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

            // 未登录用户引导登录弹窗
            if (uiState.showLoginPrompt) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissLoginPrompt() },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    title = { Text(stringResource(R.string.detail_login_required_title)) },
                    text = { Text(stringResource(R.string.detail_login_required_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.dismissLoginPrompt()
                            onNavigateToLogin()
                        }) {
                            Text(stringResource(R.string.detail_login_go))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissLoginPrompt() }) {
                            Text(stringResource(android.R.string.cancel))
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

@Composable
private fun SearchingState(completedSources: Int, totalSources: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.detail_searching),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.detail_no_resources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = { view.performHaptic(HapticType.CLICK); onRetry() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.detail_retry))
            }
        }
    }
}

// 以下 Composable 已拆分到独立文件：
// - DetailHeaderContent.kt: DetailHeaderContent, ExpandableText
// - DetailRatingsDialog.kt: RatingsRow, RatingBadge, UserRatingBar, RatingDialog
// - DetailCrewSection.kt: CrewSection, CastCard, FullCastCrewSheet, SectionHeader, FullCastItem
// - DetailSeasonsSection.kt: SeasonBadge, WatchedProgressBar, SeasonsSection, EpisodeRow, CollectionSection
// - DetailMarkWatchedDialog.kt: MarkWatchedDialog
// - DetailFilterSection.kt: FilterSection
// - DetailComments.kt: CommentItem
// - DetailVideosImages.kt: VideosAndImagesSection, VideoCard, BackdropCard, FullVideosImagesSheet, FullVideoItem, FullBackdropItem, YouTubePlayerOverlay, BackdropPagerOverlay
// - DetailPosterOverlay.kt: PosterFullscreenOverlay
