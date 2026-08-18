package com.tracktosearch.ui.screen.search

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.local.SearchHistoryItem
import com.tracktosearch.data.local.SearchHistoryStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.remote.douban.DoubanRexxarApiService
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.data.remote.douban.dto.toDoubanHotItem
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.dto.ResourceType
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.data.util.TtlCache
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val doubanRexxarApi: DoubanRexxarApiService,
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val sharedDoubanHotCache: PersistentTtlCache<DoubanHotData>,
    @ApplicationContext private val context: Context
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

        /** 预解析 imdbId 的最大条目数：只处理首屏可见数量，避免大量爬取触发反爬。 */
        private const val PREFETCH_IMDB_LIMIT = 8

        /** 豆瓣分类 → Rexxar subject_collection id（App 直连豆瓣移动端）。 */
        fun doubanCollectionId(categoryId: String): String = when (categoryId) {
            "douban-weekly" -> "movie_weekly_best"
            "douban-top250" -> "movie_top250"
            "douban-nowplaying" -> "movie_hot_gaia"
            else -> "movie_hot" // douban-movie 新片榜
        }
    }

    // 搜索结果缓存
    private val searchResultCache = TtlCache<List<ResourceItem>>(TTL_SEARCH)

    // 搜索历史 - SearchHistoryStorage 已用 StateFlow 暴露，直接收集
    private val searchHistoryFlow = searchHistoryStorage.history

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
            // 缓存 key 带版本号 v4：数据源切为 App 直连豆瓣 Rexxar，与发现页共享同一缓存
            val cacheKey = "douban-movie_1_10_v5"
            try {
                val data = sharedDoubanHotCache.getOrAwait(cacheKey) {
                    val response = doubanRexxarApi.getCollectionItems(
                        collectionId = doubanCollectionId("douban-movie"),
                        start = 0,
                        count = 20
                    ).takeIf { it.isSuccessful }?.body()
                    DoubanHotData(
                        items = response?.subject_collection_items?.map { it.toDoubanHotItem() } ?: emptyList(),
                        total = response?.total ?: 0
                    )
                }
                val titles = data.items.mapNotNull { item ->
                    item.title.replace(Regex("【\\d+\\.?\\d*】\\s*"), "").ifEmpty { null }
                }.take(8)
                _hotSearches.value = titles
                hotSearchCache.put("hot_searches_v2", titles)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 失败兜底：尝试从 v2 缓存取数据(发现页新片榜可能已加载成功)
                try {
                    val cached = sharedDoubanHotCache.get("douban-movie_1_10_v5")
                    if (cached != null) {
                        val titles = cached.items.take(8).map { item ->
                            item.title.replace(Regex("^【[^】]+】"), "").trim()
                        }.filter { it.isNotBlank() }
                        _hotSearches.value = titles
                        if (titles.isNotEmpty()) {
                            hotSearchCache.put("hot_searches_v2", titles)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
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
            // 缓存 key 带版本号 v4：数据源切为 App 直连豆瓣 Rexxar（带评分/豆瓣海报），旧 v3 网关缓存自动失效
            val cacheKey = "${categoryId}_1_10_v5"
            try {
                val data = sharedDoubanHotCache.getOrAwait(cacheKey, skipCache = skipCache) {
                    val response = doubanRexxarApi.getCollectionItems(
                        collectionId = doubanCollectionId(categoryId),
                        start = 0,
                        count = if (categoryId == "douban-top250") 25 else 20
                    ).takeIf { it.isSuccessful }?.body()
                    DoubanHotData(
                        items = response?.subject_collection_items?.map { it.toDoubanHotItem() } ?: emptyList(),
                        total = response?.total ?: 0,
                        hasMore = response != null && (response.start + response.count) < response.total
                    )
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
                // 预解析 imdbId：列表先展示，再后台串行解析（点击时无需等待 ID 转换）
                prefetchImdbIds(data.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val updated = _uiState.value.doubanHotCategories.toMutableList()
                if (index < updated.size) {
                    updated[index] = updated[index].copy(
                        isLoading = false,
                        error = e.toUserMessage(context, R.string.error_search_failed)
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

    /** 会话级预解析结果：豆瓣条目 id(hashCode) → imdbId。点击时优先使用，避免等待 ID 转换。 */
    private val prefetchedImdbIds = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /**
     * 预解析豆瓣热榜条目的 imdbId：在用户点击卡片前完成后台解析，点击时无需等待。
     * - 本地/磁盘/全局池缓存命中：立即完成（零网络，不触发反爬）
     * - 未命中：串行爬详情页（fetchDetail 内部带反爬延迟与一次重试），只处理首屏前 [PREFETCH_IMDB_LIMIT] 条
     * - 未登录（无 cookie）：跳过，点击时走原有解析路径
     * - 失败/取消：跳过不阻塞，不影响列表展示
     */
    private suspend fun prefetchImdbIds(items: List<DoubanHotItem>) {
        val cookie = doubanAuthStorage.getCredentials()?.cookie ?: return
        items.take(PREFETCH_IMDB_LIMIT).forEach { item ->
            val itemId = item.id ?: return@forEach
            if (prefetchedImdbIds.containsKey(itemId)) return@forEach
            runCatching {
                val (info, _) = doubanRepository.fetchDetail(
                    doubanUrl = item.url,
                    cookie = cookie,
                    title = item.title,
                    uploadToCloudPool = false
                )
                if (info != null && !info.imdbId.isNullOrBlank()) {
                    prefetchedImdbIds[itemId] = info.imdbId.orEmpty()
                }
            }
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
            // 缓存 key 带版本号 v4：数据源切为 App 直连豆瓣 Rexxar，旧 v3 网关缓存自动失效
            val cacheKey = "${categoryId}_${page}_${limit}_v5"
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
                    "douban-movie", "douban-weekly", "douban-top250", "douban-nowplaying" -> {
                        val rexxarResponse = doubanRexxarApi.getCollectionItems(
                            collectionId = doubanCollectionId(categoryId),
                            start = (page - 1) * limit,
                            count = limit
                        ).takeIf { it.isSuccessful }?.body()
                        com.tracktosearch.data.remote.douban.dto.DoubanHotResponse(
                            code = 0,
                            data = com.tracktosearch.data.remote.douban.dto.DoubanHotData(
                                items = rexxarResponse?.subject_collection_items?.map { it.toDoubanHotItem() } ?: emptyList(),
                                total = rexxarResponse?.total ?: 0,
                                hasMore = rexxarResponse != null && (rexxarResponse.start + rexxarResponse.count) < rexxarResponse.total,
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val updated = _uiState.value.doubanHotCategories.toMutableList()
                if (idx >= 0) {
                    updated[idx] = updated[idx].copy(
                        isLoading = false,
                        error = e.toUserMessage(context, R.string.error_search_failed)
                    )
                    _uiState.value = _uiState.value.copy(doubanHotCategories = updated)
                }
            }
        }
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return

        // 缓存检查在协程启动前,命中则同步返回,避免 isLoading=true→false 闪烁
        val cached = searchResultCache.get(keyword.trim())
        if (cached != null) {
            searchJob?.cancel()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                keyword = keyword,
                resources = cached,
                error = null,
                typeFilter = ResourceType.ALL
            )
            // 搜索历史仍需记录(异步,不阻塞 UI)
            viewModelScope.launch { searchHistoryStorage.add(keyword, "disk") }
            return
        }

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage(context, R.string.error_search_failed)
                )
            }
        }
    }

    fun removeHistory(keyword: String, type: String? = null) {
        viewModelScope.launch {
            searchHistoryStorage.remove(keyword, type)
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
                // CF 已完成豆瓣 subject -> TMDB -> Trakt 转换时，直接进入详情页。
                if (item.tmdbId > 0 && item.traktId > 0) {
                    // 预解析的 imdbId 优先：Rexxar 条目本身不带 imdb，解析后点击即用
                    onNavigate(item.traktId, item.tmdbId, cleanTitle, prefetchedImdbIds[item.id] ?: item.imdbId, 0.0)
                    return@launch
                }

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
                    // 预解析的 imdbId 优先，未命中则用 Trakt 返回的
                    val imdbId = prefetchedImdbIds[item.id] ?: first?.movie?.ids?.imdb ?: ""
                    if (traktId != null && traktId > 0) {
                        onNavigate(traktId, searchResult.id, searchResult.title, imdbId, 0.0)
                    }
                }
                _uiState.value = _uiState.value.copy(resolvingItemId = null)
            } catch (e: CancellationException) {
                throw e
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
