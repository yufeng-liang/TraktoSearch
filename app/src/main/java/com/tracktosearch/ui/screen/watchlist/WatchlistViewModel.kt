package com.tracktosearch.ui.screen.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val traktRating: Double = 0.0
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
    val traktRating: Double = 0.0
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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMovies = true, moviesError = null)
            val result = retryIO(maxRetries) { traktRepository.getMovieWatchlist(page = _uiState.value.moviePage) }
            result.onSuccess { (items, totalPages) ->
                val batchSize = 5
                val uiItems = mutableListOf<MovieUiItem>()
                for (i in items.indices step batchSize) {
                    val batch = items.subList(i, minOf(i + batchSize, items.size))
                    val batchResults = batch.map { item -> async { enrichMovieItem(item.movie) } }.awaitAll()
                    uiItems.addAll(batchResults)
                    if (i + batchSize < items.size) {
                        _uiState.value = _uiState.value.copy(
                            movies = _uiState.value.movies + uiItems.toList(),
                            isLoadingMovies = true
                        )
                    }
                }
                val tmdbFailed = uiItems.any { it.posterUrl == null && it.displayTitle == it.title }
                _uiState.value = _uiState.value.copy(
                    movies = _uiState.value.movies + uiItems,
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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingShows = true, showsError = null)
            val result = retryIO(maxRetries) { traktRepository.getShowWatchlist(page = _uiState.value.showPage) }
            result.onSuccess { (items, totalPages) ->
                val batchSize = 5
                val uiItems = mutableListOf<ShowUiItem>()
                for (i in items.indices step batchSize) {
                    val batch = items.subList(i, minOf(i + batchSize, items.size))
                    val batchResults = batch.map { item -> async { enrichShowItem(item.show) } }.awaitAll()
                    uiItems.addAll(batchResults)
                    if (i + batchSize < items.size) {
                        _uiState.value = _uiState.value.copy(
                            shows = _uiState.value.shows + uiItems.toList(),
                            isLoadingShows = true
                        )
                    }
                }
                val tmdbFailed = uiItems.any { it.posterUrl == null && it.displayTitle == it.title }
                _uiState.value = _uiState.value.copy(
                    shows = _uiState.value.shows + uiItems,
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

    private suspend fun enrichMovieItem(movie: com.tracktosearch.data.remote.trakt.dto.TraktMovie): MovieUiItem {
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
                traktRating = movie.rating
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
            traktRating = movie.rating
        )
    }

    private suspend fun enrichShowItem(show: com.tracktosearch.data.remote.trakt.dto.TraktShow): ShowUiItem {
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
                traktRating = show.rating
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
            traktRating = show.rating
        )
    }

    fun refresh() {
        _uiState.value = WatchlistUiState()
        loadMovies(forceReload = true)
        loadShows(forceReload = true)
    }

    /** 页面恢复可见时调用：如果之前已加载过，则静默刷新（重置状态重新拉取） */
    fun refreshIfLoaded() {
        val state = _uiState.value
        if (state.moviesLoaded || state.showsLoaded) {
            _uiState.value = WatchlistUiState()
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
                    movies = _uiState.value.movies + uiItems,
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
