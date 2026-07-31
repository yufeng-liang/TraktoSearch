package com.tracktosearch.ui.screen.markrecord

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.local.db.MarkActionRecordDao
import com.tracktosearch.data.local.db.MarkActionRecordEntity
import com.tracktosearch.data.local.db.MarkActionType
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.data.util.UserActionTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    val posterColorExtractor: PosterColorExtractor,
    private val sessionModeManager: SessionModeManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarkRecordUiState(isLoading = true))
    val uiState: StateFlow<MarkRecordUiState> = _uiState.asStateFlow()

    private val pageSize = 50

    /** Trakt 已看历史总页数（首次拉取后从缓存记录，用于 ALL Tab 分页判断）。
     *  refresh 时重置为 0，重新拉取。 */
    private var traktHistoryTotalPages = 0

    init {
        loadFirstPage()
    }

    fun switchTab(tab: MarkRecordTab) {
        if (_uiState.value.currentTab == tab) return
        UserActionTracker.record("action", "switch_tab", tab.name)
        _uiState.update {
            it.copy(
                currentTab = tab, currentPage = 0, hasMore = true,
                items = emptyList(), isLoading = true, error = null
            )
        }
        loadFirstPage()
    }

    fun updateSearchQuery(query: String) {
        if (query.isNotEmpty()) {
            UserActionTracker.record("action", "search", query)
        }
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
        val filterDetail = buildString {
            if (mediaTypes.isNotEmpty()) append("types=${mediaTypes.joinToString(",")} ")
            append("preset=${datePreset.name} ")
            append("asc=$ascending")
        }
        UserActionTracker.record("action", "update_filter", filterDetail)
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
        UserActionTracker.record("action", "load_next_page", "page=${state.currentPage + 1}")
        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            loadPage(state.currentPage + 1)
            updateCurrentStatusMap()
        }
    }

    fun refresh() {
        UserActionTracker.record("action", "refresh", null)
        _uiState.update {
            it.copy(
                currentPage = 0, hasMore = true, items = emptyList(),
                isLoading = true, error = null
            )
        }
        traktRepository.clearWatchHistoryCache()
        // 清缓存后 totalPages 失效，重置以便重新拉取
        traktHistoryTotalPages = 0
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
        if (!sessionModeManager.traktConnected.value) return
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
            // ALL Tab 需要同时拉本地流水和 Trakt 已看历史,记录本地条目数用于 hasMore 判断
            var allTabLocalItemCount = 0
            val newItems: List<MarkRecordItem> = when (state.currentTab) {
            MarkRecordTab.WATCHED -> {
                if (sessionModeManager.traktConnected.value) {
                    loadFromTraktHistory(
                        page = page,
                        mediaTypesFilter = state.filterMediaTypes,
                        onFirstBatch = { firstBatch ->
                            _uiState.update { it.copy(items = firstBatch, isLoading = false) }
                        }
                    )
                } else emptyList()
            }
            MarkRecordTab.ALL -> {
                val localItems = loadFromDao(page, state)
                allTabLocalItemCount = localItems.size
                // Trakt 已看历史分页:首次(page=1)必拉以获取 totalPages,后续页按 totalPages 判断
                val shouldFetchTrakt = sessionModeManager.traktConnected.value &&
                    (page == 1 ||
                    (traktHistoryTotalPages > 0 && page <= traktHistoryTotalPages)
                    )
                val traktItems = if (shouldFetchTrakt) {
                    try {
                        loadFromTraktHistoryAll(page, state.filterMediaTypes)
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                } else emptyList()
                // 首次拉取后记录 Trakt 总页数,供后续页判断是否继续拉取
                if (page == 1 && traktHistoryTotalPages == 0) {
                    traktHistoryTotalPages = traktRepository.getWatchHistoryTotalPages()
                }
                // 合并本地流水 + Trakt 已看历史,去重排序(不 take(pageSize),本页全部保留)
                (localItems + traktItems)
                    .distinctBy { itemKey(it) }
                    .sortedByDescending { it.actedAt }
            }
            else -> loadFromDao(page, state)
        }
            val allItems = if (page == 1) newItems else _uiState.value.items + newItems
            // hasMore 按 Tab 分别计算:
            // - ALL: 本地这页满 pageSize 说明本地可能还有更多;Trakt 当前页 < totalPages 说明 Trakt 还有更多
            // - 其他: 本页满 pageSize 说明可能还有更多
            val hasMore = when (state.currentTab) {
                MarkRecordTab.ALL -> {
                    val localHasMore = allTabLocalItemCount >= pageSize
                    val traktHasMore = traktHistoryTotalPages > 0 && page < traktHistoryTotalPages
                    localHasMore || traktHasMore
                }
                else -> newItems.size == pageSize
            }
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
            // U-F46: 映射网络异常为用户友好的本地化提示,不暴露英文异常信息
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = mapToFriendlyError(e)
                )
            }
        }
    }

    /** 将异常映射为用户友好的本地化错误提示。
     *  网络类异常(DNS 解析失败、连接超时等)映射为对应中文提示,其他异常使用通用加载失败。 */
    private fun mapToFriendlyError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("resolve", ignoreCase = true) ||
            msg.contains("address", ignoreCase = true) ||
            msg.contains("unreachable", ignoreCase = true) ||
            msg.contains("unable to connect", ignoreCase = true) -> {
                context.getString(R.string.auth_error_network)
            }
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) -> {
                context.getString(R.string.error_network_timeout)
            }
            else -> context.getString(R.string.error_load_failed)
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

    private suspend fun loadFromTraktHistory(
        page: Int,
        mediaTypesFilter: Set<String> = emptySet(),
        onFirstBatch: suspend (List<MarkRecordItem>) -> Unit
    ): List<MarkRecordItem> {
        var completed: List<MarkRecordItem>? = null
        traktRepository.fetchWatchHistory(page).collect { emit ->
            if (completed != null) return@collect
            if (emit.error != null) throw Exception(emit.error)
            // 应用媒体类型筛选（修复 bug：之前忽略 filterMediaTypes 导致选电视剧不生效）
            val filtered = if (mediaTypesFilter.isNotEmpty()) {
                emit.items.filter { it.mediaType in mediaTypesFilter }
            } else {
                emit.items
            }
            val mapped = filtered.map { it.toMarkRecordItem() }
            if (emit.isComplete) {
                completed = mapped
            } else {
                onFirstBatch(mapped)
            }
        }
        return completed ?: emptyList()
    }

    /**
     * ALL Tab 用的 Trakt 已看历史拉取：不带流式更新，collect 到最终结果返回。
     *
     * 与 [loadFromTraktHistory] 区别：ALL Tab 是合并视图，等本地流水 + Trakt 都拿到再合并更简单，
     * 不需要 onFirstBatch 渐进式更新。
     */
    private suspend fun loadFromTraktHistoryAll(
        page: Int,
        mediaTypesFilter: Set<String>
    ): List<MarkRecordItem> {
        var result: List<MarkRecordItem> = emptyList()
        traktRepository.fetchWatchHistory(page).collect { emit ->
            if (emit.error != null) throw Exception(emit.error)
            val filtered = if (mediaTypesFilter.isNotEmpty()) {
                emit.items.filter { it.mediaType in mediaTypesFilter }
            } else {
                emit.items
            }
            result = filtered.map { it.toMarkRecordItem() }
        }
        return result
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

private fun itemKey(item: MarkRecordItem) = "${item.traktId}_${item.actedAt}_${item.actionType}"

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
