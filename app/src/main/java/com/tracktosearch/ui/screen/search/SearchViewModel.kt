package com.tracktosearch.ui.screen.search

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.SearchHistoryItem
import com.tracktosearch.data.local.SearchHistoryStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.remote.douban.DoubanRexxarApiService
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.data.remote.douban.dto.toDoubanHotItem
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.data.util.TtlCache
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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

/**
 * 搜索页状态。
 *
 * 这里不含搜索结果：搜索提交后由内联的 TraktSearchScreen 承载结果与其加载/错误态，
 * 本页只负责输入、历史、热词与豆瓣热榜。以前这里还有 isLoading / resources / error /
 * typeFilter 四个字段，从没有任何 UI 渲染方，恒为初始值。
 */
@Immutable
data class SearchUiState(
    val searchHistory: List<SearchHistoryItem> = emptyList(),
    val doubanHotCategories: List<DoubanHotCategory> = emptyList(),
    val resolvingItemId: Int? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
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

    // 热门搜索词 - 从豆瓣口碑榜实时获取
    private val _hotSearches = MutableStateFlow<List<String>>(emptyList())
    val hotSearches: StateFlow<List<String>> = _hotSearches.asStateFlow()

    // 热门搜索缓存（1 小时）
    private val hotSearchCache = TtlCache<List<String>>(TTL_DOUBAN)

    companion object {
        private const val TTL_DOUBAN = 6 * 60 * 60 * 1000L      // 豆瓣热榜 6 小时

        /** 豆瓣分类 → Rexxar subject_collection id（App 直连豆瓣移动端）。 */
        fun doubanCollectionId(categoryId: String): String = when (categoryId) {
            "douban-weekly" -> "movie_weekly_best"
            "douban-top250" -> "movie_top250"
            "douban-nowplaying" -> "movie_hot_gaia"
            else -> "movie_hot" // douban-movie 新片榜
        }
    }

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
            // key 与发现页口碑榜同一格式：数据同为口碑榜，发现页先加载过则此处直接命中缓存
            val cacheKey = "douban-weekly_1_10_v5"
            try {
                val data = sharedDoubanHotCache.getOrAwait(cacheKey) {
                    val response = doubanRexxarApi.getCollectionItems(
                        collectionId = doubanCollectionId("douban-weekly"),
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
                // 失败兜底：尝试从共享缓存取数据(发现页口碑榜可能已加载成功)
                try {
                    val cached = sharedDoubanHotCache.get("douban-weekly_1_10_v5")
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

    // 会话级预解析结果：豆瓣条目 id(hashCode) → imdbId。
    // 单元测试覆盖 resolveAndNavigate，声明保留；预解析写入方已随豆瓣热榜死代码删除，此表恒为空。
    private val prefetchedImdbIds = java.util.concurrent.ConcurrentHashMap<Int, String>()

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
