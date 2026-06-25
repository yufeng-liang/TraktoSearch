package com.tracktosearch.ui.screen.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.local.db.MediaItemEntity
import com.tracktosearch.data.local.db.OfflineCacheManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class MediaUiItem(
    val traktId: Int,
    val tmdbId: Int,
    val title: String,
    val displayTitle: String,
    val year: Int?,
    val genres: String,
    val posterUrl: String?,
    val imdbId: String = "",
    val traktRating: Double = 0.0,
    val listedAt: String = ""
)

@Immutable
data class WatchlistUiState(
    val isLoadingMovies: Boolean = false,
    val isLoadingShows: Boolean = false,
    val movies: List<MediaUiItem> = emptyList(),
    val shows: List<MediaUiItem> = emptyList(),
    val moviesError: String? = null,
    val showsError: String? = null,
    val moviesLoaded: Boolean = false,
    val showsLoaded: Boolean = false,
    val hasMoreMovies: Boolean = true,
    val hasMoreShows: Boolean = true,
    val moviePage: Int = 1,
    val showPage: Int = 1,
    val tmdbUnavailable: Boolean = false,
    // 已看历史
    val historyMovies: List<MediaUiItem> = emptyList(),
    val historyShows: List<MediaUiItem> = emptyList(),
    val isLoadingHistoryMovies: Boolean = false,
    val isLoadingHistoryShows: Boolean = false,
    val historyMoviesError: String? = null,
    val historyShowsError: String? = null,
    val historyMoviesLoaded: Boolean = false,
    val historyShowsLoaded: Boolean = false
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    private val offlineCacheManager: OfflineCacheManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    private val maxRetries = 2

    fun loadMovies(forceReload: Boolean = false, silent: Boolean = false) {
        if (!forceReload && _uiState.value.moviesLoaded && _uiState.value.movies.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingMovies) return  // 防止并发重复请求
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingMovies = !silent,
                moviesError = if (silent) _uiState.value.moviesError else null
            )
            val result = retryIO(maxRetries) { traktRepository.getMovieWatchlist(page = _uiState.value.moviePage) }
            result.onSuccess { (items, totalPages) ->
                // 如果 forceReload 且数据与现有列表完全相同，跳过 TMDB 富化和 UI 更新
                if (forceReload) {
                    val newIds = items.map { it.movie.ids.trakt }.toSet()
                    val oldIds = _uiState.value.movies.map { it.traktId }.toSet()
                    if (newIds == oldIds && items.size == _uiState.value.movies.size) {
                        _uiState.value = _uiState.value.copy(
                            isLoadingMovies = if (silent) _uiState.value.isLoadingMovies else false,
                            hasMoreMovies = _uiState.value.moviePage < totalPages,
                            moviePage = _uiState.value.moviePage + 1
                        )
                        return@launch
                    }
                    // 静默刷新时保留旧列表避免闪烁；非静默时清空旧列表避免新旧数据混合
                    if (!silent) {
                        _uiState.value = _uiState.value.copy(movies = emptyList())
                    }
                }
                // 每个 item 的 TMDB enrich 完成就立即显示，不等整批
                val deferredItems = items.mapIndexed { index, item ->
                    async {
                        val uiItem = enrichMediaItem(
                            traktId = item.movie.ids.trakt, tmdbId = item.movie.ids.tmdb,
                            title = item.movie.title, year = item.movie.year,
                            imdbId = item.movie.ids.imdb, rating = item.movie.rating,
                            listedAt = item.listed_at, isMovie = true
                        )
                        // 非静默模式每完成一个就更新 UI；静默模式等整批完成后再一次性替换，避免闪烁
                        if (!silent) {
                            val currentMovies = _uiState.value.movies.toMutableList()
                            // 按原始顺序插入到对应位置
                            if (index < currentMovies.size) {
                                currentMovies[index] = uiItem
                            } else {
                                // 补齐中间空位
                                while (currentMovies.size < index) {
                                    val p = items[currentMovies.size].movie
                                    currentMovies.add(createPlaceholder(p.ids.trakt, p.ids.tmdb, p.title, p.year, p.ids.imdb, p.rating, items[currentMovies.size].listed_at))
                                }
                                currentMovies.add(uiItem)
                            }
                            _uiState.value = _uiState.value.copy(movies = currentMovies.toList())
                        }
                        uiItem
                    }
                }
                val uiItems = deferredItems.awaitAll()
                val tmdbFailed = uiItems.any { it.posterUrl == null && it.displayTitle == it.title }
                _uiState.value = _uiState.value.copy(
                    movies = uiItems,
                    isLoadingMovies = if (silent) _uiState.value.isLoadingMovies else false,
                    moviesLoaded = true,
                    hasMoreMovies = _uiState.value.moviePage < totalPages,
                    moviePage = _uiState.value.moviePage + 1,
                    tmdbUnavailable = _uiState.value.tmdbUnavailable || tmdbFailed
                )
                // 写入离线缓存（仅首页）
                if (_uiState.value.moviePage == 2) {
                    val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_WATCHLIST_MOVIE) }
                    offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_WATCHLIST_MOVIE, entities)
                }
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_WATCHLIST_MOVIE)
                _uiState.value = _uiState.value.copy(
                    isLoadingMovies = if (silent) _uiState.value.isLoadingMovies else false,
                    moviesLoaded = true,
                    movies = if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() } else _uiState.value.movies,
                    moviesError = if (cached.isNotEmpty()) null else (e.message ?: "加载失败")
                )
            }
        }
    }

    fun loadShows(forceReload: Boolean = false, silent: Boolean = false) {
        if (!forceReload && _uiState.value.showsLoaded && _uiState.value.shows.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingShows) return  // 防止并发重复请求
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingShows = !silent,
                showsError = if (silent) _uiState.value.showsError else null
            )
            val result = retryIO(maxRetries) { traktRepository.getShowWatchlist(page = _uiState.value.showPage) }
            result.onSuccess { (items, totalPages) ->
                // 如果 forceReload 且数据与现有列表完全相同，跳过 TMDB 富化和 UI 更新
                if (forceReload) {
                    val newIds = items.map { it.show.ids.trakt }.toSet()
                    val oldIds = _uiState.value.shows.map { it.traktId }.toSet()
                    if (newIds == oldIds && items.size == _uiState.value.shows.size) {
                        _uiState.value = _uiState.value.copy(
                            isLoadingShows = if (silent) _uiState.value.isLoadingShows else false,
                            hasMoreShows = _uiState.value.showPage < totalPages,
                            showPage = _uiState.value.showPage + 1
                        )
                        return@launch
                    }
                    // 静默刷新时保留旧列表避免闪烁；非静默时清空旧列表避免新旧数据混合
                    if (!silent) {
                        _uiState.value = _uiState.value.copy(shows = emptyList())
                    }
                }
                val deferredItems = items.mapIndexed { index, item ->
                    async {
                        val uiItem = enrichMediaItem(
                            traktId = item.show.ids.trakt, tmdbId = item.show.ids.tmdb,
                            title = item.show.title, year = item.show.year,
                            imdbId = item.show.ids.imdb, rating = item.show.rating,
                            listedAt = item.listed_at, isMovie = false
                        )
                        // 非静默模式每完成一个就更新 UI；静默模式等整批完成后再一次性替换，避免闪烁
                        if (!silent) {
                            val currentShows = _uiState.value.shows.toMutableList()
                            if (index < currentShows.size) {
                                currentShows[index] = uiItem
                            } else {
                                while (currentShows.size < index) {
                                    val s = items[currentShows.size].show
                                    currentShows.add(createPlaceholder(s.ids.trakt, s.ids.tmdb, s.title, s.year, s.ids.imdb, s.rating, items[currentShows.size].listed_at))
                                }
                                currentShows.add(uiItem)
                            }
                            _uiState.value = _uiState.value.copy(shows = currentShows.toList())
                        }
                        uiItem
                    }
                }
                val uiItems = deferredItems.awaitAll()
                val tmdbFailed = uiItems.any { it.posterUrl == null && it.displayTitle == it.title }
                _uiState.value = _uiState.value.copy(
                    shows = uiItems,
                    isLoadingShows = if (silent) _uiState.value.isLoadingShows else false,
                    showsLoaded = true,
                    hasMoreShows = _uiState.value.showPage < totalPages,
                    showPage = _uiState.value.showPage + 1,
                    tmdbUnavailable = _uiState.value.tmdbUnavailable || tmdbFailed
                )
                // 写入离线缓存（仅首页）
                if (_uiState.value.showPage == 2) {
                    val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_WATCHLIST_SHOW) }
                    offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_WATCHLIST_SHOW, entities)
                }
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_WATCHLIST_SHOW)
                _uiState.value = _uiState.value.copy(
                    isLoadingShows = if (silent) _uiState.value.isLoadingShows else false,
                    showsLoaded = true,
                    shows = if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() } else _uiState.value.shows,
                    showsError = if (cached.isNotEmpty()) null else (e.message ?: "加载失败")
                )
            }
        }
    }

    fun loadHistoryMovies(forceReload: Boolean = false) {
        if (!forceReload && _uiState.value.historyMoviesLoaded && _uiState.value.historyMovies.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingHistoryMovies) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingHistoryMovies = true,
                historyMoviesError = null
            )
            val result = retryIO(maxRetries) { traktRepository.getMovieHistory() }
            result.onSuccess { (items, _) ->
                if (forceReload) {
                    _uiState.value = _uiState.value.copy(historyMovies = emptyList())
                }
                // 历史记录可能包含同一部电影的多次观看，按 traktId 去重
                val dedupedItems = items.distinctBy { it.movie.ids.trakt }
                val deferredItems = dedupedItems.map { item ->
                    async {
                        enrichMediaItem(
                            traktId = item.movie.ids.trakt, tmdbId = item.movie.ids.tmdb,
                            title = item.movie.title, year = item.movie.year,
                            imdbId = item.movie.ids.imdb, rating = item.movie.rating,
                            listedAt = item.listed_at, isMovie = true
                        )
                    }
                }
                val uiItems = deferredItems.awaitAll()
                val tmdbFailed = uiItems.any { it.posterUrl == null && it.displayTitle == it.title }
                _uiState.value = _uiState.value.copy(
                    historyMovies = uiItems,
                    isLoadingHistoryMovies = false,
                    historyMoviesLoaded = true,
                    tmdbUnavailable = _uiState.value.tmdbUnavailable || tmdbFailed
                )
                // 写入离线缓存
                val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_HISTORY_MOVIE) }
                offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_HISTORY_MOVIE, entities)
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_HISTORY_MOVIE)
                _uiState.value = _uiState.value.copy(
                    isLoadingHistoryMovies = false,
                    historyMoviesLoaded = true,
                    historyMovies = if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() } else _uiState.value.historyMovies,
                    historyMoviesError = if (cached.isNotEmpty()) null else (e.message ?: "加载失败")
                )
            }
        }
    }

    fun loadHistoryShows(forceReload: Boolean = false) {
        if (!forceReload && _uiState.value.historyShowsLoaded && _uiState.value.historyShows.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingHistoryShows) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingHistoryShows = true,
                historyShowsError = null
            )
            val result = retryIO(maxRetries) { traktRepository.getShowHistory() }
            result.onSuccess { (items, _) ->
                if (forceReload) {
                    _uiState.value = _uiState.value.copy(historyShows = emptyList())
                }
                val dedupedItems = items.distinctBy { it.show.ids.trakt }
                val deferredItems = dedupedItems.map { item ->
                    async {
                        enrichMediaItem(
                            traktId = item.show.ids.trakt, tmdbId = item.show.ids.tmdb,
                            title = item.show.title, year = item.show.year,
                            imdbId = item.show.ids.imdb, rating = item.show.rating,
                            listedAt = item.listed_at, isMovie = false
                        )
                    }
                }
                val uiItems = deferredItems.awaitAll()
                val tmdbFailed = uiItems.any { it.posterUrl == null && it.displayTitle == it.title }
                _uiState.value = _uiState.value.copy(
                    historyShows = uiItems,
                    isLoadingHistoryShows = false,
                    historyShowsLoaded = true,
                    tmdbUnavailable = _uiState.value.tmdbUnavailable || tmdbFailed
                )
                // 写入离线缓存
                val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_HISTORY_SHOW) }
                offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_HISTORY_SHOW, entities)
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_HISTORY_SHOW)
                _uiState.value = _uiState.value.copy(
                    isLoadingHistoryShows = false,
                    historyShowsLoaded = true,
                    historyShows = if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() } else _uiState.value.historyShows,
                    historyShowsError = if (cached.isNotEmpty()) null else (e.message ?: "加载失败")
                )
            }
        }
    }

    private suspend fun enrichMediaItem(
        traktId: Int, tmdbId: Int, title: String, year: Int?,
        imdbId: String, rating: Double, listedAt: String, isMovie: Boolean
    ): MediaUiItem {
        if (tmdbId <= 0) {
            return MediaUiItem(
                traktId = traktId, tmdbId = 0, title = title,
                displayTitle = title, year = year, genres = "",
                posterUrl = null, imdbId = imdbId,
                traktRating = rating, listedAt = listedAt
            )
        }
        if (isMovie) {
            val enrichment = tmdbRepository.enrichMovie(tmdbId, title, year)
            return MediaUiItem(
                traktId = traktId, tmdbId = tmdbId, title = title,
                displayTitle = enrichment.chineseTitle, year = enrichment.year,
                genres = enrichment.genres, posterUrl = enrichment.posterUrl,
                imdbId = imdbId, traktRating = rating, listedAt = listedAt
            )
        } else {
            val enrichment = tmdbRepository.enrichTv(tmdbId, title, year)
            return MediaUiItem(
                traktId = traktId, tmdbId = tmdbId, title = title,
                displayTitle = enrichment.chineseTitle, year = enrichment.year,
                genres = enrichment.genres, posterUrl = enrichment.posterUrl,
                imdbId = imdbId, traktRating = rating, listedAt = listedAt
            )
        }
    }

    private fun createPlaceholder(
        traktId: Int, tmdbId: Int, title: String, year: Int?,
        imdbId: String, rating: Double, listedAt: String
    ): MediaUiItem {
        return MediaUiItem(
            traktId = traktId, tmdbId = tmdbId, title = title,
            displayTitle = title, year = year, genres = "",
            posterUrl = null, imdbId = imdbId,
            traktRating = rating, listedAt = listedAt
        )
    }

    fun refresh() {
        val wasHistoryLoaded = _uiState.value.historyMoviesLoaded || _uiState.value.historyShowsLoaded
        _uiState.value = WatchlistUiState()
        loadMovies(forceReload = true)
        loadShows(forceReload = true)
        if (wasHistoryLoaded) {
            loadHistoryMovies(forceReload = true)
            loadHistoryShows(forceReload = true)
        }
    }

    /** 页面恢复可见时调用：如果之前已加载过，则后台静默刷新，不重置已有数据避免重复拉取 */
    fun refreshIfLoaded(silent: Boolean = false) {
        val state = _uiState.value
        if (state.moviesLoaded || state.showsLoaded) {
            // 只重置分页，保留已有数据避免 UI 闪烁和重复拉取
            _uiState.value = state.copy(
                moviePage = 1,
                showPage = 1,
                hasMoreMovies = true,
                hasMoreShows = true
            )
            loadMovies(forceReload = true, silent = silent)
            loadShows(forceReload = true, silent = silent)
        }
        if (state.historyMoviesLoaded || state.historyShowsLoaded) {
            loadHistoryMovies(forceReload = true)
            loadHistoryShows(forceReload = true)
        }
    }

    fun loadMoreMovies() {
        if (_uiState.value.isLoadingMovies || !_uiState.value.hasMoreMovies) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMovies = true)
            val result = retryIO(maxRetries) { traktRepository.getMovieWatchlist(page = _uiState.value.moviePage) }
            result.onSuccess { (items, totalPages) ->
                val uiItems = items.map { item ->
                    async {
                        enrichMediaItem(
                            traktId = item.movie.ids.trakt, tmdbId = item.movie.ids.tmdb,
                            title = item.movie.title, year = item.movie.year,
                            imdbId = item.movie.ids.imdb, rating = item.movie.rating,
                            listedAt = item.listed_at, isMovie = true
                        )
                    }
                }.awaitAll()
                val tmdbFailed = uiItems.any { it.posterUrl == null && it.displayTitle == it.title }
                _uiState.value = _uiState.value.copy(
                    movies = (_uiState.value.movies + uiItems).distinctBy { it.traktId },
                    isLoadingMovies = false,
                    hasMoreMovies = _uiState.value.moviePage < totalPages,
                    moviePage = _uiState.value.moviePage + 1,
                    tmdbUnavailable = _uiState.value.tmdbUnavailable || tmdbFailed
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoadingMovies = false,
                    moviesError = e.message
                )
            }
        }
    }
}

private suspend fun <T> retryIO(times: Int, block: suspend () -> T): T {
    var currentAttempt = 0
    var lastException: Exception? = null
    while (currentAttempt <= times) {
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            currentAttempt++
            if (currentAttempt <= times) delay(1000L * currentAttempt)
        }
    }
    throw lastException!!
}

// ========== 离线缓存转换扩展 ==========

private fun MediaUiItem.toMediaItemEntity(type: String) = MediaItemEntity(
    traktId = traktId,
    tmdbId = tmdbId,
    type = type,
    title = title,
    displayTitle = displayTitle,
    year = year,
    genres = genres,
    posterUrl = posterUrl,
    imdbId = imdbId,
    traktRating = traktRating,
    listedAt = listedAt
)

private fun MediaItemEntity.toMediaUiItem() = MediaUiItem(
    traktId = traktId,
    tmdbId = tmdbId,
    title = title,
    displayTitle = displayTitle,
    year = year,
    genres = genres,
    posterUrl = posterUrl,
    imdbId = imdbId,
    traktRating = traktRating,
    listedAt = listedAt
)
