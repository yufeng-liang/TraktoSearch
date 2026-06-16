package com.tracktosearch.ui.screen.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MovieUiItem(
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

data class ShowUiItem(
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

data class WatchlistUiState(
    val isLoadingMovies: Boolean = false,
    val isLoadingShows: Boolean = false,
    val movies: List<MovieUiItem> = emptyList(),
    val shows: List<ShowUiItem> = emptyList(),
    val moviesError: String? = null,
    val showsError: String? = null,
    val moviesLoaded: Boolean = false,
    val showsLoaded: Boolean = false,
    val hasMoreMovies: Boolean = true,
    val hasMoreShows: Boolean = true,
    val moviePage: Int = 1,
    val showPage: Int = 1,
    val tmdbUnavailable: Boolean = false
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    private val maxRetries = 2

    fun loadMovies(forceReload: Boolean = false) {
        if (!forceReload && _uiState.value.moviesLoaded && _uiState.value.movies.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingMovies) return  // 防止并发重复请求
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMovies = true, moviesError = null)
            val result = retryIO(maxRetries) { traktRepository.getMovieWatchlist(page = _uiState.value.moviePage) }
            result.onSuccess { (items, totalPages) ->
                // 如果 forceReload 且数据与现有列表完全相同，跳过 TMDB 富化和 UI 更新
                if (forceReload) {
                    val newIds = items.map { it.movie.ids.trakt }.toSet()
                    val oldIds = _uiState.value.movies.map { it.traktId }.toSet()
                    if (newIds == oldIds && items.size == _uiState.value.movies.size) {
                        _uiState.value = _uiState.value.copy(
                            isLoadingMovies = false,
                            hasMoreMovies = _uiState.value.moviePage < totalPages,
                            moviePage = _uiState.value.moviePage + 1
                        )
                        return@launch
                    }
                    // 数据有变化，先清空旧列表避免新旧数据混合
                    _uiState.value = _uiState.value.copy(movies = emptyList())
                }
                // 每个 item 的 TMDB enrich 完成就立即显示，不等整批
                val deferredItems = items.mapIndexed { index, item ->
                    async {
                        val uiItem = enrichMovieItem(item.movie, item.listed_at)
                        // 每完成一个就更新 UI
                        val currentMovies = _uiState.value.movies.toMutableList()
                        // 按原始顺序插入到对应位置
                        if (index < currentMovies.size) {
                            currentMovies[index] = uiItem
                        } else {
                            // 补齐中间空位
                            while (currentMovies.size < index) currentMovies.add(createPlaceholderMovie(items[currentMovies.size]))
                            currentMovies.add(uiItem)
                        }
                        _uiState.value = _uiState.value.copy(movies = currentMovies.toList())
                        uiItem
                    }
                }
                val uiItems = deferredItems.awaitAll()
                val tmdbFailed = uiItems.any { it.posterUrl == null && it.displayTitle == it.title }
                _uiState.value = _uiState.value.copy(
                    movies = uiItems,
                    isLoadingMovies = false,
                    moviesLoaded = true,
                    hasMoreMovies = _uiState.value.moviePage < totalPages,
                    moviePage = _uiState.value.moviePage + 1,
                    tmdbUnavailable = _uiState.value.tmdbUnavailable || tmdbFailed
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoadingMovies = false,
                    moviesLoaded = true,
                    moviesError = e.message ?: "加载失败"
                )
            }
        }
    }

    fun loadShows(forceReload: Boolean = false) {
        if (!forceReload && _uiState.value.showsLoaded && _uiState.value.shows.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingShows) return  // 防止并发重复请求
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingShows = true, showsError = null)
            val result = retryIO(maxRetries) { traktRepository.getShowWatchlist(page = _uiState.value.showPage) }
            result.onSuccess { (items, totalPages) ->
                // 如果 forceReload 且数据与现有列表完全相同，跳过 TMDB 富化和 UI 更新
                if (forceReload) {
                    val newIds = items.map { it.show.ids.trakt }.toSet()
                    val oldIds = _uiState.value.shows.map { it.traktId }.toSet()
                    if (newIds == oldIds && items.size == _uiState.value.shows.size) {
                        _uiState.value = _uiState.value.copy(
                            isLoadingShows = false,
                            hasMoreShows = _uiState.value.showPage < totalPages,
                            showPage = _uiState.value.showPage + 1
                        )
                        return@launch
                    }
                    // 数据有变化，先清空旧列表避免新旧数据混合
                    _uiState.value = _uiState.value.copy(shows = emptyList())
                }
                val deferredItems = items.mapIndexed { index, item ->
                    async {
                        val uiItem = enrichShowItem(item.show, item.listed_at)
                        val currentShows = _uiState.value.shows.toMutableList()
                        if (index < currentShows.size) {
                            currentShows[index] = uiItem
                        } else {
                            while (currentShows.size < index) currentShows.add(createPlaceholderShow(items[currentShows.size]))
                            currentShows.add(uiItem)
                        }
                        _uiState.value = _uiState.value.copy(shows = currentShows.toList())
                        uiItem
                    }
                }
                val uiItems = deferredItems.awaitAll()
                val tmdbFailed = uiItems.any { it.posterUrl == null && it.displayTitle == it.title }
                _uiState.value = _uiState.value.copy(
                    shows = uiItems,
                    isLoadingShows = false,
                    showsLoaded = true,
                    hasMoreShows = _uiState.value.showPage < totalPages,
                    showPage = _uiState.value.showPage + 1,
                    tmdbUnavailable = _uiState.value.tmdbUnavailable || tmdbFailed
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoadingShows = false,
                    showsLoaded = true,
                    showsError = e.message ?: "加载失败"
                )
            }
        }
    }

    private suspend fun enrichMovieItem(movie: com.tracktosearch.data.remote.trakt.dto.TraktMovie, listedAt: String = ""): MovieUiItem {
        if (movie.ids.tmdb <= 0) {
            return MovieUiItem(
                traktId = movie.ids.trakt,
                tmdbId = 0,
                title = movie.title,
                displayTitle = movie.title,
                year = movie.year,
                genres = "",
                posterUrl = null,
                imdbId = movie.ids.imdb,
                traktRating = movie.rating,
                listedAt = listedAt
            )
        }
        val enrichment = tmdbRepository.enrichMovie(movie.ids.tmdb, movie.title, movie.year)
        return MovieUiItem(
            traktId = movie.ids.trakt,
            tmdbId = movie.ids.tmdb,
            title = movie.title,
            displayTitle = enrichment.chineseTitle,
            year = enrichment.year,
            genres = enrichment.genres,
            posterUrl = enrichment.posterUrl,
            imdbId = movie.ids.imdb,
            traktRating = movie.rating,
            listedAt = listedAt
        )
    }

    private suspend fun enrichShowItem(show: com.tracktosearch.data.remote.trakt.dto.TraktShow, listedAt: String = ""): ShowUiItem {
        if (show.ids.tmdb <= 0) {
            return ShowUiItem(
                traktId = show.ids.trakt,
                tmdbId = 0,
                title = show.title,
                displayTitle = show.title,
                year = show.year,
                genres = "",
                posterUrl = null,
                imdbId = show.ids.imdb,
                traktRating = show.rating,
                listedAt = listedAt
            )
        }
        val enrichment = tmdbRepository.enrichTv(show.ids.tmdb, show.title, show.year)
        return ShowUiItem(
            traktId = show.ids.trakt,
            tmdbId = show.ids.tmdb,
            title = show.title,
            displayTitle = enrichment.chineseTitle,
            year = enrichment.year,
            genres = enrichment.genres,
            posterUrl = enrichment.posterUrl,
            imdbId = show.ids.imdb,
            traktRating = show.rating,
            listedAt = listedAt
        )
    }

    private fun createPlaceholderMovie(item: TraktWatchlistMovieItem): MovieUiItem {
        return MovieUiItem(
            traktId = item.movie.ids.trakt,
            tmdbId = item.movie.ids.tmdb,
            title = item.movie.title,
            displayTitle = item.movie.title,
            year = item.movie.year,
            genres = "",
            posterUrl = null,
            imdbId = item.movie.ids.imdb,
            traktRating = item.movie.rating,
            listedAt = item.listed_at
        )
    }

    private fun createPlaceholderShow(item: TraktWatchlistShowItem): ShowUiItem {
        return ShowUiItem(
            traktId = item.show.ids.trakt,
            tmdbId = item.show.ids.tmdb,
            title = item.show.title,
            displayTitle = item.show.title,
            year = item.show.year,
            genres = "",
            posterUrl = null,
            imdbId = item.show.ids.imdb,
            traktRating = item.show.rating,
            listedAt = item.listed_at
        )
    }

    fun refresh() {
        _uiState.value = WatchlistUiState()
        loadMovies(forceReload = true)
        loadShows(forceReload = true)
    }

    /** 页面恢复可见时调用：如果之前已加载过，则后台静默刷新，不重置已有数据避免重复拉取 */
    fun refreshIfLoaded() {
        val state = _uiState.value
        if (state.moviesLoaded || state.showsLoaded) {
            // 只重置分页，保留已有数据避免 UI 闪烁和重复拉取
            _uiState.value = state.copy(
                moviePage = 1,
                showPage = 1,
                hasMoreMovies = true,
                hasMoreShows = true
            )
            loadMovies(forceReload = true)
            loadShows(forceReload = true)
        }
    }

    fun loadMoreMovies() {
        if (_uiState.value.isLoadingMovies || !_uiState.value.hasMoreMovies) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMovies = true)
            val result = retryIO(maxRetries) { traktRepository.getMovieWatchlist(page = _uiState.value.moviePage) }
            result.onSuccess { (items, totalPages) ->
                val uiItems = items.map { item -> async { enrichMovieItem(item.movie) } }.awaitAll()
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
