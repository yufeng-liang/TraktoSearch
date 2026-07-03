package com.tracktosearch.ui.screen.watchlist

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.rememberShimmerBrush
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class, ExperimentalFoundationApi::class)
@Composable
fun WatchlistScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onSearchClick: (keyword: String) -> Unit,
    onStatisticsClick: () -> Unit,
    onTraktSearch: (type: String, query: String) -> Unit,
    onDiscoverClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: WatchlistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
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
    val tabScope = rememberCoroutineScope()

    // 保存每个 (mode, tab) 组合的滚动位置
    val savedScrollPositions = remember { mutableMapOf<String, Pair<Int, Int>>() }

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
    val singleModeGridState = rememberLazyGridState()
    val historyModeGridState = rememberLazyGridState()
    val scrollToTopProvider = LocalScrollToTopProvider.current
    val gridCoroutineScope = rememberCoroutineScope()

    // 根据 selectedMode 选择对应的 gridState
    val currentGridState = if (selectedMode == 0) singleModeGridState else historyModeGridState

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

    // 监听 tab 切换，保存/恢复滚动位置 + 退出多选
    var prevTabKey by remember { mutableStateOf("${selectedMode}_${selectedTab}") }
    LaunchedEffect(selectedMode, selectedTab) {
        val currentKey = "${selectedMode}_${selectedTab}"
        // 保存旧 tab 的位置
        savedScrollPositions[prevTabKey] = Pair(
            currentGridState.firstVisibleItemIndex,
            currentGridState.firstVisibleItemScrollOffset
        )
        // 恢复新 tab 的位置
        savedScrollPositions[currentKey]?.let { (index, offset) ->
            currentGridState.scrollToItem(index, offset)
        }
        prevTabKey = currentKey
        // 退出多选模式
        isMultiSelectMode = false
        isRemoving = false
    }

    // 根据搜索关键词过滤当前 Tab 的列表
    val filteredMovies = remember(uiState.movies, searchQuery) {
        if (searchQuery.isBlank()) uiState.movies
        else uiState.movies.filter {
            it.displayTitle.contains(searchQuery, ignoreCase = true) ||
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }
    val filteredShows = remember(uiState.shows, searchQuery) {
        if (searchQuery.isBlank()) uiState.shows
        else uiState.shows.filter {
            it.displayTitle.contains(searchQuery, ignoreCase = true) ||
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }
    val filteredHistoryMovies = remember(uiState.historyMovies, searchQuery) {
        if (searchQuery.isBlank()) uiState.historyMovies
        else uiState.historyMovies.filter {
            it.displayTitle.contains(searchQuery, ignoreCase = true) ||
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }
    val filteredHistoryShows = remember(uiState.historyShows, searchQuery) {
        if (searchQuery.isBlank()) uiState.historyShows
        else uiState.historyShows.filter {
            it.displayTitle.contains(searchQuery, ignoreCase = true) ||
            it.title.contains(searchQuery, ignoreCase = true)
        }
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

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                        imageVector = Icons.Default.Refresh,
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
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
                                withLink(LinkAnnotation.Url("discover") {
                                    onDiscoverClick()
                                }) {
                                    append(stringResource(R.string.watchlist_empty_go_discover))
                                }
                                append(stringResource(R.string.watchlist_empty_hint_suffix))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
            LazyVerticalGrid(
                state = currentGridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 110.dp + statusBarHeight,
                    bottom = if (isMultiSelectMode) 80.dp else 80.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(pullToRefreshConnection)
                    .graphicsLayer { translationY = animatedOverscrollDp.toPx() }
                    .hazeSource(state = hazeState)
                ) {
                    // 根据 selectedMode 和 selectedTab 渲染对应列表
                    val items = currentItems
                    items(items.size, key = { items[it].traktId }, contentType = { "media_card" }) { index ->
                        val item = items[index]
                        val isSelected = selectedItems[item.traktId] == true
                        Box {
                            MovieCard(
                                title = item.displayTitle,
                                year = item.year,
                                genres = item.genres,
                                posterUrl = item.posterUrl,
                                tmdbId = item.tmdbId,
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
                            if (isMultiSelectMode) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clip(RoundedCornerShape(8.dp))
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
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
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
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.50f))
            ) {
                // 搜索框 + Tab 栏（非多选模式时显示）
                AnimatedVisibility(
                    visible = !isMultiSelectMode,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        // 状态栏 Spacer
                        Spacer(
                            modifier = Modifier
                                .statusBarsPadding()
                                .fillMaxWidth()
                                .clickable {
                                    gridCoroutineScope.launch {
                                        currentGridState.animateScrollToItem(0)
                                    }
                                }
                        )
                        // 搜索栏 + 胶囊切换条
                        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = false, onClick = {})
                                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 测量切换条文字宽度，动态计算搜索框宽度
                            val watchlistLabel = stringResource(R.string.watchlist_mode_watchlist)
                            val watchedLabel = stringResource(R.string.watchlist_mode_watched)
                            val isWatchlist = selectedMode == 0
                            val tabPadding = 20.dp
                            val textMeasurer = rememberTextMeasurer()
                            val watchlistTextWidthPx = remember(watchlistLabel) {
                                textMeasurer.measure(
                                    text = watchlistLabel,
                                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                ).size.width
                            }
                            val watchedTextWidthPx = remember(watchedLabel) {
                                textMeasurer.measure(
                                    text = watchedLabel,
                                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                ).size.width
                            }
                            val capsuleDensity = LocalDensity.current
                            val watchlistTabWidthDp = with(capsuleDensity) { watchlistTextWidthPx.toDp() + tabPadding * 2 }
                            val watchedTabWidthDp = with(capsuleDensity) { watchedTextWidthPx.toDp() + tabPadding * 2 }
                            val capsuleWidth = watchlistTabWidthDp + watchedTabWidthDp
                            val statsButtonSize = 48.dp
                            val rowPadding = 12.dp * 2
                            val gaps = 8.dp * 2
                            val searchBoxWidth = (screenWidth - capsuleWidth - statsButtonSize - rowPadding - gaps).coerceAtMost(screenWidth / 2)

                            // 搜索框
                            val searchInteractionSource = remember { MutableInteractionSource() }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .width(searchBoxWidth)
                                    .height(45.dp)
                                    .focusRequester(focusRequester),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
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
                                interactionSource = searchInteractionSource,
                                decorationBox = { innerTextField ->
                                    OutlinedTextFieldDefaults.DecorationBox(
                                        value = searchQuery,
                                        innerTextField = innerTextField,
                                        enabled = true,
                                        singleLine = true,
                                        visualTransformation = VisualTransformation.None,
                                        interactionSource = searchInteractionSource,
                                        placeholder = {
                                            Text(
                                                if (searchQuery.isBlank()) {
                                                    when (selectedMode) {
                                                        0 -> stringResource(R.string.watchlist_search_watchlist)
                                                        else -> stringResource(R.string.watchlist_search_history)
                                                    }
                                                } else stringResource(R.string.search_placeholder_watchlist),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        },
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(32.dp)) {
                                                    Icon(
                                                        Icons.Filled.Close,
                                                        contentDescription = stringResource(R.string.content_desc_clear),
                                                        modifier = Modifier.size(19.dp)
                                                    )
                                                }
                                            } else {
                                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.watchlist_search), modifier = Modifier.size(19.dp))
                                            }
                                        },
                                        contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 0.dp, bottom = 0.dp),
                                        container = {
                                            OutlinedTextFieldDefaults.Container(
                                                enabled = true,
                                                isError = false,
                                                interactionSource = searchInteractionSource,
                                                colors = OutlinedTextFieldDefaults.colors(),
                                                shape = RoundedCornerShape(22.dp)
                                            )
                                        }
                                    )
                                }
                            )
                            // 统计按钮（放大图标）
                            IconButton(onClick = onStatisticsClick) {
                                Icon(
                                    Icons.Default.BarChart,
                                    contentDescription = stringResource(R.string.statistics_title),
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            // 胶囊切换条：想看 / 已看
                            val tabHeight = 44.dp
                            val indicatorOffset by animateDpAsState(
                                targetValue = if (isWatchlist) 0.dp else watchlistTabWidthDp,
                                animationSpec = tween(200),
                                label = "modeIndicator"
                            )
                            val indicatorWidth by animateDpAsState(
                                targetValue = if (isWatchlist) watchlistTabWidthDp else watchedTabWidthDp,
                                animationSpec = tween(200),
                                label = "modeIndicatorWidth"
                            )
                            Box(
                                modifier = Modifier
                                    .height(tabHeight)
                                    .width(capsuleWidth)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = indicatorOffset)
                                        .width(indicatorWidth)
                                        .fillMaxHeight()
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Box(
                                        modifier = Modifier
                                            .width(watchlistTabWidthDp)
                                            .fillMaxHeight()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                view.performHaptic(HapticType.CLICK)
                                                selectedMode = 0
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = watchlistLabel,
                                            style = TextStyle(
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isWatchlist) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .width(watchedTabWidthDp)
                                            .fillMaxHeight()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                view.performHaptic(HapticType.CLICK)
                                                selectedMode = 1
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = watchedLabel,
                                            style = TextStyle(
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (!isWatchlist) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // 分类 Tab
                        val movieCount = if (selectedMode == 1) filteredHistoryMovies.size else filteredMovies.size
                        val showCount = if (selectedMode == 1) filteredHistoryShows.size else filteredShows.size
                        PrimaryTabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = Color.Transparent
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { view.performHaptic(HapticType.CLICK); selectedTab = 0 },
                                text = { Text("${stringResource(R.string.watchlist_tab_movies)}($movieCount)") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { view.performHaptic(HapticType.CLICK); selectedTab = 1 },
                                text = { Text("${stringResource(R.string.watchlist_tab_shows)}($showCount)") }
                            )
                        }

                        // TMDB 不可用提示
                        if (uiState.tmdbUnavailable) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.watchlist_poster_error),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
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
                                    Icons.AutoMirrored.Filled.ArrowBack,
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
                                        if (selectedMode == 0) {
                                            viewModel.batchRemoveFromWatchlist(
                                                ids,
                                                if (selectedTab == 0) MediaType.MOVIE else MediaType.SHOW
                                            )
                                        } else {
                                            viewModel.batchRemoveFromHistory(
                                                ids,
                                                if (selectedTab == 0) MediaType.MOVIE else MediaType.SHOW
                                            )
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

            // 骨架屏：首次加载时显示
            val isLoading = if (selectedMode == 0) {
                if (selectedTab == 0) uiState.isLoadingMovies && !uiState.moviesLoaded
                else uiState.isLoadingShows && !uiState.showsLoaded
            } else {
                if (selectedTab == 0) uiState.isLoadingHistoryMovies && !uiState.historyMoviesLoaded
                else uiState.isLoadingHistoryShows && !uiState.historyShowsLoaded
            }
            if (isLoading) {
                WatchlistSkeletonGrid(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 112.dp + statusBarHeight)
                )
            }
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
