package com.tracktosearch.ui.screen.markrecord

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.EmptyStateCard
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.LoadMoreFooter
import com.tracktosearch.ui.component.LoadMoreFooterState
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.backdropContentSource
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.rememberCachedPosterAmbientColor
import com.tracktosearch.ui.haptic.HapticOutcomeEffect
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.floatingSheetColor
import com.tracktosearch.ui.util.ToastEffect
import com.tracktosearch.ui.navigation.DetailSeedStore
import com.tracktosearch.ui.animation.EnterMode
import com.tracktosearch.ui.animation.cardEnter
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.MarkRecordsEntryKey
import com.tracktosearch.ui.component.SettingsEntryCardCorner
import com.tracktosearch.ui.component.SharedCorner
import com.tracktosearch.ui.component.appSharedBounds
import com.tracktosearch.ui.component.appSkipToLookaheadSize
import com.tracktosearch.ui.component.isAppSharedTransitionActive
import com.tracktosearch.ui.theme.GlassBorderDark
import com.tracktosearch.ui.theme.GlassFillDark
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/** 首屏骨架卡片数量：两列，铺满一屏左右即可，多了只是白耗合成 */
private const val SKELETON_ITEM_COUNT = 8

/**
 * 轻量会话 ViewModel：仅向页面暴露 Trakt 连接态。
 *
 * MarkRecordViewModel 不感知会话模式，而深链/通知可能把未连 Trakt 的用户
 * （如豆瓣独立模式）带进本页；空态需要据此区分「真的没有记录」和「缺少 Trakt 数据源」。
 */
