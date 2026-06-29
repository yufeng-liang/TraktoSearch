package com.tracktosearch.ui.screen.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbPerson
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonMovieCredit
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonTvCredit
import com.tracktosearch.data.remote.trakt.dto.TraktImages
import com.tracktosearch.data.remote.trakt.dto.TraktPersonDetail
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PersonUiState(
    val isLoading: Boolean = false,
    val person: TmdbPerson? = null,
    val movieCredits: List<TmdbPersonMovieCredit> = emptyList(),
    val tvCredits: List<TmdbPersonTvCredit> = emptyList(),
    val hasMoreMovies: Boolean = false,
    val hasMoreTvShows: Boolean = false,
    val isLoadingMoreMovies: Boolean = false,
    val isLoadingMoreTvShows: Boolean = false,
    val error: String? = null,
    val resolvingTmdbId: Int? = null,
    val traktPerson: TraktPersonDetail? = null,
    val personImages: List<String> = emptyList(), // 人物图片URL列表（headshot + fanart等）
    val isLoadingPersonImages: Boolean = false,
    val totalMovieCredits: Int = 0,
    val totalTvCredits: Int = 0,
    val originalName: String? = null
)

@HiltViewModel
class PersonViewModel @Inject constructor(
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonUiState())
    val uiState: StateFlow<PersonUiState> = _uiState.asStateFlow()

    private val _toastEvent = MutableSharedFlow<Int>()
    val toastEvent = _toastEvent.asSharedFlow()

    private var currentPersonId: Int = 0
    private var loaded: Boolean = false
    private var movieCreditsPage: Int = 1
    private var tvCreditsPage: Int = 1

    fun loadPerson(personId: Int) {
        if (currentPersonId == personId && loaded) return
        currentPersonId = personId
        loaded = false
        movieCreditsPage = 1
        tvCreditsPage = 1

        _uiState.value = PersonUiState(isLoading = true)

        viewModelScope.launch {
            try {
                val personDeferred = async { tmdbRepository.getPersonDetail(personId) }
                val movieCreditsDeferred = async { tmdbRepository.getPersonMovieCredits(personId, page = 1) }
                val tvCreditsDeferred = async { tmdbRepository.getPersonTvCredits(personId, page = 1) }

                val person = personDeferred.await()
                val movieCreditsPageResult = movieCreditsDeferred.await()
                val tvCreditsPageResult = tvCreditsDeferred.await()

                if (person == null) {
                    _uiState.value = PersonUiState(error = "Failed to load person")
                } else {
                    _uiState.value = PersonUiState(
                        isLoading = false,
                        person = person,
                        movieCredits = movieCreditsPageResult.items,
                        tvCredits = tvCreditsPageResult.items,
                        hasMoreMovies = movieCreditsPageResult.hasMore,
                        hasMoreTvShows = tvCreditsPageResult.hasMore
                    )
                    // 异步加载 Trakt 人物数据（静默失败）
                    loadTraktPerson(personId, person.name)
                    // 从 TMDB also_known_as 获取原名（英文名）
                    val originalName = person.also_known_as.firstOrNull { name ->
                        name.all { c -> c.isLetter() || c == ' ' || c == '.' || c == '-' || c == '\'' }
                    }
                    if (originalName != null) {
                        _uiState.value = _uiState.value.copy(originalName = originalName)
                    }
                }
                loaded = true
            } catch (_: Exception) {
                _uiState.value = PersonUiState(error = "Failed to load person data")
            }
        }
    }

    private fun loadTraktPerson(tmdbId: Int, personName: String) {
        // 提前设置图片加载状态，避免图片栏目出现时导致下方内容跳变
        _uiState.value = _uiState.value.copy(isLoadingPersonImages = true)
        viewModelScope.launch {
            try {
                // 使用 TMDB ID 搜索 Trakt 人物
                val searchResult = traktRepository.searchByTmdb(tmdbId, MediaType.PERSON)
                searchResult.onSuccess { results ->
                    android.util.Log.d("PersonVM", "Trakt search TMDB $tmdbId: ${results.size} results")
                    val personResult = results.firstOrNull { it.person != null }
                    val slug = personResult?.person?.ids?.slug
                    android.util.Log.d("PersonVM", "Person slug: $slug")
                    if (!slug.isNullOrEmpty()) {
                        val detailResult = traktRepository.getPersonSummary(slug)
                        detailResult.onSuccess { detail ->
                            android.util.Log.d("PersonVM", "Trakt person: ${detail.name}")
                            android.util.Log.d("PersonVM", "social_ids.facebook: ${detail.social_ids?.facebook}")
                            android.util.Log.d("PersonVM", "social_ids.instagram: ${detail.social_ids?.instagram}")
                            android.util.Log.d("PersonVM", "social_ids.twitter: ${detail.social_ids?.twitter}")
                            android.util.Log.d("PersonVM", "social_ids.wikipedia: ${detail.social_ids?.wikipedia}")
                            _uiState.value = _uiState.value.copy(traktPerson = detail)
                        }
                        // 获取参演数量
                        val movieCreditsResult = traktRepository.getPersonMovieCredits(slug)
                        movieCreditsResult.onSuccess { credits ->
                            _uiState.value = _uiState.value.copy(totalMovieCredits = credits.cast.size)
                        }
                        val showCreditsResult = traktRepository.getPersonShowCredits(slug)
                        showCreditsResult.onSuccess { credits ->
                            _uiState.value = _uiState.value.copy(totalTvCredits = credits.cast.size)
                        }
                        // 获取原名
                        val aliasesResult = traktRepository.getPersonAliases(slug)
                        aliasesResult.onSuccess { aliases ->
                            val originalName = aliases.firstOrNull { it.country == null }?.name
                            if (originalName != null && originalName != detailResult.getOrNull()?.name) {
                                _uiState.value = _uiState.value.copy(originalName = originalName)
                            }
                        }
                        // 获取 Trakt 人物图片
                        val imagesResult = traktRepository.getPersonImages(slug)
                        imagesResult.onSuccess { images ->
                            val imageUrls = mutableListOf<String>()
                            images.headshot.forEach { url ->
                                val fullUrl = if (url.startsWith("http")) url else "https://$url"
                                imageUrls.add(fullUrl)
                            }
                            images.fanart.forEach { url ->
                                val fullUrl = if (url.startsWith("http")) url else "https://$url"
                                imageUrls.add(fullUrl)
                            }
                            images.poster.forEach { url ->
                                val fullUrl = if (url.startsWith("http")) url else "https://$url"
                                imageUrls.add(fullUrl)
                            }
                            if (imageUrls.isNotEmpty()) {
                                _uiState.value = _uiState.value.copy(personImages = imageUrls)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // 静默失败，不影响页面正常显示
            }
            // 额外获取 TMDB 人物图片并合并
            loadTmdbPersonImages()
        }
    }

    private fun loadTmdbPersonImages() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPersonImages = true)
            try {
                val tmdbImages = tmdbRepository.getPersonImages(currentPersonId)
                if (tmdbImages.isNotEmpty()) {
                    val existing = _uiState.value.personImages.toMutableList()
                    val existingSet = existing.toSet()
                    tmdbImages.forEach { url ->
                        if (url !in existingSet) {
                            existing.add(url)
                        }
                    }
                    _uiState.value = _uiState.value.copy(personImages = existing)
                }
                // 获取人物被标注的图片（影视海报/剧照中含该人物的照片）
                val taggedImages = tmdbRepository.getPersonTaggedImages(currentPersonId)
                if (taggedImages.isNotEmpty()) {
                    val existing = _uiState.value.personImages.toMutableList()
                    val existingSet = existing.toSet()
                    taggedImages.forEach { url ->
                        if (url !in existingSet) {
                            existing.add(url)
                        }
                    }
                    _uiState.value = _uiState.value.copy(personImages = existing)
                }
            } catch (_: Exception) {
            } finally {
                _uiState.value = _uiState.value.copy(isLoadingPersonImages = false)
            }
        }
    }

    fun loadMoreMovies() {
        val state = _uiState.value
        if (state.isLoadingMoreMovies || !state.hasMoreMovies) return
        val nextPage = movieCreditsPage + 1

        _uiState.value = state.copy(isLoadingMoreMovies = true)
        viewModelScope.launch {
            try {
                val result = tmdbRepository.getPersonMovieCredits(currentPersonId, page = nextPage)
                movieCreditsPage = nextPage
                _uiState.value = _uiState.value.copy(
                    movieCredits = _uiState.value.movieCredits + result.items,
                    hasMoreMovies = result.hasMore,
                    isLoadingMoreMovies = false
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMoreMovies = false)
            }
        }
    }

    fun loadMoreTvShows() {
        val state = _uiState.value
        if (state.isLoadingMoreTvShows || !state.hasMoreTvShows) return
        val nextPage = tvCreditsPage + 1

        _uiState.value = state.copy(isLoadingMoreTvShows = true)
        viewModelScope.launch {
            try {
                val result = tmdbRepository.getPersonTvCredits(currentPersonId, page = nextPage)
                tvCreditsPage = nextPage
                _uiState.value = _uiState.value.copy(
                    tvCredits = _uiState.value.tvCredits + result.items,
                    hasMoreTvShows = result.hasMore,
                    isLoadingMoreTvShows = false
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMoreTvShows = false)
            }
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
                } else {
                    _toastEvent.emit(R.string.card_resolve_not_found)
                }
            }
            _uiState.value = _uiState.value.copy(resolvingTmdbId = null)
        }
    }
}
