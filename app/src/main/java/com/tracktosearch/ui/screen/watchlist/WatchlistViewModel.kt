package com.tracktosearch.ui.screen.watchlist

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.MarkActionType
import com.tracktosearch.data.local.db.MediaItemEntity
import com.tracktosearch.data.local.db.OfflineCacheManager
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.BatchRemovalItem
import com.tracktosearch.data.repository.BatchRemovalProgress
import com.tracktosearch.data.repository.ConsistencyCheckResult
import com.tracktosearch.data.repository.DoubanBatchRemovalManager
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.data.repository.DoubanSyncStage
import com.tracktosearch.data.repository.DoubanTraktStatusConsistencyChecker
import com.tracktosearch.data.repository.DoubanWatchlistRecord
import com.tracktosearch.data.repository.DoubanWatchlistStatus
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.SyncMode
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.MediaKey
import com.tracktosearch.data.repository.MediaMetadataRepository
import com.tracktosearch.data.repository.MediaSummary
import com.tracktosearch.data.repository.TitleSource
import com.tracktosearch.data.repository.TraktWatchlistRecord
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.WatchlistMediaType
import com.tracktosearch.data.repository.mergeTraktAndDoubanWatchlist
import com.tracktosearch.data.repository.mapDoubanMediaType
import com.tracktosearch.data.repository.normalizeWatchlistImdbId
import com.tracktosearch.data.session.SessionMode
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.util.TtlCache
import com.tracktosearch.di.DispatcherModule
import com.tracktosearch.ui.util.showToast
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named

@Immutable
data class MediaUiItem(
    val traktId: Int,
    val tmdbId: Int,
    val title: String,
    val displayTitle: String,
    val year: Int?,
    val genres: String,
    val posterUrl: String?,
    val imdbId: String = "",
    val traktRating: Double = 0.0,
    val listedAt: String = "",
    // 豆瓣模式本地数据用，支持无 IMDb 条目直接承载
    val doubanId: String? = null,
    val mediaType: WatchlistMediaType = WatchlistMediaType.MOVIE
) {
    /**
     * 多选模式唯一标识。
     *
     * - traktId > 0: 用 `"t$traktId"`（trakt 模式主键）
     * - traktId = 0 且 doubanId 非空: 用 `"d$doubanId"`（豆瓣模式无 imdb 条目主键）
     *   避免所有 traktId=0 的条目共用 key=0 导致选中冲突
     */
    val selectionKey: String
        get() = if (traktId > 0) "t$traktId" else if (doubanId != null) "d$doubanId" else "t$traktId"
}

// 筛选相关枚举（与豆瓣失败页独立定义，Watchlist 模块自包含）
enum class MarkedTimePreset { SEVEN_DAYS, THIRTY_DAYS, ALL }
enum class SortOrder { ASC, DESC }

/**
 * 列表排序比较器：listedAt 降序 + selectionKey 平局键。
 *
 * 批量导入的条目 listedAt 常完全相同（同一秒入库），纯时间排序的平局次序由输入顺序决定，
 * 而缓存帧与网络帧的平局组顺序并不一致（Trakt 对同 listed_at 条目跨请求返回顺序不稳定），
 * 冷启动首帧到网络落地会出现平局组内重排。固定平局键保证任意来源的同集合列表同序。
 */
fun watchlistListedAtComparator(): Comparator<MediaUiItem> =
    compareByDescending<MediaUiItem> { it.listedAt }.thenBy { it.selectionKey }

@Immutable
data class FilterState(
    val selectedGenres: Set<String> = emptySet(),
    val selectedDecadeKeys: Set<Int> = emptySet(),  // 年代起始年份,如 2020 表示 2020s
    val markedTimePreset: MarkedTimePreset = MarkedTimePreset.ALL,
    val markedTimeOrder: SortOrder = SortOrder.DESC,
    val ratingRange: ClosedFloatingPointRange<Float> = 0f..10f
)

@Immutable
data class WatchlistUiState(
    val isLoadingMovies: Boolean = false,
    val isLoadingShows: Boolean = false,
    val isLoadingOthers: Boolean = false,
    val movies: List<MediaUiItem> = emptyList(),
    val shows: List<MediaUiItem> = emptyList(),
    val others: List<MediaUiItem> = emptyList(),
    val movieTotalCount: Int? = null,
    val showTotalCount: Int? = null,
    val otherTotalCount: Int? = null,
    val moviesError: String? = null,
    val showsError: String? = null,
    val othersError: String? = null,
    val moviesLoaded: Boolean = false,
    val showsLoaded: Boolean = false,
    val othersLoaded: Boolean = false,
    val hasMoreMovies: Boolean = true,
    val hasMoreShows: Boolean = true,
    val hasMoreOthers: Boolean = false,
    val moviePage: Int = 1,
    val showPage: Int = 1,
    // 已看历史
    val historyMovies: List<MediaUiItem> = emptyList(),
    val historyShows: List<MediaUiItem> = emptyList(),
    val historyOthers: List<MediaUiItem> = emptyList(),
    val isLoadingHistoryMovies: Boolean = false,
    val isLoadingHistoryShows: Boolean = false,
    val isLoadingHistoryOthers: Boolean = false,
    val historyMoviesError: String? = null,
    val historyShowsError: String? = null,
    val historyOthersError: String? = null,
    val historyMoviesLoaded: Boolean = false,
    val historyShowsLoaded: Boolean = false,
    val historyOthersLoaded: Boolean = false,
    // 豆瓣同步进度（isRunning 时在 Tab 栏下方显示横幅，点击重新打开同步弹窗）
    val doubanSyncProgress: DoubanSyncProgress? = null,
    val doubanSyncBannerVisible: Boolean = false,
    val doubanImported: Boolean = false,
    // 状态一致性检查进度（isRunning 时显示横幅，点击重新打开检查弹窗）
    val consistencyCheckProgress: ConsistencyCheckResult? = null,
    // 豆瓣标记批量移除进度（多选移除后后台同步移除豆瓣标记，isRunning 时显示横幅）
    val batchRemovalProgress: BatchRemovalProgress? = null
) {
    /**
     * 当前可见 tab 是否存在 TMDB 不可用的条目。
     *
     * 只检测 tmdbId > 0 但 posterUrl 仍为 null 的条目（这才是 TMDB 不可用导致的）。
     * tmdbId <= 0 的条目不算（数据本身没有 tmdbId，非 TMDB 不可用）。
     * 实时从当前 tab 的列表计算，避免多 tab 加载互相覆盖。
     */
    fun isTmdbUnavailable(selectedMode: Int, selectedTab: Int): Boolean {
        val items = if (selectedMode == 0) {
            when (selectedTab) {
                0 -> movies
                1 -> shows
                else -> others
            }
        } else {
            when (selectedTab) {
                0 -> historyMovies
                1 -> historyShows
                else -> historyOthers
            }
        }
        return items.any { it.tmdbId > 0 && it.posterUrl == null }
    }
}

/**
 * 豆瓣 status 列表读表结果的保留时长（毫秒）。
 * 只为让同一次加载中并发的三个 Tab 共享一次查询，不用于跨加载复用，所以取很短的值。
 */
private const val DOUBAN_STATUS_ITEMS_TTL_MS = 2_000L

