package com.tracktosearch.ui.screen.detail

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiMediaIdsDto
import com.tracktosearch.data.ai.mediaKeyFor
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.AppErrorVariant
import com.tracktosearch.ui.component.AppPullToRefreshIndicator
import com.tracktosearch.ui.component.DetailTopBarIcon
import com.tracktosearch.ui.component.LoadMoreFooter
import com.tracktosearch.ui.component.LoadMoreFooterState
import com.tracktosearch.ui.component.LocalBackdrop
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.MovieCardSkeleton
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.NeumorphicIconButtonStyle
import com.tracktosearch.ui.component.OpenImageViewerItem
import com.tracktosearch.ui.component.openImageViewer
import com.tracktosearch.ui.component.ResourceItemCard
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.detailTopBarIconColor
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.rememberAppPullToRefreshState
import com.tracktosearch.ui.component.rememberOpenImageBounds
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.haptic.HapticOutcomeEffect
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.PopupShowEffect
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.screen.ai.AiSceneEvent
import com.tracktosearch.ui.screen.ai.aiFeaturesEnabled
import com.tracktosearch.ui.screen.ai.AiSpriteAnchor
import com.tracktosearch.ui.screen.ai.AiSpriteInterruptRequest
import com.tracktosearch.ui.screen.ai.AiSpriteMotion
import com.tracktosearch.ui.screen.ai.AiSpriteViewModel
import com.tracktosearch.ui.screen.ai.automaticSpriteArt
import com.tracktosearch.ui.screen.ai.rememberSharedAiSpriteViewModel
import com.tracktosearch.ui.screen.ai.sceneArtFor
import com.tracktosearch.ui.screen.ai.shouldShowWatchlistAddedScene
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.theme.floatingDialogColor
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.ToastEffect
import com.tracktosearch.ui.util.copyResourceLink
import com.tracktosearch.ui.util.openResourceLink
import com.tracktosearch.ui.component.SharedOrigin
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    viewModel: DetailViewModel = hiltViewModel(),
    detailAiViewModel: DetailAiViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val detailAiState by detailAiViewModel.uiState.collectAsStateWithLifecycle()
    val spriteViewModel: AiSpriteViewModel = rememberSharedAiSpriteViewModel()
    val spriteState by spriteViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // OpenImage 查看器以 Activity decorView 为宿主；非 ComponentActivity 场景拿不到时点击不打开
    val activity = context as? Activity
    val haptics = rememberAppHaptics()
    var showRatingDialog by remember { mutableStateOf(false) }
    var detailHeaderBounds by remember { mutableStateOf<Rect?>(null) }
    var detailTabBounds by remember { mutableStateOf<Rect?>(null) }
    var detailAiPanelBounds by remember { mutableStateOf<Rect?>(null) }
    var showWatchlistScene by remember { mutableStateOf(false) }
    // 推荐 tab 下标只由「评论 tab 是否显示」决定；tab 行、精灵锚点、内容分支必须共用同一份，
    // 而精灵挂在 hazeSource 之外，所以提到页面顶层而不是滚动内容那个作用域里。
    val recommendationsTabIndex = if (uiState.sectionVisible.comments) 2 else 1
    // 任何主动操作都先收起 AI 面板：面板压在内容上时用户已经在做别的决定
    val interruptForUserInput = remember { { detailAiViewModel.dismissPanel() } }
    // 评分入口先判断登录态，避免用户填完评分才被告知要登录；
    // 由 ViewModel 根据当前条目选择 Trakt 或豆瓣，不能统一送回激活页。
    val onShowRatingDialog = remember(uiState.isLoggedIn) {
        {
            interruptForUserInput()
            if (uiState.isLoggedIn) {
                showRatingDialog = true
            } else {
                viewModel.requestLoginForCurrentItem()
            }
        }
    }
    // 初值取当前 revision：详情页从子页返回或配置变更时组合会重建，而 revision 活在
    // 会被保留的 ViewModel 里；从 0 起步会把上一次「加入看单」当成新事件重放一遍。
    var handledWatchlistRevision by remember(traktId, tmdbId) {
        mutableStateOf(uiState.watchlistAddedRevision)
    }

    LaunchedEffect(uiState.watchlistAddedRevision, traktId, tmdbId) {
        if (shouldShowWatchlistAddedScene(handledWatchlistRevision, uiState.watchlistAddedRevision)) {
            handledWatchlistRevision = uiState.watchlistAddedRevision
            showWatchlistScene = true
        }
    }

    // 详情页没有激活入口，但加入看单的场景需要激活态。只恢复激活态，
    // 不走 ensureLoaded()——那条路径会拉角色目录并播试听，详情页不该冒出语音。
    LaunchedEffect(Unit) { spriteViewModel.restoreActivation() }

    // 精灵是否具备出场条件：已激活且有对应素材（没素材的角色不参与任何详情页动效）
    val spriteArtAvailable = spriteState.activatedCharacterId
        ?.let { automaticSpriteArt(it) != null } == true
    LaunchedEffect(spriteArtAvailable, spriteState.isAuthorized) {
        // release 关掉 AI 后不启用助手探头（setSpriteEnabled(false) 会连 spriteVisible 一起收，
        // 面板唯一来路是点探头，探头没了面板也开不了）
        detailAiViewModel.setSpriteEnabled(aiFeaturesEnabled && spriteArtAvailable)
    }

    // 面板收起时释放面板锚点矩形，避免下一次面板打开前用旧位置摆精灵
    LaunchedEffect(detailAiState.panelVisible) {
        if (!detailAiState.panelVisible) detailAiPanelBounds = null
    }

    // 离开页面（返回、切 tab、进程退后台）都要收尾本次会话：取消揭示计时并把停留时长记进画像
    DisposableEffect(Unit) {
        onDispose { detailAiViewModel.onScreenStopped() }
    }

    // 拦截系统返回手势/返回键：面板打开时先收面板，再退出详情页
    BackHandler(enabled = true) {
        if (detailAiState.panelVisible) {
            detailAiViewModel.dismissPanel()
        } else {
            detailAiViewModel.onScreenStopped()
            onBack(uiState.watchlistChanged, uiState.watchedChanged)
        }
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

    // 详情页 AI 助手的输入：媒体身份 + 标记状态 + 画像用的元信息。
    // 键里带上富化后的字段，评分/短评/标记一变就重算场景（未标记→已想看→已看过未评→已看过已评）。
    LaunchedEffect(
        traktId,
        tmdbId,
        imdbId,
        doubanId,
        mediaType,
        uiState.displayTitle,
        uiState.title,
        uiState.year,
        uiState.genres,
        uiState.overview,
        uiState.userRating,
        uiState.userComment,
        uiState.isMarkedWatched,
        uiState.isMarkedWatchlist,
        uiState.ratings,
        uiState.cast,
        uiState.crew
    ) {
        val normalizedType = if (mediaType == MediaType.SHOW) "show" else "movie"
        val mediaKey = runCatching {
            mediaKeyFor(
                mediaType = normalizedType,
                tmdbId = tmdbId.takeIf { it > 0 },
                traktId = traktId.takeIf { it > 0 },
                imdbId = imdbId.takeIf { it.isNotBlank() },
                doubanId = doubanId
            )
        }.getOrNull() ?: return@LaunchedEffect
        val genres = uiState.genres
            .split(Regex("\\s*(?:/|、|,|，)\\s*"))
            .map(String::trim)
            .filter(String::isNotBlank)
        detailAiViewModel.updateMedia(
            DetailAiMediaInput(
                mediaKey = mediaKey,
                mediaType = normalizedType,
                title = uiState.displayTitle.ifBlank { uiState.title.ifBlank { title } },
                year = uiState.year ?: year,
                genres = genres,
                overview = uiState.overview.takeIf { it.isNotBlank() },
                directors = uiState.crew.filter { it.job.equals("Director", ignoreCase = true) }
                    .map { it.name }
                    .distinct(),
                cast = uiState.cast.take(12).map { it.name }.distinct(),
                publicRating = uiState.ratings?.traktRating?.takeIf { it > 0 }
                    ?: traktRating.takeIf { it > 0 },
                mediaIds = AiMediaIdsDto(
                    tmdbId = tmdbId.takeIf { it > 0 },
                    traktId = traktId.takeIf { it > 0 }?.toString(),
                    imdbId = imdbId.takeIf { it.isNotBlank() },
                    doubanId = doubanId
                ),
                userRating = uiState.userRating,
                userComment = uiState.userComment,
                watched = uiState.isMarkedWatched,
                watchlist = uiState.isMarkedWatchlist
            )
        )
    }

    // 上面那些 toast、以及豆瓣乐观写失败时的重试按钮，配对的结果类触感都从这一行出
    HapticOutcomeEffect(viewModel.hapticOutcomes)

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
                // Material 自己渲染 action 按钮、拿不到 onClick=，ActionPerformed 分支是唯一的钩子。
                // performAction() 在点击当帧就 resume 这条协程，不用等 Snackbar 退场动画
                haptics.tap()
                // 撤销就是再调一次同一个 toggle：两个 toggle 都是幂等的状态翻转
                when (event.kind) {
                    DetailMarkKind.WATCHLIST -> viewModel.toggleWatchlist()
                    DetailMarkKind.WATCHED -> viewModel.toggleWatched()
                }
            }
        }
    }

    val listState = rememberLazyListState()
    // 滚动即打断：庆祝插画收起（锚点跟着头部矩形走，滑出视口后会在屏幕外播完），
    // 助手探头交给 ViewModel 走 RETREAT，下一次进入按新的停留计时重新揭示。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) return@collect
                if (showWatchlistScene) showWatchlistScene = false else detailAiViewModel.onScroll()
            }
    }
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
    var playingVideoKey by remember { mutableStateOf<String?>(null) }
    var showAllVideos by remember { mutableStateOf(false) }

    // 内容就绪状态：沉浸背景优先显示，其他内容(cast/视频/简介/tab)淡入。
    // posterDominantColor 就绪 → 80ms 后标记就绪（让背景渐变先渲染出来）；
    // 未就绪（首次访问无缓存主色）→ 400ms 后兜底就绪，避免长时间空白。
    // 全屏图片查看器（OpenImage）的缩略图矩形记录表：posterBounds 只记海报（下标 0），
    // backdropBounds 按 backdrops 下标记横向栏剧照；点击回调读取对应矩形作查看器转场起点
    val posterBounds = rememberOpenImageBounds()
    val backdropBounds = rememberOpenImageBounds()
    // 返回时必须沿用已显示状态：重放正文淡入会在返回帧触发一次整页重组，
    // 把共享头像回缩动画的帧挤掉，用户看到的就是"没有转场"。
    var contentReady by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(uiState.posterDominantColor) {
        if (contentReady) return@LaunchedEffect // 已就绪则不重复触发淡入(#26)
        delay(if (uiState.posterDominantColor != null) 80 else 400)
        contentReady = true
    }

    // 正文就绪是助手探头的前提（内容还在淡入时不该冒头），也是「进入前台」的另一半条件
    LaunchedEffect(contentReady) {
        detailAiViewModel.setForegroundReady(contentReady)
    }
    val latestContentReady by rememberUpdatedState(contentReady)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // 回到前台重新计时，避免后台待了很久回来立刻冒头
                Lifecycle.Event.ON_RESUME -> {
                    detailAiViewModel.resumeSession()
                    detailAiViewModel.setForegroundReady(latestContentReady)
                }
                // 退到后台按一次离屏收尾：停留时长记进画像，探头与揭示计时都取消
                Lifecycle.Event.ON_STOP -> detailAiViewModel.onScreenStopped()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 头部下方内容淡入：animateFloatAsState 做真实渐显（0→1 约 220ms），
    // 替代旧 derivedStateOf 0/1 直切（注释宣称淡入但实际没有过渡）。
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
                    .hazeSource(state = detailHazeState, zIndex = 0f)
                    .then(immersiveBackgroundModifier)
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
            val onToggleWatched = remember { { interruptForUserInput(); viewModel.toggleWatched() } }
            val onToggleWatchlist = remember { { interruptForUserInput(); viewModel.toggleWatchlist() } }
            val onPosterClick = remember(uiState.posterUrl, activity) {
                val url = uiState.posterUrl
                {
                    if (url != null && activity != null) {
                        // 海报大图：large 用 original，cover 与 DetailHeaderContent 实际显示的 w780 一致
                        openImageViewer(
                            activity = activity,
                            items = listOf(
                                OpenImageViewerItem(
                                    largeUrl = TmdbImageUrls.swapSize(url, "original"),
                                    coverUrl = TmdbImageUrls.swapSize(url, "w780")
                                )
                            ),
                            bounds = posterBounds,
                            clickedIndex = 0
                        )
                    }
                }
            }
            val onToggleSeason = remember { { season: Int -> viewModel.toggleSeason(season) } }
            val onToggleEpisodeWatched = remember { { season: Int, episode: Int, traktId: Int -> viewModel.toggleEpisodeWatched(season, episode, traktId) } }
            val onVideoClick = remember { { video: TmdbVideo -> interruptForUserInput(); playingVideoKey = video.key } }
            val onBackdropClick = remember(uiState.backdrops, activity) {
                val backdrops = uiState.backdrops
                { index: Int ->
                    interruptForUserInput()
                    if (activity != null && index in backdrops.indices) {
                        // 剧照横向栏：列表显示的是 w780，大图沿用原 original 段替换逻辑
                        openImageViewer(
                            activity = activity,
                            items = backdrops.map { url ->
                                OpenImageViewerItem(
                                    largeUrl = url.replace("/w780/", "/original/"),
                                    coverUrl = url
                                )
                            },
                            bounds = backdropBounds,
                            clickedIndex = index
                        )
                    }
                }
            }
            val onShowAllVideos = remember { { interruptForUserInput(); showAllVideos = true } }
            val onCollectionMovieClick = remember(onMovieClick) { { movieTmdbId: Int, movieTitle: String -> interruptForUserInput(); onMovieClick(0, movieTmdbId, movieTitle, "", 0.0) } }
            // Tab 数量计算（置于 LazyColumn 之前的 @Composable 上下文，并用副作用修正 selectedTab 范围）
            val showCommentsTab = uiState.sectionVisible.comments
            val showRecommendationsTab = uiState.sectionVisible.recommendations
            val tabCount = 1 + (if (showCommentsTab) 1 else 0) + (if (showRecommendationsTab) 1 else 0)
            LaunchedEffect(tabCount) {
                if (selectedTab > tabCount - 1) selectedTab = tabCount - 1
            }
            // 切到推荐 tab 才请求 AI 排序：不在别的 tab 上提前烧额度，也不与详情加载抢带宽；
            // release 关掉 AI 后不发起排序请求，推荐 tab 保持 TMDB 原序
            LaunchedEffect(
                selectedTab,
                recommendationsTabIndex,
                showRecommendationsTab,
                detailAiState.scene,
                uiState.recommendations
            ) {
                if (aiFeaturesEnabled &&
                    showRecommendationsTab &&
                    selectedTab == recommendationsTabIndex &&
                    uiState.recommendations.isNotEmpty()
                ) {
                    detailAiViewModel.loadRecommendations(uiState.recommendations)
                }
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
                        posterBounds = posterBounds,
                        onPersonClick = onPersonClick,
                        onToggleSeason = onToggleSeason,
                        onToggleEpisodeWatched = onToggleEpisodeWatched,
                        onRetryRatings = viewModel::retryRatings,
                        onRetryCredits = viewModel::retryCredits,
                        onRetrySeasons = viewModel::retrySeasons,
                        onRetryVideos = viewModel::retryVideos,
                        onVideoClick = onVideoClick,
                        onBackdropClick = onBackdropClick,
                        backdropBounds = backdropBounds,
                        onShowAllVideos = onShowAllVideos,
                        onCollectionMovieClick = onCollectionMovieClick,
                        posterColorExtractor = viewModel.posterColorExtractor,
                        onPosterColorExtracted = viewModel::updatePosterColor,
                        sectionVisible = uiState.sectionVisible,
                        // 头部下方内容(cast/视频/简介/季集)淡入,海报+标题+按钮始终可见
                        contentAlpha = contentAlpha,
                        onHeaderAnchorBoundsChanged = { detailHeaderBounds = it }
                    )
                }

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
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            // 推荐 tab 上的助手精灵要以 Tab 行为锚点，这里记下吸顶后的真实位置
                            modifier = Modifier.onGloballyPositioned { detailTabBounds = it.boundsInRoot() }
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { haptics.segmentTick(); interruptForUserInput(); selectedTab = 0 },
                                text = { Text("${stringResource(R.string.detail_tab_resources)}(${uiState.resources.size})", maxLines = 1) }
                            )
                            if (showCommentsTab) {
                                Tab(
                                    selected = selectedTab == 1,
                                    onClick = { haptics.segmentTick(); interruptForUserInput(); selectedTab = 1 },
                                    text = { Text("${stringResource(R.string.detail_tab_comments)}(${uiState.comments.size})", maxLines = 1) }
                                )
                            }
                            if (showRecommendationsTab) {
                                Tab(
                                    selected = selectedTab == recommendationsTabIndex,
                                    onClick = { haptics.segmentTick(); interruptForUserInput(); selectedTab = recommendationsTabIndex },
                                    text = {
                                        // 计数跟内容一致：AI 排序结果已落地就用它的条数，否则沿用 TMDB 的
                                        Text(
                                            "${stringResource(R.string.detail_tab_recommendations)}(${
                                                detailRecommendationCount(
                                                    detailAiState.recommendationsLoaded,
                                                    detailAiState.recommendations,
                                                    uiState.recommendations
                                                )
                                            })",
                                            maxLines = 1
                                        )
                                    }
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
                                    // 这块提示只在「仅显示高相关」开着时出现，点它必然是关掉
                                    .hapticClickable(semantic = HapticSemantic.TOGGLE_OFF) {
                                        viewModel.toggleShowHighRelevanceOnly()
                                    },
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
                                    onClick = { haptics.tap(); viewModel.translateComments() },
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
                            BareEmptyHint(
                                icon = Icons.Rounded.ChatBubbleOutline,
                                title = stringResource(R.string.detail_no_comments)
                            )
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
                if (uiState.sectionVisible.recommendations && selectedTab == recommendationsTabIndex) {
                    // AI 排序落地前先展示 TMDB 原序，落地后整列换成 AI 结果（不拼接，避免顺序反复跳）
                    val recommendations = if (detailAiState.recommendationsLoaded) {
                        detailAiState.recommendations
                    } else {
                        uiState.recommendations
                    }
                    when {
                        uiState.isLoadingRecommendations && recommendations.isEmpty() -> {
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
                                BareEmptyHint(
                                    icon = Icons.Rounded.Movie,
                                    title = stringResource(R.string.detail_no_recommendations)
                                )
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
                }
            } // CompositionLocalProvider(LocalContentColor)
            } // hazeSource Box 结束：采样源只包住状态栏底色 + 滚动内容

            // 悬浮控件与全屏覆盖层必须与 hazeSource 保持兄弟关系。
            // Haze 2.x 的默认 HazeSourceSelection.Behind 会先找 hazeBlur 节点最近的祖先
            // hazeSource（同一 HazeState），再只保留 zIndex 小于它的源；顶栏按钮/回顶按钮
            // 一旦落在采样源子树内，祖先源 zIndex=0 会把自己过滤掉（0 < 0 不成立），
            // 结果 blur/glass 两种模式下模糊都静默失效（源列表为空，不报错也不模糊）。
            //
            // 一个精灵两条来路：加入看单的庆祝插画（showWatchlistScene）与 AI 助手探头。
            // 庆祝优先——用户刚做完标记动作，先放完完整插画再回到助手，两者同时可见会互相盖住。
            val recommendationsTabVisible = uiState.sectionVisible.recommendations &&
                selectedTab == recommendationsTabIndex
            // 面板打开时精灵跟到面板边上；推荐 tab 上贴着 Tab 行探头；其余时间蹲在头部
            val assistantAnchor = when {
                detailAiState.panelVisible -> AiSpriteAnchor.DetailPanel
                recommendationsTabVisible -> AiSpriteAnchor.RecommendationsTab
                else -> AiSpriteAnchor.DetailHeader
            }
            val assistantBounds = when (assistantAnchor) {
                AiSpriteAnchor.RecommendationsTab -> detailTabBounds
                AiSpriteAnchor.DetailPanel -> detailAiPanelBounds
                else -> detailHeaderBounds
            }
            val assistantWantsSprite = spriteArtAvailable &&
                (detailAiState.spriteVisible || detailAiState.panelVisible)
            AiSpriteMotion(
                characterId = spriteState.activatedCharacterId.orEmpty(),
                anchor = if (showWatchlistScene) AiSpriteAnchor.DetailHeader else assistantAnchor,
                anchorBounds = if (showWatchlistScene) detailHeaderBounds else assistantBounds,
                visible = if (showWatchlistScene) {
                    spriteArtAvailable && detailHeaderBounds != null
                } else {
                    assistantWantsSprite && assistantBounds != null
                },
                onClick = {
                    // 庆祝插画只是插画，点它不打开面板
                    if (!showWatchlistScene) detailAiViewModel.onSpriteClick()
                },
                onFinished = {
                    if (showWatchlistScene) showWatchlistScene = false
                    else detailAiViewModel.onSpriteFinished()
                },
                modifier = Modifier.zIndex(5f),
                sceneRes = if (showWatchlistScene) {
                    sceneArtFor(AiSceneEvent.DETAIL_WATCHLIST_ADDED).drawableRes
                } else {
                    null
                },
                // 加入看单的庆祝插画，不该变成盖在详情页上的可点区域
                interactive = !showWatchlistScene,
                // 庆祝期间不受面板中断请求影响，否则刚标记完就被上一次会话的中断打回去
                interruptRequest = if (showWatchlistScene) {
                    null
                } else {
                    AiSpriteInterruptRequest(
                        revision = detailAiState.interruptRevision,
                        reason = detailAiState.interruptReason
                    )
                }
            )

            DetailAiPanel(
                state = detailAiState,
                onDismiss = detailAiViewModel::dismissPanel,
                onGrantProfileConsent = detailAiViewModel::grantProfileConsent,
                onNavigateToLogin = onTraktLogin,
                onRetry = detailAiViewModel::retryAnalysis,
                onOpenReview = {
                    detailAiViewModel.dismissPanel()
                    // 评分入口要先过登录态，这里复用同一个 lambda 而不是直接开弹窗
                    onShowRatingDialog()
                },
                onBoundsChanged = { detailAiPanelBounds = it }
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
                onClick = { onBack(uiState.watchlistChanged, uiState.watchedChanged) },
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
                    NeumorphicIconButton(
                        onClick = {
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

            // 全部预告片与截图弹窗
            if (showAllVideos) {
                FullVideosImagesSheet(
                    videos = uiState.videos,
                    backdrops = uiState.backdrops,
                    backdropBounds = backdropBounds,
                    onDismiss = { showAllVideos = false },
                    onVideoClick = { video ->
                        playingVideoKey = video.key
                    },
                    onBackdropClick = { index ->
                        // sheet 保持打开直接走带转场入口：ModalBottomSheet 的 Dialog 是全屏窗口，
                        // 缩略图 boundsInWindow 就是屏幕坐标，OpenImage 能精确落到 sheet 内的缩略图；
                        // 返回时 sheet 仍开着，动画缩回原位。
                        val host = activity
                        if (host != null && index in uiState.backdrops.indices) {
                            openImageViewer(
                                activity = host,
                                items = uiState.backdrops.map { url ->
                                    OpenImageViewerItem(
                                        largeUrl = url.replace("/w780/", "/original/"),
                                        coverUrl = url
                                    )
                                },
                                bounds = backdropBounds,
                                clickedIndex = index,
                                onExit = { /* sheet 保持打开，返回即回到缩略图原位 */ }
                            )
                        }
                    }
                )
            }

            // 未登录用户引导登录弹窗：确认后直达当前条目对应的平台网页登录流程。
            if (uiState.showLoginPrompt) {
                val loginTarget = uiState.loginTarget
                val dismissLoginPrompt: () -> Unit = viewModel::dismissLoginPrompt
                AlertDialog(
                    onDismissRequest = dismissLoginPrompt,
                    containerColor = floatingDialogColor(),
                    title = { Text(stringResource(R.string.detail_login_required_title)) },
                    text = { Text(stringResource(R.string.detail_login_required_message)) },
                    confirmButton = {
                        // AlertDialog 的每个槽是独立 subcomposition，各取一份
                        val confirmHaptics = rememberAppHaptics()
                        TextButton(onClick = {
                            confirmHaptics.tap()
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
                        val dismissHaptics = rememberAppHaptics()
                        TextButton(onClick = { dismissHaptics.lightTap(); dismissLoginPrompt() }) {
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
            // 只对**自动弹出**那一路发 popupShow：uiState.showRatingDialog 是
            // markAsWatched 的 onSuccess 里置的，网络回来才弹，与手势无关。
            // 本地那个 showRatingDialog 是点评分区域同一帧置的，那一下已经震过了
            PopupShowEffect(uiState.showRatingDialog)
            // 自动弹出的那一路绕过了 interruptForUserInput，面板会残留在弹窗背后
            LaunchedEffect(uiState.showRatingDialog) {
                if (uiState.showRatingDialog) detailAiViewModel.dismissPanel()
            }
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
                        // 触感由 RatingDialog 里「确定」按钮自己发（弹窗有自己的宿主 View），
                        // 这里不再补一记，否则同一次点击两震
                        // 不调用 dismissRatingDialog():setRatingWithComment/removeRating 内部会关闭弹窗并处理豆瓣同步
                        // 否则会先 syncDoubanMark(COLLECT) 再 syncDoubanMarkWithRating,导致两次豆瓣同步 toast
                        viewModel.confirmRatingWithComment(rating, comment)
                    }
                )
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
    val haptics = rememberAppHaptics()
    // 不用 EmptyStateCard：资源 Tab 夹在筛选器和列表之间，再垫一块玻璃卡片
    // 会读作「一张内容为空的资源卡」而不是「搜索没有结果」
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.detail_no_resources),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = { haptics.tap(); onRetry() }) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.detail_retry))
        }
    }
}

/**
 * 短评 / 推荐空态提示：裸排，不带卡片。同 [EmptyState] 的理由 ——
 * 玻璃卡片会读作「一张内容为空的卡片」而不是「这里没有内容」。
 */
@Composable
private fun BareEmptyHint(icon: ImageVector, title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
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
// - DetailVideosImages.kt: VideosAndImagesSection, VideoCard, BackdropCard, FullVideosImagesSheet, FullVideoItem, FullBackdropItem, YouTubePlayerOverlay
// 全屏图片查看器已迁移到 OpenImage（OpenImageViewer.kt 桥接层）
