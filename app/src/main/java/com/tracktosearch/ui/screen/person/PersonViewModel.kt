package com.tracktosearch.ui.screen.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.remote.tmdb.dto.TmdbPerson
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonMovieCredit
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonTvCredit
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

data class PersonUiState(
    val isLoading: Boolean = false,
    val person: TmdbPerson? = null,
    val movieCredits: List<TmdbPersonMovieCredit> = emptyList(),
    val tvCredits: List<TmdbPersonTvCredit> = emptyList(),
    val error: String? = null,
    val resolvingTmdbId: Int? = null
)

@HiltViewModel
class PersonViewModel @Inject constructor(
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonUiState())
    val uiState: StateFlow<PersonUiState> = _uiState.asStateFlow()

    private var currentPersonId: Int = 0
    private var loaded: Boolean = false

    fun loadPerson(personId: Int) {
        if (currentPersonId == personId && loaded) return
        currentPersonId = personId
        loaded = false

        _uiState.value = PersonUiState(isLoading = true)

        viewModelScope.launch {
            val personDeferred = async { tmdbRepository.getPersonDetail(personId) }
            val movieCreditsDeferred = async { tmdbRepository.getPersonMovieCredits(personId) }
            val tvCreditsDeferred = async { tmdbRepository.getPersonTvCredits(personId) }

            val person = personDeferred.await()
            val movieCredits = movieCreditsDeferred.await()
            val tvCredits = tvCreditsDeferred.await()

            if (person == null) {
                _uiState.value = PersonUiState(error = "Failed to load person")
            } else {
                _uiState.value = PersonUiState(
                    isLoading = false,
                    person = person,
                    movieCredits = movieCredits,
                    tvCredits = tvCredits
                )
            }
            loaded = true
        }
    }

    fun resolveAndNavigate(
        tmdbId: Int,
        title: String,
        isMovie: Boolean,
        onNavigate: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit
    ) {
        _uiState.value = _uiState.value.copy(resolvingTmdbId = tmdbId)
        viewModelScope.launch {
            val type = if (isMovie) MediaType.MOVIE else MediaType.SHOW
            val result = traktRepository.searchByTmdb(tmdbId, type)
            result.onSuccess { searchResults ->
                val first = searchResults.firstOrNull()
                val traktId = if (isMovie) first?.movie?.ids?.trakt else first?.show?.ids?.trakt
                val imdbId = if (isMovie) first?.movie?.ids?.imdb else first?.show?.ids?.imdb ?: ""
                if (traktId != null && traktId > 0) {
                    onNavigate(traktId, tmdbId, title, imdbId ?: "", 0.0)
                }
            }
            _uiState.value = _uiState.value.copy(resolvingTmdbId = null)
        }
    }
}
