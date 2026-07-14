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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.util.PersonAvatarColorStore
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.LocalActivePosterTmdbId
import com.tracktosearch.ui.component.LocalActivePosterClickSetter
import com.tracktosearch.ui.component.LocalActivePosterClickToken
import com.tracktosearch.ui.component.LocalActivePosterTmdbIdSetter
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.MovieCardSkeleton
import com.tracktosearch.ui.component.PosterColorExtractorProvider
import com.tracktosearch.ui.component.ResourceItemCard
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.copyResourceLink
import com.tracktosearch.ui.util.openResourceLink
import com.tracktosearch.ui.util.performHaptic
import dagger.hilt.android.EntryPointAccessors
import androidx.core.graphics.drawable.toBitmap
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch

/** 计算网盘tab筛选后的结果数（用于Tab标签显示） */
private fun getFilteredDiskCount(diskState: DiskSearchState): Int {
    return diskState.resources
        .filter { it.source in diskState.enabledSources }
        .filter { it.diskType in diskState.enabledDiskTypes }
        .size
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun TraktSearchScreen(
    initialQuery: String,
    type: MediaType,
    onBack: () -> Unit,
    onItemClick: (type: MediaType, traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onPersonClick: (tmdbId: Int, name: String, profileUrl: String?, avatarColor: Color?) -> Unit = { _, _, _, _ -> },
    inlineMode: Boolean = false,
    viewModel: TraktSearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val watchlistWatchedIds by viewModel.watchlistWatchedIds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    // 当前活跃海报 tmdbId（-1=都不启用），确保只有用户点击的卡片参与共享元素转场
    var activePosterTmdbId by rememberSaveable { mutableIntStateOf(-1) }
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }

    var searchQuery by rememberSaveable { mutableStateOf(initialQuery) }

    LaunchedEffect(initialQuery, type) {
        viewModel.initSearch(initialQuery, type)
    }

    val movieGridState = rememberLazyGridState()
    val showGridState = rememberLazyGridState()
    val personGridState = rememberLazyGridState()
    val diskListState = rememberLazyListState()
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
                val scrollingUp = index < prevScrollIndex || (index == prevScrollIndex && offset < prevScrollOffset)
                if (scrollingUp && index > 5) showScrollToTop = true
                else if (index <= 5) showScrollToTop = false
                prevScrollIndex = index
                prevScrollOffset = offset
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

        // 主内容区域 - hazeSource 应用到可滚动组件
        when {
                isDiskTab -> {
                    DiskSearchContent(
                        diskState = uiState.diskState,
                        onToggleSource = { viewModel.toggleDiskSource(it) },
                        onToggleDiskType = { viewModel.toggleDiskType(it) },
                        onItemClick = { openResourceLink(context, it) },
                        listState = diskListState,
                        hazeState = hazeState,
                        statusBarHeight = statusBarHeight
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
                                .hazeSource(state = hazeState),
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
                            .hazeSource(state = hazeState),
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
                            .hazeSource(state = hazeState),
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
                        ) {
                            items(currentTabState.results.size, key = { "${currentTabState.results[it].traktId}_$it" }, contentType = { "person" }) { index ->
                                val item = currentTabState.results[index]
                                PersonSearchCard(
                                    name = item.displayTitle,
                                    profileUrl = item.posterUrl,
                                    knownForDepartment = item.knownForDepartment,
                                    personId = item.tmdbId,
                                    onClick = {
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
                                    onClick = {
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
                    .hazeEffect(state = hazeState, style = HazeMaterials.thin())
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.50f))
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.search_back), tint = MaterialTheme.colorScheme.primary)
                    }
                    val searchInteractionSource = remember { MutableInteractionSource() }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(45.dp)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                if (searchQuery.isNotBlank()) {
                                    viewModel.search(searchQuery)
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
                                        when (uiState.selectedTab) {
                                            MediaType.MOVIE -> stringResource(R.string.trakt_search_hint_movies)
                                            MediaType.SHOW -> stringResource(R.string.trakt_search_hint_shows)
                                            MediaType.PERSON -> stringResource(R.string.trakt_search_hint_persons)
                                            MediaType.DISK -> stringResource(R.string.trakt_search_hint_disk)
                                        }
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(32.dp)) {
                                            Icon(
                                                Icons.Rounded.Close,
                                                contentDescription = stringResource(R.string.content_desc_clear),
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    } else {
                                        IconButton(onClick = {
                                            if (searchQuery.isNotBlank()) {
                                                viewModel.search(searchQuery)
                                            }
                                        }) {
                                            Icon(
                                                Icons.Rounded.Search,
                                                contentDescription = stringResource(R.string.watchlist_search),
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 0.dp, bottom = 0.dp),
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
                        onClick = { view.performHaptic(HapticType.TICK); viewModel.switchTab(MediaType.MOVIE) },
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
                        onClick = { view.performHaptic(HapticType.CLICK); viewModel.switchTab(MediaType.SHOW) },
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
                        onClick = { view.performHaptic(HapticType.CLICK); viewModel.switchTab(MediaType.PERSON) },
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
                        onClick = { view.performHaptic(HapticType.CLICK); viewModel.switchTab(MediaType.DISK) },
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
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                blurRadius = 20.dp,
                                noiseFactor = 0f,
                                tint = null
                            )
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
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
    statusBarHeight: Dp = 0.dp
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
                    .hazeSource(state = hazeState),
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
                    .hazeSource(state = hazeState),
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
                    .hazeSource(state = hazeState),
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
                    modifier = Modifier.hazeSource(state = hazeState)
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
                                .background(MaterialTheme.colorScheme.background)
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
                    hazeState = hazeState
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
