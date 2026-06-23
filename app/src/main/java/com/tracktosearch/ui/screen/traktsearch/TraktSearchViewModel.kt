package com.tracktosearch.ui.screen.traktsearch

import androidx.lifecycle.SavedStateHandle
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

data class SearchTabState(
    val results: List<TraktSearchUiItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
    val totalCount: Int = 0,
    val currentPage: Int = 1,
    val hasMore: Boolean = false
)

data class TraktSearchUiState(
    val query: String = "",
    val selectedTab: MediaType = MediaType.MOVIE,
    val movieState: SearchTabState = SearchTabState(),
    val showState: SearchTabState = SearchTabState()
) {
    val currentTabState: SearchTabState
        get() = if (selectedTab == MediaType.MOVIE) movieState else showState
}

@HiltViewModel
class TraktSearchViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialType = if (savedStateHandle.get<String>("type") == "show") MediaType.SHOW else MediaType.MOVIE
    private val initialQueryStr = savedStateHandle.get<String>("query") ?: ""

    private val _uiState = MutableStateFlow(TraktSearchUiState(selectedTab = initialType))
    val uiState: StateFlow<TraktSearchUiState> = _uiState.asStateFlow()

    fun initSearch(query: String, type: MediaType) {
        val current = _uiState.value
        // 已经搜索过则不重新初始化（避免从详情页返回时用路由参数覆盖用户修改的搜索词）
        if (current.currentTabState.hasSearched) return
        _uiState.value = TraktSearchUiState(query = query, selectedTab = type)
        search(query, type)
    }

    fun switchTab(type: MediaType) {
        val current = _uiState.value
        if (current.selectedTab == type) return
        _uiState.value = current.copy(selectedTab = type)
        // 如果该 tab 还没搜索过，自动触发搜索
        val tabState = current.currentTabState
        val targetState = if (type == MediaType.MOVIE) current.movieState else current.showState
        if (!targetState.hasSearched && current.query.isNotBlank()) {
            search(current.query, type)
        }
    }

    fun search(query: String, type: MediaType? = null) {
        val searchType = type ?: _uiState.value.selectedTab
        if (query.isBlank()) return

        // 更新 query 到状态中，确保 loadMore 等使用最新查询词
        _uiState.value = _uiState.value.copy(query = query)

        val tabState = if (searchType == MediaType.MOVIE) _uiState.value.movieState else _uiState.value.showState
        if (tabState.isLoading) return

        viewModelScope.launch {
            updateTabState(searchType, SearchTabState(
                isLoading = true,
                hasSearched = true
            ))

            val result = when (searchType) {
                MediaType.MOVIE -> traktRepository.searchMovies(query, page = 1)
                MediaType.SHOW -> traktRepository.searchShows(query, page = 1)
            }

            result.onSuccess { (searchResults, totalCount) ->
                val uiItems = searchResults.map { item ->
                    async { enrichSearchResult(item, searchType) }
                }.awaitAll()
                updateTabState(searchType, SearchTabState(
                    results = uiItems,
                    isLoading = false,
                    totalCount = totalCount,
                    currentPage = 1,
                    hasMore = uiItems.size < totalCount,
                    hasSearched = true
                ))
            }.onFailure { e ->
                updateTabState(searchType, SearchTabState(
                    isLoading = false,
                    error = e.message ?: "搜索失败",
                    hasSearched = true
                ))
            }
        }
    }

    fun loadMore() {
        val current = _uiState.value
        val tabState = current.currentTabState
        if (tabState.isLoading || tabState.isLoadingMore || !tabState.hasMore) return

        viewModelScope.launch {
            val nextPage = tabState.currentPage + 1
            updateTabState(current.selectedTab, tabState.copy(isLoadingMore = true))

            val result = when (current.selectedTab) {
                MediaType.MOVIE -> traktRepository.searchMovies(current.query, page = nextPage)
                MediaType.SHOW -> traktRepository.searchShows(current.query, page = nextPage)
            }

            result.onSuccess { (searchResults, totalCount) ->
                val newItems = searchResults.map { item ->
                    async { enrichSearchResult(item, current.selectedTab) }
                }.awaitAll()
                val updatedState = current.currentTabState
                updateTabState(current.selectedTab, updatedState.copy(
                    results = updatedState.results + newItems,
                    isLoadingMore = false,
                    totalCount = totalCount,
                    currentPage = nextPage,
                    hasMore = (updatedState.results.size + newItems.size) < totalCount
                ))
            }.onFailure {
                val updatedState = _uiState.value.currentTabState
                updateTabState(current.selectedTab, updatedState.copy(isLoadingMore = false))
            }
        }
    }

    private fun updateTabState(type: MediaType, state: SearchTabState) {
        _uiState.value = if (type == MediaType.MOVIE) {
            _uiState.value.copy(movieState = state)
        } else {
            _uiState.value.copy(showState = state)
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
