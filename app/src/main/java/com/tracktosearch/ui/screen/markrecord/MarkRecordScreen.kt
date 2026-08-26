package com.tracktosearch.ui.screen.markrecord

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.EmptyStateCard
import com.tracktosearch.ui.component.LoadMoreFooter
import com.tracktosearch.ui.component.LoadMoreFooterState
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.navigation.DetailSeedStore
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.backdropContentSource
import com.tracktosearch.ui.component.rememberCachedPosterAmbientColor
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.animation.EnterMode
import com.tracktosearch.ui.animation.cardEnter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.saveable.listSaver

/** 首屏骨架卡片数量：两列，铺满一屏左右即可，多了只是白耗合成 */
private const val SKELETON_ITEM_COUNT = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkRecordScreen(
    onBack: () -> Unit,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    viewModel: MarkRecordViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = isAppDarkTheme()
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    val listState = rememberLazyGridState()
    var showFilterSheet by remember { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    var rootPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var searchBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    val searchBoundsInRootLocal = searchBoundsInRoot?.let { bounds ->
        Rect(
            left = bounds.left - rootPositionInRoot.x,
            top = bounds.top - rootPositionInRoot.y,
            right = bounds.right - rootPositionInRoot.x,
            bottom = bounds.bottom - rootPositionInRoot.y
        )
    }
    val currentSearchBoundsInRootLocal by rememberUpdatedState(searchBoundsInRootLocal)
    val collapseSearch = {
        searchExpanded = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    var enterMode by remember { mutableStateOf(EnterMode.DEFAULT) }
    val animatedIds = rememberSaveable(
        saver = listSaver(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toMutableSet()) }
        )
    ) { mutableStateOf(mutableSetOf<Long>()) }
    val markRecordGlassScene = glassSceneForContent(
        contentCount = uiState.items.size,
        readabilityDemand = when {
            searchExpanded && uiState.searchQuery.isNotBlank() -> 0.88f
            searchExpanded || uiState.searchQuery.isNotBlank() -> 0.74f
            else -> 0.60f
        },
        ambientColor = rememberCachedPosterAmbientColor(
            posterUrls = uiState.items.mapNotNull { it.posterUrl },
            fallback = MaterialTheme.colorScheme.background
        ),
        contentCapacity = 36,
        loadingCount = if (uiState.isLoading || uiState.isLoadingMore) 1 else 0,
        loadingItemWeight = 4
    )
    val statusBarHeight = WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding()
    val stickyHeaderHeight = statusBarHeight + 100.dp

    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    BackHandler(enabled = searchExpanded) {
        if (isImeVisible) {
            focusManager.clearFocus()
        } else {
            collapseSearch()
        }
    }

    // 数据集切换后清空动画登记，避免旧 Tab 的播放状态影响新列表。
    LaunchedEffect(
        uiState.currentTab,
        uiState.filterMediaTypes,
        uiState.filterDatePreset,
        uiState.filterDateRange,
        uiState.sortAscending
    ) {
        animatedIds.value = mutableSetOf()
        enterMode = EnterMode.DEFAULT
    }

    // 滚动到底部前若干条时加载下一页
    LaunchedEffect(listState, uiState.items) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            lastVisible >= total - 10
        }.distinctUntilChanged().filter { it }.collect {
            viewModel.loadNextPage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootPositionInRoot = it.positionInRoot() }
            .pointerInput(searchExpanded) {
                if (!searchExpanded) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()
                    if (up != null && currentSearchBoundsInRootLocal?.contains(down.position) != true) {
                        collapseSearch()
                    }
                }
            }
    ) {
        // ========== 网格内容 ==========
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .backdropContentSource(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                top = stickyHeaderHeight + 5.dp,
                bottom = 40.dp
            )
        ) {
            when {
                uiState.isLoading && uiState.items.isEmpty() -> {
                    // 骨架屏而非居中转圈：布局与真实卡片一致，数据到达时不跳动
                    items(SKELETON_ITEM_COUNT) {
                        MarkRecordItemSkeleton()
                    }
                }
                uiState.error != null && uiState.items.isEmpty() -> {
                    item(span = { GridItemSpan(2) }) {
                        AppErrorState(
                            message = uiState.error!!,
                            onRetry = { viewModel.retry() },
                            modifier = Modifier.padding(32.dp),
                            retryLabel = stringResource(R.string.mark_records_retry)
                        )
                    }
                }
                uiState.items.isEmpty() -> {
                    item(span = { GridItemSpan(2) }) {
                        EmptyStateCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 28.dp),
                            isDark = isDark,
                            hazeState = hazeState,
                            hazeStyle = hazeStyle,
                            icon = Icons.Rounded.Inbox,
                            title =
                                stringResource(when (uiState.currentTab) {
                                    MarkRecordTab.ALL -> R.string.mark_records_empty_all
                                    MarkRecordTab.WATCHLIST -> R.string.mark_records_empty_watchlist
                                    MarkRecordTab.WATCHED -> R.string.mark_records_empty_watched
                                    MarkRecordTab.REMOVED -> R.string.mark_records_empty_removed
                                })
                        )
                    }
                }
                else -> {
                    itemsIndexed(
                        uiState.items,
                        key = { _, it -> "${it.traktId}_${it.actedAt}_${it.actionType}" }
                    ) { index, item ->
                        Box(modifier = Modifier.cardEnter(
                            id = "${item.traktId}_${item.actedAt}_${item.actionType}".hashCode().toLong(),
                            index = index,
                            enterMode = enterMode,
                            animatedIds = animatedIds
                        )) {
                            MarkRecordItemRow(
                            item = item,
                            posterColorExtractor = viewModel.posterColorExtractor,
                            onClick = {
                                val onClick = if (item.mediaType == "movie") onMovieClick else onShowClick
                                // 标记记录卡片已有海报与年份，交给详情页做首帧种子
                                DetailSeedStore.remember(item.tmdbId, item.posterUrl, item.year)
                                onClick(
                                    item.traktId, item.tmdbId,
                                    item.displayTitle.ifBlank { item.title },
                                    item.imdbId, 0.0
                                )
                            }
                            )
                        }
                    }
                    item(span = { GridItemSpan(2) }, key = "load_more_footer") {
                        LoadMoreFooter(
                            state = when {
                                uiState.isLoadingMore -> LoadMoreFooterState.Loading
                                uiState.error != null -> LoadMoreFooterState.Error
                                !uiState.hasMore -> LoadMoreFooterState.Complete
                                else -> LoadMoreFooterState.Hidden
                            },
                            onRetry = { viewModel.loadNextPage() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // ========== 吸顶栏（Blur + 半透明背景） ==========
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hazeTopBar(
                    state = hazeState,
                    style = hazeStyle,
                    blurRadius = 24.dp,
                    scene = markRecordGlassScene
                )
                // 拦截点击：顶栏覆盖可滚动网格，不消费会让点击穿透到下方列表项
                .clickable(enabled = false, onClick = {})
        ) {
            Spacer(modifier = Modifier.statusBarsPadding())

            // 标题栏 + 搜索/筛选
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    IconButton(onClick = {
                        if (searchExpanded) collapseSearch() else onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    AnimatedVisibility(
                        visible = !searchExpanded,
                        enter = androidx.compose.animation.fadeIn(tween(240)),
                        exit = androidx.compose.animation.fadeOut(tween(240)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.mark_records_title),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BoxWithConstraints(
                        modifier = if (searchExpanded) Modifier.weight(1f).height(42.dp)
                        else Modifier.height(42.dp).width(42.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        val searchWidth by animateDpAsState(
                            targetValue = if (searchExpanded) maxWidth else 42.dp,
                            animationSpec = tween(durationMillis = 240),
                            label = "mark_record_search_width"
                        )
                        Box(
                            modifier = Modifier
                                .width(searchWidth)
                                .fillMaxHeight()
                                .onGloballyPositioned { searchBoundsInRoot = it.boundsInRoot() }
                        ) {
                            if (searchExpanded) {
                                NeumorphicFrostedSurface(
                                    modifier = Modifier.fillMaxSize(),
                                    isDark = isDark,
                                    shape = RoundedCornerShape(21.dp),
                                    backgroundColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.55f),
                                    borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.75f),
                                    elevation = 4.dp,
                                    blurRadius = 16.dp,
                                    hazeState = hazeState,
                                    hazeStyle = HazeMaterials.thin(),
                                    scene = markRecordGlassScene
                                ) {
                                    BasicTextField(
                                        value = uiState.searchQuery,
                                        onValueChange = viewModel::updateSearchQuery,
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(Modifier.focusRequester(focusRequester))
                                            .testTag("mark_record_search_input")
                                            .padding(horizontal = 12.dp),
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.None,
                                            imeAction = ImeAction.Search
                                        ),
                                        keyboardActions = KeyboardActions(onSearch = {
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                        }),
                                        decorationBox = { innerTextField ->
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Search,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Box(
                                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                                    contentAlignment = Alignment.CenterStart
                                                ) {
                                                    if (uiState.searchQuery.isEmpty()) {
                                                        Text(
                                                            stringResource(R.string.mark_records_search_hint),
                                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                                            fontSize = 14.sp
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                                if (uiState.searchQuery.isNotEmpty()) {
                                                    IconButton(
                                                        onClick = { viewModel.updateSearchQuery("") },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Rounded.Close,
                                                            contentDescription = stringResource(R.string.content_desc_clear),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            } else {
                                NeumorphicIconButton(
                                    onClick = { searchExpanded = true },
                                    isDark = isDark,
                                    lightBorderAlpha = 0.35f,
                                    hazeState = hazeState,
                                    scene = markRecordGlassScene
                                ) {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = stringResource(R.string.mark_records_search_hint),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    NeumorphicIconButton(
                        onClick = {
                            collapseSearch()
                            showFilterSheet = true
                        },
                        isDark = isDark,
                        lightBorderAlpha = 0.35f,
                        hazeState = hazeState,
                        scene = markRecordGlassScene
                    ) {
                        Icon(
                            Icons.Rounded.FilterList,
                            contentDescription = stringResource(R.string.filter_title),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Tab 切换栏（透明背景）
            PrimaryTabRow(
                selectedTabIndex = uiState.currentTab.ordinal,
                containerColor = Color.Transparent
            ) {
                MarkRecordTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.currentTab == tab,
                        onClick = {
                            collapseSearch()
                            viewModel.switchTab(tab)
                        },
                        text = {
                            Text(stringResource(when (tab) {
                                MarkRecordTab.ALL -> R.string.mark_records_tab_all
                                MarkRecordTab.WATCHLIST -> R.string.mark_records_tab_watchlist
                                MarkRecordTab.WATCHED -> R.string.mark_records_tab_watched
                                MarkRecordTab.REMOVED -> R.string.mark_records_tab_removed
                            }))
                        }
                    )
                }
            }
        }

        ScrollToTopButton(
            gridState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp),
            hazeState = hazeState,
            hazeStyle = hazeStyle,
            scene = markRecordGlassScene
        )
    }

    // 筛选弹窗
    if (showFilterSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            FilterSheetContent(
                mediaTypes = uiState.filterMediaTypes,
                datePreset = uiState.filterDatePreset,
                dateRange = uiState.filterDateRange,
                ascending = uiState.sortAscending,
                onConfirm = { mediaTypes, preset, range, asc ->
                    viewModel.updateFilter(mediaTypes, preset, range, asc)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.updateFilter(emptySet(), DatePreset.ALL, null, false)
                    showFilterSheet = false
                }
            )
        }
    }
}
