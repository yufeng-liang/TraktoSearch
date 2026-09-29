package com.tracktosearch.ui.screen.discoverfilter

import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.component.PosterCard
import com.tracktosearch.ui.component.AdaptiveTwoLineTitle
import com.tracktosearch.ui.component.rememberPosterColorExtraction
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.AppErrorVariant
import com.tracktosearch.ui.component.AppFloatingDialog
import com.tracktosearch.ui.component.bottomScrollFade
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.EmptyStateCard
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.LoadMoreFooter
import com.tracktosearch.ui.component.LoadMoreFooterState
import com.tracktosearch.ui.component.ShimmerState
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.component.shimmer
import com.tracktosearch.ui.navigation.DetailSeedStore
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.backdropContentSource
import com.tracktosearch.ui.component.rememberCachedPosterAmbientColor
import com.tracktosearch.ui.component.rememberPosterPrefetch
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.theme.appSwitchColors
import com.tracktosearch.ui.theme.floatingDialogColor
import com.tracktosearch.ui.haptic.HapticOutcomeEffect
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.appMorphContentFade
import com.tracktosearch.ui.component.appSharedBounds
import com.tracktosearch.ui.component.DiscoverFilterCardKey
import com.tracktosearch.ui.component.DiscoverFilterCardCorner
import com.tracktosearch.ui.component.appSkipToLookaheadSize
import com.tracktosearch.ui.component.isAppSharedTransitionActive
import com.tracktosearch.ui.component.SharedCorner
import com.tracktosearch.ui.component.SharedOrigin
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun DiscoverFilterScreen(
    onBack: () -> Unit,
    onMovieClick: (tmdbId: Int, title: String) -> Unit,
    onShowClick: (tmdbId: Int, title: String) -> Unit,
    viewModel: DiscoverFilterViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    // 「筛选条件没变」的两处早退、以及搜索失败配对的 reject 都从这一行出
    HapticOutcomeEffect(viewModel.hapticOutcomes)
    // 地区名走系统本地化译名，需要 Context
    val filterContext = LocalContext.current
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    // 必须 remember：rememberCachedPosterAmbientColor 内部用列表身份做 remember/LaunchedEffect 的 key，
    // 每次重组都传新列表会让环境色先跳回 fallback 再重读一遍颜色缓存（拖评分滑块时最明显）
    val ambientPosterPaths = remember(uiState.items) { uiState.items.mapNotNull { it.poster_path } }
    val discoverFilterGlassScene = glassSceneForContent(
        contentCount = uiState.items.size,
        readabilityDemand = when {
            uiState.showAdvanced -> 0.94f
            uiState.selectedGenreIds.isNotEmpty() || uiState.selectedCountries.isNotEmpty() ||
                uiState.selectedKeywordIds.isNotEmpty() -> 0.82f
            uiState.isLoading -> 0.76f
            else -> 0.66f
        },
        ambientColor = rememberCachedPosterAmbientColor(
            posterUrls = ambientPosterPaths,
            fallback = MaterialTheme.colorScheme.background
        ),
        contentCapacity = 36,
        loadingCount = if (uiState.isLoading || uiState.isLoadingMore) 1 else 0,
        loadingItemWeight = 4
    )
    // rememberSaveable + Saver：进入详情页返回后恢复原滚动位置，不再回到顶部
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    val haptics = rememberAppHaptics()
    val statusBarHeight = WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding()

    var showGenreDialog by remember { mutableStateOf(false) }
    var showRegionDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }

    // 吸顶栏固定部分（状态栏+标题+Tab+筛选条）的底边，用作列表顶部留白。
    // 之前写死 148.dp，字体放大或 Tab 文案换行时第一条会被压在栏下面。
    // 由筛选条下方的零高度标尺实测，0 表示还没测到，先用写死值兜底。
    val density = LocalDensity.current
    var headerBottom by remember { mutableStateOf(0.dp) }

    // 骨架屏共享一份 shimmer 动画：逐项各建一份会同时跑多个无限动画。
    // 只在骨架屏真的在显示时创建，否则无限动画会一直向 Choreographer 要帧
    val showSkeleton = uiState.isLoading && uiState.items.isEmpty()
    val skeletonShimmer = if (showSkeleton) rememberShimmer() else null

    // 滚动到底部加载更多
    LaunchedEffect(
        gridState,
        uiState.items.size,
        uiState.isLoadingMore,
        uiState.error,
        uiState.currentPage,
        uiState.totalPages
    ) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collectLatest { lastVisibleIndex ->
                if (
                    lastVisibleIndex != null &&
                    uiState.items.isNotEmpty() &&
                    !uiState.isLoading &&
                    !uiState.isLoadingMore &&
                    uiState.error == null &&
                    uiState.currentPage < uiState.totalPages &&
                    lastVisibleIndex >= uiState.items.size - 3
                ) {
                    viewModel.loadMore()
                }
            }
    }

    // 滚动结果列表时自动收起高级面板
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && uiState.showAdvanced) {
                    viewModel.collapseAdvanced()
                }
            }
    }

    // 网格滚动预取：与 DiscoverFilterGridItem 内一致的 URL 生成规则，预热即将滚入视口的海报
    if (uiState.items.isNotEmpty()) {
        val prefetchUrls = remember(uiState.items) {
            uiState.items.map { item ->
                item.poster_path.takeIf { !it.isNullOrBlank() }
                    ?.let { TmdbImageUrls.build(it, TmdbImageUrls.W342) }
            }
        }
        rememberPosterPrefetch(gridState, prefetchUrls, decodeSizePx = 264)
    }

    // 首屏搜索由 ViewModel 的 init 发起：等到这里再发，第一帧 isLoading 还是 false，
    // 用户会先看到一帧空白再看到骨架屏

    // 高级面板展开时,返回手势优先关闭面板而非返回上一页
    BackHandler(enabled = uiState.showAdvanced) {
        viewModel.collapseAdvanced()
    }

    // 与底部入口卡片配对的是整页：卡片放大成页面、返回时收回成卡片。
    // 从顶部圆形筛选图标进来时源侧不配对，本侧只剩淡入，与其他页面一致。
    val transitionActive = isAppSharedTransitionActive()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .appSharedBounds(
                key = DiscoverFilterCardKey,
                animatedVisibilityScope = animatedVisibilityScope,
                corner = SharedCorner.flattenFrom(DiscoverFilterCardCorner),
                // 容器变形要的是「内容不变形、被裁剪逐渐露出」，默认的 scaleToBounds 会把内容
                // 跟着容器一起缩放绘制。逐帧重测的代价由内容侧的 appSkipToLookaheadSize 挡掉。
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            )
            // 页面底色挪进共享节点内侧：容器变形靠裁剪揭示，容器里必须是不透明的，
            // 否则变形期这一片能直接看到下面那一页 —— 打开的瞬间发现页内容会叠在本页上。
            // 底色跟着动画边界一起长大，且被上面那层圆角动画裁剪，落定后与原来逐像素相同。
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .appMorphContentFade()
        ) {
            // ========== 列表内容 ==========
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .appSkipToLookaheadSize()
                    .hazeSource(state = hazeState)
                    .backdropContentSource(),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = if (headerBottom > 0.dp) headerBottom else statusBarHeight + 148.dp,
                    bottom = 24.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when {
                    // 首次加载：骨架铺满一屏（9 格 = 3 行）
                    showSkeleton -> {
                        items(9) { DiscoverFilterGridSkeleton(shimmer = skeletonShimmer) }
                    }
                    // 请求失败：必须排在空结果分支之前，否则失败会落进「没有符合条件的结果」
                    uiState.error != null && uiState.items.isEmpty() && !uiState.isLoading -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            AppErrorState(
                                message = uiState.error!!,
                                onRetry = { viewModel.search() },
                                modifier = Modifier.padding(top = 80.dp)
                            )
                        }
                    }
                    // 空结果
                    uiState.hasSearched && uiState.items.isEmpty() && !uiState.isLoading -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyStateCard(
                                modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                                isDark = isAppDarkTheme(),
                                icon = Icons.Rounded.Star,
                                title = stringResource(R.string.discover_filter_empty)
                            )
                        }
                    }
                    else -> {
                        items(uiState.items, key = { it.id }) { item ->
                            DiscoverFilterGridItem(
                                item = item,
                                isMovie = uiState.type == TmdbRepository.DiscoverType.MOVIE,
                                posterColorExtractor = viewModel.posterColorExtractor,
                                onClick = {
                                    val title = if (item.title.isNotBlank()) item.title else (item.name ?: "")
                                    // 卡片海报来自 TMDB 列表接口（不写详情缓存），交给详情页做首帧种子
                                    DetailSeedStore.remember(
                                        item.id,
                                        item.poster_path?.let { TmdbImageUrls.W342 + it },
                                        (item.release_date.ifBlank { item.first_air_date.orEmpty() })
                                            .take(4).toIntOrNull(),
                                        origin = SharedOrigin.DISCOVER_FILTER,
                                        originalTitle = item.original_title.ifBlank { item.original_name }
                                    )
                                    if (uiState.type == TmdbRepository.DiscoverType.MOVIE) {
                                        onMovieClick(item.id, title)
                                    } else {
                                        onShowClick(item.id, title)
                                    }
                                }
                            )
                        }
                        // 触底反馈区（整行），保持固定高度避免列表跳动
                        item(span = { GridItemSpan(maxLineSpan) }, key = "load_more_footer") {
                            LoadMoreFooter(
                                state = when {
                                    uiState.isLoadingMore -> LoadMoreFooterState.Loading
                                    uiState.error != null && uiState.items.isNotEmpty() -> LoadMoreFooterState.Error
                                    uiState.hasSearched && uiState.items.isNotEmpty() && uiState.currentPage >= uiState.totalPages -> LoadMoreFooterState.Complete
                                    else -> LoadMoreFooterState.Hidden
                                },
                                onRetry = viewModel::loadMore,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        // 翻页失败：已有结果还在，底部给一条可重试的错误条，不清空列表
                        if (uiState.error != null && !uiState.isLoadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                AppErrorState(
                                    message = uiState.error!!,
                                    onRetry = { viewModel.loadMore() },
                                    variant = AppErrorVariant.Inline
                                )
                            }
                        }
                    }
                }
            }

            // 快速回顶按钮
            ScrollToTopButton(
                gridState = gridState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp),
                hazeState = hazeState,
                scene = discoverFilterGlassScene
            )

            // ========== 吸顶栏（Blur，透明底色） ==========
            // 注意：不要在此 Column 上加 .scrollable(state=gridState) —— 那会让高级面板内的拖拽
            // 被转发到 gridState，触发 isScrollInProgress=true 进而 collapseAdvanced()，导致弹窗内滑动
            // 就关闭弹窗的 bug。结果网格的滚动由 LazyVerticalGrid 自己处理即可。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 顶栏不参与配对，但从第一帧就在：与列表一样按落定尺寸布局，跟着容器裁剪逐渐露出。
                    // 延迟入场会让容器长大的那段时间顶栏位置空着，落位时再整片闪出来。转场期停 haze 采样
                    .appSkipToLookaheadSize()
                    .hazeTopBar(
                        state = hazeState,
                        style = hazeStyle,
                        blurRadius = 24.dp,
                        isContentUnderTopBar = if (transitionActive) false else null,
                        scene = discoverFilterGlassScene
                    )
                    // 拦截点击：顶栏覆盖可滚动列表，不消费会让点击穿透到下方列表项
                    .clickable(enabled = false, onClick = {})
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())
                // 标题栏 + 返回箭头
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 「返回箭头 + 标题」不参与配对：发现页那一侧是个圆形筛选图标，两端没有共同内容。
                    // 与整页配对的是 DiscoverFilterCardKey（底部入口卡片那条路）。
                    Row(
                        modifier = Modifier.weight(1f),
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
                            text = stringResource(R.string.discover_filter_title),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        // 结果条数：让用户知道筛出来多少，条件太严时也有个数量感。
                        // 只有「仅展示未标看过」这种服务端不知情的过滤才标"约"
                        if (uiState.totalResults > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.totalResultsApproximate) {
                                    stringResource(R.string.discover_filter_result_count, uiState.totalResults)
                                } else {
                                    stringResource(R.string.discover_filter_result_count_exact, uiState.totalResults)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    // 「重置」把所有条件清空并重新搜一次，是一次真操作而不是入口，按「清除」给 tap
                    TextButton(onClick = {
                        haptics.tap()
                        viewModel.resetFilters(); viewModel.search()
                    }) {
                        Text(stringResource(R.string.discover_filter_reset))
                    }
                }

                // Tab 切换栏：电影 / 电视剧（分段胶囊控件，贴合本页风格）
                MediaTypeSegmented(
                    isMovie = uiState.type == TmdbRepository.DiscoverType.MOVIE,
                    movieText = stringResource(R.string.discover_filter_tab_movie),
                    showText = stringResource(R.string.discover_filter_tab_show),
                    onMovie = {
                        viewModel.switchType(TmdbRepository.DiscoverType.MOVIE); viewModel.search()
                    },
                    onShow = {
                        viewModel.switchType(TmdbRepository.DiscoverType.SHOW); viewModel.search()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)
                )

                // 筛选条件栏：类型 / 地区 / 标签 / 评分 / 高级（可横向滚动，胶囊风格）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 类型
                    GlassFilterChip(
                        selected = uiState.selectedGenreIds.isNotEmpty(),
                        onClick = { showGenreDialog = true },
                        text = stringResource(R.string.discover_filter_genre),
                        count = uiState.selectedGenreIds.size,
                        kind = FilterChipKind.Entry
                    )
                    // 地区
                    GlassFilterChip(
                        selected = uiState.selectedCountries.isNotEmpty(),
                        onClick = { showRegionDialog = true },
                        text = stringResource(R.string.discover_filter_region),
                        count = uiState.selectedCountries.size,
                        kind = FilterChipKind.Entry
                    )
                    // 标签
                    GlassFilterChip(
                        selected = uiState.selectedKeywordIds.isNotEmpty(),
                        onClick = { showTagDialog = true },
                        text = stringResource(R.string.discover_filter_tag),
                        count = uiState.selectedKeywordIds.size,
                        kind = FilterChipKind.Entry
                    )
                    // 评分：满量程时只显示"评分"。恒显示"评分 0.0 - 10.0" 会让人以为已经筛过
                    val ratingFiltered = uiState.voteAverageMin > 0f || uiState.voteAverageMax < 10f
                    GlassFilterChip(
                        selected = ratingFiltered,
                        // 只展开不收起：点评分是想调评分，面板开着时 toggle 会把它收起来，看起来像点了没反应
                        onClick = { viewModel.expandAdvanced() },
                        text = if (ratingFiltered) {
                            stringResource(
                                R.string.discover_filter_rating_range,
                                uiState.voteAverageMin,
                                uiState.voteAverageMax
                            )
                        } else {
                            stringResource(R.string.discover_filter_rating_label)
                        },
                        leadingIcon = Icons.Rounded.Star,
                        // selected 是「评分已筛过」，不是「选中了评分这一项」；点下去只是展开面板
                        kind = FilterChipKind.Entry
                    )
                    // 高级筛选：selected 就是面板的展开态，开合方向感成立，走 Toggle 那档；箭头随展开翻转
                    GlassFilterChip(
                        selected = uiState.showAdvanced,
                        onClick = { viewModel.toggleAdvanced() },
                        text = stringResource(R.string.discover_filter_advanced),
                        trailingIcon = Icons.Rounded.KeyboardArrowDown,
                        trailingRotated = uiState.showAdvanced
                    )
                }

                // 零高度标尺：它的 Y 就是吸顶栏固定部分的底边，直接给列表当顶部留白。
                // 放在这里而不是最外层测高，是因为高级面板展开时列表不该整体往下挪
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            val bottom = with(density) { coords.positionInRoot().y.toDp() }
                            if (bottom > 0.dp && bottom != headerBottom) headerBottom = bottom
                        }
                )

                // 高级筛选展开区（防御性消费垂直拖拽：避免拖拽冒泡到结果列表触发 collapse）
                if (uiState.showAdvanced) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                            .pointerInput(Unit) {
                                // 空实现：消费垂直拖拽手势，防止手势冒泡触发意外行为
                                detectVerticalDragGestures { _, _ -> }
                            },
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // ===== 评分区：标题 + 区间读数胶囊 一行，滑块占满下一行 =====
                        // remember 用 uiState 的值做 key，点了重置本地值才会跟着回到 0-10
                        var localMin by remember(uiState.voteAverageMin) { mutableFloatStateOf(uiState.voteAverageMin) }
                        var localMax by remember(uiState.voteAverageMax) { mutableFloatStateOf(uiState.voteAverageMax) }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                FilterSectionLabel(stringResource(R.string.discover_filter_rating_label))
                                // 区间读数胶囊：拖动中的实时反馈（顶部评分 chip 要等松手）
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 12.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.discover_filter_rating_range, localMin, localMax),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1
                                    )
                                }
                            }
                            // 评分双滑块（步长 1，触感反馈）。
                            // 拖动只改本地状态，松手才写 uiState + 发请求：每帧写 uiState 会让整页重组，
                            // 连毛玻璃顶栏的取色和骨架/场景判断都跟着重跑，拖起来发涩。
                            RangeSlider(
                                value = localMin..localMax,
                                onValueChange = { range ->
                                    if (range.start.toInt() != localMin.toInt() ||
                                        range.endInclusive.toInt() != localMax.toInt()
                                    ) {
                                        haptics.frequentTick()
                                    }
                                    localMin = range.start
                                    localMax = range.endInclusive
                                },
                                // 松手即生效：等「应用」的话，用手势返回或滑动列表收起面板时这次改动就没了，
                                // 而顶部的评分 chip 已经显示成已筛选，看着像生效了
                                onValueChangeFinished = {
                                    haptics.gestureEnd()
                                    viewModel.setVoteRange(localMin, localMax)
                                    viewModel.search()
                                },
                                valueRange = 0f..10f,
                                steps = 9,  // 步长 1（0,1,2,...,10）
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        // ===== 排序方式 =====
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterSectionLabel(stringResource(R.string.discover_filter_sort_by))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TmdbRepository.DiscoverSort.entries.forEach { sort ->
                                    val sortText = when (sort) {
                                        TmdbRepository.DiscoverSort.POPULARITY_DESC -> stringResource(R.string.discover_filter_sort_popularity)
                                        TmdbRepository.DiscoverSort.RELEASE_DATE_DESC -> stringResource(R.string.discover_filter_sort_release_date)
                                        TmdbRepository.DiscoverSort.VOTE_AVERAGE_DESC -> stringResource(R.string.discover_filter_sort_vote_average)
                                    }
                                    GlassFilterChip(
                                        selected = uiState.sortBy == sort,
                                        // 点完立刻生效，理由同评分滑块
                                        onClick = { viewModel.setSortBy(sort); viewModel.search() },
                                        text = sortText,
                                        kind = FilterChipKind.SingleSelect
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        // ===== 年代 =====
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterSectionLabel(stringResource(R.string.discover_filter_decade))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                viewModel.decadeOptions.forEach { opt ->
                                    val decadeText = if (opt.specialLabelRes != null) {
                                        stringResource(opt.specialLabelRes)
                                    } else {
                                        stringResource(R.string.decade_format, opt.startYear)
                                    }
                                    GlassFilterChip(
                                        selected = opt.key in uiState.selectedDecadeKeys,
                                        onClick = { viewModel.toggleDecade(opt.key); viewModel.search() },
                                        text = decadeText
                                    )
                                }
                            }
                        }
                        // ===== 仅展示未标看过（登录后可用）=====
                        if (isLoggedIn) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterSectionLabel(stringResource(R.string.discover_filter_hide_watched))
                                Switch(
                                    checked = uiState.hideWatched,
                                    onCheckedChange = {
                                        haptics.toggle(it)
                                        viewModel.toggleHideWatched()
                                        viewModel.search()
                                    },
                                    colors = appSwitchColors()
                                )
                            }
                        }
                        // 收起面板看结果。面板内各项已经改完即生效，这里的 search() 通常是空操作
                        // （条件没变会被 ViewModel 挡掉），只兜住「条件变了但请求没发出去」的极端情况
                        Button(
                            onClick = {
                                haptics.tap()
                                viewModel.toggleAdvanced()  // 先收起
                                viewModel.search()
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(
                                stringResource(R.string.discover_filter_apply),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // 类型多选弹窗（点外部消失也触发搜索）
    if (showGenreDialog) {
        val genreEntries = if (uiState.type == TmdbRepository.DiscoverType.MOVIE) {
            DiscoverFilterConstants.MOVIE_GENRES
        } else {
            DiscoverFilterConstants.TV_GENRES
        }
        // remember：弹窗里每次勾选都会重组，不缓存就要重新查一遍全部译名
        val options = remember(genreEntries, filterContext) {
            genreEntries.map { (id, labelRes) -> id.toString() to filterContext.getString(labelRes) }
        }
        MultiSelectDialog(
            title = stringResource(R.string.discover_filter_select_genres),
            options = options,
            selectedIds = uiState.selectedGenreIds.map { it.toString() }.toSet(),
            onToggle = { idStr -> viewModel.toggleGenre(idStr.toInt()) },
            onDismiss = {
                showGenreDialog = false
                viewModel.search()  // 点外部消失也生效
            }
        )
    }

    // 地区多选弹窗（滑动列表模式）
    if (showRegionDialog) {
        // regionName 走 Locale 译名查询，四十来个地区每次重组查一遍会拖慢勾选反馈
        val regionOptions = remember(filterContext) {
            DiscoverFilterConstants.REGION_CODES.map { code ->
                code to DiscoverFilterConstants.regionName(filterContext, code)
            }
        }
        MultiSelectDialog(
            title = stringResource(R.string.discover_filter_select_regions),
            options = regionOptions,
            selectedIds = uiState.selectedCountries,
            onToggle = { code -> viewModel.toggleCountry(code) },
            onDismiss = {
                showRegionDialog = false
                viewModel.search()
            },
            useScrollableList = true
        )
    }

    // 标签多选弹窗（滑动列表模式，标签数量多）
    if (showTagDialog) {
        val tagOptions = remember(filterContext) {
            DiscoverFilterConstants.TAGS.map { (id, labelRes) ->
                id.toString() to filterContext.getString(labelRes)
            }
        }
        MultiSelectDialog(
            title = stringResource(R.string.discover_filter_select_tags),
            options = tagOptions,
            selectedIds = uiState.selectedKeywordIds.map { it.toString() }.toSet(),
            onToggle = { idStr -> viewModel.toggleKeyword(idStr.toInt()) },
            onDismiss = {
                showTagDialog = false
                viewModel.search()
            },
            useScrollableList = true
        )
    }
}

/**
 * 网格项：海报卡（评分/年份角标）+ 标题 + 类型。
 * 复用统一 [PosterCard]，与「我的/看单」等页保持一致的视觉与手感。
 */
@Composable
private fun DiscoverFilterGridItem(
    item: TmdbSearchResult,
    isMovie: Boolean,
    posterColorExtractor: PosterColorExtractor,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val title = if (item.title.isNotBlank()) item.title else (item.name ?: "")
    val year = if (isMovie) {
        item.release_date.takeIf { it.length >= 4 }?.substring(0, 4)
    } else {
        (item.first_air_date ?: "").takeIf { it.length >= 4 }?.substring(0, 4)
    }
    val posterUrl = if (!item.poster_path.isNullOrBlank()) {
        TmdbImageUrls.build(item.poster_path, TmdbImageUrls.W342)
    } else null

    // 海报加载成功后预提取主色写入缓存：点进详情页时沉浸色随首帧一起出现。
    val onPosterLoaded = rememberPosterColorExtraction(posterUrl, posterColorExtractor)

    // 类型名：走本地化译名，remember 避免主色渐入重组时每帧重查
    val genreText = remember(item.id, isMovie, context) {
        item.genre_ids.mapNotNull { id ->
            DiscoverFilterConstants.genreName(context, id, isMovie).takeIf { it.isNotBlank() }
        }.joinToString(" / ")
    }

    Column {
        Box {
            PosterCard(
                imageUrl = posterUrl,
                title = title,
                year = year,
                rating = item.vote_average.takeIf { it > 0.0 },
                imageSize = 264,
                onImageSuccess = onPosterLoaded,
                onClick = onClick,
                decorated = false
            )
            // 海报缺失时在卡片内提示，避免空白卡没有任何解释
            if (posterUrl == null) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // 标题（最多两行，字号自适应）
        AdaptiveTwoLineTitle(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            maxFontSize = 14.sp,
            minFontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 1.dp)
        )
        if (genreText.isNotEmpty()) {
            Text(
                text = genreText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp)
            )
        }
    }
}

/**
 * 网格骨架项：海报占位（2:3）+ 标题占位，与真实项布局对齐避免数据到位时跳动。
 * [shimmer] 由调用方共享一份，避免每项各跑一个无限动画。
 */
@Composable
private fun DiscoverFilterGridSkeleton(shimmer: ShimmerState? = null) {
    val state = shimmer ?: rememberShimmer()
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .shimmer(state, RoundedCornerShape(12.dp))
        )
        Box(
            modifier = Modifier
                .padding(start = 4.dp, end = 4.dp, top = 6.dp)
                .fillMaxWidth(0.8f)
                .height(14.dp)
                .shimmer(state, RoundedCornerShape(4.dp))
        )
    }
}

