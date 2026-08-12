package com.tracktosearch.ui.screen.watchlist

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.repository.BatchRemovalPhase
import com.tracktosearch.data.repository.DoubanSyncLoginTarget
import com.tracktosearch.data.repository.DoubanSyncSubStage
import com.tracktosearch.data.repository.WatchlistMediaType
import com.tracktosearch.data.repository.labelRes
import com.tracktosearch.data.session.SessionMode
import com.tracktosearch.ui.animation.EnterMode
import com.tracktosearch.ui.animation.cardEnter
import com.tracktosearch.ui.component.LocalActivePosterClickSetter
import com.tracktosearch.ui.component.LocalActivePosterClickToken
import com.tracktosearch.ui.component.LocalActivePosterTmdbId
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalIsCurrentTab
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.AdaptiveTwoLineTitle
import com.tracktosearch.ui.component.PosterCard
import com.tracktosearch.ui.component.PosterColorExtractorProvider
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.rememberShimmerBrush
import com.tracktosearch.ui.screen.discover.CapsuleTabSelector
import com.tracktosearch.ui.screen.douban.DoubanFirstSyncGuideDialog
import com.tracktosearch.ui.screen.douban.DoubanSyncDialog
import com.tracktosearch.ui.screen.douban.DoubanSyncModePickerDialog
import com.tracktosearch.ui.navigation.NotificationNavigator
import com.tracktosearch.ui.navigation.NotificationTarget
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.performHaptic
import dagger.hilt.android.EntryPointAccessors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun WatchlistScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    // 豆瓣条目需要保留 doubanId 和真实 mediaType，由上层统一决定详情路由。
    onMediaItemClick: ((item: MediaUiItem, inWatchlist: Boolean, isWatched: Boolean) -> Unit)? = null,
    onSearchClick: (keyword: String) -> Unit,
    onTraktSearch: (type: String, query: String) -> Unit,
    onDiscoverClick: () -> Unit = {},
    onNavigateToDoubanLogin: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    // 直接发起 Trakt 授权（CustomTabs 打开授权页）；未提供时回退到导航激活登录页
    onTraktLogin: () -> Unit = onNavigateToLogin,
    modifier: Modifier = Modifier,
    viewModel: WatchlistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val hasActiveFilters by viewModel.hasActiveFilters.collectAsStateWithLifecycle()
    val availableGenres by viewModel.availableGenres.collectAsStateWithLifecycle()
    val isTraktConnected by viewModel.isTraktConnected.collectAsStateWithLifecycle()
    val isDoubanMode by viewModel.isDoubanMode.collectAsStateWithLifecycle()
    val isDoubanLoggedIn by viewModel.isDoubanLoggedInFlow.collectAsStateWithLifecycle()
    val sessionKey = when {
        isDoubanMode -> SessionMode.DOUBAN
        isTraktConnected -> SessionMode.TRAKT
        else -> SessionMode.GUEST
    }
    val isCurrentTab = LocalIsCurrentTab.current
    var hasBeenVisible by remember { mutableStateOf(false) }
    val emptyState = resolveWatchlistEmptyState(
        traktConnected = isTraktConnected,
        doubanLoggedIn = isDoubanLoggedIn,
        doubanImported = uiState.doubanImported
    )
    var showFilterSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val batchRemovePartialFailedMessage = stringResource(R.string.watchlist_batch_remove_partial_failed)
    val view = LocalView.current
    // 当前活跃海报 tmdbId（-1=都不启用），确保只有用户点击的卡片参与共享元素转场，避免跨页面重复海报 key 冲突
    var activePosterTmdbId by rememberSaveable { mutableIntStateOf(-1) }
    // 用外置浏览器打开 Trakt，共享外置浏览器登录态（内置 WebView 有独立 CookieJar 不共享）
    val openTraktExternal: () -> Unit = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://trakt.tv/watchlist")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    LaunchedEffect(sessionKey) {
        viewModel.onSessionModeChanged(sessionKey)
    }

    LaunchedEffect(isCurrentTab) {
        if (isCurrentTab) {
            if (hasBeenVisible) {
                viewModel.onWatchlistTabVisible()
            }
            hasBeenVisible = true
        }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 0=想看, 1=已看
    var selectedMode by rememberSaveable { mutableIntStateOf(0) }
    // 卡片入场动画状态
    var enterMode by remember { mutableStateOf(EnterMode.DEFAULT) }
    var refreshPending by remember { mutableStateOf(false) }
    val animatedIds = rememberSaveable(
        saver = listSaver(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toMutableSet()) },
        ),
    ) { mutableStateOf(mutableSetOf<Long>()) }
    val tabScope = rememberCoroutineScope()
    // 控制豆瓣同步进度弹窗显示（点击横幅重新打开 / 同步完成自动弹出）
    var showSyncDialog by rememberSaveable { mutableStateOf(false) }
    // 首次同步引导弹窗
    var showFirstSyncGuide by remember { mutableStateOf(false) }
    // 模式选择弹窗（引导弹窗确认后弹出）
    var showSyncModePicker by remember { mutableStateOf(false) }
    val isDoubanSyncRunning = uiState.doubanSyncProgress?.isRunning == true
    // 续传可能在首次同步引导已经入队后才启动，避免引导遮挡正在运行的同步。
    LaunchedEffect(isDoubanSyncRunning) {
        if (isDoubanSyncRunning) {
            showFirstSyncGuide = false
        }
    }
    // 监听首次同步引导状态
    LaunchedEffect(Unit) {
        viewModel.needFirstSyncGuide.collect { need ->
            if (need) {
                showFirstSyncGuide = true
                viewModel.onFirstSyncGuideHandled()
            }
        }
    }

    // 监听同步完成事件 → 自动弹出 DoubanSyncDialog 显示结果
    LaunchedEffect(Unit) {
        viewModel.syncCompleteEvent.collect {
            showSyncDialog = true
        }
    }

    // 监听状态检查完成事件 → 自动弹出 ConsistencyCheckDialog 显示结果
    var showConsistencyDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        NotificationNavigator.pendingTarget.collect { target ->
            when (target) {
                NotificationTarget.DOUBAN_SYNC -> {
                    showSyncDialog = true
                    NotificationNavigator.consume(target)
                }
                NotificationTarget.CONSISTENCY_CHECK -> {
                    showConsistencyDialog = true
                    NotificationNavigator.consume(target)
                }
                null -> Unit
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.consistencyCheckCompleteEvent.collect {
            showConsistencyDialog = true
        }
    }

    // 切换模式时触发加载
    LaunchedEffect(selectedMode) {
        when (selectedMode) {
            1 -> {
                viewModel.loadHistoryMovies()
                viewModel.loadHistoryShows()
                viewModel.loadHistoryOthers()
            }
        }
    }

    // 用 rememberSaveable 而非 remember：旋屏/进程恢复后保留搜索关键词，与同文件其他 UI 状态保持一致
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    // 弹性下拉刷新状态
    var overscrollOffset by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val triggerThreshold = with(density) { 80.dp.toPx() } // 触发刷新的阈值
    val isThresholdReached = overscrollOffset >= triggerThreshold
    val gridCoroutineScope = rememberCoroutineScope()
    val pullToRefreshConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 下拉时消耗 overscroll 偏移
                if (overscrollOffset > 0f && available.y < 0f) {
                    val consumed = minOf(-available.y, overscrollOffset)
                    overscrollOffset -= consumed
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 列表滚到顶部后，继续下拉产生弹性偏移（带阻尼）
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    val damped = available.y * 0.4f / (1f + overscrollOffset / triggerThreshold)
                    overscrollOffset += damped
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // 松手时：如果达到阈值则触发刷新，否则弹回
                if (overscrollOffset >= triggerThreshold && !isRefreshing) {
                    isRefreshing = true
                    viewModel.refresh()
                    animatedIds.value = mutableSetOf()
                    refreshPending = true
                    isRefreshing = false
                }
                // 始终弹回
                overscrollOffset = 0f
                return Velocity.Zero
            }
        }
    }
    // 弹性偏移动画（松手后弹回）
    val animatedOverscrollDp by animateDpAsState(
        targetValue = with(density) { overscrollOffset.toDp() },
        animationSpec = if (overscrollOffset == 0f) spring(dampingRatio = 0.6f, stiffness = 400f) else tween(0),
        label = "overscroll"
    )
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
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
        isSearchExpanded = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    val isDark = isAppDarkTheme()
    // 为6种 (mode, tab) 组合各自创建独立的 gridState，彻底隔离滚动位置，
    // 避免切 tab 时列表位置互相影响
    val movieGridState = rememberLazyGridState()        // mode=0, tab=0 想看电影
    val showGridState = rememberLazyGridState()         // mode=0, tab=1 想看电视剧
    val otherGridState = rememberLazyGridState()        // mode=0, tab=2 想看其他
    val historyMovieGridState = rememberLazyGridState() // mode=1, tab=0 已看电影
    val historyShowGridState = rememberLazyGridState()  // mode=1, tab=1 已看电视剧
    val historyOtherGridState = rememberLazyGridState() // mode=1, tab=2 已看其他
    val scrollToTopProvider = LocalScrollToTopProvider.current

    // 根据 selectedMode 和 selectedTab 选择对应的 gridState
    val currentGridState = when {
        selectedMode == 0 && selectedTab == 0 -> movieGridState
        selectedMode == 0 && selectedTab == 1 -> showGridState
        selectedMode == 0 && selectedTab == 2 -> otherGridState
        selectedMode == 1 && selectedTab == 0 -> historyMovieGridState
        selectedMode == 1 && selectedTab == 1 -> historyShowGridState
        else -> historyOtherGridState
    }
    val hasContentUnderTopBar by remember(currentGridState) {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = currentGridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index,
                firstVisibleItemScrollOffsetPx = currentGridState.firstVisibleItemScrollOffset
            )
        }
    }

    // 筛选条件或搜索词变化时回到顶部：筛选是纯客户端过滤，只改变列表内容而 gridState 引用不变，
    // Compose 锚点机制在旧 key 大量消失时会错误地把滚动位置推到新列表末尾（跳到底部）。
    // 统一滚动到顶部保证筛选后从列表头部看结果。
    LaunchedEffect(filterState, searchQuery) {
        listOf(
            movieGridState, showGridState, otherGridState,
            historyMovieGridState, historyShowGridState, historyOtherGridState
        ).forEach { gridState ->
            gridState.scrollToItem(0)
        }
    }

    // key 必须同时包含 selectedMode 和 selectedTab：
    // currentGridState 由两者共同决定，缺任一 key 都会导致切 tab 后回调里仍持有旧的 gridState，
    // 「回到顶部」操作滚到不可见列表上
    DisposableEffect(selectedMode, selectedTab) {
        scrollToTopProvider.register {
            gridCoroutineScope.launch {
                currentGridState.animateScrollToItem(0)
            }
        }
        onDispose {
            scrollToTopProvider.unregister()
        }
    }

    // 长按多选状态（用 String key 避免豆瓣模式无 traktId 条目冲突）
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var isRemoving by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateMapOf<String, Boolean>() }
    // 退出多选模式时清空选中
    LaunchedEffect(isMultiSelectMode) {
        if (!isMultiSelectMode) selectedItems.clear()
    }
    BackHandler(enabled = isMultiSelectMode) {
        isMultiSelectMode = false
        isRemoving = false
    }
    BackHandler(enabled = !isMultiSelectMode && isSearchExpanded) {
        if (isImeVisible) {
            focusManager.clearFocus()
        } else {
            collapseSearch()
        }
    }

    // 监听 tab/mode 切换：退出多选模式。
    // 滚动位置由4个独立 gridState 自动保存，无需手动恢复。
    // 不再触发 EMPHASIS 入场动画——不同 tab 卡片 id 不同，alpha 从0淡入会导致闪白。
    LaunchedEffect(selectedMode, selectedTab) {
        isMultiSelectMode = false
        isRemoving = false
    }

    // 根据搜索关键词和筛选条件过滤当前 Tab 的列表
    val filteredMovies = remember(uiState.movies, searchQuery, filterState) {
        applyFilterAndSort(uiState.movies, searchQuery, filterState)
    }
    val filteredShows = remember(uiState.shows, searchQuery, filterState) {
        applyFilterAndSort(uiState.shows, searchQuery, filterState)
    }
    val filteredOthers = remember(uiState.others, searchQuery, filterState) {
        applyFilterAndSort(uiState.others, searchQuery, filterState)
    }
    val filteredHistoryMovies = remember(uiState.historyMovies, searchQuery, filterState) {
        applyFilterAndSort(uiState.historyMovies, searchQuery, filterState)
    }
    val filteredHistoryShows = remember(uiState.historyShows, searchQuery, filterState) {
        applyFilterAndSort(uiState.historyShows, searchQuery, filterState)
    }
    val filteredHistoryOthers = remember(uiState.historyOthers, searchQuery, filterState) {
        applyFilterAndSort(uiState.historyOthers, searchQuery, filterState)
    }

    // 获取当前 tab 对应的 items（用于多选操作）
    val currentItems = when {
        selectedMode == 0 && selectedTab == 0 -> filteredMovies
        selectedMode == 0 && selectedTab == 1 -> filteredShows
        selectedMode == 0 && selectedTab == 2 -> filteredOthers
        selectedMode == 1 && selectedTab == 0 -> filteredHistoryMovies
        selectedMode == 1 && selectedTab == 1 -> filteredHistoryShows
        else -> filteredHistoryOthers
    }

    // 当前列表是否正在加载
    val isCurrentLoading = when {
        selectedMode == 0 && selectedTab == 0 -> uiState.isLoadingMovies
        selectedMode == 0 && selectedTab == 1 -> uiState.isLoadingShows
        selectedMode == 0 && selectedTab == 2 -> uiState.isLoadingOthers
        selectedMode == 1 && selectedTab == 0 -> uiState.isLoadingHistoryMovies
        selectedMode == 1 && selectedTab == 1 -> uiState.isLoadingHistoryShows
        else -> uiState.isLoadingHistoryOthers
    }

    // 接近列表末尾时加载下一页，避免 watchlist 超过 200 条后停在第一页
    LaunchedEffect(selectedMode, selectedTab, currentGridState) {
        if (selectedMode != 0) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = currentGridState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            layoutInfo.totalItemsCount > 0 && lastVisibleIndex >= layoutInfo.totalItemsCount - 6
        }.collect { nearEnd ->
            if (!nearEnd) return@collect
            when (selectedTab) {
                0 -> viewModel.loadMoreMovies()
                1 -> viewModel.loadMoreShows()
            }
        }
    }

    // 下拉刷新：数据返回后触发 EMPHASIS 弹性入场动画
    LaunchedEffect(refreshPending) {
        if (!refreshPending) return@LaunchedEffect
        enterMode = EnterMode.EMPHASIS
        snapshotFlow {
            when {
                selectedMode == 0 && selectedTab == 0 -> uiState.isLoadingMovies
                selectedMode == 0 && selectedTab == 1 -> uiState.isLoadingShows
                selectedMode == 0 && selectedTab == 2 -> uiState.isLoadingOthers
                selectedMode == 1 && selectedTab == 0 -> uiState.isLoadingHistoryMovies
                selectedMode == 1 && selectedTab == 1 -> uiState.isLoadingHistoryShows
                else -> uiState.isLoadingHistoryOthers
            }
        }
            .dropWhile { !it }
            .first { !it }
        delay(600)
        enterMode = EnterMode.DEFAULT
        refreshPending = false
    }

    // 监听列表变化，移除完成后关闭多选模式
    LaunchedEffect(currentItems.size, isRemoving) {
        if (isRemoving) {
            val remainingKeys = currentItems.map { it.selectionKey }.toSet()
            val selectedKeys = selectedItems.keys.toSet()
            if (selectedKeys.none { it in remainingKeys }) {
                isMultiSelectMode = false
                isRemoving = false
            }
        }
    }

    // 点击 token,确保只有被点击的卡片参与转场
    var activeClickToken by rememberSaveable { mutableStateOf(0) }

    CompositionLocalProvider(
        LocalActivePosterTmdbId provides activePosterTmdbId,
        LocalActivePosterClickSetter provides { id ->
            activePosterTmdbId = id
            activeClickToken += 1
            activeClickToken
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
                    .onGloballyPositioned { rootPositionInRoot = it.positionInRoot() }
                    .pointerInput(isSearchExpanded) {
                        if (!isSearchExpanded) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val up = waitForUpOrCancellation()
                            if (up != null && currentSearchBoundsInRootLocal?.contains(down.position) != true) {
                                collapseSearch()
                            }
                        }
                    }
                    .padding(paddingValues)
            ) {
                val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    
                // 弹性下拉刷新指示器
                if (overscrollOffset > 0f || isRefreshing) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = statusBarHeight + 60.dp)
                            .graphicsLayer { translationY = animatedOverscrollDp.toPx() },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            tint = if (isThresholdReached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isRefreshing) stringResource(R.string.watchlist_refreshing)
                            else if (isThresholdReached) stringResource(R.string.watchlist_release_to_refresh)
                            else stringResource(R.string.watchlist_pull_to_refresh),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isThresholdReached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
    
                // 空列表引导 UI
                if (currentItems.isEmpty() && !isCurrentLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        NeumorphicFrostedSurface(
                            modifier = Modifier.fillMaxWidth(),
                            isDark = isDark,
                            shape = RoundedCornerShape(24.dp),
                            backgroundColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.45f),
                            borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.65f),
                            elevation = 4.dp,
                            blurRadius = 16.dp,
                            hazeState = hazeState,
                            hazeStyle = HazeMaterials.thin()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Movie,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                if (searchQuery.isNotEmpty()) {
                                    // 搜索无结果：只显示贴切文案，不显示引导链接
                                    Text(
                                        text = stringResource(R.string.watchlist_search_no_result, searchQuery),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    Text(
                                        text = if (selectedMode == 0)
                                            stringResource(R.string.watchlist_empty_title)
                                        else
                                            stringResource(R.string.watched_empty_title),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = when (emptyState) {
                                            WatchlistEmptyState.NO_ACCOUNTS -> stringResource(
                                                if (selectedMode == 0) R.string.watchlist_empty_no_accounts
                                                else R.string.watched_empty_no_accounts
                                            )
                                            WatchlistEmptyState.TRAKT_ONLY -> stringResource(
                                                if (selectedMode == 0) R.string.watchlist_empty_trakt_only
                                                else R.string.watched_empty_trakt_only
                                            )
                                            WatchlistEmptyState.DOUBAN_NOT_IMPORTED -> stringResource(
                                                if (selectedMode == 0) R.string.watchlist_empty_douban_not_imported
                                                else R.string.watched_empty_douban_not_imported
                                            )
                                            WatchlistEmptyState.IMPORTED_EMPTY -> stringResource(
                                                if (selectedMode == 0) R.string.watchlist_empty_imported_empty
                                                else R.string.watched_empty_imported_empty
                                            )
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    when (emptyState) {
                                        WatchlistEmptyState.NO_ACCOUNTS -> {
                                            TextButton(onClick = onTraktLogin) {
                                                Text(stringResource(R.string.watchlist_empty_login_trakt))
                                            }
                                            TextButton(onClick = onNavigateToDoubanLogin) {
                                                Text(stringResource(R.string.watchlist_empty_login_douban))
                                            }
                                            TextButton(onClick = onDiscoverClick) {
                                                Text(stringResource(R.string.watchlist_empty_go_discover))
                                            }
                                        }
                                        WatchlistEmptyState.TRAKT_ONLY -> {
                                            TextButton(onClick = onNavigateToDoubanLogin) {
                                                Text(stringResource(R.string.watchlist_empty_login_douban))
                                            }
                                            TextButton(onClick = onDiscoverClick) {
                                                Text(stringResource(R.string.watchlist_empty_go_discover))
                                            }
                                        }
                                        WatchlistEmptyState.DOUBAN_NOT_IMPORTED -> {
                                            TextButton(onClick = {
                                                if (isDoubanLoggedIn) showSyncModePicker = true else onNavigateToDoubanLogin()
                                            }) {
                                                Text(stringResource(R.string.watchlist_empty_start_douban_import))
                                            }
                                        }
                                        WatchlistEmptyState.IMPORTED_EMPTY -> {
                                            TextButton(onClick = onDiscoverClick) {
                                                Text(stringResource(R.string.watchlist_empty_go_discover))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                LazyVerticalGrid(
                    state = currentGridState,
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 134.dp + statusBarHeight,
                        bottom = 80.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = hazeState)
                        .nestedScroll(pullToRefreshConnection)
                        .graphicsLayer { translationY = animatedOverscrollDp.toPx() }
                    ) {
                        // 根据 selectedMode 和 selectedTab 渲染对应列表
                        val items = currentItems
                        items(items.size, key = { items[it].selectionKey }, contentType = { "media_card" }) { index ->
                            val item = items[index]
                            val isSelected = selectedItems[item.selectionKey] == true
                            val isResolving = isRemoving && isSelected
                            Box(modifier = Modifier.cardEnter(item.selectionKey.hashCode().toLong(), index, enterMode, animatedIds)) {
                                WatchlistPosterCard(
                                    item = item,
                                    isInWatchlist = selectedMode == 0,
                                    isWatched = selectedMode == 1,
                                    isSelected = isSelected,
                                    isResolving = isResolving,
                                    isMultiSelectMode = isMultiSelectMode,
                                    onClick = {
                                        if (isMultiSelectMode) {
                                            if (isSelected) selectedItems.remove(item.selectionKey)
                                            else selectedItems[item.selectionKey] = true
                                            if (selectedItems.isEmpty()) isMultiSelectMode = false
                                        } else {
                                            val inWatchlist = selectedMode == 0
                                            val isWatched = selectedMode == 1
                                            if (onMediaItemClick != null) {
                                                onMediaItemClick(item, inWatchlist, isWatched)
                                            } else {
                                                // 兼容旧调用方；OTHER 不应因所在 tab 被误判为剧集。
                                                when (item.mediaType) {
                                                    WatchlistMediaType.SHOW -> onShowClick(
                                                        item.traktId,
                                                        item.tmdbId,
                                                        item.title,
                                                        item.imdbId,
                                                        item.traktRating,
                                                        inWatchlist,
                                                        isWatched
                                                    )
                                                    WatchlistMediaType.MOVIE,
                                                    WatchlistMediaType.OTHER -> onMovieClick(
                                                        item.traktId,
                                                        item.tmdbId,
                                                        item.title,
                                                        item.imdbId,
                                                        item.traktRating,
                                                        inWatchlist,
                                                        isWatched
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!isMultiSelectMode) {
                                            isMultiSelectMode = true
                                        }
                                        selectedItems[item.selectionKey] = true
                                    }
                                )
                            }
                        }
                    }
                }
    
                ScrollToTopButton(
                    gridState = currentGridState,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 100.dp, end = 16.dp),
                    hazeState = hazeState
                )
    
                // Haze 模糊覆盖层 - 搜索框 + 胶囊切换 + PrimaryTabRow 或 多选操作栏
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .hazeTopBar(
                            state = hazeState,
                            style = hazeStyle,
                            blurRadius = 24.dp,
                            isContentUnderTopBar = hasContentUnderTopBar
                        )
                ) {
                    // 搜索框 + Tab 栏（非多选模式时显示）
                    AnimatedVisibility(
                        visible = !isMultiSelectMode,
                        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            // 毛玻璃吸顶标题栏（继承外层 Blur，不重复叠加避免变白）
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSearchExpanded) {
                                            collapseSearch()
                                        } else {
                                            gridCoroutineScope.launch {
                                                currentGridState.animateScrollToItem(0)
                                            }
                                        }
                                    }
                            ) {
                                Column {
                                    Spacer(modifier = Modifier.statusBarsPadding())
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                                            .height(42.dp)
                                    ) {
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = !isSearchExpanded,
                                            enter = fadeIn(animationSpec = tween(240)),
                                            exit = fadeOut(animationSpec = tween(240)),
                                            modifier = Modifier.align(Alignment.CenterStart)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.tab_me),
                                                fontSize = 28.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = (-0.5).sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    val searchHintColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                    val searchPlaceholder = if (searchQuery.isBlank()) {
                                        when (selectedMode) {
                                            0 -> stringResource(R.string.watchlist_search_watchlist)
                                            else -> stringResource(R.string.watchlist_search_history)
                                        }
                                    } else stringResource(R.string.search_placeholder_watchlist)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(42.dp)
                                            .zIndex(if (isSearchExpanded) 1f else 0f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        BoxWithConstraints(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(42.dp),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            val searchWidth by animateDpAsState(
                                                targetValue = if (isSearchExpanded) maxWidth else 42.dp,
                                                animationSpec = tween(durationMillis = 240),
                                                label = "watchlist_search_width"
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .width(searchWidth)
                                                    .fillMaxHeight()
                                                    .onGloballyPositioned { coordinates ->
                                                        searchBoundsInRoot = coordinates.boundsInRoot()
                                                    }
                                            ) {
                                                if (isSearchExpanded) {
                                                    NeumorphicFrostedSurface(
                                                        modifier = Modifier.fillMaxSize(),
                                                        isDark = isDark,
                                                        shape = RoundedCornerShape(21.dp),
                                                        backgroundColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.55f),
                                                        borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.75f),
                                                        elevation = 4.dp,
                                                        blurRadius = 16.dp,
                                                        hazeState = hazeState,
                                                        hazeStyle = HazeMaterials.thin()
                                                    ) {
                                                        BasicTextField(
                                                            value = searchQuery,
                                                            onValueChange = { searchQuery = it },
                                                            singleLine = true,
                                                            textStyle = TextStyle(
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                fontSize = 14.sp
                                                            ),
                                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .focusRequester(focusRequester)
                                                                .testTag("watchlist_search_input")
                                                                .padding(horizontal = 12.dp),
                                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                                            keyboardActions = KeyboardActions(
                                                                onSearch = {
                                                                    focusManager.clearFocus()
                                                                    if (searchQuery.isNotBlank() && selectedMode == 0) {
                                                                        val noResults = when (selectedTab) {
                                                                            0 -> filteredMovies.isEmpty()
                                                                            1 -> filteredShows.isEmpty()
                                                                            else -> filteredOthers.isEmpty()
                                                                        }
                                                                        if (noResults && selectedTab != 2) {
                                                                            onTraktSearch(if (selectedTab == 0) "movie" else "show", searchQuery)
                                                                        }
                                                                    }
                                                                }
                                                            ),
                                                            decorationBox = { innerTextField ->
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    modifier = Modifier.fillMaxSize()
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Rounded.Search,
                                                                        contentDescription = null,
                                                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                                        modifier = Modifier.size(20.dp)
                                                                    )
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .weight(1f)
                                                                            .padding(horizontal = 8.dp),
                                                                        contentAlignment = Alignment.CenterStart
                                                                    ) {
                                                                        if (searchQuery.isEmpty()) {
                                                                            Text(
                                                                                text = searchPlaceholder,
                                                                                color = searchHintColor,
                                                                                fontSize = 14.sp
                                                                            )
                                                                        }
                                                                        innerTextField()
                                                                    }
                                                                    if (searchQuery.isNotEmpty()) {
                                                                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                                                                            Icon(
                                                                                Icons.Rounded.Close,
                                                                                contentDescription = stringResource(R.string.content_desc_clear),
                                                                                modifier = Modifier.size(18.dp),
                                                                                tint = if (isDark) Color.White.copy(alpha = 0.72f) else Color(0xFF546E7A)
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        )
                                                    }
                                                } else {
                                                    NeumorphicIconButton(
                                                        onClick = { isSearchExpanded = true },
                                                        isDark = isDark,
                                                        lightBorderAlpha = 0.35f
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Search,
                                                            contentDescription = stringResource(R.string.watchlist_search),
                                                            tint = if (searchQuery.isNotBlank()) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurface
                                                            },
                                                            modifier = Modifier.size(22.dp)
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
                                            lightBorderAlpha = 0.35f
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Tune,
                                                contentDescription = stringResource(R.string.filter_title),
                                                tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        CapsuleTabSelector(
                                            tabs = listOf(
                                                stringResource(R.string.watchlist_mode_watchlist),
                                                stringResource(R.string.watchlist_mode_watched)
                                            ),
                                            selectedIndex = selectedMode,
                                            onTabSelected = {
                                                collapseSearch()
                                                selectedMode = it
                                            },
                                            sizeMultiplier = 1.25f
                                        )
                                    }
                                    }
                                }
                            }
    
                            // 分类 Tab：名称与数量徽标使用 Watchlist 专用组件
                            val hasLocalFilter = searchQuery.isNotBlank() || hasActiveFilters
                            val movieCount = if (selectedMode == 1 || isDoubanMode || hasLocalFilter) {
                                if (selectedMode == 1) filteredHistoryMovies.size else filteredMovies.size
                            } else {
                                uiState.movieTotalCount ?: if (uiState.moviesLoaded) filteredMovies.size else 0
                            }
                            val showCount = if (selectedMode == 1 || isDoubanMode || hasLocalFilter) {
                                if (selectedMode == 1) filteredHistoryShows.size else filteredShows.size
                            } else {
                                uiState.showTotalCount ?: if (uiState.showsLoaded) filteredShows.size else 0
                            }
                            val otherCount = if (selectedMode == 1 || isDoubanMode || hasLocalFilter) {
                                if (selectedMode == 1) filteredHistoryOthers.size else filteredOthers.size
                            } else {
                                uiState.otherTotalCount ?: if (uiState.othersLoaded) filteredOthers.size else 0
                            }
                            WatchlistCategoryTabs(
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                                tabs = listOf(
                                    WatchlistCategoryTab(
                                        label = stringResource(R.string.watchlist_tab_movies),
                                        count = movieCount
                                    ),
                                    WatchlistCategoryTab(
                                        label = stringResource(R.string.watchlist_tab_shows),
                                        count = showCount
                                    ),
                                    WatchlistCategoryTab(
                                        label = stringResource(R.string.detail_disk_type_other),
                                        count = otherCount
                                    )
                                ),
                                selectedIndex = selectedTab,
                                onTabSelected = { index ->
                                    collapseSearch()
                                    view.performHaptic(HapticType.CLICK)
                                    selectedTab = index
                                }
                            )
    
                            // 豆瓣同步进度横幅（同步进行中或刚完成 5 秒内显示）
                            val syncProgress = uiState.doubanSyncProgress
                            if (syncProgress != null && uiState.doubanSyncBannerVisible) {
                                // 同步进行中时图标无限旋转动画
                                val spinTransition = rememberInfiniteTransition(label = "sync_spin")
                                val spinRotation by spinTransition.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(durationMillis = 1000, easing = LinearEasing)
                                    ),
                                    label = "sync_rotation"
                                )
                                NeumorphicFrostedSurface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            when (syncProgress.bannerClickAction()) {
                                                DoubanSyncBannerAction.SHOW_PROGRESS,
                                                DoubanSyncBannerAction.SHOW_RESULT -> showSyncDialog = true
                                                DoubanSyncBannerAction.NAVIGATE_TO_DOUBAN_LOGIN -> {
                                                    viewModel.clearDoubanSyncResult()
                                                    onNavigateToDoubanLogin()
                                                }
                                                DoubanSyncBannerAction.NAVIGATE_TO_TRAKT_LOGIN -> {
                                                    viewModel.clearDoubanSyncResult()
                                                    onTraktLogin()
                                                }
                                            }
                                        },
                                    isDark = isDark,
                                    shape = RoundedCornerShape(12.dp),
                                    backgroundColor = if (syncProgress.cookieExpired) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    borderColor = if (syncProgress.cookieExpired) MaterialTheme.colorScheme.error.copy(alpha = 0.20f)
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                    elevation = 2.dp,
                                    blurRadius = 12.dp,
                                    hazeState = hazeState,
                                    hazeStyle = HazeMaterials.thin()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (syncProgress.isRunning) Icons.Rounded.Sync else Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = if (syncProgress.cookieExpired) MaterialTheme.colorScheme.onErrorContainer
                                                else MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .then(
                                                    if (syncProgress.isRunning) Modifier.graphicsLayer { rotationZ = -spinRotation }
                                                    else Modifier
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            val stageLabel = stringResource(syncProgress.stage.labelRes())
                                            val targetLabel = when (syncProgress.subStage) {
                                                DoubanSyncSubStage.FETCHING_WISH_LIST ->
                                                    stringResource(R.string.douban_sync_preview_status_wish)
                                                DoubanSyncSubStage.FETCHING_COLLECT_LIST ->
                                                    stringResource(R.string.douban_sync_preview_status_collect)
                                                else -> null
                                            }
                                            val subStageLabel = syncProgress.bannerSubStageRes()
                                                ?.let { stringResource(it) }
                                            val hasLiveCountProgress = syncProgress.hasLiveCountProgress()
                                            Text(
                                                text = if (syncProgress.cookieExpired) {
                                                    stringResource(R.string.douban_sync_cookie_expired_banner)
                                                } else if (
                                                    syncProgress.stage == com.tracktosearch.data.repository.DoubanSyncStage.LOGIN_REQUIRED &&
                                                    syncProgress.loginTarget == DoubanSyncLoginTarget.DOUBAN
                                                ) {
                                                    stringResource(R.string.douban_sync_douban_login_required_banner)
                                                } else if (
                                                    syncProgress.stage == com.tracktosearch.data.repository.DoubanSyncStage.LOGIN_REQUIRED &&
                                                    syncProgress.loginTarget == DoubanSyncLoginTarget.TRAKT
                                                ) {
                                                    stringResource(R.string.douban_sync_trakt_login_required_banner)
                                                } else if (syncProgress.stage == com.tracktosearch.data.repository.DoubanSyncStage.FAILED) {
                                                    stringResource(R.string.douban_sync_stage_failed)
                                                } else if (syncProgress.stage == com.tracktosearch.data.repository.DoubanSyncStage.CANCELLING) {
                                                    stringResource(R.string.douban_sync_cancelled_banner)
                                                } else if (syncProgress.isComplete) {
                                                    stringResource(R.string.douban_sync_complete_banner, syncProgress.successCount)
                                                } else if (hasLiveCountProgress && targetLabel != null) {
                                                    stringResource(
                                                        R.string.douban_sync_notification_progress_format,
                                                        stageLabel,
                                                        targetLabel,
                                                        syncProgress.current,
                                                        syncProgress.total
                                                    )
                                                } else if (hasLiveCountProgress && subStageLabel != null) {
                                                    stringResource(
                                                        R.string.douban_sync_notification_progress_format,
                                                        stageLabel,
                                                        subStageLabel,
                                                        syncProgress.current,
                                                        syncProgress.total
                                                    )
                                                } else if (hasLiveCountProgress) {
                                                    stringResource(
                                                        R.string.douban_sync_progress_format,
                                                        stageLabel,
                                                        syncProgress.current,
                                                        syncProgress.total
                                                    )
                                                } else if (subStageLabel != null) {
                                                    stringResource(
                                                        R.string.douban_sync_notification_stage_format,
                                                        stageLabel,
                                                        subStageLabel
                                                    )
                                                } else {
                                                    stageLabel
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (syncProgress.cookieExpired) MaterialTheme.colorScheme.onErrorContainer
                                                    else MaterialTheme.colorScheme.onPrimary
                                            )
                                            if (hasLiveCountProgress) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LinearProgressIndicator(
                                                    progress = { (syncProgress.current.toFloat() / syncProgress.total).coerceIn(0f, 1f) },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            } else if (syncProgress.isRunning) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                            }
                                        }
                                        if (syncProgress.cookieExpired) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.douban_sync_relogin),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            }
    
                            // 状态一致性检查进度横幅（检查进行中或刚完成 5 秒内显示）
                            val checkProgress = uiState.consistencyCheckProgress
                            if (checkProgress != null) {
                                val checkSpinTransition = rememberInfiniteTransition(label = "check_spin")
                                val checkSpinRotation by checkSpinTransition.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(durationMillis = 1000, easing = LinearEasing)
                                    ),
                                    label = "check_rotation"
                                )
                                NeumorphicFrostedSurface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showConsistencyDialog = true },
                                    isDark = isDark,
                                    shape = RoundedCornerShape(12.dp),
                                    backgroundColor = if (checkProgress.cookieExpired) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    borderColor = if (checkProgress.cookieExpired) MaterialTheme.colorScheme.error.copy(alpha = 0.20f)
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                    elevation = 2.dp,
                                    blurRadius = 12.dp,
                                    hazeState = hazeState,
                                    hazeStyle = HazeMaterials.thin()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (checkProgress.isRunning) Icons.Rounded.Sync
                                                else Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = if (checkProgress.cookieExpired) MaterialTheme.colorScheme.onErrorContainer
                                                else MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .then(
                                                    if (checkProgress.isRunning) Modifier.graphicsLayer { rotationZ = -checkSpinRotation }
                                                    else Modifier
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            val checkPhase = checkProgress.phase.ifBlank {
                                                stringResource(R.string.consistency_check_phase_preparing)
                                            }
                                            val checkSubPhase = checkProgress.subPhase.takeIf {
                                                it.isNotBlank() && !checkPhase.contains(it)
                                            }
                                            Text(
                                                text = if (checkProgress.cookieExpired) {
                                                    stringResource(R.string.douban_sync_cookie_expired_banner)
                                                } else if (checkProgress.isComplete) {
                                                    stringResource(R.string.consistency_check_complete_banner)
                                                } else if (checkProgress.total > 0 && checkSubPhase != null) {
                                                    stringResource(
                                                        R.string.consistency_check_notification_progress_with_subphase,
                                                        checkPhase,
                                                        checkSubPhase,
                                                        checkProgress.current,
                                                        checkProgress.total
                                                    )
                                                } else if (checkProgress.total > 0) {
                                                    stringResource(
                                                        R.string.consistency_check_notification_progress,
                                                        checkPhase,
                                                        checkProgress.current,
                                                        checkProgress.total
                                                    )
                                                } else if (checkSubPhase != null) {
                                                    stringResource(
                                                        R.string.consistency_check_notification_stage_with_subphase,
                                                        checkPhase,
                                                        checkSubPhase
                                                    )
                                                } else {
                                                    checkPhase
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (checkProgress.cookieExpired) MaterialTheme.colorScheme.onErrorContainer
                                                    else MaterialTheme.colorScheme.onPrimary
                                            )
                                            if (checkProgress.isRunning && checkProgress.total > 0) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LinearProgressIndicator(
                                                    progress = { (checkProgress.current.toFloat() / checkProgress.total).coerceIn(0f, 1f) },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 豆瓣标记批量移除进度横幅（移除进行中或刚完成 5 秒内显示）
                            val removalProgress = uiState.batchRemovalProgress
                            if (removalProgress != null) {
                                val removalSpinTransition = rememberInfiniteTransition(label = "removal_spin")
                                val removalSpinRotation by removalSpinTransition.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(durationMillis = 1000, easing = LinearEasing)
                                    ),
                                    label = "removal_rotation"
                                )
                                val removalBackground = if (removalProgress.isCancelling) {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                }
                                val removalBorder = if (removalProgress.isCancelling) {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
                                } else {
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.20f)
                                }
                                NeumorphicFrostedSurface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // 完成态点击无操作（横幅 5 秒后自动消失）
                                            if (removalProgress.isRunning && !removalProgress.isCancelling) {
                                                viewModel.cancelBatchRemoval()
                                            }
                                        },
                                    isDark = isDark,
                                    shape = RoundedCornerShape(12.dp),
                                    backgroundColor = removalBackground,
                                    borderColor = removalBorder,
                                    elevation = 2.dp,
                                    blurRadius = 12.dp,
                                    hazeState = hazeState,
                                    hazeStyle = HazeMaterials.thin()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (removalProgress.isRunning) Icons.Rounded.Delete
                                            else Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .then(
                                                    if (removalProgress.isRunning) Modifier.graphicsLayer { rotationZ = removalSpinRotation }
                                                    else Modifier
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            // phase 是 enum,映射到本地化字符串避免直接显示 enum 名违反 i18n
                                            val removalPhaseText = when (removalProgress.phase) {
                                                BatchRemovalPhase.REMOVING -> stringResource(R.string.batch_removal_phase_removing)
                                                BatchRemovalPhase.CANCELLING -> stringResource(R.string.batch_removal_phase_cancelling)
                                                BatchRemovalPhase.DONE -> stringResource(R.string.batch_removal_phase_done)
                                                BatchRemovalPhase.CANCELLED -> stringResource(R.string.batch_removal_phase_cancelled)
                                            }
                                            Text(
                                                text = if (removalProgress.isComplete) {
                                                    stringResource(
                                                        R.string.douban_batch_removal_complete,
                                                        removalProgress.successCount,
                                                        removalProgress.failCount + removalProgress.skipCount
                                                    )
                                                } else if (removalProgress.isCancelling) {
                                                    stringResource(R.string.douban_batch_removal_cancelling)
                                                } else if (removalProgress.total > 0) {
                                                    "$removalPhaseText (${removalProgress.current}/${removalProgress.total})"
                                                } else {
                                                    removalPhaseText
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            if (removalProgress.isRunning && removalProgress.total > 0) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LinearProgressIndicator(
                                                    progress = { (removalProgress.current.toFloat() / removalProgress.total).coerceIn(0f, 1f) },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                        if (removalProgress.isRunning && !removalProgress.isCancelling) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.douban_batch_removal_cancel),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                }
                            }

                            // TMDB 不可用提示(可关闭,数据刷新后自动恢复)
                        }
                    }
    
                    // 多选操作栏（多选模式时显示）
                    AnimatedVisibility(
                        visible = isMultiSelectMode,
                        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.statusBarsPadding())
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = false, onClick = {})
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    IconButton(onClick = { isMultiSelectMode = false }) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.ArrowBack,
                                            contentDescription = stringResource(R.string.common_cancel),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.watchlist_selected_count, selectedItems.size),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row {
                                        Button(
                                            onClick = {
                                                isRemoving = true
                                                // 豆瓣模式无 traktId 条目也需正确移除,改用 selectionKey 匹配出完整 MediaUiItem
                                                val selectedItemsList = currentItems.filter { it.selectionKey in selectedItems.keys }
                                                val type = when (selectedTab) {
                                                    0 -> WatchlistMediaType.MOVIE
                                                    1 -> WatchlistMediaType.SHOW
                                                    else -> WatchlistMediaType.OTHER
                                                }
                                                tabScope.launch {
                                                    val hasFailure = if (selectedMode == 0) {
                                                        viewModel.batchRemoveFromWatchlist(selectedItemsList, type)
                                                    } else {
                                                        viewModel.batchRemoveFromHistory(selectedItemsList, type)
                                                    }
                                                    // 豆瓣批量移除在 Application scope 后台运行，启动前台服务显示通知栏进度
                                                    if (viewModel.isBatchRemovalRunning()) {
                                                        com.tracktosearch.service.DoubanBatchRemovalService.start(context)
                                                    }
                                                    // 等待批量操作完成后才关闭多选栏，避免提前关闭导致用户以为已处理但实际仍在进行
                                                    isMultiSelectMode = false
                                                    isRemoving = false
                                                    // 部分条目移除失败时提示用户（成功的项已更新 UI 并启动豆瓣移除）
                                                    if (hasFailure) {
                                                        Toast.makeText(
                                                            context,
                                                            batchRemovePartialFailedMessage,
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                }
                                            },
                                            enabled = !isRemoving,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            )
                                        ) {
                                            if (isRemoving) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onError
                                                )
                                            } else {
                                                Text(
                                                    if (selectedMode == 0) stringResource(R.string.watchlist_remove_watchlist)
                                                    else stringResource(R.string.watchlist_remove_history)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        OutlinedButton(onClick = { isMultiSelectMode = false; isRemoving = false }) {
                                            Text(stringResource(R.string.common_cancel))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
    
                // 骨架屏：首次加载且列表为空时显示（避免 TMDB 富化过程中部分卡片已显示但骨架仍叠加）
                val isLoading = if (selectedMode == 0) {
                    when (selectedTab) {
                        0 -> uiState.isLoadingMovies && !uiState.moviesLoaded && uiState.movies.isEmpty()
                        1 -> uiState.isLoadingShows && !uiState.showsLoaded && uiState.shows.isEmpty()
                        else -> uiState.isLoadingOthers && !uiState.othersLoaded && uiState.others.isEmpty()
                    }
                } else {
                    when (selectedTab) {
                        0 -> uiState.isLoadingHistoryMovies && !uiState.historyMoviesLoaded && uiState.historyMovies.isEmpty()
                        1 -> uiState.isLoadingHistoryShows && !uiState.historyShowsLoaded && uiState.historyShows.isEmpty()
                        else -> uiState.isLoadingHistoryOthers && !uiState.historyOthersLoaded && uiState.historyOthers.isEmpty()
                    }
                }
                if (isLoading) {
                    WatchlistSkeletonGrid(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 136.dp + statusBarHeight)
                    )
                }
            }
    
            // 豆瓣同步进度弹窗（点击横幅重新打开）
            if (showSyncDialog) {
                DoubanSyncDialog(
                    onDismiss = {
                        // 同步运行中不允许通过点击外部关闭（需点「转后台」或「取消」）
                        val p = uiState.doubanSyncProgress
                        if (p == null || !p.isRunning) {
                            showSyncDialog = false
                            viewModel.clearDoubanSyncResult()
                        }
                    },
                    onBackground = {
                        // 「转后台」:仅隐藏弹窗,同步在 Application scope 继续运行,横幅会继续显示进度
                        showSyncDialog = false
                    },
                    onRelogin = {
                        showSyncDialog = false
                        viewModel.clearDoubanSyncResult()
                        onNavigateToDoubanLogin()
                    },
                    onTraktLogin = {
                        showSyncDialog = false
                        viewModel.clearDoubanSyncResult()
                        onTraktLogin()
                    }
                )
            }
    
            // 状态一致性检查进度弹窗（点击横幅重新打开）
            if (showConsistencyDialog) {
                com.tracktosearch.ui.screen.settings.ConsistencyCheckDialog(
                    onDismiss = {
                        val p = uiState.consistencyCheckProgress
                        if (p == null || !p.isRunning) {
                            showConsistencyDialog = false
                            // 用户主动关闭结果弹窗 → 清除横幅与进度（结果常驻，手动关闭而非自动消失）
                            viewModel.clearConsistencyCheckResult()
                        }
                    },
                    onBackground = { showConsistencyDialog = false }
                )
            }
    
            // 首次同步引导弹窗（已登录豆瓣但从未同步过时自动弹出）
            if (showFirstSyncGuide && !isDoubanSyncRunning) {
                DoubanFirstSyncGuideDialog(
                    onDismiss = { showFirstSyncGuide = false },
                    onStartImport = {
                        showFirstSyncGuide = false
                        showSyncModePicker = true
                    }
                )
            }
    
            // 同步模式选择弹窗（引导弹窗确认后弹出）
            if (showSyncModePicker) {
                DoubanSyncModePickerDialog(
                    syncedCount = 0,
                    cooldownStatus = null,
                    neverSynced = true,
                    onDismiss = { showSyncModePicker = false },
                    onModeSelected = { mode ->
                        showSyncModePicker = false
                        viewModel.startDoubanSync(mode)
                        // 立即显示进度弹窗，让用户看到同步过程
                        showSyncDialog = true
                    }
                )
            }
    
            // 筛选 ModalBottomSheet
            if (showFilterSheet) {
                WatchlistFilterSheet(
                    filterState = filterState,
                    availableGenres = availableGenres,
                    decadeOptions = viewModel.decadeOptions.collectAsStateWithLifecycle().value,
                    onGenresChange = viewModel::updateSelectedGenres,
                    onDecadeToggle = viewModel::toggleDecade,
                    onMarkedTimePresetChange = viewModel::updateMarkedTimePreset,
                    onMarkedTimeOrderChange = viewModel::updateMarkedTimeOrder,
                    onRatingRangeChange = viewModel::updateRatingRange,
                    onReset = viewModel::resetFilters,
                    onApply = { showFilterSheet = false }
                )
            }
        }
    } // CompositionLocalProvider
}