@HiltViewModel
class MarkRecordSessionViewModel @Inject constructor(
    sessionModeManager: SessionModeManager
) : ViewModel() {
    val traktConnected: StateFlow<Boolean> = sessionModeManager.traktConnected
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MarkRecordScreen(
    onBack: () -> Unit,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    viewModel: MarkRecordViewModel = hiltViewModel(),
    sessionViewModel: MarkRecordSessionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val traktConnected by sessionViewModel.traktConnected.collectAsStateWithLifecycle()
    val isDark = isAppDarkTheme()
    val haptics = rememberAppHaptics()
    // ALL 页只有 Trakt 那一半失败时提示一句「列表可能不全」——原来这种失败一个字都不报
    ToastEffect(viewModel.toastEvent)
    HapticOutcomeEffect(viewModel.hapticOutcomes)
    // 共享元素转场 scope（与设置页标记记录入口卡片配对）。本页没有 Scaffold，作用域自己从
    // CompositionLocal 取，与 StatisticsScreen 的取法一致。
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    val listState = rememberLazyGridState()
    val hasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset
            )
        }
    }
    // 改筛选/排序/搜索/Tab 后，新列表要把整张表换掉，位置必须回到顶部。
    //
    // 不能只靠 Lazy 网格自己：它默认按"首个可见 item 的 key"重新定位 —— 排序方向一反转，
    // 旧的第 0 项跑到列表末尾，位置就跟着被带到底部；筛掉大半条目时锚点在新列表里找不到，
    // 索引又会被钳到末尾。两种情况用户看到的都是"列表停在底部"而不是从头按顺序显示。
    //
    // 位置要在新列表上屏那一帧才钉：重定位就发生在那次测量里，提前调用会被这次测量吃掉。
    // requestScrollToItem 会丢掉 key 锚点，所以钉住之后不会再被重定位带走。
    val markRecordListToken = "${uiState.currentTab}|${uiState.filterMediaTypes}|" +
        "${uiState.filterDatePreset}|${uiState.filterDateRange}|${uiState.sortAscending}|${uiState.searchQuery}"
    LaunchedEffect(markRecordListToken) {
        snapshotFlow { uiState.items }.drop(1).first()
        listState.requestScrollToItem(0)
    }
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
            // 必须 remember：rememberCachedPosterAmbientColor 内部用列表身份做 remember/LaunchedEffect 的 key，
            // 每次重组都传新列表会让环境色先跳回 fallback 再重读一遍颜色缓存
            posterUrls = remember(uiState.items) { uiState.items.mapNotNull { it.posterUrl } },
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

    // 与设置页标记记录入口卡片配对的是整页，而不是顶栏：卡片放大成页面、返回时收回成卡片。
    // 卡片侧圆角 SettingsEntryCardCorner，页面侧是 0，转场期间在两者之间插值。
    val transitionActive = isAppSharedTransitionActive()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .appSharedBounds(
                key = MarkRecordsEntryKey,
                animatedVisibilityScope = animatedVisibilityScope,
                corner = SharedCorner.flattenFrom(SettingsEntryCardCorner),
                // 容器变形要的是「内容不变形、被裁剪逐渐露出」，默认的 scaleToBounds 会把内容
                // 跟着容器一起缩放绘制。逐帧重测的代价由内容侧的 appSkipToLookaheadSize 挡掉。
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            )
            // 页面底色挪进共享节点内侧：容器变形靠裁剪揭示，容器里必须是不透明的，
            // 否则变形期这一片能直接看到下面那一页 —— 打开的瞬间设置页内容会叠在本页上。
            // 底色跟着动画边界一起长大，且被上面那层圆角动画裁剪，落定后与原来逐像素相同。
            .background(MaterialTheme.colorScheme.background)
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
                // 整页参与容器变形时按落定尺寸布局：否则网格会跟着容器逐帧变宽，
                // 一次转场里重复决定「哪些项可见、每项多宽」几十遍
                .appSkipToLookaheadSize()
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
                                // 标记记录卡片已有海报与年份，交给详情页做首帧种子；
                                // origin 让详情页拼出与本行海报相同的共享元素 key
                                DetailSeedStore.remember(
                                    item.tmdbId,
                                    item.posterUrl,
                                    item.year,
                                    origin = markRecordOrigin(item)
                                )
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

        if (!uiState.isLoading && uiState.error == null && uiState.items.isEmpty()) {
            // 空态不再作为 LazyVerticalGrid 的首个自然高度 item：那会把卡片固定在内容顶部。
            // 单独覆盖在标题栏以下的可用内容区域，确保横向与纵向都真正居中。
            val filteredEmpty = uiState.hasActiveFilterOrSearch
            val needTraktHint = !traktConnected && !filteredEmpty
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = stickyHeaderHeight + 5.dp, bottom = 40.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("mark_record_empty_state_container"),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyStateCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp)
                            .testTag("mark_record_empty_state_card"),
                        isDark = isDark,
                        hazeState = hazeState,
                        hazeStyle = hazeStyle,
                        icon = if (filteredEmpty) Icons.Rounded.FilterList else Icons.Rounded.Inbox,
                        title = if (needTraktHint) {
                            stringResource(R.string.douban_import_require_trakt_title)
                        } else if (filteredEmpty) {
                            stringResource(R.string.mark_records_empty_filtered)
                        } else {
                            stringResource(when (uiState.currentTab) {
                                MarkRecordTab.ALL -> R.string.mark_records_empty_all
                                MarkRecordTab.WATCHLIST -> R.string.mark_records_empty_watchlist
                                MarkRecordTab.WATCHED -> R.string.mark_records_empty_watched
                                MarkRecordTab.REMOVED -> R.string.mark_records_empty_removed
                            })
                        },
                        description = if (needTraktHint) {
                            stringResource(R.string.douban_import_require_trakt_desc)
                        } else null,
                        actions = {
                            if (filteredEmpty) {
                                TextButton(onClick = {
                                    haptics.tap()
                                    collapseSearch()
                                    viewModel.updateSearchQuery("")
                                    viewModel.updateFilter(emptySet(), DatePreset.ALL, null, uiState.sortAscending)
                                }) {
                                    Text(stringResource(R.string.mark_records_clear_filter))
                                }
                            }
                        }
                    )
                }
            }
        }

        // ========== 吸顶栏（Blur + 半透明背景） ==========
        // 顶栏不参与配对：来源侧那张卡片上没有对应的标题栏，硬配对会把一行标题从卡片尺寸拉过来。
        // 但它必须从第一帧就在，与网格一样按落定尺寸布局，跟着容器裁剪逐渐露出；延迟入场会让容器
        // 长大的那段时间顶栏位置空着，落位时再整片闪出来。转场期间仍让 haze 停采样。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .appSkipToLookaheadSize()
                .hazeTopBar(
                    state = hazeState,
                    style = hazeStyle,
                    blurRadius = 24.dp,
                    isContentUnderTopBar = if (transitionActive) false else hasContentUnderTopBar,
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
                                    backgroundColor = if (isDark) GlassFillDark else Color.White.copy(alpha = 0.55f),
                                    borderColor = if (isDark) GlassBorderDark else Color.White.copy(alpha = 0.75f),
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
                                                        onClick = {
                                                            haptics.lightTap()
                                                            viewModel.updateSearchQuery("")
                                                        },
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
                            // 筛选生效时保持主色：弹窗关上后没有别的地方能看出条件还开着
                            tint = if (uiState.hasActiveFilter) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
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
                            haptics.segmentTick()
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

            // 已展示旧数据但仍在加载时的细进度条：不遮挡内容，只提示数据可能不是最新。
            // 放在吸顶栏最底部，出现/消失不会推动上方的标题与 Tab。
            // isLoading 也算：改筛选/搜索时列表保留旧内容不进骨架屏，这里是唯一的进度提示。
            if ((uiState.isRefreshing || uiState.isLoading) && uiState.items.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .testTag("mark_record_refresh_indicator")
                )
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
            containerColor = floatingSheetColor()
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
