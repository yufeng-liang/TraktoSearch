package com.tracktosearch.ui.screen.listdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ListDetailUiState(
    val listName: String = "",
    val items: List<ListDetailItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val error: String? = null
)

data class ListDetailItem(
    val rank: Int,
    val type: MediaType,
    val traktId: Int,
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val imdbId: String = "",
    val traktRating: Double = 0.0
)

@HiltViewModel
class TraktListDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository
) : ViewModel() {

    private val slug: String = savedStateHandle.get<String>("slug") ?: ""
    private val listName: String = savedStateHandle.get<String>("listName") ?: ""

    private val _uiState = MutableStateFlow(ListDetailUiState(listName = listName))
    val uiState: StateFlow<ListDetailUiState> = _uiState

    init {
        if (slug.isNotBlank()) {
            loadItems(page = 1)
        }
    }

    fun retry() {
        loadItems(page = 1)
    }

    fun loadMore() {
        val current = _uiState.value
        if (current.isLoadingMore || !current.hasMore) return
        loadItems(page = current.currentPage + 1)
    }

    private fun loadItems(page: Int) {
        viewModelScope.launch {
            if (page == 1) {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            } else {
                _uiState.value = _uiState.value.copy(isLoadingMore = true)
            }

            val result = traktRepository.getListItems(slug, limit = 20, page = page)
            result.onSuccess { rawItems ->
                val enhanced = rawItems.mapNotNull { item ->
                    when (item.type) {
                        "movie" -> item.movie?.let { enhanceMovie(item.rank, it) }
                        "show" -> item.show?.let { enhanceShow(item.rank, it) }
                        else -> null
                    }
                }
                val current = _uiState.value
                _uiState.value = current.copy(
                    items = if (page == 1) enhanced else current.items + enhanced,
                    isLoading = false,
                    isLoadingMore = false,
                    currentPage = page,
                    hasMore = rawItems.size >= 20,
                    error = null
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = e.message
                )
            }
        }
    }

    private suspend fun enhanceMovie(rank: Int, movie: TraktMovie): ListDetailItem {
        val tmdbId = movie.ids.tmdb
        var title = movie.title
        var year = movie.year
        var posterUrl: String? = null

        if (tmdbId > 0) {
            try {
                val detail = tmdbRepository.getMovieDetail(tmdbId)
                if (detail != null) {
                    title = detail.title.ifBlank { title }
                    year = detail.release_date.take(4).toIntOrNull() ?: year
                    posterUrl = detail.poster_path
                }
            } catch (_: Exception) {}
        }

        return ListDetailItem(
            rank = rank,
            type = MediaType.MOVIE,
            traktId = movie.ids.trakt,
            tmdbId = tmdbId,
            title = title,
            year = year,
            posterUrl = posterUrl,
            imdbId = movie.ids.imdb
        )
    }

    private suspend fun enhanceShow(rank: Int, show: TraktShow): ListDetailItem {
        val tmdbId = show.ids.tmdb
        var title = show.title
        var year = show.year
        var posterUrl: String? = null

        if (tmdbId > 0) {
            try {
                val enrichment = tmdbRepository.enrichTv(tmdbId, show.title, show.year)
                title = enrichment.chineseTitle.ifBlank { title }
                year = enrichment.year ?: year
                posterUrl = enrichment.posterUrl
            } catch (_: Exception) {}
        }

        return ListDetailItem(
            rank = rank,
            type = MediaType.SHOW,
            traktId = show.ids.trakt,
            tmdbId = tmdbId,
            title = title,
            year = year,
            posterUrl = posterUrl,
            imdbId = show.ids.imdb
        )
    }
}