/**
 * Watchlist 网格项包装器：基于 PosterCard 显示海报，并补充标题、状态角标、
 * 加载遮罩、多选遮罩以及共享元素转场。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun WatchlistPosterCard(
    item: MediaUiItem,
    isInWatchlist: Boolean,
    isWatched: Boolean,
    isSelected: Boolean,
    isResolving: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    val activePosterTmdbId = LocalActivePosterTmdbId.current
    val setActivePosterTmdbId = LocalActivePosterClickSetter.current
    val activeClickToken = LocalActivePosterClickToken.current
    var myClickToken by rememberSaveable { mutableStateOf(0) }
    // 只有被点击激活的当前页海报才启用共享元素转场，避免同 tmdbId 卡片误匹配
    val enableShared = item.tmdbId > 0
            && item.tmdbId == activePosterTmdbId
            && myClickToken != 0
            && myClickToken == activeClickToken

    val wrappedOnClick = remember(onClick, item.tmdbId, isMultiSelectMode) {
        {
            if (!isMultiSelectMode && item.tmdbId > 0) {
                myClickToken = setActivePosterTmdbId(item.tmdbId)
            }
            onClick()
        }
    }

    val posterModifier = if (enableShared && sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
        with(sharedTransitionScope) {
            Modifier
                .sharedElement(
                    rememberSharedContentState(key = "poster-${item.tmdbId}"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
                .clip(RoundedCornerShape(14.dp))
        }
    } else {
        Modifier
    }

    val context = LocalContext.current
    // 通过 EntryPoint 获取 PosterColorExtractor 单例，用于提前提取海报主色写入缓存
    val posterColorExtractor = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PosterColorExtractorProvider::class.java
        ).posterColorExtractor()
    }
    // 海报加载成功 + 卡片仍在屏幕上 1.5s 后才提取主色，避免快速滑动时大量触发 Palette 计算
    var posterLoaded by remember { mutableStateOf(false) }
    var colorExtracted by remember { mutableStateOf(false) }
    var posterLoadedBitmap: Bitmap? by remember { mutableStateOf(null) }

    LaunchedEffect(item.posterUrl) {
        posterLoaded = false
        colorExtracted = false
        posterLoadedBitmap = null
    }

    LaunchedEffect(posterLoaded, item.posterUrl) {
        if (posterLoaded && !colorExtracted && item.posterUrl != null) {
            delay(500L)
            if (posterLoaded && !colorExtracted) {
                val bitmap = posterLoadedBitmap
                if (bitmap != null) {
                    withContext(Dispatchers.Default) {
                        posterColorExtractor.extractDominantColor(item.posterUrl, bitmap)
                    }
                    colorExtracted = true
                }
            }
        }
    }

    DisposableEffect(item.posterUrl) {
        onDispose { posterLoadedBitmap = null }
    }

    Column {
        Box {
            PosterCard(
                imageUrl = item.posterUrl,
                title = item.displayTitle,
                year = item.year?.toString(),
                genres = null,
                imageSize = 264,
                onImageSuccess = { bitmap ->
                    posterLoadedBitmap = bitmap
                    posterLoaded = true
                },
                onClick = wrappedOnClick,
                onLongClick = if (isMultiSelectMode) null else onLongClick,
                posterModifier = posterModifier
            )
            // TMDB 补充数据失败时，在对应海报卡片内提示，避免页面级 Toast 与具体条目脱节
            if (item.tmdbId > 0 && item.posterUrl == null) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.watchlist_poster_error),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            // 想看/已看角标（海报左上角，沿用 MovieCard 样式）
            if (isWatched || isInWatchlist) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            color = if (isWatched) Color(0xCC000000)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (isWatched) {
                        Icon(
                            imageVector = Icons.Rounded.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color.White
                        )
                        Text(
                            text = stringResource(R.string.cd_watched_badge),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Bookmark,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = Color.White
                        )
                        Text(
                            text = stringResource(R.string.cd_watchlist_badge),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = Color.White
                        )
                    }
                }
            }
            // 加载遮罩
            if (isResolving) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                }
            }
            // 多选模式的选中遮罩与勾选图标
            if (isMultiSelectMode) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .then(
                            if (isSelected) {
                                Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            } else {
                                Modifier
                                    .clip(CircleShape)
                                    .background(Color.Transparent)
                                    .border(
                                        BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)),
                                        CircleShape
                                    )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
        // 标题（与之前 MovieCard 字号一致，最多两行）
        AdaptiveTwoLineTitle(
            text = item.displayTitle,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            maxFontSize = 14.sp,
            minFontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 1.dp)
        )
        if (item.genres.isNotEmpty()) {
            Text(
                text = item.genres,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
            )
        }
    }
}

/** 骨架屏网格 - 3列，海报占位 + 标题条 + 类型条，呼吸动画 */
@Composable
private fun WatchlistSkeletonGrid(modifier: Modifier = Modifier) {
    val brush = rememberShimmerBrush()
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 0.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        items(6) {
            Column {
                // 海报占位：2:3 圆角矩形
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(brush)
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 标题条
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 类型条
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
            }
        }
    }
}

