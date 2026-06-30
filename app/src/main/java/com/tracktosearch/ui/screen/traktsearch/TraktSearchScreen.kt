package com.tracktosearch.ui.screen.traktsearch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.tracktosearch.ui.component.ScrollToTopButton
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.dto.ResourceType
import com.tracktosearch.data.remote.dto.inferResourceType
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.MovieCardSkeleton
import com.tracktosearch.ui.component.ResourceItemCard
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import androidx.compose.ui.platform.LocalView
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun TraktSearchScreen(
    initialQuery: String,
    type: MediaType,
    onBack: () -> Unit,
    onItemClick: (type: MediaType, traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onPersonClick: (tmdbId: Int, name: String, profileUrl: String) -> Unit = { _, _, _ -> },
    inlineMode: Boolean = false,
    viewModel: TraktSearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
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
                        onTypeFilterChange = { viewModel.setDiskResourceTypeFilter(it) },
                        onDiskTypeFilterChange = { viewModel.setDiskTypeFilter(it) },
                        onItemClick = { openResourceLink(context, it) },
                        listState = diskListState,
                        hazeState = hazeState,
                        statusBarHeight = statusBarHeight
                    )
                }
                currentTabState.isLoading -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 130.dp + statusBarHeight, bottom = 80.dp),
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
                }
                currentTabState.error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(state = hazeState),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyView(message = currentTabState.error!!)
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
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 130.dp + statusBarHeight, bottom = 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(state = hazeState)
                        ) {
                            items(currentTabState.results.size, key = { "${currentTabState.results[it].traktId}_$it" }) { index ->
                                val item = currentTabState.results[index]
                                PersonSearchCard(
                                    name = item.displayTitle,
                                    profileUrl = item.posterUrl,
                                    knownForDepartment = item.knownForDepartment,
                                    onClick = {
                                        if (item.tmdbId > 0) {
                                            onPersonClick(item.tmdbId, item.title, item.posterUrl ?: "")
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
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 130.dp + statusBarHeight, bottom = 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(state = hazeState)
                        ) {
                            items(currentTabState.results.size, key = { "${currentTabState.results[it].traktId}_$it" }) { index ->
                                val item = currentTabState.results[index]
                                MovieCard(
                                    title = item.displayTitle,
                                    year = item.year,
                                    genres = item.genres,
                                    posterUrl = item.posterUrl,
                                    tmdbId = item.tmdbId,
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.search_back), tint = MaterialTheme.colorScheme.primary)
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
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
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                if (searchQuery.isNotBlank()) {
                                    viewModel.search(searchQuery)
                                }
                            }
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                if (searchQuery.isNotBlank()) {
                                    viewModel.search(searchQuery)
                                }
                            }) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.watchlist_search))
                            }
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
                            val count = uiState.movieState.totalCount
                            if (count > 0 && uiState.movieState.hasSearched) {
                                Text(stringResource(R.string.trakt_search_tab_movies_count, count))
                            } else {
                                Text(stringResource(R.string.trakt_search_tab_movies))
                            }
                        }
                    )
                    Tab(
                        selected = uiState.selectedTab == MediaType.SHOW,
                        onClick = { view.performHaptic(HapticType.CLICK); viewModel.switchTab(MediaType.SHOW) },
                        text = {
                            val count = uiState.showState.totalCount
                            if (count > 0 && uiState.showState.hasSearched) {
                                Text(stringResource(R.string.trakt_search_tab_shows_count, count))
                            } else {
                                Text(stringResource(R.string.trakt_search_tab_shows))
                            }
                        }
                    )
                    Tab(
                        selected = uiState.selectedTab == MediaType.PERSON,
                        onClick = { view.performHaptic(HapticType.CLICK); viewModel.switchTab(MediaType.PERSON) },
                        text = {
                            val count = uiState.personState.totalCount
                            if (count > 0 && uiState.personState.hasSearched) {
                                Text(stringResource(R.string.trakt_search_tab_persons_count, count))
                            } else {
                                Text(stringResource(R.string.trakt_search_tab_persons))
                            }
                        }
                    )
                    Tab(
                        selected = uiState.selectedTab == MediaType.DISK,
                        onClick = { view.performHaptic(HapticType.CLICK); viewModel.switchTab(MediaType.DISK) },
                        text = {
                            val count = uiState.diskState.resources.size
                            if (count > 0 && uiState.diskState.hasSearched) {
                                Text(stringResource(R.string.trakt_search_tab_disk_count, count))
                            } else {
                                Text(stringResource(R.string.trakt_search_tab_disk))
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
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.scroll_to_top),
                        tint = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFF616161) else Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiskSearchContent(
    diskState: com.tracktosearch.ui.screen.traktsearch.DiskSearchState,
    onTypeFilterChange: (ResourceType) -> Unit,
    onDiskTypeFilterChange: (DiskType?) -> Unit,
    onItemClick: (ResourceItem) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    hazeState: HazeState = remember { HazeState() },
    statusBarHeight: Dp = 0.dp
) {
    val view = LocalView.current

    val filteredResources = remember(diskState.resources, diskState.diskTypeFilter, diskState.typeFilter) {
        diskState.resources
            .let { rs ->
                if (diskState.typeFilter != ResourceType.ALL) {
                    rs.filter { inferResourceType(it.name) == diskState.typeFilter }
                } else rs
            }
            .let { rs ->
                if (diskState.diskTypeFilter != null) {
                    rs.filter { it.diskType == diskState.diskTypeFilter }
                } else rs
            }
    }

    when {
        diskState.isLoading -> {
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
        diskState.error != null -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyView(message = diskState.error!!)
                }
            }
        }
        diskState.resources.isEmpty() && diskState.hasSearched -> {
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
        else -> {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 8.dp, top = 130.dp + statusBarHeight, end = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.hazeSource(state = hazeState)
                ) {
                    stickyHeader {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            // 影视类型筛选
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.search_filter_type),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(64.dp)
                                )
                                LazyRow(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    item {
                                        FilterChip(
                                            selected = diskState.typeFilter == ResourceType.ALL,
                                            onClick = { view.performHaptic(HapticType.TICK); onTypeFilterChange(ResourceType.ALL) },
                                            label = { Text(stringResource(R.string.search_filter_all), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = diskState.typeFilter == ResourceType.MOVIE,
                                            onClick = { view.performHaptic(HapticType.TICK); onTypeFilterChange(ResourceType.MOVIE) },
                                            label = { Text(stringResource(R.string.search_filter_movie), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = diskState.typeFilter == ResourceType.SHOW,
                                            onClick = { view.performHaptic(HapticType.TICK); onTypeFilterChange(ResourceType.SHOW) },
                                            label = { Text(stringResource(R.string.search_filter_show), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        )
                                    }
                                }
                            }
                            // 网盘类型筛选
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.search_filter_disk),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(64.dp)
                                )
                                LazyRow(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    item {
                                        FilterChip(
                                            selected = diskState.diskTypeFilter == null,
                                            onClick = { view.performHaptic(HapticType.TICK); onDiskTypeFilterChange(null) },
                                            label = { Text(stringResource(R.string.search_filter_all), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        )
                                    }
                                    items(DiskType.entries.filter { it != DiskType.OTHER }) { type ->
                                        FilterChip(
                                            selected = diskState.diskTypeFilter == type,
                                            onClick = { view.performHaptic(HapticType.TICK); onDiskTypeFilterChange(type) },
                                            label = {
                                                val label = when (type) {
                                                    DiskType.QUARK -> stringResource(R.string.disk_quark)
                                                    DiskType.BAIDU -> stringResource(R.string.disk_baidu)
                                                    DiskType.ALI -> stringResource(R.string.disk_ali)
                                                    DiskType.XUNLEI -> stringResource(R.string.disk_xunlei)
                                                    DiskType.UC -> stringResource(R.string.disk_uc)
                                                    DiskType.ONEONEFIVE -> stringResource(R.string.disk_115)
                                                    DiskType.MAGNET -> stringResource(R.string.disk_magnet)
                                                    DiskType.OTHER -> stringResource(R.string.disk_other)
                                                }
                                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(filteredResources, key = { _, it -> it.url }) { index, item ->
                        ResourceItemCard(
                            item = item,
                            isViewed = false,
                            index = index,
                            onClick = { onItemClick(item) }
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

private fun openResourceLink(context: android.content.Context, item: ResourceItem) {
    val url = item.url
    val appPackages = when (item.diskType) {
        DiskType.QUARK -> listOf("com.quark.clouddrive", "com.quark.browser")
        DiskType.BAIDU -> listOf("com.baidu.netdisk")
        DiskType.ALI -> listOf("com.alicloud.databox")
        DiskType.XUNLEI -> listOf("com.xunlei.downloadprovider", "com.xunlei.browser")
        DiskType.UC -> listOf("com.UCMobile")
        DiskType.ONEONEFIVE -> listOf("com.crland.app")
        DiskType.MAGNET, DiskType.OTHER -> emptyList()
    }
    for (pkg in appPackages) {
        try {
            val appIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                setPackage(pkg)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (appIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(appIntent)
                return
            }
        } catch (_: Exception) {}
    }
    try {
        val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserIntent)
    } catch (_: Exception) {}
}

@Composable
private fun PersonSearchCard(
    name: String,
    profileUrl: String?,
    knownForDepartment: String,
    onClick: () -> Unit
) {
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
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(profileUrl)
                            .size(300)
                            .crossfade(true)
                            .build(),
                        contentDescription = name,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        contentScale = ContentScale.Crop
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
        imageVector = Icons.Default.Search,
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
