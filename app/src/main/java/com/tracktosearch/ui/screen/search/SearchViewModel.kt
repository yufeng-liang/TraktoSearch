package com.tracktosearch.ui.screen.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.SearchHistoryItem
import com.tracktosearch.data.local.SearchHistoryStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.remote.douban.DoubanHotApiService
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.dto.ResourceType
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.data.util.TtlCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class DoubanHotCategory(
    val id: String,
    val label: String,
    val items: List<DoubanHotItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val total: Int = 0
)

@Immutable
data class SearchUiState(
    val isLoading: Boolean = false,
    val keyword: String = "",
    val resources: List<ResourceItem> = emptyList(),
    val error: String? = null,
    val searchHistory: List<SearchHistoryItem> = emptyList(),
    val doubanHotCategories: List<DoubanHotCategory> = emptyList(),
    val typeFilter: ResourceType = ResourceType.ALL,
    val resolvingItemId: Int? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val resourceRepository: ResourceRepository,
    private val searchHistoryStorage: SearchHistoryStorage,
    private val viewedItemStorage: ViewedItemStorage,
    private val doubanHotApi: DoubanHotApiService,
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val sharedDoubanHotCache: PersistentTtlCache<DoubanHotData>
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    // 热门搜索词 - 从豆瓣新片榜实时获取
    private val _hotSearches = MutableStateFlow<List<String>>(emptyList())
    val hotSearches: StateFlow<List<String>> = _hotSearches.asStateFlow()

    // 热门搜索缓存（1 小时）
    private val hotSearchCache = TtlCache<List<String>>(TTL_DOUBAN)

    companion object {
        private const val TTL_SEARCH = 10 * 60 * 1000L      // 资源搜索 10 分钟
        private const val TTL_DOUBAN = 6 * 60 * 60 * 1000L      // 豆瓣热榜 6 小时

        private val DOUBAN_CATEGORIES = listOf(
            "douban-movie" to "New Movies",
            "douban-weekly" to "Weekly Best",
            "douban-top250" to "Top250",
            "douban-nowplaying" to "Now Playing"
        )
    }

    // 搜索结果缓存
    private val searchResultCache = TtlCache<List<ResourceItem>>(TTL_SEARCH)

    // 搜索历史 - 使用 stateIn 预加载，避免异步延迟
    private val searchHistoryFlow = searchHistoryStorage.history.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        // 收集搜索历史并更新 UI 状态
        viewModelScope.launch {
            searchHistoryFlow.collect { history ->
                _uiState.value = _uiState.value.copy(
                    searchHistory = history
                )
            }
        }
        // 加载热门搜索
        loadHotSearches()
    }

    fun loadHotSearches() {
        viewModelScope.launch {
            // 先检查热门搜索缓存（纯标题列表，本地即可命中）
            val hotCached = hotSearchCache.get("hot_searches_v2")
            if (hotCached != null) {
                _hotSearches.value = hotCached
                return@launch
            }
            // 共享缓存 + 飞行中去重：并发时只发一次网络请求
            val cacheKey = "douban-movie_1_10_v2"
            try {
                val data = sharedDoubanHotCache.getOrAwait(cacheKey) {
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
                val titles = data.items.mapNotNull { item ->
                    item.title.replace(Regex("【\\d+\\.?\\d*】\\s*"), "").ifEmpty { null }
                }.take(8)
                _hotSearches.value = titles
                hotSearchCache.put("hot_searches_v2", titles)
            } catch (_: Exception) {
                // 失败兜底：尝试从 v2 缓存取数据(发现页新片榜可能已加载成功)
                try {
                    val cached = sharedDoubanHotCache.get("douban-movie_1_10_v2")
                    if (cached != null) {
                        val titles = cached.items.take(8).map { item ->
                            item.title.replace(Regex("^【[^】]+】"), "").trim()
                        }.filter { it.isNotBlank() }
                        _hotSearches.value = titles
                        if (titles.isNotEmpty()) {
                            hotSearchCache.put("hot_searches_v2", titles)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadDoubanHot() {
        val categories = DOUBAN_CATEGORIES.map { (id, label) ->
            DoubanHotCategory(id = id, label = label, isLoading = true)
        }
        _uiState.value = _uiState.value.copy(doubanHotCategories = categories)

        DOUBAN_CATEGORIES.forEachIndexed { index, (categoryId, _) ->
            loadDoubanCategory(index, categoryId)
        }
    }

    private fun loadDoubanCategory(index: Int, categoryId: String, skipCache: Boolean = false) {
        viewModelScope.launch {
            val current = _uiState.value.doubanHotCategories.toMutableList()
            if (index < current.size) {
                current[index] = current[index].copy(isLoading = true, error = null)
                _uiState.value = _uiState.value.copy(doubanHotCategories = current)
            }
            val cacheKey = "${categoryId}_1_10_v2"
            try {
                val data = sharedDoubanHotCache.getOrAwait(cacheKey, skipCache = skipCache) {
                    try {
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
                                            url = item.url
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
                    } catch (e: Exception) {
                        throw e
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
                        error = e.message ?: "Failed to load"
                    )
                    _uiState.value = _uiState.value.copy(doubanHotCategories = updated)
                }
            }
        }
    }

    fun retryDoubanCategory(categoryId: String) {
        val index = DOUBAN_CATEGORIES.indexOfFirst { it.first == categoryId }
        if (index >= 0) {
            loadDoubanCategory(index, categoryId, skipCache = true)
        }
    }

    fun retryAllDoubanHot() {
        DOUBAN_CATEGORIES.forEachIndexed { index, (categoryId, _) ->
            val cat = _uiState.value.doubanHotCategories.getOrNull(index)
            if (cat?.error != null || cat?.items.isNullOrEmpty()) {
                loadDoubanCategory(index, categoryId)
            }
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
                                        url = item.url
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
                        error = e.message ?: "Failed to load"
                    )
                    _uiState.value = _uiState.value.copy(doubanHotCategories = updated)
                }
            }
        }
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searchHistoryStorage.add(keyword, "disk")
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                keyword = keyword,
                resources = emptyList(),
                error = null,
                typeFilter = ResourceType.ALL
            )

            try {
                // 10 分钟内相同关键词用缓存
                val cached = searchResultCache.get(keyword.trim())
                if (cached != null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        resources = cached
                    )
                    return@launch
                }

                resourceRepository.searchResourcesFlow(keyword = keyword)
                    .collect { items ->
                        if (items.isNotEmpty()) {
                            searchResultCache.put(keyword.trim(), items)
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                resources = items
                            )
                        }
                    }
                if (_uiState.value.resources.isEmpty()) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load"
                )
            }
        }
    }

    fun removeHistory(keyword: String) {
        viewModelScope.launch {
            searchHistoryStorage.remove(keyword)
        }
    }

    fun addTraktHistory(keyword: String, type: String) {
        viewModelScope.launch {
            searchHistoryStorage.add(keyword, type)
        }
    }

    // 根据输入文本过滤搜索历史，返回最多 5 条建议（以输入文本开头或包含输入文本）
    fun getSuggestions(query: String): List<SearchHistoryItem> {
        if (query.isBlank()) return emptyList()
        val trimmed = query.trim()
        return _uiState.value.searchHistory
            .filter { it.keyword != trimmed && (it.keyword.startsWith(trimmed, ignoreCase = true) || it.keyword.contains(trimmed, ignoreCase = true)) }
            .take(5)
    }

    fun clearHistory() {
        viewModelScope.launch {
            searchHistoryStorage.clear()
        }
    }

    fun clearResults() {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            resources = emptyList(),
            error = null,
            isLoading = false,
            keyword = "",
            typeFilter = ResourceType.ALL
        )
    }

    fun setTypeFilter(filter: ResourceType) {
        _uiState.value = _uiState.value.copy(typeFilter = filter)
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
}