/**
 * 筛选 ModalBottomSheet：类型多选 + 年代多选 + 标记时间区间 + 排序方向 + Trakt 评分区间。
 * 布局参考影视筛选页：年份/评分/排序/标记时间均标题+内容同一行。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun WatchlistFilterSheet(
    filterState: FilterState,
    availableGenres: List<String>,
    decadeOptions: List<Int>,
    onGenresChange: (Set<String>) -> Unit,
    onDecadeToggle: (Int) -> Unit,
    onMarkedTimePresetChange: (MarkedTimePreset) -> Unit,
    onMarkedTimeOrderChange: (SortOrder) -> Unit,
    onRatingRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onApply,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 统一背景色与发现页查看全部 sheet 一致
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        // 去除默认 drag 条,内容更紧凑
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 类型多选(chip 按估算宽度降序排列:长块先占位,短块填缝,行数少且每行数量均衡)
            // 估算宽度 = 中文字符数 * 14dp + 24dp(chip 内边距)
            val sortedGenres = remember(availableGenres) {
                availableGenres.sortedByDescending { genre ->
                    val cjkCount = genre.count { it.code in 0x4E00..0x9FFF }
                    val otherCount = genre.length - cjkCount
                    cjkCount * 14 + otherCount * 8 + 24
                }
            }
            Text(
                text = stringResource(R.string.filter_genre),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                sortedGenres.forEach { genre ->
                    FilterChip(
                        selected = genre in filterState.selectedGenres,
                        border = if (genre in filterState.selectedGenres) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        onClick = {
                            val newSet = if (genre in filterState.selectedGenres) {
                                filterState.selectedGenres - genre
                            } else {
                                filterState.selectedGenres + genre
                            }
                            onGenresChange(newSet)
                        },
                        label = { Text(genre) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            // 年代多选(标题左侧垂直居中 + chips 右侧固定每行最多 3 个,宽度跟随内容,每行内右对齐)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.filter_year),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                // 右侧 chips 宽度跟随内容,每行最多 3 个(chip 不设 weight,用手动换行;每行内右对齐)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    val rows = decadeOptions.chunked(3)
                    rows.forEach { rowDecades ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            rowDecades.forEach { decade ->
                                FilterChip(
                                    selected = decade in filterState.selectedDecadeKeys,
                                    border = if (decade in filterState.selectedDecadeKeys) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                    onClick = { onDecadeToggle(decade) },
                                    label = { Text("${decade}s") }
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            // 评分 RangeSlider(Trakt 评分 标题 + 滑动条同一行)
            val view = LocalView.current
            // 跟踪上一次的整数值，仅在整数变化时触发触感反馈（避免拖动过程中频繁震动）
            var lastRatingStart by remember(filterState.ratingRange.start) { mutableStateOf(filterState.ratingRange.start.toInt()) }
            var lastRatingEnd by remember(filterState.ratingRange.endInclusive) { mutableStateOf(filterState.ratingRange.endInclusive.toInt()) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.filter_rating_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                // 滑动条占 65% 宽度,右对齐 0-10 评分值加大加粗
                // 包一层拦截竖直滑动,避免拖动滑块时触发 sheet 上下移动
                val ratingScrollConnection = remember {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                            Offset(0f, available.y)

                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource
                        ): Offset = Offset(0f, available.y)

                        override suspend fun onPreFling(available: Velocity): Velocity =
                            Velocity(0f, available.y)

                        override suspend fun onPostFling(
                            consumed: Velocity,
                            available: Velocity
                        ): Velocity = Velocity(0f, available.y)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .nestedScroll(ratingScrollConnection)
                        // 拦截竖直拖拽:RangeSlider 拖动是 pointerInput 级别,不走 nestedScroll。
                        // 斜向拖动时竖直分量会冒泡到 ModalBottomSheet 的 anchoredDraggable 触发 sheet 移动。
                        // 用 draggable(Vertical, startDragImmediately=true) 跳过 touch slop,
                        // 在第一个 move 事件就立即消费竖直分量,使 anchoredDraggable 永远无法累积
                        // 竖直 slop 启动拖拽。RangeSlider(子节点,Main pass 先处理)仍能正常检测水平拖拽。
                        .draggable(
                            state = rememberDraggableState { _ -> },
                            orientation = Orientation.Vertical,
                            startDragImmediately = true,
                            enabled = true
                        )
                ) {
                    RangeSlider(
                        value = filterState.ratingRange,
                        onValueChange = { range ->
                            // 仅在整数值变化时触发触感反馈（参考 PanHubConfigDialog 的并发数滑动条）
                            val newStart = range.start.toInt()
                            val newEnd = range.endInclusive.toInt()
                            if (newStart != lastRatingStart || newEnd != lastRatingEnd) {
                                view.performHaptic(HapticType.TICK)
                                lastRatingStart = newStart
                                lastRatingEnd = newEnd
                            }
                            onRatingRangeChange(range)
                        },
                        valueRange = 0f..10f,
                        steps = 9,  // 步长 1（0,1,2,...,10）
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = "%.0f-%.0f".format(filterState.ratingRange.start, filterState.ratingRange.endInclusive),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            // 标记时间区间(标题+chips 共用一行,SpaceBetween 让每行均匀分布)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.filter_marked_time),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    MarkedTimePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = filterState.markedTimePreset == preset,
                            border = if (filterState.markedTimePreset == preset) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            onClick = { onMarkedTimePresetChange(preset) },
                            label = {
                                Text(stringResource(when (preset) {
                                    MarkedTimePreset.SEVEN_DAYS -> R.string.filter_time_7d
                                    MarkedTimePreset.THIRTY_DAYS -> R.string.filter_time_30d
                                    MarkedTimePreset.ALL -> R.string.filter_time_all
                                }))
                            }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            // 排序方式(标题左侧 + SegmentedButtonRow 右侧对齐,按钮高度压缩为单行)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.filter_sort_order),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                // 右侧 SegmentedButtonRow 靠右,固定最小高度保持单行
                // wrapContentWidth 让 Row 宽度按内容撑开
                // 给每个 button 加 widthIn(min) 防止 weight(1f) 把内容压缩到文字截断
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .height(32.dp)
                        .wrapContentWidth()
                ) {
                    SegmentedButton(
                        selected = filterState.markedTimeOrder == SortOrder.DESC,
                        onClick = { onMarkedTimeOrderChange(SortOrder.DESC) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        modifier = Modifier.widthIn(min = 100.dp),
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 0.dp, bottom = 0.dp),
                        label = { Text(stringResource(R.string.filter_sort_desc), maxLines = 1) },
                        icon = {
                            Icon(
                                Icons.Rounded.ArrowDownward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                    SegmentedButton(
                        selected = filterState.markedTimeOrder == SortOrder.ASC,
                        onClick = { onMarkedTimeOrderChange(SortOrder.ASC) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        modifier = Modifier.widthIn(min = 100.dp),
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 0.dp, bottom = 0.dp),
                        label = { Text(stringResource(R.string.filter_sort_asc), maxLines = 1) },
                        icon = {
                            Icon(
                                Icons.Rounded.ArrowUpward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 重置 + 应用
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.filter_reset))
                }
                Button(onClick = onApply) {
                    Text(stringResource(R.string.filter_apply))
                }
            }
        }
    }
}

/**
 * 应用搜索 + 筛选条件，并按标记时间排序。
 * - 搜索：标题/displayTitle 包含关键词（忽略大小写）
 * - 类型：多选，item.genres（按 `,` 或 `·` 分隔）与选中类型有交集即通过；未选则全部通过
 * - 年份：按年代多选匹配（null year 视为不通过；未选年代则全部通过）
 * - 标记时间：按预设区间过滤（7天/30天/全部）
 * - Trakt 评分：item.traktRating 落在区间内
 * - 排序：按 listedAt（ISO 字符串天然有序）升降序
 */
