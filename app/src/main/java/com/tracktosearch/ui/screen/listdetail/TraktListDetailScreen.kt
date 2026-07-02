package com.tracktosearch.ui.screen.listdetail

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateTopPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.MovieCardSkeleton
import androidx.compose.runtime.snapshotFlow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun TraktListDetailScreen(
    onBack: () -> Unit,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    viewModel: TraktListDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val hazeState = remember { HazeState() }
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

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

    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) { padding ->
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
                                .statusBarsPadding()
                                .hazeSource(state = hazeState),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 65.dp + statusBarHeight, bottom = 16.dp)
                        ) {
                            items(6) {
                                MovieCardSkeleton()
                            }
                        }
                        // 返回按钮
                        BackButton(hazeState = hazeState, onBack = onBack)
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
                                Text("重试")
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
                            .statusBarsPadding()
                            .hazeSource(state = hazeState),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 65.dp + statusBarHeight, bottom = 16.dp)
                    ) {
                        itemsIndexed(uiState.items, key = { _, item -> "${item.type}-${item.traktId}" }) { _, item ->
                            MovieCard(
                                title = item.title,
                                year = item.year,
                                genres = "",
                                posterUrl = item.posterUrl?.let { "https://image.tmdb.org/t/p/w500$it" },
                                tmdbId = item.tmdbId,
                                onClick = {
                                    when (item.type) {
                                        MediaType.MOVIE -> onMovieClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating)
                                        MediaType.SHOW -> onShowClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating)
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

                    // 返回按钮
                    BackButton(hazeState = hazeState, onBack = onBack)

                    // 列表名标题
                    if (uiState.listName.isNotBlank()) {
                        Text(
                            text = uiState.listName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .statusBarsPadding()
                                .padding(start = 64.dp, top = 10.dp, end = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackButton(
    hazeState: HazeState,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .statusBarsPadding()
            .padding(start = 12.dp, top = 4.dp)
            .size(40.dp)
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
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                shape = CircleShape
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = CircleShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
    }
}