/**
 * 多选弹窗
 * @param useScrollableList 为 true 时使用可滚动的 FlowRow（矩形块标签布局 + 垂直滚动），适合选项较多的场景如地区、标签
 */
@Composable
private fun MultiSelectDialog(
    title: String,
    options: List<Pair<String, String>>,  // (id, displayName)
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    useScrollableList: Boolean = false
) {
    val scrollState = rememberScrollState()
    val fadeColor = floatingDialogColor()
    // 「完成」是这个弹窗唯一的确认按钮，关掉的同时由调用方发起搜索；
    // 点外部关闭走 onDismissRequest，按约定静默。触感由 AppDialogActionRow 统一承担。
    AppFloatingDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirm = DialogAction(
            label = stringResource(R.string.common_done),
            onClick = onDismiss
        )
    ) {
        if (useScrollableList) {
            // 可滚动的矩形块标签布局：FlowRow 自动换行，整体垂直可滚动
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .bottomScrollFade(scrollState, fadeColor)
                    .verticalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (id, name) ->
                    GlassFilterChip(
                        selected = id in selectedIds,
                        onClick = { onToggle(id) },
                        text = name
                    )
                }
            }
        } else {
            // FlowRow 标签布局（无滚动）
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (id, name) ->
                    GlassFilterChip(
                        selected = id in selectedIds,
                        onClick = { onToggle(id) },
                        text = name
                    )
                }
            }
        }
    }
}

