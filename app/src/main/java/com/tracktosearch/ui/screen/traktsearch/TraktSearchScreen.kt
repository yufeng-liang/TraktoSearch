@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.screen.traktsearch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.util.PersonAvatarColorStore
import com.tracktosearch.data.ai.AiRecommendation
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.LocalActivePosterClickSetter
import com.tracktosearch.ui.component.LocalActivePosterClickToken
import com.tracktosearch.ui.component.LocalActivePosterTmdbId
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.backdropSource
import com.tracktosearch.ui.component.rememberCachedPosterAmbientColor
import com.tracktosearch.ui.component.appVisualEffect
import com.tracktosearch.ui.component.MovieCardSkeleton
import com.tracktosearch.ui.component.PosterColorExtractorProvider
import com.tracktosearch.ui.component.ResourceItemCard
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.copyResourceLink
import com.tracktosearch.ui.util.openResourceLink
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.screen.ai.AiSpriteAnchor
import com.tracktosearch.ui.screen.ai.AiSpriteCenter
import com.tracktosearch.ui.screen.ai.AiSpriteInterruptReason
import com.tracktosearch.ui.screen.ai.AiSpriteInterruptRequest
import com.tracktosearch.ui.screen.ai.AiSpriteMotion
import com.tracktosearch.ui.screen.ai.AiSpriteOverlayPolicy
import com.tracktosearch.ui.screen.ai.AiSpriteOverlayTrigger
import com.tracktosearch.ui.screen.ai.AiSpriteViewModel
import com.tracktosearch.ui.screen.ai.AiSceneEvent
import com.tracktosearch.ui.screen.ai.automaticSpriteArt
import com.tracktosearch.ui.screen.ai.sceneArtFor
import com.tracktosearch.ui.screen.ai.sceneEventForSearch
import com.tracktosearch.ui.screen.ai.searchAnchorFor
import dagger.hilt.android.EntryPointAccessors
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSampling
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.launch

