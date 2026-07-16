package com.tracktosearch.ui.screen.discover

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.local.DiscoverSectionConfig
import com.tracktosearch.data.local.DiscoverSectionStorage
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.SearchHistoryStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.remote.douban.DoubanCookieExpiredException
import com.tracktosearch.data.remote.douban.DoubanHotApiService
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendItem
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedShowResponse
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktRecommendationShowResponse
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingListResponse
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingShowResponse
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.data.util.TtlCache
import com.tracktosearch.ui.screen.search.DoubanHotCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 「猜你喜欢」Tab */
enum class RecommendTab { MOVIE, TV }

/** 「猜你喜欢」栏目状态 */
sealed class DoubanRecommendState {
    /** 未登录豆瓣 */
    object NotLoggedIn : DoubanRecommendState()
    /** 加载中 */
    object Loading : DoubanRecommendState()
    /** 加载成功 */
    data class Success(
        val movieItems: List<DoubanRecommendItem>,
        val tvItems: List<DoubanRecommendItem>,
        val currentTab: RecommendTab
    ) : DoubanRecommendState()
    /** 加载失败 */
    data class Error(val message: String) : DoubanRecommendState()
}

@Immutable
data class DiscoverUiState(
    val doubanHotCategories: List<DoubanHotCategory> = emptyList(),
    val tmdbPopularMovies: List<TmdbSearchResult> = emptyList(),
    val tmdbUpcomingMovies: List<TmdbSearchResult> = emptyList(),
    val traktRecommendations: List<TraktMovie> = emptyList(),
    val traktTrendingMovies: List<TraktTrendingMovieResponse> = emptyList(),
    val traktTrendingShows: List<TraktTrendingShowResponse> = emptyList(),
    val traktAnticipatedMovies: List<TraktAnticipatedMovieResponse> = emptyList(),
    val traktAnticipatedShows: List<TraktAnticipatedShowResponse> = emptyList(),
    val traktShowRecommendations: List<TraktRecommendationShowResponse> = emptyList(),
    val trendingTimeWindow: String = "day",
    val trendingLists: List<TraktTrendingListResponse> = emptyList(),
    val isLoadingTraktLists: Boolean = false,
    val popularTotal: Int = 0,
    val upcomingTotal: Int = 0,
    val recommendationsTotal: Int = 0,
    val isLoadingPopular: Boolean = false,
    val isLoadingUpcoming: Boolean = false,
    val isLoadingRecommendations: Boolean = false,
    val isLoadingTrakt: Boolean = false,
    val popularError: String? = null,
    val upcomingError: String? = null,
    val recommendationsError: String? = null,
    val traktTrendingMoviesError: String? = null,
    val traktTrendingShowsError: String? = null,
    val traktAnticipatedError: String? = null,
    val traktShowRecommendationsError: String? = null,
    val trendingListsError: String? = null,
    val resolvingItemId: Int? = null,
    val resolvingTmdbId: Int? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    // "全部"弹窗的分页状态
    val popularAllItems: List<TmdbSearchResult> = emptyList(),
    val popularAllPage: Int = 1,
    val popularAllHasMore: Boolean = true,
    val isLoadingPopularAll: Boolean = false,
    val upcomingAllItems: List<TmdbSearchResult> = emptyList(),
    val upcomingAllPage: Int = 1,
    val upcomingAllHasMore: Boolean = true,
    val isLoadingUpcomingAll: Boolean = false,
    val recommendationsAllItems: List<TraktMovie> = emptyList(),
    val recommendationsAllPage: Int = 1,
    val recommendationsAllHasMore: Boolean = true,
    val isLoadingRecommendationsAll: Boolean = false,
    // 豆瓣「猜你喜欢」状态
    val doubanRecommendState: DoubanRecommendState = DoubanRecommendState.NotLoggedIn,
    /** 当前正在解析的豆瓣推荐条目 ID（用于卡片转圈遮罩） */
    val resolvingRecommendItemId: String? = null
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val doubanHotApi: DoubanHotApiService,
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val searchHistoryStorage: SearchHistoryStorage,
    private val viewedItemStorage: ViewedItemStorage,
    private val discoverSectionStorage: DiscoverSectionStorage,
    private val tokenStorage: TokenStorage,
    private val sharedDoubanHotCache: PersistentTtlCache<DoubanHotData>,
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanRecommendCache: PersistentTtlCache<List<DoubanRecommendItem>>,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    /**
     * 当前 ID 转换协程。点击新卡片时取消上一次，避免并发转换导致：
     * - 两次导航（旧协程完成后跳旧详情页，新协程完成后跳新详情页）
     * - 共享 resolvingXxxId 状态被旧协程的 finally 错误清空
     */
    private var resolveJob: Job? = null

    /** 全局想看/已看 ID 缓存，登录后加载一次 */
    private val _watchlistWatchedIds = MutableStateFlow<TraktRepository.WatchlistWatchedIds?>(null)
    val watchlistWatchedIds: StateFlow<TraktRepository.WatchlistWatchedIds?> = _watchlistWatchedIds.asStateFlow()

    private val _toastEvent = MutableSharedFlow<Int>()
    val toastEvent = _toastEvent.asSharedFlow()

    // 发现页栏目配置（显示/隐藏 + 排序），同步读取已保存顺序作为初始值，避免首帧跳动
    val sectionConfigs: StateFlow<List<DiscoverSectionConfig>> = discoverSectionStorage.sectionConfigs
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            DiscoverSectionStorage.ALL_SECTION_IDS.mapIndexed { index, id ->
                DiscoverSectionConfig(id = id, visible = true, order = index)
            }
        )

    companion object {
        private const val TTL_DOUBAN = 6 * 60 * 60 * 1000L // 豆瓣热榜 6 小时

        private val DOUBAN_CATEGORIES = listOf(
            "douban-movie",
            "douban-weekly",
            "douban-top250",
            "douban-nowplaying"
        )
    }

    init {
        // 首屏优先加载：豆瓣 + TMDB（国内用户首屏最常看到）
        // Trakt 栏目延迟加载，由 loadRemainingSections() 在用户滚动到底部附近时触发
        loadInitialSections()
        // 猜你喜欢（首屏优先，与豆瓣热榜同级）
        loadDoubanRecommend()
        // 登录后加载全局想看/已看 ID 缓存
        loadWatchlistWatchedIds()
    }

    /** 加载全局想看/已看 ID 缓存 */
    private fun loadWatchlistWatchedIds() {
        viewModelScope.launch {
            traktRepository.loadWatchlistWatchedIds()
            _watchlistWatchedIds.value = traktRepository.getWatchlistWatchedIds()
        }
    }

    /** 加载豆瓣「猜你喜欢」推荐（电影 + 电视剧） */
    fun loadDoubanRecommend() {
        val credentials = doubanAuthStorage.getCredentials()
        if (credentials == null) {
            _uiState.value = _uiState.value.copy(doubanRecommendState = DoubanRecommendState.NotLoggedIn)
            return
        }
        _uiState.value = _uiState.value.copy(doubanRecommendState = DoubanRecommendState.Loading)
        viewModelScope.launch {
            try {
                // 并发加载电影+电视剧（各自缓存按 userId 隔离）
                val movieDeferred = async { loadRecommendTab("movie", credentials) }
                val tvDeferred = async { loadRecommendTab("tv", credentials) }
                val movieItems = movieDeferred.await()
                val tvItems = tvDeferred.await()
                _uiState.value = _uiState.value.copy(
                    doubanRecommendState = DoubanRecommendState.Success(
                        movieItems = movieItems,
                        tvItems = tvItems,
                        currentTab = RecommendTab.MOVIE
                    )
                )
            } catch (e: DoubanCookieExpiredException) {
                // cookie 过期，清除登录态，显示引导卡片
                doubanAuthStorage.clearCredentials()
                _uiState.value = _uiState.value.copy(doubanRecommendState = DoubanRecommendState.NotLoggedIn)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    doubanRecommendState = DoubanRecommendState.Error(e.message ?: "加载失败")
                )
            }
        }
    }

    /** 加载单个 Tab 的推荐数据（缓存优先，未命中走豆瓣 API） */
    private suspend fun loadRecommendTab(type: String, credentials: DoubanCredentials): List<DoubanRecommendItem> {
        // _v3: 修复字段映射（pic/rating.year/reason_data 解析）后让旧缓存失效
        val cacheKey = "recommend_v3_${type}_${credentials.userId}"
        return doubanRecommendCache.getOrAwait(cacheKey) {
            doubanRepository.fetchRecommend(type, credentials.cookie)
        }
    }

    /** 切换猜你喜欢 Tab（电影/电视剧） */
    fun switchRecommendTab(tab: RecommendTab) {
        val current = _uiState.value.doubanRecommendState
        if (current is DoubanRecommendState.Success) {
            _uiState.value = _uiState.value.copy(
                doubanRecommendState = current.copy(currentTab = tab)
            )
        }
    }

    /** 重试猜你喜欢加载 */
    fun retryDoubanRecommend() {
        loadDoubanRecommend()
    }

    /**
     * 页面恢复可见时刷新想看/已看缓存快照。
     * TraktRepository 内部的 @Volatile var 在详情页标记后会就地替换，
     * 但本 ViewModel 持有的 StateFlow 仍是旧引用，需要重新读取以触发 UI 更新。
     */
    fun refreshWatchlistWatchedIds() {
        _watchlistWatchedIds.value = traktRepository.getWatchlistWatchedIds()
    }

    /** 首屏优先加载：豆瓣热榜 + TMDB 热门/即将上映 */
    private fun loadInitialSections() {
        val configs = sectionConfigs.value
        val visibleIds = configs.filter { it.visible }.map { it.id }.toSet()
        if (DOUBAN_CATEGORIES.any { it in visibleIds }) {
            // 豆瓣热榜已有数据则跳过
            if (_uiState.value.doubanHotCategories.none { it.items.isNotEmpty() }) loadDoubanHot()
        }
        if ("tmdb-popular" in visibleIds && !_uiState.value.isLoadingPopular && _uiState.value.tmdbPopularMovies.isEmpty() && _uiState.value.popularError == null) loadTmdbPopular()
        if ("tmdb-upcoming" in visibleIds && !_uiState.value.isLoadingUpcoming && _uiState.value.tmdbUpcomingMovies.isEmpty() && _uiState.value.upcomingError == null) loadTmdbUpcoming()
        // 猜你喜欢（首屏优先加载，与豆瓣热榜同级）
        if (DiscoverSectionStorage.SECTION_ID_DOUBAN_RECOMMEND in visibleIds) {
            if (_uiState.value.doubanRecommendState is DoubanRecommendState.NotLoggedIn ||
                _uiState.value.doubanRecommendState is DoubanRecommendState.Error) {
                loadDoubanRecommend()
            }
        }
    }

    /** 延迟加载剩余栏目：Trakt 推荐/趋势/列表等，进入发现页后或滚动时调用 */
    fun loadRemainingSections(force: Boolean = false) {
        val configs = sectionConfigs.value
        val visibleIds = configs.filter { it.visible }.map { it.id }.toSet()
        val s = _uiState.value
        if ("trakt-recommendations" in visibleIds && (force || !s.isLoadingRecommendations && s.traktRecommendations.isEmpty() && s.recommendationsError == null)) loadTraktRecommendations()
        if ("trakt-lists" in visibleIds && (force || !s.isLoadingTraktLists && s.trendingLists.isEmpty())) loadTraktLists()
        val traktSectionIds = listOf("trakt-trending-movies", "trakt-trending-shows", "trakt-anticipated", "trakt-show-recommendations")
        if (traktSectionIds.any { it in visibleIds } && (force || !s.isLoadingTrakt && s.traktTrendingMovies.isEmpty())) loadTraktData()
    }

    /** 根据栏目可见性设置，按需加载各栏目数据（完整加载，供下拉刷新用） */
    private fun loadVisibleSections() {
        loadDoubanHot()
        loadDoubanRecommend()
        loadTmdbPopular()
        loadTmdbUpcoming()
        loadRemainingSections(force = true)
    }

    /** 强制刷新所有栏目（清除缓存后重新加载） */
    fun forceRefreshAll() {
        loadVisibleSections()
    }

    fun loadDoubanHot() {
        val categories = DOUBAN_CATEGORIES.map { id ->
            DoubanHotCategory(id = id, label = "", isLoading = true)
        }
        _uiState.value = _uiState.value.copy(doubanHotCategories = categories)

        DOUBAN_CATEGORIES.forEachIndexed { index, categoryId ->
            loadDoubanCategory(index, categoryId)
        }
    }

    private fun loadDoubanCategory(index: Int, categoryId: String) {
        viewModelScope.launch {
            val current = _uiState.value.doubanHotCategories.toMutableList()
            if (index < current.size) {
                current[index] = current[index].copy(isLoading = true, error = null)
                _uiState.value = _uiState.value.copy(doubanHotCategories = current)
            }
            // 共享缓存 + 飞行中去重：与搜索页共享同一请求
            // 缓存 key 带版本号 v2：豆瓣 API 修复口碑榜海报+tmdbId / 正在热映海报后，让旧缓存自动失效
            val cacheKey = "${categoryId}_1_10_v2"
            try {
                val data = sharedDoubanHotCache.getOrAwait(cacheKey) {
                    when (categoryId) {
                        "douban-movie" -> {
                            val response = doubanHotApi.getChart()
                            com.tracktosearch.data.remote.douban.dto.DoubanHotData(
                                items = response.data.map { item ->
                                    val ratingText = if (item.rating.isNotBlank() && item.rating != "暂无评分") "【${item.rating}】" else ""
                                    com.tracktosearch.data.remote.douban.dto.DoubanHotItem(
                                        id = item.id.hashCode(),
                                        title = "$ratingText${item.title}",
                                        cover = item.poster,
                                        desc = item.ratingCount,
                                        rating = item.rating,
                                        url = item.url
                                    )
                                },
                                total = response.total
                            )
                        }
                        "douban-weekly" -> {
                            val response = doubanHotApi.getWeekly()
                            com.tracktosearch.data.remote.douban.dto.DoubanHotData(
                                items = response.data.map { item ->
                                    val ratingText = if (item.rating.isNotBlank() && item.rating != "暂无评分") "【${item.rating}】" else ""
                                    com.tracktosearch.data.remote.douban.dto.DoubanHotItem(
                                        id = item.id.hashCode(),
                                        title = "$ratingText${item.title}",
                                        cover = item.poster,
                                        desc = item.ratingCount,
                                        rating = item.rating,
                                        url = item.url,
                                        tmdbId = item.tmdbId
                                    )
                                },
                                total = response.total
                            )
                        }
                        "douban-top250" -> {
                            val response = doubanHotApi.getTop250(page = 1)
                            com.tracktosearch.data.remote.douban.dto.DoubanHotData(
                                items = response.data.map { item ->
                                    val ratingText = if (item.rating.isNotBlank()) "【${item.rating}】" else ""
                                    com.tracktosearch.data.remote.douban.dto.DoubanHotItem(
                                        id = item.id.hashCode(),
                                        title = "$ratingText${item.title}",
                                        cover = item.poster,
                                        desc = item.ratingCount,
                                        rating = item.rating,
                                        url = item.url
                                    )
                                },
                                total = response.total
                            )
                        }
                        "douban-nowplaying" -> {
                            val response = doubanHotApi.getNowPlaying()
                            com.tracktosearch.data.remote.douban.dto.DoubanHotData(
                                items = response.data.take(10).map { item ->
                                    val ratingText = if (item.rating.isNotBlank() && item.rating != "暂无评分") "【${item.rating}】" else ""
                                    com.tracktosearch.data.remote.douban.dto.DoubanHotItem(
                                        id = item.id.hashCode(),
                                        title = "$ratingText${item.title}",
                                        cover = item.poster,
                                        desc = item.ratingCount,
                                        rating = item.rating,
                                        url = item.url
                                    )
                                },
                                total = response.total
                            )
                        }
                        else -> com.tracktosearch.data.remote.douban.dto.DoubanHotData()
                    }
                }
                val updated = _uiState.value.doubanHotCategories.toMutableList()
                if (index < updated.size) {
                    updated[index] = updated[index].copy(
                        items = data.items,
                        isLoading = false,
                        error = null,
                        total = data.total
                    )
                    _uiState.value = _uiState.value.copy(doubanHotCategories = updated)
                }
            } catch (e: Exception) {
                val updated = _uiState.value.doubanHotCategories.toMutableList()
                if (index < updated.size) {
                    updated[index] = updated[index].copy(
                        isLoading = false,
                        error = e.message ?: context.getString(R.string.error_load_failed)
                    )
                    _uiState.value = _uiState.value.copy(doubanHotCategories = updated)
                }
            }
        }
    }

    fun retryDoubanCategory(categoryId: String) {
        val index = DOUBAN_CATEGORIES.indexOfFirst { it == categoryId }
        if (index >= 0) {
            loadDoubanCategory(index, categoryId)
        }
    }

    fun loadDoubanHotAll(categoryId: String, page: Int = 1, limit: Int = 25) {
        viewModelScope.launch {
            val current = _uiState.value.doubanHotCategories.toMutableList()
            val idx = current.indexOfFirst { it.id == categoryId }
            if (idx >= 0) {
                // 只有首次加载(page==1且无数据)才显示骨架屏，追加加载不设isLoading
                if (page == 1 && current[idx].items.isEmpty()) {
                    current[idx] = current[idx].copy(isLoading = true)
                    _uiState.value = _uiState.value.copy(doubanHotCategories = current)
                }
            }
            // 1 小时内用缓存（仅首页）
            // 缓存 key 带版本号 v2：与 loadDoubanCategory 保持一致，让旧缓存自动失效
            val cacheKey = "${categoryId}_${page}_${limit}_v2"
            if (page == 1) {
                sharedDoubanHotCache.get(cacheKey)?.let { data ->
                    val updated = _uiState.value.doubanHotCategories.toMutableList()
                    if (idx >= 0) {
                        updated[idx] = updated[idx].copy(
                            items = data.items,
                            isLoading = false,
                            error = null,
                            currentPage = page,
                            hasMore = data.hasMore || data.items.size >= limit,
                            total = data.total
                        )
                        _uiState.value = _uiState.value.copy(doubanHotCategories = updated)
                    }
                    return@launch
                }
            }
            try {
                val response = when (categoryId) {
                    "douban-movie" -> {
                        val chartResponse = doubanHotApi.getChart()
                        com.tracktosearch.data.remote.douban.dto.DoubanHotResponse(
                            code = chartResponse.code,
                            data = com.tracktosearch.data.remote.douban.dto.DoubanHotData(
                                items = chartResponse.data.map { item ->
                                    val ratingText = if (item.rating.isNotBlank() && item.rating != "暂无评分") "【${item.rating}】" else ""
                                    com.tracktosearch.data.remote.douban.dto.DoubanHotItem(
                                        id = item.id.hashCode(),
                                        title = "$ratingText${item.title}",
                                        cover = item.poster,
                                        desc = item.ratingCount,
                                        rating = item.rating,
                                        url = item.url
                                    )
                                },
                                total = chartResponse.total,
                                hasMore = false,
                                page = page,
                                limit = limit
                            )
                        )
                    }
                    "douban-weekly" -> {
                        val weeklyResponse = doubanHotApi.getWeekly()
                        com.tracktosearch.data.remote.douban.dto.DoubanHotResponse(
                            code = weeklyResponse.code,
                            data = com.tracktosearch.data.remote.douban.dto.DoubanHotData(
                                items = weeklyResponse.data.map { item ->
                                    val ratingText = if (item.rating.isNotBlank() && item.rating != "暂无评分") "【${item.rating}】" else ""
                                    com.tracktosearch.data.remote.douban.dto.DoubanHotItem(
                                        id = item.id.hashCode(),
                                        title = "$ratingText${item.title}",
                                        cover = item.poster,
                                        desc = item.ratingCount,
                                        rating = item.rating,
                                        url = item.url,
                                        tmdbId = item.tmdbId
                                    )
                                },
                                total = weeklyResponse.total,
                                hasMore = false,
                                page = page,
                                limit = limit
                            )
                        )
                    }
                    "douban-top250" -> {
                        val top250Response = doubanHotApi.getTop250(page = page)
                        com.tracktosearch.data.remote.douban.dto.DoubanHotResponse(
                            code = top250Response.code,
                            data = com.tracktosearch.data.remote.douban.dto.DoubanHotData(
                                items = top250Response.data.map { item ->
                                    val ratingText = if (item.rating.isNotBlank()) "【${item.rating}】" else ""
                                    com.tracktosearch.data.remote.douban.dto.DoubanHotItem(
                                        id = item.id.hashCode(),
                                        title = "$ratingText${item.title}",
                                        cover = item.poster,
                                        desc = item.ratingCount,
                                        rating = item.rating,
                                        url = item.url
                                    )
                                },
                                total = top250Response.total,
                                hasMore = page * limit < top250Response.total,
                                page = page,
                                limit = limit
                            )
                        )
                    }
                    "douban-nowplaying" -> {
                        val nowPlayingResponse = doubanHotApi.getNowPlaying()
                        com.tracktosearch.data.remote.douban.dto.DoubanHotResponse(
                            code = nowPlayingResponse.code,
                            data = com.tracktosearch.data.remote.douban.dto.DoubanHotData(
                                items = nowPlayingResponse.data.map { item ->
                                    val ratingText = if (item.rating.isNotBlank() && item.rating != "暂无评分") "【${item.rating}】" else ""
                                    com.tracktosearch.data.remote.douban.dto.DoubanHotItem(
                                        id = item.id.hashCode(),
                                        title = "$ratingText${item.title}",
                                        cover = item.poster,
                                        desc = item.ratingCount,
                                        rating = item.rating,
                                        url = item.url
                                    )
                                },
                                total = nowPlayingResponse.total,
                                hasMore = false,
                                page = page,
                                limit = limit
                            )
                        )
                    }
                    else -> com.tracktosearch.data.remote.douban.dto.DoubanHotResponse()
                }
                val updated = _uiState.value.doubanHotCategories.toMutableList()
                if (idx >= 0) {
                    val existingItems = if (page > 1) updated[idx].items else emptyList()
                    val newItems = response.data.items.filter { newItem ->
                        existingItems.none { it.id == newItem.id && it.id != null }
                    }
                    updated[idx] = updated[idx].copy(
                        items = existingItems + newItems,
                        isLoading = false,
                        error = null,
                        currentPage = page,
                        hasMore = response.data.hasMore || response.data.items.size >= limit,
                        total = if (response.data.total > 0) response.data.total else updated[idx].total
                    )
                    _uiState.value = _uiState.value.copy(doubanHotCategories = updated)
                }
                if (page == 1) {
                    sharedDoubanHotCache.put(cacheKey, response.data)
                }
            } catch (e: Exception) {
                val updated = _uiState.value.doubanHotCategories.toMutableList()
                if (idx >= 0) {
                    updated[idx] = updated[idx].copy(
                        isLoading = false,
                        error = e.message ?: context.getString(R.string.error_load_failed)
                    )
                    _uiState.value = _uiState.value.copy(doubanHotCategories = updated)
                }
            }
        }
    }

    fun loadTmdbPopular() {
        val timeWindow = _uiState.value.trendingTimeWindow
        _uiState.value = _uiState.value.copy(isLoadingPopular = true, popularError = null)
        viewModelScope.launch {
            try {
                val movies = tmdbRepository.getTrendingMovies(timeWindow = timeWindow)
                _uiState.value = _uiState.value.copy(
                    tmdbPopularMovies = movies,
                    isLoadingPopular = false,
                    popularError = if (movies.isEmpty()) context.getString(R.string.error_no_data) else null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingPopular = false,
                    popularError = e.message ?: context.getString(R.string.error_load_failed)
                )
            }
        }
    }

    /** 切换趋势榜时间窗口（今日/本周） */
    fun switchTrendingTimeWindow(timeWindow: String) {
        if (_uiState.value.trendingTimeWindow == timeWindow) return
        _uiState.value = _uiState.value.copy(trendingTimeWindow = timeWindow)
        loadTmdbPopular()
        // 如果"查看全部"Sheet 已打开（popularAllItems 有数据），重新加载 Sheet 数据
        if (_uiState.value.popularAllItems.isNotEmpty()) {
            loadPopularAll(page = 1, forceReload = true)
        }
    }

    fun loadTmdbUpcoming() {
        _uiState.value = _uiState.value.copy(isLoadingUpcoming = true, upcomingError = null)
        viewModelScope.launch {
            try {
                val movies = tmdbRepository.getUpcomingMovies()
                _uiState.value = _uiState.value.copy(
                    tmdbUpcomingMovies = movies,
                    isLoadingUpcoming = false,
                    upcomingError = if (movies.isEmpty()) context.getString(R.string.error_no_data) else null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingUpcoming = false,
                    upcomingError = e.message ?: context.getString(R.string.error_load_failed)
                )
            }
        }
    }

    fun loadTraktRecommendations() {
        _uiState.value = _uiState.value.copy(isLoadingRecommendations = true, recommendationsError = null)
        viewModelScope.launch {
            try {
                // 已登录用 Trakt 个性化推荐，失败则降级为 TMDB 高分电影
                val result = traktRepository.getRecommendations(limit = 10)
                result.onSuccess { recommendations ->
                    if (recommendations.isNotEmpty()) {
                        // 通过 TMDB 获取本地化标题和海报
                        val enhanced = enhanceWithTmdbData(recommendations)
                        _uiState.value = _uiState.value.copy(
                            traktRecommendations = enhanced,
                            isLoadingRecommendations = false,
                            recommendationsError = null
                        )
                    } else {
                        fallbackToTopRated()
                    }
                }.onFailure {
                    fallbackToTopRated()
                }
            } catch (e: Exception) {
                fallbackToTopRated()
            }
        }
    }

    fun loadTraktData() {
        _uiState.value = _uiState.value.copy(
            isLoadingTrakt = true,
            traktTrendingMoviesError = null,
            traktTrendingShowsError = null,
            traktAnticipatedError = null,
            traktShowRecommendationsError = null
        )
        viewModelScope.launch {
            try {
                val isLoggedIn = !tokenStorage.accessToken.first().isNullOrEmpty()
                val deferredTrendingMovies = async { traktRepository.getTrendingMovies(limit = 10) }
                val deferredTrendingShows = async { traktRepository.getTrendingShows(limit = 10) }
                val deferredAnticipatedMovies = async { traktRepository.getAnticipatedMovies(limit = 10) }
                val deferredAnticipatedShows = async { traktRepository.getAnticipatedShows(limit = 10) }
                val deferredShowRecs = if (isLoggedIn) {
                    async { traktRepository.getShowRecommendations(limit = 10) }
                } else null

                val trendingMoviesResult = deferredTrendingMovies.await()
                val trendingShowsResult = deferredTrendingShows.await()
                val anticipatedMoviesResult = deferredAnticipatedMovies.await()
                val anticipatedShowsResult = deferredAnticipatedShows.await()
                val showRecsResult = deferredShowRecs?.await()

                // 增强 Trakt 电影数据（海报+本地化标题）— 并行增强
                val enhancedTrendingMovies = trendingMoviesResult.getOrNull()?.first?.let { items ->
                    coroutineScope { items.map { async { it.copy(movie = enhanceTraktMovie(it.movie)) } }.awaitAll() }
                } ?: emptyList()
                val enhancedAnticipatedMovies = anticipatedMoviesResult.getOrNull()?.first?.let { items ->
                    coroutineScope { items.map { async { it.copy(movie = enhanceTraktMovie(it.movie)) } }.awaitAll() }
                } ?: emptyList()

                // 增强 Trakt 剧集数据（海报+本地化标题）— 并行增强
                val enhancedTrendingShows = trendingShowsResult.getOrNull()?.first?.let { items ->
                    coroutineScope { items.map { async { it.copy(show = enhanceTraktShow(it.show)) } }.awaitAll() }
                } ?: emptyList()
                val enhancedAnticipatedShows = anticipatedShowsResult.getOrNull()?.first?.let { items ->
                    coroutineScope { items.map { async { it.copy(show = enhanceTraktShow(it.show)) } }.awaitAll() }
                } ?: emptyList()
                val enhancedShowRecs = showRecsResult?.getOrNull()?.let { items ->
                    if (items.isNotEmpty()) {
                        val enhanced = coroutineScope { items.map { async { it.copy(show = enhanceTraktShow(it.show)) } }.awaitAll() }
                        // 过滤掉没有海报的项，若全部失败则降级为热门剧集
                        val withPosters = enhanced.filter { !it.show.posterPath.isNullOrEmpty() }
                        if (withPosters.isNotEmpty()) withPosters else enhancedTrendingShows.map { trending ->
                            com.tracktosearch.data.remote.trakt.dto.TraktRecommendationShowResponse(show = trending.show)
                        }
                    } else {
                        // 已登录但推荐列表为空时，降级为热门剧集
                        enhancedTrendingShows.map { trending ->
                            com.tracktosearch.data.remote.trakt.dto.TraktRecommendationShowResponse(
                                show = trending.show
                            )
                        }
                    }
                } ?: (if (!isLoggedIn) {
                    // 未登录时用热门剧集作为推荐降级
                    enhancedTrendingShows.map { trending ->
                        com.tracktosearch.data.remote.trakt.dto.TraktRecommendationShowResponse(
                            show = trending.show
                        )
                    }
                } else {
                    // 已登录但请求失败时，也降级为热门剧集
                    enhancedTrendingShows.map { trending ->
                        com.tracktosearch.data.remote.trakt.dto.TraktRecommendationShowResponse(
                            show = trending.show
                        )
                    }
                })

                // 记录各栏目的错误信息（result 失败时记录）
                val trendingMoviesError = trendingMoviesResult.exceptionOrNull()?.message
                    ?: context.getString(R.string.error_load_failed).takeIf { trendingMoviesResult.isFailure }
                val trendingShowsError = trendingShowsResult.exceptionOrNull()?.message
                    ?: context.getString(R.string.error_load_failed).takeIf { trendingShowsResult.isFailure }
                // 最受期待电影/剧集合并为一个 error（用电影的失败状态，或剧集的）
                val anticipatedError = anticipatedMoviesResult.exceptionOrNull()?.message
                    ?: anticipatedShowsResult.exceptionOrNull()?.message
                    ?: context.getString(R.string.error_load_failed).takeIf { anticipatedMoviesResult.isFailure && anticipatedShowsResult.isFailure }
                val showRecsError = showRecsResult?.exceptionOrNull()?.message
                    ?: context.getString(R.string.error_load_failed).takeIf { showRecsResult != null && showRecsResult.isFailure && !isLoggedIn }

                _uiState.value = _uiState.value.copy(
                    traktTrendingMovies = enhancedTrendingMovies,
                    traktTrendingShows = enhancedTrendingShows,
                    traktAnticipatedMovies = enhancedAnticipatedMovies,
                    traktAnticipatedShows = enhancedAnticipatedShows,
                    traktShowRecommendations = enhancedShowRecs,
                    traktTrendingMoviesError = trendingMoviesError,
                    traktTrendingShowsError = trendingShowsError,
                    traktAnticipatedError = anticipatedError,
                    traktShowRecommendationsError = showRecsError,
                    isLoadingTrakt = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingTrakt = false,
                    traktTrendingMoviesError = e.message ?: context.getString(R.string.error_load_failed),
                    traktTrendingShowsError = e.message ?: context.getString(R.string.error_load_failed),
                    traktAnticipatedError = e.message ?: context.getString(R.string.error_load_failed),
                    traktShowRecommendationsError = e.message ?: context.getString(R.string.error_load_failed)
                )
            }
        }
    }

    /** 加载社区热门列表 */
    fun loadTraktLists() {
        _uiState.value = _uiState.value.copy(isLoadingTraktLists = true, trendingListsError = null)
        viewModelScope.launch {
            try {
                val result = traktRepository.getTrendingLists(limit = 10)
                result.onSuccess { lists ->
                    _uiState.value = _uiState.value.copy(
                        trendingLists = lists,
                        isLoadingTraktLists = false,
                        trendingListsError = null
                    )
                }.onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoadingTraktLists = false,
                        trendingListsError = e.message ?: context.getString(R.string.error_load_failed)
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingTraktLists = false,
                    trendingListsError = e.message ?: context.getString(R.string.error_load_failed)
                )
            }
        }
    }

    /** 增强 TraktMovie 数据（通过 TMDB 获取海报和本地化标题） */
    private suspend fun enhanceTraktMovie(movie: TraktMovie): TraktMovie {
        val tmdbId = movie.ids.tmdb
        if (tmdbId <= 0) return movie
        return try {
            val detail = tmdbRepository.getMovieDetail(tmdbId)
            if (detail != null) {
                movie.copy(
                    title = detail.title.ifBlank { movie.title },
                    year = detail.release_date.take(4).toIntOrNull() ?: movie.year,
                    posterPath = detail.poster_path
                )
            } else movie
        } catch (_: Exception) { movie }
    }

    /** 增强 TraktShow 数据（通过 TMDB 获取海报和本地化标题） */
    private suspend fun enhanceTraktShow(show: TraktShow): TraktShow {
        val tmdbId = show.ids.tmdb
        if (tmdbId <= 0) return show
        return try {
            val enrichment = tmdbRepository.enrichTv(tmdbId, show.title, show.year)
            show.copy(
                title = enrichment.chineseTitle.ifBlank { show.title },
                year = enrichment.year ?: show.year,
                posterPath = enrichment.posterUrl
            )
        } catch (_: Exception) { show }
    }

    /** 通过 TMDB 获取本地化标题和海报路径 */
    private suspend fun enhanceWithTmdbData(movies: List<TraktMovie>): List<TraktMovie> {
        return movies.map { movie ->
            val tmdbId = movie.ids.tmdb
            if (tmdbId > 0) {
                try {
                    val detail = tmdbRepository.getMovieDetail(tmdbId)
                    if (detail != null) {
                        movie.copy(
                            title = detail.title.ifBlank { movie.title },
                            year = detail.release_date.take(4).toIntOrNull() ?: movie.year,
                            posterPath = detail.poster_path
                        )
                    } else {
                        movie
                    }
                } catch (_: Exception) {
                    movie
                }
            } else {
                movie
            }
        }
    }

    /** 未登录或推荐失败时降级为 TMDB 高分电影 */
    private suspend fun fallbackToTopRated() {
        try {
            val movies = tmdbRepository.getTopRatedMovies()
            // 将 TMDB 结果转换为 TraktMovie 格式以便 UI 统一渲染
            val traktMovies = movies.map { tmdb ->
                TraktMovie(
                    title = tmdb.title,
                    year = tmdb.release_date.take(4).toIntOrNull() ?: 0,
                    ids = com.tracktosearch.data.remote.trakt.dto.TraktIds(
                        trakt = 0,
                        tmdb = tmdb.id
                    )
                )
            }
            _uiState.value = _uiState.value.copy(
                traktRecommendations = traktMovies,
                isLoadingRecommendations = false,
                recommendationsError = if (traktMovies.isEmpty()) context.getString(R.string.error_no_data) else null
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoadingRecommendations = false,
                recommendationsError = e.message ?: context.getString(R.string.error_load_failed)
            )
        }
    }

    /** TMDB 电影卡片点击：通过 TMDB ID 转换为 Trakt ID 后跳转 */
    fun resolveTmdbAndNavigate(
        tmdbId: Int,
        title: String,
        onNavigate: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit
    ) {
        // 取消上一次未完成的转换，避免并发导航与状态错乱
        resolveJob?.cancel()
        // 优先从全局想看/已看缓存查找
        val wlCached = _watchlistWatchedIds.value?.traktIdByTmdb(tmdbId, MediaType.MOVIE)
        if (wlCached != null && wlCached > 0) {
            val inWl = _watchlistWatchedIds.value?.isInWatchlist(wlCached, tmdbId, MediaType.MOVIE) == true
            val isW = _watchlistWatchedIds.value?.isWatched(wlCached, tmdbId, MediaType.MOVIE) == true
            onNavigate(wlCached, tmdbId, title, "", 0.0, inWl, isW)
            return
        }
        // 其次从 ID 转换缓存查找（之前转换过的不再转圈）
        val idCached = traktRepository.getCachedTraktId(tmdbId, MediaType.MOVIE)
        if (idCached != null && idCached > 0) {
            val inWl = _watchlistWatchedIds.value?.isInWatchlist(idCached, tmdbId, MediaType.MOVIE) == true
            val isW = _watchlistWatchedIds.value?.isWatched(idCached, tmdbId, MediaType.MOVIE) == true
            onNavigate(idCached, tmdbId, title, "", 0.0, inWl, isW)
            return
        }
        // 负缓存命中（idCached == 0）：之前搜过没找到，不转圈直接提示
        if (idCached == 0) {
            viewModelScope.launch { _toastEvent.emit(R.string.card_resolve_not_found) }
            return
        }
        resolveJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(resolvingTmdbId = tmdbId)
            try {
                val traktResult = traktRepository.searchByTmdb(tmdbId, MediaType.MOVIE)
                traktResult.onSuccess { searchResults ->
                    val first = searchResults.firstOrNull()
                    val traktId = first?.movie?.ids?.trakt
                    val imdbId = first?.movie?.ids?.imdb ?: ""
                    if (traktId != null && traktId > 0) {
                        val inWl = _watchlistWatchedIds.value?.isInWatchlist(traktId, tmdbId, MediaType.MOVIE) == true
                        val isW = _watchlistWatchedIds.value?.isWatched(traktId, tmdbId, MediaType.MOVIE) == true
                        onNavigate(traktId, tmdbId, title, imdbId, 0.0, inWl, isW)
                    } else {
                        _toastEvent.emit(R.string.card_resolve_not_found)
                    }
                }
            } catch (_: Exception) {
                // 忽略
            } finally {
                // 仅当仍是自己设置的 tmdbId 时才清空，避免清空新协程设的值
                if (_uiState.value.resolvingTmdbId == tmdbId) {
                    _uiState.value = _uiState.value.copy(resolvingTmdbId = null)
                }
            }
        }
    }

    /** Trakt 推荐卡片点击：已有 Trakt ID 和 TMDB ID，直接跳转 */
    fun navigateTraktMovie(
        movie: TraktMovie,
        onNavigate: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit
    ) {
        val traktId = movie.ids.trakt
        val tmdbId = movie.ids.tmdb
        val imdbId = movie.ids.imdb
        if (traktId > 0) {
            val inWl = _watchlistWatchedIds.value?.isInWatchlist(traktId, tmdbId, MediaType.MOVIE) == true
            val isW = _watchlistWatchedIds.value?.isWatched(traktId, tmdbId, MediaType.MOVIE) == true
            onNavigate(traktId, tmdbId, movie.title, imdbId, movie.rating, inWl, isW)
        } else if (tmdbId > 0) {
            // 降级：只有 TMDB ID 时走转换
            resolveTmdbAndNavigate(tmdbId, movie.title, onNavigate)
        }
    }

    /** Trakt 剧集卡片点击：通过 TMDB ID 转换为 Trakt ID 后跳转 */
    fun navigateTraktShow(
        show: TraktShow,
        onNavigate: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit
    ) {
        val tmdbId = show.ids.tmdb
        if (tmdbId > 0) {
            // 取消上一次未完成的转换，避免并发导航与状态错乱
            resolveJob?.cancel()
            // 优先从全局想看/已看缓存查找
            val wlCached = _watchlistWatchedIds.value?.traktIdByTmdb(tmdbId, MediaType.SHOW)
            if (wlCached != null && wlCached > 0) {
                val inWl = _watchlistWatchedIds.value?.isInWatchlist(wlCached, tmdbId, MediaType.SHOW) == true
                val isW = _watchlistWatchedIds.value?.isWatched(wlCached, tmdbId, MediaType.SHOW) == true
                onNavigate(wlCached, tmdbId, show.title, show.ids.imdb, show.rating, inWl, isW)
                return
            }
            // 其次从 ID 转换缓存查找
            val idCached = traktRepository.getCachedTraktId(tmdbId, MediaType.SHOW)
            if (idCached != null && idCached > 0) {
                val inWl = _watchlistWatchedIds.value?.isInWatchlist(idCached, tmdbId, MediaType.SHOW) == true
                val isW = _watchlistWatchedIds.value?.isWatched(idCached, tmdbId, MediaType.SHOW) == true
                onNavigate(idCached, tmdbId, show.title, show.ids.imdb, show.rating, inWl, isW)
                return
            }
            // 负缓存命中（idCached == 0）：之前搜过没找到，不转圈直接提示
            if (idCached == 0) {
                viewModelScope.launch { _toastEvent.emit(R.string.card_resolve_not_found) }
                return
            }
            resolveJob = viewModelScope.launch {
                _uiState.value = _uiState.value.copy(resolvingTmdbId = tmdbId)
                try {
                    val traktResult = traktRepository.searchByTmdb(tmdbId, MediaType.SHOW)
                    traktResult.onSuccess { searchResults ->
                        val first = searchResults.firstOrNull()
                        val traktId = first?.show?.ids?.trakt
                        val imdbId = first?.show?.ids?.imdb ?: ""
                        if (traktId != null && traktId > 0) {
                            val inWl = _watchlistWatchedIds.value?.isInWatchlist(traktId, tmdbId, MediaType.SHOW) == true
                            val isW = _watchlistWatchedIds.value?.isWatched(traktId, tmdbId, MediaType.SHOW) == true
                            onNavigate(traktId, tmdbId, show.title, imdbId, show.rating, inWl, isW)
                        }
                    }
                } catch (_: Exception) {
                    // 忽略
                } finally {
                    // 仅当仍是自己设置的 tmdbId 时才清空，避免清空新协程设的值
                    if (_uiState.value.resolvingTmdbId == tmdbId) {
                        _uiState.value = _uiState.value.copy(resolvingTmdbId = null)
                    }
                }
            }
        }
    }

    fun retryAll() {
        loadVisibleSections()
    }

    /** 豆瓣标题 → TMDB 搜索结果缓存（标题不变，映射永不过期） */
    private val doubanTmdbCache = TtlCache<TmdbSearchResult>(Long.MAX_VALUE, maxSize = 200)

    // 豆瓣热榜卡片点击：通过 TMDB 搜索标题，再转换为 Trakt ID 后跳转详情页
    fun resolveAndNavigate(
        item: DoubanHotItem,
        onNavigate: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit
    ) {
        // 取消上一次未完成的转换，避免并发导航与状态错乱
        resolveJob?.cancel()
        // 提取标题（去除 【评分】 和 #序号 前缀）
        val cleanTitle = item.title
            .replace(Regex("【\\d+\\.?\\d*】\\s*"), "")
            .replace(Regex("^#\\d+\\s*"), "")
            .trim()

        // 同步检查缓存 → ID 转换缓存，命中则秒进不转圈
        // 优先用 item.tmdbId（口碑榜等已带 tmdbId），否则查豆瓣标题缓存
        val cachedTmdb = if (item.tmdbId > 0) {
            TmdbSearchResult(id = item.tmdbId, title = cleanTitle)
        } else {
            doubanTmdbCache.get(cleanTitle)
        }
        if (cachedTmdb != null && cachedTmdb.id > 0) {
            // 想看/已看缓存
            val wlCached = _watchlistWatchedIds.value?.traktIdByTmdb(cachedTmdb.id, MediaType.MOVIE)
            if (wlCached != null && wlCached > 0) {
                val inWl = _watchlistWatchedIds.value?.isInWatchlist(wlCached, cachedTmdb.id, MediaType.MOVIE) == true
                val isW = _watchlistWatchedIds.value?.isWatched(wlCached, cachedTmdb.id, MediaType.MOVIE) == true
                onNavigate(wlCached, cachedTmdb.id, cachedTmdb.title, "", 0.0, inWl, isW)
                return
            }
            // ID 转换缓存
            val idCached = traktRepository.getCachedTraktId(cachedTmdb.id, MediaType.MOVIE)
            if (idCached != null && idCached > 0) {
                val inWl = _watchlistWatchedIds.value?.isInWatchlist(idCached, cachedTmdb.id, MediaType.MOVIE) == true
                val isW = _watchlistWatchedIds.value?.isWatched(idCached, cachedTmdb.id, MediaType.MOVIE) == true
                onNavigate(idCached, cachedTmdb.id, cachedTmdb.title, "", 0.0, inWl, isW)
                return
            }
            // 负缓存命中：之前搜过没找到，不转圈直接提示
            if (idCached == 0) {
                viewModelScope.launch { _toastEvent.emit(R.string.card_resolve_not_found) }
                return
            }
        }

        resolveJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(resolvingItemId = item.id)
            try {
                // 1. 用 TMDB 搜索（优先从豆瓣标题缓存获取）
                // 优化：口碑榜等已带 tmdbId 的条目直接复用，跳过 TMDB 标题搜索
                val searchResult = if (item.tmdbId > 0) {
                    TmdbSearchResult(id = item.tmdbId, title = cleanTitle)
                } else {
                    doubanTmdbCache.get(cleanTitle) ?: run {
                        val result = tmdbRepository.searchMovie(cleanTitle)
                        if (result != null && result.id > 0) {
                            doubanTmdbCache.put(cleanTitle, result)
                        }
                        result
                    }
                }
                if (searchResult == null || searchResult.id <= 0) {
                    _uiState.value = _uiState.value.copy(resolvingItemId = null)
                    return@launch
                }

                // 2. 优先从全局缓存中查找，命中则秒进
                val cachedTraktId = _watchlistWatchedIds.value?.traktIdByTmdb(searchResult.id, MediaType.MOVIE)
                if (cachedTraktId != null && cachedTraktId > 0) {
                    val inWl = _watchlistWatchedIds.value?.isInWatchlist(cachedTraktId, searchResult.id, MediaType.MOVIE) == true
                    val isW = _watchlistWatchedIds.value?.isWatched(cachedTraktId, searchResult.id, MediaType.MOVIE) == true
                    onNavigate(cachedTraktId, searchResult.id, searchResult.title, "", 0.0, inWl, isW)
                    _uiState.value = _uiState.value.copy(resolvingItemId = null)
                    return@launch
                }

                // 2.5 其次从 ID 转换缓存查找
                val idCached = traktRepository.getCachedTraktId(searchResult.id, MediaType.MOVIE)
                if (idCached != null && idCached > 0) {
                    val inWl = _watchlistWatchedIds.value?.isInWatchlist(idCached, searchResult.id, MediaType.MOVIE) == true
                    val isW = _watchlistWatchedIds.value?.isWatched(idCached, searchResult.id, MediaType.MOVIE) == true
                    onNavigate(idCached, searchResult.id, searchResult.title, "", 0.0, inWl, isW)
                    _uiState.value = _uiState.value.copy(resolvingItemId = null)
                    return@launch
                }
                // 负缓存命中：之前搜过没找到
                if (idCached == 0) {
                    _toastEvent.emit(R.string.card_resolve_not_found)
                    _uiState.value = _uiState.value.copy(resolvingItemId = null)
                    return@launch
                }

                // 3. 缓存未命中，用 Trakt search/tmdb/{id} 转换
                val traktResult = traktRepository.searchByTmdb(searchResult.id, MediaType.MOVIE)
                traktResult.onSuccess { searchResults ->
                    val first = searchResults.firstOrNull()
                    val traktId = first?.movie?.ids?.trakt
                    val imdbId = first?.movie?.ids?.imdb ?: ""
                    if (traktId != null && traktId > 0) {
                        val inWl = _watchlistWatchedIds.value?.isInWatchlist(traktId, searchResult.id, MediaType.MOVIE) == true
                        val isW = _watchlistWatchedIds.value?.isWatched(traktId, searchResult.id, MediaType.MOVIE) == true
                        onNavigate(traktId, searchResult.id, searchResult.title, imdbId, 0.0, inWl, isW)
                    } else {
                        _toastEvent.emit(R.string.card_resolve_not_found)
                    }
                }.onFailure {
                    _toastEvent.emit(R.string.card_resolve_error)
                }
                _uiState.value = _uiState.value.copy(resolvingItemId = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(resolvingItemId = null)
                _toastEvent.emit(R.string.card_resolve_error)
            } finally {
                // 仅当仍是自己设置的 item.id 时才清空，避免清空新协程设的值
                if (_uiState.value.resolvingItemId == item.id) {
                    _uiState.value = _uiState.value.copy(resolvingItemId = null)
                }
            }
        }
    }

    /** 豆瓣推荐卡片点击：通过标题搜索 TMDB，再转换为 Trakt ID 后跳转详情页 */
    fun resolveAndNavigateRecommend(
        item: DoubanRecommendItem,
        isMovieTab: Boolean,
        onNavigate: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit
    ) {
        // 取消上一次未完成的转换，避免并发导航与状态错乱
        resolveJob?.cancel()
        val cleanTitle = item.title.trim()
        val mediaType = if (isMovieTab) MediaType.MOVIE else MediaType.SHOW

        // 同步检查缓存 → 命中则秒进不转圈
        val cachedTmdb = doubanTmdbCache.get(cleanTitle)
        if (cachedTmdb != null && cachedTmdb.id > 0) {
            // 想看/已看缓存
            val wlCached = _watchlistWatchedIds.value?.traktIdByTmdb(cachedTmdb.id, mediaType)
            if (wlCached != null && wlCached > 0) {
                val inWl = _watchlistWatchedIds.value?.isInWatchlist(wlCached, cachedTmdb.id, mediaType) == true
                val isW = _watchlistWatchedIds.value?.isWatched(wlCached, cachedTmdb.id, mediaType) == true
                onNavigate(wlCached, cachedTmdb.id, cachedTmdb.title, "", 0.0, inWl, isW)
                return
            }
            // ID 转换缓存
            val idCached = traktRepository.getCachedTraktId(cachedTmdb.id, mediaType)
            if (idCached != null && idCached > 0) {
                val inWl = _watchlistWatchedIds.value?.isInWatchlist(idCached, cachedTmdb.id, mediaType) == true
                val isW = _watchlistWatchedIds.value?.isWatched(idCached, cachedTmdb.id, mediaType) == true
                onNavigate(idCached, cachedTmdb.id, cachedTmdb.title, "", 0.0, inWl, isW)
                return
            }
            // 负缓存命中：之前搜过没找到
            if (idCached == 0) {
                viewModelScope.launch { _toastEvent.emit(R.string.card_resolve_not_found) }
                return
            }
        }

        resolveJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(resolvingRecommendItemId = item.id)
            try {
                // 1. 用 TMDB 搜索（优先从缓存获取）
                val searchResult = doubanTmdbCache.get(cleanTitle) ?: run {
                    val result = tmdbRepository.searchMovie(cleanTitle)
                    if (result != null && result.id > 0) {
                        doubanTmdbCache.put(cleanTitle, result)
                    }
                    result
                }
                if (searchResult == null || searchResult.id <= 0) {
                    _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
                    _toastEvent.emit(R.string.card_resolve_not_found)
                    return@launch
                }

                // 2. 优先从全局缓存中查找
                val cachedTraktId = _watchlistWatchedIds.value?.traktIdByTmdb(searchResult.id, mediaType)
                if (cachedTraktId != null && cachedTraktId > 0) {
                    val inWl = _watchlistWatchedIds.value?.isInWatchlist(cachedTraktId, searchResult.id, mediaType) == true
                    val isW = _watchlistWatchedIds.value?.isWatched(cachedTraktId, searchResult.id, mediaType) == true
                    onNavigate(cachedTraktId, searchResult.id, searchResult.title, "", 0.0, inWl, isW)
                    _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
                    return@launch
                }

                // 3. ID 转换缓存查找
                val idCached = traktRepository.getCachedTraktId(searchResult.id, mediaType)
                if (idCached != null && idCached > 0) {
                    val inWl = _watchlistWatchedIds.value?.isInWatchlist(idCached, searchResult.id, mediaType) == true
                    val isW = _watchlistWatchedIds.value?.isWatched(idCached, searchResult.id, mediaType) == true
                    onNavigate(idCached, searchResult.id, searchResult.title, "", 0.0, inWl, isW)
                    _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
                    return@launch
                }
                // 负缓存命中
                if (idCached == 0) {
                    _toastEvent.emit(R.string.card_resolve_not_found)
                    _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
                    return@launch
                }

                // 4. 缓存未命中，用 Trakt search/tmdb/{id} 转换
                val traktResult = traktRepository.searchByTmdb(searchResult.id, mediaType)
                traktResult.onSuccess { searchResults ->
                    val first = searchResults.firstOrNull()
                    val traktId = if (mediaType == MediaType.MOVIE) first?.movie?.ids?.trakt else first?.show?.ids?.trakt
                    val imdbId = if (mediaType == MediaType.MOVIE) first?.movie?.ids?.imdb ?: "" else first?.show?.ids?.imdb ?: ""
                    if (traktId != null && traktId > 0) {
                        val inWl = _watchlistWatchedIds.value?.isInWatchlist(traktId, searchResult.id, mediaType) == true
                        val isW = _watchlistWatchedIds.value?.isWatched(traktId, searchResult.id, mediaType) == true
                        onNavigate(traktId, searchResult.id, searchResult.title, imdbId, 0.0, inWl, isW)
                    } else {
                        _toastEvent.emit(R.string.card_resolve_not_found)
                    }
                }.onFailure {
                    _toastEvent.emit(R.string.card_resolve_error)
                }
                _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
                _toastEvent.emit(R.string.card_resolve_error)
            } finally {
                // 仅当仍是自己设置的 item.id 时才清空，避免清空新协程设的值
                if (_uiState.value.resolvingRecommendItemId == item.id) {
                    _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
                }
            }
        }
    }

    fun markViewed(url: String) {
        viewModelScope.launch {
            viewedItemStorage.markViewed(url)
        }
    }

    /** 加载趋势电影全部（分页） */
    fun loadPopularAll(page: Int = 1, forceReload: Boolean = false) {
        if (_uiState.value.isLoadingPopularAll) return
        // forceReload 时清空已有数据（用于切换 timeWindow 后重新加载）
        if (forceReload) {
            _uiState.value = _uiState.value.copy(
                popularAllItems = emptyList(),
                popularAllPage = 1,
                popularAllHasMore = true
            )
        }
        // page 1 且非 forceReload 时，如果已有横向卡片数据，先预填充避免空白转圈
        if (!forceReload && page == 1 && _uiState.value.popularAllItems.isEmpty() && _uiState.value.tmdbPopularMovies.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                popularAllItems = _uiState.value.tmdbPopularMovies,
                popularAllPage = 1,
                popularAllHasMore = true,
                isLoadingPopularAll = false
            )
        }
        _uiState.value = _uiState.value.copy(isLoadingPopularAll = true)
        viewModelScope.launch {
            try {
                val timeWindow = _uiState.value.trendingTimeWindow
                val movies = tmdbRepository.getTrendingMovies(timeWindow = timeWindow, page = page)
                val existing = if (page == 1) emptyList() else _uiState.value.popularAllItems
                val newItems = movies.filter { newItem ->
                    existing.none { it.id == newItem.id }
                }
                _uiState.value = _uiState.value.copy(
                    popularAllItems = existing + newItems,
                    popularAllPage = page,
                    popularAllHasMore = movies.size >= 20,
                    isLoadingPopularAll = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingPopularAll = false)
            }
        }
    }

    /** 加载即将上映全部（分页） */
    fun loadUpcomingAll(page: Int = 1) {
        if (_uiState.value.isLoadingUpcomingAll) return
        // page 1 时，如果已有横向卡片数据，先预填充避免空白转圈
        if (page == 1 && _uiState.value.upcomingAllItems.isEmpty() && _uiState.value.tmdbUpcomingMovies.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                upcomingAllItems = _uiState.value.tmdbUpcomingMovies,
                upcomingAllPage = 1,
                upcomingAllHasMore = true,
                isLoadingUpcomingAll = false
            )
        }
        _uiState.value = _uiState.value.copy(isLoadingUpcomingAll = true)
        viewModelScope.launch {
            try {
                val movies = tmdbRepository.getUpcomingMovies(page = page)
                val existing = if (page == 1) _uiState.value.tmdbUpcomingMovies else _uiState.value.upcomingAllItems
                val newItems = movies.filter { newItem ->
                    existing.none { it.id == newItem.id }
                }
                _uiState.value = _uiState.value.copy(
                    upcomingAllItems = existing + newItems,
                    upcomingAllPage = page,
                    upcomingAllHasMore = movies.size >= 20,
                    isLoadingUpcomingAll = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingUpcomingAll = false)
            }
        }
    }

    /** 加载为你推荐全部（分页） */
    fun loadRecommendationsAll(page: Int = 1) {
        if (_uiState.value.isLoadingRecommendationsAll) return
        // page 1 时，如果已有横向卡片数据，先预填充避免空白转圈
        if (page == 1 && _uiState.value.recommendationsAllItems.isEmpty() && _uiState.value.traktRecommendations.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                recommendationsAllItems = _uiState.value.traktRecommendations,
                recommendationsAllPage = 1,
                recommendationsAllHasMore = true,
                isLoadingRecommendationsAll = false
            )
        }
        _uiState.value = _uiState.value.copy(isLoadingRecommendationsAll = true)
        viewModelScope.launch {
            try {
                // 尝试 Trakt 推荐（分页）
                val result = traktRepository.getRecommendations(limit = 20)
                result.onSuccess { recommendations ->
                    if (recommendations.isNotEmpty()) {
                        // 通过 TMDB 获取本地化标题和海报
                        val enhanced = enhanceWithTmdbData(recommendations)
                        val existing = if (page == 1) _uiState.value.traktRecommendations else _uiState.value.recommendationsAllItems
                        val newItems = enhanced.filter { newItem ->
                            existing.none { it.ids.trakt == newItem.ids.trakt }
                        }
                        _uiState.value = _uiState.value.copy(
                            recommendationsAllItems = existing + newItems,
                            recommendationsAllPage = page,
                            recommendationsAllHasMore = recommendations.size >= 20,
                            isLoadingRecommendationsAll = false
                        )
                    } else {
                        fallbackToTopRatedAll(page)
                    }
                }.onFailure {
                    fallbackToTopRatedAll(page)
                }
            } catch (e: Exception) {
                fallbackToTopRatedAll(page)
            }
        }
    }

    /** 推荐降级为高分电影（分页） */
    private suspend fun fallbackToTopRatedAll(page: Int) {
        try {
            val movies = tmdbRepository.getTopRatedMovies(page = page)
            val traktMovies = movies.map { tmdb ->
                TraktMovie(
                    title = tmdb.title,
                    year = tmdb.release_date.take(4).toIntOrNull() ?: 0,
                    ids = com.tracktosearch.data.remote.trakt.dto.TraktIds(
                        trakt = 0,
                        tmdb = tmdb.id
                    )
                )
            }
            val existing = if (page == 1) emptyList() else _uiState.value.recommendationsAllItems
            val newItems = traktMovies.filter { newItem ->
                existing.none { it.ids.tmdb == newItem.ids.tmdb }
            }
            _uiState.value = _uiState.value.copy(
                recommendationsAllItems = existing + newItems,
                recommendationsAllPage = page,
                recommendationsAllHasMore = traktMovies.size >= 20,
                isLoadingRecommendationsAll = false
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoadingRecommendationsAll = false)
        }
    }
}