/**
 * [GlassFilterChip] 的三种身份。决定它发哪一档触感 —— 三者的差别是「这一次点击在语义上
 * 做了什么」，不是长得像什么，所以不能靠 `selected` 一个布尔量分流。
 */
private enum class FilterChipKind {
    /** 能同时亮多个（类型 / 地区 / 年代）或就地开合（高级筛选）：`selected` 就是这一项自己的状态 */
    Toggle,

    /** 一组里只能亮一个（排序方式）：没有「关掉」这回事，只是把选中位挪了一格 */
    SingleSelect,

    /**
     * 次级入口：点下去是「打开一个弹窗 / 展开一块面板」，`selected` 表示「这一项已有筛选」
     * 而不是「选中了这一项」。按 [Toggle] 分流会在已有筛选时发 TOGGLE_OFF，
     * 手感在说「关掉了」，而实际上什么都没关。
     */
    Entry,
}

/**
 * 筛选胶囊标签（Pill）
 *
 * 未选中：surfaceVariant 半透明 + 极细描边（浅色模式下也能看清边界）；
 * 选中：主题色填充。切换背景/文字色都走动画过渡，按压有轻微缩放，手感更实。
 * 可选 [count]（>0 时在末尾显示计数徽章）、[leadingIcon]（如评分的星）、
 * [trailingIcon]（如高级的下拉箭头，用 [trailingRotated] 控制展开时翻转 180°）。
 * 触感反馈与无障碍语义都收在这里，[kind] 区分触感语义（见 [FilterChipKind]）。
 * [hazeState] 参数已废弃，chip 自身不需要毛玻璃效果。
 */