/** 计算网盘tab筛选后的结果数（用于Tab标签显示） */
private fun getFilteredDiskCount(diskState: DiskSearchState): Int {
    return diskState.resources
        .filter { it.source in diskState.enabledSources }
        .filter { it.diskType in diskState.enabledDiskTypes }
        .size
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraktSearchScreen(
    initialQuery: String,
    type: MediaType,
    onBack: () -> Unit,
    onItemClick: (type: MediaType, traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onPersonClick: (tmdbId: Int, name: String, profileUrl: String?, avatarColor: Color?) -> Unit = { _, _, _, _ -> },
    onNavigateToLogin: () -> Unit = {},
    onRecommendationClick: ((AiRecommendation) -> Unit)? = null,
    inlineMode: Boolean = false,
    viewModel: TraktSearchViewModel = hiltViewModel(),
    spriteViewModel: AiSpriteViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val watchlistWatchedIds by viewModel.watchlistWatchedIds.collectAsStateWithLifecycle()
    val spriteState by spriteViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    // 当前活跃海报 tmdbId（-1=都不启用），确保只有用户点击的卡片参与共享元素转场
    var activePosterTmdbId by rememberSaveable { mutableIntStateOf(-1) }
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    val hazeSurface = MaterialTheme.colorScheme.surface
    val isDark = isAppDarkTheme()

    var searchQuery by rememberSaveable { mutableStateOf(initialQuery) }
    var showAiSpriteCenter by rememberSaveable { mutableStateOf(false) }
    var showAiSpriteMotion by rememberSaveable { mutableStateOf(false) }
    var overlayEntryHandled by rememberSaveable { mutableStateOf(initialQuery.isNotBlank()) }
    var isSearchFocused by remember { mutableStateOf(false) }
    var wasSearchLoading by remember { mutableStateOf(false) }
    var activeSpriteAnchor by remember { mutableStateOf(AiSpriteAnchor.SearchBox) }
    var activeSceneEvent by remember { mutableStateOf<AiSceneEvent?>(null) }
    var spriteInterruptRevision by remember { mutableStateOf(0L) }
    var spriteInterruptReason by remember { mutableStateOf(AiSpriteInterruptReason.BLOCKED) }
    var searchBoxBounds by remember { mutableStateOf<Rect?>(null) }
    var firstResultBounds by remember { mutableStateOf<Rect?>(null) }
    var firstResultAnchorKey by remember { mutableStateOf<String?>(null) }
    var lastInteractionAt by remember { mutableStateOf(System.currentTimeMillis()) }
    val overlayPreferences = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences("ai_sprite_overlay_quota_v1", android.content.Context.MODE_PRIVATE)
    }
    val overlayPolicy = remember(overlayPreferences) {
        AiSpriteOverlayPolicy(
            readDailyCount = { dayKey ->
                if (overlayPreferences.getString("day_key", null) == dayKey) {
                    overlayPreferences.getInt("daily_count", 0)
                } else 0
            },
            writeDailyCount = { dayKey, count ->
                overlayPreferences.edit()
                    .putString("day_key", dayKey)
                    .putInt("daily_count", count)
                    .apply()
            }
        )
    }
    var overlayDayKey by remember {
        mutableStateOf(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()))
    }
    val resultAnchorKey = "${uiState.selectedTab.name}:${uiState.query}"
    val currentFirstResultBounds = firstResultBounds.takeIf { firstResultAnchorKey == resultAnchorKey }

    fun interruptAiSprite(reason: AiSpriteInterruptReason) {
        lastInteractionAt = System.currentTimeMillis()
        spriteInterruptRevision += 1L
        spriteInterruptReason = reason
        showAiSpriteMotion = false
        activeSceneEvent = null
    }

    LaunchedEffect(Unit) {
        spriteViewModel.ensureLoaded()
        while (true) {
            val now = java.util.Calendar.getInstance()
            val nextDay = (now.clone() as java.util.Calendar).apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            kotlinx.coroutines.delay((nextDay.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L))
            overlayDayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        }
    }

    LaunchedEffect(
        spriteState.activatedCharacterId,
        uiState.currentTabState.isLoading,
        uiState.currentTabState.results.size,
        uiState.selectedTab,
        searchQuery,
        overlayDayKey,
        showAiSpriteCenter,
        showAiSpriteMotion,
        lastInteractionAt,
        firstResultBounds,
        firstResultAnchorKey,
        searchBoxBounds
    ) {
        val isLoading = if (uiState.selectedTab == MediaType.DISK) {
            uiState.diskState.isLoading
        } else {
            uiState.currentTabState.isLoading
        }
        val hasResults = if (uiState.selectedTab == MediaType.DISK) {
            uiState.diskState.resources.isNotEmpty()
        } else {
            uiState.currentTabState.results.isNotEmpty()
        }
        val blocked = showAiSpriteCenter || isLoading || isSearchFocused
        val trigger = com.tracktosearch.ui.screen.ai.nextAiSpriteOverlayTrigger(
            entryHandled = overlayEntryHandled,
            wasSearchLoading = wasSearchLoading,
            isSearchLoading = isLoading,
            hasResults = hasResults,
            isSearchFocused = isSearchFocused,
            searchQuery = searchQuery,
            activated = spriteState.activatedCharacterId?.let { automaticSpriteArt(it) != null } == true,
            nowMs = System.currentTimeMillis(),
            idleForMs = System.currentTimeMillis() - lastInteractionAt,
            hasBlockingOverlay = blocked
        )
            if (trigger != null && !blocked && !showAiSpriteMotion) {
            if (trigger == AiSpriteOverlayTrigger.FIRST_ENTRY) overlayEntryHandled = true
            activeSpriteAnchor = searchAnchorFor(trigger)
            activeSceneEvent = sceneEventForSearch(trigger)
            if ((trigger != AiSpriteOverlayTrigger.SEARCH_COMPLETED || currentFirstResultBounds != null) &&
                overlayPolicy.tryConsume(true, trigger, overlayDayKey)
            ) {
                showAiSpriteMotion = true
            }
        }
        wasSearchLoading = isLoading
    }

    LaunchedEffect(lastInteractionAt, spriteState.activatedCharacterId, uiState.selectedTab, searchQuery, showAiSpriteMotion) {
        val isLoading = if (uiState.selectedTab == MediaType.DISK) {
            uiState.diskState.isLoading
        } else {
            uiState.currentTabState.isLoading
        }
        if (spriteState.activatedCharacterId?.let { automaticSpriteArt(it) != null } == true &&
            !isLoading && searchQuery.isBlank() && !isSearchFocused && !showAiSpriteMotion && !showAiSpriteCenter
        ) {
            kotlinx.coroutines.delay(8_000L)
            if (System.currentTimeMillis() - lastInteractionAt >= 8_000L &&
                overlayPolicy.tryConsume(true, AiSpriteOverlayTrigger.IDLE, overlayDayKey)
            ) {
                activeSpriteAnchor = AiSpriteAnchor.SearchBox
                activeSceneEvent = sceneEventForSearch(AiSpriteOverlayTrigger.IDLE)
                showAiSpriteMotion = true
            }
        }
    }
    val searchContentCount = when (uiState.selectedTab) {
        MediaType.MOVIE -> uiState.movieState.results.size
        MediaType.SHOW -> uiState.showState.results.size
        MediaType.PERSON -> uiState.personState.results.size
        MediaType.DISK -> getFilteredDiskCount(uiState.diskState)
    }
    val traktSearchGlassScene = glassSceneForContent(
        contentCount = searchContentCount,
        readabilityDemand = when {
            searchQuery.isNotBlank() && uiState.selectedTab == MediaType.PERSON -> 0.88f
            searchQuery.isNotBlank() -> 0.76f
            searchContentCount > 0 -> 0.58f
            else -> 0.34f
        },
        ambientColor = rememberCachedPosterAmbientColor(
            posterUrls = uiState.currentTabState.results.mapNotNull { it.posterUrl },
            fallback = MaterialTheme.colorScheme.background
        ),
        contentCapacity = 36,
        loadingCount =
            (if (uiState.currentTabState.isLoading) 1 else 0) +
                (if (uiState.currentTabState.isLoadingMore) 1 else 0) +
                (if (uiState.selectedTab == MediaType.DISK && uiState.diskState.isLoading) 1 else 0),
        loadingItemWeight = 4
    )

    LaunchedEffect(initialQuery, type) {
        viewModel.initSearch(initialQuery, type)
    }

    // rememberSaveable + Saver：进入详情/人物页返回后恢复原滚动位置，不再回到顶部
    val movieGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val showGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val personGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val diskListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val scrollToTopProvider = LocalScrollToTopProvider.current
    val traktCoroutineScope = rememberCoroutineScope()

    // 回顶按钮显隐状态
    var showScrollToTop by remember { mutableStateOf(false) }
    var prevScrollIndex by remember { mutableStateOf(0) }
    var prevScrollOffset by remember { mutableStateOf(0) }
    val currentGridState = when (uiState.selectedTab) {
        MediaType.MOVIE -> movieGridState
        MediaType.SHOW -> showGridState
        MediaType.PERSON -> personGridState
        MediaType.DISK -> movieGridState
    }
    LaunchedEffect(currentGridState) {
        snapshotFlow { currentGridState.firstVisibleItemIndex to currentGridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (index != prevScrollIndex || offset != prevScrollOffset) {
                    interruptAiSprite(AiSpriteInterruptReason.SCROLL)
                }
                val scrollingUp = index < prevScrollIndex || (index == prevScrollIndex && offset < prevScrollOffset)
                if (scrollingUp && index > 5) showScrollToTop = true
                else if (index <= 5) showScrollToTop = false
                prevScrollIndex = index
                prevScrollOffset = offset
            }
    }

    LaunchedEffect(diskListState) {
        var previousValue = diskListState.firstVisibleItemIndex to diskListState.firstVisibleItemScrollOffset
        snapshotFlow { diskListState.firstVisibleItemIndex to diskListState.firstVisibleItemScrollOffset }
            .collect { value ->
                if (value != previousValue) {
                    previousValue = value
                    interruptAiSprite(AiSpriteInterruptReason.SCROLL)
                }
            }
    }

    DisposableEffect(Unit) {
        scrollToTopProvider.register {
            val currentGridState = when (uiState.selectedTab) {
                MediaType.MOVIE -> movieGridState
                MediaType.SHOW -> showGridState
                MediaType.PERSON -> personGridState
                MediaType.DISK -> null
            }
            traktCoroutineScope.launch {
                if (currentGridState != null) {
                    currentGridState.animateScrollToItem(0)
                } else {
                    diskListState.animateScrollToItem(0)
                }
            }
        }
        onDispose {
            scrollToTopProvider.unregister()
        }
    }

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 点击 token,确保只有被点击的卡片参与转场
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
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 当前 tab 的状态
        val currentTabState = uiState.currentTabState
        val isDiskTab = uiState.selectedTab == MediaType.DISK

        // 根据当前 tab 选择 gridState
        val currentGridState = when (uiState.selectedTab) {
            MediaType.MOVIE -> movieGridState
            MediaType.SHOW -> showGridState
            MediaType.PERSON -> personGridState
            MediaType.DISK -> movieGridState // 网盘用 LazyColumn 的 listState
        }
        val hasContentUnderTopBar by remember(isDiskTab, currentGridState) {
            derivedStateOf {
                if (isDiskTab) {
                    hasListScrolled(
                        firstVisibleItemIndex = diskListState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffsetPx = diskListState.firstVisibleItemScrollOffset
                    )
                } else {
                    hasListScrolled(
                        firstVisibleItemIndex = currentGridState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffsetPx = currentGridState.firstVisibleItemScrollOffset
                    )
                }
            }
        }

        // 主内容区域 - hazeSource 应用到可滚动组件
        when {
                isDiskTab -> {
                    DiskSearchContent(
                        diskState = uiState.diskState,
                        onToggleSource = {
                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                            viewModel.toggleDiskSource(it)
                        },
                        onToggleDiskType = {
                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                            viewModel.toggleDiskType(it)
                        },
                        onItemClick = {
                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                            openResourceLink(context, it)
                        },
                        listState = diskListState,
                        hazeState = hazeState,
                        statusBarHeight = statusBarHeight,
                        scene = traktSearchGlassScene
                    )
                }
                currentTabState.isLoading -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 124.dp + statusBarHeight, bottom = 80.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(state = hazeState)
                            .backdropSource()
                    ) {
                        items(9) {
                            if (uiState.selectedTab == MediaType.PERSON) PersonCardSkeleton()
                            else MovieCardSkeleton()
                        }
                    }
                    // 重试状态提示
                    if (currentTabState.isRetrying) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(state = hazeState)
                                .backdropSource(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.search_retrying, currentTabState.retryCount),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                currentTabState.error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(state = hazeState)
                            .backdropSource(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyView(message = currentTabState.error)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.search(uiState.query, uiState.selectedTab) }) {
                                Text(stringResource(R.string.watchlist_retry))
                            }
                        }
                    }
                }
                currentTabState.results.isEmpty() && currentTabState.hasSearched -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(state = hazeState)
                            .backdropSource(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (uiState.selectedTab) {
                                MediaType.MOVIE -> stringResource(R.string.trakt_search_no_movies)
                                MediaType.SHOW -> stringResource(R.string.trakt_search_no_shows)
                                MediaType.PERSON -> stringResource(R.string.trakt_search_no_persons)
                                MediaType.DISK -> stringResource(R.string.trakt_search_no_movies)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
                else -> {
                    // 自动触发加载更多
                    LaunchedEffect(currentGridState, currentTabState.hasMore, currentTabState.isLoadingMore) {
                        snapshotFlow { currentGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                            .collect { lastVisibleIndex ->
                                if (lastVisibleIndex != null &&
                                    lastVisibleIndex >= currentTabState.results.size - 6 &&
                                    currentTabState.hasMore && !currentTabState.isLoadingMore
                                ) {
                                    viewModel.loadMore()
                                }
                            }
                    }

                    if (uiState.selectedTab == MediaType.PERSON) {
                        // 人物搜索结果
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = currentGridState,
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 124.dp + statusBarHeight, bottom = 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(state = hazeState)
                                .backdropSource()
                        ) {
                            items(currentTabState.results.size, key = { "${currentTabState.results[it].traktId}_$it" }, contentType = { "person" }) { index ->
                                val item = currentTabState.results[index]
                                PersonSearchCard(
                                    name = item.displayTitle,
                                    profileUrl = item.posterUrl,
                                    knownForDepartment = item.knownForDepartment,
                                    personId = item.tmdbId,
                                    modifier = if (index == 0) {
                                        Modifier.onGloballyPositioned {
                                            firstResultBounds = it.boundsInRoot()
                                            firstResultAnchorKey = resultAnchorKey
                                        }
                                    } else Modifier,
                                    onClick = {
                                        interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                                        if (item.tmdbId > 0) {
                                            onPersonClick(item.tmdbId, item.title, item.posterUrl ?: "", item.avatarColor)
                                        }
                                    }
                                )
                            }
                            if (currentTabState.isLoadingMore) {
                                item(span = { GridItemSpan(3) }) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    } else {
                        // 电影/电视剧搜索结果
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = currentGridState,
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 124.dp + statusBarHeight, bottom = 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(state = hazeState)
                                .backdropSource()
                        ) {
                            items(currentTabState.results.size, key = { "${currentTabState.results[it].traktId}_$it" }, contentType = { "media_card" }) { index ->
                                val item = currentTabState.results[index]
                                MovieCard(
                                    title = item.displayTitle,
                                    year = item.year,
                                    genres = item.genres,
                                    posterUrl = item.posterUrl,
                                    tmdbId = item.tmdbId,
                                    isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.traktId, item.tmdbId, uiState.selectedTab) == true,
                                    isWatched = watchlistWatchedIds?.isWatched(item.traktId, item.tmdbId, uiState.selectedTab) == true,
                                    modifier = if (index == 0) {
                                        Modifier.onGloballyPositioned {
                                            firstResultBounds = it.boundsInRoot()
                                            firstResultAnchorKey = resultAnchorKey
                                        }
                                    } else Modifier,
                                    onClick = {
                                        interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                                        onItemClick(uiState.selectedTab, item.traktId, item.tmdbId, item.displayTitle, item.imdbId, item.traktRating)
                                    }
                                )
                            }
                            if (currentTabState.isLoadingMore) {
                                item(span = { GridItemSpan(3) }) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Haze 模糊覆盖层（搜索框 + Tab）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.50f))
                    .hazeTopBar(
                        state = hazeState,
                        style = hazeStyle,
                        blurRadius = 24.dp,
                        isContentUnderTopBar = hasContentUnderTopBar,
                        scene = traktSearchGlassScene
                    )
                    // 拦截点击：顶栏覆盖可滚动网格，不消费会让点击穿透到下方列表项
                    .clickable(enabled = false, onClick = {})
            ) {
                // 状态栏 Spacer
                Spacer(modifier = Modifier.statusBarsPadding())
                // 搜索框
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        interruptAiSprite(AiSpriteInterruptReason.NAVIGATION)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.search_back), tint = MaterialTheme.colorScheme.primary)
                    }
                    val searchInteractionSource = remember { MutableInteractionSource() }
                    NeumorphicFrostedSurface(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .onGloballyPositioned { searchBoxBounds = it.boundsInRoot() },
                        isDark = isDark,
                        shape = RoundedCornerShape(21.dp),
                        backgroundColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.55f),
                        borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.75f),
                        glassRole = GlassSurfaceRole.SearchField,
                        interactionSource = searchInteractionSource,
                        scene = traktSearchGlassScene,
                        elevation = 4.dp,
                        blurRadius = 16.dp,
                        hazeState = hazeState,
                        hazeStyle = HazeMaterials.thin()
                    ) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = {
                                interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                                searchQuery = it
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(focusRequester)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        interruptAiSprite(AiSpriteInterruptReason.FOCUS)
                                    }
                                    isSearchFocused = it.isFocused
                                }
                                .padding(horizontal = 12.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                                    if (searchQuery.isNotBlank()) {
                                        viewModel.search(searchQuery)
                                    }
                                }
                            ),
                            interactionSource = searchInteractionSource,
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
                                                text = when (uiState.selectedTab) {
                                                    MediaType.MOVIE -> stringResource(R.string.trakt_search_hint_movies)
                                                    MediaType.SHOW -> stringResource(R.string.trakt_search_hint_shows)
                                                    MediaType.PERSON -> stringResource(R.string.trakt_search_hint_persons)
                                                    MediaType.DISK -> stringResource(R.string.trakt_search_hint_disk)
                                                },
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                                fontSize = 14.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = {
                                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                                            searchQuery = ""
                                        }, modifier = Modifier.size(28.dp)) {
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
                }

                // Tab 栏
                val selectedTabIndex = when (uiState.selectedTab) {
                    MediaType.MOVIE -> 0
                    MediaType.SHOW -> 1
                    MediaType.PERSON -> 2
                    MediaType.DISK -> 3
                }
                PrimaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = uiState.selectedTab == MediaType.MOVIE,
                        onClick = {
                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                            view.performHaptic(HapticType.TICK)
                            viewModel.switchTab(MediaType.MOVIE)
                        },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.trakt_search_tab_movies), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                val count = uiState.movieState.totalCount
                                if (count > 0 && uiState.movieState.hasSearched) {
                                    Text("($count)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    )
                    Tab(
                        selected = uiState.selectedTab == MediaType.SHOW,
                        onClick = {
                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                            view.performHaptic(HapticType.CLICK)
                            viewModel.switchTab(MediaType.SHOW)
                        },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.trakt_search_tab_shows), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                val count = uiState.showState.totalCount
                                if (count > 0 && uiState.showState.hasSearched) {
                                    Text("($count)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    )
                    Tab(
                        selected = uiState.selectedTab == MediaType.PERSON,
                        onClick = {
                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                            view.performHaptic(HapticType.CLICK)
                            viewModel.switchTab(MediaType.PERSON)
                        },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.trakt_search_tab_persons), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                val count = uiState.personState.totalCount
                                if (count > 0 && uiState.personState.hasSearched) {
                                    Text("($count)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    )
                    Tab(
                        selected = uiState.selectedTab == MediaType.DISK,
                        onClick = {
                            interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                            view.performHaptic(HapticType.CLICK)
                            viewModel.switchTab(MediaType.DISK)
                        },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.trakt_search_tab_disk), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                val filteredCount = getFilteredDiskCount(uiState.diskState)
                                if (filteredCount > 0 && uiState.diskState.hasSearched) {
                                    Text("($filteredCount)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    )
                }
            }

            // 快速回顶按钮
            AnimatedVisibility(
                visible = showScrollToTop,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 100.dp, end = 16.dp)
            ) {
                val scrollScope = rememberCoroutineScope()
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .appVisualEffect(
                            input = HazeInput.Sources(hazeState),
                            hazeStyle = HazeBlurStyle {
                                backgroundColor(hazeSurface.copy(alpha = 0.6f))
                                blurRadius(20.dp)
                                noiseFactor(0f)
                            },
                            glassRole = GlassSurfaceRole.CircularControl,
                            glassShape = RoundedCornerShape(50),
                            glassTint = hazeSurface.copy(alpha = 0.6f),
                            scene = traktSearchGlassScene,
                            blurSampling = HazeSampling.Adaptive,
                            interactionSource = interactionSource
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {
                                view.performHaptic(HapticType.TICK)
                                scrollScope.launch { currentGridState.animateScrollToItem(0) }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.scroll_to_top),
                        tint = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFF616161) else Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            AiSpriteMotion(
                characterId = spriteState.activatedCharacterId.orEmpty(),
                anchor = activeSpriteAnchor,
                anchorBounds = when (activeSpriteAnchor) {
                    AiSpriteAnchor.ResultCard -> currentFirstResultBounds
                    else -> searchBoxBounds
                },
                visible = showAiSpriteMotion && !showAiSpriteCenter &&
                    (when (activeSpriteAnchor) {
                        AiSpriteAnchor.ResultCard -> currentFirstResultBounds
                        else -> searchBoxBounds
                    } != null),
                onClick = {
                    interruptAiSprite(AiSpriteInterruptReason.USER_INPUT)
                    showAiSpriteCenter = true
                },
                onFinished = {
                    showAiSpriteMotion = false
                    activeSceneEvent = null
                    lastInteractionAt = System.currentTimeMillis()
                },
                modifier = Modifier.zIndex(5f),
                sceneRes = activeSceneEvent?.let { sceneArtFor(it).drawableRes },
                interruptRequest = AiSpriteInterruptRequest(spriteInterruptRevision, spriteInterruptReason)
            )

            AiSpriteCenter(
                visible = showAiSpriteCenter,
                onDismiss = {
                    interruptAiSprite(AiSpriteInterruptReason.NAVIGATION)
                    showAiSpriteCenter = false
                    spriteViewModel.closeFeature()
                },
                onNavigateToLogin = onNavigateToLogin,
                onMovieClick = { traktId, tmdbId, title, imdbId, traktRating, _, _ ->
                    onItemClick(MediaType.MOVIE, traktId, tmdbId, title, imdbId, traktRating)
                },
                onShowClick = { traktId, tmdbId, title, imdbId, traktRating, _, _ ->
                    onItemClick(MediaType.SHOW, traktId, tmdbId, title, imdbId, traktRating)
                },
                onRecommendationClick = onRecommendationClick,
                viewModel = spriteViewModel
            )
        }
    }
    } // CompositionLocalProvider

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiskSearchContent(
    diskState: com.tracktosearch.ui.screen.traktsearch.DiskSearchState,
    onToggleSource: (String) -> Unit,
    onToggleDiskType: (DiskType) -> Unit,
    onItemClick: (ResourceItem) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    hazeState: HazeState = remember { HazeState() },
    statusBarHeight: Dp = 0.dp,
    scene: GlassScene = GlassScene()
) {
    val view = LocalView.current
    val context = LocalContext.current

    val filteredResources = remember(diskState.resources, diskState.enabledSources, diskState.enabledDiskTypes) {
        diskState.resources
            .filter { it.source in diskState.enabledSources }
            .filter { it.diskType in diskState.enabledDiskTypes }
    }

    // 还没搜索过
    if (!diskState.hasSearched) return

    when {
        // 搜索中且无结果：显示全屏加载动画
        diskState.isLoading && diskState.resources.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    .backdropSource(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    SearchLoadingAnimation()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (diskState.totalSources > 0) {
                            stringResource(R.string.search_loading_disk_progress, diskState.completedSources, diskState.totalSources)
                        } else {
                            stringResource(R.string.search_loading_disk)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // 出错且无结果
        diskState.error != null && diskState.resources.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    .backdropSource(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyView(message = diskState.error)
                }
            }
        }
        // 搜索完成但筛选后无结果
        !diskState.isLoading && filteredResources.isEmpty() && diskState.hasSearched -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    .backdropSource(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.search_no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
        // 有结果（搜索中或搜索完成）：先到先显示
        else -> {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 8.dp, top = 124.dp + statusBarHeight, end = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .hazeSource(state = hazeState)
                        .backdropSource()
                ) {
                    // 搜索中时顶部显示紧凑进度条
                    if (diskState.isLoading && diskState.resources.isNotEmpty()) {
                        item(key = "searching_progress") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.search_loading_disk_progress, diskState.completedSources, diskState.totalSources),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // 筛选条（吸顶）
                    stickyHeader {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Transparent)
                        ) {
                            // 搜索源筛选
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_filter_sources),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(64.dp)
                                )
                                LazyRow(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        items = diskState.availableSources,
                                        key = { it }
                                    ) { source ->
                                        val label = when (source) {
                                            "pansou" -> "PanSou"
                                            "panhub" -> "PanHub"
                                            "zreso" -> "Zreso"
                                            else -> diskState.customSourceNames[source] ?: source
                                        }
                                        FilterChip(
                                            selected = source in diskState.enabledSources,
                                            border = if (source in diskState.enabledSources) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                            onClick = { view.performHaptic(HapticType.TICK); onToggleSource(source) },
                                            label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }
                            }
                            // 网盘类型筛选
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 14.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_filter_disk_types),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(64.dp)
                                )
                                LazyRow(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        items = ResourceRepository.ALL_DISK_TYPES.toList(),
                                        key = { it.name }
                                    ) { type ->
                                        val label = when (type) {
                                            DiskType.QUARK -> stringResource(R.string.detail_disk_type_quark)
                                            DiskType.BAIDU -> stringResource(R.string.detail_disk_type_baidu)
                                            DiskType.ALI -> stringResource(R.string.detail_disk_type_ali)
                                            DiskType.XUNLEI -> stringResource(R.string.detail_disk_type_xunlei)
                                            DiskType.UC -> stringResource(R.string.detail_disk_type_uc)
                                            DiskType.ONEONEFIVE -> stringResource(R.string.detail_disk_type_115)
                                            DiskType.MAGNET -> stringResource(R.string.detail_disk_type_magnet)
                                            DiskType.OTHER -> stringResource(R.string.detail_disk_type_other)
                                        }
                                        FilterChip(
                                            selected = type in diskState.enabledDiskTypes,
                                            border = if (type in diskState.enabledDiskTypes) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                            onClick = { view.performHaptic(HapticType.TICK); onToggleDiskType(type) },
                                            label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(filteredResources, key = { _, it -> it.url }, contentType = { _, _ -> "resource" }) { index, item ->
                        ResourceItemCard(
                            item = item,
                            sourceName = diskState.customSourceNames[item.source],
                            isViewed = false,
                            index = index,
                            onClick = { onItemClick(item) },
                            onLongClick = {
                                view.performHaptic(HapticType.HEAVY_CLICK)
                                copyResourceLink(context, item)
                            }
                        )
                    }
                }

                ScrollToTopButton(
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 100.dp, end = 16.dp),
                    hazeState = hazeState,
                    scene = scene
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PersonSearchCard(
    name: String,
    profileUrl: String?,
    knownForDepartment: String,
    personId: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onAvatarColorExtracted: ((Color?) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var extractedColor by remember { mutableStateOf<Color?>(null) }
    val posterColorExtractor = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PosterColorExtractorProvider::class.java
        ).posterColorExtractor()
    }
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
                contentAlignment = Alignment.Center
            ) {
                if (profileUrl != null) {
                    // 启用 sharedElement 转场:key 与 PersonHeaderContent 一致("person-avatar-$personId")
                    val imageModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && LocalSharedTransitionEnabled.current) {
                        with(sharedTransitionScope) {
                            Modifier
                                .sharedElement(
                                    rememberSharedContentState(key = "person-avatar-$personId"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                                .fillMaxSize()
                        }
                    } else {
                        Modifier.fillMaxSize()
                    }
                    SubcomposeAsyncImage(
                        model = remember(profileUrl) {
                            ImageRequest.Builder(context)
                                .data(profileUrl)
                                .size(200)
                                .crossfade(false)
                                .listener(
                                    onSuccess = { _, result ->
                                        val bitmap = result.drawable.toBitmap()
                                        scope.launch {
                                            posterColorExtractor.extractDominantColor(profileUrl, bitmap)
                                                .takeIf { it != 0L }
                                                ?.let { argb ->
                                                    // 写入进程内缓存，供 PersonScreen 首帧读取避免白色闪烁
                                                    PersonAvatarColorStore.put(personId, argb)
                                                    val color = Color(argb)
                                                    if (extractedColor != color) {
                                                        extractedColor = color
                                                        onAvatarColorExtracted?.invoke(color)
                                                    }
                                                }
                                        }
                                    }
                                )
                                .build()
                        },
                        contentDescription = name,
                        modifier = imageModifier
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        contentScale = ContentScale.Crop,
                        loading = {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        },
                        error = {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(text = name.take(1), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = name.take(1),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.padding(6.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (knownForDepartment.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = knownForDepartment,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonCardSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {}
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(12.dp),
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {}
    }
}

@Composable
private fun SearchLoadingAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "search_loading")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Icon(
        imageVector = Icons.Rounded.Search,
        contentDescription = null,
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
        tint = MaterialTheme.colorScheme.primary
    )
}
