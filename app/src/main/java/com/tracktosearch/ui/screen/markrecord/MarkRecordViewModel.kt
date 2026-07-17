package com.tracktosearch.ui.screen.markrecord

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.db.MarkActionRecordDao
import com.tracktosearch.data.local.db.MarkActionRecordEntity
import com.tracktosearch.data.local.db.MarkActionType
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.PosterColorExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 标记记录 Tab */
enum class MarkRecordTab(val actionTypes: List<String>?) {
    ALL(null),
    WATCHLIST(listOf(MarkActionType.ADD_WATCHLIST.value)),
    WATCHED(null),
    REMOVED(listOf(MarkActionType.REMOVE_WATCHLIST.value, MarkActionType.UNMARK_WATCHED.value))
}

/** 日期范围预设 */
enum class DatePreset { SEVEN_DAYS, THIRTY_DAYS, CUSTOM, ALL }

/** 当前标记状态 */
enum class CurrentMarkStatus { IN_WATCHLIST, WATCHED, NONE }

/** UI 层统一的记录项 */
data class MarkRecordItem(
    val traktId: Int,
    val tmdbId: Int,
    val imdbId: String,
    val mediaType: String,
    val title: String,
    val displayTitle: String,
    val posterUrl: String?,
    val year: Int?,
    val actionType: String,
    val actedAt: Long,
    val episodeInfo: String?,
    val currentStatus: CurrentMarkStatus?
)

data class MarkRecordUiState(
    val isLoading: Boolean = false,
    val items: List<MarkRecordItem> = emptyList(),
    val currentTab: MarkRecordTab = MarkRecordTab.ALL,
    val searchQuery: String = "",
    val filterMediaTypes: Set<String> = emptySet(),
    val filterDatePreset: DatePreset = DatePreset.ALL,
    val filterDateRange: Pair<Long, Long>? = null,
    val sortAscending: Boolean = false,
    val currentPage: Int = 0,
    val hasMore: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val currentStatusMap: Map<Int, CurrentMarkStatus> = emptyMap()
)