@Composable
private fun GlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    text: String,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
    kind: FilterChipKind = FilterChipKind.Toggle,
    count: Int = 0,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    trailingRotated: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        label = "chip_scale"
    )
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        label = "chip_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "chip_fg"
    )
    val borderColor = if (selected) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    }
    val trailingRotation by animateFloatAsState(
        targetValue = if (trailingRotated) 180f else 0f,
        label = "chip_trailing_rot"
    )
    val shape = RoundedCornerShape(percent = 50)

    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(background)
            .border(BorderStroke(1.dp, borderColor), shape)
            .semantics {
                this.role = Role.Button
                this.selected = selected
            }
            .hapticClickable(
                interactionSource = interactionSource,
                indication = null,
                semantic = when (kind) {
                    FilterChipKind.SingleSelect -> HapticSemantic.SEGMENT_TICK
                    FilterChipKind.Entry -> HapticSemantic.LIGHT_TAP
                    FilterChipKind.Toggle ->
                        if (selected) HapticSemantic.TOGGLE_OFF else HapticSemantic.TOGGLE_ON
                }
            ) {
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 计数徽章：类型/地区/标签选了几项，一眼可见
        if (count > 0) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    color = if (selected) contentColor else MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
        if (trailingIcon != null) {
            Icon(
                trailingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(trailingRotation)
            )
        }
    }
}

/**
 * 电影 / 电视剧 分段切换控件（Segmented Control）。
 * 比下划线 Tab 更贴合本页胶囊风格：一个圆角容器内两段，选中段主题色填充并动画过渡。
 */
@Composable
private fun MediaTypeSegmented(
    isMovie: Boolean,
    movieText: String,
    showText: String,
    onMovie: () -> Unit,
    onShow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SegmentButton(
            text = movieText,
            selected = isMovie,
            onClick = onMovie,
            modifier = Modifier.weight(1f)
        )
        SegmentButton(
            text = showText,
            selected = !isMovie,
            onClick = onShow,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SegmentButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(percent = 50)
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "segment_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "segment_fg"
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .semantics {
                this.role = Role.Tab
                this.selected = selected
            }
            .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** 高级面板里每个分区的小标题，字重与色值统一在此。 */
@Composable
private fun FilterSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}
