package com.tracktosearch.ui.screen.watchlist

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.animation.EnterMode
import com.tracktosearch.ui.animation.cardEnter
import com.tracktosearch.ui.component.LocalActivePosterClickSetter
import com.tracktosearch.ui.component.LocalActivePosterClickToken
import com.tracktosearch.ui.component.LocalActivePosterTmdbId
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.PosterCard
import com.tracktosearch.ui.component.PosterColorExtractorProvider
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.rememberShimmerBrush
import com.tracktosearch.ui.screen.discover.CapsuleTabSelector
import com.tracktosearch.ui.screen.douban.DoubanFirstSyncGuideDialog
import com.tracktosearch.ui.screen.douban.DoubanSyncDialog
import com.tracktosearch.ui.screen.douban.DoubanSyncModePickerDialog
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.performHaptic
import dagger.hilt.android.EntryPointAccessors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class, ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun WatchlistScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onSearchClick: (keyword: String) -> Unit,
    onTraktSearch: (type: String, query: String) -> Unit,
    onDiscoverClick: () -> Unit = {},
    onNavigateToDoubanLogin: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: WatchlistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val hasActiveFilters by viewModel.hasActiveFilters.collectAsStateWithLifecycle()
    val availableGenres by viewModel.availableGenres.collectAsStateWithLifecycle()
    var showFilterSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val view = LocalView.current
    // 当前活跃海报 tmdbId（-1=都不启用），确保只有用户点击的卡片参与共享元素转场，避免跨页面重复海报 key 冲突
    var activePosterTmdbId by rememberSaveable { mutableIntStateOf(-1) }
    // 用外置浏览器打开 Trakt，共享外置浏览器登录态（内置 WebView 有独立 CookieJar 不共享）
    val openTraktExternal: () -> Unit = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://trakt.tv/watchlist")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    LaunchedEffect(Unit) {
        viewModel.loadMovies()
        viewModel.loadShows()
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
    // 海报加载失败横幅关闭状态(用户点关闭后隐藏,数据刷新后自动恢复)
    var posterErrorDismissed by remember { mutableStateOf(false) }
    // 当前可见 tab 的 TMDB 不可用状态（实时计算，避免多 tab 加载互相覆盖）
    val tmdbUnavailable = uiState.isTmdbUnavailable(selectedMode, selectedTab)
    LaunchedEffect(tmdbUnavailable) {
        // tmdbUnavailable 变 false 时重置关闭状态,下次再失败时重新显示横幅
        if (!tmdbUnavailable) posterErrorDismissed = false
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
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
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
    val hazeState = remember { HazeState() }
    val isDark = isAppDarkTheme()
    // 为4种 (mode, tab) 组合各自创建独立的 gridState，彻底隔离滚动位置，
    // 避免切 tab 时列表位置互相影响
    val movieGridState = rememberLazyGridState()        // mode=0, tab=0 想看电影
    val showGridState = rememberLazyGridState()         // mode=0, tab=1 想看电视剧
    val historyMovieGridState = rememberLazyGridState() // mode=1, tab=0 已看电影
    val historyShowGridState = rememberLazyGridState()  // mode=1, tab=1 已看电视剧
    val scrollToTopProvider = LocalScrollToTopProvider.current

    // 根据 selectedMode 和 selectedTab 选择对应的 gridState
    val currentGridState = when {
        selectedMode == 0 && selectedTab == 0 -> movieGridState
        selectedMode == 0 && selectedTab == 1 -> showGridState
        selectedMode == 1 && selectedTab == 0 -> historyMovieGridState
        else -> historyShowGridState
    }

    DisposableEffect(selectedMode) {
        scrollToTopProvider.register {
            gridCoroutineScope.launch {
                currentGridState.animateScrollToItem(0)
            }
        }
        onDispose {
            scrollToTopProvider.unregister()
        }
    }

    // 长按多选状态
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var isRemoving by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateMapOf<Int, Boolean>() }
    // 退出多选模式时清空选中
    LaunchedEffect(isMultiSelectMode) {
        if (!isMultiSelectMode) selectedItems.clear()
    }
    BackHandler(enabled = isMultiSelectMode) {
        isMultiSelectMode = false
        isRemoving = false
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
    val filteredHistoryMovies = remember(uiState.historyMovies, searchQuery, filterState) {
        applyFilterAndSort(uiState.historyMovies, searchQuery, filterState)
    }
    val filteredHistoryShows = remember(uiState.historyShows, searchQuery, filterState) {
        applyFilterAndSort(uiState.historyShows, searchQuery, filterState)
    }

    // 获取当前 tab 对应的 items（用于多选操作）
    val currentItems = when {
        selectedMode == 0 && selectedTab == 0 -> filteredMovies
        selectedMode == 0 && selectedTab == 1 -> filteredShows
        selectedMode == 1 && selectedTab == 0 -> filteredHistoryMovies
        else -> filteredHistoryShows
    }

    // 当前列表是否正在加载
    val isCurrentLoading = when {
        selectedMode == 0 && selectedTab == 0 -> uiState.isLoadingMovies
        selectedMode == 0 && selectedTab == 1 -> uiState.isLoadingShows
        selectedMode == 1 && selectedTab == 0 -> uiState.isLoadingHistoryMovies
        else -> uiState.isLoadingHistoryShows
    }

    // 下拉刷新：数据返回后触发 EMPHASIS 弹性入场动画
    LaunchedEffect(refreshPending) {
        if (!refreshPending) return@LaunchedEffect
        enterMode = EnterMode.EMPHASIS
        snapshotFlow {
            when {
                selectedMode == 0 && selectedTab == 0 -> uiState.isLoadingMovies
                selectedMode == 0 && selectedTab == 1 -> uiState.isLoadingShows
                selectedMode == 1 && selectedTab == 0 -> uiState.isLoadingHistoryMovies
                else -> uiState.isLoadingHistoryShows
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
            val remainingIds = currentItems.map { it.traktId }.toSet()
            val selectedIds = selectedItems.keys.toSet()
            if (selectedIds.none { it in remainingIds }) {
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
                    .padding(paddingValues)
                    .hazeSource(state = hazeState)
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
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp)
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
                                        buildAnnotatedString {
                                            append(if (selectedMode == 0)
                                                stringResource(R.string.watchlist_empty_hint_prefix)
                                            else
                                                stringResource(R.string.watched_empty_hint_prefix)
                                            )
                                            withLink(LinkAnnotation.Url("https://app.trakt.tv/") {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://app.trakt.tv/")))
                                            }) {
                                                append(stringResource(R.string.watchlist_empty_go_trakt))
                                            }
                                            append(stringResource(R.string.watchlist_empty_hint_middle))
                                            withLink(LinkAnnotation.Clickable(
                                                tag = "discover",
                                                linkInteractionListener = LinkInteractionListener { onDiscoverClick() }
                                            )) {
                                                append(stringResource(R.string.watchlist_empty_go_discover))
                                            }
                                            append(stringResource(R.string.watchlist_empty_hint_suffix))
                                            // 「，或从豆瓣导入标记」超链接
                                            append(stringResource(R.string.watchlist_empty_douban_import_prefix))
                                            withLink(LinkAnnotation.Clickable(
                                                tag = "douban_import",
                                                linkInteractionListener = LinkInteractionListener {
                                                    // 已登录豆瓣 → 弹模式选择弹窗；未登录 → 跳转豆瓣登录页
                                                    val hasDouban = viewModel.isDoubanLoggedIn()
                                                    if (hasDouban) {
                                                        showSyncModePicker = true
                                                    } else {
                                                        onNavigateToDoubanLogin()
                                                    }
                                                }
                                            )) {
                                                append(stringResource(R.string.watchlist_empty_douban_import_link))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
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
                        top = 170.dp + statusBarHeight,
                        bottom = if (isMultiSelectMode) 80.dp else 80.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(pullToRefreshConnection)
                        .graphicsLayer { translationY = animatedOverscrollDp.toPx() }
                    ) {
                        // 根据 selectedMode 和 selectedTab 渲染对应列表
                        val items = currentItems
                        items(items.size, key = { items[it].traktId }, contentType = { "media_card" }) { index ->
                            val item = items[index]
                            val isSelected = selectedItems[item.traktId] == true
                            val isResolving = isRemoving && isSelected
                            Box(modifier = Modifier.cardEnter(item.traktId.toLong(), index, enterMode, animatedIds)) {
                                WatchlistPosterCard(
                                    item = item,
                                    isInWatchlist = selectedMode == 0,
                                    isWatched = selectedMode == 1,
                                    isSelected = isSelected,
                                    isResolving = isResolving,
                                    isMultiSelectMode = isMultiSelectMode,
                                    onClick = {
                                        if (isMultiSelectMode) {
                                            if (isSelected) selectedItems.remove(item.traktId)
                                            else selectedItems[item.traktId] = true
                                            if (selectedItems.isEmpty()) isMultiSelectMode = false
                                        } else {
                                            val inWatchlist = selectedMode == 0
                                            val isWatched = selectedMode == 1
                                            if (selectedTab == 0) {
                                                onMovieClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating, inWatchlist, isWatched)
                                            } else {
                                                onShowClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating, inWatchlist, isWatched)
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!isMultiSelectMode) {
                                            isMultiSelectMode = true
                                        }
                                        selectedItems[item.traktId] = true
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
                        .hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.thin()
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
                            // 毛玻璃吸顶标题栏（继承外层 Box 的 hazeEffect，不重复叠加避免变白）
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        gridCoroutineScope.launch {
                                            currentGridState.animateScrollToItem(0)
                                        }
                                    }
                            ) {
                                Column {
                                    Spacer(modifier = Modifier.statusBarsPadding())
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Bottom
                                    ) {
                                        Text(
                                            text = stringResource(R.string.tab_me),
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = (-0.5).sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        // 想看/已看胶囊切换（标题栏右侧，底部对齐标题底缘）
                                        CapsuleTabSelector(
                                            tabs = listOf(
                                                stringResource(R.string.watchlist_mode_watchlist),
                                                stringResource(R.string.watchlist_mode_watched)
                                            ),
                                            selectedIndex = selectedMode,
                                            onTabSelected = { selectedMode = it },
                                            sizeMultiplier = 1.25f
                                        )
                                    }
                                }
                            }
                            // 搜索栏 + 筛选按钮
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = false, onClick = {})
                                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 拟态玻璃搜索栏
                                val searchHintColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                val searchPlaceholder = if (searchQuery.isBlank()) {
                                    when (selectedMode) {
                                        0 -> stringResource(R.string.watchlist_search_watchlist)
                                        else -> stringResource(R.string.watchlist_search_history)
                                    }
                                } else stringResource(R.string.search_placeholder_watchlist)
                                NeumorphicFrostedSurface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp),
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
                                            .padding(horizontal = 12.dp),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = {
                                                focusManager.clearFocus()
                                                if (searchQuery.isNotBlank() && selectedMode == 0) {
                                                    val noResults = if (selectedTab == 0) filteredMovies.isEmpty() else filteredShows.isEmpty()
                                                    if (noResults) {
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
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                                // 拟态玻璃筛选按钮
                                NeumorphicIconButton(
                                    onClick = { showFilterSheet = true },
                                    isDark = isDark
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Tune,
                                        contentDescription = stringResource(R.string.filter_title),
                                        tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
    
                            // 分类 Tab（下划线样式）
                            val movieCount = if (selectedMode == 1) filteredHistoryMovies.size else filteredMovies.size
                            val showCount = if (selectedMode == 1) filteredHistoryShows.size else filteredShows.size
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)
                            ) {
                                listOf(
                                    stringResource(R.string.watchlist_tab_movies) to movieCount,
                                    stringResource(R.string.watchlist_tab_shows) to showCount
                                ).forEachIndexed { index, (label, count) ->
                                    val selected = selectedTab == index
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            view.performHaptic(HapticType.CLICK)
                                            selectedTab = index
                                        }
                                    ) {
                                        Text(
                                            text = "$label($count)",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .width(24.dp)
                                                .height(3.dp)
                                                .clip(RoundedCornerShape(1.5.dp))
                                                .background(
                                                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                                                )
                                        )
                                    }
                                }
                            }
    
                            // 豆瓣同步进度横幅（同步进行中或刚完成 5 秒内显示）
                            val syncProgress = uiState.doubanSyncProgress
                            if (syncProgress != null) {
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
                                        .clickable { showSyncDialog = true },
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
                                                else Color.White,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .then(
                                                    if (syncProgress.isRunning) Modifier.graphicsLayer { rotationZ = -spinRotation }
                                                    else Modifier
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (syncProgress.cookieExpired) {
                                                    stringResource(R.string.douban_sync_cookie_expired_banner)
                                                } else if (syncProgress.isComplete) {
                                                    stringResource(R.string.douban_sync_complete_banner, syncProgress.successCount)
                                                } else if (syncProgress.total > 0) {
                                                    "${syncProgress.phase} (${syncProgress.current}/${syncProgress.total})"
                                                } else {
                                                    syncProgress.phase
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (syncProgress.cookieExpired) MaterialTheme.colorScheme.onErrorContainer
                                                    else Color.White
                                            )
                                            if (syncProgress.isRunning && syncProgress.total > 0) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                LinearProgressIndicator(
                                                    progress = { (syncProgress.current.toFloat() / syncProgress.total).coerceIn(0f, 1f) },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
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
                                                else Color.White,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .then(
                                                    if (checkProgress.isRunning) Modifier.graphicsLayer { rotationZ = -checkSpinRotation }
                                                    else Modifier
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (checkProgress.cookieExpired) {
                                                    stringResource(R.string.douban_sync_cookie_expired_banner)
                                                } else if (checkProgress.isComplete) {
                                                    stringResource(R.string.consistency_check_complete_banner)
                                                } else if (checkProgress.total > 0) {
                                                    "${checkProgress.phase} (${checkProgress.current}/${checkProgress.total})"
                                                } else {
                                                    checkProgress.phase
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (checkProgress.cookieExpired) MaterialTheme.colorScheme.onErrorContainer
                                                    else Color.White
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
                                                    "${removalProgress.phase} (${removalProgress.current}/${removalProgress.total})"
                                                } else {
                                                    removalProgress.phase
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
                            if (tmdbUnavailable && !posterErrorDismissed) {
                                NeumorphicFrostedSurface(
                                    modifier = Modifier.fillMaxWidth(),
                                    isDark = isDark,
                                    shape = RoundedCornerShape(12.dp),
                                    backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.20f),
                                    elevation = 2.dp,
                                    blurRadius = 12.dp,
                                    hazeState = hazeState,
                                    hazeStyle = HazeMaterials.thin()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Rounded.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.watchlist_poster_error),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { posterErrorDismissed = true },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.Close,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
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
                                    .hazeEffect(state = hazeState, style = HazeMaterials.thin())
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
                                                val ids = selectedItems.keys.toList()
                                                val type = if (selectedTab == 0) MediaType.MOVIE else MediaType.SHOW
                                                tabScope.launch {
                                                    if (selectedMode == 0) {
                                                        viewModel.batchRemoveFromWatchlist(ids, type)
                                                    } else {
                                                        viewModel.batchRemoveFromHistory(ids, type)
                                                    }
                                                    // 豆瓣批量移除在 Application scope 后台运行，启动前台服务显示通知栏进度
                                                    if (viewModel.isBatchRemovalRunning()) {
                                                        com.tracktosearch.service.DoubanBatchRemovalService.start(context)
                                                    }
                                                    // 等待批量操作完成后才关闭多选栏，避免提前关闭导致用户以为已处理但实际仍在进行
                                                    isMultiSelectMode = false
                                                    isRemoving = false
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
                    if (selectedTab == 0) uiState.isLoadingMovies && !uiState.moviesLoaded && uiState.movies.isEmpty()
                    else uiState.isLoadingShows && !uiState.showsLoaded && uiState.shows.isEmpty()
                } else {
                    if (selectedTab == 0) uiState.isLoadingHistoryMovies && !uiState.historyMoviesLoaded && uiState.historyMovies.isEmpty()
                    else uiState.isLoadingHistoryShows && !uiState.historyShowsLoaded && uiState.historyShows.isEmpty()
                }
                if (isLoading) {
                    WatchlistSkeletonGrid(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 172.dp + statusBarHeight)
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
                        }
                    },
                    onBackground = {
                        // 「转后台」:仅隐藏弹窗,同步在 Application scope 继续运行,横幅会继续显示进度
                        showSyncDialog = false
                    },
                    onRelogin = {
                        showSyncDialog = false
                        onNavigateToDoubanLogin()
                    },
                    onTraktLogin = {
                        showSyncDialog = false
                        onNavigateToLogin()
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
            if (showFirstSyncGuide) {
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
                                        BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)),
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
        Text(
            text = item.displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
        val matchesSearch = searchQuery.isBlank() ||
            item.displayTitle.contains(searchQuery, ignoreCase = true) ||
            item.title.contains(searchQuery, ignoreCase = true)
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