@HiltViewModel
class MarkRecordViewModel @Inject constructor(
    private val markActionRecordDao: MarkActionRecordDao,
    private val traktRepository: TraktRepository,
    val posterColorExtractor: PosterColorExtractor
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarkRecordUiState(isLoading = true))
    val uiState: StateFlow<MarkRecordUiState> = _uiState.asStateFlow()

    private val pageSize = 50

    init {
        loadFirstPage()
    }

    fun switchTab(tab: MarkRecordTab) {
        if (_uiState.value.currentTab == tab) return
        _uiState.update {
            it.copy(
                currentTab = tab, currentPage = 0, hasMore = true,
                items = emptyList(), isLoading = true, error = null
            )
        }
        loadFirstPage()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query, currentPage = 0, hasMore = true,
                items = emptyList(), isLoading = true
            )
        }
        loadFirstPage()
    }

    fun updateFilter(
        mediaTypes: Set<String>,
        datePreset: DatePreset,
        dateRange: Pair<Long, Long>?,
        ascending: Boolean
    ) {
        _uiState.update {
            it.copy(
                filterMediaTypes = mediaTypes,
                filterDatePreset = datePreset,
                filterDateRange = dateRange,
                sortAscending = ascending,
                currentPage = 0, hasMore = true, items = emptyList(), isLoading = true
            )
        }
        loadFirstPage()
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            loadPage(state.currentPage + 1)
            updateCurrentStatusMap()
        }
    }

    fun refresh() {
        _uiState.update {
            it.copy(
                currentPage = 0, hasMore = true, items = emptyList(),
                isLoading = true, error = null
            )
        }
        traktRepository.clearWatchHistoryCache()
        loadFirstPage()
    }

    fun retry() {
        _uiState.update { it.copy(error = null, isLoading = true) }
        loadFirstPage()
    }

    private fun loadFirstPage() {
        viewModelScope.launch {
            loadPage(1)
            updateCurrentStatusMap()
        }
    }

    /** 计算当前状态徽标（从 WatchlistWatchedIds 全局缓存查询） */
    private suspend fun updateCurrentStatusMap() {
        val items = _uiState.value.items
        if (items.isEmpty()) return
        val ids = traktRepository.getWatchlistWatchedIds() ?: return
        val map = mutableMapOf<Int, CurrentMarkStatus>()
        for (item in items) {
            val type = if (item.mediaType == "movie") MediaType.MOVIE else MediaType.SHOW
            val inWl = ids.isInWatchlist(item.traktId, null, type)
            val watched = ids.isWatched(item.traktId, null, type)
            map[item.traktId] = when {
                watched -> CurrentMarkStatus.WATCHED
                inWl -> CurrentMarkStatus.IN_WATCHLIST
                else -> CurrentMarkStatus.NONE
            }
        }
        _uiState.update { state ->
            state.copy(
                currentStatusMap = map,
                // 将当前状态写回列表项，供当前状态徽标与移除分类暗化使用
                items = state.items.map { it.copy(currentStatus = map[it.traktId]) }
            )
        }
    }

    private suspend fun loadPage(page: Int) {
        val state = _uiState.value
        try {
            val newItems: List<MarkRecordItem> = when (state.currentTab) {
                MarkRecordTab.WATCHED -> loadFromTraktHistory(page)
                MarkRecordTab.ALL -> {
                    val localItems = loadFromDao(page, state)
                    // ALL Tab 合并 local 自建表 + Trakt 已看历史；Trakt 失败不影响 local 展示
                    val traktItems = if (page == 1) {
                        try { loadFromTraktHistory(1) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                    } else emptyList()
                    (localItems + traktItems).sortedByDescending { it.actedAt }.take(pageSize)
                }
                else -> loadFromDao(page, state)
            }
            val allItems = if (page == 1) newItems else _uiState.value.items + newItems
            // ALL Tab 是合并视图，不做分页（hasMore 恒为 false）
            val hasMore = newItems.size == pageSize && state.currentTab != MarkRecordTab.ALL
            _uiState.update {
                it.copy(
                    items = allItems,
                    currentPage = page,
                    hasMore = hasMore,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, isLoadingMore = false, error = e.message ?: "加载失败") }
        }
    }

    private suspend fun loadFromDao(page: Int, state: MarkRecordUiState): List<MarkRecordItem> {
        val (startTime, endTime) = computeTimeRange(state)
        val titleQuery = state.searchQuery.takeIf { it.isNotBlank() }?.let { "%$it%" }
        val records = markActionRecordDao.query(
            actionTypes = state.currentTab.actionTypes ?: emptyList(),
            actionTypesEmpty = state.currentTab.actionTypes == null,
            mediaTypes = state.filterMediaTypes.toList(),
            mediaTypesEmpty = state.filterMediaTypes.isEmpty(),
            startTime = startTime,
            endTime = endTime,
            titleQuery = titleQuery,
            ascending = state.sortAscending,
            limit = pageSize,
            offset = (page - 1) * pageSize
        )
        return records.map { it.toMarkRecordItem() }
    }

    private suspend fun loadFromTraktHistory(page: Int): List<MarkRecordItem> {
        val result = traktRepository.fetchWatchHistory(page)
        if (!result.isSuccess) throw result.exceptionOrNull() ?: Exception("fetchWatchHistory failed")
        val historyPage = result.getOrNull()!!
        return historyPage.items.map { it.toMarkRecordItem() }
    }

    private fun computeTimeRange(state: MarkRecordUiState): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return when (state.filterDatePreset) {
            DatePreset.SEVEN_DAYS -> Pair(now - 7L * 24 * 60 * 60 * 1000, 0)
            DatePreset.THIRTY_DAYS -> Pair(now - 30L * 24 * 60 * 60 * 1000, 0)
            DatePreset.CUSTOM -> state.filterDateRange ?: Pair(0, 0)
            DatePreset.ALL -> Pair(0, 0)
        }
    }
}

private fun MarkActionRecordEntity.toMarkRecordItem() = MarkRecordItem(
    traktId = traktId,
    tmdbId = tmdbId,
    imdbId = imdbId,
    mediaType = mediaType,
    title = title,
    displayTitle = displayTitle,
    posterUrl = posterUrl,
    year = year,
    actionType = actionType,
    actedAt = actedAt,
    episodeInfo = episodeInfo,
    currentStatus = null
)

private fun TraktRepository.WatchHistoryItem.toMarkRecordItem() = MarkRecordItem(
    traktId = traktId,
    tmdbId = tmdbId,
    imdbId = imdbId,
    mediaType = mediaType,
    title = title,
    displayTitle = displayTitle,
    posterUrl = posterUrl,
    year = year,
    actionType = "WATCHED",
    actedAt = watchedAt,
    episodeInfo = episodeInfo,
    currentStatus = null
)
