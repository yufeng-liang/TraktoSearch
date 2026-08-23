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
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.UserReviewEntity
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanDetailPresentation
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.DoubanRexxarMediaType
import com.tracktosearch.data.remote.douban.DoubanRexxarRepository
import com.tracktosearch.data.remote.douban.DoubanRexxarShortComment
import com.tracktosearch.data.remote.douban.mergeDoubanDetail
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
import com.tracktosearch.data.repository.ResourceQuery
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UserReviewRepository
import com.tracktosearch.data.session.SessionMode
import com.tracktosearch.data.session.SessionModeManager
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

/** 详情评分第二槽位的数据来源，解析完成前不渲染可能错误的平台。 */
enum class DetailRatingSource {
    UNKNOWN,
    NORMAL,
    DOUBAN
}

enum class CommentSource {
    DOUBAN,
    FALLBACK
}

enum class OwnCommentTarget {
    TRAKT,
    DOUBAN
}

const val DOUBAN_COMMENT_SOURCE = "Douban"

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
    val lowRelevanceHiddenCount: Int = 0,            // 开启"仅显示高相关"后被隐藏的低相关结果数
    val showHighRelevanceOnly: Boolean = false,      // 是否仅显示高相关结果
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
    val traktCommentId: Int? = null,              // Trakt 本人短评 ID
    val isEditingOwnComment: Boolean = false,
    val isSavingOwnComment: Boolean = false,
    val pendingOwnComment: String? = null,        // 豆瓣未评分时，等待评分弹窗确认的短评
    val ownCommentTargets: Set<OwnCommentTarget> = emptySet(),
    val retryOwnCommentTargets: Set<OwnCommentTarget> = emptySet(),
    val isRating: Boolean = false,               // 是否正在提交评分
    val isRatingLoading: Boolean = false,        // 是否正在加载已有评分
    val error: String? = null,
    val searchAttempted: Boolean = false,
    val ratings: MultiRatings? = null,
    val ratingSource: DetailRatingSource = DetailRatingSource.UNKNOWN,
    val viewedUrls: Set<String> = emptySet(),
    val comments: List<TraktComment> = emptyList(),
    val translatedComments: List<TraktComment> = emptyList(),
    val commentSource: CommentSource = CommentSource.FALLBACK,
    val isTranslating: Boolean = false,
    val translatingCommentId: Int? = null,  // 正在翻译的单条评论ID
    /** 批量翻译进度文案（如 "3/10"），null 表示未在翻译 */
    val translationProgress: String? = null,
    val commentPage: Int = 1,              // 当前 Trakt 评论页码
    val tmdbCommentPage: Int = 1,          // 当前 TMDB 评论页码
    val doubanCommentPage: Int = 1,        // 当前豆瓣评论页码
    val hasMoreComments: Boolean = false,   // 是否还有更多评论
    val isLoadingComments: Boolean = false, // 是否正在加载首屏公共评论
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
    // 成功加入想看后的单调 revision，仅用于触发一次性详情页场景动效
    val watchlistAddedRevision: Long = 0L,
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

/** 场景 revision 只服务当前页面，详情缓存恢复时不能再次播放旧的加入想看动效。 */
internal fun DetailUiState.withoutTransientSceneState(): DetailUiState =
    copy(watchlistAddedRevision = 0L)

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

private data class DetailCacheKey(
    val traktId: Int,
    val tmdbId: Int,
    val doubanId: String?,
    val mediaType: MediaType
)

