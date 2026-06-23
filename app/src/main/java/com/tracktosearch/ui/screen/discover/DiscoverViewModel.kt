package com.tracktosearch.ui.screen.discover

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.SearchHistoryStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.local.DiscoverSectionConfig
import com.tracktosearch.data.local.DiscoverSectionStorage
import com.tracktosearch.data.remote.douban.DoubanHotApiService
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.TtlCache
import com.tracktosearch.ui.screen.search.DoubanHotCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@Immutable
data class DiscoverUiState(
    val doubanHotCategories: List<DoubanHotCategory> = emptyList(),
    val tmdbPopularMovies: List<TmdbSearchResult> = emptyList(),
    val tmdbUpcomingMovies: List<TmdbSearchResult> = emptyList(),
    val traktRecommendations: List<TraktMovie> = emptyList(),
    val popularTotal: Int = 0,
    val upcomingTotal: Int = 0,
    val recommendationsTotal: Int = 0,
    val isLoadingPopular: Boolean = false,
    val isLoadingUpcoming: Boolean = false,
    val isLoadingRecommendations: Boolean = false,
    val popularError: String? = null,
    val upcomingError: String? = null,
    val recommendationsError: String? = null,
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
    val isLoadingRecommendationsAll: Boolean = false
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val doubanHotApi: DoubanHotApiService,
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val searchHistoryStorage: SearchHistoryStorage,
    private val viewedItemStorage: ViewedItemStorage,
    private val discoverSectionStorage: DiscoverSectionStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    // 发现页栏目配置（显示/隐藏 + 排序），同步读取已保存顺序作为初始值，避免首帧跳动
    val sectionConfigs: StateFlow<List<DiscoverSectionConfig>> = discoverSectionStorage.sectionConfigs
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            runBlocking { discoverSectionStorage.sectionConfigs.first() }
        )

    companion object {
        private const val TTL_DOUBAN = 60 * 60 * 1000L // 豆瓣热榜 1 小时

        private val DOUBAN_CATEGORIES = listOf(
            "douban-movie",
            "douban-weekly",
            "douban-top250",
            "douban-us-box"
        )
    }

    // 豆瓣热榜缓存
    private val doubanHotCache = TtlCache<DoubanHotData>(TTL_DOUBAN)

    init {
        loadDoubanHot()
        loadTmdbPopular()
        loadTmdbUpcoming()
        loadTraktRecommendations()
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
            // 1 小时内用缓存
            val cacheKey = "${categoryId}_1_10"
            doubanHotCache.get(cacheKey)?.let { data ->
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
                return@launch
            }
            try {
                val response = doubanHotApi.getDoubanHot(category = categoryId, limit = 10)
                val updated = _uiState.value.doubanHotCategories.toMutableList()
                if (index < updated.size) {
                    updated[index] = updated[index].copy(
                        items = response.data.items,
                        isLoading = false,
                        error = null,
                        total = response.data.total
                    )
                    _uiState.value = _uiState.value.copy(doubanHotCategories = updated)
                }
                doubanHotCache.put(cacheKey, response.data)
            } catch (e: Exception) {
                val updated = _uiState.value.doubanHotCategories.toMutableList()
                if (index < updated.size) {
                    updated[index] = updated[index].copy(
                        isLoading = false,
                        error = e.message ?: "加载失败"
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
            val cacheKey = "${categoryId}_${page}_$limit"
            if (page == 1) {
                doubanHotCache.get(cacheKey)?.let { data ->
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
                val response = doubanHotApi.getDoubanHot(category = categoryId, page = page, limit = limit)
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
                    doubanHotCache.put(cacheKey, response.data)
                }
            } catch (e: Exception) {
                val updated = _uiState.value.doubanHotCategories.toMutableList()
                if (idx >= 0) {
                    updated[idx] = updated[idx].copy(
                        isLoading = false,
                        error = e.message ?: "加载失败"
                    )
                    _uiState.value = _uiState.value.copy(doubanHotCategories = updated)
                }
            }
        }
    }

    fun loadTmdbPopular() {
        _uiState.value = _uiState.value.copy(isLoadingPopular = true, popularError = null)
        viewModelScope.launch {
            try {
                val movies = tmdbRepository.getPopularMovies()
                _uiState.value = _uiState.value.copy(
                    tmdbPopularMovies = movies,
                    isLoadingPopular = false,
                    popularError = if (movies.isEmpty()) "暂无数据" else null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingPopular = false,
                    popularError = e.message ?: "加载失败"
                )
            }
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
                    upcomingError = if (movies.isEmpty()) "暂无数据" else null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingUpcoming = false,
                    upcomingError = e.message ?: "加载失败"
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
                recommendationsError = if (traktMovies.isEmpty()) "暂无数据" else null
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoadingRecommendations = false,
                recommendationsError = e.message ?: "加载失败"
            )
        }
    }

    /** TMDB 电影卡片点击：通过 TMDB ID 转换为 Trakt ID 后跳转 */
    fun resolveTmdbAndNavigate(
        tmdbId: Int,
        title: String,
        onNavigate: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(resolvingTmdbId = tmdbId)
            try {
                val traktResult = traktRepository.searchByTmdb(tmdbId, MediaType.MOVIE)
                traktResult.onSuccess { searchResults ->
                    val first = searchResults.firstOrNull()
                    val traktId = first?.movie?.ids?.trakt
                    val imdbId = first?.movie?.ids?.imdb ?: ""
                    if (traktId != null && traktId > 0) {
                        onNavigate(traktId, tmdbId, title, imdbId, 0.0)
                    }
                }
            } catch (_: Exception) {
                // 忽略
            } finally {
                _uiState.value = _uiState.value.copy(resolvingTmdbId = null)
            }
        }
    }

    /** Trakt 推荐卡片点击：已有 Trakt ID 和 TMDB ID，直接跳转 */
    fun navigateTraktMovie(
        movie: TraktMovie,
        onNavigate: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit
    ) {
        val traktId = movie.ids.trakt
        val tmdbId = movie.ids.tmdb
        val imdbId = movie.ids.imdb
        if (traktId > 0) {
            onNavigate(traktId, tmdbId, movie.title, imdbId, movie.rating)
        } else if (tmdbId > 0) {
            // 降级：只有 TMDB ID 时走转换
            resolveTmdbAndNavigate(tmdbId, movie.title, onNavigate)
        }
    }

    fun retryAll() {
        loadDoubanHot()
        loadTmdbPopular()
        loadTmdbUpcoming()
        loadTraktRecommendations()
    }

    // 豆瓣热榜卡片点击：通过 TMDB 搜索标题，再转换为 Trakt ID 后跳转详情页
    fun resolveAndNavigate(
        item: DoubanHotItem,
        onNavigate: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(resolvingItemId = item.id)
            try {
                // 提取标题（去除 【评分】 和 #序号 前缀）
                val cleanTitle = item.title
                    .replace(Regex("【\\d+\\.?\\d*】\\s*"), "")
                    .replace(Regex("^#\\d+\\s*"), "")
                    .trim()

                // 1. 用 TMDB 搜索
                val searchResult = tmdbRepository.searchMovie(cleanTitle)
                if (searchResult == null || searchResult.id <= 0) {
                    _uiState.value = _uiState.value.copy(resolvingItemId = null)
                    return@launch
                }

                // 2. 用 Trakt search/tmdb/{id} 转换为 trakt id
                val traktResult = traktRepository.searchByTmdb(searchResult.id, MediaType.MOVIE)
                traktResult.onSuccess { searchResults ->
                    val first = searchResults.firstOrNull()
                    val traktId = first?.movie?.ids?.trakt
                    val imdbId = first?.movie?.ids?.imdb ?: ""
                    if (traktId != null && traktId > 0) {
                        onNavigate(traktId, searchResult.id, searchResult.title, imdbId, 0.0)
                    }
                }
                _uiState.value = _uiState.value.copy(resolvingItemId = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(resolvingItemId = null)
            }
        }
    }

    fun markViewed(url: String) {
        viewModelScope.launch {
            viewedItemStorage.markViewed(url)
        }
    }

    /** 加载热门电影全部（分页） */
    fun loadPopularAll(page: Int = 1) {
        if (_uiState.value.isLoadingPopularAll) return
        // page 1 时，如果已有横向卡片数据，先预填充避免空白转圈
        if (page == 1 && _uiState.value.popularAllItems.isEmpty() && _uiState.value.tmdbPopularMovies.isNotEmpty()) {
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
                val movies = tmdbRepository.getPopularMovies(page = page)
                val existing = if (page == 1) _uiState.value.tmdbPopularMovies else _uiState.value.popularAllItems
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