/** 已看历史剩余分页的并发上限：并发拉取但不把网关一次性打满。 */
private const val HISTORY_PAGE_CONCURRENCY = 4

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    private val mediaMetadataRepository: MediaMetadataRepository,
    private val offlineCacheManager: OfflineCacheManager,
    private val doubanSyncManager: DoubanSyncManager,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanSyncMetaStorage: DoubanSyncMetaStorage,
    private val statusConsistencyChecker: DoubanTraktStatusConsistencyChecker,
    private val doubanBatchRemovalManager: DoubanBatchRemovalManager,
    private val sessionModeManager: SessionModeManager,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    @Named(DispatcherModule.COMPUTE_DISPATCHER) private val computeDispatcher: CoroutineDispatcher,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    /** 当前是否处于豆瓣独立模式（UI 用于空状态文案区分等） */
    val isDoubanMode: StateFlow<Boolean> = sessionModeManager.isDoubanMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val traktConnectionState: StateFlow<com.tracktosearch.data.remote.trakt.TraktConnectionState> =
        sessionModeManager.traktConnectionState

    val isTraktConnected: StateFlow<Boolean> = sessionModeManager.traktConnected
    val isDoubanLoggedInFlow: StateFlow<Boolean> = doubanAuthStorage.isLoggedIn

    // 各加载协程的 Job 引用,refresh() 前统一 cancel,避免旧协程写入覆盖新数据
    private var loadMoviesJob: Job? = null
    private var loadShowsJob: Job? = null
    private var loadOthersJob: Job? = null
    private var loadHistoryMoviesJob: Job? = null
    private var loadHistoryShowsJob: Job? = null
    private var loadHistoryOthersJob: Job? = null
    // 最近一次已加载的会话模式，避免页面重新进入组合时重复重置列表触发骨架屏
    private var loadedSessionKey: SessionMode? = null
    private var doubanSyncBannerHideJob: Job? = null
    private val pendingWatchlistMutations = mutableListOf<TraktRepository.WatchlistMutation>()
    /** 本次会话已用离线缓存兜过首帧的列表类型，避免每次刷新都多读一次 Room */
    private val offlineCacheHydrated = mutableSetOf<String>()
    private val loadedTraktMovies = mutableListOf<MediaUiItem>()
    private val loadedTraktShows = mutableListOf<MediaUiItem>()
    private val loadedTraktHistoryMovies = mutableListOf<MediaUiItem>()
    private val loadedTraktHistoryShows = mutableListOf<MediaUiItem>()
    /** 本会话已发起过元数据摘要补全的条目 key，滚动往返不重复请求。 */
    private val prefetchedMetadataKeys = ConcurrentHashMap.newKeySet<String>()

    /** 同步完成事件（UI 监听后自动弹出 DoubanSyncDialog 显示结果） */
    private val _syncCompleteEvent = MutableSharedFlow<Unit>()
    val syncCompleteEvent = _syncCompleteEvent.asSharedFlow()

    /** 状态一致性检查完成事件（UI 监听后自动弹出 ConsistencyCheckDialog 显示结果） */
    private val _consistencyCheckCompleteEvent = MutableSharedFlow<Unit>()
    val consistencyCheckCompleteEvent = _consistencyCheckCompleteEvent.asSharedFlow()

    /**
     * 用户手动关闭一致性检查结果弹窗后调用：
     * 清除横幅与进度，避免结果常驻、也避免下次进入页面时收到旧 isComplete 重复弹窗。
     * （结果弹窗常驻显示，由用户主动关闭而非自动消失）
     */
    fun clearConsistencyCheckResult() {
        _uiState.value = _uiState.value.copy(consistencyCheckProgress = null)
        statusConsistencyChecker.resetProgress()
    }

    /** 用户关闭豆瓣同步结果后清除结果，避免下一次进入页面重复展示旧状态。 */
    fun clearDoubanSyncResult() {
        doubanSyncBannerHideJob?.cancel()
        _uiState.update {
            it.copy(
                doubanSyncProgress = null,
                doubanSyncBannerVisible = false
            )
        }
        doubanSyncManager.resetProgress()
    }

    /** 直接重试结果页中仍可恢复的失败项；返回 false 表示没有可重试项或已有任务运行。 */
    fun retryLatestDoubanFailures(): Boolean {
        val failures = _uiState.value.doubanSyncProgress?.failedItems.orEmpty()
        val recoverableReasons = failures
            .map { it.failureReason }
            .filter { it.recoverable }
            .toSet()
        if (recoverableReasons.isEmpty()) return false
        return doubanSyncManager.startRetry(failures, recoverableReasons)
    }

    /** 是否需要首次同步引导（已登录豆瓣 + 从未同步过） */
    private val _needFirstSyncGuide = MutableStateFlow(false)
    val needFirstSyncGuide: StateFlow<Boolean> = _needFirstSyncGuide.asStateFlow()

    /** 检查是否需要首次同步引导 */
    private fun checkFirstSyncNeeded() {
        viewModelScope.launch {
            // 未登录豆瓣 → 不需要引导
            if (doubanAuthStorage.getCredentials() == null) return@launch
            // 已有同步任务运行时，续传/后台恢复会自行展示进度，不再弹首次导入引导
            if (doubanSyncManager.isRunning()) return@launch
            // 已登录但从未同步过 → 需要引导
            val status = doubanSyncMetaStorage.getCooldownStatus()
            if (status.neverSynced) {
                _needFirstSyncGuide.value = true
            }
        }
    }

    private fun refreshDoubanEmptyState() {
        viewModelScope.launch {
            val imported = !doubanSyncMetaStorage.getCooldownStatus().neverSynced
            _uiState.update {
                it.copy(
                    doubanImported = imported
                )
            }
        }
    }

    /** 用户已处理首次同步引导（点击「开始导入」或「稍后再说」后调用） */
    fun onFirstSyncGuideHandled() {
        _needFirstSyncGuide.value = false
    }

    /** 检查是否已登录豆瓣 */
    fun isDoubanLoggedIn(): Boolean = doubanAuthStorage.getCredentials() != null

    /** 启动豆瓣同步 */
    fun startDoubanSync(mode: SyncMode) {
        // 运行中守卫：模式选择对话框首帧重组前双击会双触发本入口，
        // 运行中直接忽略（DoubanSyncManager.startSync 自身也有防重入，这里提前拦截避免无效请求）
        if (doubanSyncManager.isRunning()) return
        viewModelScope.launch {
            doubanSyncManager.startSync(mode)
        }
    }

    private val maxRetries = 2

    // ========== 筛选状态 ==========
    private val _filterState = MutableStateFlow(FilterState())
    val filterState: StateFlow<FilterState> = _filterState.asStateFlow()

    /** 是否存在生效的筛选条件（markedTimeOrder 不算，只是排序方向） */
    val hasActiveFilters: StateFlow<Boolean> = _filterState.map { state ->
        state.selectedGenres.isNotEmpty() ||
            state.selectedDecadeKeys.isNotEmpty() ||
            state.markedTimePreset != MarkedTimePreset.ALL ||
            state.ratingRange != 0f..10f
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 派生流聚合输入：仅取六个媒体列表字段（进度等高频字段不参与聚合，避免 tick 触发重算） */
    private data class GenreDecadeAggregationInput(
        val movies: List<MediaUiItem>,
        val shows: List<MediaUiItem>,
        val others: List<MediaUiItem>,
        val historyMovies: List<MediaUiItem>,
        val historyShows: List<MediaUiItem>,
        val historyOthers: List<MediaUiItem>
    )

    /** 按列表引用去重：进度 tick 不替换列表引用（O(1) 比较直接跳过），仅列表真实替换才触发下游聚合 */
    private val genreDecadeInput: Flow<GenreDecadeAggregationInput> = _uiState
        .map { state ->
            GenreDecadeAggregationInput(
                state.movies, state.shows, state.others,
                state.historyMovies, state.historyShows, state.historyOthers
            )
        }
        .distinctUntilChanged { old, new ->
            old.movies === new.movies && old.shows === new.shows &&
                old.others === new.others && old.historyMovies === new.historyMovies &&
                old.historyShows === new.historyShows && old.historyOthers === new.historyOthers
        }

    /** 从三类媒体聚合可选类型（按 `,` 和 `·` 拆分、distinct、sorted）；聚合在 Default 线程执行 */
    val availableGenres: StateFlow<List<String>> = genreDecadeInput
        .map { input ->
            (input.movies.asSequence() + input.shows.asSequence() + input.others.asSequence() +
                input.historyMovies.asSequence() + input.historyShows.asSequence() + input.historyOthers.asSequence())
                .flatMap { it.genres.split(",", "·").asSequence() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
                .toList()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 从三类媒体动态生成可选年代列表（按起始年份降序，如 2020、2010、2000...）；聚合在 Default 线程执行 */
    val decadeOptions: StateFlow<List<Int>> = genreDecadeInput
        .map { input ->
            (input.movies.asSequence() + input.shows.asSequence() + input.others.asSequence() +
                input.historyMovies.asSequence() + input.historyShows.asSequence() + input.historyOthers.asSequence())
                .mapNotNull { it.year }
                .filter { it > 0 }
                .map { (it / 10) * 10 }  // 取年代起始年份,如 2023 -> 2020
                .distinct()
                .sortedDescending()
                .toList()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSelectedGenres(genres: Set<String>) {
        _filterState.value = _filterState.value.copy(selectedGenres = genres)
    }
    fun toggleDecade(key: Int) {
        val current = _filterState.value.selectedDecadeKeys
        _filterState.value = _filterState.value.copy(
            selectedDecadeKeys = if (key in current) current - key else current + key
        )
    }
    fun updateMarkedTimePreset(preset: MarkedTimePreset) {
        _filterState.value = _filterState.value.copy(markedTimePreset = preset)
    }
    fun updateMarkedTimeOrder(order: SortOrder) {
        _filterState.value = _filterState.value.copy(markedTimeOrder = order)
    }
    fun updateRatingRange(range: ClosedFloatingPointRange<Float>) {
        _filterState.value = _filterState.value.copy(ratingRange = range)
    }
    fun resetFilters() {
        _filterState.value = FilterState()
    }

    init {
        // 检查首次同步引导
        checkFirstSyncNeeded()
        refreshDoubanEmptyState()
        // 详情页可能在 Watchlist 首次加载完成前就标记，先暂存单条变更，待对应列表加载后应用。
        viewModelScope.launch {
            traktRepository.watchlistMutations.collect { mutation ->
                pendingWatchlistMutations += mutation
                applyPendingWatchlistMutations()
                syncWatchlistCacheWithMutation(mutation)
            }
        }
        // 监听豆瓣同步进度：横幅 5 秒后隐藏，但结果保留到用户关闭对话框。
        viewModelScope.launch {
            doubanSyncManager.progress.collect { progress: DoubanSyncProgress ->
                if (progress.isRunning) {
                    // 续传可能在首次引导已经显示后才启动，清除剩余的引导状态
                    _needFirstSyncGuide.value = false
                    doubanSyncBannerHideJob?.cancel()
                    _uiState.update {
                        it.copy(
                            doubanSyncProgress = progress,
                            doubanSyncBannerVisible = true
                        )
                    }
                } else if (progress.isComplete) {
                    doubanSyncBannerHideJob?.cancel()
                    _uiState.update {
                        it.copy(
                            doubanSyncProgress = progress,
                            doubanSyncBannerVisible = true
                        )
                    }
                    // 同步完成后统一刷新，覆盖仅写入本地最低限度快照但 successCount 为 0 的条目
                    refreshIfLoaded(silent = true, forceRefresh = true)
                    refreshDoubanEmptyState()
                    _syncCompleteEvent.emit(Unit)
                    val completedProgress = progress
                    val keepResultVisible = progress.stage == DoubanSyncStage.CANCELLING ||
                        progress.failedCount > 0 ||
                        progress.pendingItemCount > 0 ||
                        progress.conflictsFound > 0 ||
                        (progress.cloudUploadAttempted && !progress.cloudUploadSucceeded)
                    if (!keepResultVisible) {
                        doubanSyncBannerHideJob = viewModelScope.launch {
                            delay(5000)
                            _uiState.update { state ->
                                if (state.doubanSyncProgress == completedProgress) {
                                    state.copy(doubanSyncBannerVisible = false)
                                } else state
                            }
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            doubanSyncProgress = null,
                            doubanSyncBannerVisible = false
                        )
                    }
                }
            }
        }
        // 监听状态一致性检查进度：isRunning 时显示横幅，完成时自动弹出结果弹窗（常驻，用户手动关）
        // CC-L05: checkCompleteHandled 移到 Checker 内部，避免 ViewModel 重建后丢失导致重复弹窗
        // CC-F01: progress 被重置为默认值时清除横幅，修复设置页关闭弹窗后 Watchlist 页横幅不消失
        viewModelScope.launch {
            statusConsistencyChecker.checkProgress.collect { progress: ConsistencyCheckResult ->
                if (progress.isRunning || progress.isComplete) {
                    _uiState.update { it.copy(consistencyCheckProgress = progress) }
                    if (progress.isComplete) {
                        // 取消时不自动弹窗（用户已在设置页的 ConsistencyCheckDialog 看到取消结果）
                        // 通过 Checker 内的 checkCompleteHandled 标志确保 ViewModel 重建后不重复弹窗：
                        // 首次消费返回 true 触发弹窗,之后返回 false 直到下次检查完成
                        if (!progress.isCancelled && statusConsistencyChecker.consumeCheckCompleteEvent()) {
                            _consistencyCheckCompleteEvent.emit(Unit)
                        }
                        // 不重置 checkProgress、不自动关闭：结果弹窗常驻，用户手动关闭；
                        // 横幅保留完成态，可随时点开回看结果
                    }
                } else {
                    // progress 被重置为默认值（isRunning=false, isComplete=false），
                    // 主动清除横幅避免常驻显示
                    _uiState.update { it.copy(consistencyCheckProgress = null) }
                }
            }
        }
        // 监听豆瓣标记批量移除进度：isRunning 时显示横幅，完成后 5 秒消失
        viewModelScope.launch {
            // 运行轮次观察标记：仅对本收集器见过的运行轮次提示远端失败，
            // 避免 ViewModel 重建后 StateFlow 重放旧完成值导致重复 Toast
            var removalRunObserved = false
            doubanBatchRemovalManager.progress.collect { progress: BatchRemovalProgress ->
                if (progress.isRunning || progress.isComplete) {
                    if (progress.isRunning) {
                        removalRunObserved = true
                    }
                    _uiState.update { it.copy(batchRemovalProgress = progress) }
                    if (progress.isComplete) {
                        // 本地已删除但豆瓣远端移除失败的条目，远端标记仍在，下次同步会重新导入（条目“复活”）；
                        // 失败仅记日志用户无感知，这里明确提示失败条数
                        if (removalRunObserved && progress.failCount > 0) {
                            removalRunObserved = false
                            context.showToast(
                                context.getString(R.string.douban_batch_remove_remote_failed, progress.failCount)
                            )
                        }
                        // 完成后 5 秒横幅消失。记录完成快照，delay 后比对仍一致才清，
                        // 避免 5 秒窗口内新一轮移除完成后误清新一轮结果横幅（同同步横幅守卫模式）
                        val completedProgress = progress
                        delay(5000)
                        _uiState.update { state ->
                            if (state.batchRemovalProgress == completedProgress) {
                                state.copy(batchRemovalProgress = null)
                            } else state
                        }
                        // 仅当横幅确被清除（仍是同一轮）才重置 progress，
                        // 避免把新一轮的进行中/结果状态误 reset
                        if (_uiState.value.batchRemovalProgress == null) {
                            doubanBatchRemovalManager.resetProgress()
                        }
                    }
                }
            }
        }
    }

    /** 批量移除豆瓣标记是否在运行中（UI 用于判断是否允许重复触发） */
    fun isBatchRemovalRunning(): Boolean = doubanBatchRemovalManager.isRunning()

    /** 取消正在进行的批量移除 */
    fun cancelBatchRemoval() {
        doubanBatchRemovalManager.cancel()
    }

    fun loadMovies(
        forceReload: Boolean = false,
        silent: Boolean = false,
        loadAllPages: Boolean = false,
        loadMore: Boolean = false,
        revalidate: Boolean = false
    ) {
        if (loadMore && (!_uiState.value.moviesLoaded || !_uiState.value.hasMoreMovies)) return
        if (!forceReload && !revalidate && !loadMore && _uiState.value.moviesLoaded && _uiState.value.movies.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingMovies) return  // 防止并发重复请求
        loadMoviesJob = viewModelScope.launch {
            val sessionMode = sessionModeManager.sessionMode.first()
            // 访客模式（GUEST）：未连 trakt 未登豆瓣，不展示任何 watchlist 数据，
            // 直接清空并跳过网络与缓存读取，避免读到旧登录态遗留的离线缓存。
            if (sessionMode == SessionMode.GUEST) {
                _uiState.update {
                    it.copy(
                        isLoadingMovies = false,
                        moviesLoaded = false,
                        movies = emptyList(),
                        moviesError = null,
                        movieTotalCount = null,
                        hasMoreMovies = false
                    )
                }
                return@launch
            }
            // 豆瓣独立模式：直接读本地 douban_synced_items 表，跳过 trakt API
            if (sessionMode == SessionMode.DOUBAN) {
                loadMoviesFromDouban(forceReload, silent)
                return@launch
            }
            // 增量刷新命中变化时从第一页重建列表，避免沿用刷新前的页码
            val page = if (revalidate) 1 else _uiState.value.moviePage
            // Watchlist 增量同步：只有首页拉取才查活动时间，未变化时不发列表请求，保留当前列表与离线快照。
            // 活动接口失败会返回「需要刷新」，等同原有逻辑；翻页补页（loadMore / loadAllPages）不参与判断。
            val plan = if (loadMore || page > 1) null
            else traktRepository.resolveWatchlistRefreshPlan(forceReload)
            if (plan != null && !forceReload && !plan.shouldRefreshMovies) {
                hydrateFromOfflineCache(OfflineCacheManager.TYPE_WATCHLIST_MOVIE)
                // 本地完全没有数据时不用旧基线跳过：离线快照可能已被清空，回退到列表请求更安全
                if (_uiState.value.moviesLoaded || _uiState.value.movies.isNotEmpty()) {
                    // 快照只存首页：用持久化的服务端总数恢复计数，并把分页起点挪到第 2 页，
                    // 否则跳过列表请求后电影会永远停在快照里的 200 条。
                    val savedTotal = traktRepository.savedWatchlistTotal(MediaType.MOVIE)
                    _uiState.update { state ->
                        val hasMore = savedTotal != null && savedTotal > state.movies.size
                        state.copy(
                            isLoadingMovies = false,
                            moviesLoaded = true,
                            moviesError = null,
                            movieTotalCount = savedTotal ?: state.movieTotalCount,
                            hasMoreMovies = hasMore,
                            moviePage = if (hasMore) 2 else state.moviePage
                        )
                    }
                    return@launch
                }
            }
            if (sessionMode == SessionMode.TRAKT && isDoubanLoggedIn()) {
                loadMoviesWithDouban(forceReload, silent, loadAllPages, page, plan)
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    isLoadingMovies = if (loadMore || !silent) true else state.isLoadingMovies,
                    moviesError = if (silent) state.moviesError else null
                )
            }
            if (!loadMore) hydrateFromOfflineCache(OfflineCacheManager.TYPE_WATCHLIST_MOVIE)
            val result = retryIO(maxRetries) { traktRepository.getMovieWatchlist(page = page, limit = 200, forceRefresh = forceReload) }
            result.onSuccess { (rawItems, totalPages) ->
                // 首页拉取成功即认为与服务端活动时间对齐；失败不写基线，下次仍会重试
                if (page == 1 && plan != null) traktRepository.markMovieWatchlistSynced(plan)
                val totalCount = traktRepository.getMovieWatchlistTotalCount(page, 200) ?: rawItems.size
                // 过滤掉本地已标记已看但不在想看缓存中的电影（处理标记已看后 Trakt API 最终一致性延迟）
                val locallyWatchedIds = traktRepository.getLocallyWatchedOnlyTraktIds(MediaType.MOVIE)
                val items = if (locallyWatchedIds.isNotEmpty()) rawItems.filter { it.movie.ids.trakt !in locallyWatchedIds } else rawItems
                // 如果 forceReload 且数据与现有列表完全相同，跳过 TMDB 富化和 UI 更新
                if (forceReload && page == 1) {
                    val newSignature = items.map { it.movie.ids.trakt to it.listed_at }
                    val oldSignature = _uiState.value.movies.map { it.traktId to it.listedAt }
                    if (newSignature == oldSignature) {
                        _uiState.update { state ->
                            state.copy(
                                movieTotalCount = totalCount,
                                isLoadingMovies = false,
                                hasMoreMovies = state.moviePage < totalPages,
                                moviePage = page + 1
                            )
                        }
                        if (loadAllPages && page < totalPages) {
                            loadMovies(forceReload = true, silent = true, loadAllPages = true)
                        }
                        return@launch
                    }
                }
                // 先发布完整占位列表，富化结果全部完成后再一次性替换，避免每个 item 触发一次状态复制。
                // 已有非空数据时不再清空列表（等价静默刷新语义）；占位仅在列表为空（首次加载）时发布，
                // 避免加载过程连续发布 emptyList / placeholderList / 最终列表多个引用触发整表重算与 Coil 重解码
                val placeholderList = List(items.size) { index ->
                    val p = items[index].movie
                    createPlaceholder(p.ids.trakt, p.ids.tmdb, p.title, p.year, p.ids.imdb, p.rating, items[index].listed_at)
                }
                if (!silent && _uiState.value.movies.isEmpty()) {
                    _uiState.update { it.copy(movies = placeholderList) }
                }
                // Watchlist 直接使用 Trakt full,images，列表阶段不再逐条请求 TMDB。
                val uiItems = items.map { item ->
                    mapWatchlistMovie(item, _uiState.value.movies.firstOrNull { cached ->
                        cached.traktId == item.movie.ids.trakt
                    })
                }
                // 写入离线缓存（仅首页）：在 moviePage 递增前判断，确保首页加载必缓存
                if (page == 1) {
                    val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_WATCHLIST_MOVIE) }
                    offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_WATCHLIST_MOVIE, entities)
                }
                val mergedMovies = if (page == 1) {
                    uiItems
                } else {
                    (_uiState.value.movies + uiItems).distinctBy { it.selectionKey }
                }.sortedWith(watchlistListedAtComparator())
                _uiState.update { state ->
                    state.copy(
                        movies = mergedMovies,
                        movieTotalCount = totalCount,
                        isLoadingMovies = false,
                        moviesLoaded = true,
                        hasMoreMovies = page < totalPages,
                        moviesError = null,
                        moviePage = page + 1
                    )
                }
                applyPendingWatchlistMutations(MediaType.MOVIE)
                if (loadAllPages && page < totalPages) {
                    loadMovies(forceReload = true, silent = true, loadAllPages = true)
                }
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_WATCHLIST_MOVIE)
                _uiState.update { state ->
                    state.copy(
                        isLoadingMovies = false,
                        moviesLoaded = true,
                        movies = if (loadMore) state.movies
                        else if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() }
                        else state.movies,
                        movieTotalCount = state.movieTotalCount ?: cached.size.takeIf { it > 0 },
                        moviesError = if (loadMore || cached.isEmpty()) e.toUserMessage(context, R.string.error_load_failed) else null
                    )
                }
            }
        }
    }

    fun loadShows(
        forceReload: Boolean = false,
        silent: Boolean = false,
        loadAllPages: Boolean = false,
        loadMore: Boolean = false,
        revalidate: Boolean = false
    ) {
        if (loadMore && (!_uiState.value.showsLoaded || !_uiState.value.hasMoreShows)) return
        if (!forceReload && !revalidate && !loadMore && _uiState.value.showsLoaded && _uiState.value.shows.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingShows) return  // 防止并发重复请求
        loadShowsJob = viewModelScope.launch {
            val sessionMode = sessionModeManager.sessionMode.first()
            // 访客模式（GUEST）：清空跳过，不展示旧缓存，参考 loadMovies。
            if (sessionMode == SessionMode.GUEST) {
                _uiState.update {
                    it.copy(
                        isLoadingShows = false,
                        showsLoaded = false,
                        shows = emptyList(),
                        showsError = null,
                        showTotalCount = null,
                        hasMoreShows = false
                    )
                }
                return@launch
            }
            // 豆瓣独立模式：直接读本地 douban_synced_items 表，跳过 trakt API
            if (sessionMode == SessionMode.DOUBAN) {
                loadShowsFromDouban(forceReload, silent)
                return@launch
            }
            // 增量刷新命中变化时从第一页重建列表，避免沿用刷新前的页码
            val page = if (revalidate) 1 else _uiState.value.showPage
            // Watchlist 增量同步：只有首页拉取才查活动时间，未变化时不发列表请求，保留当前列表与离线快照。
            val plan = if (loadMore || page > 1) null
            else traktRepository.resolveWatchlistRefreshPlan(forceReload)
            if (plan != null && !forceReload && !plan.shouldRefreshShows) {
                hydrateFromOfflineCache(OfflineCacheManager.TYPE_WATCHLIST_SHOW)
                // 本地完全没有数据时不用旧基线跳过：离线快照可能已被清空，回退到列表请求更安全
                if (_uiState.value.showsLoaded || _uiState.value.shows.isNotEmpty()) {
                    // 同电影：快照只有首页，用持久化总数恢复计数与分页起点
                    val savedTotal = traktRepository.savedWatchlistTotal(MediaType.SHOW)
                    _uiState.update { state ->
                        val hasMore = savedTotal != null && savedTotal > state.shows.size
                        state.copy(
                            isLoadingShows = false,
                            showsLoaded = true,
                            showsError = null,
                            showTotalCount = savedTotal ?: state.showTotalCount,
                            hasMoreShows = hasMore,
                            showPage = if (hasMore) 2 else state.showPage
                        )
                    }
                    return@launch
                }
            }
            if (sessionMode == SessionMode.TRAKT && isDoubanLoggedIn()) {
                loadShowsWithDouban(forceReload, silent, loadAllPages, page, plan)
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    isLoadingShows = if (loadMore || !silent) true else state.isLoadingShows,
                    showsError = if (silent) state.showsError else null
                )
            }
            if (!loadMore) hydrateFromOfflineCache(OfflineCacheManager.TYPE_WATCHLIST_SHOW)
            val result = retryIO(maxRetries) { traktRepository.getShowWatchlist(page = page, limit = 200, forceRefresh = forceReload) }
            result.onSuccess { (rawItems, totalPages) ->
                // 首页拉取成功即认为与服务端活动时间对齐；失败不写基线，下次仍会重试
                if (page == 1 && plan != null) traktRepository.markShowWatchlistSynced(plan)
                val totalCount = traktRepository.getShowWatchlistTotalCount(page, 200) ?: rawItems.size
                // 过滤掉本地已标记已看但不在想看缓存中的剧集（处理标记已看后 Trakt API 最终一致性延迟）
                val locallyWatchedIds = traktRepository.getLocallyWatchedOnlyTraktIds(MediaType.SHOW)
                val items = if (locallyWatchedIds.isNotEmpty()) rawItems.filter { it.show.ids.trakt !in locallyWatchedIds } else rawItems
                // 如果 forceReload 且数据与现有列表完全相同，跳过 TMDB 富化和 UI 更新
                if (forceReload && page == 1) {
                    val newSignature = items.map { it.show.ids.trakt to it.listed_at }
                    val oldSignature = _uiState.value.shows.map { it.traktId to it.listedAt }
                    if (newSignature == oldSignature) {
                        _uiState.update { state ->
                            state.copy(
                                showTotalCount = totalCount,
                                isLoadingShows = false,
                                hasMoreShows = state.showPage < totalPages,
                                showPage = page + 1
                            )
                        }
                        if (loadAllPages && page < totalPages) {
                            loadShows(forceReload = true, silent = true, loadAllPages = true)
                        }
                        return@launch
                    }
                }
                // 预填充占位列表到完整大小，避免多个 async 协程并发 add/resize 导致 IndexOutOfBounds
                // 已有非空数据时不再清空列表（等价静默刷新语义）；占位仅在列表为空（首次加载）时发布，
                // 避免加载过程连续发布 emptyList / placeholderList / 最终列表多个引用触发整表重算与 Coil 重解码
                val placeholderList = List(items.size) { index ->
                    val s = items[index].show
                    createPlaceholder(
                        s.ids.trakt, s.ids.tmdb, s.title, s.year, s.ids.imdb, s.rating,
                        items[index].listed_at, WatchlistMediaType.SHOW
                    )
                }
                if (!silent && _uiState.value.shows.isEmpty()) {
                    _uiState.update { it.copy(shows = placeholderList) }
                }
                // Watchlist 直接使用 Trakt full,images，列表阶段不再逐条请求 TMDB。
                val uiItems = items.map { item ->
                    mapWatchlistShow(item, _uiState.value.shows.firstOrNull { cached ->
                        cached.traktId == item.show.ids.trakt
                    })
                }
                // 写入离线缓存（仅首页）：在 showPage 递增前判断，确保首页加载必缓存
                if (page == 1) {
                    val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_WATCHLIST_SHOW) }
                    offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_WATCHLIST_SHOW, entities)
                }
                val mergedShows = if (page == 1) {
                    uiItems
                } else {
                    (_uiState.value.shows + uiItems).distinctBy { it.selectionKey }
                }.sortedWith(watchlistListedAtComparator())
                _uiState.update { state ->
                    state.copy(
                        shows = mergedShows,
                        showTotalCount = totalCount,
                        isLoadingShows = false,
                        showsLoaded = true,
                        hasMoreShows = page < totalPages,
                        showsError = null,
                        showPage = page + 1
                    )
                }
                applyPendingWatchlistMutations(MediaType.SHOW)
                if (loadAllPages && page < totalPages) {
                    loadShows(forceReload = true, silent = true, loadAllPages = true)
                }
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_WATCHLIST_SHOW)
                _uiState.update { state ->
                    state.copy(
                        isLoadingShows = false,
                        showsLoaded = true,
                        shows = if (loadMore) state.shows
                        else if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() }
                        else state.shows,
                        showTotalCount = state.showTotalCount ?: cached.size.takeIf { it > 0 },
                        showsError = if (loadMore || cached.isEmpty()) e.toUserMessage(context, R.string.error_load_failed) else null
                    )
                }
            }
        }
    }

    fun loadOthers(forceReload: Boolean = false, silent: Boolean = false) {
        if (!forceReload && _uiState.value.othersLoaded && _uiState.value.others.isNotEmpty()) return
        if (_uiState.value.isLoadingOthers) return
        loadOthersJob = viewModelScope.launch {
            when (sessionModeManager.sessionMode.first()) {
                SessionMode.DOUBAN -> {
                    loadOthersFromDouban(forceReload, silent)
                    return@launch
                }
                SessionMode.TRAKT -> if (isDoubanLoggedIn()) {
                    loadOthersWithDouban(forceReload)
                    return@launch
                }
                else -> Unit
            }
            _uiState.update { state ->
                state.copy(
                    othersError = if (silent) state.othersError else null,
                    others = emptyList(),
                    othersLoaded = true,
                    otherTotalCount = 0,
                    hasMoreOthers = false,
                    isLoadingOthers = false
                )
            }
        }
    }

    /**
     * 拉取全部历史分页。
     *
     * 第一页拿到 totalPages 后，剩余页按 [HISTORY_PAGE_CONCURRENCY] 分批并发拉取：
     * 原实现逐页串行，几千条历史要串十几个网络往返才出内容。
     */
    private suspend fun <T> fetchAllHistoryPages(
        fetchPage: suspend (page: Int) -> Result<Pair<List<T>, Int>>
    ): Result<List<T>> {
        val firstPage = retryIO(maxRetries) { fetchPage(1) }
        val (firstItems, totalPages) = firstPage.getOrElse { error -> return Result.failure(error) }
        if (totalPages <= 1) return Result.success(firstItems)

        val allItems = ArrayList<T>(firstItems.size * totalPages)
        allItems += firstItems
        for (chunk in (2..totalPages).chunked(HISTORY_PAGE_CONCURRENCY)) {
            val results = coroutineScope {
                chunk.map { page -> async { retryIO(maxRetries) { fetchPage(page) } } }.awaitAll()
            }
            results.forEach { result ->
                allItems += result.getOrElse { error -> return Result.failure(error) }.first
            }
        }
        return Result.success(allItems)
    }

    fun loadHistoryMovies(forceReload: Boolean = false) {
        if (!forceReload && _uiState.value.historyMoviesLoaded && _uiState.value.historyMovies.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingHistoryMovies) return
        loadHistoryMoviesJob = viewModelScope.launch {
            // 访客模式（GUEST）：清空跳过，不展示旧缓存历史，参考 loadMovies。
            if (sessionModeManager.sessionMode.first() == SessionMode.GUEST) {
                _uiState.update {
                    it.copy(
                        isLoadingHistoryMovies = false,
                        historyMoviesLoaded = false,
                        historyMovies = emptyList(),
                        historyMoviesError = null
                    )
                }
                return@launch
            }
            // 豆瓣独立模式：直接读本地 douban_synced_items 表，跳过 trakt API
            if (sessionModeManager.sessionMode.first() == SessionMode.DOUBAN) {
                loadHistoryMoviesFromDouban(forceReload)
                return@launch
            }
            if (sessionModeManager.sessionMode.first() == SessionMode.TRAKT && isDoubanLoggedIn()) {
                loadHistoryMoviesWithDouban(forceReload)
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    isLoadingHistoryMovies = true,
                    historyMoviesError = null
                )
            }
            hydrateFromOfflineCache(OfflineCacheManager.TYPE_HISTORY_MOVIE)
            val result = fetchAllHistoryPages<TraktWatchlistMovieItem> { page ->
                traktRepository.getMovieHistory(page = page, limit = 200)
            }
            result.onSuccess { items ->
                if (forceReload) {
                    _uiState.update { it.copy(historyMovies = emptyList()) }
                }
                // 历史记录可能包含同一部电影的多次观看，按 traktId 去重
                val dedupedItems = items.distinctBy { it.movie.ids.trakt }
                // 过滤掉本地已取消已看的电影（处理取消已看后 Trakt API 最终一致性延迟）
                val locallyRemovedIds = traktRepository.getLocallyWatchlistOnlyTraktIds(MediaType.MOVIE)
                val filteredItems = if (locallyRemovedIds.isNotEmpty()) dedupedItems.filter { it.movie.ids.trakt !in locallyRemovedIds } else dedupedItems
                val summaries = fetchSummaryMap(
                    filteredItems.mapNotNull { item ->
                        item.movie.ids.tmdb.takeIf { it > 0 }?.let { MediaKey("movie", it, tmdbRepository.currentLanguageTag()) }
                    }
                )
                val uiItems = filteredItems.map { item ->
                    applySummaryToMovie(
                        item,
                        summaries[MediaKey("movie", item.movie.ids.tmdb, tmdbRepository.currentLanguageTag())]
                    )
                }
                _uiState.update { state ->
                    state.copy(
                        historyMovies = uiItems,
                        isLoadingHistoryMovies = false,
                        historyMoviesLoaded = true
                    )
                }
                // 写入离线缓存
                val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_HISTORY_MOVIE) }
                offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_HISTORY_MOVIE, entities)
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_HISTORY_MOVIE)
                _uiState.update { state ->
                    state.copy(
                        isLoadingHistoryMovies = false,
                        historyMoviesLoaded = true,
                        historyMovies = if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() } else state.historyMovies,
                        historyMoviesError = if (cached.isNotEmpty()) null else e.toUserMessage(context, R.string.error_load_failed)
                    )
                }
            }
        }
    }

    fun loadHistoryShows(forceReload: Boolean = false) {
        if (!forceReload && _uiState.value.historyShowsLoaded && _uiState.value.historyShows.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingHistoryShows) return
        loadHistoryShowsJob = viewModelScope.launch {
            // 访客模式（GUEST）：清空跳过，不展示旧缓存历史，参考 loadMovies.
            if (sessionModeManager.sessionMode.first() == SessionMode.GUEST) {
                _uiState.update {
                    it.copy(
                        isLoadingHistoryShows = false,
                        historyShowsLoaded = false,
                        historyShows = emptyList(),
                        historyShowsError = null
                    )
                }
                return@launch
            }
            // 豆瓣独立模式：直接读本地 douban_synced_items 表，跳过 trakt API
            if (sessionModeManager.sessionMode.first() == SessionMode.DOUBAN) {
                loadHistoryShowsFromDouban(forceReload)
                return@launch
            }
            if (sessionModeManager.sessionMode.first() == SessionMode.TRAKT && isDoubanLoggedIn()) {
                loadHistoryShowsWithDouban(forceReload)
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    isLoadingHistoryShows = true,
                    historyShowsError = null
                )
            }
            hydrateFromOfflineCache(OfflineCacheManager.TYPE_HISTORY_SHOW)
            val result = fetchAllHistoryPages<TraktWatchlistShowItem> { page ->
                traktRepository.getShowHistory(page = page, limit = 200)
            }
            result.onSuccess { items ->
                if (forceReload) {
                    _uiState.update { it.copy(historyShows = emptyList()) }
                }
                val dedupedItems = items.distinctBy { it.show.ids.trakt }
                // 过滤掉本地已取消已看的剧集（处理取消已看后 Trakt API 最终一致性延迟）
                val locallyRemovedIds = traktRepository.getLocallyWatchlistOnlyTraktIds(MediaType.SHOW)
                val filteredItems = if (locallyRemovedIds.isNotEmpty()) dedupedItems.filter { it.show.ids.trakt !in locallyRemovedIds } else dedupedItems
                val summaries = fetchSummaryMap(
                    filteredItems.mapNotNull { item ->
                        item.show.ids.tmdb.takeIf { it > 0 }?.let { MediaKey("tv", it, tmdbRepository.currentLanguageTag()) }
                    }
                )
                val uiItems = filteredItems.map { item ->
                    applySummaryToShow(
                        item,
                        summaries[MediaKey("tv", item.show.ids.tmdb, tmdbRepository.currentLanguageTag())]
                    )
                }
                _uiState.update { state ->
                    state.copy(
                        historyShows = uiItems,
                        isLoadingHistoryShows = false,
                        historyShowsLoaded = true
                    )
                }
                // 写入离线缓存
                val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_HISTORY_SHOW) }
                offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_HISTORY_SHOW, entities)
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_HISTORY_SHOW)
                _uiState.update { state ->
                    state.copy(
                        isLoadingHistoryShows = false,
                        historyShowsLoaded = true,
                        historyShows = if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() } else state.historyShows,
                        historyShowsError = if (cached.isNotEmpty()) null else e.toUserMessage(context, R.string.error_load_failed)
                    )
                }
            }
        }
    }

    fun loadHistoryOthers(forceReload: Boolean = false) {
        if (!forceReload && _uiState.value.historyOthersLoaded && _uiState.value.historyOthers.isNotEmpty()) return
        if (_uiState.value.isLoadingHistoryOthers) return
        loadHistoryOthersJob = viewModelScope.launch {
            when (sessionModeManager.sessionMode.first()) {
                SessionMode.DOUBAN -> {
                    loadHistoryOthersFromDouban(forceReload)
                    return@launch
                }
                SessionMode.TRAKT -> if (isDoubanLoggedIn()) {
                    loadHistoryOthersWithDouban(forceReload)
                    return@launch
                }
                else -> Unit
            }
            _uiState.update { state ->
                state.copy(
                    historyOthersError = null,
                    historyOthers = emptyList(),
                    historyOthersLoaded = true,
                    isLoadingHistoryOthers = false
                )
            }
        }
    }

    private suspend fun loadMoviesWithDouban(
        forceReload: Boolean,
        silent: Boolean,
        loadAllPages: Boolean,
        page: Int,
        plan: TraktRepository.WatchlistRefreshPlan?
    ) {
        _uiState.update { state ->
            state.copy(
                isLoadingMovies = if (page > 1 || !silent) true else state.isLoadingMovies,
                moviesError = if (page > 1 || !silent) null else state.moviesError
            )
        }
        if (page == 1) hydrateFromOfflineCache(OfflineCacheManager.TYPE_WATCHLIST_MOVIE)
        if (forceReload && page == 1) loadedTraktMovies.clear()
        val result = retryIO(maxRetries) {
            traktRepository.getMovieWatchlist(page = page, limit = 200, forceRefresh = forceReload)
        }
        result.onSuccess { (rawItems, totalPages) ->
            // 首页拉取成功即认为与服务端活动时间对齐；失败不写基线，下次仍会重试
            if (page == 1 && plan != null) traktRepository.markMovieWatchlistSynced(plan)
            val locallyWatchedIds = traktRepository.getLocallyWatchedOnlyTraktIds(MediaType.MOVIE)
            val items = if (locallyWatchedIds.isNotEmpty()) {
                rawItems.filter { it.movie.ids.trakt !in locallyWatchedIds }
            } else {
                rawItems
            }
            // Watchlist 直接使用 Trakt full,images，列表阶段不再逐条请求 TMDB。
            val uiItems = items.map { item ->
                mapWatchlistMovie(item, _uiState.value.movies.firstOrNull { cached ->
                    cached.traktId == item.movie.ids.trakt
                })
            }
            replaceLoadedTraktItems(loadedTraktMovies, uiItems)
            val doubanItems = getDoubanItemsForType("wish", WatchlistMediaType.MOVIE, forceReload)
            val merged = mergeWatchlistItems(loadedTraktMovies, doubanItems, WatchlistMediaType.MOVIE)
            val totalCount = traktRepository.getMovieWatchlistTotalCount(page, 200) ?: rawItems.size
            _uiState.update { state ->
                state.copy(
                    movies = merged,
                    movieTotalCount = unionCount(totalCount, loadedTraktMovies, doubanItems),
                    isLoadingMovies = false,
                    moviesLoaded = true,
                    hasMoreMovies = page < totalPages,
                    moviePage = page + 1,
                    moviesError = null
                )
            }
            // 想看列表首页成功后写离线缓存：豆瓣合并路径原先不落盘，导致冷启动只能白屏等网络
            if (page == 1) {
                offlineCacheManager.saveMediaItems(
                    OfflineCacheManager.TYPE_WATCHLIST_MOVIE,
                    uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_WATCHLIST_MOVIE) }
                )
            }
            if (loadAllPages && page < totalPages) {
                loadMovies(forceReload = true, silent = true, loadAllPages = true)
            }
        }.onFailure { error ->
            val doubanItems = getDoubanItemsForType("wish", WatchlistMediaType.MOVIE, forceReload)
            val merged = mergeWatchlistItems(loadedTraktMovies, doubanItems, WatchlistMediaType.MOVIE)
            _uiState.update { state ->
                state.copy(
                    movies = if (merged.isNotEmpty()) merged else state.movies,
                    movieTotalCount = merged.size.takeIf { it > 0 } ?: state.movieTotalCount,
                    isLoadingMovies = false,
                    moviesLoaded = true,
                    moviesError = if (page > 1 || merged.isEmpty()) error.toUserMessage(context, R.string.error_load_failed) else null
                )
            }
        }
    }

    private suspend fun loadShowsWithDouban(
        forceReload: Boolean,
        silent: Boolean,
        loadAllPages: Boolean,
        page: Int,
        plan: TraktRepository.WatchlistRefreshPlan?
    ) {
        _uiState.update { state ->
            state.copy(
                isLoadingShows = if (page > 1 || !silent) true else state.isLoadingShows,
                showsError = if (page > 1 || !silent) null else state.showsError
            )
        }
        if (page == 1) hydrateFromOfflineCache(OfflineCacheManager.TYPE_WATCHLIST_SHOW)
        if (forceReload && page == 1) loadedTraktShows.clear()
        val result = retryIO(maxRetries) {
            traktRepository.getShowWatchlist(page = page, limit = 200, forceRefresh = forceReload)
        }
        result.onSuccess { (rawItems, totalPages) ->
            // 首页拉取成功即认为与服务端活动时间对齐；失败不写基线，下次仍会重试
            if (page == 1 && plan != null) traktRepository.markShowWatchlistSynced(plan)
            val locallyWatchedIds = traktRepository.getLocallyWatchedOnlyTraktIds(MediaType.SHOW)
            val items = if (locallyWatchedIds.isNotEmpty()) {
                rawItems.filter { it.show.ids.trakt !in locallyWatchedIds }
            } else {
                rawItems
            }
            // Watchlist 直接使用 Trakt full,images，列表阶段不再逐条请求 TMDB。
            val uiItems = items.map { item ->
                mapWatchlistShow(item, _uiState.value.shows.firstOrNull { cached ->
                    cached.traktId == item.show.ids.trakt
                })
            }
            replaceLoadedTraktItems(loadedTraktShows, uiItems)
            val doubanItems = getDoubanItemsForType("wish", WatchlistMediaType.SHOW, forceReload)
            val merged = mergeWatchlistItems(loadedTraktShows, doubanItems, WatchlistMediaType.SHOW)
            val totalCount = traktRepository.getShowWatchlistTotalCount(page, 200) ?: rawItems.size
            _uiState.update { state ->
                state.copy(
                    shows = merged,
                    showTotalCount = unionCount(totalCount, loadedTraktShows, doubanItems),
                    isLoadingShows = false,
                    showsLoaded = true,
                    hasMoreShows = page < totalPages,
                    showPage = page + 1,
                    showsError = null
                )
            }
            // 想看列表首页成功后写离线缓存：豆瓣合并路径原先不落盘，导致冷启动只能白屏等网络
            if (page == 1) {
                offlineCacheManager.saveMediaItems(
                    OfflineCacheManager.TYPE_WATCHLIST_SHOW,
                    uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_WATCHLIST_SHOW) }
                )
            }
            if (loadAllPages && page < totalPages) {
                loadShows(forceReload = true, silent = true, loadAllPages = true)
            }
        }.onFailure { error ->
            val doubanItems = getDoubanItemsForType("wish", WatchlistMediaType.SHOW, forceReload)
            val merged = mergeWatchlistItems(loadedTraktShows, doubanItems, WatchlistMediaType.SHOW)
            _uiState.update { state ->
                state.copy(
                    shows = if (merged.isNotEmpty()) merged else state.shows,
                    showTotalCount = merged.size.takeIf { it > 0 } ?: state.showTotalCount,
                    isLoadingShows = false,
                    showsLoaded = true,
                    showsError = if (page > 1 || merged.isEmpty()) error.toUserMessage(context, R.string.error_load_failed) else null
                )
            }
        }
    }

    private suspend fun loadHistoryMoviesWithDouban(forceReload: Boolean) {
        _uiState.update { it.copy(isLoadingHistoryMovies = true, historyMoviesError = null) }
        hydrateFromOfflineCache(OfflineCacheManager.TYPE_HISTORY_MOVIE)
        if (forceReload) loadedTraktHistoryMovies.clear()
        val result = fetchAllHistoryPages<TraktWatchlistMovieItem> { page ->
            traktRepository.getMovieHistory(page = page, limit = 200)
        }
        result.onSuccess { rawItems ->
            val dedupedItems = rawItems.distinctBy { it.movie.ids.trakt }
            val locallyRemovedIds = traktRepository.getLocallyWatchlistOnlyTraktIds(MediaType.MOVIE)
            val items = if (locallyRemovedIds.isNotEmpty()) {
                dedupedItems.filter { it.movie.ids.trakt !in locallyRemovedIds }
            } else {
                dedupedItems
            }
            // 历史列表只走一次批量摘要；失败回退 Trakt 原文，不逐条回源 TMDB。
            val summaries = fetchSummaryMap(
                items.mapNotNull { item ->
                    item.movie.ids.tmdb.takeIf { it > 0 }?.let { MediaKey("movie", it, tmdbRepository.currentLanguageTag()) }
                }
            )
            val uiItems = items.map { item ->
                applySummaryToMovie(
                    item,
                    summaries[MediaKey("movie", item.movie.ids.tmdb, tmdbRepository.currentLanguageTag())]
                ).copy(mediaType = WatchlistMediaType.MOVIE)
            }
            replaceLoadedTraktItems(loadedTraktHistoryMovies, uiItems)
            val doubanItems = getDoubanWatchlistItems("collect", forceReload)
            val merged = mergeWatchlistItems(loadedTraktHistoryMovies, doubanItems, WatchlistMediaType.MOVIE)
            _uiState.update { state ->
                state.copy(
                    historyMovies = merged,
                    isLoadingHistoryMovies = false,
                    historyMoviesLoaded = true,
                    historyMoviesError = null
                )
            }
            // 已看列表成功后写离线缓存，供下次冷启动先出内容
            offlineCacheManager.saveMediaItems(
                OfflineCacheManager.TYPE_HISTORY_MOVIE,
                uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_HISTORY_MOVIE) }
            )
        }.onFailure { error ->
            val doubanItems = getDoubanWatchlistItems("collect", forceReload)
            val merged = mergeWatchlistItems(loadedTraktHistoryMovies, doubanItems, WatchlistMediaType.MOVIE)
            _uiState.update { state ->
                state.copy(
                    historyMovies = if (merged.isNotEmpty()) merged else state.historyMovies,
                    isLoadingHistoryMovies = false,
                    historyMoviesLoaded = true,
                    historyMoviesError = if (merged.isNotEmpty()) null else error.toUserMessage(context, R.string.error_load_failed)
                )
            }
        }
    }

    private suspend fun loadHistoryShowsWithDouban(forceReload: Boolean) {
        _uiState.update { it.copy(isLoadingHistoryShows = true, historyShowsError = null) }
        hydrateFromOfflineCache(OfflineCacheManager.TYPE_HISTORY_SHOW)
        if (forceReload) loadedTraktHistoryShows.clear()
        val result = fetchAllHistoryPages<TraktWatchlistShowItem> { page ->
            traktRepository.getShowHistory(page = page, limit = 200)
        }
        result.onSuccess { rawItems ->
            val dedupedItems = rawItems.distinctBy { it.show.ids.trakt }
            val locallyRemovedIds = traktRepository.getLocallyWatchlistOnlyTraktIds(MediaType.SHOW)
            val items = if (locallyRemovedIds.isNotEmpty()) {
                dedupedItems.filter { it.show.ids.trakt !in locallyRemovedIds }
            } else {
                dedupedItems
            }
            // 历史列表只走一次批量摘要；失败回退 Trakt 原文，不逐条回源 TMDB。
            val summaries = fetchSummaryMap(
                items.mapNotNull { item ->
                    item.show.ids.tmdb.takeIf { it > 0 }?.let { MediaKey("tv", it, tmdbRepository.currentLanguageTag()) }
                }
            )
            val uiItems = items.map { item ->
                applySummaryToShow(
                    item,
                    summaries[MediaKey("tv", item.show.ids.tmdb, tmdbRepository.currentLanguageTag())]
                ).copy(mediaType = WatchlistMediaType.SHOW)
            }
            replaceLoadedTraktItems(loadedTraktHistoryShows, uiItems)
            val doubanItems = getDoubanWatchlistItems("collect", forceReload)
            val merged = mergeWatchlistItems(loadedTraktHistoryShows, doubanItems, WatchlistMediaType.SHOW)
            _uiState.update { state ->
                state.copy(
                    historyShows = merged,
                    isLoadingHistoryShows = false,
                    historyShowsLoaded = true,
                    historyShowsError = null
                )
            }
            // 已看列表成功后写离线缓存，供下次冷启动先出内容
            offlineCacheManager.saveMediaItems(
                OfflineCacheManager.TYPE_HISTORY_SHOW,
                uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_HISTORY_SHOW) }
            )
        }.onFailure { error ->
            val doubanItems = getDoubanWatchlistItems("collect", forceReload)
            val merged = mergeWatchlistItems(loadedTraktHistoryShows, doubanItems, WatchlistMediaType.SHOW)
            _uiState.update { state ->
                state.copy(
                    historyShows = if (merged.isNotEmpty()) merged else state.historyShows,
                    isLoadingHistoryShows = false,
                    historyShowsLoaded = true,
                    historyShowsError = if (merged.isNotEmpty()) null else error.toUserMessage(context, R.string.error_load_failed)
                )
            }
        }
    }

    private suspend fun loadOthersWithDouban(forceReload: Boolean) {
        _uiState.update { it.copy(isLoadingOthers = true, othersError = null) }
        val doubanItems = getDoubanWatchlistItems("wish", forceReload)
        val merged = mergeWatchlistItems(emptyList(), doubanItems, WatchlistMediaType.OTHER)
        _uiState.update { state ->
            state.copy(
                others = merged,
                otherTotalCount = merged.size,
                isLoadingOthers = false,
                othersLoaded = true,
                hasMoreOthers = false,
                othersError = null
            )
        }
    }

    private suspend fun loadHistoryOthersWithDouban(forceReload: Boolean) {
        _uiState.update { it.copy(isLoadingHistoryOthers = true, historyOthersError = null) }
        val doubanItems = getDoubanWatchlistItems("collect", forceReload)
        val merged = mergeWatchlistItems(emptyList(), doubanItems, WatchlistMediaType.OTHER)
        _uiState.update { state ->
            state.copy(
                historyOthers = merged,
                isLoadingHistoryOthers = false,
                historyOthersLoaded = true,
                historyOthersError = null
            )
        }
    }

    // ========== 豆瓣独立模式：watchlist 数据源走本地 douban_synced_items 表 ==========

    /** 豆瓣模式：想看电影列表（status="wish"） */
    private suspend fun loadMoviesFromDouban(forceReload: Boolean, silent: Boolean) {
        _uiState.update { state ->
            state.copy(
                isLoadingMovies = !silent,
                moviesError = if (silent) state.moviesError else null
            )
        }
        // 豆瓣模式数据全量本地,不分页;forceReload 时重新读表(数据可能在后台同步更新)
        val items = getDoubanItemsForType("wish", WatchlistMediaType.MOVIE, forceReload)
        val uiItems = items.map { it.toMediaUiItem() }
        _uiState.update { state ->
            state.copy(
                movies = uiItems,
                movieTotalCount = uiItems.size,
                isLoadingMovies = if (silent) state.isLoadingMovies else false,
                moviesLoaded = true,
                hasMoreMovies = false,  // 豆瓣模式本地全量,不分页
                moviesError = null
            )
        }
    }

    /** 豆瓣模式：想看剧集列表（status="wish"） */
    private suspend fun loadShowsFromDouban(forceReload: Boolean, silent: Boolean) {
        _uiState.update { state ->
            state.copy(
                isLoadingShows = !silent,
                showsError = if (silent) state.showsError else null
            )
        }
        val items = getDoubanItemsForType("wish", WatchlistMediaType.SHOW, forceReload)
        val uiItems = items.map { it.toMediaUiItem() }
        _uiState.update { state ->
            state.copy(
                shows = uiItems,
                showTotalCount = uiItems.size,
                isLoadingShows = if (silent) state.isLoadingShows else false,
                showsLoaded = true,
                hasMoreShows = false,
                showsError = null
            )
        }
    }

    /** 豆瓣模式：想看其他类型列表（status="wish"） */
    private suspend fun loadOthersFromDouban(forceReload: Boolean, silent: Boolean) {
        _uiState.update { state ->
            state.copy(
                isLoadingOthers = !silent,
                othersError = if (silent) state.othersError else null
            )
        }
        val items = getDoubanItemsForType("wish", WatchlistMediaType.OTHER, forceReload)
        val uiItems = items.map { it.toMediaUiItem() }
        _uiState.update { state ->
            state.copy(
                others = uiItems,
                otherTotalCount = uiItems.size,
                isLoadingOthers = false,
                othersLoaded = true,
                hasMoreOthers = false,
                othersError = null
            )
        }
    }

    /** 豆瓣模式：已看电影列表（status="collect"） */
    private suspend fun loadHistoryMoviesFromDouban(forceReload: Boolean) {
        _uiState.update { state ->
            state.copy(
                isLoadingHistoryMovies = true,
                historyMoviesError = null
            )
        }
        val items = getDoubanItemsForType("collect", WatchlistMediaType.MOVIE, forceReload)
        val uiItems = items.map { it.toMediaUiItem() }
        _uiState.update { state ->
            state.copy(
                historyMovies = uiItems,
                isLoadingHistoryMovies = false,
                historyMoviesLoaded = true,
                historyMoviesError = null
            )
        }
    }

    /** 豆瓣模式：已看剧集列表（status="collect"） */
    private suspend fun loadHistoryShowsFromDouban(forceReload: Boolean) {
        _uiState.update { state ->
            state.copy(
                isLoadingHistoryShows = true,
                historyShowsError = null
            )
        }
        val items = getDoubanItemsForType("collect", WatchlistMediaType.SHOW, forceReload)
        val uiItems = items.map { it.toMediaUiItem() }
        _uiState.update { state ->
            state.copy(
                historyShows = uiItems,
                isLoadingHistoryShows = false,
                historyShowsLoaded = true,
                historyShowsError = null
            )
        }
    }

    /** 豆瓣模式：已看其他类型列表（status="collect"） */
    private suspend fun loadHistoryOthersFromDouban(forceReload: Boolean) {
        _uiState.update { state ->
            state.copy(
                isLoadingHistoryOthers = true,
                historyOthersError = null
            )
        }
        val items = getDoubanItemsForType("collect", WatchlistMediaType.OTHER, forceReload)
        val uiItems = items.map { it.toMediaUiItem() }
        _uiState.update { state ->
            state.copy(
                historyOthers = uiItems,
                isLoadingHistoryOthers = false,
                historyOthersLoaded = true,
                historyOthersError = null
            )
        }
    }

    /** DoubanSyncedItem → MediaUiItem 映射（豆瓣模式本地数据渲染用） */
    private fun DoubanSyncedItem.toMediaUiItem() = MediaUiItem(
        traktId = traktId ?: 0,
        tmdbId = tmdbId ?: 0,
        title = title,
        displayTitle = displayTitle ?: title,
        year = year,
        genres = genres ?: "",
        posterUrl = posterUrl,
        imdbId = imdbId ?: "",
        traktRating = 0.0,
        listedAt = listedAt ?: "",
        doubanId = doubanId,
        mediaType = mapDoubanMediaType(mediaType)
    )

    /**
     * 读取指定 status 的豆瓣本地条目。
     *
     * 同一次加载里电影/剧集/其他三个 Tab 都要整份 status 列表做并集匹配（跨类型错标也要能配上），
     * 原实现让三个并发协程各查一次全表。这里用极短 TTL 的 TtlCache 主要取其飞行中去重能力：
     * 并发的三次读共享一次查询；forceRefresh 时跳过缓存值但仍共享同一次查询，
     * 保证标记/同步后立刻读到最新数据。
     */
    private suspend fun getDoubanWatchlistItems(
        status: String,
        forceRefresh: Boolean = false
    ): List<DoubanSyncedItem> =
        doubanStatusItemsCache.getOrAwait(status, skipCache = forceRefresh) {
            doubanSyncedItemDao.getByStatus(status)
        }

    private val doubanStatusItemsCache = TtlCache<List<DoubanSyncedItem>>(
        ttlMillis = DOUBAN_STATUS_ITEMS_TTL_MS,
        maxSize = 4
    )

    private suspend fun getDoubanItemsForType(
        status: String,
        mediaType: WatchlistMediaType,
        forceRefresh: Boolean = false
    ): List<DoubanSyncedItem> = getDoubanWatchlistItems(status, forceRefresh)
        .filter { mapDoubanMediaType(it.mediaType) == mediaType }

    private fun replaceLoadedTraktItems(target: MutableList<MediaUiItem>, items: List<MediaUiItem>) {
        val byTraktId = target.associateBy { it.traktId }.toMutableMap()
        items.forEach { item -> byTraktId[item.traktId] = item }
        target.clear()
        target += byTraktId.values
    }

    /** 合并 Trakt 与豆瓣条目为 UI 列表（纯计算，跑在计算调度器上，避免主线程整表映射+排序） */
    private suspend fun mergeWatchlistItems(
        traktItems: List<MediaUiItem>,
        doubanItems: List<DoubanSyncedItem>,
        mediaType: WatchlistMediaType
    ): List<MediaUiItem> = withContext(computeDispatcher) {
        val mergedItems = mergeTraktAndDoubanWatchlist(
            traktEntries = traktItems.map { it.toTraktWatchlistRecord() },
            doubanEntries = doubanItems.map { it.toDoubanWatchlistRecord() }
        )
        val traktById = traktItems.associateBy { it.traktId }
        val doubanById = doubanItems.associateBy { it.doubanId }
        mergedItems
            .filter { it.mediaType == mediaType }
            .map { merged ->
                val traktItem = merged.traktId?.let(traktById::get)
                val doubanItem = merged.doubanId?.let(doubanById::get)
                MediaUiItem(
                    traktId = merged.traktId ?: 0,
                    tmdbId = traktItem?.tmdbId?.takeIf { it > 0 } ?: doubanItem?.tmdbId ?: 0,
                    title = traktItem?.title?.takeIf { it.isNotBlank() } ?: merged.title,
                    displayTitle = traktItem?.displayTitle?.takeIf { it.isNotBlank() }
                        ?: merged.displayTitle,
                    year = traktItem?.year ?: merged.year,
                    genres = traktItem?.genres?.takeIf { it.isNotBlank() }
                        ?: merged.genres.joinToString(", "),
                    posterUrl = traktItem?.posterUrl ?: merged.posterUrl,
                    imdbId = merged.imdbId.orEmpty(),
                    traktRating = traktItem?.traktRating ?: 0.0,
                    listedAt = merged.listedAt.orEmpty(),
                    doubanId = merged.doubanId,
                    mediaType = merged.mediaType
                )
            }
    }

    private fun MediaUiItem.toTraktWatchlistRecord() = TraktWatchlistRecord(
        traktId = traktId,
        title = title,
        mediaType = when (mediaType) {
            WatchlistMediaType.SHOW -> "show"
            else -> "movie"
        },
        imdbId = imdbId,
        year = year,
        genres = genres.split(",", "·").map { it.trim() }.filter { it.isNotEmpty() },
        posterUrl = posterUrl,
        listedAt = listedAt,
        inWatchlist = true,
        watched = false
    )

    private fun DoubanSyncedItem.toDoubanWatchlistRecord() = DoubanWatchlistRecord(
        doubanId = doubanId,
        title = title,
        displayTitle = displayTitle,
        mediaType = mediaType,
        status = if (status.equals("collect", ignoreCase = true)) {
            DoubanWatchlistStatus.COLLECT
        } else {
            DoubanWatchlistStatus.WISH
        },
        traktId = traktId?.takeIf { it > 0 },
        imdbId = imdbId,
        year = year,
        genres = genres.orEmpty().split(",", "·").map { it.trim() }.filter { it.isNotEmpty() },
        posterUrl = posterUrl,
        listedAt = listedAt
    )

    private suspend fun unionCount(
        traktTotalCount: Int,
        loadedTraktItems: List<MediaUiItem>,
        doubanItems: List<DoubanSyncedItem>
    ): Int = withContext(computeDispatcher) {
        val loadedTraktImdbIds = loadedTraktItems.mapNotNull { normalizeWatchlistImdbId(it.imdbId) }.toSet()
        val loadedTraktIds = loadedTraktItems.mapNotNull { it.traktId.takeIf { id -> id > 0 } }.toSet()
        val doubanOnlyCount = doubanItems.count {
            val imdbId = normalizeWatchlistImdbId(it.imdbId)
            val traktId = it.traktId?.takeIf { id -> id > 0 }
            val matchesLoadedTraktId = traktId != null && traktId in loadedTraktIds
            val matchesLoadedImdbId = imdbId != null && imdbId in loadedTraktImdbIds
            !matchesLoadedTraktId && !matchesLoadedImdbId
        }
        (traktTotalCount + doubanOnlyCount).coerceAtLeast(loadedTraktItems.size + doubanOnlyCount)
    }

    private suspend fun enrichMediaItem(
        traktId: Int, tmdbId: Int, title: String, year: Int?,
        imdbId: String, rating: Double, listedAt: String, isMovie: Boolean
    ): MediaUiItem {
        if (tmdbId <= 0) {
            return MediaUiItem(
                traktId = traktId, tmdbId = 0, title = title,
                displayTitle = title, year = year, genres = "",
                posterUrl = null, imdbId = imdbId,
                traktRating = rating, listedAt = listedAt
            )
        }
        if (isMovie) {
            val enrichment = tmdbRepository.enrichMovie(tmdbId, title, year)
            return MediaUiItem(
                traktId = traktId, tmdbId = tmdbId, title = title,
                displayTitle = enrichment.chineseTitle, year = enrichment.year,
                genres = enrichment.genres, posterUrl = enrichment.posterUrl,
                imdbId = imdbId, traktRating = rating, listedAt = listedAt
            )
        } else {
            val enrichment = tmdbRepository.enrichTv(tmdbId, title, year)
            return MediaUiItem(
                traktId = traktId, tmdbId = tmdbId, title = title,
                displayTitle = enrichment.chineseTitle, year = enrichment.year,
                genres = enrichment.genres, posterUrl = enrichment.posterUrl,
                imdbId = imdbId, traktRating = rating, listedAt = listedAt
            )
        }
    }

    /**
     * Trakt 图片字段返回的是不带协议的路径（如 media.trakt.tv/images/...），
     * 直接交给 Coil 会按相对地址处理而加载失败，这里统一补全 HTTPS 前缀。
     */
    private fun toFullTraktImageUrl(raw: String): String =
        if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"

    private fun mapWatchlistMovie(
        item: TraktWatchlistMovieItem,
        cached: MediaUiItem?
    ): MediaUiItem {
        val movie = item.movie
        val tmdbId = movie.ids.tmdb
        val title = movie.title.trim().ifEmpty { cached?.title.orEmpty() }
        // 标题统一由共享摘要校正；但 Trakt 响应晚于首帧落地时，离线快照里已校正过的
        // displayTitle 必须沿用，不能打回 Trakt 原文——可见条目的摘要预取每会话只跑一次，
        // 打回英文后不会再有第二批补正，英文标题会一直停到下次冷启。
        // 快照 displayTitle 的唯一写入方是共享摘要（旧逐条富化链路已下线），不会带回旧别名。
        val displayTitle = cached?.displayTitle?.takeIf { it.isNotBlank() } ?: title
        val genres = movie.genres.joinToString(" · ").ifEmpty { cached?.genres.orEmpty() }
        val posterUrl = movie.images.poster.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::toFullTraktImageUrl)
            ?: movie.posterPath?.takeIf { it.isNotBlank() }
            ?: cached?.posterUrl
        return MediaUiItem(
            traktId = movie.ids.trakt,
            tmdbId = tmdbId,
            title = title,
            displayTitle = displayTitle,
            year = movie.year.takeIf { it > 0 } ?: cached?.year,
            genres = genres,
            posterUrl = posterUrl,
            imdbId = movie.ids.imdb,
            traktRating = movie.rating,
            listedAt = item.listed_at,
            mediaType = WatchlistMediaType.MOVIE
        )
    }

    /** 用共享摘要补列表标题/海报/类型；Trakt 已有非空值优先，摘要只填空。 */
    private fun applySummaryToMovie(
        item: TraktWatchlistMovieItem,
        summary: MediaSummary?
    ): MediaUiItem {
        val base = mapWatchlistMovie(item, null)
        if (summary == null) return base
        val resolvedTitle = resolveSummaryDisplayTitle(summary, base.title) ?: base.displayTitle
        return base.copy(
            displayTitle = resolvedTitle,
            year = base.year ?: summary.year,
            genres = base.genres.ifBlank { summary.genres.joinToString(" · ") },
            posterUrl = base.posterUrl
                ?: summary.posterPath?.let { com.tracktosearch.data.remote.tmdb.TmdbImageUrls.W342 + it }
        )
    }

    private fun mapWatchlistShow(
        item: TraktWatchlistShowItem,
        cached: MediaUiItem?
    ): MediaUiItem {
        val show = item.show
        val tmdbId = show.ids.tmdb
        val title = show.title.trim().ifEmpty { cached?.title.orEmpty() }
        // 同 mapWatchlistMovie：Trakt 响应落地时沿用快照里已校正的 displayTitle
        val displayTitle = cached?.displayTitle?.takeIf { it.isNotBlank() } ?: title
        val genres = show.genres.joinToString(" · ").ifEmpty { cached?.genres.orEmpty() }
        val posterUrl = show.images.poster.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::toFullTraktImageUrl)
            ?: show.posterPath?.takeIf { it.isNotBlank() }
            ?: cached?.posterUrl
        return MediaUiItem(
            traktId = show.ids.trakt,
            tmdbId = tmdbId,
            title = title,
            displayTitle = displayTitle,
            year = show.year.takeIf { it > 0 } ?: cached?.year,
            genres = genres,
            posterUrl = posterUrl,
            imdbId = show.ids.imdb,
            traktRating = show.rating,
            listedAt = item.listed_at,
            mediaType = WatchlistMediaType.SHOW
        )
    }

    /** 用共享摘要补列表标题/海报/类型；Trakt 已有非空值优先，摘要只填空。 */
    private fun applySummaryToShow(
        item: TraktWatchlistShowItem,
        summary: MediaSummary?
    ): MediaUiItem {
        val base = mapWatchlistShow(item, null)
        if (summary == null) return base
        val resolvedTitle = resolveSummaryDisplayTitle(summary, base.title) ?: base.displayTitle
        return base.copy(
            displayTitle = resolvedTitle,
            year = base.year ?: summary.year,
            genres = base.genres.ifBlank { summary.genres.joinToString(" · ") },
            posterUrl = base.posterUrl
                ?: summary.posterPath?.let { com.tracktosearch.data.remote.tmdb.TmdbImageUrls.W342 + it }
        )
    }

    private fun createPlaceholder(
        traktId: Int, tmdbId: Int, title: String, year: Int?,
        imdbId: String, rating: Double, listedAt: String,
        mediaType: WatchlistMediaType = WatchlistMediaType.MOVIE
    ): MediaUiItem {
        return MediaUiItem(
            traktId = traktId, tmdbId = tmdbId, title = title,
            displayTitle = title, year = year, genres = "",
            posterUrl = null, imdbId = imdbId,
            traktRating = rating, listedAt = listedAt, mediaType = mediaType
        )
    }

    /**
     * 首帧先用 Room 离线缓存出内容。
     *
     * Trakt 想看/已看的列表数据只有内存缓存，冷启动必须等「Trakt 列表请求 + 全部 TMDB 富化」
     * 完成才有内容，期间一直是骨架屏；豆瓣合并路径连占位列表都不发。
     * 这里在发请求前把上次成功写入的快照（含海报/中文名/类型，足够完整渲染）先发布出去，
     * 网络结果回来后整体覆盖。不置 loaded 标记，因此不会跳过后续网络请求。
     */
    private suspend fun hydrateFromOfflineCache(type: String) {
        if (offlineCacheHydrated.contains(type)) return
        offlineCacheHydrated += type
        val state = _uiState.value
        val alreadyHasData = when (type) {
            OfflineCacheManager.TYPE_WATCHLIST_MOVIE -> state.movies.isNotEmpty()
            OfflineCacheManager.TYPE_WATCHLIST_SHOW -> state.shows.isNotEmpty()
            OfflineCacheManager.TYPE_HISTORY_MOVIE -> state.historyMovies.isNotEmpty()
            OfflineCacheManager.TYPE_HISTORY_SHOW -> state.historyShows.isNotEmpty()
            else -> true
        }
        if (alreadyHasData) return
        val cached = offlineCacheManager.getMediaItems(type)
        if (cached.isEmpty()) {
            return
        }
        val items = withContext(computeDispatcher) {
            cached.map { it.toMediaUiItem() }.sortedWith(watchlistListedAtComparator())
        }
        _uiState.update { current ->
            when (type) {
                // 再次判空：网络结果可能在读盘期间先落地，此时不覆盖更新的数据
                OfflineCacheManager.TYPE_WATCHLIST_MOVIE -> if (current.movies.isEmpty()) {
                    current.copy(movies = items, movieTotalCount = current.movieTotalCount ?: items.size)
                } else current
                OfflineCacheManager.TYPE_WATCHLIST_SHOW -> if (current.shows.isEmpty()) {
                    current.copy(shows = items, showTotalCount = current.showTotalCount ?: items.size)
                } else current
                OfflineCacheManager.TYPE_HISTORY_MOVIE -> if (current.historyMovies.isEmpty()) {
                    current.copy(historyMovies = items)
                } else current
                OfflineCacheManager.TYPE_HISTORY_SHOW -> if (current.historyShows.isEmpty()) {
                    current.copy(historyShows = items)
                } else current
                else -> current
            }
        }
    }

    fun refresh() {
        reloadForSession(forceReload = true)
    }

    /**
     * 下拉刷新：已有数据时保留列表与计数，只重置分页后强制重拉。
     * 不走 refresh()，因为那条路会把 uiState 整体重置，导致下拉一松手内容清空、
     * 计数跳成 0 并闪一屏骨架屏。首次（尚无数据）才退回全量加载。
     */
    fun pullToRefresh() {
        val state = _uiState.value
        val anyLoaded = state.moviesLoaded || state.showsLoaded || state.othersLoaded ||
            state.historyMoviesLoaded || state.historyShowsLoaded || state.historyOthersLoaded
        if (anyLoaded) {
            // 用户明确触发的下拉刷新保留强制拉取语义，不做活动时间判断
            refreshIfLoaded(silent = false, forceRefresh = true)
        } else {
            refresh()
        }
    }

    /** 会话模式发生变化时重新读取当前账号的数据；同一会话重复调用不重置列表，避免返回页面时骨架屏闪烁。 */
    fun onSessionModeChanged(sessionKey: SessionMode) {
        if (loadedSessionKey == sessionKey) return
        loadedSessionKey = sessionKey
        reloadForSession(forceReload = false)
    }

    private fun reloadForSession(forceReload: Boolean) {
        // 取消所有正在进行的加载协程,避免旧协程完成后覆盖刚重置的新数据
        loadMoviesJob?.cancel()
        loadShowsJob?.cancel()
        loadOthersJob?.cancel()
        loadHistoryMoviesJob?.cancel()
        loadHistoryShowsJob?.cancel()
        loadHistoryOthersJob?.cancel()

        val wasHistoryLoaded = _uiState.value.historyMoviesLoaded ||
            _uiState.value.historyShowsLoaded || _uiState.value.historyOthersLoaded
        loadedTraktMovies.clear()
        loadedTraktShows.clear()
        loadedTraktHistoryMovies.clear()
        loadedTraktHistoryShows.clear()
        // 列表被重置回空，允许再次用离线缓存兜首帧
        offlineCacheHydrated.clear()
        _uiState.value = WatchlistUiState()
        refreshDoubanEmptyState()
        loadMovies(forceReload = forceReload)
        loadShows(forceReload = forceReload)
        loadOthers(forceReload = forceReload)
        if (wasHistoryLoaded) {
            loadHistoryMovies(forceReload = forceReload)
            loadHistoryShows(forceReload = forceReload)
            loadHistoryOthers(forceReload = forceReload)
        }
    }

    /**
     * 页面恢复可见 / 下拉刷新。
     *
     * 默认走增量同步：先查一次 Trakt 活动时间，只拉取发生变化的那一类列表；
     * 未变化时不动列表、不重置分页，也不触发无意义重组。
     * [forceRefresh] = true（下拉刷新）时保留强制拉取语义。
     */
    fun refreshIfLoaded(silent: Boolean = false, forceRefresh: Boolean = false) {
        val state = _uiState.value
        if (state.moviesLoaded || state.showsLoaded || state.othersLoaded) {
            if (forceRefresh) {
                // 只重置分页，保留已有数据避免 UI 闪烁和重复拉取
                _uiState.update {
                    it.copy(
                        moviePage = 1,
                        showPage = 1,
                        hasMoreMovies = true,
                        hasMoreShows = true
                    )
                }
            }
            loadMovies(forceReload = forceRefresh, silent = silent, revalidate = !forceRefresh)
            loadShows(forceReload = forceRefresh, silent = silent, revalidate = !forceRefresh)
            loadOthers(forceReload = true, silent = silent)
        }
        if (state.historyMoviesLoaded || state.historyShowsLoaded || state.historyOthersLoaded) {
            loadHistoryMovies(forceReload = true)
            loadHistoryShows(forceReload = true)
            loadHistoryOthers(forceReload = true)
        }
    }

    /** 仅刷新想看列表（从详情页标记想看后调用） */
    fun refreshWatchlist() {
        val state = _uiState.value
        if (state.moviesLoaded || state.showsLoaded || state.othersLoaded) {
            _uiState.update {
                it.copy(
                    moviePage = 1,
                    showPage = 1,
                    hasMoreMovies = true,
                    hasMoreShows = true
                )
            }
            loadMovies(forceReload = true, silent = true, loadAllPages = true)
            loadShows(forceReload = true, silent = true, loadAllPages = true)
            loadOthers(forceReload = true, silent = true)
        }
    }

    /**
     * 通知当前实际可见的 Watchlist 条目，做限量中文标题预取。
     *
     * 列表先用 Trakt 直出的原始标题秒出，这里只对真正出现在屏幕上的条目补本地化：
     * - 已有本地化标题缓存：零请求直接补显示（例如详情页刚补过中文名）；
     * - 未命中缓存：合并成一次 `/api/media/summaries` 批量请求，每批最多 20 条；
     * - 同一 (类型, tmdbId, 语言) 在本会话只请求一次，绝不恢复逐条 TMDB 请求；
     * - 标题只补 Trakt 原始标题，海报/类型/年份只填空，不覆盖已显示值；
     * - 已有非空豆瓣展示标题的条目不覆盖。
     *
     * 失败时保留 Trakt 原标题，不影响列表显示。
     */
    fun onVisibleWatchlistItemsChanged(items: List<MediaUiItem>) {
        if (items.isEmpty()) return
        val language = tmdbRepository.currentLanguageTag()
        val candidates = items.mapNotNull { item ->
            val mediaType = when (item.mediaType) {
                WatchlistMediaType.MOVIE -> "movie"
                WatchlistMediaType.SHOW -> "tv"
                WatchlistMediaType.OTHER -> return@mapNotNull null
            }
            if (item.traktId <= 0 || item.tmdbId <= 0) return@mapNotNull null
            // 纯豆瓣承载条目(无 TMDB 映射)没有共享摘要可校正，保留本地标题；
            // 其余条目的 displayTitle 只作为摘要注意力缺失时的兜底。
            // 旧链路缓存的别名标题(如「急速天劫」)必须允许被共享摘要的
            // TMDB 详情标题覆盖，否则列表与详情会永久显示两个不同片名。
            if (item.traktId <= 0 && item.doubanId != null) {
                return@mapNotNull null
            }
            val key = MediaKey(mediaType, item.tmdbId, language)
            if (!prefetchedMetadataKeys.add(key.cacheKey())) return@mapNotNull null
            key to item
        }
        if (candidates.isEmpty()) return
        viewModelScope.launch {
            val summaries = try {
                mediaMetadataRepository.getSummaries(candidates.map { it.first })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val returnedKeys = summaries.mapTo(mutableSetOf()) { summary ->
                MediaKey(summary.mediaType, summary.tmdbId, summary.locale).cacheKey()
            }
            // 失败或缺失的条目不能被记成“已预取”，下一次可见时仍需重试。
            candidates.forEach { (key, _) ->
                if (key.cacheKey() !in returnedKeys) {
                    prefetchedMetadataKeys.remove(key.cacheKey())
                }
            }
            summaries.forEach { summary ->
                val item = candidates.firstOrNull {
                    it.first.mediaType == summary.mediaType &&
                        it.first.tmdbId == summary.tmdbId &&
                        it.first.locale == summary.locale
                }?.second ?: return@forEach
                applyVisibleMetadata(item, summary)
            }
        }
    }

    /**
     * 批量取共享摘要。
     *
     * 历史/豆瓣合并路径只允许一次批量请求，失败时返回空映射，由调用方回退 Trakt 原文，
     * 不在这里按条目回退 TMDB，避免重新引入 N+1。
     */
    private suspend fun fetchSummaryMap(keys: List<MediaKey>): Map<MediaKey, MediaSummary> {
        if (keys.isEmpty()) return emptyMap()
        return try {
            mediaMetadataRepository.getSummaries(keys)
                .associateBy { summary ->
                    MediaKey(summary.mediaType, summary.tmdbId, summary.locale)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 共享摘要标题的最终采纳，返回 null 表示维持调用方已显示的标题。
     *
     * - 有译名（DETAIL/ALTERNATIVE）直接用译名；但 TMDB 部分国产条目的 zh-CN 标题字段
     *   填的是英文发行名（如「爱情公寓」→ Apartment of Love），中文界面下译名不带 CJK
     *   而原文带时退回 TMDB 原文，与详情页「原名」一致。
     * - 无译名（NONE）时服务端已把 title 兜底为 TMDB 原文；国产/日韩影视的 Trakt 标题
     *   常是拼音或罗马音，原文带 CJK 而 Trakt 标题不带时改用原文。
     * - 以上 CJK 退回只在中文界面生效；西片维持 Trakt/TMDB 译名结论不变。
     */
    private fun resolveSummaryDisplayTitle(summary: MediaSummary, traktTitle: String): String? {
        val localized = summary.title.trim()
        val original = summary.originalTitle.trim()
        val zhUi = tmdbRepository.currentLanguageTag().startsWith("zh", ignoreCase = true)
        if (summary.titleSource == TitleSource.NONE || localized.isEmpty()) {
            if (zhUi && original.isNotEmpty() && original.hasCjkChar() && !traktTitle.hasCjkChar()) {
                return original
            }
            return null
        }
        if (zhUi && original.hasCjkChar() && !localized.hasCjkChar()) return original
        return localized
    }

    /** 把共享摘要写回当前列表条目与离线快照；标题、海报和类型只补空缺，不覆盖已经显示的值。 */
    private suspend fun applyVisibleMetadata(item: MediaUiItem, summary: MediaSummary) {
        val title = resolveSummaryDisplayTitle(summary, item.title).orEmpty()
        val isMovie = when (item.mediaType) {
            WatchlistMediaType.MOVIE -> true
            WatchlistMediaType.SHOW -> false
            WatchlistMediaType.OTHER -> return
        }
        val cacheType = if (isMovie) {
            OfflineCacheManager.TYPE_WATCHLIST_MOVIE
        } else {
            OfflineCacheManager.TYPE_WATCHLIST_SHOW
        }
        _uiState.update { state ->
            val currentItems = if (isMovie) state.movies else state.shows
            var changed = false
            val updatedItems = currentItems.map { entry ->
                if (entry.selectionKey != item.selectionKey) return@map entry
                // 纯豆瓣承载条目(无 Trakt/TMDB 映射)保留自身标题，避免被 TMDB 摘要覆盖。
                if (entry.traktId <= 0 && entry.doubanId != null) {
                    return@map entry
                }
                val nextTitle = title.takeIf {
                    it.isNotEmpty() && !it.equals(entry.title, ignoreCase = true)
                } ?: entry.displayTitle
                val nextPoster = entry.posterUrl
                    ?: summary.posterPath?.let { com.tracktosearch.data.remote.tmdb.TmdbImageUrls.W342 + it }
                val nextGenres = entry.genres.ifBlank { summary.genres.joinToString(" · ") }
                val nextYear = entry.year ?: summary.year
                if (
                    nextTitle == entry.displayTitle &&
                    nextPoster == entry.posterUrl &&
                    nextGenres == entry.genres &&
                    nextYear == entry.year
                ) return@map entry
                changed = true
                entry.copy(
                    displayTitle = nextTitle,
                    posterUrl = nextPoster,
                    genres = nextGenres,
                    year = nextYear
                )
            }
            if (!changed) state
            else if (isMovie) state.copy(movies = updatedItems)
            else state.copy(shows = updatedItems)
        }
        val updatedItem = (if (isMovie) _uiState.value.movies else _uiState.value.shows)
            .firstOrNull { it.selectionKey == item.selectionKey } ?: return
        try {
            offlineCacheManager.saveMediaItem(cacheType, updatedItem.toMediaItemEntity(cacheType))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 离线快照写入失败不影响列表显示，下次成功加载会整表覆盖
        }
    }

    /** 列表滚动接近末尾时加载下一页，支持超过 Trakt 单页上限的 watchlist。 */
    fun loadMoreMovies() {
        loadMovies(silent = true, loadMore = true)
    }

    /** 列表滚动接近末尾时加载下一页，支持超过 Trakt 单页上限的电视剧 watchlist。 */
    fun loadMoreShows() {
        loadShows(silent = true, loadMore = true)
    }

    /** Watchlist Tab 从其他页面重新可见时刷新已加载的想看列表。 */
    fun onWatchlistTabVisible() {
        applyPendingWatchlistMutations()
    }

    /** 应用详情页产生的单条变更，避免返回 Watchlist 时重新请求完整 Trakt 列表。 */
    private fun applyPendingWatchlistMutations(type: MediaType? = null) {
        if (pendingWatchlistMutations.isEmpty()) return
        val remaining = mutableListOf<TraktRepository.WatchlistMutation>()
        pendingWatchlistMutations.forEach { mutation ->
            if (type != null && mutation.mediaType != type) {
                remaining += mutation
            } else if (!applyWatchlistMutation(mutation)) {
                remaining += mutation
            }
        }
        pendingWatchlistMutations.clear()
        pendingWatchlistMutations += remaining
    }

    private fun applyWatchlistMutation(mutation: TraktRepository.WatchlistMutation): Boolean {
        val isMovie = mutation.mediaType == MediaType.MOVIE
        var applied = false
        _uiState.update { state ->
            val loaded = if (isMovie) state.moviesLoaded else state.showsLoaded
            if (!loaded) return@update state
            applied = true
            val currentItems = if (isMovie) state.movies else state.shows
            val existing = currentItems.any { it.traktId == mutation.traktId }
            val updatedItems = when (mutation.action) {
                TraktRepository.WatchlistMutationAction.ADD -> {
                    (currentItems.filter { it.traktId != mutation.traktId } + mutation.toMediaUiItem())
                        .sortedWith(watchlistListedAtComparator())
                }
                TraktRepository.WatchlistMutationAction.REMOVE -> {
                    currentItems.filter { it.traktId != mutation.traktId }
                }
            }
            if (isMovie) {
                state.copy(
                    movies = updatedItems,
                    movieTotalCount = when (mutation.action) {
                        TraktRepository.WatchlistMutationAction.ADD ->
                            if (existing) state.movieTotalCount else (state.movieTotalCount ?: currentItems.size) + 1
                        TraktRepository.WatchlistMutationAction.REMOVE ->
                            (state.movieTotalCount ?: currentItems.size).minus(if (existing) 1 else 0).coerceAtLeast(0)
                    }
                )
            } else {
                state.copy(
                    shows = updatedItems,
                    showTotalCount = when (mutation.action) {
                        TraktRepository.WatchlistMutationAction.ADD ->
                            if (existing) state.showTotalCount else (state.showTotalCount ?: currentItems.size) + 1
                        TraktRepository.WatchlistMutationAction.REMOVE ->
                            (state.showTotalCount ?: currentItems.size).minus(if (existing) 1 else 0).coerceAtLeast(0)
                    }
                )
            }
        }
        return applied
    }

    /**
     * 标记变更直写离线缓存。
     *
     * 缓存快照只在列表成功加载时整体重写；详情页标记后如果没再进 Watchlist 页
     * （refreshWatchlist 只在列表已加载时才重拉），缓存仍是标记前的旧集合。
     * 下次冷启动先闪旧集合、网络结果落地后卡片跳位。这里让缓存跟随每条成功
     * 落到 Trakt 的变更同步增删，保证冷启动首帧与网络终帧组合一致。
     */
    private fun syncWatchlistCacheWithMutation(mutation: TraktRepository.WatchlistMutation) {
        val cacheType = when (mutation.mediaType) {
            MediaType.MOVIE -> OfflineCacheManager.TYPE_WATCHLIST_MOVIE
            MediaType.SHOW -> OfflineCacheManager.TYPE_WATCHLIST_SHOW
            else -> return
        }
        viewModelScope.launch {
            when (mutation.action) {
                TraktRepository.WatchlistMutationAction.ADD ->
                    offlineCacheManager.saveMediaItem(cacheType, mutation.toMediaUiItem().toMediaItemEntity(cacheType))
                TraktRepository.WatchlistMutationAction.REMOVE ->
                    offlineCacheManager.removeMediaItem(cacheType, mutation.traktId)
            }
        }
    }

    private fun TraktRepository.WatchlistMutation.toMediaUiItem(): MediaUiItem {
        val listedAt = Instant.ofEpochMilli(actedAt).toString()
        return MediaUiItem(
            traktId = traktId,
            tmdbId = tmdbId,
            title = title,
            displayTitle = displayTitle.ifBlank { title },
            year = year,
            genres = genres,
            posterUrl = posterUrl,
            imdbId = imdbId,
            traktRating = traktRating,
            listedAt = listedAt,
            mediaType = if (mediaType == MediaType.SHOW) WatchlistMediaType.SHOW else WatchlistMediaType.MOVIE
        )
    }

    /** 仅刷新已看历史（从详情页标记已看后调用） */
    fun refreshWatched() {
        val state = _uiState.value
        if (state.historyMoviesLoaded || state.historyShowsLoaded || state.historyOthersLoaded) {
            loadHistoryMovies(forceReload = true)
            loadHistoryShows(forceReload = true)
            loadHistoryOthers(forceReload = true)
        }
    }

    /** 从想看列表批量移除，保留旧 MediaType API 供现有调用方使用。 */
    suspend fun batchRemoveFromWatchlist(items: List<MediaUiItem>, type: MediaType): Boolean =
        batchRemoveFromWatchlist(items, type.toWatchlistMediaType())

    /** 从三类想看列表批量移除。 */
    suspend fun batchRemoveFromWatchlist(items: List<MediaUiItem>, type: WatchlistMediaType): Boolean =
        batchRemoveFromMedia(items, type, isWatchlist = true)

    /** 从已看历史批量移除，保留旧 MediaType API 供现有调用方使用。 */
    suspend fun batchRemoveFromHistory(items: List<MediaUiItem>, type: MediaType): Boolean =
        batchRemoveFromHistory(items, type.toWatchlistMediaType())

    /** 从三类已看历史批量移除。 */
    suspend fun batchRemoveFromHistory(items: List<MediaUiItem>, type: WatchlistMediaType): Boolean =
        batchRemoveFromMedia(items, type, isWatchlist = false)

    private suspend fun batchRemoveFromMedia(
        items: List<MediaUiItem>,
        type: WatchlistMediaType,
        isWatchlist: Boolean
    ): Boolean {
        if (items.isEmpty()) return false
        if (sessionModeManager.sessionMode.first() == SessionMode.DOUBAN) {
            batchRemoveFromDouban(items, type, isWatchlist)
            return false
        }

        val traktItems = items.filter { it.traktId > 0 && type != WatchlistMediaType.OTHER }
        val localOnlyItems = items.filter { it !in traktItems }
        val mediaType = type.toTraktMediaType()
        val results = if (mediaType != null && traktItems.isNotEmpty()) {
            coroutineScope {
                traktItems.map { item ->
                    async {
                        if (isWatchlist) {
                            traktRepository.removeFromWatchlist(item.traktId, mediaType, item.tmdbId)
                        } else {
                            traktRepository.removeWatched(item.traktId, mediaType, item.tmdbId)
                        }
                    }
                }.awaitAll()
            }
        } else {
            emptyList()
        }
        val successfulTraktItems = traktItems.filterIndexed { index, _ -> results[index].isSuccess }
        val failedCount = results.count { it.isFailure }
        val successfulItems = successfulTraktItems + localOnlyItems

        if (localOnlyItems.isNotEmpty()) {
            removeDoubanItemsLocally(localOnlyItems, type, isWatchlist)
        }
        removeItemsFromUi(successfulItems, type, isWatchlist)
        syncWatchlistCacheAfterBatchRemoval(successfulItems, type, isWatchlist)

        val itemsToSync = successfulTraktItems.filter { it.doubanId != null || it.imdbId.isNotBlank() }
        if (itemsToSync.isNotEmpty() || localOnlyItems.isNotEmpty()) {
            val removalItems = (itemsToSync + localOnlyItems).map {
                BatchRemovalItem(it.traktId, it.imdbId, it.displayTitle, it.doubanId)
            }
            doubanBatchRemovalManager.startRemoval(removalItems, type == WatchlistMediaType.MOVIE)
        }
        return failedCount > 0
    }

    /**
     * 豆瓣独立模式批量移除：从本地 douban_synced_items 表立即删除,并后台调用豆瓣 API 移除标记。
     *
     * 与 Trakt 模式不同：
     * - 本地表删除立即生效（UI 立即更新），无需等待网络请求
     * - 豆瓣 API 移除在 Application scope 后台运行，失败仅记录日志
     * - UI 按 selectionKey 过滤（豆瓣模式无 traktId 条目也需正确移除）
     * - 同步写入 mark_action_record 流水（用 MediaUiItem 已有快照字段,不走 TMDB enrich），
     *   使豆瓣模式批量移除也出现在标记记录页 REMOVED Tab
     */
    private suspend fun batchRemoveFromDouban(
        items: List<MediaUiItem>,
        type: WatchlistMediaType,
        isWatchlist: Boolean
    ) {
        if (items.isEmpty()) return
        removeDoubanItemsLocally(items, type, isWatchlist)
        removeItemsFromUi(items, type, isWatchlist)
        val removalItems = items.map { item ->
            BatchRemovalItem(item.traktId, item.imdbId, item.displayTitle, item.doubanId)
        }
        doubanBatchRemovalManager.startRemoval(removalItems, type == WatchlistMediaType.MOVIE)
    }

    private suspend fun removeDoubanItemsLocally(
        items: List<MediaUiItem>,
        type: WatchlistMediaType,
        isWatchlist: Boolean
    ) {
        val traktMediaType = type.toTraktMediaType()
        val actionType = if (isWatchlist) MarkActionType.REMOVE_WATCHLIST else MarkActionType.UNMARK_WATCHED
        items.forEach { item ->
            val doubanId = item.doubanId
                ?: item.imdbId.takeIf { it.isNotBlank() }?.let { imdbId ->
                    runCatching { doubanSyncedItemDao.getByImdbId(imdbId) }.getOrNull()?.doubanId
                }
            if (doubanId != null) {
                doubanSyncedItemDao.deleteByDoubanId(doubanId)
            }
            if (traktMediaType != null) {
                traktRepository.insertMarkRecordWithSnapshot(
                    traktId = item.traktId,
                    tmdbId = item.tmdbId,
                    imdbId = item.imdbId,
                    mediaType = traktMediaType,
                    title = item.title,
                    displayTitle = item.displayTitle,
                    posterUrl = item.posterUrl,
                    year = item.year,
                    actionType = actionType
                )
            }
        }
    }

    private fun removeItemsFromUi(
        items: List<MediaUiItem>,
        type: WatchlistMediaType,
        isWatchlist: Boolean
    ) {
        val selectionKeys = items.map { it.selectionKey }.toSet()
        _uiState.update { state ->
            when (type) {
                WatchlistMediaType.MOVIE -> if (isWatchlist) {
                    state.copy(movies = state.movies.filter { it.selectionKey !in selectionKeys })
                } else {
                    state.copy(historyMovies = state.historyMovies.filter { it.selectionKey !in selectionKeys })
                }
                WatchlistMediaType.SHOW -> if (isWatchlist) {
                    state.copy(shows = state.shows.filter { it.selectionKey !in selectionKeys })
                } else {
                    state.copy(historyShows = state.historyShows.filter { it.selectionKey !in selectionKeys })
                }
                WatchlistMediaType.OTHER -> if (isWatchlist) {
                    state.copy(others = state.others.filter { it.selectionKey !in selectionKeys })
                } else {
                    state.copy(historyOthers = state.historyOthers.filter { it.selectionKey !in selectionKeys })
                }
            }
        }
    }

    /**
     * 批量移除后同步删除离线缓存行，理由同 [syncWatchlistCacheWithMutation]：
     * 批量移除只更新 UI 与服务端，不重写缓存快照，下次冷启动会闪现已移除的条目。
     */
    private fun syncWatchlistCacheAfterBatchRemoval(
        items: List<MediaUiItem>,
        type: WatchlistMediaType,
        isWatchlist: Boolean
    ) {
        val cacheType = when {
            isWatchlist && type == WatchlistMediaType.MOVIE -> OfflineCacheManager.TYPE_WATCHLIST_MOVIE
            isWatchlist && type == WatchlistMediaType.SHOW -> OfflineCacheManager.TYPE_WATCHLIST_SHOW
            !isWatchlist && type == WatchlistMediaType.MOVIE -> OfflineCacheManager.TYPE_HISTORY_MOVIE
            !isWatchlist && type == WatchlistMediaType.SHOW -> OfflineCacheManager.TYPE_HISTORY_SHOW
            else -> return
        }
        val traktIds = items.mapNotNull { it.traktId.takeIf { id -> id > 0 } }
        if (traktIds.isEmpty()) return
        viewModelScope.launch {
            traktIds.forEach { offlineCacheManager.removeMediaItem(cacheType, it) }
        }
    }

    private fun WatchlistMediaType.toTraktMediaType(): MediaType? = when (this) {
        WatchlistMediaType.MOVIE -> MediaType.MOVIE
        WatchlistMediaType.SHOW -> MediaType.SHOW
        WatchlistMediaType.OTHER -> null
    }

    private fun MediaType.toWatchlistMediaType(): WatchlistMediaType = when (this) {
        MediaType.MOVIE -> WatchlistMediaType.MOVIE
        MediaType.SHOW -> WatchlistMediaType.SHOW
        else -> WatchlistMediaType.OTHER
    }
}

private suspend fun <T> retryIO(times: Int, block: suspend () -> T): T {
    var currentAttempt = 0
    var lastException: Exception? = null
    while (currentAttempt <= times) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastException = e
            currentAttempt++
            if (currentAttempt <= times) delay(1000L * currentAttempt)
        }
    }
    throw lastException ?: IllegalStateException("retryIO failed without exception")
}

// ========== 离线缓存转换扩展 ==========

/** 是否含 CJK 表意/音节字符（汉字、假名、谚文），用于识别本土文字标题与拉丁转写。 */
private fun String.hasCjkChar(): Boolean = any { ch ->
    val code = ch.code
    code in 0x2E80..0x9FFF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF
}

private fun MediaUiItem.toMediaItemEntity(type: String) = MediaItemEntity(
    traktId = traktId,
    tmdbId = tmdbId,
    type = type,
    title = title,
    displayTitle = displayTitle,
    year = year,
    genres = genres,
    posterUrl = posterUrl,
    imdbId = imdbId,
    traktRating = traktRating,
    listedAt = listedAt
)

private fun MediaItemEntity.toMediaUiItem() = MediaUiItem(
    traktId = traktId,
    tmdbId = tmdbId,
    title = title,
    displayTitle = displayTitle,
    year = year,
    genres = genres,
    posterUrl = posterUrl,
    imdbId = imdbId,
    traktRating = traktRating,
    listedAt = listedAt,
    mediaType = if (type.endsWith("_show")) WatchlistMediaType.SHOW else WatchlistMediaType.MOVIE
)
