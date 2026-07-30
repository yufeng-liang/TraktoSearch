package com.tracktosearch.ui.screen.watchlist

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.db.MediaItemEntity
import com.tracktosearch.data.local.db.OfflineCacheManager
import com.tracktosearch.data.repository.BatchRemovalItem
import com.tracktosearch.data.repository.BatchRemovalProgress
import com.tracktosearch.data.repository.ConsistencyCheckResult
import com.tracktosearch.data.repository.DoubanBatchRemovalManager
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.data.repository.DoubanTraktStatusConsistencyChecker
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.SyncMode
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val listedAt: String = ""
)

// 筛选相关枚举（与豆瓣失败页独立定义，Watchlist 模块自包含）
enum class MarkedTimePreset { SEVEN_DAYS, THIRTY_DAYS, ALL }
enum class SortOrder { ASC, DESC }

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
    val movies: List<MediaUiItem> = emptyList(),
    val shows: List<MediaUiItem> = emptyList(),
    val moviesError: String? = null,
    val showsError: String? = null,
    val moviesLoaded: Boolean = false,
    val showsLoaded: Boolean = false,
    val hasMoreMovies: Boolean = true,
    val hasMoreShows: Boolean = true,
    val moviePage: Int = 1,
    val showPage: Int = 1,
    // 已看历史
    val historyMovies: List<MediaUiItem> = emptyList(),
    val historyShows: List<MediaUiItem> = emptyList(),
    val isLoadingHistoryMovies: Boolean = false,
    val isLoadingHistoryShows: Boolean = false,
    val historyMoviesError: String? = null,
    val historyShowsError: String? = null,
    val historyMoviesLoaded: Boolean = false,
    val historyShowsLoaded: Boolean = false,
    // 豆瓣同步进度（isRunning 时在 Tab 栏下方显示横幅，点击重新打开同步弹窗）
    val doubanSyncProgress: DoubanSyncProgress? = null,
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
            if (selectedTab == 0) movies else shows
        } else {
            if (selectedTab == 0) historyMovies else historyShows
        }
        return items.any { it.tmdbId > 0 && it.posterUrl == null }
    }
}

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    private val offlineCacheManager: OfflineCacheManager,
    private val doubanSyncManager: DoubanSyncManager,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanSyncMetaStorage: DoubanSyncMetaStorage,
    private val statusConsistencyChecker: DoubanTraktStatusConsistencyChecker,
    private val doubanBatchRemovalManager: DoubanBatchRemovalManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    // 各加载协程的 Job 引用,refresh() 前统一 cancel,避免旧协程写入覆盖新数据
    private var loadMoviesJob: Job? = null
    private var loadShowsJob: Job? = null
    private var loadHistoryMoviesJob: Job? = null
    private var loadHistoryShowsJob: Job? = null

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

    /** 是否需要首次同步引导（已登录豆瓣 + 从未同步过） */
    private val _needFirstSyncGuide = MutableStateFlow(false)
    val needFirstSyncGuide: StateFlow<Boolean> = _needFirstSyncGuide.asStateFlow()

    /** 检查是否需要首次同步引导 */
    private fun checkFirstSyncNeeded() {
        viewModelScope.launch {
            // 未登录豆瓣 → 不需要引导
            if (doubanAuthStorage.getCredentials() == null) return@launch
            // 已登录但从未同步过 → 需要引导
            val status = doubanSyncMetaStorage.getCooldownStatus()
            if (status.neverSynced) {
                _needFirstSyncGuide.value = true
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

    /** 从 movies + shows 聚合可选类型（按 `,` 和 `·` 拆分、distinct、sorted） */
    val availableGenres: StateFlow<List<String>> = _uiState.map { state ->
        (state.movies.asSequence() + state.shows.asSequence())
            .flatMap { it.genres.split(",", "·").asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 从 movies + shows 动态生成可选年代列表（按起始年份降序，如 2020、2010、2000...） */
    val decadeOptions: StateFlow<List<Int>> = _uiState.map { state ->
        (state.movies.asSequence() + state.shows.asSequence())
            .mapNotNull { it.year }
            .filter { it > 0 }
            .map { (it / 10) * 10 }  // 取年代起始年份,如 2023 -> 2020
            .distinct()
            .sortedDescending()
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        // 监听豆瓣同步进度：isRunning 时显示横幅，完成时自动弹出结果弹窗
        viewModelScope.launch {
            doubanSyncManager.progress.collect { progress: DoubanSyncProgress ->
                // 同步进行中或已完成 → 暴露给 UI 显示横幅
                if (progress.isRunning || progress.isComplete) {
                    _uiState.update { it.copy(doubanSyncProgress = progress) }
                    // 完成且有成功条目，触发静默刷新（保留已有数据避免闪烁）
                    if (progress.isComplete && progress.successCount > 0) {
                        refreshIfLoaded(silent = true)
                    }
                    // 完成后：发事件让 UI 自动弹出 DoubanSyncDialog，横幅 5 秒后消失
                    if (progress.isComplete) {
                        _syncCompleteEvent.emit(Unit)
                        delay(5000)
                        _uiState.update { it.copy(doubanSyncProgress = null) }
                        // 重置 progress 避免下次进入页面时 collector 收到旧 isComplete=true 重复弹窗
                        doubanSyncManager.resetProgress()
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
            doubanBatchRemovalManager.progress.collect { progress: BatchRemovalProgress ->
                if (progress.isRunning || progress.isComplete) {
                    _uiState.update { it.copy(batchRemovalProgress = progress) }
                    if (progress.isComplete) {
                        // 完成后 5 秒横幅消失
                        delay(5000)
                        _uiState.update { it.copy(batchRemovalProgress = null) }
                        // 重置 progress 避免下次进入页面时 collector 收到旧 isComplete=true 重复显示横幅
                        doubanBatchRemovalManager.resetProgress()
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

    fun loadMovies(forceReload: Boolean = false, silent: Boolean = false) {
        if (!forceReload && _uiState.value.moviesLoaded && _uiState.value.movies.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingMovies) return  // 防止并发重复请求
        loadMoviesJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingMovies = !silent,
                moviesError = if (silent) _uiState.value.moviesError else null
            )
            val result = retryIO(maxRetries) { traktRepository.getMovieWatchlist(page = _uiState.value.moviePage, limit = 200, forceRefresh = forceReload) }
            result.onSuccess { (rawItems, totalPages) ->
                // 过滤掉本地已标记已看但不在想看缓存中的电影（处理标记已看后 Trakt API 最终一致性延迟）
                val locallyWatchedIds = traktRepository.getLocallyWatchedOnlyTraktIds(MediaType.MOVIE)
                val items = if (locallyWatchedIds.isNotEmpty()) rawItems.filter { it.movie.ids.trakt !in locallyWatchedIds } else rawItems
                // 如果 forceReload 且数据与现有列表完全相同，跳过 TMDB 富化和 UI 更新
                if (forceReload) {
                    val newIds = items.map { it.movie.ids.trakt }.toSet()
                    val oldIds = _uiState.value.movies.map { it.traktId }.toSet()
                    if (newIds == oldIds && items.size == _uiState.value.movies.size) {
                        _uiState.value = _uiState.value.copy(
                            isLoadingMovies = if (silent) _uiState.value.isLoadingMovies else false,
                            hasMoreMovies = _uiState.value.moviePage < totalPages,
                            moviePage = _uiState.value.moviePage + 1
                        )
                        return@launch
                    }
                    // 静默刷新时保留旧列表避免闪烁；非静默时清空旧列表避免新旧数据混合
                    if (!silent) {
                        _uiState.value = _uiState.value.copy(movies = emptyList())
                    }
                }
                // 先发布完整占位列表，富化结果全部完成后再一次性替换，避免每个 item 触发一次状态复制。
                val placeholderList = List(items.size) { index ->
                    val p = items[index].movie
                    createPlaceholder(p.ids.trakt, p.ids.tmdb, p.title, p.year, p.ids.imdb, p.rating, items[index].listed_at)
                }
                if (!silent) {
                    _uiState.update { it.copy(movies = placeholderList) }
                }
                val deferredItems = items.map { item ->
                    async {
                        enrichMediaItem(
                            traktId = item.movie.ids.trakt, tmdbId = item.movie.ids.tmdb,
                            title = item.movie.title, year = item.movie.year,
                            imdbId = item.movie.ids.imdb, rating = item.movie.rating,
                            listedAt = item.listed_at, isMovie = true
                        )
                    }
                }
                val uiItems = deferredItems.awaitAll()
                // 写入离线缓存（仅首页）：在 moviePage 递增前判断，确保首页加载必缓存
                if (_uiState.value.moviePage == 1) {
                    val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_WATCHLIST_MOVIE) }
                    offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_WATCHLIST_MOVIE, entities)
                }
                _uiState.value = _uiState.value.copy(
                    movies = uiItems,
                    isLoadingMovies = if (silent) _uiState.value.isLoadingMovies else false,
                    moviesLoaded = true,
                    hasMoreMovies = _uiState.value.moviePage < totalPages,
                    moviePage = _uiState.value.moviePage + 1
                )
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_WATCHLIST_MOVIE)
                _uiState.value = _uiState.value.copy(
                    isLoadingMovies = if (silent) _uiState.value.isLoadingMovies else false,
                    moviesLoaded = true,
                    movies = if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() } else _uiState.value.movies,
                    moviesError = if (cached.isNotEmpty()) null else (e.message ?: context.getString(R.string.error_load_failed))
                )
            }
        }
    }

    fun loadShows(forceReload: Boolean = false, silent: Boolean = false) {
        if (!forceReload && _uiState.value.showsLoaded && _uiState.value.shows.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingShows) return  // 防止并发重复请求
        loadShowsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingShows = !silent,
                showsError = if (silent) _uiState.value.showsError else null
            )
            val result = retryIO(maxRetries) { traktRepository.getShowWatchlist(page = _uiState.value.showPage, limit = 200, forceRefresh = forceReload) }
            result.onSuccess { (rawItems, totalPages) ->
                // 过滤掉本地已标记已看但不在想看缓存中的剧集（处理标记已看后 Trakt API 最终一致性延迟）
                val locallyWatchedIds = traktRepository.getLocallyWatchedOnlyTraktIds(MediaType.SHOW)
                val items = if (locallyWatchedIds.isNotEmpty()) rawItems.filter { it.show.ids.trakt !in locallyWatchedIds } else rawItems
                // 如果 forceReload 且数据与现有列表完全相同，跳过 TMDB 富化和 UI 更新
                if (forceReload) {
                    val newIds = items.map { it.show.ids.trakt }.toSet()
                    val oldIds = _uiState.value.shows.map { it.traktId }.toSet()
                    if (newIds == oldIds && items.size == _uiState.value.shows.size) {
                        _uiState.value = _uiState.value.copy(
                            isLoadingShows = if (silent) _uiState.value.isLoadingShows else false,
                            hasMoreShows = _uiState.value.showPage < totalPages,
                            showPage = _uiState.value.showPage + 1
                        )
                        return@launch
                    }
                    // 静默刷新时保留旧列表避免闪烁；非静默时清空旧列表避免新旧数据混合
                    if (!silent) {
                        _uiState.value = _uiState.value.copy(shows = emptyList())
                    }
                }
                // 每个 item 的 TMDB enrich 完成就立即显示，不等整批
                // 预填充占位列表到完整大小，避免多个 async 协程并发 add/resize 导致 IndexOutOfBounds
                val placeholderList = List(items.size) { index ->
                    val s = items[index].show
                    createPlaceholder(s.ids.trakt, s.ids.tmdb, s.title, s.year, s.ids.imdb, s.rating, items[index].listed_at)
                }
                if (!silent) {
                    _uiState.update { it.copy(shows = placeholderList) }
                }
                val deferredItems = items.map { item ->
                    async {
                        enrichMediaItem(
                            traktId = item.show.ids.trakt, tmdbId = item.show.ids.tmdb,
                            title = item.show.title, year = item.show.year,
                            imdbId = item.show.ids.imdb, rating = item.show.rating,
                            listedAt = item.listed_at, isMovie = false
                        )
                    }
                }
                val uiItems = deferredItems.awaitAll()
                // 写入离线缓存（仅首页）：在 showPage 递增前判断，确保首页加载必缓存
                if (_uiState.value.showPage == 1) {
                    val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_WATCHLIST_SHOW) }
                    offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_WATCHLIST_SHOW, entities)
                }
                _uiState.value = _uiState.value.copy(
                    shows = uiItems,
                    isLoadingShows = if (silent) _uiState.value.isLoadingShows else false,
                    showsLoaded = true,
                    hasMoreShows = _uiState.value.showPage < totalPages,
                    showPage = _uiState.value.showPage + 1
                )
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_WATCHLIST_SHOW)
                _uiState.value = _uiState.value.copy(
                    isLoadingShows = if (silent) _uiState.value.isLoadingShows else false,
                    showsLoaded = true,
                    shows = if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() } else _uiState.value.shows,
                    showsError = if (cached.isNotEmpty()) null else (e.message ?: context.getString(R.string.error_load_failed))
                )
            }
        }
    }

    fun loadHistoryMovies(forceReload: Boolean = false) {
        if (!forceReload && _uiState.value.historyMoviesLoaded && _uiState.value.historyMovies.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingHistoryMovies) return
        loadHistoryMoviesJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingHistoryMovies = true,
                historyMoviesError = null
            )
            val result = retryIO(maxRetries) { traktRepository.getMovieHistory() }
            result.onSuccess { (items, _) ->
                if (forceReload) {
                    _uiState.value = _uiState.value.copy(historyMovies = emptyList())
                }
                // 历史记录可能包含同一部电影的多次观看，按 traktId 去重
                val dedupedItems = items.distinctBy { it.movie.ids.trakt }
                // 过滤掉本地已取消已看的电影（处理取消已看后 Trakt API 最终一致性延迟）
                val locallyRemovedIds = traktRepository.getLocallyWatchlistOnlyTraktIds(MediaType.MOVIE)
                val filteredItems = if (locallyRemovedIds.isNotEmpty()) dedupedItems.filter { it.movie.ids.trakt !in locallyRemovedIds } else dedupedItems
                val deferredItems = filteredItems.map { item ->
                    async {
                        enrichMediaItem(
                            traktId = item.movie.ids.trakt, tmdbId = item.movie.ids.tmdb,
                            title = item.movie.title, year = item.movie.year,
                            imdbId = item.movie.ids.imdb, rating = item.movie.rating,
                            listedAt = item.listed_at, isMovie = true
                        )
                    }
                }
                val uiItems = deferredItems.awaitAll()
                _uiState.value = _uiState.value.copy(
                    historyMovies = uiItems,
                    isLoadingHistoryMovies = false,
                    historyMoviesLoaded = true
                )
                // 写入离线缓存
                val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_HISTORY_MOVIE) }
                offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_HISTORY_MOVIE, entities)
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_HISTORY_MOVIE)
                _uiState.value = _uiState.value.copy(
                    isLoadingHistoryMovies = false,
                    historyMoviesLoaded = true,
                    historyMovies = if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() } else _uiState.value.historyMovies,
                    historyMoviesError = if (cached.isNotEmpty()) null else (e.message ?: context.getString(R.string.error_load_failed))
                )
            }
        }
    }

    fun loadHistoryShows(forceReload: Boolean = false) {
        if (!forceReload && _uiState.value.historyShowsLoaded && _uiState.value.historyShows.isNotEmpty()) {
            return
        }
        if (_uiState.value.isLoadingHistoryShows) return
        loadHistoryShowsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingHistoryShows = true,
                historyShowsError = null
            )
            val result = retryIO(maxRetries) { traktRepository.getShowHistory() }
            result.onSuccess { (items, _) ->
                if (forceReload) {
                    _uiState.value = _uiState.value.copy(historyShows = emptyList())
                }
                val dedupedItems = items.distinctBy { it.show.ids.trakt }
                // 过滤掉本地已取消已看的剧集（处理取消已看后 Trakt API 最终一致性延迟）
                val locallyRemovedIds = traktRepository.getLocallyWatchlistOnlyTraktIds(MediaType.SHOW)
                val filteredItems = if (locallyRemovedIds.isNotEmpty()) dedupedItems.filter { it.show.ids.trakt !in locallyRemovedIds } else dedupedItems
                val deferredItems = filteredItems.map { item ->
                    async {
                        enrichMediaItem(
                            traktId = item.show.ids.trakt, tmdbId = item.show.ids.tmdb,
                            title = item.show.title, year = item.show.year,
                            imdbId = item.show.ids.imdb, rating = item.show.rating,
                            listedAt = item.listed_at, isMovie = false
                        )
                    }
                }
                val uiItems = deferredItems.awaitAll()
                _uiState.value = _uiState.value.copy(
                    historyShows = uiItems,
                    isLoadingHistoryShows = false,
                    historyShowsLoaded = true
                )
                // 写入离线缓存
                val entities = uiItems.map { it.toMediaItemEntity(OfflineCacheManager.TYPE_HISTORY_SHOW) }
                offlineCacheManager.saveMediaItems(OfflineCacheManager.TYPE_HISTORY_SHOW, entities)
            }.onFailure { e ->
                // 从离线缓存读取
                val cached = offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_HISTORY_SHOW)
                _uiState.value = _uiState.value.copy(
                    isLoadingHistoryShows = false,
                    historyShowsLoaded = true,
                    historyShows = if (cached.isNotEmpty()) cached.map { it.toMediaUiItem() } else _uiState.value.historyShows,
                    historyShowsError = if (cached.isNotEmpty()) null else (e.message ?: context.getString(R.string.error_load_failed))
                )
            }
        }
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

    private fun createPlaceholder(
        traktId: Int, tmdbId: Int, title: String, year: Int?,
        imdbId: String, rating: Double, listedAt: String
    ): MediaUiItem {
        return MediaUiItem(
            traktId = traktId, tmdbId = tmdbId, title = title,
            displayTitle = title, year = year, genres = "",
            posterUrl = null, imdbId = imdbId,
            traktRating = rating, listedAt = listedAt
        )
    }

    fun refresh() {
        // 取消所有正在进行的加载协程,避免旧协程完成后覆盖刚重置的新数据
        loadMoviesJob?.cancel()
        loadShowsJob?.cancel()
        loadHistoryMoviesJob?.cancel()
        loadHistoryShowsJob?.cancel()

        val wasHistoryLoaded = _uiState.value.historyMoviesLoaded || _uiState.value.historyShowsLoaded
        _uiState.value = WatchlistUiState()
        loadMovies(forceReload = true)
        loadShows(forceReload = true)
        if (wasHistoryLoaded) {
            loadHistoryMovies(forceReload = true)
            loadHistoryShows(forceReload = true)
        }
    }

    /** 页面恢复可见时调用：如果之前已加载过，则后台静默刷新，不重置已有数据避免重复拉取 */
    fun refreshIfLoaded(silent: Boolean = false) {
        val state = _uiState.value
        if (state.moviesLoaded || state.showsLoaded) {
            // 只重置分页，保留已有数据避免 UI 闪烁和重复拉取
            _uiState.value = state.copy(
                moviePage = 1,
                showPage = 1,
                hasMoreMovies = true,
                hasMoreShows = true
            )
            loadMovies(forceReload = true, silent = silent)
            loadShows(forceReload = true, silent = silent)
        }
        if (state.historyMoviesLoaded || state.historyShowsLoaded) {
            loadHistoryMovies(forceReload = true)
            loadHistoryShows(forceReload = true)
        }
    }

    /** 仅刷新想看列表（从详情页标记想看后调用） */
    fun refreshWatchlist() {
        val state = _uiState.value
        if (state.moviesLoaded || state.showsLoaded) {
            _uiState.value = state.copy(
                moviePage = 1,
                showPage = 1,
                hasMoreMovies = true,
                hasMoreShows = true
            )
            loadMovies(forceReload = true, silent = true)
            loadShows(forceReload = true, silent = true)
        }
    }

    /** 仅刷新已看历史（从详情页标记已看后调用） */
    fun refreshWatched() {
        val state = _uiState.value
        if (state.historyMoviesLoaded || state.historyShowsLoaded) {
            loadHistoryMovies(forceReload = true)
            loadHistoryShows(forceReload = true)
        }
    }

    /** 从想看列表批量移除（suspend，调用方等待完成后关闭多选栏）。
     *  部分成功时仍会更新 UI（移除已成功项）并启动豆瓣批量移除。
     *  @return true=有部分条目移除失败；false=全部成功 */
    suspend fun batchRemoveFromWatchlist(traktIds: List<Int>, type: MediaType): Boolean {
        val isMovie = type == MediaType.MOVIE
        val results = coroutineScope {
            traktIds.map { id ->
                async { traktRepository.removeFromWatchlist(id, type) }
            }.awaitAll()
        }
        val successCount = results.count { it.isSuccess }
        val failedCount = results.size - successCount
        if (successCount > 0) {
            // 仅对成功的 id 执行 UI 更新和豆瓣批量移除，避免部分失败时 UI 与服务端不一致
            val successIds = traktIds.filterIndexed { i, _ -> results[i].isSuccess }.toSet()
            // 先从当前列表中收集待同步移除豆瓣的条目（需要 imdbId/title），在 UI 更新前抓取
            val itemsToSync = if (isMovie) {
                _uiState.value.movies.filter { it.traktId in successIds }
            } else {
                _uiState.value.shows.filter { it.traktId in successIds }
            }
            _uiState.value = if (isMovie) {
                _uiState.value.copy(movies = _uiState.value.movies.filter { it.traktId !in successIds })
            } else {
                _uiState.value.copy(shows = _uiState.value.shows.filter { it.traktId !in successIds })
            }
            // 后台同步移除豆瓣标记（在 Application scope 跑，不依赖 ViewModel 生命周期）
            if (itemsToSync.isNotEmpty()) {
                val removalItems = itemsToSync.map { BatchRemovalItem(it.traktId, it.imdbId, it.displayTitle) }
                doubanBatchRemovalManager.startRemoval(removalItems, isMovie)
            }
        }
        return failedCount > 0
    }

    /** 从已看历史批量移除（suspend，调用方等待完成后关闭多选栏）。
     *  部分成功时仍会更新 UI（移除已成功项）并启动豆瓣批量移除。
     *  @return true=有部分条目移除失败；false=全部成功 */
    suspend fun batchRemoveFromHistory(traktIds: List<Int>, type: MediaType): Boolean {
        val isMovie = type == MediaType.MOVIE
        val results = coroutineScope {
            traktIds.map { id ->
                async { traktRepository.removeWatched(id, type) }
            }.awaitAll()
        }
        val successCount = results.count { it.isSuccess }
        val failedCount = results.size - successCount
        if (successCount > 0) {
            // 仅对成功的 id 执行 UI 更新和豆瓣批量移除，避免部分失败时 UI 与服务端不一致
            val successIds = traktIds.filterIndexed { i, _ -> results[i].isSuccess }.toSet()
            // 先从当前列表中收集待同步移除豆瓣的条目（需要 imdbId/title），在 UI 更新前抓取
            val itemsToSync = if (isMovie) {
                _uiState.value.historyMovies.filter { it.traktId in successIds }
            } else {
                _uiState.value.historyShows.filter { it.traktId in successIds }
            }
            _uiState.value = if (isMovie) {
                _uiState.value.copy(historyMovies = _uiState.value.historyMovies.filter { it.traktId !in successIds })
            } else {
                _uiState.value.copy(historyShows = _uiState.value.historyShows.filter { it.traktId !in successIds })
            }
            // 后台同步移除豆瓣标记（在 Application scope 跑，不依赖 ViewModel 生命周期）
            if (itemsToSync.isNotEmpty()) {
                val removalItems = itemsToSync.map { BatchRemovalItem(it.traktId, it.imdbId, it.displayTitle) }
                doubanBatchRemovalManager.startRemoval(removalItems, isMovie)
            }
        }
        return failedCount > 0
    }
}

private suspend fun <T> retryIO(times: Int, block: suspend () -> T): T {
    var currentAttempt = 0
    var lastException: Exception? = null
    while (currentAttempt <= times) {
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            currentAttempt++
            if (currentAttempt <= times) delay(1000L * currentAttempt)
        }
    }
    throw lastException!!
}

// ========== 离线缓存转换扩展 ==========

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
    listedAt = listedAt
)
