package com.tracktosearch.ui.screen.listdetail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.MovieCardSkeleton
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.backdropContentSource
import com.tracktosearch.ui.component.rememberCachedPosterAmbientColor
import com.tracktosearch.ui.component.rememberPosterPrefetch
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.LoadMoreFooter
import com.tracktosearch.ui.component.LoadMoreFooterState
import com.tracktosearch.ui.component.appMorphContentFade
import com.tracktosearch.ui.component.appSharedBounds
import com.tracktosearch.ui.component.traktListSharedKey
import com.tracktosearch.ui.component.SharedOrigin
import com.tracktosearch.ui.component.SharedCorner
import com.tracktosearch.ui.component.TraktListCardCorner
import com.tracktosearch.ui.component.TopBarBackdropBlurRadius
import com.tracktosearch.ui.component.appSkipToLookaheadSize
import com.tracktosearch.ui.component.isAppSharedTransitionActive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun TraktListDetailScreen(
    onBack: () -> Unit,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    viewModel: TraktListDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val watchlistWatchedIds by viewModel.watchlistWatchedIds.collectAsStateWithLifecycle()
    // rememberSaveable + Saver：进入详情页返回后恢复原滚动位置，不再回到顶部
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    // 富化是分批回来的，items 一批换一次；海报列表提出来 remember，避免每次重组重新分配
    val posterUrls = remember(uiState.items) { uiState.items.mapNotNull { it.posterUrl } }
    val listGlassScene = glassSceneForContent(
        contentCount = uiState.items.size,
        readabilityDemand = when {
            uiState.error != null -> 0.96f
            uiState.isLoading -> 0.78f
            uiState.items.isNotEmpty() -> 0.82f
            else -> 0.62f
        },
        ambientColor = rememberCachedPosterAmbientColor(
            posterUrls = posterUrls,
            fallback = MaterialTheme.colorScheme.background
        ),
        contentCapacity = 42,
        loadingCount = if (uiState.isLoading || uiState.isLoadingMore) 1 else 0,
        loadingItemWeight = 4
    )
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 顶栏与设置页三个子页面同一套行为：未滚动时完全透明，内容滚到顶栏下面才显出玻璃底。
    // 转场期一律按「未滚动」处理，容器变形每帧背景都在变，实时模糊在这段时间只是白烧 GPU。
    val hasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = gridState.firstVisibleItemScrollOffset
            )
        }
    }
    // 共享元素转场 scope（标题栏整体与发现页社区列表卡片配对）
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    BackHandler(enabled = true) { onBack() }

    // 滚动到底部时加载更多
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null && lastVisibleIndex >= uiState.items.size - 3) {
                    viewModel.loadMore()
                }
            }
    }

    // 网格滚动预取：posterUrl 已是完整 URL，与 MovieCard 内的取图地址一致，预热即将滚入视口的海报
    if (uiState.items.isNotEmpty()) {
        val prefetchUrls = remember(uiState.items) { uiState.items.map { it.posterUrl } }
        rememberPosterPrefetch(gridState, prefetchUrls)
    }

    // 骨架屏与未到位的海报共享一条 shimmer 动画：一屏九到十几个方块各跑一条会白烧一帧的时间
    val shimmer = rememberShimmer()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // 容器色置透明、底色改由下面那个共享节点自己画，理由见该处注释。
        // contentColor 显式写成 onBackground：Scaffold 默认取 contentColorFor(containerColor)，
        // 而 contentColorFor(Transparent) 是 Unspecified，会让整页文字颜色退回外层 LocalContentColor。
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) { padding ->
        // 与发现页社区列表卡片配对的是整页，而不是顶栏：卡片放大成页面、返回时收回成卡片。
        val transitionActive = isAppSharedTransitionActive()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .appSharedBounds(
                    key = traktListSharedKey(uiState.listId),
                    animatedVisibilityScope = animatedVisibilityScope,
                    corner = SharedCorner.flattenFrom(TraktListCardCorner),
                    // 容器变形要的是「内容不变形、被裁剪逐渐露出」，默认的 scaleToBounds 会把内容
                    // 跟着容器一起缩放绘制。逐帧重测的代价由内容侧的 appSkipToLookaheadSize 挡掉。
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                )
                // 页面底色挪进共享节点内侧，并把 Scaffold 的容器色置透明。
                // 否则 Scaffold 会在共享节点之外先铺满一整屏不透明底色，转场第一帧整屏就已经是本页的背景，
                // 「卡片长成页面」退化成「页面已经在了，只是内容从一个小矩形里长出来」。
                // 挪进来之后底色跟着动画边界一起长大，且被上面那层圆角动画裁剪，落定后与原来逐像素相同。
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .appMorphContentFade()
            ) {
                when {
                    // 首次加载：骨架屏
                    uiState.isLoading && uiState.items.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .appSkipToLookaheadSize()
                                    .hazeSource(state = hazeState)
                                    .backdropContentSource(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 65.dp + statusBarHeight, bottom = 16.dp)
                            ) {
                                // 3 列，铺满首屏需要 9 个；6 个会在屏幕下方留一段空白
                                items(9) {
                                    MovieCardSkeleton(shimmer = shimmer)
                                }
                            }

                            // Haze 模糊标题栏：不参与配对，但从第一帧就在 —— 与网格一样按落定尺寸布局，
                            // 跟着容器裁剪逐渐露出；延迟入场会让顶栏位置先空着，落位时再整片闪出来。
                            // 骨架阶段列表还没滚过，顶栏按透明处理，与真实内容态的未滚动状态一致
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .appSkipToLookaheadSize()
                                    .hazeTopBar(
                                        state = hazeState,
                                        style = hazeStyle,
                                        blurRadius = TopBarBackdropBlurRadius,
                                        isContentUnderTopBar = false,
                                        scene = listGlassScene
                                    )
                                    .clickable(enabled = false, onClick = {})
                            ) {
                                Spacer(modifier = Modifier.statusBarsPadding())
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = onBack) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.ArrowBack,
                                            contentDescription = stringResource(R.string.search_back),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        // 榜单名是导航参数，进页就有；骨架阶段直接显示，避免「加载中」再跳成真名闪一下。
                                        // 从别处进来没带名字时才退回「加载中」
                                        text = uiState.listName.ifBlank { stringResource(R.string.common_loading) },
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // 错误且无数据
                    uiState.error != null && uiState.items.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            AppErrorState(
                                message = uiState.error!!,
                                onRetry = { viewModel.retry() }
                            )
                        }
                    }

                    // 正常内容
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            modifier = Modifier
                                .fillMaxSize()
                                .appSkipToLookaheadSize()
                                .hazeSource(state = hazeState)
                                .backdropContentSource(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 65.dp + statusBarHeight, bottom = 16.dp)
                        ) {
                            itemsIndexed(uiState.items, key = { _, item -> "${item.type}-${item.traktId}" }) { _, item ->
                                val isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.traktId, item.tmdbId, item.type) == true
                                val isWatched = watchlistWatchedIds?.isWatched(item.traktId, item.tmdbId, item.type) == true
                                MovieCard(
                                    title = item.title,
                                    year = item.year,
                                    genres = "",
                                    // posterUrl 已是完整 URL（ViewModel 统一），不要再拼尺寸前缀
                                    posterUrl = item.posterUrl,
                                    tmdbId = item.tmdbId,
                                    origin = SharedOrigin.of(SharedOrigin.TRAKT_LIST, uiState.listId.toString()),
                                    isInWatchlist = isInWatchlist,
                                    isWatched = isWatched,
                                    posterShimmer = shimmer,
                                    onClick = {
                                        when (item.type) {
                                            MediaType.MOVIE -> onMovieClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating, isInWatchlist, isWatched)
                                            MediaType.SHOW -> onShowClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating, isInWatchlist, isWatched)
                                            else -> {}
                                        }
                                    }
                                )
                            }

                            item(span = { GridItemSpan(3) }, key = "load_more_footer") {
                                LoadMoreFooter(
                                    state = when {
                                        uiState.isLoadingMore -> LoadMoreFooterState.Loading
                                        uiState.error != null && uiState.items.isNotEmpty() -> LoadMoreFooterState.Error
                                        uiState.items.isNotEmpty() && !uiState.hasMore -> LoadMoreFooterState.Complete
                                        else -> LoadMoreFooterState.Hidden
                                    },
                                    onRetry = viewModel::loadMore,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // 快速回顶按钮
                        ScrollToTopButton(
                            gridState = gridState,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 16.dp, end = 16.dp),
                            hazeState = hazeState,
                            scene = listGlassScene
                        )

                        // Haze 模糊标题栏：不参与配对，但从第一帧就在 —— 与网格一样按落定尺寸布局，
                        // 跟着容器裁剪逐渐露出；延迟入场会让顶栏位置先空着，落位时再整片闪出来。
                        // 未滚动时透明、滚动后显玻璃底，转场期仍停 haze 采样
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .appSkipToLookaheadSize()
                                .hazeTopBar(
                                    state = hazeState,
                                    style = hazeStyle,
                                    blurRadius = TopBarBackdropBlurRadius,
                                    isContentUnderTopBar = hasContentUnderTopBar && !transitionActive,
                                    scene = listGlassScene
                                )
                                .clickable(enabled = false, onClick = {})
                        ) {
                            Spacer(modifier = Modifier.statusBarsPadding())
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = stringResource(R.string.content_desc_back),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = uiState.listName,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // 磁盘快照已经渲染、远端第一页还在飞时的细进度条：不遮挡内容，只提示数据可能不是最新。
                            // 放在标题栏最底部，出现/消失不会推动上方的标题。
                            if (uiState.isRefreshing && uiState.items.isNotEmpty()) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .testTag("list_detail_refresh_indicator")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


