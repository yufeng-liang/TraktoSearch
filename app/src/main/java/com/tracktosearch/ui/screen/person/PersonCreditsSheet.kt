package com.tracktosearch.ui.screen.person

import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonMovieCredit
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonTvCredit
import com.tracktosearch.ui.component.AppBottomSheet
import com.tracktosearch.ui.component.LoadMoreFooter
import com.tracktosearch.ui.component.LoadMoreFooterState

/** 全部参演电影弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllMovieCreditsSheet(
    title: String,
    credits: List<TmdbPersonMovieCredit>,
    resolvingTmdbId: Int?,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    loadMoreError: Boolean,
    onLoadMore: () -> Unit,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    viewModel: PersonViewModel,
    onDismiss: () -> Unit,
    gridState: LazyGridState
) {
    LaunchedEffect(gridState, credits.size, hasMore, isLoadingMore, loadMoreError) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (hasMore && !isLoadingMore && !loadMoreError && lastVisibleIndex != null && lastVisibleIndex >= credits.size - 3) {
                    onLoadMore()
                }
            }
    }

    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = title
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Grid 列表
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(credits, key = { index, credit -> "movie_${credit.id}_$index" }, contentType = { _, _ -> "media_card" }) { _, credit ->
                    CreditCard(
                        title = credit.title,
                        subtitle = credit.character,
                        year = credit.release_date.take(4),
                        posterUrl = credit.poster_path?.let { TmdbImageUrls.build(it) },
                        isResolving = resolvingTmdbId == credit.id,
                        onClick = {
                            viewModel.resolveAndNavigate(
                                tmdbId = credit.id,
                                title = credit.title,
                                isMovie = true,
                                onNavigate = onMovieClick
                            )
                        }
                    )
                }
                if (credits.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        LoadMoreFooter(
                            state = when {
                                isLoadingMore -> LoadMoreFooterState.Loading
                                loadMoreError -> LoadMoreFooterState.Error
                                !hasMore -> LoadMoreFooterState.Complete
                                else -> LoadMoreFooterState.Hidden
                            },
                            onRetry = onLoadMore,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/** 全部参演电视剧弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllTvCreditsSheet(
    title: String,
    credits: List<TmdbPersonTvCredit>,
    resolvingTmdbId: Int?,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    loadMoreError: Boolean,
    onLoadMore: () -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    viewModel: PersonViewModel,
    onDismiss: () -> Unit,
    gridState: LazyGridState
) {
    LaunchedEffect(gridState, credits.size, hasMore, isLoadingMore, loadMoreError) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (hasMore && !isLoadingMore && !loadMoreError && lastVisibleIndex != null && lastVisibleIndex >= credits.size - 3) {
                    onLoadMore()
                }
            }
    }

    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = title
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Grid 列表
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(credits, key = { index, credit -> "tv_${credit.id}_$index" }, contentType = { _, _ -> "media_card" }) { _, credit ->
                    CreditCard(
                        title = credit.name,
                        subtitle = credit.character,
                        year = credit.first_air_date.take(4),
                        posterUrl = credit.poster_path?.let { TmdbImageUrls.build(it) },
                        isResolving = resolvingTmdbId == credit.id,
                        onClick = {
                            viewModel.resolveAndNavigate(
                                tmdbId = credit.id,
                                title = credit.name,
                                isMovie = false,
                                onNavigate = onShowClick
                            )
                        }
                    )
                }
                if (credits.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        LoadMoreFooter(
                            state = when {
                                isLoadingMore -> LoadMoreFooterState.Loading
                                loadMoreError -> LoadMoreFooterState.Error
                                !hasMore -> LoadMoreFooterState.Complete
                                else -> LoadMoreFooterState.Hidden
                            },
                            onRetry = onLoadMore,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
