package com.tracktosearch.ui.screen.discoverfilter

import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.ui.theme.RatingGold
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.AppErrorVariant
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.navigation.DetailSeedStore
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.backdropContentSource
import com.tracktosearch.ui.component.rememberCachedPosterAmbientColor
import com.tracktosearch.ui.component.rememberPosterPrefetch
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.theme.appSwitchColors
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import androidx.compose.animation.ExperimentalSharedTransitionApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
    // 地区名走系统本地化译名，需要 Context
    val filterContext = LocalContext.current
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
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
            posterUrls = uiState.items.mapNotNull { it.poster_path },
            fallback = MaterialTheme.colorScheme.background
        ),
        contentCapacity = 36,
        loadingCount = if (uiState.isLoading || uiState.isLoadingMore) 1 else 0,
        loadingItemWeight = 4
    )
    // rememberSaveable + Saver：进入详情页返回后恢复原滚动位置，不再回到顶部
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    val view = LocalView.current
    val statusBarHeight = WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding()

    var showGenreDialog by remember { mutableStateOf(false) }
    var showRegionDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }

    // 滚动到底部加载更多
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collectLatest { lastVisibleIndex ->
                if (lastVisibleIndex != null && lastVisibleIndex >= uiState.items.size - 4) {
                    viewModel.loadMore()
                }
            }
    }

    // 滚动结果列表时自动收起高级面板
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && uiState.showAdvanced) {
                    viewModel.collapseAdvanced()
                }
            }
    }

    // 列表滚动预取：与 DiscoverFilterListItem 内一致的 URL 生成规则，预热即将滚入视口的海报
    if (uiState.items.isNotEmpty()) {
        val prefetchUrls = remember(uiState.items) {
            uiState.items.map { item ->
                item.poster_path.takeIf { !it.isNullOrBlank() }
                    ?.let { TmdbImageUrls.build(it, TmdbImageUrls.W185) }
            }
        }
        rememberPosterPrefetch(listState, prefetchUrls)
    }

    // 首次进入自动搜索
    LaunchedEffect(Unit) {
        if (!uiState.hasSearched) viewModel.search()
    }

    // 高级面板展开时,返回手势优先关闭面板而非返回上一页
    BackHandler(enabled = uiState.showAdvanced) {
        viewModel.collapseAdvanced()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
                    with(sharedTransitionScope) {
                        Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "discover-filter-entry-card"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // ========== 列表内容 ==========
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .backdropContentSource(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = statusBarHeight + 148.dp,  // 为吸顶栏留空间（标题+Tab+筛选条）
                bottom = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                // 首次加载：3 个骨架项
                uiState.isLoading && uiState.items.isEmpty() -> {
                    items(3) { DiscoverFilterItemSkeleton() }
                }
                // 请求失败：必须排在空结果分支之前。否则失败会落进「没有符合条件的结果」，
                // 用户以为条件太严去改条件，而真正的原因是这次请求没成功。
                uiState.error != null && uiState.items.isEmpty() && !uiState.isLoading -> {
                    item {
                        AppErrorState(
                            message = uiState.error!!,
                            onRetry = { viewModel.search() },
                            modifier = Modifier.padding(top = 80.dp)
                        )
                    }
                }
                // 空结果
                uiState.hasSearched && uiState.items.isEmpty() && !uiState.isLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.discover_filter_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    items(uiState.items, key = { it.id }) { item ->
                        DiscoverFilterListItem(
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
                                        .take(4).toIntOrNull()
                                )
                                if (uiState.type == TmdbRepository.DiscoverType.MOVIE) {
                                    onMovieClick(item.id, title)
                                } else {
                                    onShowClick(item.id, title)
                                }
                            }
                        )
                    }
                    // 加载更多指示器
                    if (uiState.isLoadingMore) {
                        item {
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
                    // 翻页失败：已有结果还在，底部给一条可重试的错误条，不清空列表
                    if (uiState.error != null && !uiState.isLoadingMore) {
                        item {
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
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp),
            hazeState = hazeState,
            scene = discoverFilterGlassScene
        )

        // ========== 吸顶栏（Blur，透明底色） ==========
        // 注意：不要在此 Column 上加 .scrollable(state=listState) —— 那会让高级面板内的拖拽
        // 被转发到 listState，触发 isScrollInProgress=true 进而 collapseAdvanced()，导致弹窗内滑动
        // 就关闭弹窗的 bug。结果列表的滚动由 LazyColumn 自己处理即可。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hazeTopBar(
                    state = hazeState,
                    style = hazeStyle,
                    blurRadius = 24.dp,
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
                // 「返回箭头 + 标题」作为整体与发现页右上角筛选图标配对（sharedBounds）
                val headerModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
                    with(sharedTransitionScope) {
                        Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "discover-filter-entry-icon"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else {
                    Modifier
                }
                Row(
                    modifier = headerModifier.weight(1f),
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
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { viewModel.resetFilters(); viewModel.search() }) {
                    Text(stringResource(R.string.discover_filter_reset))
                }
            }

            // Tab 切换栏：电影 / 电视剧（透明背景，参考 Watchlist 页）
            PrimaryTabRow(
                selectedTabIndex = if (uiState.type == TmdbRepository.DiscoverType.MOVIE) 0 else 1,
                containerColor = Color.Transparent
            ) {
                Tab(
                    selected = uiState.type == TmdbRepository.DiscoverType.MOVIE,
                    onClick = {
                        view.performHaptic(HapticType.CLICK)
                        viewModel.switchType(TmdbRepository.DiscoverType.MOVIE); viewModel.search()
                    },
                    text = { Text(stringResource(R.string.discover_filter_tab_movie)) }
                )
                Tab(
                    selected = uiState.type == TmdbRepository.DiscoverType.SHOW,
                    onClick = {
                        view.performHaptic(HapticType.CLICK)
                        viewModel.switchType(TmdbRepository.DiscoverType.SHOW); viewModel.search()
                    },
                    text = { Text(stringResource(R.string.discover_filter_tab_show)) }
                )
            }

            // 筛选条件栏：类型 / 地区 / 标签 / 评分 / 高级（分散对齐）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 类型
                GlassFilterChip(
                    selected = uiState.selectedGenreIds.isNotEmpty(),
                    onClick = { showGenreDialog = true },
                    hazeState = hazeState,
                    text = if (uiState.selectedGenreIds.isEmpty())
                        stringResource(R.string.discover_filter_genre)
                    else "${uiState.selectedGenreIds.size} ${stringResource(R.string.discover_filter_genre)}"
                )
                // 地区
                GlassFilterChip(
                    selected = uiState.selectedCountries.isNotEmpty(),
                    onClick = { showRegionDialog = true },
                    hazeState = hazeState,
                    text = if (uiState.selectedCountries.isEmpty())
                        stringResource(R.string.discover_filter_region)
                    else "${uiState.selectedCountries.size} ${stringResource(R.string.discover_filter_region)}"
                )
                // 标签
                GlassFilterChip(
                    selected = uiState.selectedKeywordIds.isNotEmpty(),
                    onClick = { showTagDialog = true },
                    hazeState = hazeState,
                    text = if (uiState.selectedKeywordIds.isEmpty())
                        stringResource(R.string.discover_filter_tag)
                    else "${uiState.selectedKeywordIds.size} ${stringResource(R.string.discover_filter_tag)}"
                )
                // 评分（显示"评分 0.0-10.0"）
                GlassFilterChip(
                    selected = uiState.voteAverageMin > 0f || uiState.voteAverageMax < 10f,
                    onClick = { viewModel.toggleAdvanced() },
                    hazeState = hazeState,
                    text = stringResource(R.string.discover_filter_rating_label) + " " +
                        stringResource(
                            R.string.discover_filter_rating_range,
                            uiState.voteAverageMin,
                            uiState.voteAverageMax
                        )
                )
                // 高级筛选
                GlassFilterChip(
                    selected = uiState.showAdvanced,
                    onClick = { viewModel.toggleAdvanced() },
                    hazeState = hazeState,
                    text = stringResource(R.string.discover_filter_advanced)
                )
            }

            // 高级筛选展开区（防御性消费垂直拖拽：避免拖拽冒泡到结果列表触发 collapse）
            if (uiState.showAdvanced) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .pointerInput(Unit) {
                            // 空实现：消费垂直拖拽手势，防止手势冒泡触发意外行为
                            detectVerticalDragGestures { _, _ -> }
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 评分标题 + 滑动条同一行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.discover_filter_rating_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(36.dp)
                        )
                        // 评分双滑块（步长 1，触感反馈）
                        var localMin by remember { mutableFloatStateOf(uiState.voteAverageMin) }
                        var localMax by remember { mutableFloatStateOf(uiState.voteAverageMax) }
                        RangeSlider(
                            value = localMin..localMax,
                            onValueChange = { range ->
                                val oldMin = localMin
                                val oldMax = localMax
                                localMin = range.start
                                localMax = range.endInclusive
                                if (range.start.toInt() != oldMin.toInt() || range.endInclusive.toInt() != oldMax.toInt()) {
                                    view.performHaptic(HapticType.TICK)
                                }
                                viewModel.setVoteRange(range.start, range.endInclusive)
                            },
                            valueRange = 0f..10f,
                            steps = 9,  // 步长 1（0,1,2,...,10）
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HorizontalDivider()
                    // 排序方式：标题左对齐，内容右对齐
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.discover_filter_sort_by),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            TmdbRepository.DiscoverSort.entries.forEach { sort ->
                                val sortText = when (sort) {
                                    TmdbRepository.DiscoverSort.POPULARITY_DESC -> stringResource(R.string.discover_filter_sort_popularity)
                                    TmdbRepository.DiscoverSort.RELEASE_DATE_DESC -> stringResource(R.string.discover_filter_sort_release_date)
                                    TmdbRepository.DiscoverSort.VOTE_AVERAGE_DESC -> stringResource(R.string.discover_filter_sort_vote_average)
                                }
                                GlassFilterChip(
                                    selected = uiState.sortBy == sort,
                                    onClick = {
                                        view.performHaptic(HapticType.CLICK)
                                        viewModel.setSortBy(sort)
                                    },
                                    hazeState = hazeState,
                                    text = sortText
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    // 年代
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.discover_filter_decade),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            viewModel.decadeOptions.forEach { opt ->
                                val decadeText = if (opt.specialLabelRes != null) {
                                    stringResource(opt.specialLabelRes)
                                } else {
                                    stringResource(R.string.decade_format, opt.startYear)
                                }
                                GlassFilterChip(
                                    selected = opt.key in uiState.selectedDecadeKeys,
                                    onClick = {
                                        view.performHaptic(HapticType.CLICK)
                                        viewModel.toggleDecade(opt.key)
                                    },
                                    hazeState = hazeState,
                                    text = decadeText
                                )
                            }
                        }
                    }
                    // 仅展示未标看过（登录后可用）
                    if (isLoggedIn) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.discover_filter_hide_watched),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Switch(
                                checked = uiState.hideWatched,
                                onCheckedChange = { view.performHaptic(HapticType.CLICK); viewModel.toggleHideWatched() },
                                colors = appSwitchColors()
                            )
                        }
                    }
                    // 应用按钮：点击后搜索并收起高级面板
                    Button(
                        onClick = {
                            view.performHaptic(HapticType.HEAVY_CLICK)
                            viewModel.toggleAdvanced()  // 先收起
                            viewModel.search()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.discover_filter_apply))
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
        val options = genreEntries.map { (id, labelRes) -> id.toString() to stringResource(labelRes) }
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
        MultiSelectDialog(
            title = stringResource(R.string.discover_filter_select_regions),
            options = DiscoverFilterConstants.REGION_CODES.map { code ->
                code to DiscoverFilterConstants.regionName(filterContext, code)
            },
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
        MultiSelectDialog(
            title = stringResource(R.string.discover_filter_select_tags),
            options = DiscoverFilterConstants.TAGS.map { (id, labelRes) ->
                id.toString() to stringResource(labelRes)
            },
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
 * 列表项：海报 + 标题 + 评分 + 年份 + 类型 + 地区
 * 背景使用海报主色沉浸渐变。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun DiscoverFilterListItem(
    item: TmdbSearchResult,
    isMovie: Boolean,
    posterColorExtractor: PosterColorExtractor,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    val title = if (item.title.isNotBlank()) item.title else (item.name ?: "")
    val year = if (isMovie) {
        item.release_date.takeIf { it.length >= 4 }?.substring(0, 4)
    } else {
        (item.first_air_date ?: "").takeIf { it.length >= 4 }?.substring(0, 4)
    }
    val posterUrl = if (!item.poster_path.isNullOrBlank()) {
        TmdbImageUrls.build(item.poster_path, TmdbImageUrls.W185)
    } else null

    var dominantColor by remember { mutableStateOf<Color?>(null) }

    // 根据主色亮度自适应文字颜色
    val onGradientColor = dominantColor?.let { c ->
        if (c.luminance() > 0.5f) Color.Black.copy(alpha = 0.92f) else Color.White
    } ?: MaterialTheme.colorScheme.onSurface
    val onGradientVariantColor = dominantColor?.let { c ->
        if (c.luminance() > 0.5f) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.78f)
    } ?: MaterialTheme.colorScheme.onSurfaceVariant

    // 海报 modifier：当两个 scope 可用时加 sharedElement（与详情页海报配对）
    val posterModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
        with(sharedTransitionScope) {
            Modifier
                .width(80.dp)
                .height(120.dp)
                .sharedElement(
                    rememberSharedContentState(key = "poster-${item.id}"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
                .clip(RoundedCornerShape(8.dp))
        }
    } else {
        Modifier
            .width(80.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(8.dp))
    }

    val backgroundBrush: Brush? = dominantColor?.let { color ->
        Brush.horizontalGradient(
            colors = listOf(
                color,
                color.copy(alpha = 0.7f)
            )
        )
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "filter_list_item_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (backgroundBrush != null) {
                    Modifier.background(backgroundBrush)
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 海报（80×120）
        if (posterUrl != null) {
            AsyncImage(
                model = remember(posterUrl) {
                    ImageRequest.Builder(context)
                        .data(posterUrl)
                        .size(150)
                        .crossfade(false)
                        .listener(
                            onSuccess = { _, result ->
                                val bitmap = result.drawable.toBitmap()
                                scope.launch {
                                    val argb = posterColorExtractor.extractDominantColor(posterUrl, bitmap)
                                    if (argb != 0L) {
                                        dominantColor = Color(argb)
                                    }
                                }
                            }
                        )
                        .build()
                },
                contentDescription = title,
                modifier = posterModifier,
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = posterModifier
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 信息列
        Column(
            modifier = Modifier
                .weight(1f)
                .height(120.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onGradientColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // 地区：紧贴标题下方一行；优先用 origin_country（TV），为空时用 original_language 推断（movie 回退）
            val regionText = if (item.origin_country.isNotEmpty()) {
                item.origin_country.joinToString(" / ") { code ->
                    DiscoverFilterConstants.regionName(context, code)
                }
            } else if (item.original_language.isNotBlank()) {
                DiscoverFilterConstants.countryByLanguage(context, item.original_language)?.second
            } else null
            if (regionText != null) {
                Text(
                    text = regionText,
                    style = MaterialTheme.typography.bodySmall,
                    color = onGradientVariantColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (year != null) {
                Text(
                    text = year,
                    style = MaterialTheme.typography.bodySmall,
                    color = onGradientVariantColor
                )
            }
            // 评分
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = null,
                    tint = RatingGold,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "%.1f".format(item.vote_average),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onGradientColor
                )
            }
            // 类型
            val genreNames = item.genre_ids.mapNotNull { id ->
                DiscoverFilterConstants.genreName(context, id, isMovie).takeIf { it.isNotBlank() }
            }
            if (genreNames.isNotEmpty()) {
                Text(
                    text = genreNames.joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    color = onGradientVariantColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 列表骨架项：与列表项布局对齐（海报 80×120 + 信息列）
 */
@Composable
private fun DiscoverFilterItemSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 海报骨架 80×120
        Box(
            modifier = Modifier
                .width(80.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        // 信息骨架
        Column(
            modifier = Modifier
                .weight(1f)
                .height(120.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 标题骨架
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            // 年份骨架
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            // 评分骨架
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            // 类型骨架
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            // 地区骨架
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
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
    val view = LocalView.current
    val scrollState = rememberScrollState()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(16.dp)
                )
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (useScrollableList) {
                // 可滚动的矩形块标签布局：FlowRow 自动换行，整体垂直可滚动
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    options.forEach { (id, name) ->
                        GlassFilterChip(
                            selected = id in selectedIds,
                            onClick = {
                                view.performHaptic(HapticType.CLICK)
                                onToggle(id)
                            },
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
                            onClick = {
                                view.performHaptic(HapticType.CLICK)
                                onToggle(id)
                            },
                            text = name
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_done))
                }
            }
        }
    }
}

/**
 * 筛选标签
 *
 * 未选中时使用 surfaceVariant 半透明背景，选中时使用主题色填充。
 * 文字色与背景保持高对比度，避免浅色模式下看不清。
 * [hazeState] 参数已废弃，chip 自身不需要毛玻璃效果。
 */
@Composable
private fun GlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    text: String,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