private fun applyFilterAndSort(
    items: List<MediaUiItem>,
    searchQuery: String,
    filter: FilterState
): List<MediaUiItem> {
    val filtered = items.filter { item ->
        // 搜索
        val matchesSearch = searchQuery.isBlank() || matchesSearchQuery(item, searchQuery)
        if (!matchesSearch) return@filter false
        // 类型多选（按 `,` 或 `·` 分隔）
        val matchesGenres = filter.selectedGenres.isEmpty() ||
            (item.genres.isNotEmpty() && filter.selectedGenres.any { g ->
                item.genres.split(",", "·").any { it.trim() == g }
            })
        if (!matchesGenres) return@filter false
        // 年代多选（未选年代则全部通过；null year 视为不通过）
        val matchesDecade = filter.selectedDecadeKeys.isEmpty() ||
            (item.year != null && ((item.year / 10) * 10) in filter.selectedDecadeKeys)
        if (!matchesDecade) return@filter false
        // 标记时间区间
        val matchesMarkedTime = when (filter.markedTimePreset) {
            MarkedTimePreset.SEVEN_DAYS -> isWithinDays(item.listedAt, 7)
            MarkedTimePreset.THIRTY_DAYS -> isWithinDays(item.listedAt, 30)
            MarkedTimePreset.ALL -> true
        }
        if (!matchesMarkedTime) return@filter false
        // Trakt 评分区间
        val rating = item.traktRating.toFloat()
        rating >= filter.ratingRange.start && rating <= filter.ratingRange.endInclusive
    }
    // 标记时间排序（ISO 字符串天然有序）
    return if (filter.markedTimeOrder == SortOrder.DESC) {
        filtered.sortedByDescending { it.listedAt }
    } else {
        filtered.sortedBy { it.listedAt }
    }
}

