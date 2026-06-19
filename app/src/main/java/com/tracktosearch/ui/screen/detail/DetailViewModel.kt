package com.tracktosearch.ui.screen.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.trakt.dto.TraktComment
import com.tracktosearch.data.remote.trakt.dto.TraktCommentUser
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
import com.tracktosearch.data.remote.tmdb.dto.TmdbReview
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.local.FavoriteResourceStorage
import com.tracktosearch.data.util.CommentTranslator
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecommendationItem(
    val traktId: Int,
    val tmdbId: Int,
    val title: String,
    val displayTitle: String,
    val year: Int?,
    val genres: String,
    val posterUrl: String?,
    val imdbId: String = "",
    val traktRating: Double = 0.0,
    val isInWatchlist: Boolean = false,
    val isWatched: Boolean = false
)

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
    val isMarkingWatched: Boolean = false,       // 是否正在处理标记/取消标记已看
    val isMarkedWatchlist: Boolean = false,      // 是否在想看列表中
    val isMarkingWatchlist: Boolean = false,     // 是否正在处理添加/移除想看
    // 用户评分（null=未评分）
    val userRating: Int? = null,
    val isRating: Boolean = false,               // 是否正在提交评分
    val isRatingLoading: Boolean = false,        // 是否正在加载已有评分
    val error: String? = null,
    val searchAttempted: Boolean = false,
    val ratings: MultiRatings? = null,
    val viewedUrls: Set<String> = emptySet(),
    val favoriteUrls: Set<String> = emptySet(),
    val comments: List<TraktComment> = emptyList(),
    val translatedComments: List<TraktComment> = emptyList(),
    val isTranslating: Boolean = false,
    val translatingCommentId: Int? = null,  // 正在翻译的单条评论ID
    val commentPage: Int = 1,              // 当前 Trakt 评论页码
    val tmdbCommentPage: Int = 1,          // 当前 TMDB 评论页码
    val hasMoreComments: Boolean = false,   // 是否还有更多评论
    val isLoadingMoreComments: Boolean = false,  // 是否正在加载更多评论
    val seasons: List<TraktSeason> = emptyList(),
    val episodes: Map<Int, List<TraktEpisode>> = emptyMap(),
    val expandedSeasons: Set<Int> = emptySet(),
    // 已看剧集：季号 -> 已看集号集合
    val watchedEpisodeNumbers: Map<Int, Set<Int>> = emptyMap(),
    // 正在切换已看状态的剧集（季号-集号）
    val togglingEpisode: Pair<Int, Int>? = null,
    // 演职员
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList(),
    // 列表是否变更（标记已看后变为 true，上级页面用于决定是否刷新）
    val watchlistChanged: Boolean = false,
    // 相关推荐
    val recommendations: List<RecommendationItem> = emptyList(),
    val isLoadingRecommendations: Boolean = false,
    // 各模块加载错误提示
    val ratingsError: Boolean = false,
    val commentsError: Boolean = false,
    val recommendationsError: Boolean = false,
    val seasonsError: Boolean = false,
    val creditsError: Boolean = false
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val resourceRepository: ResourceRepository,
    private val ratingsRepository: RatingsRepository,
    private val viewedItemStorage: ViewedItemStorage,
    private val favoriteResourceStorage: FavoriteResourceStorage,
    private val commentTranslator: CommentTranslator
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var currentTraktId: Int = 0
    private var currentMediaType: MediaType = MediaType.MOVIE
    private var currentTitle: String = ""
    private var currentKeyword: String = ""
    private var currentOriginalTitle: String = ""
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
        // 已加载相同影视则用缓存（从子详情页返回时不重新请求）
        if (currentTraktId == traktId && detailLoaded) return

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
                            EnrichmentData(e.chineseTitle, e.originalTitle, e.overview, e.genres, e.posterUrl, e.year, e.rating, e.runtime, e.releaseDate)
                        }
                        MediaType.SHOW -> {
                            val e = tmdbRepository.enrichTv(tmdbId, title, year)
                            tmdbRating = e.rating
                            EnrichmentData(e.chineseTitle, e.originalTitle, e.overview, e.genres, e.posterUrl, e.year, e.rating, e.episodeRunTime, e.releaseDate)
                        }
                    }
                }.getOrNull()

                val chineseTitle = enrichment?.chineseTitle?.takeIf { it.isNotEmpty() } ?: title
                currentKeyword = chineseTitle
                currentOriginalTitle = enrichment?.originalTitle?.takeIf { it.isNotEmpty() && it != chineseTitle } ?: ""
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

            // 异步获取相关推荐（内部合并检查当前影视+推荐项的想看/已看状态）
            fetchRecommendations()

            // 异步获取用户评分
            fetchUserRating()
        }
    }

    private fun fetchRatingsAsync(tmdbRating: Double) {
        ratingsJob?.cancel()
        ratingsJob = ratingsRepository.fetchRatingsStream(
            imdbId = currentImdbId,
            tmdbRating = tmdbRating,
            traktRating = currentTraktRating
        ).onEach { ratings ->
            _uiState.value = _uiState.value.copy(ratings = ratings, ratingsError = false)
        }.catch {
            _uiState.value = _uiState.value.copy(ratingsError = true)
        }.launchIn(viewModelScope)
    }

    private fun fetchComments() {
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            try {
                // 并行加载 Trakt 和 TMDB 评论（首页各取少量）
                val traktDeferred = async {
                    traktRepository.getComments(currentTraktId, currentMediaType, limit = 10, page = 1)
                        .getOrDefault(emptyList())
                }
                val tmdbDeferred = async {
                    if (currentTmdbId > 0) {
                        tmdbRepository.getReviews(currentTmdbId, currentMediaType, page = 1)
                    } else null
                }

                val traktComments = traktDeferred.await()
                val tmdbResponse = tmdbDeferred.await()
                val tmdbComments = tmdbResponse?.results?.map { it.toTraktComment() } ?: emptyList()
                val allComments = traktComments + tmdbComments

                val hasMoreTrakt = traktComments.size >= 10
                val hasMoreTmdb = tmdbResponse != null && tmdbResponse.total_pages > 1

                _uiState.value = _uiState.value.copy(
                    comments = allComments,
                    translatedComments = emptyList(),
                    commentPage = 1,
                    tmdbCommentPage = 1,
                    hasMoreComments = hasMoreTrakt || hasMoreTmdb,
                    commentsError = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(commentsError = true)
            }
        }
    }

    /** 加载更多评论（下一页，Trakt 和 TMDB 并行） */
    fun loadMoreComments() {
        val current = _uiState.value
        if (!current.hasMoreComments || current.isLoadingMoreComments) return

        val nextTraktPage = current.commentPage + 1
        val nextTmdbPage = current.tmdbCommentPage + 1
        _uiState.value = current.copy(isLoadingMoreComments = true)

        viewModelScope.launch {
            // 判断是否还有更多 Trakt/TMDB 评论
            val hasMoreTrakt = current.commentPage > 0 // 首页满 10 条时 commentPage=1，可以继续
            val hasMoreTmdb = current.tmdbCommentPage > 0

            val traktDeferred = async {
                if (hasMoreTrakt) {
                    traktRepository.getComments(currentTraktId, currentMediaType, limit = 10, page = nextTraktPage)
                        .getOrDefault(emptyList())
                } else emptyList()
            }
            val tmdbDeferred = async {
                if (hasMoreTmdb && currentTmdbId > 0) {
                    tmdbRepository.getReviews(currentTmdbId, currentMediaType, page = nextTmdbPage)
                } else null
            }

            val newTraktComments = traktDeferred.await()
            val tmdbResponse = tmdbDeferred.await()
            val newTmdbComments = tmdbResponse?.results?.map { it.toTraktComment() } ?: emptyList()

            // 去重
            val existingIds = _uiState.value.comments.map { it.id }.toSet()
            val uniqueNew = (newTraktComments + newTmdbComments).filter { it.id !in existingIds }

            val newHasMoreTrakt = newTraktComments.size >= 10
            val newHasMoreTmdb = tmdbResponse != null && nextTmdbPage < tmdbResponse.total_pages

            _uiState.value = _uiState.value.copy(
                comments = _uiState.value.comments + uniqueNew,
                commentPage = if (newTraktComments.isNotEmpty()) nextTraktPage else current.commentPage,
                tmdbCommentPage = if (newTmdbComments.isNotEmpty()) nextTmdbPage else current.tmdbCommentPage,
                hasMoreComments = newHasMoreTrakt || newHasMoreTmdb,
                isLoadingMoreComments = false
            )
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
                _uiState.value = _uiState.value.copy(seasons = seasons, seasonsError = false)
                // 季集加载后获取观看进度
                fetchWatchedProgress()
            }.onFailure {
                _uiState.value = _uiState.value.copy(seasonsError = true)
            }
        }
    }

    /** 获取已看剧集进度，转换为 季号 -> 已看集号集合 */
    private fun fetchWatchedProgress() {
        viewModelScope.launch {
            val result = traktRepository.getShowWatchedProgress(currentTraktId)
            result.onSuccess { progress ->
                val watchedMap = mutableMapOf<Int, Set<Int>>()
                progress.seasons.forEach { season ->
                    val watchedEpisodes = season.episodes
                        .filter { it.completed }
                        .map { it.number }
                        .toSet()
                    if (watchedEpisodes.isNotEmpty()) {
                        watchedMap[season.number] = watchedEpisodes
                    }
                }
                _uiState.value = _uiState.value.copy(watchedEpisodeNumbers = watchedMap)
            }
        }
    }

    private fun fetchCredits() {
        if (currentTmdbId <= 0) return
        viewModelScope.launch {
            try {
                val credits = tmdbRepository.getCredits(currentTmdbId, currentMediaType)
                credits?.let {
                    // 按职位优先级排序：导演 > 编剧 > 制片人，去重
                    val priorityOrder = mapOf("Director" to 0, "Writer" to 1, "Producer" to 2, "Screenplay" to 1)
                    val filteredCrew = it.crew
                        .filter { crew -> crew.job in listOf("Director", "Writer", "Producer", "Screenplay") }
                        .distinctBy { it.id } // 去重（同一人可能有多个 job）
                        .sortedBy { priorityOrder[it.job] ?: 99 }
                    _uiState.value = _uiState.value.copy(
                        cast = it.cast,
                        crew = filteredCrew,
                        creditsError = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(creditsError = true)
            }
        }
    }

    private fun fetchRecommendations() {
        if (currentTraktId <= 0) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingRecommendations = true)
            try {
                val enriched = when (currentMediaType) {
                    MediaType.MOVIE -> {
                        val result = traktRepository.getRelatedMovies(currentTraktId)
                        val movies = result.getOrDefault(emptyList())
                        movies.map { movie ->
                            async {
                                val enrichment = tmdbRepository.enrichMovie(
                                    movie.ids.tmdb, movie.title, movie.year.takeIf { it > 0 }
                                )
                                RecommendationItem(
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
                        }.awaitAll()
                    }
                    MediaType.SHOW -> {
                        val result = traktRepository.getRelatedShows(currentTraktId)
                        val shows = result.getOrDefault(emptyList())
                        shows.map { show ->
                            async {
                                val enrichment = tmdbRepository.enrichTv(
                                    show.ids.tmdb, show.title, show.year.takeIf { it > 0 }
                                )
                                RecommendationItem(
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
                        }.awaitAll()
                    }
                }

                val filtered = enriched.filter { it.tmdbId > 0 }

                // 批量检查想看/已看状态（同时检查当前影视，避免重复 API 调用）
                val allIdsToCheck = filtered.map { it.traktId } + currentTraktId
                val statusMap = traktRepository.batchCheckStatus(allIdsToCheck, currentMediaType)

                // 更新推荐项状态
                val withStatus = filtered.map { item ->
                    val status = statusMap[item.traktId]
                    item.copy(
                        isInWatchlist = status?.first ?: false,
                        isWatched = status?.second ?: false
                    )
                }

                // 更新当前影视的想看/已看状态
                val currentStatus = statusMap[currentTraktId]
                _uiState.value = _uiState.value.copy(
                    recommendations = withStatus,
                    isLoadingRecommendations = false,
                    isMarkedWatchlist = currentStatus?.first ?: false,
                    isMarkedWatched = currentStatus?.second ?: false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingRecommendations = false,
                    recommendationsError = true
                )
            }
        }
    }

    private fun fetchUserRating() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRatingLoading = true)
            try {
                val rating = traktRepository.getUserRating(currentTraktId, currentMediaType)
                _uiState.value = _uiState.value.copy(
                    userRating = rating,
                    isRatingLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isRatingLoading = false)
            }
        }
    }

    /** 提交评分（1-10 分） */
    fun setRating(rating: Int) {
        val current = _uiState.value
        if (current.isRating) return
        // 点击与当前评分相同的星标 → 取消评分
        if (current.userRating == rating) {
            removeRating()
            return
        }
        _uiState.value = current.copy(isRating = true)
        viewModelScope.launch {
            traktRepository.addRating(currentTraktId, rating, currentMediaType)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        userRating = rating,
                        isRating = false
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isRating = false)
                }
        }
    }

    /** 取消评分 */
    fun removeRating() {
        val current = _uiState.value
        if (current.isRating) return
        _uiState.value = current.copy(isRating = true)
        viewModelScope.launch {
            traktRepository.removeRating(currentTraktId, currentMediaType)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        userRating = null,
                        isRating = false
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isRating = false)
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

    /** 切换某集已看/取消已看 */
    fun toggleEpisodeWatched(seasonNumber: Int, episodeNumber: Int, episodeTraktId: Int) {
        val current = _uiState.value
        if (current.togglingEpisode == Pair(seasonNumber, episodeNumber)) return

        val isWatched = episodeNumber in (current.watchedEpisodeNumbers[seasonNumber] ?: emptySet())
        _uiState.value = current.copy(togglingEpisode = Pair(seasonNumber, episodeNumber))

        viewModelScope.launch {
            val result = if (isWatched) {
                traktRepository.unmarkEpisodeWatched(episodeTraktId)
            } else {
                traktRepository.markEpisodeWatched(episodeTraktId)
            }
            result.onSuccess {
                val newWatched = current.watchedEpisodeNumbers.toMutableMap()
                val seasonSet = newWatched[seasonNumber]?.toMutableSet() ?: mutableSetOf()
                if (isWatched) {
                    seasonSet.remove(episodeNumber)
                } else {
                    seasonSet.add(episodeNumber)
                }
                if (seasonSet.isEmpty()) {
                    newWatched.remove(seasonNumber)
                } else {
                    newWatched[seasonNumber] = seasonSet
                }
                _uiState.value = _uiState.value.copy(
                    watchedEpisodeNumbers = newWatched,
                    togglingEpisode = null
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(togglingEpisode = null)
            }
        }
    }

    /**
     * 启动搜索：中英文并行搜索，源级别先到先显示，全部完成后合并去重
     */
    private fun startSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, error = null)

            val isShow = currentMediaType == MediaType.SHOW
            val hasEnglish = currentOriginalTitle.isNotEmpty()

            val chineseFlow = resourceRepository.searchResourcesFlow(currentKeyword, isShow = isShow)
            val englishFlow = if (hasEnglish) {
                resourceRepository.searchResourcesFlow(currentOriginalTitle, isShow = isShow)
            } else null

            var firstResultShown = false
            var chineseItems = emptyList<ResourceItem>()
            var englishItems = emptyList<ResourceItem>()

            // 同时收集两个 Flow，任一发射就更新 UI
            // 注意：只有非空结果才关闭搜索状态，避免空结果先到导致"无结果"闪烁
            if (englishFlow != null) {
                coroutineScope {
                    val chineseJob = launch {
                        chineseFlow.collect { items ->
                            chineseItems = items
                            if (!firstResultShown && items.isNotEmpty()) {
                                firstResultShown = true
                                _uiState.value = _uiState.value.copy(isSearching = false)
                            }
                            updateSearchResults(chineseItems, englishItems, isShow)
                        }
                    }
                    val englishJob = launch {
                        englishFlow.collect { items ->
                            englishItems = items
                            if (!firstResultShown && items.isNotEmpty()) {
                                firstResultShown = true
                                _uiState.value = _uiState.value.copy(isSearching = false)
                            }
                            updateSearchResults(chineseItems, englishItems, isShow)
                        }
                    }
                    chineseJob.join()
                    englishJob.join()
                    // 所有源都完成后若仍无结果，关闭搜索状态
                    if (_uiState.value.isSearching) {
                        _uiState.value = _uiState.value.copy(isSearching = false)
                    }
                }
            } else {
                chineseFlow.collect { items ->
                    chineseItems = items
                    if (!firstResultShown && items.isNotEmpty()) {
                        firstResultShown = true
                        _uiState.value = _uiState.value.copy(isSearching = false)
                    }
                    updateSearchResults(chineseItems, englishItems, isShow)
                }
                // 单个 Flow 收集完成后若仍无结果，关闭搜索状态
                if (_uiState.value.isSearching) {
                    _uiState.value = _uiState.value.copy(isSearching = false)
                }
            }
        }
    }

    /**
     * 合并中英文搜索结果并更新 UI
     */
    private suspend fun updateSearchResults(
        chineseItems: List<ResourceItem>,
        englishItems: List<ResourceItem>,
        isShow: Boolean
    ) {
        val existingUrls = chineseItems.map { it.url }.toSet()
        val mergedItems = chineseItems + englishItems.filter { it.url !in existingUrls }

        if (mergedItems.isNotEmpty()) {
            allResources = resourceRepository.mergeAndCacheResources(
                keyword = currentKeyword,
                items = mergedItems,
                isShow = isShow
            )
        } else {
            allResources = resourceRepository.getCachedAllResources(currentKeyword)
        }

        val state = _uiState.value
        val filtered = resourceRepository.filterItems(
            allResources, state.enabledSources, state.enabledDiskTypes
        )
        val viewedUrls = viewedItemStorage.getviewedUrls()
        val favoriteUrls = favoriteResourceStorage.getFavoriteUrls()
        _uiState.value = _uiState.value.copy(
            searchAttempted = true,
            resources = filtered,
            viewedUrls = viewedUrls,
            favoriteUrls = favoriteUrls
        )
    }

    /**
     * 本地过滤当前已缓存的全量结果，不调 API
     */
    private fun applyLocalFilter(): List<ResourceItem> {
        val state = _uiState.value
        return resourceRepository.filterItems(allResources, state.enabledSources, state.enabledDiskTypes)
    }

    /**
     * 重新搜索：清除缓存，强制重新请求 API（中英文名并行搜索）
     */
    fun searchResources() {
        if (!detailLoaded) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val state = _uiState.value
            _uiState.value = state.copy(isSearching = true, error = null)
            val isShow = currentMediaType == MediaType.SHOW

            // 强制刷新中文名搜索
            val chineseResult = resourceRepository.refreshResources(
                keyword = currentKeyword,
                enabledSources = state.enabledSources,
                enabledDiskTypes = state.enabledDiskTypes,
                isShow = isShow
            )
            // 如果有英文名，也刷新英文名搜索
            val englishResult = if (currentOriginalTitle.isNotEmpty()) {
                resourceRepository.refreshResources(
                    keyword = currentOriginalTitle,
                    enabledSources = state.enabledSources,
                    enabledDiskTypes = state.enabledDiskTypes,
                    isShow = isShow
                )
            } else {
                Result.success(emptyList())
            }

            val chineseItems = chineseResult.getOrDefault(emptyList())
            val englishItems = englishResult.getOrDefault(emptyList())
            val existingUrls = chineseItems.map { it.url }.toSet()
            val mergedItems = chineseItems + englishItems.filter { it.url !in existingUrls }

            if (mergedItems.isNotEmpty()) {
                allResources = resourceRepository.mergeAndCacheResources(
                    keyword = currentKeyword,
                    items = mergedItems,
                    isShow = isShow
                )
            } else {
                allResources = resourceRepository.getCachedAllResources(currentKeyword)
            }

            val filtered = resourceRepository.filterItems(
                allResources, state.enabledSources, state.enabledDiskTypes
            )
            _uiState.value = _uiState.value.copy(
                isSearching = false,
                searchAttempted = true,
                resources = filtered
            )
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
        // 标记已看 → 立即通知刷新（不等 API 完成，确保返回时列表更新）
        // 取消标记 → 设为 false，避免无变更时重复拉取
        _uiState.value = current.copy(
            isMarkingWatched = true,
            watchlistChanged = targetState // 标记时 true，取消时 false
        )

        viewModelScope.launch {
            if (targetState) {
                // 标记为已看：后端会自动从想看列表移除
                traktRepository.markAsWatched(currentTraktId, currentMediaType)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(
                            isMarkedWatched = true,
                            isMarkedWatchlist = false,
                            isMarkingWatched = false
                        )
                    }
                    .onFailure {
                        // 失败则回滚状态，同时撤销刷新标记
                        _uiState.value = _uiState.value.copy(
                            isMarkingWatched = false,
                            watchlistChanged = false
                        )
                    }
            } else {
                // 取消标记：后端会自动重新加入想看列表
                traktRepository.removeWatched(currentTraktId, currentMediaType)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(
                            isMarkedWatched = false,
                            isMarkedWatchlist = true,
                            isMarkingWatched = false
                        )
                    }
                    .onFailure {
                        _uiState.value = _uiState.value.copy(isMarkingWatched = false)
                    }
            }
        }
    }

    /** 切换想看状态：添加/移除想看 */
    fun toggleWatchlist() {
        val current = _uiState.value
        if (current.isMarkingWatchlist) return

        val targetState = !current.isMarkedWatchlist
        _uiState.value = current.copy(
            isMarkingWatchlist = true,
            watchlistChanged = targetState || current.watchlistChanged
        )

        viewModelScope.launch {
            val result = if (targetState) {
                traktRepository.addToWatchlist(currentTraktId, currentMediaType)
            } else {
                traktRepository.removeFromWatchlist(currentTraktId, currentMediaType)
            }
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isMarkedWatchlist = targetState,
                    isMarkingWatchlist = false
                )
            } else {
                _uiState.value = _uiState.value.copy(isMarkingWatchlist = false)
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

    fun toggleFavorite(item: ResourceItem) {
        viewModelScope.launch {
            val nowFavorite = favoriteResourceStorage.toggleFavorite(item)
            _uiState.value = _uiState.value.copy(
                favoriteUrls = if (nowFavorite) {
                    _uiState.value.favoriteUrls + item.url
                } else {
                    _uiState.value.favoriteUrls - item.url
                }
            )
        }
    }

    private data class EnrichmentData(
        val chineseTitle: String,
        val originalTitle: String = "",
        val overview: String,
        val genres: String,
        val posterUrl: String?,
        val year: Int?,
        val rating: Double,
        val runtime: Int? = null,
        val releaseDate: String = ""
    )
}

/** 将 TMDB Review 转换为统一的 TraktComment 格式 */
private fun TmdbReview.toTraktComment(): TraktComment {
    // TMDB 的 id 是字符串，用 hashCode 转为 Int，加偏移避免与 Trakt ID 冲突
    val tmdbId = Math.abs(id.hashCode()) + 1_000_000
    return TraktComment(
        id = tmdbId,
        comment = content,
        spoiler = false,
        review = true,
        created_at = created_at,
        user_rating = author_details.rating,
        user = TraktCommentUser(
            username = author_details.username.ifEmpty { author },
            name = author_details.name.ifEmpty { author }
        ),
        source = "TMDB"
    )
}
