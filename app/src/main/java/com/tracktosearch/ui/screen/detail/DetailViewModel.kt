package com.tracktosearch.ui.screen.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.trakt.dto.TraktComment
import com.tracktosearch.data.remote.trakt.dto.TraktEpisode
import com.tracktosearch.data.remote.trakt.dto.TraktSeason
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.MultiRatings
import com.tracktosearch.data.repository.RatingsRepository
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.remote.tmdb.dto.TmdbCast
import com.tracktosearch.data.remote.tmdb.dto.TmdbCrew
import com.tracktosearch.data.remote.tmdb.dto.TmdbCreditsResponse
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.util.CommentTranslator
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val title: String = "",
    val displayTitle: String = "",
    val year: Int? = null,
    val releaseDate: String = "",
    val overview: String = "",
    val genres: String = "",
    val posterUrl: String? = null,
    val runtime: Int? = null,
    val resources: List<ResourceItem> = emptyList(),
    val enabledSources: Set<String> = ResourceRepository.ALL_SOURCES,
    val enabledDiskTypes: Set<DiskType> = ResourceRepository.ALL_DISK_TYPES,
    val isMarkedWatched: Boolean = false,
    val error: String? = null,
    val searchAttempted: Boolean = false,
    val ratings: MultiRatings? = null,
    val viewedUrls: Set<String> = emptySet(),
    val comments: List<TraktComment> = emptyList(),
    val translatedComments: List<TraktComment> = emptyList(),
    val isTranslating: Boolean = false,
    val translatingCommentId: Int? = null,  // 正在翻译的单条评论ID
    val commentPage: Int = 1,              // 当前评论页码
    val hasMoreComments: Boolean = false,   // 是否还有更多评论
    val isLoadingMoreComments: Boolean = false,  // 是否正在加载更多评论
    val isMarkingWatched: Boolean = false,       // 是否正在处理标记/取消标记
    val seasons: List<TraktSeason> = emptyList(),
    val episodes: Map<Int, List<TraktEpisode>> = emptyMap(),
    val expandedSeasons: Set<Int> = emptySet(),
    // 演职员
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList(),
    // 列表是否变更（标记已看后变为 true，上级页面用于决定是否刷新）
    val watchlistChanged: Boolean = false
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val resourceRepository: ResourceRepository,
    private val ratingsRepository: RatingsRepository,
    private val viewedItemStorage: ViewedItemStorage,
    private val commentTranslator: CommentTranslator
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var currentTraktId: Int = 0
    private var currentMediaType: MediaType = MediaType.MOVIE
    private var currentTitle: String = ""
    private var currentKeyword: String = ""
    private var currentImdbId: String = ""
    private var currentTraktRating: Double = 0.0
    private var currentTmdbId: Int = 0
    private var detailLoaded: Boolean = false
    private var searchJob: Job? = null
    private var ratingsJob: Job? = null
    private var commentsJob: Job? = null
    private var seasonsJob: Job? = null
    // 全量结果（未按 filter 过滤）
    private var allResources: List<ResourceItem> = emptyList()

    fun loadDetail(traktId: Int, tmdbId: Int, title: String, mediaType: MediaType, year: Int? = null, imdbId: String = "", traktRating: Double = 0.0) {
        currentTraktId = traktId
        currentMediaType = mediaType
        currentTitle = title
        currentKeyword = title
        currentImdbId = imdbId
        currentTraktRating = traktRating
        currentTmdbId = tmdbId
        detailLoaded = false
        allResources = emptyList()

        _uiState.value = DetailUiState(
            isLoading = true,
            isSearching = true,
            title = title.replace("+", " "),
            displayTitle = title.replace("+", " "),
            year = year
        )

        viewModelScope.launch {
            var tmdbRating = 0.0
            if (tmdbId <= 0) {
                _uiState.value = _uiState.value.copy(isLoading = false, displayTitle = title.replace("+", " "))
                detailLoaded = true
                startSearch()
            } else {
                val enrichment: EnrichmentData? = runCatching {
                    when (mediaType) {
                        MediaType.MOVIE -> {
                            val e = tmdbRepository.enrichMovie(tmdbId, title, year)
                            tmdbRating = e.rating
                            EnrichmentData(e.chineseTitle, e.overview, e.genres, e.posterUrl, e.year, e.rating, e.runtime, e.releaseDate)
                        }
                        MediaType.SHOW -> {
                            val e = tmdbRepository.enrichTv(tmdbId, title, year)
                            tmdbRating = e.rating
                            EnrichmentData(e.chineseTitle, e.overview, e.genres, e.posterUrl, e.year, e.rating, e.episodeRunTime, e.releaseDate)
                        }
                    }
                }.getOrNull()

                val chineseTitle = enrichment?.chineseTitle?.takeIf { it.isNotEmpty() } ?: title
                currentKeyword = chineseTitle
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    displayTitle = chineseTitle.replace("+", " "),
                    overview = enrichment?.overview ?: "",
                    genres = enrichment?.genres ?: "",
                    posterUrl = enrichment?.posterUrl,
                    year = enrichment?.year ?: year,
                    releaseDate = enrichment?.releaseDate ?: "",
                    runtime = enrichment?.runtime
                )
                detailLoaded = true
                startSearch()
            }

            // 异步获取多平台评分
            fetchRatingsAsync(tmdbRating)

            // 异步获取评论
            fetchComments()

            // 异步获取季/集信息（仅电视剧）
            fetchSeasons()

            // 异步获取演职员
            fetchCredits()
        }
    }

    private fun fetchRatingsAsync(tmdbRating: Double) {
        ratingsJob?.cancel()
        ratingsJob = ratingsRepository.fetchRatingsStream(
            imdbId = currentImdbId,
            tmdbRating = tmdbRating,
            traktRating = currentTraktRating
        ).onEach { ratings ->
            _uiState.value = _uiState.value.copy(ratings = ratings)
        }.launchIn(viewModelScope)
    }

    private fun fetchComments() {
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            Log.d("DetailVM", "Fetching comments for traktId=$currentTraktId, type=$currentMediaType")
            val result = traktRepository.getComments(currentTraktId, currentMediaType, limit = 5, page = 1)
            result.onSuccess { comments ->
                Log.d("DetailVM", "Got ${comments.size} comments")
                _uiState.value = _uiState.value.copy(
                    comments = comments,
                    translatedComments = emptyList(),
                    commentPage = 1,
                    hasMoreComments = comments.isNotEmpty()  // 有评论就尝试加载更多
                )
            }.onFailure { e ->
                Log.e("DetailVM", "Failed to fetch comments", e)
            }
        }
    }

    /** 加载更多评论（下一页） */
    fun loadMoreComments() {
        val current = _uiState.value
        if (!current.hasMoreComments || current.isLoadingMoreComments) return

        val nextPage = current.commentPage + 1
        _uiState.value = current.copy(isLoadingMoreComments = true)

        viewModelScope.launch {
            val result = traktRepository.getComments(currentTraktId, currentMediaType, limit = 5, page = nextPage)
            result.onSuccess { newComments ->
                Log.d("DetailVM", "Loaded ${newComments.size} more comments (page $nextPage)")
                _uiState.value = _uiState.value.copy(
                    comments = _uiState.value.comments + newComments,
                    commentPage = nextPage,
                    hasMoreComments = newComments.size >= 5,  // 满页说明可能还有更多
                    isLoadingMoreComments = false
                )
            }.onFailure { e ->
                Log.e("DetailVM", "Failed to load more comments", e)
                _uiState.value = _uiState.value.copy(isLoadingMoreComments = false)
            }
        }
    }

    /** 用户点击翻译按钮时调用，按需翻译全部评论 */
    fun translateComments() {
        val comments = _uiState.value.comments
        if (comments.isEmpty()) return
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTranslating = true)
            try {
                val translated = commentTranslator.translateComments(comments)
                Log.d("DetailVM", "Translation done: ${translated.size} comments")
                _uiState.value = _uiState.value.copy(
                    translatedComments = translated,
                    isTranslating = false
                )
            } catch (e: Exception) {
                Log.e("DetailVM", "Translation failed", e)
                _uiState.value = _uiState.value.copy(isTranslating = false)
            }
        }
    }

    /** 用户点击单条评论的翻译按钮时调用 */
    fun translateSingleComment(commentId: Int) {
        val comment = _uiState.value.comments.find { it.id == commentId } ?: return
        // 已翻译过则不重复
        if (_uiState.value.translatedComments.any { it.id == commentId }) return

        _uiState.value = _uiState.value.copy(translatingCommentId = commentId)
        viewModelScope.launch {
            try {
                val translated = commentTranslator.translateSingleComment(comment)
                val currentTranslated = _uiState.value.translatedComments.toMutableList()
                currentTranslated.add(translated)
                _uiState.value = _uiState.value.copy(
                    translatedComments = currentTranslated,
                    translatingCommentId = null
                )
            } catch (e: Exception) {
                Log.e("DetailVM", "Single comment translation failed", e)
                _uiState.value = _uiState.value.copy(translatingCommentId = null)
            }
        }
    }

    private fun fetchSeasons() {
        if (currentMediaType != MediaType.SHOW) return
        seasonsJob?.cancel()
        seasonsJob = viewModelScope.launch {
            val result = traktRepository.getShowSeasons(currentTraktId)
            result.onSuccess { seasons ->
                _uiState.value = _uiState.value.copy(seasons = seasons)
            }
        }
    }

    private fun fetchCredits() {
        if (currentTmdbId <= 0) return
        viewModelScope.launch {
            val credits = tmdbRepository.getCredits(currentTmdbId, currentMediaType)
            credits?.let {
                Log.d("DetailVM", "Got ${it.cast.size} cast, ${it.crew.size} crew")
                // 按职位优先级排序：导演 > 编剧 > 制片人，去重
                val priorityOrder = mapOf("Director" to 0, "Writer" to 1, "Producer" to 2, "Screenplay" to 1)
                val filteredCrew = it.crew
                    .filter { crew -> crew.job in listOf("Director", "Writer", "Producer", "Screenplay") }
                    .distinctBy { it.id } // 去重（同一人可能有多个 job）
                    .sortedBy { priorityOrder[it.job] ?: 99 }
                _uiState.value = _uiState.value.copy(
                    cast = it.cast,
                    crew = filteredCrew
                )
            }
        }
    }

    fun toggleSeason(seasonNumber: Int) {
        val current = _uiState.value.expandedSeasons
        val newExpanded = if (seasonNumber in current) current - seasonNumber else current + seasonNumber

        // 如果展开且还没有加载该季的集信息，则加载
        if (seasonNumber !in current && seasonNumber !in _uiState.value.episodes) {
            viewModelScope.launch {
                val result = traktRepository.getSeasonEpisodes(currentTraktId, seasonNumber)
                result.onSuccess { episodeList ->
                    val newEpisodes = _uiState.value.episodes.toMutableMap()
                    newEpisodes[seasonNumber] = episodeList
                    _uiState.value = _uiState.value.copy(
                        expandedSeasons = newExpanded,
                        episodes = newEpisodes
                    )
                }
            }
        } else {
            _uiState.value = _uiState.value.copy(expandedSeasons = newExpanded)
        }
    }

    /**
     * 启动搜索：取消之前的任务，使用当前 filter 状态
     * 总是拉全量（两个源 + 所有网盘），缓存后本地过滤
     */
    private fun startSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val state = _uiState.value
            _uiState.value = state.copy(isSearching = true, error = null)
            val result = resourceRepository.searchResources(keyword = currentKeyword)
            result.onSuccess { _ ->
                // repository 已缓存全量，这里读取全量以便 filter 切换
                allResources = resourceRepository.getCachedAllResources(currentKeyword)
                val state = _uiState.value
                val filtered = resourceRepository.filterItems(
                    allResources, state.enabledSources, state.enabledDiskTypes
                )
                // 加载已查看 URL 集合
                val viewedUrls = viewedItemStorage.getviewedUrls()
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchAttempted = true,
                    resources = filtered,
                    viewedUrls = viewedUrls
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchAttempted = true,
                    error = e.message
                )
            }
        }
    }

    /**
     * 本地过滤当前已缓存的全量结果，不调 API
     */
    private fun applyLocalFilter(): List<ResourceItem> {
        val state = _uiState.value
        return resourceRepository.filterItems(allResources, state.enabledSources, state.enabledDiskTypes)
    }

    /**
     * 重新搜索：清除缓存，强制重新请求 API
     */
    fun searchResources() {
        if (!detailLoaded) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val state = _uiState.value
            _uiState.value = state.copy(isSearching = true, error = null)
            // 强制刷新（清除缓存后重拉）
            val result = resourceRepository.refreshResources(
                keyword = currentKeyword,
                enabledSources = state.enabledSources,
                enabledDiskTypes = state.enabledDiskTypes
            )
            result.onSuccess { items ->
                allResources = resourceRepository.getCachedAllResources(currentKeyword)
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchAttempted = true,
                    resources = items
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchAttempted = true,
                    error = e.message
                )
            }
        }
    }

    fun toggleSource(source: String) {
        val current = _uiState.value.enabledSources
        val newSources = if (source in current) {
            if (current.size == 1) current else current - source
        } else {
            current + source
        }
        if (newSources == current) return
        // 直接传新值过滤，避免从 _uiState.value 读到尚未提交的旧状态
        val filtered = resourceRepository.filterItems(
            allResources, newSources, _uiState.value.enabledDiskTypes
        )
        _uiState.value = _uiState.value.copy(
            enabledSources = newSources,
            resources = filtered
        )
    }

    fun toggleDiskType(type: DiskType) {
        val current = _uiState.value.enabledDiskTypes
        val newTypes = if (type in current) {
            if (current.size == 1) current else current - type
        } else {
            current + type
        }
        if (newTypes == current) return
        // 直接传新值过滤，避免从 _uiState.value 读到尚未提交的旧状态
        val filtered = resourceRepository.filterItems(
            allResources, _uiState.value.enabledSources, newTypes
        )
        _uiState.value = _uiState.value.copy(
            enabledDiskTypes = newTypes,
            resources = filtered
        )
    }

    /** 切换标记已看/取消标记 */
    fun toggleWatched() {
        val current = _uiState.value
        if (current.isMarkingWatched) return

        val targetState = !current.isMarkedWatched
        _uiState.value = current.copy(isMarkingWatched = true)

        viewModelScope.launch {
            if (targetState) {
                // 标记为已看
                traktRepository.markAsWatched(currentTraktId, currentMediaType)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(
                            isMarkedWatched = true,
                            isMarkingWatched = false,
                            // 通知上级页面：从列表中移除该 item
                            watchlistChanged = true
                        )
                    }
                    .onFailure {
                        // 失败则回滚状态
                        _uiState.value = _uiState.value.copy(isMarkingWatched = false)
                    }
            } else {
                // 取消标记（本地切换，无对应 API 时乐观更新）
                _uiState.value = _uiState.value.copy(
                    isMarkedWatched = false,
                    isMarkingWatched = false
                )
            }
        }
    }

    fun markResourceViewed(url: String) {
        val current = _uiState.value.viewedUrls
        if (url in current) return
        viewModelScope.launch {
            viewedItemStorage.markViewed(url)
            _uiState.value = _uiState.value.copy(viewedUrls = current + url)
        }
    }

    private data class EnrichmentData(
        val chineseTitle: String,
        val overview: String,
        val genres: String,
        val posterUrl: String?,
        val year: Int?,
        val rating: Double,
        val runtime: Int? = null,
        val releaseDate: String = ""
    )
}
