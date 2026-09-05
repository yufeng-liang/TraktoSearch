package com.tracktosearch.ui.screen.discover

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendItem
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedShowResponse
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktRecommendationShowResponse
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingShowResponse
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.ui.animation.fadeSlideIn
import com.tracktosearch.ui.component.AppVisualSurface
import com.tracktosearch.ui.component.DoubanHotCardSkeleton
import com.tracktosearch.ui.component.GlassTabIndicator
import com.tracktosearch.ui.component.SectionHeader
import com.tracktosearch.ui.component.VisualSurfaceKind
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.rememberPosterPrefetch
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.theme.GlassFillDark
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.ui.component.SharedOrigin
import java.util.Locale

// LazyRow 内容类型常量：Compose 依据 contentType 复用滚动复用池中的 item 布局，减少重组与重新测量
private const val CONTENT_TYPE_MEDIA_CARD = "media_card"
private const val CONTENT_TYPE_SKELETON = "skeleton"

@Composable
internal fun TmdbMovieSection(
    title: String,
    movies: List<TmdbSearchResult>,
    isLoading: Boolean,
    error: String?,
    resolvingItemId: Int?,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    /** 本栏目的 origin，见 discoverSectionOrigin；决定海报与详情页的配对。 */
    posterOrigin: String,
    onItemClick: (TmdbSearchResult) -> Unit,
    onRetry: () -> Unit,
    onViewAll: () -> Unit,
    lazyListState: LazyListState? = null
) {
    Column {
        if (title.isNotEmpty()) {
            SectionHeader(
                title = title,
                actionText = if (movies.isNotEmpty()) stringResource(R.string.discover_view_all, movies.size) else null,
                onActionClick = if (movies.isNotEmpty()) onViewAll else null
            )
        }
        when {
            isLoading -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    items(
                        count = 5,
                        key = { index -> "skeleton_$index" },
                        contentType = { CONTENT_TYPE_SKELETON }
                    ) { DoubanHotCardSkeleton() }
                }
            }
            error != null -> {
                ErrorRetryRow(error = error, onRetry = onRetry)
            }
            movies.isEmpty() -> {
                EmptyRow()
            }
            else -> {
                val internalState = rememberLazyListState()
                val effectiveState = lazyListState ?: internalState
                // 与 MovieCard 内保持一致的海报 URL 生成规则，保证预取命中同一缓存
                val posterUrls = remember(movies) {
                    movies.map { movie ->
                        movie.poster_path?.let {
                            if (it.startsWith("http")) it else TmdbImageUrls.build(it)
                        }
                    }
                }
                rememberPosterPrefetch(effectiveState, posterUrls)
                LazyRow(
                    state = effectiveState,
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    itemsIndexed(movies, key = { _, movie -> "tmdb_movie_${movie.id}" }, contentType = { _, _ -> CONTENT_TYPE_MEDIA_CARD }) { index, movie ->
                        Box(modifier = Modifier.fadeSlideIn(index)) {
                            MovieCard(
                                title = movie.title,
                                posterPath = movie.poster_path,
                                year = movie.release_date.take(4),
                                rating = null,
                                isResolving = resolvingItemId == movie.id,
                                isInWatchlist = watchlistWatchedIds?.isInWatchlist(null, movie.id, MediaType.MOVIE) == true,
                                isWatched = watchlistWatchedIds?.isWatched(null, movie.id, MediaType.MOVIE) == true,
                                tmdbId = movie.id,
                                origin = posterOrigin,
                                onClick = { onItemClick(movie) }
                            )
                        }
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
    isLoggedIn: Boolean = true,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    /** 本栏目的 origin，见 discoverSectionOrigin；决定海报与详情页的配对。 */
    posterOrigin: String,
    onItemClick: (TraktMovie) -> Unit,
    onRetry: () -> Unit,
    onViewAll: () -> Unit,
    onLoginClick: () -> Unit = {}
) {
    Column {
        SectionHeader(
            title = title,
            actionText = if (movies.isNotEmpty()) stringResource(R.string.discover_view_all, movies.size) else null,
            onActionClick = if (movies.isNotEmpty()) onViewAll else null
        )
        when {
            !isLoggedIn -> {
                LoginUnlockCard(onLoginClick = onLoginClick)
            }
            isLoading -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    items(
                        count = 5,
                        key = { index -> "skeleton_$index" },
                        contentType = { CONTENT_TYPE_SKELETON }
                    ) { DoubanHotCardSkeleton() }
                }
            }
            error != null -> {
                ErrorRetryRow(error = error, onRetry = onRetry)
            }
            movies.isEmpty() -> {
                EmptyRow()
            }
            else -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    itemsIndexed(movies, key = { _, movie -> "trakt_movie_${movie.ids.trakt}_${movie.ids.tmdb}" }, contentType = { _, _ -> CONTENT_TYPE_MEDIA_CARD }) { index, movie ->
                        Box(modifier = Modifier.fadeSlideIn(index)) {
                            MovieCard(
                                title = movie.title,
                                posterPath = movie.posterPath,
                                year = if (movie.year > 0) movie.year.toString() else "",
                                rating = if (movie.rating > 0)
                                    String.format("%.1f", movie.rating) else null,
                                isResolving = resolvingItemId == movie.ids.tmdb,
                                isInWatchlist = watchlistWatchedIds?.isInWatchlist(movie.ids.trakt, movie.ids.tmdb, MediaType.MOVIE) == true,
                                isWatched = watchlistWatchedIds?.isWatched(movie.ids.trakt, movie.ids.tmdb, MediaType.MOVIE) == true,
                                tmdbId = movie.ids.tmdb,
                                origin = posterOrigin,
                                onClick = { onItemClick(movie) }
                            )
                        }
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
    error: String? = null,
    /** 本栏目的 origin，见 discoverSectionOrigin；决定海报与详情页的配对。 */
    posterOrigin: String,
    onItemClick: (TraktMovie) -> Unit,
    onViewAll: () -> Unit,
    onRetry: () -> Unit = {}
) {
    Column {
        SectionHeader(
            title = stringResource(R.string.discover_trakt_trending_movies),
            actionText = if (totalCount > 0) stringResource(R.string.discover_view_all, totalCount) else null,
            onActionClick = if (totalCount > 0) onViewAll else null
        )
        when {
            isLoading -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    items(
                        count = 5,
                        key = { index -> "skeleton_$index" },
                        contentType = { CONTENT_TYPE_SKELETON }
                    ) { DoubanHotCardSkeleton() }
                }
            }
            error != null -> ErrorRetryRow(error = error, onRetry = onRetry)
            items.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    itemsIndexed(items, key = { _, item -> "trending_movie_${item.movie.ids.trakt}" }, contentType = { _, _ -> CONTENT_TYPE_MEDIA_CARD }) { index, item ->
                        Box(modifier = Modifier.fadeSlideIn(index)) {
                            MovieCard(
                                title = item.movie.title,
                                posterPath = item.movie.posterPath,
                                year = if (item.movie.year > 0) item.movie.year.toString() else "",
                                rating = if (item.movie.rating > 0) String.format("%.1f", item.movie.rating) else null,
                                subtitle = stringResource(R.string.discover_watchers, item.watchers),
                                isResolving = resolvingItemId == item.movie.ids.tmdb,
                                isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.movie.ids.trakt, item.movie.ids.tmdb, MediaType.MOVIE) == true,
                                isWatched = watchlistWatchedIds?.isWatched(item.movie.ids.trakt, item.movie.ids.tmdb, MediaType.MOVIE) == true,
                                tmdbId = item.movie.ids.tmdb,
                                origin = posterOrigin,
                                onClick = { onItemClick(item.movie) }
                            )
                        }
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
    error: String? = null,
    /** 本栏目的 origin，见 discoverSectionOrigin；决定海报与详情页的配对。 */
    posterOrigin: String,
    onItemClick: (TraktShow) -> Unit,
    onViewAll: () -> Unit,
    onRetry: () -> Unit = {}
) {
    Column {
        SectionHeader(
            title = stringResource(R.string.discover_trakt_trending_shows),
            actionText = if (totalCount > 0) stringResource(R.string.discover_view_all, totalCount) else null,
            onActionClick = if (totalCount > 0) onViewAll else null
        )
        when {
            isLoading -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    items(
                        count = 5,
                        key = { index -> "skeleton_$index" },
                        contentType = { CONTENT_TYPE_SKELETON }
                    ) { DoubanHotCardSkeleton() }
                }
            }
            error != null -> ErrorRetryRow(error = error, onRetry = onRetry)
            items.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    itemsIndexed(items, key = { _, item -> "show_rec_${item.show.ids.trakt}" }, contentType = { _, _ -> CONTENT_TYPE_MEDIA_CARD }) { index, item ->
                        Box(modifier = Modifier.fadeSlideIn(index)) {
                            MovieCard(
                                title = item.show.title,
                                posterPath = item.show.posterPath,
                                year = if (item.show.year > 0) item.show.year.toString() else "",
                                rating = if (item.show.rating > 0) String.format("%.1f", item.show.rating) else null,
                                subtitle = stringResource(R.string.discover_watchers, item.watchers),
                                isResolving = resolvingItemId == item.show.ids.tmdb,
                                isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.show.ids.trakt, item.show.ids.tmdb, MediaType.SHOW) == true,
                                isWatched = watchlistWatchedIds?.isWatched(item.show.ids.trakt, item.show.ids.tmdb, MediaType.SHOW) == true,
                                tmdbId = item.show.ids.tmdb,
                                origin = posterOrigin,
                                onClick = { onItemClick(item.show) }
                            )
                        }
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
    error: String? = null,
    /** 本栏目的 origin，见 discoverSectionOrigin；决定海报与详情页的配对。 */
    posterOrigin: String,
    onMovieClick: (TraktMovie) -> Unit,
    onShowClick: (TraktShow) -> Unit,
    onViewAll: () -> Unit,
    onRetry: () -> Unit = {}
) {
    Column {
        SectionHeader(
            title = stringResource(R.string.discover_trakt_anticipated),
            actionText = if (totalCount > 0) stringResource(R.string.discover_view_all, totalCount) else null,
            onActionClick = if (totalCount > 0) onViewAll else null
        )
        when {
            isLoading -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    items(
                        count = 5,
                        key = { index -> "skeleton_$index" },
                        contentType = { CONTENT_TYPE_SKELETON }
                    ) { DoubanHotCardSkeleton() }
                }
            }
            error != null -> ErrorRetryRow(error = error, onRetry = onRetry)
            anticipatedMovies.isEmpty() && anticipatedShows.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    // 先展示电影，再展示剧集
                    itemsIndexed(anticipatedMovies, key = { _, item -> "anticip_m_${item.movie.ids.trakt}" }, contentType = { _, _ -> CONTENT_TYPE_MEDIA_CARD }) { index, item ->
                        Box(modifier = Modifier.fadeSlideIn(index)) {
                            MovieCard(
                                title = item.movie.title,
                                posterPath = item.movie.posterPath,
                                year = if (item.movie.year > 0) item.movie.year.toString() else "",
                                rating = if (item.movie.rating > 0) String.format("%.1f", item.movie.rating) else null,
                                subtitle = stringResource(R.string.discover_list_count, item.list_count),
                                isResolving = resolvingItemId == item.movie.ids.tmdb,
                                isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.movie.ids.trakt, item.movie.ids.tmdb, MediaType.MOVIE) == true,
                                isWatched = watchlistWatchedIds?.isWatched(item.movie.ids.trakt, item.movie.ids.tmdb, MediaType.MOVIE) == true,
                                tmdbId = item.movie.ids.tmdb,
                                origin = SharedOrigin.of(posterOrigin, "movie"),
                                onClick = { onMovieClick(item.movie) }
                            )
                        }
                    }
                    itemsIndexed(anticipatedShows, key = { _, item -> "anticip_s_${item.show.ids.trakt}" }, contentType = { _, _ -> CONTENT_TYPE_MEDIA_CARD }) { index, item ->
                        Box(modifier = Modifier.fadeSlideIn(anticipatedMovies.size + index)) {
                            MovieCard(
                                title = item.show.title,
                                posterPath = item.show.posterPath,
                                year = if (item.show.year > 0) item.show.year.toString() else "",
                                rating = if (item.show.rating > 0) String.format("%.1f", item.show.rating) else null,
                                subtitle = stringResource(R.string.discover_list_count, item.list_count),
                                isResolving = resolvingItemId == item.show.ids.tmdb,
                                isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.show.ids.trakt, item.show.ids.tmdb, MediaType.SHOW) == true,
                                isWatched = watchlistWatchedIds?.isWatched(item.show.ids.trakt, item.show.ids.tmdb, MediaType.SHOW) == true,
                                tmdbId = item.show.ids.tmdb,
                                origin = SharedOrigin.of(posterOrigin, "show"),
                                onClick = { onShowClick(item.show) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 为你推荐剧集栏目（访客模式显示登录解锁卡片，登录后展示个性化推荐） */
@Composable
internal fun TraktShowRecommendationSection(
    items: List<TraktRecommendationShowResponse>,
    isLoading: Boolean,
    resolvingItemId: Int?,
    totalCount: Int,
    isLoggedIn: Boolean = true,
    watchlistWatchedIds: TraktRepository.WatchlistWatchedIds? = null,
    error: String? = null,
    /** 本栏目的 origin，见 discoverSectionOrigin；决定海报与详情页的配对。 */
    posterOrigin: String,
    onItemClick: (TraktShow) -> Unit,
    onViewAll: () -> Unit,
    onRetry: () -> Unit = {},
    onLoginClick: () -> Unit = {}
) {
    // 未登录：显示登录解锁卡片（不早返回）
    // 已登录但无数据且无错误且非加载中：隐藏栏目（保持原行为）
    if (isLoggedIn && items.isEmpty() && !isLoading && error == null) return
    Column {
        SectionHeader(
            title = stringResource(R.string.discover_trakt_recommendations_shows),
            actionText = if (totalCount > 0) stringResource(R.string.discover_view_all, totalCount) else null,
            onActionClick = if (totalCount > 0) onViewAll else null
        )
        when {
            !isLoggedIn -> {
                LoginUnlockCard(onLoginClick = onLoginClick)
            }
            isLoading -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    items(
                        count = 5,
                        key = { index -> "skeleton_$index" },
                        contentType = { CONTENT_TYPE_SKELETON }
                    ) { DoubanHotCardSkeleton() }
                }
            }
            error != null -> ErrorRetryRow(error = error, onRetry = onRetry)
            items.isEmpty() -> EmptyRow()
            else -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    itemsIndexed(items, key = { _, item -> "show_rec_${item.show.ids.trakt}" }, contentType = { _, _ -> CONTENT_TYPE_MEDIA_CARD }) { index, item ->
                        Box(modifier = Modifier.fadeSlideIn(index)) {
                            MovieCard(
                                title = item.show.title,
                                posterPath = item.show.posterPath,
                                year = if (item.show.year > 0) item.show.year.toString() else "",
                                rating = if (item.show.rating > 0) String.format("%.1f", item.show.rating) else null,
                                isResolving = resolvingItemId == item.show.ids.tmdb,
                                isInWatchlist = watchlistWatchedIds?.isInWatchlist(item.show.ids.trakt, item.show.ids.tmdb, MediaType.SHOW) == true,
                                isWatched = watchlistWatchedIds?.isWatched(item.show.ids.trakt, item.show.ids.tmdb, MediaType.SHOW) == true,
                                tmdbId = item.show.ids.tmdb,
                                origin = posterOrigin,
                                onClick = { onItemClick(item.show) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 通用「登录解锁」引导卡片（Trakt 推荐栏目用）。
 *
 * 与豆瓣「猜你喜欢」的绿色卡片区分：采用紫蓝色渐变，避免视觉重复。
 * 卡片样式（圆角、按压回弹、文案层级）与豆瓣登录卡片保持一致。
 */
@Composable
internal fun LoginUnlockCard(onLoginClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "login_unlock_scale"
    )
    val shape = RoundedCornerShape(20.dp)
    val gradient = remember { DiscoverTraktLoginGradient.toBrush() }
    AppVisualSurface(
        kind = VisualSurfaceKind.Content,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            // OAuth 不算外跳：Custom Tab 只是拿 code 的管道，授权完带着 token 从 deep link
            // 回到本应用，用户的意图是「登录」。判据是回不回来 —— 单纯拉浏览器看个网页才静默。
            .hapticClickable(
                interactionSource = interactionSource,
                indication = null,
                // 整张卡就是这一栏唯一的操作、文案是加粗的「去登录 Trakt」，
                // 与豆瓣那张同样式的卡同档
                semantic = HapticSemantic.TAP,
                onClick = onLoginClick
            ),
        shape = shape,
        backgroundColor = Color.Transparent,
        borderColor = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.30f),
                    shape = shape
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.discover_trakt_recommendations_login_prompt),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = stringResource(R.string.discover_trakt_recommendations_login_button),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
        }
    }
}

/**
 * 豆瓣登录引导卡片（未登录 / Cookie 失效两种状态共用）。
 *
 * 与 Trakt 的 [LoginUnlockCard] 区分：绿色渐变。promptText 区分
 * 「未登录」与「Cookie 已失效请重新登录」两种文案，动作都是跳豆瓣登录页。
 */
@Composable
private fun DoubanLoginGuideCard(promptText: String, onLoginClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "douban_recommend_login_scale"
    )
    val shape = RoundedCornerShape(20.dp)
    val gradient = remember { DiscoverDoubanLoginGradient.toBrush() }
    AppVisualSurface(
        kind = VisualSurfaceKind.Content,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .hapticClickable(
                interactionSource = interactionSource,
                indication = null,
                // 整张卡就是这一栏唯一的操作、文案是加粗的「去登录」，按带文字的主操作给
                semantic = HapticSemantic.TAP,
                onClick = onLoginClick
            ),
        shape = shape,
        backgroundColor = Color.Transparent,
        borderColor = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.30f),
                    shape = shape
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = promptText,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = stringResource(R.string.discover_douban_recommend_login_button),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
        }
    }
}

/**
 * 豆瓣「猜你喜欢」栏目
 *
 * 未登录豆瓣时显示引导登录卡片；登录后展示电影/电视剧 Tab 切换 + 个性化推荐列表。
 * 卡片 subtitle 展示推荐理由（reasonTags 用 " · " 连接）。
 */
@Composable
internal fun DoubanRecommendSection(
    state: DoubanRecommendState,
    resolvingItemId: String?,
    /** 本栏目的 origin，见 discoverSectionOrigin；决定海报与详情页的配对。 */
    posterOrigin: String,
    onItemClick: (DoubanRecommendItem) -> Unit,
    onRetry: () -> Unit,
    onLoginClick: () -> Unit,
    onTabSelected: (RecommendTab) -> Unit
) {
    Column {
        // 栏目标题 + Tab 切换条（同一行）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.discover_douban_recommend),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (state is DoubanRecommendState.Success) {
                CapsuleTabSelector(
                    tabs = listOf(
                        stringResource(R.string.discover_douban_recommend_movie),
                        stringResource(R.string.discover_douban_recommend_tv)
                    ),
                    selectedIndex = if (state.currentTab == RecommendTab.TV) 1 else 0,
                    onTabSelected = { index ->
                        onTabSelected(if (index == 0) RecommendTab.MOVIE else RecommendTab.TV)
                    }
                )
            }
        }

        when (state) {
            is DoubanRecommendState.NotLoggedIn -> {
                // 引导登录卡片：复刻底部「去影视筛选页」卡片样式（仅渐变配色不同）
                DoubanLoginGuideCard(
                    promptText = stringResource(R.string.discover_douban_recommend_login_prompt),
                    onLoginClick = onLoginClick
                )
            }

            is DoubanRecommendState.CookieInvalid -> {
                // Cookie 失效引导：样式同登录卡片，文案改为「请重新登录」提示（登录态未清除）
                DoubanLoginGuideCard(
                    promptText = stringResource(R.string.douban_writeback_cookie_expired),
                    onLoginClick = onLoginClick
                )
            }

            is DoubanRecommendState.Loading -> {
                LazyRow(
                    modifier = Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)
                ) {
                    items(
                        count = 5,
                        key = { index -> "skeleton_$index" },
                        contentType = { CONTENT_TYPE_SKELETON }
                    ) { DoubanHotCardSkeleton() }
                }
            }

            is DoubanRecommendState.Error -> {
                ErrorRetryRow(error = state.message, onRetry = onRetry)
            }

            is DoubanRecommendState.Success -> {
                val items = if (state.currentTab == RecommendTab.MOVIE) state.movieItems else state.tvItems
                if (items.isEmpty()) {
                    EmptyRow()
                } else {
                    // 电影/电视剧各自独立的滚动状态，互不影响
                    val movieListState = rememberLazyListState()
                    val tvListState = rememberLazyListState()
                    val listState = if (state.currentTab == RecommendTab.MOVIE) movieListState else tvListState
                    // 与 MovieCard 内 posterPath 取值规则一致，预取命中同一缓存
                    val posterUrls = remember(items) {
                        items.map { it.pic?.normal ?: it.pic?.large ?: it.cover }
                    }
                    // 豆瓣图尺寸不定（pic.normal 常见 480 宽），必须显式对齐 MovieCard 的
                    // ImageRequest.size(342)：Coil 2 无 transformation 时不把 size 并入内存缓存 key，
                    // 预取先落地就会把原始尺寸的大位图塞进缓存让卡片复用，白占约一倍内存。
                    rememberPosterPrefetch(listState, posterUrls, decodeSizePx = 342)
                    LazyRow(
                        state = listState,
                        modifier = Modifier,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 5.dp)
                    ) {
                        itemsIndexed(
                            items,
                            key = { _, item -> "douban_rec_${state.currentTab.name.lowercase()}_${item.id}" },
                            contentType = { _, _ -> CONTENT_TYPE_MEDIA_CARD }
                        ) { index, item ->
                            Box(modifier = Modifier.fadeSlideIn(index)) {
                                MovieCard(
                                    title = item.title,
                                    posterPath = item.pic?.normal ?: item.pic?.large ?: item.cover,
                                    year = item.year ?: "",
                                    rating = item.rating?.value?.let { String.format(Locale.US, "%.1f", it) },
                                    subtitle = item.reasonTags?.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                                    isResolving = resolvingItemId == item.id,
                                    isInWatchlist = false,
                                    isWatched = false,
                                    tmdbId = 0,
                                    isDoubanRating = true,
                                    origin = posterOrigin,
                                    onClick = { onItemClick(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 通用胶囊 Tab 分段器（药丸形滑块指示器）
 *
 * 滑块贴合无留白，宽度动态测量文字，高度 31dp。
 * 用于发现页「猜你喜欢」电影/电视剧切换、「趋势」今日/本周切换等。
 */
@Composable
internal fun CapsuleTabSelector(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    sizeMultiplier: Float = 1f
) {
    if (tabs.isEmpty()) return
    val tabPadding = (12 * sizeMultiplier).dp
    val tabHeight = (31 * sizeMultiplier).dp
    val fontSize = (13 * sizeMultiplier).sp
    // 用 TextMeasurer 同步测量文字宽度，避免 onTextLayout 异步回调导致宽度跳变
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tabWidths = tabs.map { label ->
        remember(label, sizeMultiplier) {
            val widthPx = textMeasurer.measure(
                text = label,
                style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.SemiBold)
            ).size.width
            with(density) { widthPx.toDp() + tabPadding * 2 }
        }
    }
    val capsuleWidth = tabWidths.fold(0.dp) { acc, w -> acc + w }
    // 滑块偏移 = 选中项之前所有 Tab 宽度之和
    val targetOffset = tabWidths.take(selectedIndex).fold(0.dp) { acc, w -> acc + w }
    val targetWidth = tabWidths.getOrElse(selectedIndex) { 0.dp }
    val indicatorOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = tween(220),
        label = "capsule_tab_offset"
    )
    val indicatorWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(220),
        label = "capsule_tab_width"
    )

    val isDark = isAppDarkTheme()
    val capsuleShape = RoundedCornerShape(50)
    val primaryColor = MaterialTheme.colorScheme.primary
    // 缓存不依赖参数的 Brush/Shadow 对象，避免滑块动画期间每次重组重建（提升滑动流畅度）
    val activeGradient = remember(primaryColor) {
        Brush.linearGradient(
            listOf(primaryColor, primaryColor.copy(alpha = 0.85f))
        )
    }
    val indicatorShadowOuter = remember(primaryColor) {
        Shadow(
            radius = 5.dp,
            color = primaryColor.copy(alpha = 0.30f),
            offset = DpOffset(3.dp, 3.dp)
        )
    }
    val indicatorShadowHighlight = remember {
        Shadow(
            radius = 4.dp,
            color = Color.White.copy(alpha = 0.40f),
            offset = DpOffset((-1).dp, (-1).dp)
        )
    }
    val indicatorShadowInner = remember {
        Shadow(
            radius = 4.dp,
            color = Color.White.copy(alpha = 0.25f),
            offset = DpOffset((-1).dp, (-1).dp)
        )
    }
    Box(
        modifier = Modifier
            .height(tabHeight)
            .width(capsuleWidth)
            .clip(capsuleShape)
            .background(
                if (isDark) GlassFillDark else Color.White.copy(alpha = 0.35f)
            )
    ) {
        // Glass 使用独立指示器；Blur 才保留旧拟态药丸。
        if (LocalVisualEffectMode.current == VisualEffectMode.GLASS) {
            GlassTabIndicator(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(indicatorWidth)
                    .fillMaxHeight()
                    .padding(2.dp),
                isDark = isDark,
                shape = capsuleShape
            )
        } else {
            Box(
                modifier = Modifier
                .offset(x = indicatorOffset)
                .width(indicatorWidth)
                .fillMaxHeight()
                .padding(horizontal = 2.dp, vertical = 2.dp)
                .dropShadow(
                    shape = capsuleShape,
                    shadow = indicatorShadowOuter
                )
                .dropShadow(
                    shape = capsuleShape,
                    shadow = indicatorShadowHighlight
                )
                .clip(capsuleShape)
                .background(activeGradient)
                .innerShadow(
                    shape = capsuleShape,
                    shadow = indicatorShadowInner
                )
                )
            }
        // Tab 文字
        Row(modifier = Modifier.fillMaxHeight()) {
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .width(tabWidths[index])
                        .fillMaxHeight()
                        // 一组里只能亮一个，滑块只是挪一格，没有「关掉」这回事 → 刻度感
                        .hapticClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            semantic = HapticSemantic.SEGMENT_TICK
                        ) { onTabSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = if (selected && LocalVisualEffectMode.current != VisualEffectMode.GLASS) {
                            MaterialTheme.colorScheme.onPrimary
                        }
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