/**
 * 看单搜索匹配：
 * - 直接匹配：displayTitle（中文名）/ title（原句）包含关键词（忽略大小写）
 * - 拼音匹配：查询为纯 ASCII 字母时，用中文名的拼音匹配
 *   - 全拼：如 "花束般的恋爱" → "huashubandelianai"，输入 "huashu" 命中
 *   - 首字母缩写：如 "hsbdla"，输入 "hsbd" 命中
 *   拼音匹配仅在查询不含汉字时启用，避免干扰中文直接匹配
 */
private fun matchesSearchQuery(item: MediaUiItem, query: String): Boolean {
    if (item.displayTitle.contains(query, ignoreCase = true) ||
        item.title.contains(query, ignoreCase = true)
    ) return true
    // 查询纯 ASCII（拼音输入）时启用拼音匹配
    return PinyinSearch.matches(item.displayTitle, query)
}

/** 判断 ISO 时间字符串是否在最近 N 天内（解析失败返回 false） */
private fun isWithinDays(isoString: String, days: Long): Boolean {
    if (isoString.isBlank()) return false
    return try {
        val instant = Instant.parse(isoString)
        val cutoff = Instant.now().minus(days, ChronoUnit.DAYS)
        instant.isAfter(cutoff)
    } catch (e: Exception) {
        false
    }
}
