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
import com.tracktosearch.ui.haptic.HapticOutcome
import com.tracktosearch.ui.haptic.HapticOutcomeEmitter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

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
    /**
     * 远端观看历史刷新进行中。
     *
     * 列表已有内容（磁盘快照或本地流水先渲染出来）时，顶栏下方显示细进度条提示数据可能不是最新；
     * 列表还是空的（骨架屏阶段）不显示，由 [isLoading] 负责。翻页有底部指示器，不重复提示。
     */
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val currentStatusMap: Map<Int, CurrentMarkStatus> = emptyMap()
) {
    /**
     * 是否有生效的筛选条件（排序方向不算，它只改顺序不改集合）。
     *
     * 顶栏筛选按钮据此高亮，空列表也据此区分「真的没有记录」和「筛掉了」。
     */
    val hasActiveFilter: Boolean
        get() = filterMediaTypes.isNotEmpty() || filterDatePreset != DatePreset.ALL

    /** 是否有生效的筛选或搜索条件：空列表提示文案用它判断要不要给「清除筛选」出口。 */
    val hasActiveFilterOrSearch: Boolean
        get() = hasActiveFilter || searchQuery.isNotBlank()
}

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

    /**
     * 「Trakt 那半边没拉上」的一次性提示。
     *
     * ALL 页把本地流水与 Trakt 历史并成一个列表，Trakt 整段失败时原来只是当成空列表，
     * [MarkRecordUiState.error] 照样是 null —— 屏幕上是一份看起来完整、实际缺了一半的列表。
     */
    // tryEmit 遇缓冲占用会静默丢条，必须 DROP_OLDEST（与 HapticOutcomeEmitter 同配置）
    private val _toastEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val toastEvent: SharedFlow<Int> = _toastEvent.asSharedFlow()

    /** 结果类触感的出口，界面侧一行 `HapticOutcomeEffect(viewModel.hapticOutcomes)` 收集。 */
    private val hapticOutcomeEmitter = HapticOutcomeEmitter()
    val hapticOutcomes: SharedFlow<HapticOutcome> = hapticOutcomeEmitter.outcomes

    private val pageSize = 50

    /** Trakt 已看历史总页数（首次拉取后从缓存记录，用于 ALL Tab 分页判断）。
     *  refresh 时重置为 0，重新拉取。 */
    private var traktHistoryTotalPages = 0

    /** 正在进行的远端刷新数量：快速切 Tab 或改筛选会并发拉取，用计数避免先结束的那个提前收起进度条。 */
    private var remoteRefreshCount = 0

    /**
     * 当前在飞的加载任务。
     *
     * 切 Tab / 改筛选 / 改搜索词前取消：不取消的话旧条件的结果回来会写进已经换了条件的列表
     * （在 ALL Tab 的 Trakt 历史还没回来时切到「已移除」，历史结果到达后就混进已移除列表）。
     */
    private var loadJob: Job? = null

    /**
     * 加载轮次。
     *
     * 取消只在挂起点生效，渐进渲染的批次回调可能刚好卡在挂起点之间，落一次过期写入。
     * 每次启动加载 +1，写状态前比对轮次，过期的直接丢弃。
     */
    private var loadGeneration = 0

    /** 搜索输入防抖任务：见 [updateSearchQuery]。 */
    private var searchDebounceJob: Job? = null

    /**
     * 各 Tab 的结果缓存 + 当时的筛选条件快照。
     *
     * Tab 来回切换原来每次都清空重新拉（ALL/已看要走 Trakt 历史 + 上百次 TMDB 富化），
     * 网速差时来回都是骨架屏。条件没变就直接还原，再静默刷新一次保证不看到过期数据。
     */
    private val tabResultCaches = mutableMapOf<MarkRecordTab, TabResultCache>()

    private data class TabResultCache(
        val items: List<MarkRecordItem>,
        val currentPage: Int,
        val hasMore: Boolean,
        val snapshot: FilterSnapshot
    )

    /** 除 Tab 以外的全部查询条件：判断缓存是否还对得上当前条件。 */
    private data class FilterSnapshot(
        val searchQuery: String,
        val mediaTypes: Set<String>,
        val datePreset: DatePreset,
        val dateRange: Pair<Long, Long>?,
        val ascending: Boolean
    )

    private fun MarkRecordUiState.toSnapshot() = FilterSnapshot(
        searchQuery = searchQuery,
        mediaTypes = filterMediaTypes,
        datePreset = filterDatePreset,
        dateRange = filterDateRange,
        ascending = sortAscending
    )

    init {
        loadFirstPage()
    }

    fun switchTab(tab: MarkRecordTab) {
        val before = _uiState.value
        if (before.currentTab == tab) return
        UserActionTracker.record("action", "switch_tab", tab.name)
        // 离开的 Tab 先存一份结果，回来时能立刻还原
        if (before.items.isNotEmpty()) {
            tabResultCaches[before.currentTab] = TabResultCache(
                items = before.items,
                currentPage = before.currentPage,
                hasMore = before.hasMore,
                snapshot = before.toSnapshot()
            )
        }
        // 旧 Tab 的请求还在飞就丢掉：它回来会把结果写进新 Tab 的列表
        cancelPendingLoad()
        val cached = tabResultCaches[tab]
        if (cached != null && cached.snapshot == before.toSnapshot()) {
            _uiState.update {
                it.copy(
                    currentTab = tab,
                    items = cached.items,
                    currentPage = cached.currentPage,
                    hasMore = cached.hasMore,
                    isLoading = false, isLoadingMore = false, error = null
                )
            }
            // 已翻过页的不静默刷新：重拉第一页会把后面几页的内容截掉
            if (cached.currentPage <= 1) startLoad(page = 1, silent = true)
            return
        }
        _uiState.update {
            it.copy(
                currentTab = tab, currentPage = 0, hasMore = true,
                items = emptyList(), isLoading = true, error = null
            )
        }
        loadFirstPage()
    }

    /**
     * 搜索词变化。
     *
     * 输入框绑定的是 [MarkRecordUiState.searchQuery]，所以词要立刻写进状态，否则打字会卡住；
     * 但重新查询要防抖：每个字符都触发一次 Room 查询 + Trakt 历史拉取的话，
     * 输入过程中列表一直在清空/重填，整屏在骨架屏和结果之间闪。
     */
    fun updateSearchQuery(query: String) {
        if (_uiState.value.searchQuery == query) return
        if (query.isNotEmpty()) {
            UserActionTracker.record("action", "search", query)
        }
        _uiState.update { it.copy(searchQuery = query) }
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            reloadKeepingItems()
        }
    }

    fun updateFilter(
        mediaTypes: Set<String>,
        datePreset: DatePreset,
        dateRange: Pair<Long, Long>?,
        ascending: Boolean
    ) {
        val state = _uiState.value
        // 弹窗里什么都没改就点确定：不必清空列表重下一遍
        val unchanged = state.filterMediaTypes == mediaTypes &&
            state.filterDatePreset == datePreset &&
            state.filterDateRange == dateRange &&
            state.sortAscending == ascending
        if (unchanged) return
        val filterDetail = buildString {
            if (mediaTypes.isNotEmpty()) append("types=${mediaTypes.joinToString(",")} ")
            append("preset=${datePreset.name} ")
            append("asc=$ascending")
        }
        UserActionTracker.record("action", "update_filter", filterDetail)
        searchDebounceJob?.cancel()
        _uiState.update {
            it.copy(
                filterMediaTypes = mediaTypes,
                filterDatePreset = datePreset,
                filterDateRange = dateRange,
                sortAscending = ascending,
                currentPage = 0, hasMore = true, isLoading = true, error = null
            )
        }
        startLoad(page = 1)
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        UserActionTracker.record("action", "load_next_page", "page=${state.currentPage + 1}")
        _uiState.update { it.copy(isLoadingMore = true) }
        startLoad(page = state.currentPage + 1)
    }

    fun refresh() {
        UserActionTracker.record("action", "refresh", null)
        searchDebounceJob?.cancel()
        _uiState.update {
            it.copy(
                currentPage = 0, hasMore = true, items = emptyList(),
                isLoading = true, error = null
            )
        }
        traktRepository.clearWatchHistoryCache()
        // 清缓存后 totalPages 失效，重置以便重新拉取
        traktHistoryTotalPages = 0
        // 各 Tab 的旧结果同样失效，否则切回去还是过期数据
        tabResultCaches.clear()
        loadFirstPage()
    }

    fun retry() {
        _uiState.update { it.copy(error = null, isLoading = true) }
        loadFirstPage()
    }

    /**
     * 改了筛选/搜索条件后的重载：保留旧列表直到新结果到达。
     *
     * 先清空的话每次改条件整屏都要在骨架屏和结果之间闪一下；旧内容留着 + 顶栏细进度条，
     * 用户能看出在刷新，也不会丢掉视觉参照。
     */
    private fun reloadKeepingItems() {
        _uiState.update { it.copy(currentPage = 0, hasMore = true, isLoading = true, error = null) }
        startLoad(page = 1)
    }

    private fun loadFirstPage() {
        startLoad(page = 1)
    }

    /** 取消在飞的加载与待触发的防抖重载。 */
    private fun cancelPendingLoad() {
        searchDebounceJob?.cancel()
        loadJob?.cancel()
    }

    /**
     * 启动一次加载，接管在飞的请求。
     *
     * @param silent 缓存命中后的静默刷新：不进骨架屏，只在顶栏下方走细进度条
     */
    private fun startLoad(page: Int, silent: Boolean = false) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        if (silent) _uiState.update { it.copy(isRefreshing = true) }
        loadJob = viewModelScope.launch {
            try {
                loadPage(page, generation)
                updateCurrentStatusMap(generation)
            } finally {
                // 轮次判断不能省：被新请求取消时进度条归新请求管
                if (silent && generation == loadGeneration) {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    /** 计算当前状态徽标（从 WatchlistWatchedIds 全局缓存查询） */
    private suspend fun updateCurrentStatusMap(generation: Int) {
        if (!sessionModeManager.traktConnected.value) return
        val items = _uiState.value.items
        if (items.isEmpty()) return
        val ids = traktRepository.getWatchlistWatchedIds() ?: return
        if (generation != loadGeneration) return
        val map = mutableMapOf<Int, CurrentMarkStatus>()
        for (item in items) {
            map[item.traktId] = currentStatusOf(item, ids)
        }
        _uiState.update { state ->
            state.copy(
                currentStatusMap = map,
                // 将当前状态写回列表项，供当前状态徽标与移除分类暗化使用
                items = state.items.map { it.copy(currentStatus = map[it.traktId]) }
            )
        }
    }

    private fun currentStatusOf(
        item: MarkRecordItem,
        ids: TraktRepository.WatchlistWatchedIds
    ): CurrentMarkStatus {
        val type = if (item.mediaType == "movie") MediaType.MOVIE else MediaType.SHOW
        return when {
            ids.isWatched(item.traktId, null, type) -> CurrentMarkStatus.WATCHED
            ids.isInWatchlist(item.traktId, null, type) -> CurrentMarkStatus.IN_WATCHLIST
            else -> CurrentMarkStatus.NONE
        }
    }

    /**
     * 用全局想看/已看 ID 缓存补上「当前标记状态」。
     *
     * [TraktRepository.getWatchlistWatchedIds] 只是读内存字段，渐进渲染每批算一次几乎没有成本；
     * 不这样做的话状态徽标和「已移除」暗化会等到整页加载完才整片弹出。
     */
    private fun withCurrentStatus(items: List<MarkRecordItem>): List<MarkRecordItem> {
        if (!sessionModeManager.traktConnected.value) return items
        val ids = traktRepository.getWatchlistWatchedIds() ?: return items
        return items.map { it.copy(currentStatus = currentStatusOf(it, ids)) }
    }

    private suspend fun loadPage(page: Int, generation: Int) {
        val state = _uiState.value
        // 渐进渲染期间会多次写入 items，必须先固定基准，否则最终合并会把已渲染的批次重复累加
        val baseItems = if (page == 1) emptyList() else state.items
        try {
            // ALL Tab 需要同时拉本地流水和 Trakt 已看历史,记录本地条目数用于 hasMore 判断
            var allTabLocalItemCount = 0
            val newItems: List<MarkRecordItem> = when (state.currentTab) {
            MarkRecordTab.WATCHED -> {
                if (sessionModeManager.traktConnected.value) {
                    val historyItems = loadFromTraktHistory(
                        page = page,
                        mediaTypesFilter = state.filterMediaTypes,
                        onBatch = { batch -> publishProgress(baseItems, batch, generation) }
                    )
                    // 首次拉取后记录总页数，供 hasMore 判断（媒体类型筛选会削掉条目数，不能按条目数判断）
                    if (traktHistoryTotalPages == 0) {
                        traktHistoryTotalPages = traktRepository.getWatchHistoryTotalPages()
                    }
                    historyItems
                } else emptyList()
            }
            MarkRecordTab.ALL -> {
                val localItems = loadFromDao(page, state)
                allTabLocalItemCount = localItems.size
                // 本地流水是 Room 查询，毫秒级可用：先渲染出来，不必等 Trakt 的上百次 TMDB 富化
                if (localItems.isNotEmpty()) publishProgress(baseItems, localItems, generation)
                // Trakt 已看历史分页:首次(page=1)必拉以获取 totalPages,后续页按 totalPages 判断
                val shouldFetchTrakt = sessionModeManager.traktConnected.value &&
                    (page == 1 ||
                    (traktHistoryTotalPages > 0 && page <= traktHistoryTotalPages)
                    )
                val traktItems = if (shouldFetchTrakt) {
                    try {
                        loadFromTraktHistory(
                            page = page,
                            mediaTypesFilter = state.filterMediaTypes,
                            onBatch = { batch -> publishProgress(baseItems, localItems + batch, generation) }
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 只有 Trakt 这一半失败：本地流水已经渲染出来了，不该整页报错，
                        // 但也不能让用户以为这就是全部记录
                        _toastEvent.tryEmit(R.string.mark_records_trakt_history_failed)
                        hapticOutcomeEmitter.emit(HapticOutcome.FAILURE)
                        emptyList()
                    }
                } else emptyList()
                // 首次拉取后记录 Trakt 总页数,供后续页判断是否继续拉取
                if (page == 1 && traktHistoryTotalPages == 0) {
                    traktHistoryTotalPages = traktRepository.getWatchHistoryTotalPages()
                }
                localItems + traktItems
            }
            else -> loadFromDao(page, state)
        }
            val allItems = withCurrentStatus(
                (baseItems + newItems)
                    .distinctBy { itemKey(it) }
                    .sortedByDescending { it.actedAt }
            )
            // hasMore 按 Tab 分别计算:
            // - ALL: 本地这页满 pageSize 说明本地可能还有更多;Trakt 当前页 < totalPages 说明 Trakt 还有更多
            // - WATCHED: 一页是 100 部电影 + 100 集，永远不等于 pageSize，按条目数判断会在第一页就停住
            // - 其他: 本页满 pageSize 说明可能还有更多
            val hasMore = when (state.currentTab) {
                MarkRecordTab.ALL -> {
                    val localHasMore = allTabLocalItemCount >= pageSize
                    val traktHasMore = traktHistoryTotalPages > 0 && page < traktHistoryTotalPages
                    localHasMore || traktHasMore
                }
                MarkRecordTab.WATCHED -> traktHistoryTotalPages > 0 && page < traktHistoryTotalPages
                else -> newItems.size == pageSize
            }
            if (generation != loadGeneration) return
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
            if (generation != loadGeneration) return
            // U-F46: 映射网络异常为用户友好的本地化提示,不暴露英文异常信息
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = mapToFriendlyError(e)
                )
            }
            hapticOutcomeEmitter.emit(HapticOutcome.FAILURE)
        }
    }

    /** 渐进渲染：把当前已就绪的部分结果并入基准列表并立即展示，退出加载态。 */
    private fun publishProgress(
        baseItems: List<MarkRecordItem>,
        partial: List<MarkRecordItem>,
        generation: Int
    ) {
        if (partial.isEmpty()) return
        // 过期轮次的批次直接丢：切了 Tab/改了条件之后它属于上一份条件
        if (generation != loadGeneration) return
        val merged = withCurrentStatus(
            (baseItems + partial)
                .distinctBy { itemKey(it) }
                .sortedByDescending { it.actedAt }
        )
        _uiState.update { it.copy(items = merged, isLoading = false, error = null) }
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

    /**
     * 拉取 Trakt 已看历史，逐批回调已就绪的部分结果。
     *
     * 仓库层的产出顺序是「磁盘旧快照 → 未富化占位全量 → 逐批富化结果（数量递增）→ 富化完成的权威全量」，
     * 中间批次的条目数会少于占位全量，若直接整表替换列表会在「全量占位」和「部分富化」之间来回抖动。
     * 因此这里用 key→最佳版本的映射累积：已富化条目不会被后续未富化占位盖回去。
     *
     * @param onBatch 每收到一批就回调当前累积的最佳结果（不含最终完成批，由返回值统一提交）
     */
    private suspend fun loadFromTraktHistory(
        page: Int,
        mediaTypesFilter: Set<String> = emptySet(),
        onBatch: suspend (List<MarkRecordItem>) -> Unit
    ): List<MarkRecordItem> {
        beginRemoteRefresh()
        try {
            val best = LinkedHashMap<String, MarkRecordItem>()
            val enrichedKeys = mutableSetOf<String>()
            var finalItems: List<MarkRecordItem>? = null
            traktRepository.fetchWatchHistory(page).collect { emit ->
                if (finalItems != null) return@collect
                if (emit.error != null) throw Exception(emit.error)
                // 应用媒体类型筛选（修复 bug：之前忽略 filterMediaTypes 导致选电视剧不生效）
                val filtered = if (mediaTypesFilter.isNotEmpty()) {
                    emit.items.filter { it.mediaType in mediaTypesFilter }
                } else {
                    emit.items
                }
                val mapped = filtered.map { it.toMarkRecordItem() }
                if (emit.isComplete) {
                    // 完成批是权威结果：直接以它为准，丢掉磁盘旧快照里可能已被删除的条目
                    finalItems = mapped
                    return@collect
                }
                mapped.forEach { item ->
                    val key = itemKey(item)
                    if (emit.enriched) {
                        best[key] = item
                        enrichedKeys += key
                    } else if (key !in enrichedKeys) {
                        best[key] = item
                    }
                }
                onBatch(best.values.toList())
            }
            return finalItems ?: best.values.toList()
        } finally {
            endRemoteRefresh()
        }
    }

    /**
     * 远端拉取开始：列表已有内容时顶栏下方显示细进度条。
     *
     * 翻页已有底部加载指示器，这里不重复提示。计数与状态只在主线程读写
     * （viewModelScope 默认 Main.immediate），不需要额外同步。
     */
    private fun beginRemoteRefresh() {
        remoteRefreshCount++
        _uiState.update { it.copy(isRefreshing = !it.isLoadingMore) }
    }

    /** 远端拉取结束：最后一个并发任务结束才收起进度条。 */
    private fun endRemoteRefresh() {
        remoteRefreshCount = (remoteRefreshCount - 1).coerceAtLeast(0)
        if (remoteRefreshCount == 0) {
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun computeTimeRange(state: MarkRecordUiState): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return when (state.filterDatePreset) {
            DatePreset.SEVEN_DAYS -> Pair(now - 7L * 24 * 60 * 60 * 1000, 0)
            DatePreset.THIRTY_DAYS -> Pair(now - 30L * 24 * 60 * 60 * 1000, 0)
            DatePreset.CUSTOM -> {
                val range = state.filterDateRange ?: return Pair(0, 0)
                // 日期选择器给的是所选日期的 UTC 零点，直接拿去比 actedAt（本地时间戳）会错一段：
                // 东八区选 8-27 当结束日，UTC 零点 = 本地 08:00，当天 08:00 之后的记录全被排除。
                // 两端都换算成所选日期在本地时区的起点/终点。
                Pair(startOfLocalDay(range.first), endOfLocalDay(range.second))
            }
            DatePreset.ALL -> Pair(0, 0)
        }
    }

    private companion object {
        /** 搜索防抖：一次输入停顿就够触发查询，再短会把连续输入拆成多次 Room + Trakt 拉取。 */
        const val SEARCH_DEBOUNCE_MILLIS = 300L
    }
}

/** UTC 零点毫秒 → 该日期在本地时区的起始毫秒；0（不限）原样返回。 */
private fun startOfLocalDay(utcMidnightMillis: Long): Long {
    if (utcMidnightMillis <= 0L) return 0L
    return utcDateOf(utcMidnightMillis).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** UTC 零点毫秒 → 该日期在本地时区的最后一毫秒；0（不限）原样返回。 */
private fun endOfLocalDay(utcMidnightMillis: Long): Long {
    if (utcMidnightMillis <= 0L) return 0L
    return utcDateOf(utcMidnightMillis).plusDays(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
}

private fun utcDateOf(utcMidnightMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()

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
