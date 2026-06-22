package com.tracktosearch.ui.screen.traktsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import com.tracktosearch.data.remote.trakt.dto.TraktSearchResult
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class TraktSearchUiItem(
    val traktId: Int = 0,
    val tmdbId: Int = 0,
    val title: String = "",
    val displayTitle: String = "",
    val year: Int? = null,
    val genres: String = "",
    val posterUrl: String? = null,
    val imdbId: String = "",
    val traktRating: Double = 0.0
)

data class TraktSearchUiState(
    val query: String = "",
    val type: MediaType = MediaType.MOVIE,
    val results: List<TraktSearchUiItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false
)

@HiltViewModel
class TraktSearchViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TraktSearchUiState())
    val uiState: StateFlow<TraktSearchUiState> = _uiState.asStateFlow()

    fun initSearch(query: String, type: MediaType) {
        _uiState.value = TraktSearchUiState(query = query, type = type)
        search(query, type)
    }

    fun search(query: String, type: MediaType? = null) {
        val searchType = type ?: _uiState.value.type
        if (query.isBlank()) return
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                query = query,
                type = searchType,
                isLoading = true,
                error = null,
                hasSearched = true
            )

            val result = when (searchType) {
                MediaType.MOVIE -> traktRepository.searchMovies(query)
                MediaType.SHOW -> traktRepository.searchShows(query)
            }

            result.onSuccess { searchResults ->
                val uiItems = searchResults.map { item ->
                    async { enrichSearchResult(item, searchType) }
                }.awaitAll()
                _uiState.value = _uiState.value.copy(
                    results = uiItems,
                    isLoading = false
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "搜索失败"
                )
            }
        }
    }

    private suspend fun enrichSearchResult(result: TraktSearchResult, type: MediaType): TraktSearchUiItem {
        return when (type) {
            MediaType.MOVIE -> {
                val movie = result.movie ?: return TraktSearchUiItem()
                if (movie.ids.tmdb <= 0) {
                    TraktSearchUiItem(
                        traktId = movie.ids.trakt,
                        tmdbId = 0,
                        title = movie.title,
                        displayTitle = movie.title,
                        year = movie.year,
                        genres = movie.genres.joinToString(" · "),
                        posterUrl = movie.posterPath,
                        imdbId = movie.ids.imdb,
                        traktRating = movie.rating
                    )
                } else {
                    val enrichment = tmdbRepository.enrichMovie(movie.ids.tmdb, movie.title, movie.year)
                    TraktSearchUiItem(
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
            }
            MediaType.SHOW -> {
                val show = result.show ?: return TraktSearchUiItem()
                if (show.ids.tmdb <= 0) {
                    TraktSearchUiItem(
                        traktId = show.ids.trakt,
                        tmdbId = 0,
                        title = show.title,
                        displayTitle = show.title,
                        year = show.year,
                        genres = show.genres.joinToString(" · "),
                        posterUrl = null,
                        imdbId = show.ids.imdb,
                        traktRating = show.rating
                    )
                } else {
                    val enrichment = tmdbRepository.enrichTv(show.ids.tmdb, show.title, show.year)
                    TraktSearchUiItem(
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
            }
        }
    }
}
