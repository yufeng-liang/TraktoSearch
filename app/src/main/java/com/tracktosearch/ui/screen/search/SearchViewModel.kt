package com.tracktosearch.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.SearchHistoryStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.repository.ResourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val isLoading: Boolean = false,
    val keyword: String = "",
    val resources: List<ResourceItem> = emptyList(),
    val error: String? = null,
    val searchHistory: List<String> = emptyList(),
    val searchHistoryLoaded: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val resourceRepository: ResourceRepository,
    private val searchHistoryStorage: SearchHistoryStorage,
    private val viewedItemStorage: ViewedItemStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            searchHistoryStorage.history.collect { history ->
                _uiState.value = _uiState.value.copy(
                    searchHistory = history,
                    searchHistoryLoaded = true
                )
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
            // 使用 Flow 接口：先到的源先显示，所有源完成后合并去重
            resourceRepository.searchResourcesFlow(keyword = keyword)
                .collect { items ->
                    if (items.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            resources = items
                        )
                    }
                    // 空结果不关闭加载状态，避免短暂显示"没有结果"
                }
            // Flow 结束后，如果仍然没有结果，关闭加载状态
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
