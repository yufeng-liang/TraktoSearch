package com.tracktosearch.ui.screen.detail

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.local.DetailSectionStorage
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.UserReviewEntity
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.tmdb.dto.TmdbCast
import com.tracktosearch.data.remote.tmdb.dto.TmdbCollectionResponse
import com.tracktosearch.data.remote.tmdb.dto.TmdbCrew
import com.tracktosearch.data.remote.tmdb.dto.TmdbReview
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import com.tracktosearch.data.remote.trakt.dto.TraktComment
import com.tracktosearch.data.remote.trakt.dto.TraktCommentUser
import com.tracktosearch.data.remote.trakt.dto.TraktEpisode
import com.tracktosearch.data.remote.trakt.dto.TraktImages
import com.tracktosearch.data.remote.trakt.dto.TraktSeason
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.MultiRatings
import com.tracktosearch.data.repository.RatingsRepository
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UserReviewRepository
import com.tracktosearch.data.util.CommentTranslator
import com.tracktosearch.data.util.PosterColorExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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

@Immutable
data class DetailUiState(
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val title: String = "",
    val displayTitle: String = "",
    val originalTitle: String = "",
    val year: Int? = null,
    val releaseDate: String = "",
    val overview: String = "",
    val genres: String = "",
    val country: String = "",
    val posterUrl: String? = null,
    // 海报主色调(沉浸式背景渐变用,null=未提取或提取失败)
    val posterDominantColor: Color? = null,
    val runtime: Int? = null,
    val resources: List<ResourceItem> = emptyList(),
    val availableSources: List<String> = emptyList(),   // 筛选条上所有可用源（来自设置页，固定不变）
    val enabledSources: Set<String> = ResourceRepository.ALL_SOURCES, // 当前选中的源（点击筛选时变化）
    val customSourceNames: Map<String, String> = emptyMap(), // 自定义源 ID -> 名称
    val enabledDiskTypes: Set<DiskType> = ResourceRepository.ALL_DISK_TYPES,
    // 资源搜索进度：已完成源数 / 总源数
    val completedSources: Int = 0,
    val totalSources: Int = ResourceRepository.ALL_SOURCES.size,
    val isMarkedWatched: Boolean = false,
    val isMarkingWatched: Boolean = false,       // 是否正在处理标记/取消标记已看
    val isMarkedWatchlist: Boolean = false,      // 是否在想看列表中
    val isMarkingWatchlist: Boolean = false,     // 是否正在处理添加/移除想看
    // 用户评分（null=未评分）
    val userRating: Int? = null,
    val userComment: String? = null,             // 用户已提交的短评（用于回显到评分弹窗）
    val isRating: Boolean = false,               // 是否正在提交评分
    val isRatingLoading: Boolean = false,        // 是否正在加载已有评分
    val error: String? = null,
    val searchAttempted: Boolean = false,
    val ratings: MultiRatings? = null,
    val viewedUrls: Set<String> = emptySet(),
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
    // 预告片与截图
    val videos: List<TmdbVideo> = emptyList(),
    val backdrops: List<String> = emptyList(),
    val isLoadingVideosImages: Boolean = false,
    // 想看列表是否变更（添加/移除想看后变为 true）
    val watchlistChanged: Boolean = false,
    // 已看历史是否变更（标记/取消已看后变为 true）
    val watchedChanged: Boolean = false,
    // 相关推荐
    val recommendations: List<RecommendationItem> = emptyList(),
    val isLoadingRecommendations: Boolean = false,
    // 系列信息
    val collectionInfo: TmdbCollectionResponse? = null,
    // 影视状态（Released, In Production, Returning Series, Ended 等）
    val status: String = "",
    // 各模块加载错误提示
    val ratingsError: Boolean = false,
    val commentsError: Boolean = false,
    val recommendationsError: Boolean = false,
    val seasonsError: Boolean = false,
    val creditsError: Boolean = false,
    // 未登录用户引导登录
    val isLoggedIn: Boolean = true,
    val showLoginPrompt: Boolean = false,
    // 电视剧标记已看弹窗
    val showMarkWatchedDialog: Boolean = false,
    // 电影标记已看后弹出评分弹窗
    val showRatingDialog: Boolean = false,
    // 详情页模块可见性设置
    val sectionVisible: DetailSectionVisibility = DetailSectionVisibility(),
    // ===== 豆瓣双向同步 =====
    /** 预查到的 doubanId(null=未就绪/未查到) */
    val doubanIdForSync: String? = null,
    /** 豆瓣同步进行中 */
    val isDoubanSyncing: Boolean = false,
    /** 是否显示重试按钮(豆瓣同步失败或 ID 未就绪时 true) */
    val doubanSyncRetryable: Boolean = false,
    /** 待重试的豆瓣动作 */
    val pendingDoubanAction: DoubanSyncAction? = null
)

/** 豆瓣同步动作枚举 */
enum class DoubanSyncAction {
    WISH,               // 标记想看
    COLLECT,            // 标记已看
    REMOVE_WISH,        // 取消想看
    REMOVE_COLLECT      // 取消已看
}