private data class DoubanDetailSupplement(
    val doubanId: String,
    val item: DoubanSyncedItem?,
    val detail: DoubanDetailCacheEntry?
) {
    val imdbId: String?
        get() = item?.imdbId?.takeIf { it.isNotBlank() } ?: detail?.imdbId?.takeIf { it.isNotBlank() }
    val publicRating: Double?
        get() = detail?.doubanRating?.takeIf { it > 0 }
    val userRating: Int?
        get() = item?.rating?.takeIf { it in 1..5 }?.times(2)
    val title: String?
        get() = item?.displayTitle?.takeIf { it.isNotBlank() }
            ?: item?.title?.takeIf { it.isNotBlank() }
            ?: detail?.title?.takeIf { it.isNotBlank() }
    val genres: String?
        get() = item?.genres?.takeIf { it.isNotBlank() }
            ?: detail?.genres?.takeIf { it.isNotEmpty() }?.joinToString(" / ")
    val year: Int?
        get() = item?.year ?: detail?.year?.take(4)?.toIntOrNull()
    val posterUrl: String?
        get() = item?.posterUrl?.takeIf { it.isNotBlank() }
            ?: detail?.posterUrl?.takeIf { it.isNotBlank() }
    val overview: String?
        get() = detail?.summary?.takeIf { it.isNotBlank() }
    val country: String?
        get() = detail?.countries?.takeIf { it.isNotEmpty() }?.joinToString(" / ")
    val originalTitle: String?
        get() = item?.subtitle?.takeIf { it.isNotBlank() }
    val runtimeMinutes: Int?
        get() = detail?.runtime?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
}

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
    private val doubanRexxarRepository: DoubanRexxarRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    // 会话模式管理器(豆瓣独立模式分支判断)
    private val sessionModeManager: SessionModeManager,
    // 海报主色调提取器(对 DetailHeaderContent 暴露,用于在海报加载成功后提取主色)
    val posterColorExtractor: PosterColorExtractor,
    // 本地评分+短评缓存(优先读取 Trakt 之外的本地值,提交/更新时写回)
    private val userReviewRepository: UserReviewRepository
) : ViewModel() {

    companion object {
        private const val CACHE_MAX_SIZE = 3
        private const val COMMENT_PAGE_SIZE = 10
        private const val OWN_COMMENT_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        @Suppress("UNCHECKED_CAST")
        private val detailCache: MutableMap<DetailCacheKey, CachedDetailData> = java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<DetailCacheKey, CachedDetailData>(CACHE_MAX_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DetailCacheKey, CachedDetailData>?): Boolean = size > CACHE_MAX_SIZE
            }
        )

        private fun cachePut(key: DetailCacheKey, value: CachedDetailData) {
            detailCache[key] = value  // removeEldestEntry auto-evict
        }

        private fun cacheGet(key: DetailCacheKey): CachedDetailData? {
            return detailCache[key]
        }

        private fun cacheRemove(key: DetailCacheKey) {
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
            val currentCollectionId: Int = 0,
            val currentDoubanId: String? = null
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
            isLoggedIn = resolveLoginState()
            _uiState.value = _uiState.value.copy(isLoggedIn = isLoggedIn)
        }
        // 收集会话模式,供 toggleWatched/toggleWatchlist 同步判断豆瓣独立模式分支
        // 同时用于豆瓣模式隐藏 trakt 专属模块（评论 tab 依赖 trakt API，豆瓣模式无 trakt token 会失败）
        viewModelScope.launch {
            sessionModeManager.sessionMode.collect { mode ->
                currentSessionMode = mode
                isLoggedIn = resolveLoginState()
                _uiState.value = _uiState.value.copy(isLoggedIn = isLoggedIn)
            }
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
    // 资源相关度评分用的目标影视上下文（搜索开始时构造）
    private var currentResourceQuery: ResourceQuery? = null
    // 当前搜索结果的相关度分值表（url -> score），与 allResources 同生命周期
    private var currentScoreMap: Map<String, Int> = emptyMap()
    // 当前搜索结果的高相关资格表；规则由 Repository 统一计算，详情页只负责本地过滤
    private var currentHighRelevanceMap: Map<String, Boolean> = emptyMap()
    private var currentImdbId: String = ""
    private var currentTraktRating: Double = 0.0
    private var currentTmdbId: Int = 0
    private var currentCollectionId: Int = 0
    private var currentDoubanId: String? = null
    private var currentDoubanRating: Double? = null
    private var currentDoubanUserRating: Int? = null

    private suspend fun resolveLoginState(): Boolean =
        if (currentSessionMode == SessionMode.DOUBAN) {
            doubanAuthStorage.getCredentials() != null
        } else {
            !tokenStorage.accessToken.first().isNullOrEmpty()
        }
    private var currentDetailCacheKey: DetailCacheKey? = null
    private var detailLoaded: Boolean = false
    private var searchJob: Job? = null
    private var ratingsJob: Job? = null
    private var commentsJob: Job? = null
    private var ownCommentJob: Job? = null
    private var seasonsJob: Job? = null
    private var delayedLoadJob: Job? = null
    private var doubanIdPrefetchJob: Job? = null
    private var doubanRexxarJob: Job? = null
    // 全量结果（未按 filter 过滤）
    private var allResources: List<ResourceItem> = emptyList()
    private var isLoggedIn: Boolean = false
    // 当前会话模式(在 init 中收集,供 toggleWatched/toggleWatchlist 同步判断豆瓣模式分支)
    // 初始 null:sessionMode flow 未发出首值前降级到 trakt 原逻辑,避免误判
    private var currentSessionMode: SessionMode? = null

    suspend fun loadDetail(traktId: Int, tmdbId: Int, title: String, mediaType: MediaType, year: Int? = null, imdbId: String = "", traktRating: Double = 0.0, inWatchlist: Boolean = false, isWatched: Boolean = false, doubanId: String? = null) {
        val cacheKey = DetailCacheKey(
            traktId = traktId,
            tmdbId = tmdbId,
            doubanId = doubanId?.takeIf { it.isNotBlank() },
            mediaType = mediaType
        )
        // 已加载相同影视则用缓存（从子详情页返回时不重新请求）
        if (currentDetailCacheKey == cacheKey && detailLoaded) return
        doubanIdPrefetchJob?.cancel()

        // 尝试从静态缓存恢复
        val cached = cacheGet(cacheKey)
        if (cached != null) {
            currentDetailCacheKey = cacheKey
            currentTraktId = traktId
            currentMediaType = cached.currentMediaType
            currentTitle = cached.uiState.title
            currentKeyword = cached.currentKeyword
            currentOriginalTitle = cached.currentOriginalTitle
            currentImdbId = cached.currentImdbId
            currentTraktRating = cached.currentTraktRating
            currentTmdbId = cached.currentTmdbId
            currentCollectionId = cached.currentCollectionId
            currentDoubanId = cached.currentDoubanId ?: cached.uiState.doubanIdForSync
            currentDoubanRating = cached.uiState.ratings?.doubanRating
            currentDoubanUserRating = cached.uiState.userRating.takeIf { currentDoubanId != null }
            detailLoaded = true
            allResources = cached.allResources
            currentScoreMap = emptyMap()
            currentHighRelevanceMap = emptyMap()
            // 从全局缓存获取真实的想看/已看状态，不依赖路由参数（从推荐列表进入时默认为 false）
            // 豆瓣独立模式从本地 douban_synced_items 表读取,其他模式走 trakt API
            val (realInWatchlist, realWatched) = resolveWatchStates(traktId, cached.currentMediaType, cached.currentImdbId)
            val restoredRatingSource = if (
                cached.uiState.ratingSource == DetailRatingSource.UNKNOWN &&
                !currentDoubanId.isNullOrBlank()
            ) {
                DetailRatingSource.DOUBAN
            } else {
                cached.uiState.ratingSource
            }
            _uiState.value = cached.uiState.withoutTransientSceneState().copy(
                isMarkedWatchlist = realInWatchlist,
                isMarkedWatched = realWatched,
                ratingSource = restoredRatingSource
            )
            // 海报 TMDB 优先：旧缓存若残留豆瓣海报 URL 且条目有 TMDB(tmdbId>0)，异步补拉 TMDB 海报替换；
            // 纯豆瓣条目(tmdbId=0)无 TMDB 海报，保留豆瓣海报
            val cachedPoster = cached.uiState.posterUrl
            if (cachedPoster != null && isDoubanPosterUrl(cachedPoster) && cached.currentTmdbId > 0) {
                viewModelScope.launch {
                    val tmdbPoster = fetchTmdbPosterUrl(cached.currentTmdbId, cached.uiState.title, cached.uiState.year)
                    if (tmdbPoster != null && currentDetailCacheKey == cacheKey) {
                        _uiState.value = _uiState.value.copy(posterUrl = tmdbPoster)
                        saveToCache()
                    }
                }
            }
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
            if (_uiState.value.ratingSource == DetailRatingSource.UNKNOWN) {
                prefetchDoubanId()
            }
            currentDoubanId?.let { startDoubanRexxarLoad(cacheKey, it) }
            // 按需补启附属 fetch:缓存可能是在附属请求完成前被写入的(用户中途返回),
            // 此时 ratings/comments/credits/videos/seasons 等字段为空,需要重新拉取避免永久卡在骨架状态
            val visibility = cached.uiState.sectionVisible
            startOwnCommentLoad()
            if (visibility.myRating && cached.uiState.ratings == null) fetchRatingsAsync(cached.currentTraktRating)
            if (visibility.comments && cached.uiState.comments.isEmpty()) fetchComments()
            if (cached.uiState.seasons.isEmpty() && cached.currentMediaType == MediaType.SHOW) fetchSeasons()
            if (visibility.cast && cached.uiState.cast.isEmpty() && cached.uiState.crew.isEmpty()) fetchCredits()
            if (visibility.videosImages && (cached.uiState.isLoadingVideosImages || (cached.uiState.videos.isEmpty() && cached.uiState.backdrops.isEmpty()))) fetchVideosAndImages()
            // 评分同步读取 Room DB（持久化缓存），命中则立即设置 userRating 消除"先 null 后有值"窗口
            // 未命中时降级到异步 fetchUserRating（走 Trakt 网络）
            if (visibility.myRating && currentTraktId > 0 && cached.uiState.userRating == null && currentDoubanUserRating == null) {
                val localReview = withContext(Dispatchers.IO) {
                    runCatching { userReviewRepository.getReview(currentTraktId.toLong()) }.getOrNull()
                }
                if (localReview != null && (localReview.rating != null || !localReview.comment.isNullOrBlank())) {
                    _uiState.value = _uiState.value.copy(
                        userRating = localReview.rating?.toInt(),
                        // 空白短评归一为 null:userComment 全局约定「无短评 = null」,
                        // 留着空串会在写豆瓣本地表时把已存的备注覆盖成空
                        userComment = localReview.comment?.takeIf { it.isNotBlank() },
                        traktCommentId = localReview.traktCommentId,
                        isRatingLoading = false
                    )
                } else {
                    fetchUserRating()
                }
            }
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
        currentDetailCacheKey = cacheKey
        currentMediaType = mediaType
        currentTitle = title
        currentKeyword = title
        currentImdbId = imdbId
        currentTraktRating = traktRating
        currentTmdbId = tmdbId
        currentCollectionId = 0
        currentOriginalTitle = ""
        currentDoubanId = doubanId?.takeIf { it.isNotBlank() }
        currentDoubanRating = null
        currentDoubanUserRating = null
        detailLoaded = false
        allResources = emptyList()
        currentScoreMap = emptyMap()
        currentHighRelevanceMap = emptyMap()

        _uiState.value = DetailUiState(
            isLoading = true,
            isSearching = true,
            title = title.replace("+", " "),
            displayTitle = title.replace("+", " "),
            year = year,
            isMarkedWatchlist = inWatchlist,
            isMarkedWatched = isWatched,
            isLoadingVideosImages = true,
            doubanIdForSync = currentDoubanId,
            ratingSource = if (currentDoubanId != null) {
                DetailRatingSource.DOUBAN
            } else {
                DetailRatingSource.UNKNOWN
            }
        )

        // 本人短评独立于公共评论加载：先命中本地缓存，再按 TTL 后台校准。
        startOwnCommentLoad()

        viewModelScope.launch {
            val doubanSupplement = loadDoubanSupplement(currentDoubanId, currentImdbId)
            if (doubanSupplement != null) {
                currentDoubanId = doubanSupplement.doubanId
                doubanSupplement.imdbId?.let { currentImdbId = it }
                currentDoubanRating = doubanSupplement.publicRating
                currentDoubanUserRating = doubanSupplement.userRating
                val current = _uiState.value
                _uiState.value = current.copy(
                    title = doubanSupplement.title ?: current.title,
                    displayTitle = doubanSupplement.title ?: current.displayTitle,
                    originalTitle = doubanSupplement.originalTitle ?: current.originalTitle,
                    year = doubanSupplement.year ?: current.year,
                    overview = doubanSupplement.overview ?: current.overview,
                    genres = doubanSupplement.genres ?: current.genres,
                    country = doubanSupplement.country ?: current.country,
                    // 海报先以豆瓣兜底，后续 TMDB enrichment 存在时覆盖为 TMDB（TMDB 优先）
                    posterUrl = current.posterUrl ?: doubanSupplement.posterUrl,
                    runtime = doubanSupplement.runtimeMinutes ?: current.runtime,
                    userRating = doubanSupplement.userRating ?: current.userRating,
                    userComment = doubanSupplement.item?.comment ?: current.userComment,
                    isRatingLoading = doubanSupplement.userRating == null && current.isRatingLoading,
                    ratings = current.ratings?.copy(doubanRating = doubanSupplement.publicRating)
                        ?: MultiRatings(doubanRating = doubanSupplement.publicRating),
                    doubanIdForSync = doubanSupplement.doubanId,
                    ratingSource = DetailRatingSource.DOUBAN
                )
            } else if (currentDoubanId != null) {
                _uiState.value = _uiState.value.copy(
                    doubanIdForSync = currentDoubanId,
                    ratingSource = DetailRatingSource.DOUBAN
                )
            }

            currentDoubanId?.let { startDoubanRexxarLoad(cacheKey, it) }

            // 先检查登录状态
            isLoggedIn = resolveLoginState()
            _uiState.value = _uiState.value.copy(isLoggedIn = isLoggedIn)

            // 从全局缓存校正想看/已看状态（路由参数从推荐列表进入时可能为 false）
            // 豆瓣独立模式从本地 douban_synced_items 表读取,其他模式走 trakt API
            val (realInWatchlist, realWatched) = resolveWatchStates(currentTraktId, currentMediaType, currentImdbId)
            _uiState.value = _uiState.value.copy(
                isMarkedWatchlist = realInWatchlist,
                isMarkedWatched = realWatched
            )

            var tmdbRating = 0.0
            var collectionId = 0
            if (tmdbId <= 0) {
                // 纯豆瓣条目(tmdbId=0)：无 TMDB 海报，保持 supplement 已填入的豆瓣海报
                _uiState.value = _uiState.value.copy(isLoading = false)
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

                // 主标题以 TMDB 中文标题为准，与列表卡片 displayTitle 保持一致；
                // 豆瓣标题仅作纯豆瓣条目（无 TMDB 中文标题）时的兜底。
                val chineseTitle = enrichment?.chineseTitle?.takeIf { it.isNotEmpty() }
                    ?: doubanSupplement?.title
                    ?: title
                currentKeyword = chineseTitle
                // 原名以 TMDB 外文原名为准（与主标题同源），豆瓣副标题仅作纯豆瓣条目兜底
                currentOriginalTitle = enrichment?.originalTitle?.takeIf { it.isNotEmpty() && it != chineseTitle }
                    ?: doubanSupplement?.originalTitle
                    ?: ""
                val displayOriginalTitle = currentOriginalTitle.ifEmpty {
                    // 如果 TMDB 没返回 originalTitle 或与中文标题相同，使用 Trakt 原始标题
                    title.takeIf { it.isNotEmpty() && it != chineseTitle.replace("+", " ") } ?: ""
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    displayTitle = chineseTitle.replace("+", " "),
                    originalTitle = displayOriginalTitle,
                    overview = doubanSupplement?.overview ?: enrichment?.overview ?: "",
                    // 类型以 TMDB 为准（与列表卡片 genres 一致），豆瓣类型仅作纯豆瓣条目兜底
                    genres = enrichment?.genres ?: doubanSupplement?.genres ?: "",
                    country = doubanSupplement?.country ?: enrichment?.country ?: "",
                    // 海报 TMDB 优先；纯豆瓣条目(tmdbId=0 无 TMDB 海报)时用豆瓣海报兜底
                    posterUrl = enrichment?.posterUrl ?: doubanSupplement?.posterUrl,
                    year = doubanSupplement?.year ?: enrichment?.year ?: year,
                    releaseDate = enrichment?.releaseDate ?: "",
                    runtime = doubanSupplement?.runtimeMinutes ?: enrichment?.runtime,
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
            if (visibility.myRating && currentDoubanUserRating == null) fetchUserRating()

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
    private suspend fun loadDoubanSupplement(
        doubanId: String?,
        imdbId: String
    ): DoubanDetailSupplement? {
        val item = if (!doubanId.isNullOrBlank()) {
            runCatching { doubanSyncedItemDao.getByDoubanId(doubanId) }.getOrNull()
        } else if (imdbId.isNotBlank()) {
            runCatching { doubanSyncedItemDao.getByImdbId(imdbId) }.getOrNull()
        } else {
            null
        }
        val resolvedDoubanId = doubanId?.takeIf { it.isNotBlank() } ?: item?.doubanId
        if (resolvedDoubanId.isNullOrBlank()) return null

        val detail = runCatching {
            doubanRepository.getDetailSnapshot()[resolvedDoubanId]
        }.getOrNull()
        return DoubanDetailSupplement(
            doubanId = resolvedDoubanId,
            item = item,
            detail = detail
        )
    }

    private suspend fun setResolvedDoubanId(doubanId: String) {
        val publicRating = runCatching {
            doubanRepository.getDetailSnapshot()[doubanId]?.doubanRating?.takeIf { it > 0.0 }
        }.getOrNull()
        currentDoubanId = doubanId
        currentDoubanRating = publicRating
        _uiState.value = _uiState.value.copy(
            doubanIdForSync = doubanId,
            ratingSource = DetailRatingSource.DOUBAN,
            ratings = _uiState.value.ratings?.copy(doubanRating = publicRating)
        )
        currentDetailCacheKey?.let { startDoubanRexxarLoad(it, doubanId) }
        if (_uiState.value.sectionVisible.comments && _uiState.value.comments.isEmpty()) {
            fetchComments()
        }
    }

    /** Rexxar 的公开数据只做增量补充，网络失败时不清空已有详情或剧照。 */
    private fun startDoubanRexxarLoad(expectedKey: DetailCacheKey, doubanId: String) {
        val rexxarType = when (currentMediaType) {
            MediaType.MOVIE -> DoubanRexxarMediaType.MOVIE
            MediaType.SHOW -> DoubanRexxarMediaType.TV
            MediaType.PERSON, MediaType.DISK -> return
        }
        doubanRexxarJob?.cancel()
        doubanRexxarJob = viewModelScope.launch {
            val detailDeferred = async {
                doubanRexxarRepository.getDetail(doubanId, rexxarType)
            }
            val photosDeferred = async {
                doubanRexxarRepository.getPhotos(doubanId, rexxarType, start = 0, count = 20)
            }
            val detailResult = detailDeferred.await()
            val photosResult = photosDeferred.await()

            if (currentDetailCacheKey != expectedKey || currentDoubanId != doubanId) return@launch

            detailResult.getOrNull()?.let { rexxarDetail ->
                val supplement = loadDoubanSupplement(doubanId, currentImdbId)
                val merged = mergeDoubanDetail(
                    rexxar = rexxarDetail,
                    html = supplement?.detail,
                    snapshot = supplement?.item,
                    existing = currentDoubanPresentation()
                )
                currentImdbId = merged.imdbId ?: currentImdbId
                currentDoubanRating = merged.score ?: currentDoubanRating
                applyDoubanPresentation(merged, doubanId)
            }

            photosResult.getOrNull()?.let { photoPage ->
                val urls = photoPage.photos.mapNotNull { photo ->
                    photo.largeUrl?.takeIf { it.isNotBlank() }
                        ?: photo.normalUrl?.takeIf { it.isNotBlank() }
                        ?: photo.smallUrl?.takeIf { it.isNotBlank() }
                }.distinct().take(20)
                if (urls.isNotEmpty() && currentDetailCacheKey == expectedKey && currentDoubanId == doubanId) {
                    _uiState.value = _uiState.value.copy(backdrops = urls)
                }
            }
            saveToCache()
        }
    }

    private fun currentDoubanPresentation(): DoubanDetailPresentation {
        val state = _uiState.value
        val genres = state.genres
            .split(Regex("\\s*(?:/|·)\\s*"))
            .map(String::trim)
            .filter(String::isNotEmpty)
        return DoubanDetailPresentation(
            doubanId = currentDoubanId,
            mediaType = when (currentMediaType) {
                MediaType.MOVIE -> DoubanRexxarMediaType.MOVIE
                MediaType.SHOW -> DoubanRexxarMediaType.TV
                MediaType.PERSON, MediaType.DISK -> null
            },
            title = state.displayTitle.takeIf { it.isNotBlank() },
            originalTitle = state.originalTitle.takeIf { it.isNotBlank() },
            year = state.year,
            releaseDates = listOfNotNull(state.releaseDate.takeIf { it.isNotBlank() }),
            genres = genres,
            countries = listOfNotNull(state.country.takeIf { it.isNotBlank() }),
            overview = state.overview.takeIf { it.isNotBlank() },
            score = currentDoubanRating ?: state.ratings?.doubanRating,
            runtime = state.runtime?.toString(),
            imdbId = currentImdbId.takeIf { it.isNotBlank() },
            posterUrl = state.posterUrl
        )
    }

    private fun applyDoubanPresentation(presentation: DoubanDetailPresentation, doubanId: String) {
        val current = _uiState.value
        // 主标题保持列表一致的 TMDB 标题；豆瓣合并仅补充年份/简介等元数据，不再覆盖主标题
        val title = current.displayTitle.takeIf { it.isNotBlank() }
            ?: presentation.title ?: current.displayTitle
        val genres = presentation.genres.takeIf { it.isNotEmpty() }?.joinToString(" / ")
        val country = presentation.countries.takeIf { it.isNotEmpty() }?.joinToString(" / ")
        val runtime = presentation.runtime?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        val ratings = presentation.score?.let { score ->
            (current.ratings ?: MultiRatings()).copy(doubanRating = score)
        } ?: current.ratings
        currentKeyword = title
        // 原名保持加载时确定的 TMDB 外文原名，不再被 rexxar/豆瓣合并覆盖
        _uiState.value = current.copy(
            title = title,
            displayTitle = title,
            originalTitle = current.originalTitle,
            year = presentation.year ?: current.year,
            releaseDate = presentation.releaseDates.firstOrNull() ?: current.releaseDate,
            overview = presentation.overview ?: current.overview,
            // 类型保持列表一致的 TMDB 类型，rexxar 不再用豆瓣类型覆盖
            genres = current.genres.takeIf { it.isNotBlank() } ?: genres ?: "",
            country = country ?: current.country,
            // 海报保持当前值（TMDB 优先，纯豆瓣为豆瓣图），rexxar 合并不再替换为豆瓣图
            posterUrl = current.posterUrl ?: presentation.posterUrl,
            runtime = runtime ?: current.runtime,
            ratings = ratings,
            doubanIdForSync = doubanId,
            ratingSource = DetailRatingSource.DOUBAN
        )
        prefetchPosterColor(_uiState.value.posterUrl)
    }

    private fun prefetchDoubanId(expectedKey: DetailCacheKey? = currentDetailCacheKey) {
        if (expectedKey == null || currentDetailCacheKey != expectedKey) return
        doubanIdPrefetchJob?.cancel()
        val requestTraktId = currentTraktId
        val requestMediaType = currentMediaType
        val requestImdbId = currentImdbId.takeIf { it.isNotBlank() }
        val requestTmdbId = currentTmdbId
        val existingDoubanId = currentDoubanId
        existingDoubanId?.let { doubanId ->
            doubanIdPrefetchJob = viewModelScope.launch {
                if (currentDetailCacheKey != expectedKey) return@launch
                setResolvedDoubanId(doubanId)
                if (currentDetailCacheKey == expectedKey) saveToCache()
            }
            return
        }
        doubanIdPrefetchJob = viewModelScope.launch {
            if (currentDetailCacheKey != expectedKey) return@launch
            val mediaTypeStr = if (requestMediaType == MediaType.SHOW) "show" else "movie"
            // 先查同步表（O(1)，零网络）
            val imdbId = requestImdbId
            if (imdbId != null) {
                val syncedItem = runCatching { doubanSyncedItemDao.getByImdbId(imdbId) }.getOrNull()
                if (syncedItem != null) {
                    if (currentDetailCacheKey != expectedKey) return@launch
                    setResolvedDoubanId(syncedItem.doubanId)
                    // 顺带写入映射缓存
                    doubanRepository.putDoubanIdMapping(
                        traktId = requestTraktId,
                        mediaType = mediaTypeStr,
                        doubanId = syncedItem.doubanId,
                        imdbId = imdbId,
                        tmdbId = requestTmdbId
                    )
                    if (currentDetailCacheKey == expectedKey) saveToCache()
                    return@launch
                }
            }
            // 再走 Repository 的完整链路（缓存 → 详情缓存 → 网络搜索）
            val doubanId = doubanRepository.findDoubanId(
                traktId = requestTraktId,
                imdbId = imdbId,
                mediaType = mediaTypeStr,
                tmdbId = requestTmdbId
            )
            if (currentDetailCacheKey != expectedKey) return@launch
            if (doubanId != null) {
                setResolvedDoubanId(doubanId)
            } else {
                currentDoubanId = null
                _uiState.value = _uiState.value.copy(
                    doubanIdForSync = null,
                    ratingSource = DetailRatingSource.NORMAL
                )
            }
            saveToCache()
        }
    }

    /**
     * 解析当前条目的想看/已看状态。
     *
     * 豆瓣独立模式:从本地 douban_synced_items 表读取
     *   - status="wish" → isInWatchlist=true
     *   - status="collect" → isWatched=true
     * 其他模式:走 traktRepository.checkInWatchlist/checkWatched
     *
     * @return Pair(inWatchlist, watched)
     */
    private suspend fun resolveWatchStates(
        traktId: Int,
        mediaType: MediaType,
        imdbId: String
    ): Pair<Boolean, Boolean> {
        return if (currentSessionMode == SessionMode.DOUBAN || (traktId <= 0 && currentDoubanId != null)) {
            if (imdbId.isBlank()) {
                val syncedItem = currentDoubanId?.let {
                    runCatching { doubanSyncedItemDao.getByDoubanId(it) }.getOrNull()
                }
                Pair(
                    syncedItem?.status == "wish",
                    syncedItem?.status == "collect"
                )
            } else {
                val syncedItem = currentDoubanId?.let {
                    runCatching { doubanSyncedItemDao.getByDoubanId(it) }.getOrNull()
                } ?: runCatching { doubanSyncedItemDao.getByImdbId(imdbId) }.getOrNull()
                Pair(
                    syncedItem?.status == "wish",
                    syncedItem?.status == "collect"
                )
            }
        } else if (!sessionModeManager.traktConnected.value || traktId <= 0) {
            Pair(false, false)
        } else {
            Pair(
                traktRepository.checkInWatchlist(traktId, mediaType),
                traktRepository.checkWatched(traktId, mediaType)
            )
        }
    }

    /** 豆瓣条目优先走豆瓣标记接口；双登录时仅对没有有效 Trakt ID 的条目启用。 */
    private fun isDoubanBackedItem(): Boolean {
        if (currentSessionMode == SessionMode.DOUBAN) return true
        val doubanId = currentDoubanId ?: _uiState.value.doubanIdForSync
        return currentTraktId <= 0 && !doubanId.isNullOrBlank()
    }

    /** 普通详情没有有效 Trakt ID 时禁止把占位 ID 传给写接口。 */
    private fun canWriteToTrakt(): Boolean {
        if (currentTraktId > 0) return true
        viewModelScope.launch { _toastEvent.emit(R.string.detail_load_error) }
        return false
    }

    /** 判断海报 URL 是否来自豆瓣，用于识别旧缓存中的豆瓣海报并回退到 TMDB。 */
    private fun isDoubanPosterUrl(url: String): Boolean = url.contains("doubanio.com") || url.contains("douban.com")

    /** 从 TMDB 重新拉取海报 URL（走 TtlCache，通常命中缓存不触发网络）。 */
    private suspend fun fetchTmdbPosterUrl(tmdbId: Int, title: String?, year: Int?): String? {
        if (tmdbId <= 0) return null
        return runCatching {
            when (currentMediaType) {
                MediaType.MOVIE -> tmdbRepository.enrichMovie(tmdbId, title.orEmpty(), year).posterUrl
                MediaType.SHOW -> tmdbRepository.enrichTv(tmdbId, title.orEmpty(), year).posterUrl
                else -> null
            }
        }.getOrNull()
    }

    private fun fetchRatingsAsync(tmdbRating: Double) {
        ratingsJob?.cancel()
        ratingsJob = viewModelScope.launch {
            try {
                ratingsRepository.fetchRatingsStream(
                    imdbId = currentImdbId,
                    tmdbRating = tmdbRating,
                    traktRating = currentTraktRating
                ).collect { ratings ->
                    _uiState.value = _uiState.value.copy(
                        ratings = ratings.copy(doubanRating = currentDoubanRating),
                        ratingsError = false
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(ratingsError = true)
            }
        }
    }

    private fun fetchComments() {
        commentsJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isLoadingComments = true,
            commentsError = false
        )
        commentsJob = viewModelScope.launch {
            try {
                val doubanType = when (currentMediaType) {
                    MediaType.MOVIE -> DoubanRexxarMediaType.MOVIE
                    MediaType.SHOW -> DoubanRexxarMediaType.TV
                    MediaType.PERSON, MediaType.DISK -> null
                }
                val doubanPage = currentDoubanId?.let { doubanId ->
                    doubanType?.let { type ->
                        doubanRexxarRepository.getShortComments(
                            doubanId,
                            type,
                            start = 0,
                            count = COMMENT_PAGE_SIZE
                        ).getOrNull()
                    }
                }
                val doubanComments = doubanPage?.comments
                    ?.map { it.toTraktComment() }
                    .orEmpty()
                if (doubanPage != null && doubanComments.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        comments = doubanComments,
                        translatedComments = emptyList(),
                        commentSource = CommentSource.DOUBAN,
                        commentPage = 0,
                        tmdbCommentPage = 0,
                        doubanCommentPage = 1,
                        hasMoreComments = doubanPage.start + doubanComments.size < doubanPage.total,
                        isLoadingComments = false,
                        commentsError = false
                    )
                    return@launch
                }

                // 并行加载 Trakt 和 TMDB 评论（首页各取少量）
                val traktDeferred = async {
                    if (currentSessionMode != SessionMode.DOUBAN &&
                        sessionModeManager.traktConnected.value &&
                        currentTraktId > 0
                    ) {
                        traktRepository.getComments(currentTraktId, currentMediaType, limit = COMMENT_PAGE_SIZE, page = 1)
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
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
                    commentSource = CommentSource.FALLBACK,
                    commentPage = 1,
                    tmdbCommentPage = 1,
                    doubanCommentPage = 0,
                    hasMoreComments = hasMoreTrakt || hasMoreTmdb,
                    isLoadingComments = false,
                    commentsError = false
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingComments = false,
                    commentsError = true
                )
            }
        }
    }

    /** 加载更多评论（下一页，Trakt 和 TMDB 并行） */
    fun loadMoreComments() {
        val current = _uiState.value
        if (!current.hasMoreComments || current.isLoadingMoreComments) return

        if (current.commentSource == CommentSource.DOUBAN) {
            loadMoreDoubanComments(current)
            return
        }

        val nextTraktPage = current.commentPage + 1
        val nextTmdbPage = current.tmdbCommentPage + 1
        _uiState.value = current.copy(isLoadingMoreComments = true)

        viewModelScope.launch {
            try {
                // 判断是否还有更多 Trakt/TMDB 评论
                val canLoadTraktComments =
                    currentSessionMode != SessionMode.DOUBAN &&
                        sessionModeManager.traktConnected.value &&
                        currentTraktId > 0
                val hasMoreTrakt = current.commentPage > 0 && canLoadTraktComments // 首页满 10 条时 commentPage=1，可以继续
                val hasMoreTmdb = current.tmdbCommentPage > 0

                val traktDeferred = async {
                    if (hasMoreTrakt) {
                        traktRepository.getComments(currentTraktId, currentMediaType, limit = COMMENT_PAGE_SIZE, page = nextTraktPage)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMoreComments = false)
            }
        }
    }

    private fun loadMoreDoubanComments(current: DetailUiState) {
        val doubanId = currentDoubanId ?: _uiState.value.doubanIdForSync
        val doubanType = when (currentMediaType) {
            MediaType.MOVIE -> DoubanRexxarMediaType.MOVIE
            MediaType.SHOW -> DoubanRexxarMediaType.TV
            MediaType.PERSON, MediaType.DISK -> null
        }
        if (doubanId.isNullOrBlank() || doubanType == null) {
            _uiState.value = current.copy(isLoadingMoreComments = false, hasMoreComments = false)
            return
        }

        val nextStart = current.doubanCommentPage.coerceAtLeast(1) * COMMENT_PAGE_SIZE
        _uiState.value = current.copy(isLoadingMoreComments = true)
        viewModelScope.launch {
            try {
                val page = doubanRexxarRepository.getShortComments(
                    doubanId,
                    doubanType,
                    start = nextStart,
                    count = COMMENT_PAGE_SIZE
                ).getOrNull()
                if (page == null) {
                    _uiState.value = _uiState.value.copy(isLoadingMoreComments = false)
                    return@launch
                }
                val newComments = page.comments.map { it.toTraktComment() }
                val existingIds = _uiState.value.comments.map { it.id }.toSet()
                val uniqueNew = newComments.filter { it.id !in existingIds }
                val hasMore = page.start + newComments.size < page.total
                _uiState.value = _uiState.value.copy(
                    comments = _uiState.value.comments + uniqueNew,
                    doubanCommentPage = current.doubanCommentPage.coerceAtLeast(1) + 1,
                    hasMoreComments = hasMore,
                    isLoadingMoreComments = false,
                    commentsError = false
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMoreComments = false)
            }
        }
    }

    /** 用户点击翻译按钮时调用，按需翻译全部评论（流式更新，先翻完的先展示） */
    fun translateComments() {
        // 豆瓣评论基本都是中文，无需翻译，全部翻译时跳过
        val comments = _uiState.value.comments.filter { it.source != DOUBAN_COMMENT_SOURCE }
        if (comments.isEmpty()) return
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isTranslating = true,
                translationProgress = "0/${comments.size}"
            )
            // 用数组按原顺序收集译文，每收到一条就更新 UiState
            val results = arrayOfNulls<TraktComment>(comments.size)
            var completed = 0
            try {
                commentTranslator.translateCommentsFlow(comments).collect { (index, translated) ->
                    val original = comments.getOrNull(index)
                    if (original == null || original.comment != translated.comment) {
                        results[index] = translated
                    }
                    completed++
                    // 当前已完成的译文列表（保持原顺序，跳过未完成的 null）
                    val current = results.mapNotNull { it }
                    _uiState.value = _uiState.value.copy(
                        translatedComments = current,
                        translationProgress = "$completed/${comments.size}"
                    )
                }
                _uiState.value = _uiState.value.copy(
                    isTranslating = false,
                    translationProgress = null
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DetailVM", "Translation failed", e)
                _uiState.value = _uiState.value.copy(
                    isTranslating = false,
                    translationProgress = null
                )
            }
        }
    }

    /** 用户点击单条评论的翻译按钮时调用 */
    fun translateSingleComment(commentId: Int) {
        val comment = _uiState.value.comments.find { it.id == commentId } ?: return
        // 豆瓣评论基本都是中文，不需要翻译
        if (comment.source == DOUBAN_COMMENT_SOURCE) return
        // 已翻译过则不重复
        if (_uiState.value.translatedComments.any { it.id == commentId }) return

        _uiState.value = _uiState.value.copy(translatingCommentId = commentId)
        viewModelScope.launch {
            try {
                val translated = commentTranslator.translateSingleComment(comment)
                val updated = _uiState.value.translatedComments.toMutableList()
                if (translated.comment != comment.comment) updated.add(translated)
                _uiState.value = _uiState.value.copy(
                    translatedComments = updated.toList(),
                    translatingCommentId = null
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DetailVM", "Single comment translation failed", e)
                _uiState.value = _uiState.value.copy(translatingCommentId = null)
            }
        }
    }

    private fun fetchSeasons() {
        if (currentMediaType != MediaType.SHOW || currentTraktId <= 0 || !sessionModeManager.traktConnected.value) return
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
        if (!sessionModeManager.traktConnected.value) return
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
            }.onFailure { Log.w("DetailViewModel", "fetchWatchedProgress failed", it) }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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

                val currentDoubanBackdrops = _uiState.value.backdrops
                _uiState.value = _uiState.value.copy(
                    videos = allVideos,
                    backdrops = if (currentDoubanId != null && currentDoubanBackdrops.isNotEmpty()) {
                        currentDoubanBackdrops
                    } else {
                        backdropUrls.take(20)
                    },
                    isLoadingVideosImages = false
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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

                if (sessionModeManager.traktConnected.value) {
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingRecommendations = false,
                    recommendationsError = true
                )
            }
        }
    }

    /** 本人短评独立缓存链路，不依赖公共评论接口。 */
    private fun startOwnCommentLoad() {
        ownCommentJob?.cancel()
        val detailTraktId = currentTraktId
        if (detailTraktId <= 0) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(ownCommentTargets = resolveOwnCommentTargets())
            }
            return
        }
        ownCommentJob = viewModelScope.launch {
            val local = withContext(Dispatchers.IO) {
                runCatching { userReviewRepository.getReview(detailTraktId.toLong()) }.getOrNull()
            }
            if (detailTraktId != currentTraktId) return@launch

            val targets = resolveOwnCommentTargets()
            if (local != null) {
                _uiState.value = _uiState.value.copy(
                    // 同上：空白短评归一为 null，避免空串覆盖豆瓣本地表已存备注
                    userComment = local.comment?.takeIf { it.isNotBlank() } ?: _uiState.value.userComment,
                    traktCommentId = local.traktCommentId ?: _uiState.value.traktCommentId,
                    ownCommentTargets = targets
                )
            } else {
                _uiState.value = _uiState.value.copy(ownCommentTargets = targets)
            }

            val isFresh = local?.commentCheckedAt?.let {
                System.currentTimeMillis() - it < OWN_COMMENT_CACHE_TTL_MS
            } == true
            if (OwnCommentTarget.TRAKT !in targets || isFresh) return@launch

            val remote = traktRepository.findMyCommentForItem(detailTraktId, currentMediaType)
                .getOrNull()
            if (detailTraktId != currentTraktId) return@launch
            val checkedAt = System.currentTimeMillis()
            if (remote != null) {
                _uiState.value = _uiState.value.copy(
                    userComment = remote.comment,
                    traktCommentId = remote.id,
                    ownCommentTargets = targets
                )
                cacheOwnComment(remote.comment, remote.id, checkedAt)
            } else {
                // 只记录校准时间；豆瓣侧已有短评时不清空它。
                cacheOwnComment(local?.comment, local?.traktCommentId, checkedAt)
            }
        }
    }

    private suspend fun resolveOwnCommentTargets(): Set<OwnCommentTarget> {
        val targets = linkedSetOf<OwnCommentTarget>()
        if (currentSessionMode != SessionMode.DOUBAN &&
            sessionModeManager.traktConnected.value && currentTraktId > 0
        ) {
            targets += OwnCommentTarget.TRAKT
        }
        if (doubanAuthStorage.getCredentials() != null) {
            targets += OwnCommentTarget.DOUBAN
        }
        return targets
    }

    private suspend fun cacheOwnComment(comment: String?, traktCommentId: Int?, checkedAt: Long) {
        if (currentTraktId <= 0) return
        val existing = userReviewRepository.getReview(currentTraktId.toLong())
        val state = _uiState.value
        val base = existing ?: UserReviewEntity(
            traktId = currentTraktId.toLong(),
            tmdbId = currentTmdbId.takeIf { it > 0 },
            imdbId = currentImdbId.takeIf { it.isNotBlank() },
            mediaType = currentMediaTypeStr(),
            title = currentTitle.takeIf { it.isNotBlank() },
            year = state.year,
            rating = state.userRating?.toFloat(),
            comment = null,
            liked = null,
            createdAt = null,
            updatedAt = null
        )
        userReviewRepository.saveReview(
            base.copy(
                comment = comment?.takeIf { it.isNotBlank() },
                traktCommentId = traktCommentId,
                commentCheckedAt = checkedAt,
                rating = state.userRating?.toFloat() ?: base.rating
            )
        )
    }

    fun beginOwnCommentEdit() {
        _uiState.value = _uiState.value.copy(isEditingOwnComment = true)
    }

    fun cancelOwnCommentEdit() {
        _uiState.value = _uiState.value.copy(isEditingOwnComment = false)
    }

    fun submitOwnComment(comment: String) {
        val normalizedComment = comment.trim()
        val current = _uiState.value
        if (normalizedComment.isBlank() || current.isSavingOwnComment) return
        ownCommentJob?.cancel()
        viewModelScope.launch {
            val targets = resolveOwnCommentTargets()
            if (targets.isEmpty()) {
                _uiState.value = _uiState.value.copy(showLoginPrompt = true)
                return@launch
            }
            if (OwnCommentTarget.DOUBAN in targets && current.userRating == null) {
                _uiState.value = _uiState.value.copy(
                    pendingOwnComment = normalizedComment,
                    ownCommentTargets = targets,
                    showRatingDialog = true
                )
                return@launch
            }
            saveOwnCommentToTargets(normalizedComment, targets)
        }
    }

    fun retryOwnCommentSync() {
        val current = _uiState.value
        val comment = current.userComment ?: return
        if (current.isSavingOwnComment || current.retryOwnCommentTargets.isEmpty()) return
        viewModelScope.launch {
            val availableTargets = resolveOwnCommentTargets()
            val targets = current.retryOwnCommentTargets.intersect(availableTargets)
            if (targets.isEmpty()) {
                _uiState.value = _uiState.value.copy(showLoginPrompt = true)
                return@launch
            }
            saveOwnCommentToTargets(comment, targets)
        }
    }

    fun confirmRatingWithComment(rating: Int?, comment: String) {
        if (rating == null || rating == 0) {
            if (_uiState.value.pendingOwnComment != null) {
                viewModelScope.launch { _toastEvent.emit(R.string.detail_own_comment_rating_required) }
                return
            }
            removeRating()
            return
        }
        setRatingWithComment(rating, comment)
    }

    private suspend fun saveOwnCommentToTargets(comment: String, targets: Set<OwnCommentTarget>) {
        val current = _uiState.value
        val displayTargets = resolveOwnCommentTargets()
        _uiState.value = current.copy(
            isSavingOwnComment = true,
            ownCommentTargets = displayTargets,
            retryOwnCommentTargets = emptySet()
        )
        val (traktResult, doubanSuccess) = coroutineScope {
            val traktDeferred = if (OwnCommentTarget.TRAKT in targets) {
                async { upsertTraktComment(comment, current.traktCommentId) }
            } else null
            val doubanDeferred = if (OwnCommentTarget.DOUBAN in targets) {
                async { updateDoubanOwnComment(comment) }
            } else null
            Pair(traktDeferred?.await(), doubanDeferred?.await())
        }

        val failedTargets = buildSet {
            if (OwnCommentTarget.TRAKT in targets && traktResult?.isSuccess != true) add(OwnCommentTarget.TRAKT)
            if (OwnCommentTarget.DOUBAN in targets && doubanSuccess != true) add(OwnCommentTarget.DOUBAN)
        }
        val traktCommentId = traktResult?.getOrNull()?.id ?: current.traktCommentId
        _uiState.value = _uiState.value.copy(
            userComment = comment,
            traktCommentId = traktCommentId,
            pendingOwnComment = null,
            isEditingOwnComment = false,
            isSavingOwnComment = false,
            ownCommentTargets = displayTargets,
            retryOwnCommentTargets = failedTargets,
            isMarkedWatched = if (OwnCommentTarget.DOUBAN in targets) true else _uiState.value.isMarkedWatched,
            watchedChanged = if (OwnCommentTarget.DOUBAN in targets) true else _uiState.value.watchedChanged
        )
        cacheOwnComment(comment, traktCommentId, System.currentTimeMillis())
        if (OwnCommentTarget.DOUBAN in targets) {
            _uiState.value.doubanIdForSync?.let { doubanId ->
                upsertDoubanSyncedItem(
                    doubanId = doubanId,
                    status = "collect",
                    pendingSync = OwnCommentTarget.DOUBAN in failedTargets
                )
            }
        }
        saveToCache()
    }

    private suspend fun upsertTraktComment(comment: String, knownCommentId: Int?): Result<TraktComment> {
        val commentId = knownCommentId
            ?: traktRepository.findMyCommentForItem(currentTraktId, currentMediaType).getOrNull()?.id
        return if (commentId != null) {
            traktRepository.editComment(commentId, comment)
        } else {
            traktRepository.postComment(currentTraktId, currentMediaType, comment)
        }
    }

    private suspend fun updateDoubanOwnComment(comment: String): Boolean {
        val rating = _uiState.value.userRating ?: return false
        val doubanId = _uiState.value.doubanIdForSync ?: return false
        val credentials = doubanAuthStorage.getCredentials() ?: return false
        val ck = doubanRepository.fetchCsrfToken(doubanId, credentials.cookie) ?: return false
        val doubanRating = Math.round(rating / 2.0).toInt()
        return doubanRepository.markWatchedWithRating(
            doubanId,
            credentials.cookie,
            ck,
            doubanRating,
            comment
        ).success
    }

    private fun fetchUserRating() {
        // 未登录跳过用户评分查询
        if (!sessionModeManager.traktConnected.value) return
        // traktId 无效(未登录/无 traktId)时跳过,避免崩溃
        if (currentTraktId <= 0) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRatingLoading = true)
            // 优先读取本地评分+短评缓存:命中且 rating/comment 非空则直接初始化 UI,不再走网络
            val local = runCatching { userReviewRepository.getReview(currentTraktId.toLong()) }.getOrNull()
            if (local != null && (local.rating != null || !local.comment.isNullOrBlank())) {
                _uiState.value = _uiState.value.copy(
                    userRating = local.rating?.toInt(),
                    // 同上：空白短评归一为 null，避免空串覆盖豆瓣本地表已存备注
                    userComment = local.comment?.takeIf { it.isNotBlank() },
                    traktCommentId = local.traktCommentId,
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isRatingLoading = false)
            }
        }
    }

    /** 提交评分（1-10 分） */
    fun setRating(rating: Int) {
        val current = _uiState.value
        if (current.isRating) return
        // 豆瓣独立模式:评分走豆瓣 markWatchedWithRating(豆瓣评分=看过耦合) + 写本地表
        if (isDoubanBackedItem()) {
            // 点击与当前评分相同 → 取消评分
            if (current.userRating == rating) {
                removeRating()
                return
            }
            setRatingDouban(rating, comment = null)
            return
        }
        // 未登录：弹出登录引导
        if (!isLoggedIn) {
            _uiState.value = _uiState.value.copy(showLoginPrompt = true)
            return
        }
        if (!canWriteToTrakt()) return
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
        // 豆瓣独立模式:评分+短评走豆瓣 markWatchedWithRating + 写本地表
        if (isDoubanBackedItem()) {
            setRatingDouban(rating, comment = comment)
            return
        }
        if (!isLoggedIn) {
            _uiState.value = _uiState.value.copy(showLoginPrompt = true)
            return
        }
        if (!canWriteToTrakt()) return
        _uiState.value = current.copy(isRating = true, showRatingDialog = false, pendingDoubanAction = null)
        viewModelScope.launch {
            val traktRatingResult = traktRepository.addRating(currentTraktId, rating, currentMediaType)
            if (traktRatingResult.isFailure) {
                _uiState.value = _uiState.value.copy(isRating = false)
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                userRating = rating,
                isRating = false,
                pendingDoubanAction = null,
                pendingOwnComment = null
            )
            if (comment.isBlank()) {
                saveToCache()
                saveUserReviewToLocal(rating, _uiState.value.userComment)
                val doubanRating = Math.round(rating / 2.0).toInt()
                syncDoubanMarkWithRating(doubanRating, "", current.pendingDoubanAction == DoubanSyncAction.COLLECT)
                return@launch
            }

            // 评分已成功后，短评按当前登录平台独立同步并分别记录失败端。
            saveOwnCommentToTargets(comment, resolveOwnCommentTargets())
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
        // 豆瓣独立模式:取消评分需重新 markCollect 不带 rating(豆瓣评分与看过耦合,清除评分保留看过)
        if (isDoubanBackedItem()) {
            removeRatingDouban()
            return
        }
        // 未登录：弹出登录引导（与 setRating 保持一致）
        if (!isLoggedIn) {
            _uiState.value = _uiState.value.copy(showLoginPrompt = true)
            return
        }
        if (!canWriteToTrakt()) return
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
        if (currentTraktId <= 0) return
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
        if (currentTraktId <= 0) return
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

        // 豆瓣独立模式: 剧集标记依赖 trakt API,豆瓣网页 API 不支持单集标记
        // 直接 return 避免触发 showLoginPrompt 错误引导用户去登录 Trakt
        if (currentSessionMode == SessionMode.DOUBAN) return
        // 未登录：弹出登录引导
        if (tokenStorage.getCachedAccessToken().isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(showLoginPrompt = true)
            return
        }
        if (!canWriteToTrakt()) return

        val isWatched = episodeNumber in (current.watchedEpisodeNumbers[seasonNumber] ?: emptySet())
        _uiState.value = current.copy(togglingEpisode = Pair(seasonNumber, episodeNumber))

        viewModelScope.launch {
            val result = if (isWatched) {
                traktRepository.unmarkEpisodeWatched(
                    episodeTraktId,
                    season = seasonNumber,
                    episode = episodeNumber,
                    showTraktId = currentTraktId,
                    showTmdbId = currentTmdbId,
                    showTitle = currentTitle
                )
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
     * 构造资源相关度评分用的目标影视查询上下文。
     * 从当前 UI 状态提取导演/演员名作为强相关信号（标题命中导演名明显指向同一作品）。
     * 导演取 job=Director 的 crew，演员取前 5 位（避免过多名字堆分）。
     */
    private fun buildResourceQuery(): ResourceQuery {
        val state = _uiState.value
        val directors = state.crew.filter { it.job == "Director" }.map { it.name }.filter { it.isNotBlank() }
        val castNames = state.cast.take(5).map { it.name }.filter { it.isNotBlank() }
        return ResourceQuery(
            title = currentKeyword,
            originalTitle = currentOriginalTitle.takeIf { it.isNotEmpty() },
            year = state.year,
            country = state.country.takeIf { it.isNotEmpty() },
            mediaType = currentMediaType,
            directors = directors,
            cast = castNames
        )
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

            // 构造目标影视查询上下文，用于资源相关度评分（缺字段时对应信号自动跳过）
            val resourceQuery = buildResourceQuery()
            currentResourceQuery = resourceQuery

            // 线程安全地跟踪已完成的源（中英文搜索共享同一集合，去重计数）
            val completedSourceNames = ConcurrentHashMap.newKeySet<String>()
            val onSourceComplete: (String) -> Unit = { source ->
                completedSourceNames.add(source)
                // 不单独更新 state，在 updateSearchResults 中一并更新减少重组次数
            }

            // conflate: 跳过中间值，只处理最新发射，减少高频更新时的重组
            val chineseFlow = resourceRepository.searchResourcesFlow(
                currentKeyword, isShow = isShow, query = resourceQuery, onSourceComplete = onSourceComplete
            ).conflate()
            val englishFlow = if (hasEnglish) {
                resourceRepository.searchResourcesFlow(
                    currentOriginalTitle, isShow = isShow, query = resourceQuery, onSourceComplete = onSourceComplete
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
            val ranked = resourceRepository.mergeAndCacheResources(
                keyword = currentKeyword,
                items = mergedItems,
                isShow = isShow,
                query = currentResourceQuery
            )
            allResources = ranked.items
            currentScoreMap = ranked.scoreMap
            currentHighRelevanceMap = ranked.highRelevanceMap
        } else {
            allResources = resourceRepository.getCachedAllResources(currentKeyword)
            currentScoreMap = emptyMap()
            currentHighRelevanceMap = emptyMap()
        }

        val state = _uiState.value
        val filtered = withContext(Dispatchers.Default) {
            resourceRepository.filterItems(
                allResources, state.enabledSources, state.enabledDiskTypes
            )
        }
        // 按"仅显示高相关"开关过滤，并统计被隐藏的低相关数量
        val (displayed, hiddenCount) = applyHighRelevanceFilter(filtered, state.showHighRelevanceOnly)
        val viewedUrls = withContext(Dispatchers.IO) {
            viewedItemStorage.getViewedUrls()
        }
        _uiState.value = _uiState.value.copy(
            searchAttempted = true,
            resources = displayed,
            lowRelevanceHiddenCount = hiddenCount,
            viewedUrls = viewedUrls,
            completedSources = completedSourceCount
        )
        saveToCache()
    }

    /**
     * 应用"仅显示高相关"过滤：低于阈值的结果隐藏，返回（展示列表, 隐藏数量）。
     * 若无目标影视上下文（query 为空，通用搜索），不隐藏。
     * 资格从 [currentHighRelevanceMap] 读取（url -> Boolean），避免 ResourceItem 作为可变共享状态。
     */
    private fun applyHighRelevanceFilter(
        items: List<ResourceItem>,
        onlyHigh: Boolean
    ): Pair<List<ResourceItem>, Int> {
        if (!onlyHigh || currentResourceQuery == null || currentHighRelevanceMap.isEmpty()) return items to 0
        val kept = items.filter { currentHighRelevanceMap[it.url] == true }
        return kept to (items.size - kept.size)
    }

    /**
     * 按当前源/网盘筛选 + "仅显示高相关"过滤，返回展示列表与被隐藏数量。
     * 始终从 [allResources] 重新过滤，避免开关切换时复用已被旧开关过滤的子集导致低相关项丢失。
     */
    private fun applyCurrentFilters(): Pair<List<ResourceItem>, Int> {
        val state = _uiState.value
        val filtered = resourceRepository.filterItems(allResources, state.enabledSources, state.enabledDiskTypes)
        return applyHighRelevanceFilter(filtered, state.showHighRelevanceOnly)
    }

    /**
     * 本地过滤当前已缓存的全量结果，不调 API（保留原签名供复用）
     */
    private fun applyLocalFilter(): List<ResourceItem> = applyCurrentFilters().first

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

            // 构造目标影视查询上下文（资源相关度评分）
            val resourceQuery = buildResourceQuery()
            currentResourceQuery = resourceQuery

            // 中英文名并行搜索
            val chineseDeferred = async {
                resourceRepository.refreshResources(
                    keyword = currentKeyword,
                    enabledSources = storageEnabledSources,
                    enabledDiskTypes = state.enabledDiskTypes,
                    isShow = isShow,
                    query = resourceQuery
                )
            }
            val englishDeferred = if (currentOriginalTitle.isNotEmpty()) {
                async {
                    resourceRepository.refreshResources(
                        keyword = currentOriginalTitle,
                        enabledSources = storageEnabledSources,
                        enabledDiskTypes = state.enabledDiskTypes,
                        isShow = isShow,
                        query = resourceQuery
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
                val ranked = resourceRepository.mergeAndCacheResources(
                    keyword = currentKeyword,
                    items = mergedItems,
                    isShow = isShow,
                    query = resourceQuery
                )
                allResources = ranked.items
                currentScoreMap = ranked.scoreMap
                currentHighRelevanceMap = ranked.highRelevanceMap
            } else {
                allResources = resourceRepository.getCachedAllResources(currentKeyword)
                currentScoreMap = emptyMap()
                currentHighRelevanceMap = emptyMap()
            }

            val filtered = resourceRepository.filterItems(
                allResources, storageEnabledSources, _uiState.value.enabledDiskTypes
            )
            val (displayed, hiddenCount) = applyHighRelevanceFilter(filtered, _uiState.value.showHighRelevanceOnly)
            _uiState.value = _uiState.value.copy(
                isSearching = false,
                searchAttempted = true,
                resources = displayed,
                lowRelevanceHiddenCount = hiddenCount
            )
            saveToCache()
        }
    }

    /**
     * 切换"仅显示高相关"开关。
     * 关键：必须从 [allResources] 重新过滤，不能用已被旧开关过滤的列表，
     * 否则从"仅高相关"切回"全部"时低相关项已丢失无法恢复。
     */
    fun toggleShowHighRelevanceOnly() {
        val newVal = !_uiState.value.showHighRelevanceOnly
        val state = _uiState.value
        val filtered = resourceRepository.filterItems(allResources, state.enabledSources, state.enabledDiskTypes)
        val (displayed, hiddenCount) = applyHighRelevanceFilter(filtered, newVal)
        _uiState.value = _uiState.value.copy(
            showHighRelevanceOnly = newVal,
            resources = displayed,
            lowRelevanceHiddenCount = hiddenCount
        )
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
        val (displayed, hiddenCount) = run {
            val filtered = resourceRepository.filterItems(
                allResources, newSources, _uiState.value.enabledDiskTypes
            )
            applyHighRelevanceFilter(filtered, _uiState.value.showHighRelevanceOnly)
        }
        _uiState.value = _uiState.value.copy(
            enabledSources = newSources,
            resources = displayed,
            lowRelevanceHiddenCount = hiddenCount
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
        val (displayed, hiddenCount) = run {
            val filtered = resourceRepository.filterItems(
                allResources, _uiState.value.enabledSources, newTypes
            )
            applyHighRelevanceFilter(filtered, _uiState.value.showHighRelevanceOnly)
        }
        _uiState.value = _uiState.value.copy(
            enabledDiskTypes = newTypes,
            resources = displayed,
            lowRelevanceHiddenCount = hiddenCount
        )
    }

    /** 切换标记已看/取消标记 */
    fun toggleWatched() {
        val current = _uiState.value
        if (current.isMarkingWatched) return

        // 豆瓣独立模式:不检查 trakt token(网关已激活是豆瓣模式前提),走本地表 + 豆瓣 API
        if (isDoubanBackedItem()) {
            toggleWatchedDouban()
            return
        }
        // 未登录：弹出登录引导（使用 getCachedAccessToken 同步检查，避免异步时序问题）
        if (tokenStorage.getCachedAccessToken().isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(showLoginPrompt = true)
            return
        }
        if (!canWriteToTrakt()) return

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
                        publishWatchlistMutation(TraktRepository.WatchlistMutationAction.ADD)
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
                    publishWatchlistMutation(TraktRepository.WatchlistMutationAction.REMOVE)
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
        _uiState.value = _uiState.value.copy(
            showRatingDialog = false,
            pendingDoubanAction = null,
            pendingOwnComment = null
        )
        if (pending == DoubanSyncAction.COLLECT) {
            viewModelScope.launch { syncDoubanMark(DoubanSyncAction.COLLECT) }
        }
    }

    /** 提交勾选的季/集为已看 */
    fun submitMarkWatched(selectedEpisodeIds: List<Int>) {
        // 豆瓣承载条目没有可用的 Trakt 剧集 ID，不能把剧集批量操作发给 Trakt。
        if (isDoubanBackedItem()) {
            _uiState.value = _uiState.value.copy(showMarkWatchedDialog = false)
            return
        }
        if (!canWriteToTrakt()) {
            _uiState.value = _uiState.value.copy(showMarkWatchedDialog = false)
            return
        }
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

        // 豆瓣独立模式:不检查 trakt token(网关已激活是豆瓣模式前提),走本地表 + 豆瓣 API
        if (isDoubanBackedItem()) {
            toggleWatchlistDouban()
            return
        }

        // 未登录：弹出登录引导（使用 getCachedAccessToken 同步检查，避免异步时序问题）
        if (tokenStorage.getCachedAccessToken().isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(showLoginPrompt = true)
            return
        }
        if (!canWriteToTrakt()) return

        val targetState = !current.isMarkedWatchlist
        // 已看过时点击想看：标记 watched 正在变更（addToWatchlist 内部副操作会从 watched 移除）
        val willChangeWatched = targetState && current.isMarkedWatched

        _uiState.value = current.copy(
            isMarkingWatchlist = true,
            isMarkingWatched = willChangeWatched,
            // 添加和移除都必须通知上级详情页和 Watchlist 页刷新
            watchlistChanged = true,
            watchedChanged = willChangeWatched || current.watchedChanged
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
                    // 乐观设置已看为 false（副操作失败由下次全量拉取纠正，对称于 markAsWatched 副操作失败处理）
                    isMarkedWatched = if (willChangeWatched) false else _uiState.value.isMarkedWatched,
                    isMarkingWatchlist = false,
                    isMarkingWatched = false,
                    watchlistAddedRevision = if (targetState) {
                        _uiState.value.watchlistAddedRevision + 1L
                    } else {
                        _uiState.value.watchlistAddedRevision
                    }
                )
                publishWatchlistMutation(
                    if (targetState) TraktRepository.WatchlistMutationAction.ADD
                    else TraktRepository.WatchlistMutationAction.REMOVE
                )
                saveToCache()
                // 豆瓣双向同步:标记想看→豆瓣 wish,取消想看→豆瓣 remove
                syncDoubanMark(if (targetState) DoubanSyncAction.WISH else DoubanSyncAction.REMOVE_WISH)
            } else {
                _uiState.value = _uiState.value.copy(
                    isMarkingWatchlist = false,
                    isMarkingWatched = false
                )
            }
        }
    }

    // ===== 豆瓣独立模式标记操作 =====
    // 豆瓣独立模式下不调 trakt API,直接调豆瓣标记 API + 写本地 douban_synced_items 表。
    // doubanId 来自 uiState.doubanIdForSync(loadDetail 时 prefetchDoubanId 预查)。
    // 豆瓣 API 失败用乐观更新:本地表立即反映,pendingSync=true 供下次同步重试。

    /**
     * 豆瓣独立模式:切换想看状态。
     *
     * - 添加想看: markWish → 写表 status="wish"
     * - 取消想看: removeInterest → 删除本地记录
     * - doubanId 未就绪 → 显示重试按钮(保留 pendingDoubanAction)
     * - API 失败 → 乐观更新本地表,pendingSync=true
     */
    private fun toggleWatchlistDouban() {
        val current = _uiState.value
        if (current.isMarkingWatchlist) return

        val doubanId = current.doubanIdForSync
        if (doubanId.isNullOrBlank()) {
            // doubanId 未就绪 → 显示重试按钮,保留动作供 retryDoubanSync 复用
            _uiState.value = current.copy(
                doubanSyncRetryable = true,
                pendingDoubanAction = if (current.isMarkedWatchlist) DoubanSyncAction.REMOVE_WISH else DoubanSyncAction.WISH
            )
            viewModelScope.launch { _toastEvent.emit(R.string.detail_douban_sync_id_not_ready) }
            return
        }

        val cred = doubanAuthStorage.getCredentials()
        if (cred == null) {
            // 豆瓣未登录(理论上豆瓣模式前提是已登录,此为防御性检查)
            viewModelScope.launch { _toastEvent.emit(R.string.detail_douban_sync_failed) }
            return
        }

        val targetState = !current.isMarkedWatchlist
        _uiState.value = current.copy(
            isMarkingWatchlist = true,
            watchlistChanged = targetState || current.watchlistChanged
        )

        viewModelScope.launch {
            val success = if (targetState) {
                doubanRepository.markWish(doubanId, cred.cookie)
            } else {
                doubanRepository.removeInterest(doubanId, cred.cookie)
            }

            if (success) {
                if (targetState) {
                    // 添加想看成功:写本地表 status="wish",pendingSync=false
                    upsertDoubanSyncedItem(doubanId, status = "wish", pendingSync = false)
                } else {
                    // 取消想看成功:删除本地记录
                    runCatching { doubanSyncedItemDao.deleteByDoubanId(doubanId) }
                }
            } else {
                // API 失败:乐观更新本地表,pendingSync=true(下次同步重试)
                if (targetState) {
                    upsertDoubanSyncedItem(doubanId, status = "wish", pendingSync = true)
                } else {
                    // 取消失败仍保持乐观删除，但保留即时重试动作，避免豆瓣侧标记无法撤销。
                    runCatching { doubanSyncedItemDao.deleteByDoubanId(doubanId) }
                    _uiState.value = _uiState.value.copy(
                        doubanSyncRetryable = true,
                        pendingDoubanAction = DoubanSyncAction.REMOVE_WISH
                    )
                    Log.w("DetailViewModel", "Douban removeInterest failed for $doubanId, optimistic delete applied")
                }
            }

            _uiState.value = _uiState.value.copy(
                isMarkedWatchlist = targetState,
                isMarkingWatchlist = false,
                watchlistAddedRevision = if (targetState && success) {
                    _uiState.value.watchlistAddedRevision + 1L
                } else {
                    _uiState.value.watchlistAddedRevision
                },
                doubanSyncRetryable = if (success) false else _uiState.value.doubanSyncRetryable,
                pendingDoubanAction = if (success) null else _uiState.value.pendingDoubanAction
            )
            saveToCache()
        }
    }

    /**
     * 豆瓣独立模式:切换已看状态。
     *
     * - 添加已看: markCollect → 写表 status="collect"
     * - 取消已看: removeInterest → 删除本地记录(并回退为想看,与 trakt 模式 removeWatched 副操作一致)
     * - 电视剧不弹选集弹窗(豆瓣 markCollect 针对整剧,不支持单集)
     *
     * @param rating 1-5 豆瓣五星制(可选,标记已看时一并提交评分)
     */
    private fun toggleWatchedDouban(rating: Int? = null) {
        val current = _uiState.value
        if (current.isMarkingWatched) return

        val doubanId = current.doubanIdForSync
        if (doubanId.isNullOrBlank()) {
            _uiState.value = current.copy(
                doubanSyncRetryable = true,
                pendingDoubanAction = if (current.isMarkedWatched) DoubanSyncAction.REMOVE_COLLECT else DoubanSyncAction.COLLECT
            )
            viewModelScope.launch { _toastEvent.emit(R.string.detail_douban_sync_id_not_ready) }
            return
        }

        val cred = doubanAuthStorage.getCredentials()
        if (cred == null) {
            viewModelScope.launch { _toastEvent.emit(R.string.detail_douban_sync_failed) }
            return
        }

        // 已看 → 取消已看(副操作:回退为想看,与 trakt 模式 removeWatched 一致)
        if (current.isMarkedWatched) {
            _uiState.value = current.copy(
                isMarkingWatched = true,
                watchedChanged = true,
                watchlistChanged = true
            )
            viewModelScope.launch {
                val success = doubanRepository.removeInterest(doubanId, cred.cookie)
                if (success) {
                    // 取消已看成功:本地回退为想看(与 trakt removeWatched 副操作一致)
                    upsertDoubanSyncedItem(doubanId, status = "wish", pendingSync = false)
                } else {
                    // 失败:乐观回退为想看,pendingSync=true
                    upsertDoubanSyncedItem(doubanId, status = "wish", pendingSync = true)
                    Log.w("DetailViewModel", "Douban removeInterest failed for $doubanId, optimistic rollback to wish applied")
                }
                _uiState.value = _uiState.value.copy(
                    isMarkedWatched = false,
                    isMarkedWatchlist = true,
                    isMarkingWatched = false
                )
                saveToCache()
            }
            return
        }

        // 未看 → 标记已看(电影直接标记,电视剧豆瓣模式不弹选集弹窗,整剧标记)
        _uiState.value = current.copy(
            isMarkingWatched = true,
            watchedChanged = true,
            watchlistChanged = true
        )
        viewModelScope.launch {
            val success = doubanRepository.markCollect(doubanId, cred.cookie, rating)
            if (success) {
                upsertDoubanSyncedItem(doubanId, status = "collect", pendingSync = false)
            } else {
                // 失败:乐观标记已看,pendingSync=true
                upsertDoubanSyncedItem(doubanId, status = "collect", pendingSync = true)
                Log.w("DetailViewModel", "Douban markCollect failed for $doubanId, optimistic collect applied")
            }
            _uiState.value = _uiState.value.copy(
                isMarkedWatched = true,
                isMarkedWatchlist = false,
                isMarkingWatched = false
            )
            saveToCache()
        }
    }

    /**
     * 豆瓣独立模式辅助:写入/更新本地 douban_synced_items 表(REPLACE 策略)。
     *
     * 字段从当前详情页状态填充(tmdbId/displayTitle/year/genres/posterUrl 来自 TMDB 富化),
     * 评分映射 Trakt 1-10 → 豆瓣 1-5(豆瓣模式评分暂未实现,通常为 null)。
     */
    private suspend fun upsertDoubanSyncedItem(
        doubanId: String,
        status: String,
        pendingSync: Boolean,
        clearRating: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val mediaTypeStr = if (currentMediaType == MediaType.SHOW) "show" else "movie"
        val state = _uiState.value
        val existing = runCatching { doubanSyncedItemDao.getByDoubanId(doubanId) }.getOrNull()
        val rating = state.userRating?.let { Math.round(it / 2.0).toInt() }
            ?: existing?.rating
        val item = existing?.copy(
            imdbId = currentImdbId.takeIf { it.isNotBlank() } ?: existing.imdbId,
            traktId = currentTraktId.takeIf { it > 0 } ?: existing.traktId,
            title = currentTitle.takeIf { it.isNotBlank() } ?: existing.title,
            status = status,
            rating = if (clearRating) null else rating,
            syncedAt = now,
            mediaType = existing.mediaType.takeIf { it.isNotBlank() } ?: mediaTypeStr,
            tmdbId = currentTmdbId.takeIf { it > 0 } ?: existing.tmdbId,
            displayTitle = state.displayTitle.takeIf { it.isNotBlank() } ?: existing.displayTitle,
            year = state.year ?: existing.year,
            genres = state.genres.takeIf { it.isNotBlank() } ?: existing.genres,
            posterUrl = state.posterUrl ?: existing.posterUrl,
            listedAt = existing.listedAt,
            pendingSync = pendingSync,
            doubanUrl = existing.doubanUrl,
            comment = state.userComment ?: existing.comment,
            markedAt = existing.markedAt,
            subtitle = existing.subtitle
        ) ?: DoubanSyncedItem(
            doubanId = doubanId,
            imdbId = currentImdbId.takeIf { it.isNotBlank() },
            traktId = currentTraktId.takeIf { it > 0 },
            title = currentTitle,
            status = status,
            rating = if (clearRating) null else rating,
            syncedAt = now,
            mediaType = mediaTypeStr,
            tmdbId = currentTmdbId.takeIf { it > 0 },
            displayTitle = state.displayTitle.takeIf { it.isNotBlank() },
            year = state.year,
            genres = state.genres.takeIf { it.isNotBlank() },
            posterUrl = state.posterUrl,
            listedAt = null,
            pendingSync = pendingSync
        )
        runCatching { doubanSyncedItemDao.insertAll(listOf(item)) }
    }

    /**
     * 豆瓣独立模式评分(含可选短评)。
     *
     * 豆瓣评分与看过耦合,统一走 markWatchedWithRating(interest=collect + rating)。
     * - Trakt 1-10 → 豆瓣 1-5 映射: doubanRating = round(rating / 2.0)
     * - 成功/失败均乐观更新 UI 与本地表,失败时 pendingSync=true 供下次同步重试
     * - 评分后状态变为 collect(看过),isMarkedWatched=true
     *
     * @param rating Trakt 1-10 分
     * @param comment 短评(可选,null 表示不写短评)
     */
    private fun setRatingDouban(rating: Int, comment: String?) {
        val current = _uiState.value
        val doubanId = current.doubanIdForSync
        if (doubanId.isNullOrBlank()) {
            _uiState.value = current.copy(
                doubanSyncRetryable = true,
                pendingDoubanAction = DoubanSyncAction.COLLECT
            )
            viewModelScope.launch { _toastEvent.emit(R.string.detail_douban_sync_id_not_ready) }
            return
        }
        val cred = doubanAuthStorage.getCredentials()
        if (cred == null) {
            viewModelScope.launch { _toastEvent.emit(R.string.detail_douban_sync_failed) }
            return
        }
        val doubanRating = Math.round(rating / 2.0).toInt()
        _uiState.value = current.copy(
            isRating = true,
            showRatingDialog = false,
            pendingOwnComment = null
        )
        viewModelScope.launch {
            // 豆瓣 markWatchedWithRating 需先 fetchCsrfToken 拿 ck
            val ck = doubanRepository.fetchCsrfToken(doubanId, cred.cookie)
            val success = if (ck != null) {
                doubanRepository.markWatchedWithRating(doubanId, cred.cookie, ck, doubanRating, comment ?: "").success
            } else false
            // 乐观更新:UI 立即反映评分(豆瓣评分=看过,isMarkedWatched=true)
            _uiState.value = _uiState.value.copy(
                userRating = rating,
                userComment = comment?.takeIf { it.isNotBlank() },
                isRating = false,
                isMarkedWatched = true,
                isMarkedWatchlist = false,
                watchedChanged = true,
                watchlistChanged = true
            )
            // 写本地表:成功 pendingSync=false,失败 pendingSync=true
            upsertDoubanSyncedItem(doubanId, status = "collect", pendingSync = !success)
            if (!success) {
                Log.w("DetailViewModel", "Douban markWatchedWithRating failed for $doubanId, optimistic rating applied")
            }
            saveToCache()
            saveUserReviewToLocal(rating, comment?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * 豆瓣独立模式取消评分。
     *
     * 豆瓣评分与看过耦合,无法只清除评分而保留看过。
     * 方案:重新 markInterest(interest=collect, rating=null),覆盖提交无评分的 collect,
     * 豆瓣会清除原评分但保留看过状态。
     * - 若当前无评分(userRating==null) → no-op
     * - 成功/失败均乐观更新 UI(userRating=null)与本地表,失败时 pendingSync=true
     */
    private fun removeRatingDouban() {
        val current = _uiState.value
        if (current.userRating == null) return  // 无评分可取消
        val doubanId = current.doubanIdForSync
        if (doubanId.isNullOrBlank()) {
            _uiState.value = current.copy(doubanSyncRetryable = true)
            viewModelScope.launch { _toastEvent.emit(R.string.detail_douban_sync_id_not_ready) }
            return
        }
        val cred = doubanAuthStorage.getCredentials()
        if (cred == null) {
            viewModelScope.launch { _toastEvent.emit(R.string.detail_douban_sync_failed) }
            return
        }
        _uiState.value = current.copy(isRating = true, showRatingDialog = false)
        viewModelScope.launch {
            // 重新 markCollect 不带 rating,清除评分保留看过
            val success = doubanRepository.markInterest(doubanId, "collect", cred.cookie, rating = null)
            // 乐观更新:UI 立即清除评分(保留看过状态)
            _uiState.value = _uiState.value.copy(
                userRating = null,
                userComment = null,
                isRating = false
            )
            // 写本地表:rating 从 state 读取(此时已为 null),status 保留当前(应为 collect)
            val currentStatus = if (_uiState.value.isMarkedWatched) "collect" else "wish"
            upsertDoubanSyncedItem(
                doubanId,
                status = currentStatus,
                pendingSync = !success,
                clearRating = true
            )
            if (!success) {
                Log.w("DetailViewModel", "Douban markInterest(collect, no rating) failed for $doubanId, optimistic rating removal applied")
            }
            saveToCache()
            deleteUserReviewFromLocal()
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
            DoubanSyncAction.WISH -> doubanRepository.markInterestByCk("wish", doubanId, cookie, ck).success
            DoubanSyncAction.COLLECT -> doubanRepository.markInterestByCk("collect", doubanId, cookie, ck).success
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
                    ?: doubanRepository.findDoubanId(
                        traktId = currentTraktId,
                        imdbId = imdbId,
                        mediaType = mediaTypeStr,
                        tmdbId = currentTmdbId
                    )
                if (doubanId != null) {
                    setResolvedDoubanId(doubanId)
                    saveToCache()
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
        currentDetailCacheKey?.let(::cacheRemove)
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

    /** 将详情页已经成功的标记操作发布给 Watchlist，列表页只更新这一条。 */
    private fun publishWatchlistMutation(action: TraktRepository.WatchlistMutationAction) {
        val state = _uiState.value
        traktRepository.publishWatchlistMutation(
            TraktRepository.WatchlistMutation(
                action = action,
                traktId = currentTraktId,
                tmdbId = currentTmdbId,
                mediaType = currentMediaType,
                title = currentTitle,
                displayTitle = state.displayTitle,
                year = state.year,
                genres = state.genres,
                posterUrl = state.posterUrl,
                imdbId = currentImdbId,
                traktRating = currentTraktRating
            )
        )
    }

    private fun saveToCache() {
        val cacheKey = currentDetailCacheKey ?: return
        cachePut(cacheKey, CachedDetailData(
            uiState = _uiState.value.withoutTransientSceneState(),
            allResources = allResources,
            currentKeyword = currentKeyword,
            currentOriginalTitle = currentOriginalTitle,
            currentImdbId = currentImdbId,
            currentTraktRating = currentTraktRating,
            currentTmdbId = currentTmdbId,
            currentMediaType = currentMediaType,
            currentCollectionId = currentCollectionId,
            currentDoubanId = currentDoubanId
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
                val existing = userReviewRepository.getReview(currentTraktId.toLong())
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
                        traktCommentId = existing?.traktCommentId,
                        commentCheckedAt = existing?.commentCheckedAt,
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

/** 将豆瓣短评适配为现有评论 UI 使用的统一模型。 */
private fun DoubanRexxarShortComment.toTraktComment(): TraktComment {
    val stableId = (id.hashCode().toLong() and 0x7fff_ffffL).toInt().coerceAtLeast(1)
    val author = authorName.orEmpty()
    return TraktComment(
        id = stableId,
        comment = text.orEmpty(),
        created_at = createdAt.orEmpty(),
        user_rating = ratingStars?.times(2.0),
        user = TraktCommentUser(username = author, name = author),
        source = DOUBAN_COMMENT_SOURCE
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
