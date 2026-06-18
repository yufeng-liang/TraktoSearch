package com.tracktosearch.ui.screen.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.tracktosearch.data.local.SearchHistoryStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.remote.douban.DoubanHotApiService
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.repository.ResourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class SearchUiState(
    val isLoading: Boolean = false,
    val keyword: String = "",
    val resources: List<ResourceItem> = emptyList(),
    val error: String? = null,
    val searchHistory: List<String> = emptyList(),
    val searchHistoryLoaded: Boolean = false,
    val doubanHotCategories: List<DoubanHotCategory> = emptyList()
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val resourceRepository: ResourceRepository,
    private val searchHistoryStorage: SearchHistoryStorage,
    private val viewedItemStorage: ViewedItemStorage,
    private val doubanHotApi: DoubanHotApiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    companion object {
        private val DOUBAN_CATEGORIES = listOf(
            "douban-movie" to "新片榜",
            "douban-weekly" to "口碑榜",
            "douban-top250" to "Top250",
            "douban-us-box" to "北美票房榜"
        )
    }

    init {
        viewModelScope.launch {
            searchHistoryStorage.history.collect { history ->
                _uiState.value = _uiState.value.copy(
                    searchHistory = history,
                    searchHistoryLoaded = true
                )
            }
        }
        loadDoubanHot()
    }

    private fun loadDoubanHot() {
        val categories = DOUBAN_CATEGORIES.map { (id, label) ->
            DoubanHotCategory(id = id, label = label, isLoading = true)
        }
        _uiState.value = _uiState.value.copy(doubanHotCategories = categories)

        DOUBAN_CATEGORIES.forEachIndexed { index, (categoryId, _) ->
            viewModelScope.launch {
                try {
                    val response = doubanHotApi.getDoubanHot(category = categoryId, limit = 10)
                    Log.d("DoubanHot", "Category: $categoryId, items count: ${response.data.items.size}")
                    response.data.items.forEach { item ->
                        Log.d("DoubanHot", "  item: title=${item.title}, desc=${item.desc}, hot=${item.hot}, cover=${item.cover}")
                    }
                    val current = _uiState.value.doubanHotCategories.toMutableList()
                    if (index < current.size) {
                        current[index] = current[index].copy(
                            items = response.data.items,
                            isLoading = false,
                            error = null,
                            total = response.data.total
                        )
                        _uiState.value = _uiState.value.copy(doubanHotCategories = current)
                    }
                } catch (e: Exception) {
                    Log.e("DoubanHot", "Failed to load category: $categoryId", e)
                    val current = _uiState.value.doubanHotCategories.toMutableList()
                    if (index < current.size) {
                        current[index] = current[index].copy(
                            isLoading = false,
                            error = e.message ?: "加载失败"
                        )
                        _uiState.value = _uiState.value.copy(doubanHotCategories = current)
                    }
                }
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

    fun search(keyword: String) {
        if (keyword.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searchHistoryStorage.add(keyword)
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                keyword = keyword,
                resources = emptyList(),
                error = null
            )
            resourceRepository.searchResourcesFlow(keyword = keyword)
                .collect { items ->
                    if (items.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            resources = items
                        )
                    }
                }
            if (_uiState.value.resources.isEmpty()) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun removeHistory(keyword: String) {
        viewModelScope.launch {
            searchHistoryStorage.remove(keyword)
        }
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
            keyword = ""
        )
    }

    fun markViewed(url: String) {
        viewModelScope.launch {
            viewedItemStorage.markViewed(url)
        }
    }
}