data class DetailSectionVisibility(
    val cast: Boolean = true,
    val videosImages: Boolean = true,
    val overview: Boolean = true,
    val myRating: Boolean = true,
    val comments: Boolean = true,
    val recommendations: Boolean = true
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val resourceRepository: ResourceRepository,
    private val ratingsRepository: RatingsRepository,
    private val viewedItemStorage: ViewedItemStorage,
    private val commentTranslator: CommentTranslator,
    private val tokenStorage: TokenStorage,
    private val detailSectionStorage: DetailSectionStorage,
    private val languageStorage: LanguageStorage,
    // 豆瓣双向同步依赖
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    // 海报主色调提取器(对 DetailHeaderContent 暴露,用于在海报加载成功后提取主色)
    val posterColorExtractor: PosterColorExtractor,
    // 本地评分+短评缓存(优先读取 Trakt 之外的本地值,提交/更新时写回)
    private val userReviewRepository: UserReviewRepository
) : ViewModel() {

    companion object {
        private const val CACHE_MAX_SIZE = 3
        @Suppress("UNCHECKED_CAST")
        private val detailCache: MutableMap<Int, CachedDetailData> = java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<Int, CachedDetailData>(CACHE_MAX_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedDetailData>?): Boolean = size > CACHE_MAX_SIZE
            }
        )

        private fun cachePut(key: Int, value: CachedDetailData) {
            detailCache[key] = value  // removeEldestEntry auto-evict
        }

        private fun cacheGet(key: Int): CachedDetailData? {
            return detailCache[key]
        }

        private fun cacheRemove(key: Int) {
            detailCache.remove(key)
        }

        data class CachedDetailData(
            val uiState: DetailUiState,
            val allResources: List<ResourceItem>,
            val currentKeyword: String,
            val currentOriginalTitle: String,
            val currentImdbId: String,
            val currentTraktRating: Double,
            val currentTmdbId: Int,
            val currentMediaType: MediaType,
            val currentCollectionId: Int = 0
        )
    }

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    /** Toast 事件(参考 PersonViewModel 模式),用于豆瓣同步成功/失败提示 */
    private val _toastEvent = MutableSharedFlow<Int>()
    val toastEvent = _toastEvent.asSharedFlow()

    /** 海报主色调提取完成后更新 UiState(由 DetailHeaderContent 在图片加载成功回调中调用) */
    fun updatePosterColor(color: Color) {
        _uiState.value = _uiState.value.copy(posterDominantColor = color)
    }

    /**
     * 进入详情页时尽早从缓存预查海报主色(不需要 bitmap)。
     * 命中则瞬间设置 posterDominantColor,让沉浸背景在海报图片加载完成前就显示出来。
     * 未命中(首次访问)仍由 DetailHeaderContent 的 Coil listener 走 [updatePosterColor] 流程。
     */
    private fun prefetchPosterColor(posterUrl: String?) {
        if (posterUrl.isNullOrEmpty()) return
        // 已有主色就不重复查
        if (_uiState.value.posterDominantColor != null) return
        viewModelScope.launch {
            posterColorExtractor.getCachedColor(posterUrl)?.let { argb ->
                if (argb != 0L) {
                    _uiState.value = _uiState.value.copy(posterDominantColor = Color(argb))
                }
            }
        }
    }

    init {
        // 尽早检查登录状态，避免 loadDetail 异步延迟导致 isLoggedIn 为 false
        viewModelScope.launch {
            isLoggedIn = !tokenStorage.accessToken.first().isNullOrEmpty()
            _uiState.value = _uiState.value.copy(isLoggedIn = isLoggedIn)
        }
        // 监听详情页模块可见性设置
        viewModelScope.launch {
            detailSectionStorage.sectionConfigs.collect { configs ->
                val visibility = DetailSectionVisibility(
                    cast = configs.find { it.id == "cast" }?.visible ?: true,
                    videosImages = configs.find { it.id == "videos-images" }?.visible ?: true,
                    overview = configs.find { it.id == "overview" }?.visible ?: true,
                    myRating = configs.find { it.id == "my-rating" }?.visible ?: true,
                    comments = configs.find { it.id == "comments" }?.visible ?: true,
                    recommendations = configs.find { it.id == "recommendations" }?.visible ?: true
                )
                _uiState.value = _uiState.value.copy(sectionVisible = visibility)
            }
        }
    }

    private var currentTraktId: Int = 0
    private var currentMediaType: MediaType = MediaType.MOVIE
    private var currentTitle: String = ""
    private var currentKeyword: String = ""
    private var currentOriginalTitle: String = ""
    private var currentImdbId: String = ""
    private var currentTraktRating: Double = 0.0
    private var currentTmdbId: Int = 0
    private var currentCollectionId: Int = 0
    private var detailLoaded: Boolean = false
    private var searchJob: Job? = null
    private var ratingsJob: Job? = null
    private var commentsJob: Job? = null
    private var seasonsJob: Job? = null
    private var delayedLoadJob: Job? = null
    // 全量结果（未按 filter 过滤）
    private var allResources: List<ResourceItem> = emptyList()
    private var isLoggedIn: Boolean = false

    suspend fun loadDetail(traktId: Int, tmdbId: Int, title: String, mediaType: MediaType, year: Int? = null, imdbId: String = "", traktRating: Double = 0.0, inWatchlist: Boolean = false, isWatched: Boolean = false) {
        // 已加载相同影视则用缓存（从子详情页返回时不重新请求）
        if (currentTraktId == traktId && detailLoaded) return

        // 尝试从静态缓存恢复
        val cached = cacheGet(traktId)
        if (cached != null) {
            currentTraktId = traktId
            currentMediaType = cached.currentMediaType
            currentTitle = cached.uiState.title
            currentKeyword = cached.currentKeyword
            currentOriginalTitle = cached.currentOriginalTitle
            currentImdbId = cached.currentImdbId
            currentTraktRating = cached.currentTraktRating
            currentTmdbId = cached.currentTmdbId
            currentCollectionId = cached.currentCollectionId
            detailLoaded = true
            allResources = cached.allResources
            // 从全局缓存获取真实的想看/已看状态，不依赖路由参数（从推荐列表进入时默认为 false）
            val realInWatchlist = traktRepository.checkInWatchlist(traktId, cached.currentMediaType)
            val realWatched = traktRepository.checkWatched(traktId, cached.currentMediaType)
            _uiState.value = cached.uiState.copy(
                isMarkedWatchlist = realInWatchlist,
                isMarkedWatched = realWatched
            )
            // 从存储拉取最新 viewedUrls 覆盖缓存旧值，避免跨页面返回后丢失状态
            val latestViewed = withContext(Dispatchers.IO) { viewedItemStorage.getViewedUrls() }
            _uiState.value = _uiState.value.copy(viewedUrls = latestViewed)
            // 上次缓存时若未提取到海报主色(用户中途返回),尽早从持久化缓存补查
            prefetchPosterColor(cached.uiState.posterUrl)
            // 上次缓存时搜索未完成(用户中途返回),重启搜索避免卡在搜索中状态
            // startSearch 内部会重新设置 isSearching=true 并发起新的搜索流程
            if (cached.uiState.isSearching) {
                startSearch()
            }
            // 按需补启附属 fetch:缓存可能是在附属请求完成前被写入的(用户中途返回),
            // 此时 ratings/comments/credits/videos/seasons 等字段为空,需要重新拉取避免永久卡在骨架状态
            val visibility = cached.uiState.sectionVisible
            if (visibility.myRating && cached.uiState.ratings == null) fetchRatingsAsync(cached.currentTraktRating)
            if (visibility.comments && cached.uiState.comments.isEmpty()) fetchComments()
            if (cached.uiState.seasons.isEmpty() && cached.currentMediaType == MediaType.SHOW) fetchSeasons()
            if (visibility.cast && cached.uiState.cast.isEmpty() && cached.uiState.crew.isEmpty()) fetchCredits()
            if (visibility.videosImages && (cached.uiState.isLoadingVideosImages || (cached.uiState.videos.isEmpty() && cached.uiState.backdrops.isEmpty()))) fetchVideosAndImages()
            // fetchUserRating 内部会判断 tokenStorage.getCachedAccessToken() 是否为空,无需在此重复判断
            if (visibility.myRating && cached.uiState.userRating == null) fetchUserRating()
            delayedLoadJob?.cancel()
            delayedLoadJob = viewModelScope.launch {
                delay(1500)
                if (visibility.recommendations && cached.uiState.recommendations.isEmpty()) fetchRecommendations()
                if (cached.currentMediaType == MediaType.MOVIE && cached.currentCollectionId > 0 && cached.uiState.collectionInfo == null) {
                    fetchCollection(cached.currentCollectionId)
                }
            }
            return
        }

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
            year = year,
            isMarkedWatchlist = inWatchlist,
            isMarkedWatched = isWatched,
            isLoadingVideosImages = true
        )

        viewModelScope.launch {
            // 先检查登录状态
            isLoggedIn = !tokenStorage.accessToken.first().isNullOrEmpty()
            _uiState.value = _uiState.value.copy(isLoggedIn = isLoggedIn)

            // 从全局缓存校正想看/已看状态（路由参数从推荐列表进入时可能为 false）
            val realInWatchlist = traktRepository.checkInWatchlist(currentTraktId, currentMediaType)
            val realWatched = traktRepository.checkWatched(currentTraktId, currentMediaType)
            _uiState.value = _uiState.value.copy(
                isMarkedWatchlist = realInWatchlist,
                isMarkedWatched = realWatched
            )

            var tmdbRating = 0.0
            var collectionId = 0
            if (tmdbId <= 0) {
                _uiState.value = _uiState.value.copy(isLoading = false, displayTitle = title.replace("+", " "))
                detailLoaded = true
                saveToCache()
                startSearch()
            } else {
                val enrichment: EnrichmentData? = runCatching {
                    when (mediaType) {
                        MediaType.MOVIE -> {
                            val e = tmdbRepository.enrichMovie(tmdbId, title, year)
                            tmdbRating = e.rating
                            // enrichMovie 已返回 collectionId 和 imdbId，无需第二次 getMovieDetail
                            collectionId = e.collectionId ?: 0
                            currentCollectionId = collectionId
                            if (currentImdbId.isBlank()) {
                                e.imdbId?.takeIf { it.isNotBlank() }?.let { currentImdbId = it }
                            }
                            EnrichmentData(e.chineseTitle, e.originalTitle, e.overview, e.genres, e.posterUrl, e.year, e.rating, e.runtime, e.releaseDate, e.country, e.status)
                        }
                        MediaType.SHOW -> {
                            val e = tmdbRepository.enrichTv(tmdbId, title, year)
                            tmdbRating = e.rating
                            // enrichTv 已返回 imdbId，无需第二次 getTvDetail
                            if (currentImdbId.isBlank()) {
                                e.imdbId?.takeIf { it.isNotBlank() }?.let { currentImdbId = it }
                            }
                            EnrichmentData(e.chineseTitle, e.originalTitle, e.overview, e.genres, e.posterUrl, e.year, e.rating, e.episodeRunTime, e.releaseDate, e.country, e.status)
                        }
                        MediaType.PERSON -> null
                        MediaType.DISK -> null
                    }
                }.getOrNull()

                val chineseTitle = enrichment?.chineseTitle?.takeIf { it.isNotEmpty() } ?: title
                currentKeyword = chineseTitle
                currentOriginalTitle = enrichment?.originalTitle?.takeIf { it.isNotEmpty() && it != chineseTitle } ?: ""
                val displayOriginalTitle = currentOriginalTitle.ifEmpty {
                    // 如果 TMDB 没返回 originalTitle 或与中文标题相同，使用 Trakt 原始标题
                    title.takeIf { it.isNotEmpty() && it != chineseTitle.replace("+", " ") } ?: ""
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    displayTitle = chineseTitle.replace("+", " "),
                    originalTitle = displayOriginalTitle,
                    overview = enrichment?.overview ?: "",
                    genres = enrichment?.genres ?: "",
                    country = enrichment?.country ?: "",
                    posterUrl = enrichment?.posterUrl,
                    year = enrichment?.year ?: year,
                    releaseDate = enrichment?.releaseDate ?: "",
                    runtime = enrichment?.runtime,
                    status = enrichment?.status ?: ""
                )
                // 拿到 posterUrl 后立即从缓存预查主色,让沉浸背景先于海报图片加载显示
                prefetchPosterColor(enrichment?.posterUrl)
                detailLoaded = true
                saveToCache()
                startSearch()
            }

            // 根据模块可见性设置，按需加载各模块数据
            val visibility = _uiState.value.sectionVisible

            // 首屏关键数据：评分、评论、季信息、演职员、预告片截图
            if (visibility.myRating) fetchRatingsAsync(tmdbRating)
            if (visibility.comments) fetchComments()
            fetchSeasons()
            if (visibility.cast) fetchCredits()
            if (visibility.videosImages) fetchVideosAndImages()
            if (visibility.myRating) fetchUserRating()

            // 非首屏数据延迟加载，降低进入详情页时的网络请求峰值
            // 用 delayedLoadJob 持有，loadDetail 重入（如快速返回再进入新详情）时取消旧的延迟任务
            delayedLoadJob?.cancel()
            delayedLoadJob = viewModelScope.launch {
                delay(1500)
                if (visibility.recommendations) fetchRecommendations()
                if (currentMediaType == MediaType.MOVIE && collectionId > 0) fetchCollection(collectionId)
            }

            // 预查 doubanId（异步，不阻塞 UI），用于用户标记时同步豆瓣
            prefetchDoubanId()
        }
    }

    /**
     * 预查 doubanId 链路:永久缓存 → 同步表 by imdbId → 详情缓存 → 网络搜索。
     * 结果存入 uiState.doubanIdForSync,用户标记时直接取用。
     */
    private fun prefetchDoubanId() {
        if (currentTraktId <= 0) return
        viewModelScope.launch {
            val mediaTypeStr = if (currentMediaType == MediaType.SHOW) "show" else "movie"
            // 先查同步表（O(1)，零网络）
            val imdbId = currentImdbId.takeIf { it.isNotBlank() }
            if (imdbId != null) {
                val syncedItem = runCatching { doubanSyncedItemDao.getByImdbId(imdbId) }.getOrNull()
                if (syncedItem != null) {
                    _uiState.value = _uiState.value.copy(doubanIdForSync = syncedItem.doubanId)
                    // 顺带写入映射缓存
                    doubanRepository.putDoubanIdMapping(currentTraktId, mediaTypeStr, syncedItem.doubanId)
                    return@launch
                }
            }
            // 再走 Repository 的完整链路（缓存 → 详情缓存 → 网络搜索）
            val doubanId = doubanRepository.findDoubanId(currentTraktId, imdbId, mediaTypeStr)
            if (doubanId != null) {
                _uiState.value = _uiState.value.copy(doubanIdForSync = doubanId)
            }
        }
    }

    private fun fetchRatingsAsync(tmdbRating: Double) {
        ratingsJob?.cancel()
        ratingsJob = viewModelScope.launch {
            ratingsRepository.fetchRatingsStream(
                imdbId = currentImdbId,
                tmdbRating = tmdbRating,
                traktRating = currentTraktRating
            ).collect { ratings ->
                _uiState.value = _uiState.value.copy(ratings = ratings, ratingsError = false)
            }
        }
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
            try {
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
            } catch (_: Exception) {
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
                val updated = _uiState.value.translatedComments.toMutableList().apply { add(translated) }.toList()
                _uiState.value = _uiState.value.copy(
                    translatedComments = updated,
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
        // 未登录跳过观看进度查询
        if (tokenStorage.getCachedAccessToken().isNullOrEmpty()) return
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

    private fun fetchVideosAndImages() {
        if (currentTmdbId <= 0) return
        _uiState.value = _uiState.value.copy(isLoadingVideosImages = true)
        viewModelScope.launch {
            try {
                val videosDeferred = async {
                    when (currentMediaType) {
                        MediaType.MOVIE -> tmdbRepository.getMovieVideos(currentTmdbId)
                        MediaType.SHOW -> tmdbRepository.getTvVideos(currentTmdbId)
                        MediaType.PERSON -> emptyList()
                        MediaType.DISK -> emptyList()
                    }
                }
                val imagesDeferred = async {
                    when (currentMediaType) {
                        MediaType.MOVIE -> tmdbRepository.getMovieImages(currentTmdbId)
                        MediaType.SHOW -> tmdbRepository.getTvImages(currentTmdbId)
                        MediaType.PERSON -> emptyList()
                        MediaType.DISK -> emptyList()
                    }
                }
                // Trakt 数据源（补充）
                val traktVideosDeferred = async {
                    if (currentTraktId > 0) {
                        traktRepository.getVideos(currentTraktId.toString(), currentMediaType)
                            .getOrDefault(emptyList())
                    } else emptyList()
                }
                val traktImagesDeferred = async {
                    if (currentTraktId > 0) {
                        traktRepository.getImages(currentTraktId.toString(), currentMediaType)
                            .getOrDefault(TraktImages())
                    } else TraktImages()
                }

                val videos = videosDeferred.await()
                val images = imagesDeferred.await()
                val traktVideos = traktVideosDeferred.await()
                val traktImagesData = traktImagesDeferred.await()

                // 排序：official Trailer 优先，然后非 official Trailer，然后 Teaser，然后其他
                val sortedVideos = videos
                    .filter { it.site == "YouTube" && it.key.isNotEmpty() }
                    .sortedWith(compareByDescending<TmdbVideo> { it.type == "Trailer" && it.official }
                        .thenByDescending { it.type == "Trailer" }
                        .thenByDescending { it.type == "Teaser" && it.official }
                        .thenByDescending { it.type == "Teaser" }
                        .thenByDescending { it.size })

                // 合并 Trakt 视频（去重，补充 TMDB 没有的）
                val existingYoutubeUrls = sortedVideos.map { "https://www.youtube.com/watch?v=${it.key}" }.toSet()
                val traktExtraVideos = traktVideos
                    .filter { it.site.equals("youtube", ignoreCase = true) && it.url.isNotEmpty() }
                    .filter { it.url !in existingYoutubeUrls }
                    .map { video ->
                        // 从 YouTube URL 提取 video key
                        val key = video.url.substringAfter("v=").substringBefore("&").substringBefore("#")
                        TmdbVideo(
                            id = "trakt_${key}",
                            key = key,
                            name = video.title,
                            site = "YouTube",
                            type = video.type.replaceFirstChar { it.uppercase() },
                            official = video.official,
                            size = video.size
                        )
                    }
                val allVideos = sortedVideos + traktExtraVideos

                // 合并截图：TMDB backdrops + Trakt fanart（去重）
                val backdropUrls = images
                    .map { TmdbImageUrls.build(it.file_path, TmdbImageUrls.W780) }
                    .toMutableList()
                val traktFanartUrls = traktImagesData.fanart
                    .map { if (it.startsWith("http")) it else "https://$it" }
                    .filter { url -> backdropUrls.none { it.contains(url.substringAfterLast("/").substringBefore(".")) } }
                backdropUrls.addAll(traktFanartUrls)

                _uiState.value = _uiState.value.copy(
                    videos = allVideos,
                    backdrops = backdropUrls.take(20),
                    isLoadingVideosImages = false
                )
            } catch (_: Exception) {
                // 加载失败不影响页面正常显示
                _uiState.value = _uiState.value.copy(isLoadingVideosImages = false)
            }
        }
    }

    private fun fetchRecommendations() {
        if (currentTraktId <= 0) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingRecommendations = true)
            // 限制并发数，避免触发 API 限流
            val enrichSemaphore = Semaphore(3)
            try {
                // 并行请求 Trakt related 和 TMDB similar
                val enriched = when (currentMediaType) {
                    MediaType.MOVIE -> {
                        val traktDeferred = async {
                            val result = traktRepository.getRelatedMovies(currentTraktId)
                            val movies = result.getOrDefault(emptyList())
                            movies.map { movie ->
                                async {
                                    enrichSemaphore.withPermit {
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
                                }
                            }.awaitAll()
                        }
                        val tmdbDeferred = async {
                            if (currentTmdbId > 0) {
                                val similar = tmdbRepository.getSimilarMovies(currentTmdbId)
                                similar.map { item ->
                                    val enrichment = tmdbRepository.enrichMovie(
                                        item.id, item.title, item.release_date.take(4).toIntOrNull()
                                    )
                                    RecommendationItem(
                                        traktId = 0,
                                        tmdbId = item.id,
                                        title = item.title,
                                        displayTitle = enrichment.chineseTitle,
                                        year = enrichment.year,
                                        genres = enrichment.genres,
                                        posterUrl = enrichment.posterUrl
                                    )
                                }
                            } else emptyList()
                        }
                        val traktItems = traktDeferred.await()
                        val tmdbItems = tmdbDeferred.await()
                        // 合并去重，TMDB 数据优先
                        val seenTmdbIds = tmdbItems.map { it.tmdbId }.toSet()
                        tmdbItems + traktItems.filter { it.tmdbId !in seenTmdbIds }
                    }
                    MediaType.SHOW -> {
                        val traktDeferred = async {
                            val result = traktRepository.getRelatedShows(currentTraktId)
                            val shows = result.getOrDefault(emptyList())
                            shows.map { show ->
                                async {
                                    enrichSemaphore.withPermit {
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
                                }
                            }.awaitAll()
                        }
                        val tmdbDeferred = async {
                            if (currentTmdbId > 0) {
                                val similar = tmdbRepository.getSimilarShows(currentTmdbId)
                                similar.map { item ->
                                    val enrichment = tmdbRepository.enrichTv(
                                        item.id, item.title, item.release_date.take(4).toIntOrNull()
                                    )
                                    RecommendationItem(
                                        traktId = 0,
                                        tmdbId = item.id,
                                        title = item.title,
                                        displayTitle = enrichment.chineseTitle,
                                        year = enrichment.year,
                                        genres = enrichment.genres,
                                        posterUrl = enrichment.posterUrl
                                    )
                                }
                            } else emptyList()
                        }
                        val traktItems = traktDeferred.await()
                        val tmdbItems = tmdbDeferred.await()
                        val seenTmdbIds = tmdbItems.map { it.tmdbId }.toSet()
                        tmdbItems + traktItems.filter { it.tmdbId !in seenTmdbIds }
                    }
                    MediaType.PERSON -> emptyList()
                    MediaType.DISK -> emptyList()
                }

                val filtered = enriched.filter { it.tmdbId > 0 }

                if (tokenStorage.getCachedAccessToken() != null) {
                    // 已登录：使用全局想看/已看 ID 缓存检查状态（避免重复 API 调用）
                    var cachedIds = traktRepository.getWatchlistWatchedIds()
                    if (cachedIds == null) {
                        // 缓存未加载：先加载
                        cachedIds = traktRepository.loadWatchlistWatchedIds()
                    }
                    val withStatus = filtered.map { item ->
                        item.copy(
                            isInWatchlist = cachedIds.isInWatchlist(item.traktId, item.tmdbId, currentMediaType),
                            isWatched = cachedIds.isWatched(item.traktId, item.tmdbId, currentMediaType)
                        )
                    }
                    _uiState.value = _uiState.value.copy(
                        recommendations = withStatus,
                        isLoadingRecommendations = false
                        // 不覆盖 isMarkedWatchlist/isMarkedWatched——推荐加载不应修改当前影视的标记状态，
                        // 用户在 1.5s 延迟窗口内的手动操作不应被缓存旧值回退
                    )
                } else {
                    // 未登录：跳过状态查询，直接展示推荐列表
                    _uiState.value = _uiState.value.copy(
                        recommendations = filtered,
                        isLoadingRecommendations = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingRecommendations = false,
                    recommendationsError = true
                )
            }
        }
    }

    private fun fetchUserRating() {
        // 未登录跳过用户评分查询
        if (tokenStorage.getCachedAccessToken().isNullOrEmpty()) return
        // traktId 无效(未登录/无 traktId)时跳过,避免崩溃
        if (currentTraktId <= 0) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRatingLoading = true)
            // 优先读取本地评分+短评缓存:命中且 rating/comment 非空则直接初始化 UI,不再走网络
            val local = runCatching { userReviewRepository.getReview(currentTraktId.toLong()) }.getOrNull()
            if (local != null && (local.rating != null || !local.comment.isNullOrBlank())) {
                _uiState.value = _uiState.value.copy(
                    userRating = local.rating?.toInt(),
                    userComment = local.comment,
                    isRatingLoading = false
                )
                return@launch
            }
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
        // 未登录：弹出登录引导
        if (!isLoggedIn) {
            _uiState.value = _uiState.value.copy(showLoginPrompt = true)
            return
        }
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
                    saveToCache()
                    saveUserReviewToLocal(rating, _uiState.value.userComment)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isRating = false)
                }
        }
    }

    /**
     * 提交评分+短评，并同步到豆瓣和 Trakt。
     *
     * - Trakt 评分走 addRating
     * - Trakt 短评走 postComment（comment 非空时才发）
     * - 豆瓣评分+短评走 markWatchedWithRating（一次性传看过+评分+短评）
     *   - 若 pendingDoubanAction == COLLECT（标记已看后首次打分）→ 用 markWatchedWithRating 一次性传
     *   - 否则（已标记过看过，仅修改评分）→ 同样用 markWatchedWithRating 覆盖更新评分+短评
     *
     * @param rating Trakt 1-10 分
     * @param comment 短评（可选，空串表示不写短评）
     */
    fun setRatingWithComment(rating: Int, comment: String) {
        val current = _uiState.value
        if (current.isRating) return
        if (!isLoggedIn) {
            _uiState.value = _uiState.value.copy(showLoginPrompt = true)
            return
        }
        _uiState.value = current.copy(isRating = true, showRatingDialog = false, pendingDoubanAction = null)
        viewModelScope.launch {
            // 1. Trakt 评分
            val traktRatingResult = traktRepository.addRating(currentTraktId, rating, currentMediaType)
            // 2. Trakt 短评（非空时才发）
            var traktCommentResult: Result<*>? = null
            if (comment.isNotBlank()) {
                traktCommentResult = traktRepository.postComment(currentTraktId, currentMediaType, comment)
            }
            // 3. 豆瓣评分+短评（Trakt 评分成功后才同步豆瓣）
            val pending = current.pendingDoubanAction
            if (traktRatingResult.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    userRating = rating,
                    userComment = comment.ifBlank { null },
                    isRating = false,
                    pendingDoubanAction = null
                )
                saveToCache()
                saveUserReviewToLocal(rating, comment.ifBlank { null })
                // 豆瓣同步:评分映射 Trakt 1-10 → 豆瓣 1-5
                val doubanRating = Math.round(rating / 2.0).toInt()
                syncDoubanMarkWithRating(doubanRating, comment, pending == DoubanSyncAction.COLLECT)
            } else {
                _uiState.value = _uiState.value.copy(isRating = false)
            }
        }
    }

    /**
     * 豆瓣评分+短评同步（一次性传看过+评分+短评）。
     *
     * @param doubanRating 1..5 豆瓣五星制
     * @param comment 短评（可选）
     * @param includeCollect 是否同时标记看过（pendingDoubanAction == COLLECT 时为 true）
     */
    private suspend fun syncDoubanMarkWithRating(doubanRating: Int, comment: String, includeCollect: Boolean) {
        val doubanId = _uiState.value.doubanIdForSync
        val cred = doubanAuthStorage.getCredentials()

        if (cred == null || doubanId.isNullOrBlank()) {
            // 豆瓣未就绪 → 显示重试按钮（保留 pendingDoubanAction 供重试）
            _uiState.value = _uiState.value.copy(doubanSyncRetryable = true)
            _toastEvent.emit(R.string.detail_douban_sync_id_not_ready)
            return
        }

        _uiState.value = _uiState.value.copy(isDoubanSyncing = true)
        val ck = doubanRepository.fetchCsrfToken(doubanId, cred.cookie)
        val result = if (ck != null) {
            if (includeCollect) {
                // 一次性传看过+评分+短评
                doubanRepository.markWatchedWithRating(doubanId, cred.cookie, ck, doubanRating, comment).success
            } else {
                // 仅更新评分+短评（豆瓣会覆盖原有 collect 状态 + 更新 rating + comment）
                doubanRepository.markWatchedWithRating(doubanId, cred.cookie, ck, doubanRating, comment).success
            }
        } else false
        _uiState.value = _uiState.value.copy(isDoubanSyncing = false)

        if (result) {
            _uiState.value = _uiState.value.copy(doubanSyncRetryable = false, pendingDoubanAction = null)
            _toastEvent.emit(R.string.detail_douban_sync_success)
        } else {
            _uiState.value = _uiState.value.copy(doubanSyncRetryable = true)
            _toastEvent.emit(R.string.detail_douban_sync_failed)
        }
    }

    /** 取消评分 */
    fun removeRating() {
        val current = _uiState.value
        if (current.isRating) return
        _uiState.value = current.copy(isRating = true, showRatingDialog = false, pendingDoubanAction = null)
        viewModelScope.launch {
            traktRepository.removeRating(currentTraktId, currentMediaType)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        userRating = null,
                        userComment = null,
                        isRating = false
                    )
                    saveToCache()
                    deleteUserReviewFromLocal()
                    // 取消评分后,若有待处理的豆瓣 COLLECT 同步,仅同步看过(无评分无短评)
                    if (current.pendingDoubanAction == DoubanSyncAction.COLLECT) {
                        syncDoubanMark(DoubanSyncAction.COLLECT)
                    }
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isRating = false)
                }
        }
    }

    fun toggleSeason(seasonNumber: Int) {
        val current = _uiState.value.expandedSeasons
        val newExpanded = if (seasonNumber in current) current - seasonNumber else current + seasonNumber

        // 先立即更新展开状态（UI 先展开，显示加载指示器）
        _uiState.value = _uiState.value.copy(expandedSeasons = newExpanded)

        // 如果展开且还没有加载该季的集信息，则异步加载
        if (seasonNumber !in current && seasonNumber !in _uiState.value.episodes) {
            viewModelScope.launch {
                val result = traktRepository.getSeasonEpisodes(currentTraktId, seasonNumber)
                result.onSuccess { episodeList ->
                    // 本地化集标题
                    val localized = localizeEpisodeTitles(episodeList, seasonNumber)
                    val newEpisodes = _uiState.value.episodes.toMutableMap()
                    newEpisodes[seasonNumber] = localized
                    _uiState.value = _uiState.value.copy(episodes = newEpisodes)
                }.onFailure {
                    // 加载失败:回滚展开状态,避免空白展开
                    _uiState.value = _uiState.value.copy(
                        expandedSeasons = _uiState.value.expandedSeasons - seasonNumber
                    )
                }
            }
        }
    }

    /** 为标记已看弹窗加载指定季的集信息（不修改展开状态） */
    fun loadEpisodesForMarkWatched(seasonNumber: Int) {
        if (seasonNumber in _uiState.value.episodes) return

        viewModelScope.launch {
            val result = traktRepository.getSeasonEpisodes(currentTraktId, seasonNumber)
            result.onSuccess { episodeList ->
                val localized = localizeEpisodeTitles(episodeList, seasonNumber)
                val newEpisodes = _uiState.value.episodes.toMutableMap()
                newEpisodes[seasonNumber] = localized
                _uiState.value = _uiState.value.copy(episodes = newEpisodes)
            }
        }
    }

    /** 切换某集已看/取消已看 */
    fun toggleEpisodeWatched(seasonNumber: Int, episodeNumber: Int, episodeTraktId: Int) {
        val current = _uiState.value
        if (current.togglingEpisode == Pair(seasonNumber, episodeNumber)) return

        // 未登录：弹出登录引导
        if (tokenStorage.getCachedAccessToken().isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(showLoginPrompt = true)
            return
        }

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
            // 从设置页读取启用的搜索源（含自定义源），初始化筛选状态
            val storageEnabledSources = resourceRepository.getEnabledSources()
            val customSources = resourceRepository.getEnabledCustomSources()
            val customNames = customSources.associate { it.id to it.name }
            _uiState.value = _uiState.value.copy(
                isSearching = true,
                error = null,
                completedSources = 0,
                totalSources = storageEnabledSources.size,
                availableSources = storageEnabledSources.toList(),
                enabledSources = storageEnabledSources,
                customSourceNames = customNames
            )

            val isShow = currentMediaType == MediaType.SHOW
            val hasEnglish = currentOriginalTitle.isNotEmpty()

            // 线程安全地跟踪已完成的源（中英文搜索共享同一集合，去重计数）
            val completedSourceNames = ConcurrentHashMap.newKeySet<String>()
            val onSourceComplete: (String) -> Unit = { source ->
                completedSourceNames.add(source)
                // 不单独更新 state，在 updateSearchResults 中一并更新减少重组次数
            }

            // conflate: 跳过中间值，只处理最新发射，减少高频更新时的重组
            val chineseFlow = resourceRepository.searchResourcesFlow(
                currentKeyword, isShow = isShow, onSourceComplete = onSourceComplete
            ).conflate()
            val englishFlow = if (hasEnglish) {
                resourceRepository.searchResourcesFlow(
                    currentOriginalTitle, isShow = isShow, onSourceComplete = onSourceComplete
                ).conflate()
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
                            updateSearchResults(chineseItems, englishItems, isShow, completedSourceNames.size)
                        }
                    }
                    val englishJob = launch {
                        englishFlow.collect { items ->
                            englishItems = items
                            if (!firstResultShown && items.isNotEmpty()) {
                                firstResultShown = true
                                _uiState.value = _uiState.value.copy(isSearching = false)
                            }
                            updateSearchResults(chineseItems, englishItems, isShow, completedSourceNames.size)
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
                    updateSearchResults(chineseItems, englishItems, isShow, completedSourceNames.size)
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
        isShow: Boolean,
        completedSourceCount: Int = _uiState.value.completedSources
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
        val filtered = withContext(Dispatchers.Default) {
            resourceRepository.filterItems(
                allResources, state.enabledSources, state.enabledDiskTypes
            )
        }
        val viewedUrls = withContext(Dispatchers.IO) {
            viewedItemStorage.getViewedUrls()
        }
        _uiState.value = _uiState.value.copy(
            searchAttempted = true,
            resources = filtered,
            viewedUrls = viewedUrls,
            completedSources = completedSourceCount
        )
        saveToCache()
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
            // 从设置页读取启用的搜索源（含自定义源），更新筛选状态
            val storageEnabledSources = resourceRepository.getEnabledSources()
            val customSources = resourceRepository.getEnabledCustomSources()
            val customNames = customSources.associate { it.id to it.name }
            val state = _uiState.value.copy(
                availableSources = storageEnabledSources.toList(),
                enabledSources = storageEnabledSources,
                customSourceNames = customNames,
                totalSources = storageEnabledSources.size
            )
            _uiState.value = state.copy(
                isSearching = true,
                error = null,
                completedSources = 0
            )
            val isShow = currentMediaType == MediaType.SHOW

            // 中英文名并行搜索
            val chineseDeferred = async {
                resourceRepository.refreshResources(
                    keyword = currentKeyword,
                    enabledSources = storageEnabledSources,
                    enabledDiskTypes = state.enabledDiskTypes,
                    isShow = isShow
                )
            }
            val englishDeferred = if (currentOriginalTitle.isNotEmpty()) {
                async {
                    resourceRepository.refreshResources(
                        keyword = currentOriginalTitle,
                        enabledSources = storageEnabledSources,
                        enabledDiskTypes = state.enabledDiskTypes,
                        isShow = isShow
                    )
                }
            } else null

            val chineseResult = chineseDeferred.await()
            val englishResult = englishDeferred?.await() ?: Result.success(emptyList())

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
                allResources, storageEnabledSources, _uiState.value.enabledDiskTypes
            )
            _uiState.value = _uiState.value.copy(
                isSearching = false,
                searchAttempted = true,
                resources = filtered
            )
            saveToCache()
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

        // 未登录：弹出登录引导（使用 getCachedAccessToken 同步检查，避免异步时序问题）
        if (tokenStorage.getCachedAccessToken().isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(showLoginPrompt = true)
            return
        }

        // 如果已标记已看，则取消标记
        // removeWatched 会副操作添加到想看列表，因此同时标记 watchlistChanged 以触发返回后刷新想看列表
        if (current.isMarkedWatched) {
            _uiState.value = current.copy(isMarkingWatched = true, watchedChanged = true, watchlistChanged = true)
            viewModelScope.launch {
                traktRepository.removeWatched(currentTraktId, currentMediaType, currentTmdbId)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(
                            isMarkedWatched = false,
                            isMarkedWatchlist = true,
                            isMarkingWatched = false
                        )
                        saveToCache()
                        // 豆瓣双向同步:取消已看→豆瓣标记想看(加回 wish)
                        syncDoubanMark(DoubanSyncAction.REMOVE_COLLECT)
                    }
                    .onFailure {
                        _uiState.value = _uiState.value.copy(
                            isMarkingWatched = false,
                            watchedChanged = false,
                            watchlistChanged = false
                        )
                    }
            }
            return
        }

        // 电视剧：弹出季/集勾选弹窗
        if (currentMediaType == MediaType.SHOW) {
            _uiState.value = _uiState.value.copy(showMarkWatchedDialog = true)
            return
        }

        // 电影：直接标记
        // markAsWatched 会副操作从想看列表移除，因此同时标记 watchlistChanged 以触发返回后刷新想看列表
        _uiState.value = current.copy(isMarkingWatched = true, watchedChanged = true, watchlistChanged = true)
        viewModelScope.launch {
            traktRepository.markAsWatched(currentTraktId, currentMediaType)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isMarkedWatched = true,
                        isMarkedWatchlist = false,
                        isMarkingWatched = false,
                        showRatingDialog = true,
                        // 延迟豆瓣同步:等打分弹窗确认后一次性传看过+评分+短评
                        pendingDoubanAction = DoubanSyncAction.COLLECT
                    )
                    saveToCache()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isMarkingWatched = false,
                        watchedChanged = false,
                        watchlistChanged = false
                    )
                }
        }
    }

    /** 关闭标记已看弹窗 */
    fun dismissMarkWatchedDialog() {
        _uiState.value = _uiState.value.copy(showMarkWatchedDialog = false)
    }

    /** 关闭评分弹窗：用户未打分直接关闭时，若有待处理的豆瓣同步则仅同步看过（无评分无短评） */
    fun dismissRatingDialog() {
        val pending = _uiState.value.pendingDoubanAction
        _uiState.value = _uiState.value.copy(showRatingDialog = false, pendingDoubanAction = null)
        if (pending == DoubanSyncAction.COLLECT) {
            viewModelScope.launch { syncDoubanMark(DoubanSyncAction.COLLECT) }
        }
    }

    /** 提交勾选的季/集为已看 */
    fun submitMarkWatched(selectedEpisodeIds: List<Int>) {
        _uiState.value = _uiState.value.copy(
            showMarkWatchedDialog = false,
            isMarkingWatched = true,
            watchedChanged = true,
            watchlistChanged = true
        )
        viewModelScope.launch {
            traktRepository.markEpisodesWatched(selectedEpisodeIds, currentTraktId, currentTmdbId)
                .onSuccess {
                    // 标记成功后刷新观看进度
                    fetchWatchedProgress()
                    _uiState.value = _uiState.value.copy(
                        isMarkedWatched = true,
                        isMarkedWatchlist = false,
                        isMarkingWatched = false,
                        showRatingDialog = true,
                        // 延迟豆瓣同步:等打分弹窗确认后一次性传看过+评分+短评
                        pendingDoubanAction = DoubanSyncAction.COLLECT
                    )
                    saveToCache()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isMarkingWatched = false,
                        watchedChanged = false,
                        watchlistChanged = false
                    )
                }
        }
    }

    /** 切换想看状态：添加/移除想看 */
    fun toggleWatchlist() {
        val current = _uiState.value
        if (current.isMarkingWatchlist) return

        // 未登录：弹出登录引导（使用 getCachedAccessToken 同步检查，避免异步时序问题）
        if (tokenStorage.getCachedAccessToken().isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(showLoginPrompt = true)
            return
        }

        val targetState = !current.isMarkedWatchlist

        // 已看过时点击想看:从已看列表移除 + 添加到想看列表(把看过变成想看)
        if (targetState && current.isMarkedWatched) {
            _uiState.value = current.copy(
                isMarkingWatchlist = true,
                isMarkingWatched = true,
                watchlistChanged = true,
                watchedChanged = true
            )
            viewModelScope.launch {
                // 先从已看列表移除
                traktRepository.removeWatched(currentTraktId, currentMediaType, currentTmdbId)
                    .onSuccess {
                        // 再添加到想看列表
                        traktRepository.addToWatchlist(currentTraktId, currentMediaType, currentTmdbId)
                            .onSuccess {
                                _uiState.value = _uiState.value.copy(
                                    isMarkedWatched = false,
                                    isMarkedWatchlist = true,
                                    isMarkingWatchlist = false,
                                    isMarkingWatched = false
                                )
                                saveToCache()
                                // 豆瓣双向同步:已看→想看,豆瓣标记为 wish
                                syncDoubanMark(DoubanSyncAction.WISH)
                            }
                            .onFailure {
                                _uiState.value = _uiState.value.copy(
                                    isMarkingWatchlist = false,
                                    isMarkingWatched = false
                                )
                            }
                    }
                    .onFailure {
                        _uiState.value = _uiState.value.copy(
                            isMarkingWatchlist = false,
                            isMarkingWatched = false
                        )
                    }
            }
            return
        }

        _uiState.value = current.copy(
            isMarkingWatchlist = true,
            watchlistChanged = targetState || current.watchlistChanged
        )

        viewModelScope.launch {
            val result = if (targetState) {
                traktRepository.addToWatchlist(currentTraktId, currentMediaType, currentTmdbId)
            } else {
                traktRepository.removeFromWatchlist(currentTraktId, currentMediaType, currentTmdbId)
            }
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isMarkedWatchlist = targetState,
                    isMarkingWatchlist = false
                )
                saveToCache()
                // 豆瓣双向同步:标记想看→豆瓣 wish,取消想看→豆瓣 remove
                syncDoubanMark(if (targetState) DoubanSyncAction.WISH else DoubanSyncAction.REMOVE_WISH)
            } else {
                _uiState.value = _uiState.value.copy(isMarkingWatchlist = false)
            }
        }
    }

    /** 关闭登录引导弹窗 */
    fun dismissLoginPrompt() {
        _uiState.value = _uiState.value.copy(showLoginPrompt = false)
    }

    /**
     * 豆瓣双向同步:在 Trakt 标记成功后,同步更新豆瓣侧状态。
     *
     * - doubanId 未就绪或未登录豆瓣 → 显示重试按钮 + Toast 提示
     * - 豆瓣同步成功 → Toast 提示「豆瓣数据已同步更新」
     * - 豆瓣同步失败 → 显示重试按钮 + Toast 提示
     */
    private suspend fun syncDoubanMark(action: DoubanSyncAction) {
        val doubanId = _uiState.value.doubanIdForSync
        val cred = doubanAuthStorage.getCredentials()

        // 未登录豆瓣或 doubanId 未就绪 → 显示重试按钮
        if (cred == null || doubanId.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                doubanSyncRetryable = true,
                pendingDoubanAction = action
            )
            _toastEvent.emit(R.string.detail_douban_sync_id_not_ready)
            return
        }

        _uiState.value = _uiState.value.copy(isDoubanSyncing = true)
        val result = executeDoubanMark(action, doubanId, cred.cookie)
        _uiState.value = _uiState.value.copy(isDoubanSyncing = false)

        if (result) {
            _uiState.value = _uiState.value.copy(doubanSyncRetryable = false, pendingDoubanAction = null)
            _toastEvent.emit(R.string.detail_douban_sync_success)
        } else {
            _uiState.value = _uiState.value.copy(doubanSyncRetryable = true, pendingDoubanAction = action)
            _toastEvent.emit(R.string.detail_douban_sync_failed)
        }
    }

    /** 执行豆瓣标记操作,返回是否成功 */
    private suspend fun executeDoubanMark(
        action: DoubanSyncAction,
        doubanId: String,
        cookie: String
    ): Boolean {
        // 先获取 csrf token(ck)
        val ck = doubanRepository.fetchCsrfToken(doubanId, cookie) ?: return false
        return when (action) {
            DoubanSyncAction.WISH -> doubanRepository.markInterest("wish", doubanId, cookie, ck).success
            DoubanSyncAction.COLLECT -> doubanRepository.markInterest("collect", doubanId, cookie, ck).success
            DoubanSyncAction.REMOVE_WISH,
            DoubanSyncAction.REMOVE_COLLECT -> doubanRepository.removeMark(doubanId, cookie, ck).success
        }
    }

    /** 用户点击重试按钮:重新执行豆瓣同步 */
    fun retryDoubanSync() {
        val action = _uiState.value.pendingDoubanAction ?: return
        if (_uiState.value.isDoubanSyncing) return
        viewModelScope.launch {
            // 若 doubanId 仍未就绪,先重新预查
            if (_uiState.value.doubanIdForSync.isNullOrBlank()) {
                val mediaTypeStr = if (currentMediaType == MediaType.SHOW) "show" else "movie"
                val imdbId = currentImdbId.takeIf { it.isNotBlank() }
                // 先查同步表
                val syncedItem = if (imdbId != null) {
                    runCatching { doubanSyncedItemDao.getByImdbId(imdbId) }.getOrNull()
                } else null
                val doubanId = syncedItem?.doubanId
                    ?: doubanRepository.findDoubanId(currentTraktId, imdbId, mediaTypeStr)
                if (doubanId != null) {
                    _uiState.value = _uiState.value.copy(doubanIdForSync = doubanId)
                } else {
                    _toastEvent.emit(R.string.detail_douban_sync_id_not_ready)
                    return@launch
                }
            }
            syncDoubanMark(action)
        }
    }

    fun markResourceViewed(url: String) {
        viewModelScope.launch {
            // 用 synchronized 包读-改-写，避免快速多次点击丢状态（#12）
            // markViewed 在锁外调用（suspend 函数不可在 synchronized 内），DAO 串行化保障 IO 原子
            val alreadyViewed = synchronized(viewedUrlsLock) {
                val current = _uiState.value.viewedUrls
                if (url in current) {
                    true
                } else {
                    _uiState.value = _uiState.value.copy(viewedUrls = current + url)
                    false
                }
            }
            if (!alreadyViewed) {
                viewedItemStorage.markViewed(url)
            }
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
        val releaseDate: String = "",
        val country: String = "",
        val status: String = ""
    )

    /** 获取系列信息 */
    private fun fetchCollection(collectionId: Int) {
        viewModelScope.launch {
            val collection = tmdbRepository.getCollection(collectionId)
            if (collection != null) {
                _uiState.value = _uiState.value.copy(collectionInfo = collection)
            }
        }
    }

    // 用 synchronized 包 markResourceViewed 读-改-写，避免快速多次点击丢状态（#12）
    private val viewedUrlsLock = Any()

    override fun onCleared() {
        super.onCleared()
        // 清理静态缓存对应条目，避免 VM 销毁后仍持有完整 UiState 导致内存泄漏
        if (currentTraktId > 0) cacheRemove(currentTraktId)
    }

    /** 使用 TMDB 季详情 API 获取本地化集标题，替换 Trakt 的英文标题 */
    private suspend fun localizeEpisodeTitles(
        episodes: List<TraktEpisode>,
        seasonNumber: Int
    ): List<TraktEpisode> {
        if (currentTmdbId <= 0) return episodes
        // 中文环境下才需要本地化（TMDB 默认就是中文）
        val lang = languageStorage.language.first()
        if (lang == LanguageStorage.LANGUAGE_ENGLISH) return episodes

        val seasonDetail = tmdbRepository.getTvSeasonDetail(currentTmdbId, seasonNumber)
            ?: return episodes
        val titleMap = seasonDetail.episodes.associate { it.episode_number to it.name }
        return episodes.map { ep ->
            val localTitle = titleMap[ep.number]
            if (!localTitle.isNullOrEmpty() && localTitle != ep.title) {
                ep.copy(title = localTitle)
            } else ep
        }
    }

    private fun saveToCache() {
        if (currentTraktId <= 0) return
        cachePut(currentTraktId, CachedDetailData(
            uiState = _uiState.value,
            allResources = allResources,
            currentKeyword = currentKeyword,
            currentOriginalTitle = currentOriginalTitle,
            currentImdbId = currentImdbId,
            currentTraktRating = currentTraktRating,
            currentTmdbId = currentTmdbId,
            currentMediaType = currentMediaType,
            currentCollectionId = currentCollectionId
        ))
    }

    private fun currentMediaTypeStr(): String =
        if (currentMediaType == MediaType.SHOW) "show" else "movie"

    /**
     * 将当前评分/短评写回本地缓存(优先层)。
     * traktId 无效(未登录/无 traktId)时跳过,避免崩溃。
     */
    private fun saveUserReviewToLocal(rating: Int?, comment: String?) {
        if (currentTraktId <= 0) return
        viewModelScope.launch {
            runCatching {
                userReviewRepository.saveReview(
                    UserReviewEntity(
                        traktId = currentTraktId.toLong(),
                        tmdbId = currentTmdbId.takeIf { it > 0 },
                        imdbId = currentImdbId.takeIf { it.isNotBlank() },
                        mediaType = currentMediaTypeStr(),
                        title = currentTitle.takeIf { it.isNotBlank() },
                        year = _uiState.value.year,
                        rating = rating?.toFloat(),
                        comment = comment?.takeIf { it.isNotBlank() },
                        liked = null,
                        createdAt = null,
                        updatedAt = null
                    )
                )
            }
        }
    }

    /** 取消评分时删除本地缓存记录(优先层)。 */
    private fun deleteUserReviewFromLocal() {
        if (currentTraktId <= 0) return
        viewModelScope.launch {
            runCatching { userReviewRepository.deleteReview(currentTraktId.toLong()) }
        }
    }
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
