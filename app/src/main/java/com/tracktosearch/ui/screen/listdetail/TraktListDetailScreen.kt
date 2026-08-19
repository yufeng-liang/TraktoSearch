package com.tracktosearch.ui.screen.listdetail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.LocalActivePosterClickSetter
import com.tracktosearch.ui.component.LocalActivePosterClickToken
import com.tracktosearch.ui.component.LocalActivePosterTmdbId
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.MovieCardSkeleton
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.backdropSource
import com.tracktosearch.ui.component.rememberCachedPosterAmbientColor
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.ScrollToTopButton
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun TraktListDetailScreen(
    onBack: () -> Unit,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    viewModel: TraktListDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val watchlistWatchedIds by viewModel.watchlistWatchedIds.collectAsStateWithLifecycle()
    // rememberSaveable + Saver：进入详情页返回后恢复原滚动位置，不再回到顶部
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    val listGlassScene = glassSceneForContent(
        contentCount = uiState.items.size,
        readabilityDemand = when {
            uiState.error != null -> 0.96f
            uiState.isLoading -> 0.78f
            uiState.items.isNotEmpty() -> 0.82f
            else -> 0.62f
        },
        ambientColor = rememberCachedPosterAmbientColor(
            posterUrls = uiState.items.mapNotNull { it.posterUrl },
            fallback = MaterialTheme.colorScheme.background
        ),
        contentCapacity = 42,
        loadingCount = if (uiState.isLoading || uiState.isLoadingMore) 1 else 0,
        loadingItemWeight = 4
    )
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 当前活跃海报 tmdbId（-1=都不启用），确保只有用户点击的卡片参与共享元素转场
    var activePosterTmdbId by rememberSaveable { mutableIntStateOf(-1) }
    // 共享元素转场 scope（标题栏整体与发现页社区列表卡片配对）
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    BackHandler(enabled = true) { onBack() }

    // 滚动到底部时加载更多
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null && lastVisibleIndex >= uiState.items.size - 6) {
                    viewModel.loadMore()
                }
            }
    }

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
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                // 首次加载：骨架屏
                uiState.isLoading && uiState.items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(state = hazeState)
                                .backdropSource(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 65.dp + statusBarHeight, bottom = 16.dp)
                        ) {
                            items(6) {
                                MovieCardSkeleton()
                            }
                        }

                        // Haze 模糊标题栏（与发现页社区列表卡片配对 sharedBounds 转场）
                        val loadingHeaderModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && uiState.listId > 0 && LocalSharedTransitionEnabled.current) {
                            with(sharedTransitionScope) {
                                Modifier.sharedBounds(
                                    sharedContentState = rememberSharedContentState(key = "trakt-list-card-${uiState.listId}"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                            }
                        } else { Modifier }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(loadingHeaderModifier)
                                .hazeTopBar(
                                    state = hazeState,
                                    style = hazeStyle,
                                    blurRadius = 24.dp,
                                    scene = listGlassScene
                                )
                                .clickable(enabled = false, onClick = {})
                        ) {
                            Spacer(modifier = Modifier.statusBarsPadding())
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
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
                                    text = stringResource(R.string.common_loading),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // 错误且无数据
                uiState.error != null && uiState.items.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Button(
                                onClick = { viewModel.retry() },
                                modifier = Modifier.padding(top = 12.dp)
                            ) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }
                }

                // 正常内容
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(state = hazeState)
                            .backdropSource(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 65.dp + statusBarHeight, bottom = 16.dp)
                    ) {
                        itemsIndexed(uiState.items, key = { _, item -> "${item.type}-${item.traktId}" }) { _, item ->
                            val isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.traktId, item.tmdbId, item.type) == true
                            val isWatched = watchlistWatchedIds?.isWatched(item.traktId, item.tmdbId, item.type) == true
                            MovieCard(
                                title = item.title,
                                year = item.year,
                                genres = "",
                                posterUrl = item.posterUrl?.let { TmdbImageUrls.build(it) },
                                tmdbId = item.tmdbId,
                                isInWatchlist = isInWatchlist,
                                isWatched = isWatched,
                                onClick = {
                                    when (item.type) {
                                        MediaType.MOVIE -> onMovieClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating, isInWatchlist, isWatched)
                                        MediaType.SHOW -> onShowClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating, isInWatchlist, isWatched)
                                        else -> {}
                                    }
                                }
                            )
                        }

                        // 底部加载更多骨架
                        if (uiState.isLoadingMore) {
                            items(3) {
                                MovieCardSkeleton()
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
                        scene = listGlassScene
                    )

                    // Haze 模糊标题栏（与发现页社区列表卡片配对 sharedBounds 转场）
                    val headerModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && uiState.listId > 0 && LocalSharedTransitionEnabled.current) {
                        with(sharedTransitionScope) {
                            Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "trakt-list-card-${uiState.listId}"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    } else { Modifier }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(headerModifier)
                            .hazeTopBar(
                                state = hazeState,
                                style = hazeStyle,
                                blurRadius = 24.dp,
                                scene = listGlassScene
                            )
                            .clickable(enabled = false, onClick = {})
                    ) {
                        Spacer(modifier = Modifier.statusBarsPadding())
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.content_desc_back),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = uiState.listName,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
    }
}


