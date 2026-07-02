package com.tracktosearch.ui.screen.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedShowResponse
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktRecommendationShowResponse
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingShowResponse
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.ui.component.DoubanHotCardSkeleton
import com.tracktosearch.data.repository.MediaType

@Composable
internal fun TmdbMovieSection(
    title: String,
    movies: List<TmdbSearchResult>,
    isLoading: Boolean,
    error: String?,
    resolvingItemId: Int?,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    onItemClick: (TmdbSearchResult) -> Unit,
    onRetry: () -> Unit,
    onViewAll: () -> Unit
) {
    Column {
        if (title.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                if (movies.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .clickable { onViewAll() }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.discover_view_all, movies.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            error != null -> {
                ErrorRetryRow(error = error, onRetry = onRetry)
            }
            movies.isEmpty() -> {
                EmptyRow()
            }
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(movies, key = { index, movie -> "tmdb_movie_${index}_${movie.id}" }, contentType = { _, _ -> "media_card" }) { _, movie ->
                        MovieCard(
                            title = movie.title,
                            posterPath = movie.poster_path,
                            year = movie.release_date.take(4),
                            rating = null,
                            isResolving = resolvingItemId == movie.id,
                            isInWatchlist = watchlistWatchedIds?.isInWatchlist(null, movie.id, MediaType.MOVIE) == true,
                            isWatched = watchlistWatchedIds?.isWatched(null, movie.id, MediaType.MOVIE) == true,
                            onClick = { onItemClick(movie) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TraktRecommendationSection(
    title: String,
    movies: List<TraktMovie>,
    isLoading: Boolean,
    error: String?,
    resolvingItemId: Int?,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    onItemClick: (TraktMovie) -> Unit,
    onRetry: () -> Unit,
    onViewAll: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (movies.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clickable { onViewAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_view_all, movies.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            error != null -> {
                ErrorRetryRow(error = error, onRetry = onRetry)
            }
            movies.isEmpty() -> {
                EmptyRow()
            }
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(movies, key = { index, movie -> "trakt_movie_${index}_${movie.ids.trakt}_${movie.ids.tmdb}" }, contentType = { _, _ -> "media_card" }) { _, movie ->
                        MovieCard(
                            title = movie.title,
                            posterPath = movie.posterPath,
                            year = if (movie.year > 0) movie.year.toString() else "",
                            rating = if (movie.rating > 0)
                                String.format("%.1f", movie.rating) else null,
                            isResolving = resolvingItemId == movie.ids.tmdb,
                            isInWatchlist = watchlistWatchedIds?.isInWatchlist(movie.ids.trakt, movie.ids.tmdb, MediaType.MOVIE) == true,
                            isWatched = watchlistWatchedIds?.isWatched(movie.ids.trakt, movie.ids.tmdb, MediaType.MOVIE) == true,
                            onClick = { onItemClick(movie) }
                        )
                    }
                }
            }
        }
    }
}

/** Trakt 热门电影栏目 */
@Composable
internal fun TraktTrendingMovieSection(
    items: List<TraktTrendingMovieResponse>,
    isLoading: Boolean,
    resolvingItemId: Int?,
    totalCount: Int,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    onItemClick: (TraktMovie) -> Unit,
    onViewAll: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.discover_trakt_trending_movies),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (totalCount > 0) {
                Row(
                    modifier = Modifier
                        .clickable { onViewAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_view_all, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            items.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(items, key = { index, item -> "trending_movie_${index}_${item.movie.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, item ->
                        MovieCard(
                            title = item.movie.title,
                            posterPath = item.movie.posterPath,
                            year = if (item.movie.year > 0) item.movie.year.toString() else "",
                            rating = if (item.movie.rating > 0) String.format("%.1f", item.movie.rating) else null,
                            subtitle = stringResource(R.string.discover_watchers, item.watchers),
                            isResolving = resolvingItemId == item.movie.ids.tmdb,
                            isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.movie.ids.trakt, item.movie.ids.tmdb, MediaType.MOVIE) == true,
                            isWatched = watchlistWatchedIds?.isWatched(item.movie.ids.trakt, item.movie.ids.tmdb, MediaType.MOVIE) == true,
                            onClick = { onItemClick(item.movie) }
                        )
                    }
                }
            }
        }
    }
}

/** Trakt 热门剧集栏目 */
@Composable
internal fun TraktTrendingShowSection(
    items: List<TraktTrendingShowResponse>,
    isLoading: Boolean,
    resolvingItemId: Int?,
    totalCount: Int,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    onItemClick: (TraktShow) -> Unit,
    onViewAll: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.discover_trakt_trending_shows),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (totalCount > 0) {
                Row(
                    modifier = Modifier
                        .clickable { onViewAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_view_all, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            items.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(items, key = { index, item -> "show_rec_${index}_${item.show.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, item ->
                        MovieCard(
                            title = item.show.title,
                            posterPath = item.show.posterPath,
                            year = if (item.show.year > 0) item.show.year.toString() else "",
                            rating = if (item.show.rating > 0) String.format("%.1f", item.show.rating) else null,
                            subtitle = stringResource(R.string.discover_watchers, item.watchers),
                            isResolving = resolvingItemId == item.show.ids.tmdb,
                            isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.show.ids.trakt, item.show.ids.tmdb, MediaType.SHOW) == true,
                            isWatched = watchlistWatchedIds?.isWatched(item.show.ids.trakt, item.show.ids.tmdb, MediaType.SHOW) == true,
                            onClick = { onItemClick(item.show) }
                        )
                    }
                }
            }
        }
    }
}

/** Trakt 最受期待栏目（电影+剧集混合） */
@Composable
internal fun TraktAnticipatedSection(
    anticipatedMovies: List<TraktAnticipatedMovieResponse>,
    anticipatedShows: List<TraktAnticipatedShowResponse>,
    isLoading: Boolean,
    resolvingItemId: Int?,
    totalCount: Int,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    onMovieClick: (TraktMovie) -> Unit,
    onShowClick: (TraktShow) -> Unit,
    onViewAll: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.discover_trakt_anticipated),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (totalCount > 0) {
                Row(
                    modifier = Modifier
                        .clickable { onViewAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_view_all, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            anticipatedMovies.isEmpty() && anticipatedShows.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // 先展示电影，再展示剧集
                    itemsIndexed(anticipatedMovies, key = { index, item -> "anticip_m_${index}_${item.movie.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, item ->
                        MovieCard(
                            title = item.movie.title,
                            posterPath = item.movie.posterPath,
                            year = if (item.movie.year > 0) item.movie.year.toString() else "",
                            rating = if (item.movie.rating > 0) String.format("%.1f", item.movie.rating) else null,
                            subtitle = stringResource(R.string.discover_list_count, item.list_count),
                            isResolving = resolvingItemId == item.movie.ids.tmdb,
                            isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.movie.ids.trakt, item.movie.ids.tmdb, MediaType.MOVIE) == true,
                            isWatched = watchlistWatchedIds?.isWatched(item.movie.ids.trakt, item.movie.ids.tmdb, MediaType.MOVIE) == true,
                            onClick = { onMovieClick(item.movie) }
                        )
                    }
                    itemsIndexed(anticipatedShows, key = { index, item -> "anticip_s_${index}_${item.show.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, item ->
                        MovieCard(
                            title = item.show.title,
                            posterPath = item.show.posterPath,
                            year = if (item.show.year > 0) item.show.year.toString() else "",
                            rating = if (item.show.rating > 0) String.format("%.1f", item.show.rating) else null,
                            subtitle = stringResource(R.string.discover_list_count, item.list_count),
                            isResolving = resolvingItemId == item.show.ids.tmdb,
                            isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.show.ids.trakt, item.show.ids.tmdb, MediaType.SHOW) == true,
                            isWatched = watchlistWatchedIds?.isWatched(item.show.ids.trakt, item.show.ids.tmdb, MediaType.SHOW) == true,
                            onClick = { onShowClick(item.show) }
                        )
                    }
                }
            }
        }
    }
}

/** 为你推荐剧集栏目（仅登录用户可见） */
@Composable
internal fun TraktShowRecommendationSection(
    items: List<TraktRecommendationShowResponse>,
    isLoading: Boolean,
    resolvingItemId: Int?,
    totalCount: Int,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    onItemClick: (TraktShow) -> Unit,
    onViewAll: () -> Unit
) {
    if (items.isEmpty() && !isLoading) return // 未登录时无数据不显示
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.discover_trakt_recommendations_shows),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            if (totalCount > 0) {
                Row(
                    modifier = Modifier
                        .clickable { onViewAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.discover_view_all, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        when {
            isLoading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            items.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(items, key = { index, item -> "show_rec_${index}_${item.show.ids.trakt}" }, contentType = { _, _ -> "media_card" }) { _, item ->
                        MovieCard(
                            title = item.show.title,
                            posterPath = item.show.posterPath,
                            year = if (item.show.year > 0) item.show.year.toString() else "",
                            rating = if (item.show.rating > 0) String.format("%.1f", item.show.rating) else null,
                            isResolving = resolvingItemId == item.show.ids.tmdb,
                            onClick = { onItemClick(item.show) }
                        )
                    }
                }
            }
        }
    }
}
