package com.tracktosearch.ui.screen.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tracktosearch.R
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.AppErrorVariant
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedShowResponse
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingListResponse
import com.tracktosearch.data.repository.TraktRepository
import androidx.compose.foundation.shape.RoundedCornerShape
import com.tracktosearch.ui.component.AdaptiveTwoLineTitle
import com.tracktosearch.ui.component.AppVisualSurface
import com.tracktosearch.ui.component.DiscoverModalBottomSheet
import com.tracktosearch.ui.component.RatingBadge
import com.tracktosearch.ui.component.VisualSurfaceKind
import com.tracktosearch.ui.component.YearBadge
import com.tracktosearch.ui.component.LoadMoreFooter
import com.tracktosearch.ui.component.LoadMoreFooterState
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics

/** Sheet 内影视卡片：海报 + 下方标题/副标题 */
@Composable
private fun SheetMediaCard(
    imageUrl: String?,
    title: String,
    subtitle: String? = null,
    year: String? = null,
    rating: Double? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "sheet_media_card_scale"
    )
    val posterShape = RoundedCornerShape(12.dp)

    Column {
        AppVisualSurface(
            kind = VisualSurfaceKind.Content,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .scale(cardScale)
                .hapticClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    // 弹窗网格里的海报卡是看图/导航，不震（用户规则：点击图片无触感）
                    semantic = null,
                    onClick = onClick
                ),
            shape = posterShape,
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            borderColor = Color.Transparent
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (rating != null) {
                    RatingBadge(
                        rating = rating,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                    )
                }
                if (!year.isNullOrBlank() && year != "0") {
                    YearBadge(
                        year = year,
                        fontSize = 10,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            AdaptiveTwoLineTitle(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                maxFontSize = 12.sp,
                minFontSize = 12.sp
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** TMDB 电影全部弹窗（分页加载） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TmdbAllSheet(
    title: String,
    items: List<TmdbSearchResult>,
    isLoading: Boolean,
    hasMore: Boolean,
    currentPage: Int,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    selectedTimeWindow: String = "day",
    onTimeWindowChange: ((String) -> Unit)? = null,
    onItemClick: (TmdbSearchResult) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null
) {
    val listState = rememberLazyGridState()
    // 滚动到底部时自动加载更多
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null &&
                    lastVisibleIndex >= items.size - 5 &&
                    hasMore &&
                    !isLoading &&
                    errorMessage == null
                ) {
                    onLoadMore()
                }
            }
    }

    DiscoverModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        // ModalBottomSheet 的内容是独立 subcomposition（自己的宿主 View），
        // 必须在这一层取，不能复用调用页那一份
        val sheetHaptics = rememberAppHaptics()
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (onTimeWindowChange != null) {
                        CapsuleTabSelector(
                            tabs = listOf(
                                stringResource(R.string.discover_trending_day),
                                stringResource(R.string.discover_trending_week)
                            ),
                            selectedIndex = if (selectedTimeWindow == "day") 0 else 1,
                            onTabSelected = { index ->
                                onTimeWindowChange(if (index == 0) "day" else "week")
                            }
                        )
                    }
                    // 只给这个可见的关闭按钮发；点遮罩/侧滑关闭走 onDismissRequest，按约定静默
                    IconButton(onClick = {
                        sheetHaptics.lightTap()
                        onDismiss()
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.common_close)
                        )
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxHeight(0.8f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items, key = { _, movie -> "all_tmdb_${movie.id}" }, contentType = { _, _ -> "media_card" }) { _, movie ->
                    SheetMediaCard(
                        imageUrl = movie.poster_path?.let { TmdbImageUrls.build(it) },
                        title = movie.title,
                        year = movie.release_date.take(4),
                        onClick = { onItemClick(movie) }
                    )
                }
                item(span = { GridItemSpan(3) }) {
                    LoadMoreFooter(
                        state = when {
                            isLoading -> LoadMoreFooterState.Loading
                            errorMessage != null -> LoadMoreFooterState.Error
                            !hasMore && items.isNotEmpty() -> LoadMoreFooterState.Complete
                            else -> LoadMoreFooterState.Hidden
                        },
                        onRetry = onRetry ?: onLoadMore,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // 加载更多失败：显示可点击的重试提示
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
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    onItemClick: (TraktMovie) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null
) {
    val listState = rememberLazyGridState()
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null &&
                    lastVisibleIndex >= items.size - 5 &&
                    hasMore &&
                    !isLoading &&
                    errorMessage == null
                ) {
                    onLoadMore()
                }
            }
    }

    DiscoverModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        // Sheet 内容有自己的宿主 View，单独取一份
        val sheetHaptics = rememberAppHaptics()
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
                IconButton(onClick = {
                    sheetHaptics.lightTap()
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close)
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxHeight(0.8f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items, key = { _, movie -> "all_trakt_${movie.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, movie ->
                    SheetMediaCard(
                        imageUrl = movie.posterPath?.let { TmdbImageUrls.build(it) },
                        title = movie.title,
                        year = if (movie.year > 0) movie.year.toString() else "",
                        rating = if (movie.rating > 0) movie.rating else null,
                        onClick = { onItemClick(movie) }
                    )
                }
                item(span = { GridItemSpan(3) }) {
                    LoadMoreFooter(
                        state = when {
                            isLoading -> LoadMoreFooterState.Loading
                            errorMessage != null -> LoadMoreFooterState.Error
                            !hasMore && items.isNotEmpty() -> LoadMoreFooterState.Complete
                            else -> LoadMoreFooterState.Hidden
                        },
                        onRetry = onRetry ?: onLoadMore,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // 加载更多失败：显示可点击的重试提示
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
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    onItemClick: (TraktShow) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyGridState()

    DiscoverModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        // Sheet 内容有自己的宿主 View，单独取一份
        val sheetHaptics = rememberAppHaptics()
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
                IconButton(onClick = {
                    sheetHaptics.lightTap()
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close)
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxHeight(0.8f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items, key = { _, show -> "all_trakt_show_${show.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, show ->
                    SheetMediaCard(
                        imageUrl = show.posterPath?.let { TmdbImageUrls.build(it) },
                        title = show.title,
                        year = if (show.year > 0) show.year.toString() else "",
                        rating = if (show.rating > 0) show.rating else null,
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
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    onMovieClick: (TraktMovie) -> Unit,
    onShowClick: (TraktShow) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyGridState()

    DiscoverModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        // Sheet 内容有自己的宿主 View，单独取一份
        val sheetHaptics = rememberAppHaptics()
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
                IconButton(onClick = {
                    sheetHaptics.lightTap()
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close)
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxHeight(0.8f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 先展示电影，再展示剧集
                itemsIndexed(anticipatedMovies, key = { _, item -> "all_anticip_m_${item.movie.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, item ->
                    SheetMediaCard(
                        imageUrl = item.movie.posterPath?.let { TmdbImageUrls.build(it) },
                        title = item.movie.title,
                        subtitle = stringResource(R.string.discover_list_count, item.list_count),
                        year = if (item.movie.year > 0) item.movie.year.toString() else "",
                        rating = if (item.movie.rating > 0) item.movie.rating else null,
                        onClick = { onMovieClick(item.movie) }
                    )
                }
                itemsIndexed(anticipatedShows, key = { _, item -> "all_anticip_s_${item.show.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, item ->
                    SheetMediaCard(
                        imageUrl = item.show.posterPath?.let { TmdbImageUrls.build(it) },
                        title = item.show.title,
                        subtitle = stringResource(R.string.discover_list_count, item.list_count),
                        year = if (item.show.year > 0) item.show.year.toString() else "",
                        rating = if (item.show.rating > 0) item.show.rating else null,
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
    onListClick: (listId: Int, listName: String) -> Unit,
    onDismiss: () -> Unit
) {
    DiscoverModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        // Sheet 内容有自己的宿主 View，单独取一份
        val sheetHaptics = rememberAppHaptics()
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
                IconButton(onClick = {
                    sheetHaptics.lightTap()
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close)
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(lists, key = { _, item -> item.list.ids.slug }, contentType = { _, _ -> "list" }) { _, listResponse ->
                    // 与发现页里的同一批卡片共用 TrendingListCard，规格必须一致。
                    // 这一侧不挂 appSharedBounds：ModalBottomSheet 在自己的窗口里，配不上共享元素。
                    TrendingListCard(
                        title = listResponse.list.name,
                        meta = stringResource(R.string.discover_list_meta, listResponse.list.item_count, listResponse.list.user?.username ?: "", listResponse.like_count),
                        description = listResponse.list.description,
                        onClick = { onListClick(listResponse.list.ids.trakt, listResponse.list.name) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
