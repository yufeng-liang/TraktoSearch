package com.tracktosearch.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.SearchHistoryStorage
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.repository.ResourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val searchHistory: List<String> = emptyList()
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val resourceRepository: ResourceRepository,
    private val searchHistoryStorage: SearchHistoryStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            searchHistoryStorage.history.collect { history ->
                _uiState.value = _uiState.value.copy(searchHistory = history)
            }
        }
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return

        viewModelScope.launch {
            searchHistoryStorage.add(keyword)
            _uiState.value = SearchUiState(isLoading = true, keyword = keyword, searchHistory = _uiState.value.searchHistory)
            val result = resourceRepository.searchResources(keyword = keyword)
            result.onSuccess { items ->
                val quarkOnly = items.filter { it.diskType == com.tracktosearch.data.remote.dto.DiskType.QUARK }
                val finalItems = if (quarkOnly.isNotEmpty()) quarkOnly else items
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    resources = finalItems
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
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
}
