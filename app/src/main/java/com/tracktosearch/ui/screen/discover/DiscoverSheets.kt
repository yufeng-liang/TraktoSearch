package com.tracktosearch.ui.screen.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedShowResponse
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingListResponse

/** TMDB 电影全部弹窗（分页加载） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TmdbAllSheet(
    title: String,
    items: List<TmdbSearchResult>,
    isLoading: Boolean,
    hasMore: Boolean,
    currentPage: Int,
    onItemClick: (TmdbSearchResult) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyGridState()
    // 滚动到底部时自动加载更多
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null &&
                    lastVisibleIndex >= items.size - 5 &&
                    hasMore &&
                    !isLoading
                ) {
                    onLoadMore()
                }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = listState,
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items, key = { index, movie -> "all_tmdb_${index}_${movie.id}" }, contentType = { _, _ -> "media_card" }) { _, movie ->
                    MovieCard(
                        title = movie.title,
                        posterPath = movie.poster_path,
                        year = movie.release_date.take(4),
                        rating = null,
                        isResolving = false,
                        onClick = { onItemClick(movie) }
                    )
                }
                if (isLoading) {
                    item(span = { GridItemSpan(3) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Trakt 推荐全部弹窗（分页加载） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TraktMovieAllSheet(
    title: String,
    items: List<TraktMovie>,
    isLoading: Boolean,
    hasMore: Boolean,
    currentPage: Int,
    onItemClick: (TraktMovie) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyGridState()
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null &&
                    lastVisibleIndex >= items.size - 5 &&
                    hasMore &&
                    !isLoading
                ) {
                    onLoadMore()
                }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = listState,
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items, key = { index, movie -> "all_trakt_${index}_${movie.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, movie ->
                    MovieCard(
                        title = movie.title,
                        posterPath = movie.posterPath,
                        year = if (movie.year > 0) movie.year.toString() else "",
                        rating = if (movie.rating > 0)
                            String.format("%.1f", movie.rating) else null,
                        isResolving = false,
                        onClick = { onItemClick(movie) }
                    )
                }
                if (isLoading) {
                    item(span = { GridItemSpan(3) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Trakt 剧集全部弹窗（无分页，直接展示已加载数据） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TraktShowAllSheet(
    title: String,
    items: List<TraktShow>,
    onItemClick: (TraktShow) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyGridState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = listState,
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items, key = { index, show -> "all_trakt_show_${index}_${show.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, show ->
                    MovieCard(
                        title = show.title,
                        posterPath = show.posterPath,
                        year = if (show.year > 0) show.year.toString() else "",
                        rating = if (show.rating > 0)
                            String.format("%.1f", show.rating) else null,
                        isResolving = false,
                        onClick = { onItemClick(show) }
                    )
                }
            }
        }
    }
}

/** Trakt 最受期待全部弹窗（电影+剧集混合，无分页） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TraktAnticipatedAllSheet(
    anticipatedMovies: List<TraktAnticipatedMovieResponse>,
    anticipatedShows: List<TraktAnticipatedShowResponse>,
    onMovieClick: (TraktMovie) -> Unit,
    onShowClick: (TraktShow) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyGridState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.discover_trakt_anticipated),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = listState,
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 先展示电影，再展示剧集
                itemsIndexed(anticipatedMovies, key = { index, item -> "all_anticip_m_${index}_${item.movie.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, item ->
                    MovieCard(
                        title = item.movie.title,
                        posterPath = item.movie.posterPath,
                        year = if (item.movie.year > 0) item.movie.year.toString() else "",
                        rating = if (item.movie.rating > 0) String.format("%.1f", item.movie.rating) else null,
                        subtitle = stringResource(R.string.discover_list_count, item.list_count),
                        isResolving = false,
                        onClick = { onMovieClick(item.movie) }
                    )
                }
                itemsIndexed(anticipatedShows, key = { index, item -> "all_anticip_s_${index}_${item.show.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, item ->
                    MovieCard(
                        title = item.show.title,
                        posterPath = item.show.posterPath,
                        year = if (item.show.year > 0) item.show.year.toString() else "",
                        rating = if (item.show.rating > 0) String.format("%.1f", item.show.rating) else null,
                        subtitle = stringResource(R.string.discover_list_count, item.list_count),
                        isResolving = false,
                        onClick = { onShowClick(item.show) }
                    )
                }
            }
        }
    }
}

/** 社区热门列表全部弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrendingListsAllSheet(
    lists: List<TraktTrendingListResponse>,
    onListClick: (slug: String, listName: String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.discover_trending_lists),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(lists, key = { _, item -> item.list.ids.slug }, contentType = { _, _ -> "list" }) { _, listResponse ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onListClick(listResponse.list.ids.slug, listResponse.list.name) }) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = listResponse.list.name,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.discover_list_meta, listResponse.list.item_count, listResponse.list.user?.username ?: "", listResponse.like_count),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (listResponse.list.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = listResponse.list.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
