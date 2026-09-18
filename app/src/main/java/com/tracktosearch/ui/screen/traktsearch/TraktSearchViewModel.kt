package com.tracktosearch.ui.screen.traktsearch

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiProfileBehavior
import com.tracktosearch.data.ai.AiProfileBehaviorRecorder
import com.tracktosearch.data.ai.MediaSourceSnapshot
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.remote.trakt.dto.TraktSearchResult
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.ui.haptic.HapticOutcome
import com.tracktosearch.ui.haptic.HapticOutcomeEmitter
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@Immutable
data class TraktSearchUiItem(
    val traktId: Int = 0,
    val tmdbId: Int = 0,
    val title: String = "",
    val displayTitle: String = "",
    val year: Int? = null,
    val genres: String = "",
    val posterUrl: String? = null,
    val imdbId: String = "",
    val traktRating: Double = 0.0,
    val knownForDepartment: String = "",
    // 人物流行度(TMDB 提供),用于人物搜索结果按流行度降序排序
    val popularity: Double = 0.0,
    // 前一屏 SubcomposeAsyncImage 加载头像后提取的主色，直接透传给 PersonScreen 首帧沉浸
    val avatarColor: Color? = null
)

@Immutable
data class SearchTabState(
    val results: List<TraktSearchUiItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val loadMoreError: Boolean = false,
    val hasSearched: Boolean = false,
    val totalCount: Int = 0,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val retryCount: Int = 0,
    val isRetrying: Boolean = false
)

@Immutable
data class DiskSearchState(
    val resources: List<ResourceItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
    val enabledSources: Set<String> = ResourceRepository.ALL_SOURCES,
    val enabledDiskTypes: Set<com.tracktosearch.data.remote.dto.DiskType> = ResourceRepository.ALL_DISK_TYPES,
    val availableSources: List<String> = emptyList(),
    val customSourceNames: Map<String, String> = emptyMap(),
    val completedSources: Int = 0,
    val totalSources: Int = 0
)

@Immutable
data class TraktSearchUiState(
    val query: String = "",
    val selectedTab: MediaType = MediaType.MOVIE,
    val movieState: SearchTabState = SearchTabState(),
    val showState: SearchTabState = SearchTabState(),
    val personState: SearchTabState = SearchTabState(),
    val diskState: DiskSearchState = DiskSearchState()
) {
    val currentTabState: SearchTabState
        get() = tabStateFor(selectedTab)

    fun tabStateFor(type: MediaType): SearchTabState = when (type) {
        MediaType.MOVIE -> movieState
        MediaType.SHOW -> showState
        MediaType.PERSON -> personState
        MediaType.DISK -> SearchTabState()
    }
}

@HiltViewModel
class TraktSearchViewModel @Inject constructor(
    private val traktRepository: TraktRepository,
    private val tmdbRepository: TmdbRepository,
    private val resourceRepository: ResourceRepository,
    private val sessionModeManager: SessionModeManager,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    // 观影画像行为记录：未授权时记录器自己静默返回，调用点不需要判授权
    private val aiProfileBehaviorRecorder: AiProfileBehaviorRecorder
) : ViewModel() {

    // 限制 enrich 并发数，避免触发 API 限流
    private val enrichSemaphore = Semaphore(5)

    // 当前搜索任务，新搜索前取消旧任务避免竞态
    private var searchJob: kotlinx.coroutines.Job? = null

    private val initialTypeFromNav = when (savedStateHandle.get<String>("type")) {
        "show" -> MediaType.SHOW
        "person" -> MediaType.PERSON
        "disk" -> MediaType.DISK
        else -> null
    }
    private val initialQueryStr = savedStateHandle.get<String>("query") ?: ""

    // 初始类型：优先使用导航参数，否则使用默认值 MOVIE
    private val _initialTab = initialTypeFromNav ?: MediaType.MOVIE
    private val _uiState = MutableStateFlow(TraktSearchUiState(selectedTab = _initialTab))
    val uiState: StateFlow<TraktSearchUiState> = _uiState.asStateFlow()

    /** 结果类触感的出口，界面侧一行 `HapticOutcomeEffect(viewModel.hapticOutcomes)` 收集。 */
    private val hapticOutcomeEmitter = HapticOutcomeEmitter()
    val hapticOutcomes: SharedFlow<HapticOutcome> = hapticOutcomeEmitter.outcomes

    // 全局想看/已看缓存
    private val _watchlistWatchedIds = MutableStateFlow<TraktRepository.WatchlistWatchedIds?>(null)
    val watchlistWatchedIds: StateFlow<TraktRepository.WatchlistWatchedIds?> = _watchlistWatchedIds.asStateFlow()

    init {
        viewModelScope.launch {
            if (!sessionModeManager.traktConnected.value) {
                _watchlistWatchedIds.value = TraktRepository.WatchlistWatchedIds()
                return@launch
            }
            traktRepository.getWatchlistWatchedIds()?.let { _watchlistWatchedIds.value = it }
            // 仓库层网络失败会向上抛（约定由调用方兜底）；失败仅保留上面的缓存值，
            // 未捕获会把协程异常抛到全局 handler 直接杀死进程（断网时必现）
            try {
                traktRepository.loadWatchlistWatchedIds().let { _watchlistWatchedIds.value = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("TraktSearchVM", "loadWatchlistWatchedIds failed: ${e.message}")
            }
        }
    }

    /**
     * 初始化搜索：从 Composable 传入正确的 type 和 query。
     * inline 模式下 savedStateHandle 中没有 type，需要通过此方法同步正确的类型。
     */
    fun initSearch(query: String, type: MediaType) {
        val current = _uiState.value
        val anySearched = current.movieState.hasSearched || current.showState.hasSearched ||
                current.personState.hasSearched || current.diskState.hasSearched
        // 已有搜索结果且查询词和类型都相同，不重置（从详情页返回时保持状态）
        // 注意：type 不同时不能早返回，否则切 type 同 query 的场景（如 MOVIE/痴迷 → PERSON/痴迷）
        // 会被错误跳过，导致 selectedTab 不切换、新 type 搜索不触发
        if (anySearched && current.query == query && current.selectedTab == type) return

        // 同步 selectedTab 为传入的 type（修复 inline 模式下初始类型错误的问题）
        if (_uiState.value.selectedTab != type) {
            _uiState.value = _uiState.value.copy(selectedTab = type)
        }
        _uiState.value = TraktSearchUiState(query = query, selectedTab = type)
        if (type == MediaType.DISK) {
            searchDiskInternal(query)
        } else {
            search(query, type)
        }
    }

    fun switchTab(type: MediaType) {
        val current = _uiState.value
        if (current.selectedTab == type) return
        _uiState.value = current.copy(selectedTab = type)
        // 如果该 tab 还没搜索过，自动触发搜索
        val targetState = when (type) {
            MediaType.MOVIE -> current.movieState
            MediaType.SHOW -> current.showState
            MediaType.PERSON -> current.personState
            MediaType.DISK -> null
        }
        if (type == MediaType.DISK) {
            if (!current.diskState.hasSearched && current.query.isNotBlank()) {
                searchDiskInternal(current.query)
            }
        } else if (targetState != null && !targetState.hasSearched && current.query.isNotBlank()) {
            search(current.query, type)
        }
    }

    fun search(query: String, type: MediaType? = null) {
        val searchType = type ?: _uiState.value.selectedTab
        if (query.isBlank()) return

        // 网盘搜索走独立逻辑
        if (searchType == MediaType.DISK) {
            searchDiskInternal(query)
            return
        }

        // 更新 query 到状态中，确保 loadMore 等使用最新查询词
        _uiState.value = _uiState.value.copy(query = query)

        // 无论上一轮是否在加载都必须取消旧任务：搜索中换词若直接早退，
        // 旧协程会把旧词结果写回、与状态里的新词错配成「新词配旧结果」
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            updateTabState(searchType, SearchTabState(
                isLoading = true,
                hasSearched = true
            ))

            val result = withTimeoutOrNull(15_000) {
                when (searchType) {
                    MediaType.MOVIE -> traktRepository.searchMovies(query, page = 1)
                    MediaType.SHOW -> traktRepository.searchShows(query, page = 1)
                    MediaType.PERSON -> traktRepository.searchPeople(query, page = 1)
                    MediaType.DISK -> Result.failure(Exception("DISK not supported"))
                }
            }

            if (result == null) {
                // 超时，自动重试
                handleTimeoutRetry(searchType, query, retryCount = 0)
                return@launch
            }

            if (searchType == MediaType.PERSON) {
                // 人物搜索：同时用 TMDB 搜索（支持中文名），合并去重
                val tmdbResults = tmdbRepository.searchPerson(query)
                // 建立 tmdbId → popularity 索引,TMDB 搜索结果已自带 popularity 字段,无需额外请求
                val tmdbPopularityMap = tmdbResults.associate { it.id to it.popularity }
                result.onSuccess { (searchResults, totalCount) ->
                    val traktItems = searchResults.map { item ->
                        async { withTimeoutOrNull(8_000) { enrichSearchResult(item, searchType) } }
                    }.awaitAll().filterNotNull().filter { it.traktId > 0 || it.tmdbId > 0 }
                        // 用 TMDB popularity 索引补全流行度(零额外请求)
                        .map { it.copy(popularity = tmdbPopularityMap[it.tmdbId] ?: 0.0) }
                    // 收集已有的 tmdbId
                    val existingTmdbIds = traktItems.map { it.tmdbId }.toMutableSet()
                    // TMDB 独有的结果：通过 TMDB ID 反查 Trakt
                    val tmdbOnlyItems = tmdbResults.filter { it.id !in existingTmdbIds }.map { person ->
                        async {
                            withTimeoutOrNull(8_000) {
                                val traktLookup = traktRepository.searchByTmdb(person.id, MediaType.PERSON)
                                val traktPerson = traktLookup.getOrNull()?.firstOrNull()?.person
                                val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                                TraktSearchUiItem(
                                    traktId = traktPerson?.ids?.trakt ?: 0,
                                    tmdbId = person.id,
                                    title = person.original_name,
                                    displayTitle = person.name,
                                    posterUrl = profileUrl,
                                    knownForDepartment = person.known_for_department,
                                    popularity = person.popularity
                                )
                            }
                        }
                    }.awaitAll().filterNotNull().filter { it.traktId > 0 || it.tmdbId > 0 }
                    // 合并后按流行度降序排序(知名人物优先展示)
                    val merged = (traktItems + tmdbOnlyItems).sortedByDescending { it.popularity }
                    val effectiveTotal = if (merged.isEmpty()) 0 else totalCount + tmdbOnlyItems.size
                    updateTabState(searchType, SearchTabState(
                        results = merged,
                        isLoading = false,
                        totalCount = effectiveTotal,
                        currentPage = 1,
                        hasMore = traktItems.size < totalCount,
                        hasSearched = true
                    ))
                    // 同时用 TMDB 多类型搜索填充电影和剧集标签页
                    // 仅在对应 tab 未搜索过时填充,避免覆盖用户已有的搜索结果
                    val movieTabSearched = _uiState.value.movieState.hasSearched
                    val showTabSearched = _uiState.value.showState.hasSearched
                    if (!movieTabSearched || !showTabSearched) {
                        val multiResult = tmdbRepository.searchMulti(query)
                        if (multiResult != null) {
                            val movieResults = multiResult.results.filter { it.media_type == "movie" }
                            val tvResults = multiResult.results.filter { it.media_type == "tv" }
                            if (!movieTabSearched && movieResults.isNotEmpty()) {
                                val movieItems = movieResults.map { r ->
                                    TraktSearchUiItem(
                                        tmdbId = r.id,
                                        title = r.name ?: r.title ?: "",
                                        displayTitle = r.title ?: r.name ?: "",
                                        posterUrl = r.poster_path?.let { TmdbImageUrls.build(it) },
                                        year = (r.release_date ?: r.first_air_date ?: "").take(4).toIntOrNull() ?: 0
                                    )
                                }
                                updateTabState(MediaType.MOVIE, SearchTabState(
                                    results = movieItems,
                                    isLoading = false,
                                    totalCount = movieItems.size,
                                    currentPage = 1,
                                    hasMore = false,
                                    hasSearched = true
                                ))
                            }
                            if (!showTabSearched && tvResults.isNotEmpty()) {
                                val tvItems = tvResults.map { r ->
                                    TraktSearchUiItem(
                                        tmdbId = r.id,
                                        title = r.name ?: r.title ?: "",
                                        displayTitle = r.title ?: r.name ?: "",
                                        posterUrl = r.poster_path?.let { TmdbImageUrls.build(it) },
                                        year = (r.release_date ?: r.first_air_date ?: "").take(4).toIntOrNull() ?: 0
                                    )
                                }
                                updateTabState(MediaType.SHOW, SearchTabState(
                                    results = tvItems,
                                    isLoading = false,
                                    totalCount = tvItems.size,
                                    currentPage = 1,
                                    hasMore = false,
                                    hasSearched = true
                                ))
                            }
                        }
                    }
                }.onFailure { e ->
                    // Trakt 失败时仍可用 TMDB 结果
                    if (tmdbResults.isNotEmpty()) {
                        val tmdbItems = tmdbResults.map { person ->
                            async {
                                withTimeoutOrNull(8_000) {
                                    val traktLookup = traktRepository.searchByTmdb(person.id, MediaType.PERSON)
                                    val traktPerson = traktLookup.getOrNull()?.firstOrNull()?.person
                                    val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                                    TraktSearchUiItem(
                                        traktId = traktPerson?.ids?.trakt ?: 0,
                                        tmdbId = person.id,
                                        title = person.original_name,
                                        displayTitle = person.name,
                                        posterUrl = profileUrl,
                                        knownForDepartment = person.known_for_department,
                                        popularity = person.popularity
                                    )
                                }
                            }
                        }.awaitAll().filterNotNull().filter { it.traktId > 0 || it.tmdbId > 0 }
                            // 按流行度降序排序
                            .sortedByDescending { it.popularity }
                        val effectiveTotal = if (tmdbItems.isEmpty()) 0 else tmdbResults.size
                        updateTabState(searchType, SearchTabState(
                            results = tmdbItems,
                            isLoading = false,
                            totalCount = effectiveTotal,
                            currentPage = 1,
                            hasMore = false,
                            hasSearched = true
                        ))
                    } else {
                        updateTabState(searchType, SearchTabState(
                            isLoading = false,
                            error = e.toUserMessage(context, R.string.error_search_failed),
                            hasSearched = true
                        ))
                    }
                }
            } else {
                result.onSuccess { (searchResults, totalCount) ->
                    val uiItems = searchResults.map { item ->
                        async { withTimeoutOrNull(8_000) { enrichSearchResult(item, searchType) } }
                    }.awaitAll().filterNotNull().filter { it.traktId > 0 || it.tmdbId > 0 }
                    // totalCount 与实际结果同步：enrich 超时或无效项导致结果为空时，总数也应为 0
                    val effectiveTotal = if (uiItems.isEmpty()) 0 else totalCount
                    updateTabState(searchType, SearchTabState(
                        results = uiItems,
                        isLoading = false,
                        totalCount = effectiveTotal,
                        currentPage = 1,
                        hasMore = uiItems.size < effectiveTotal,
                        hasSearched = true
                    ))
                }.onFailure { e ->
                    updateTabState(searchType, SearchTabState(
                        isLoading = false,
                        error = e.toUserMessage(context, R.string.error_search_failed),
                        hasSearched = true
                    ))
                }
            }
        }
    }

    private suspend fun handleTimeoutRetry(searchType: MediaType, query: String, retryCount: Int) {
        val maxRetries = 3
        if (retryCount >= maxRetries) {
            updateTabState(searchType, SearchTabState(
                isLoading = false,
                error = context.getString(R.string.error_search_timeout),
                hasSearched = true
            ))
            return
        }

        updateTabState(searchType, SearchTabState(
            isLoading = true,
            hasSearched = true,
            retryCount = retryCount + 1,
            isRetrying = true
        ))

        // 指数退避延迟
        val delayMs = 1000L * (1 shl retryCount)
        delay(delayMs)

        val result = withTimeoutOrNull(15_000) {
            when (searchType) {
                MediaType.MOVIE -> traktRepository.searchMovies(query, page = 1)
                MediaType.SHOW -> traktRepository.searchShows(query, page = 1)
                MediaType.PERSON -> traktRepository.searchPeople(query, page = 1)
                MediaType.DISK -> Result.failure(Exception("DISK not supported"))
            }
        }

        if (result == null) {
            // 继续重试
            handleTimeoutRetry(searchType, query, retryCount + 1)
            return
        }

        // 处理搜索结果
        coroutineScope {
            when (searchType) {
                MediaType.PERSON -> {
                    val tmdbResults = tmdbRepository.searchPerson(query)
                    result.onSuccess { (searchResults, totalCount) ->
                        val traktItems = searchResults.map { item ->
                            async { withTimeoutOrNull(8_000) { enrichSearchResult(item, searchType) } }
                        }.awaitAll().filterNotNull().filter { it.traktId > 0 || it.tmdbId > 0 }
                        val existingTmdbIds = traktItems.map { it.tmdbId }.toMutableSet()
                        val tmdbOnlyItems = tmdbResults.filter { it.id !in existingTmdbIds }.map { person ->
                            async {
                                withTimeoutOrNull(8_000) {
                                    val traktLookup = traktRepository.searchByTmdb(person.id, MediaType.PERSON)
                                    val traktPerson = traktLookup.getOrNull()?.firstOrNull()?.person
                                    val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                                    TraktSearchUiItem(
                                        traktId = traktPerson?.ids?.trakt ?: 0,
                                        tmdbId = person.id,
                                        title = person.original_name,
                                        displayTitle = person.name,
                                        posterUrl = profileUrl,
                                        knownForDepartment = person.known_for_department
                                    )
                                }
                            }
                        }.awaitAll().filterNotNull().filter { it.traktId > 0 || it.tmdbId > 0 }
                        val merged = traktItems + tmdbOnlyItems
                        val effectiveTotal = if (merged.isEmpty()) 0 else totalCount + tmdbOnlyItems.size
                        updateTabState(searchType, SearchTabState(
                            results = merged,
                            isLoading = false,
                            totalCount = effectiveTotal,
                            currentPage = 1,
                            hasMore = traktItems.size < totalCount,
                            hasSearched = true
                        ))
                    }.onFailure { e ->
                        if (tmdbResults.isNotEmpty()) {
                            val tmdbItems = tmdbResults.map { person ->
                                async {
                                    withTimeoutOrNull(8_000) {
                                        val traktLookup = traktRepository.searchByTmdb(person.id, MediaType.PERSON)
                                        val traktPerson = traktLookup.getOrNull()?.firstOrNull()?.person
                                        val profileUrl = person.profile_path?.let { TmdbImageUrls.build(it) }
                                        TraktSearchUiItem(
                                            traktId = traktPerson?.ids?.trakt ?: 0,
                                            tmdbId = person.id,
                                            title = person.original_name,
                                            displayTitle = person.name,
                                            posterUrl = profileUrl,
                                            knownForDepartment = person.known_for_department
                                        )
                                    }
                                }
                            }.awaitAll().filterNotNull().filter { it.traktId > 0 || it.tmdbId > 0 }
                            val effectiveTotal = if (tmdbItems.isEmpty()) 0 else tmdbResults.size
                            updateTabState(searchType, SearchTabState(
                                results = tmdbItems,
                                isLoading = false,
                                totalCount = effectiveTotal,
                                currentPage = 1,
                                hasMore = false,
                                hasSearched = true
                            ))
                        } else {
                            updateTabState(searchType, SearchTabState(
                                isLoading = false,
                                error = e.toUserMessage(context, R.string.error_search_failed),
                                hasSearched = true
                            ))
                        }
                    }
                }
                else -> {
                    result.onSuccess { (searchResults, totalCount) ->
                        val uiItems = searchResults.map { item ->
                            async { withTimeoutOrNull(8_000) { enrichSearchResult(item, searchType) } }
                        }.awaitAll().filterNotNull().filter { it.traktId > 0 || it.tmdbId > 0 }
                        val effectiveTotal = if (uiItems.isEmpty()) 0 else totalCount
                        updateTabState(searchType, SearchTabState(
                            results = uiItems,
                            isLoading = false,
                            totalCount = effectiveTotal,
                            currentPage = 1,
                            hasMore = uiItems.size < effectiveTotal,
                            hasSearched = true
                        ))
                    }.onFailure { e ->
                        updateTabState(searchType, SearchTabState(
                            isLoading = false,
                            error = e.toUserMessage(context, R.string.error_search_failed),
                            hasSearched = true
                        ))
                    }
                }
            }
        }
    }

    fun loadMore() {
        val current = _uiState.value
        // 网盘搜索不支持加载更多
        if (current.selectedTab == MediaType.DISK) return
        val loadTab = current.selectedTab
        val loadQuery = current.query
        val tabState = current.currentTabState
        if (tabState.isLoading || tabState.isLoadingMore || !tabState.hasMore) return

        // 翻页协程纳入 searchJob 统一管理：换词/新搜索时旧翻页一并取消，
        // 否则旧词下一页会合入新词结果并污染新搜索的分页游标
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val nextPage = tabState.currentPage + 1
            updateTabState(loadTab, tabState.copy(isLoadingMore = true, loadMoreError = false))

            val result = when (loadTab) {
                MediaType.MOVIE -> traktRepository.searchMovies(loadQuery, page = nextPage)
                MediaType.SHOW -> traktRepository.searchShows(loadQuery, page = nextPage)
                MediaType.PERSON -> traktRepository.searchPeople(loadQuery, page = nextPage)
                MediaType.DISK -> Result.failure(Exception("DISK not supported"))
            }

            // 写回前校验：期间换词或切 tab，本次翻页作废（不写任何 tab 状态）
            val latest = _uiState.value
            if (latest.query != loadQuery || latest.selectedTab != loadTab) return@launch

            result.onSuccess { (searchResults, totalCount) ->
                val newItems = searchResults.map { item ->
                    async { withTimeoutOrNull(8_000) { enrichSearchResult(item, loadTab) } }
                }.awaitAll().filterNotNull().filter { it.traktId > 0 || it.tmdbId > 0 }
                // enrich 期间用户可能又切走，写回前再校验一次
                val updatedState = latest.tabStateFor(loadTab)
                val mergedResults = updatedState.results + newItems
                val effectiveTotal = if (mergedResults.isEmpty()) 0 else totalCount
                updateTabState(loadTab, updatedState.copy(
                    results = mergedResults,
                    isLoadingMore = false,
                    loadMoreError = false,
                    totalCount = effectiveTotal,
                    currentPage = nextPage,
                    hasMore = mergedResults.size < effectiveTotal
                ))
            }.onFailure {
                val updatedState = latest.tabStateFor(loadTab)
                updateTabState(loadTab, updatedState.copy(isLoadingMore = false, loadMoreError = true))
            }
        }
    }

    /** 只记录电影/剧集卡片点击；人物和网盘资源没有影视行为语义。 */
    fun recordMediaClick(item: TraktSearchUiItem, type: MediaType) {
        val mediaType = when (type) {
            MediaType.MOVIE -> "movie"
            MediaType.SHOW -> "show"
            else -> return
        }
        viewModelScope.launch {
            runCatching {
                aiProfileBehaviorRecorder.recordNow(
                    snapshot = MediaSourceSnapshot(
                        mediaType = mediaType,
                        tmdbId = item.tmdbId.takeIf { it > 0 },
                        traktId = item.traktId.takeIf { it > 0 },
                        imdbId = item.imdbId.takeIf { it.isNotBlank() },
                        title = item.displayTitle.ifBlank { item.title },
                        year = item.year,
                        genres = item.genres.split(" · ").map(String::trim).filter(String::isNotBlank),
                        publicRating = item.traktRating.takeIf { it > 0 }
                    ),
                    behavior = AiProfileBehavior.SearchClick
                )
            }
        }
    }

    private fun updateTabState(type: MediaType, state: SearchTabState) {
        _uiState.value = when (type) {
            MediaType.MOVIE -> _uiState.value.copy(movieState = state)
            MediaType.SHOW -> _uiState.value.copy(showState = state)
            MediaType.PERSON -> _uiState.value.copy(personState = state)
            MediaType.DISK -> _uiState.value
        }
        // 五处搜索失败都从这个函数落进 error，触感挂在这里就不会漏掉某一处；
        // 成功不发 —— 结果列表铺出来本身就是反馈
        if (state.error != null) hapticOutcomeEmitter.emit(HapticOutcome.FAILURE)
    }

    private suspend fun enrichSearchResult(result: TraktSearchResult, type: MediaType): TraktSearchUiItem {
        return enrichSemaphore.withPermit {
            when (type) {
                MediaType.MOVIE -> {
                    val movie = result.movie ?: return@withPermit TraktSearchUiItem()
                    if (movie.ids.tmdb <= 0) {
                        TraktSearchUiItem(
                            traktId = movie.ids.trakt,
                            tmdbId = 0,
                            title = movie.title,
                            displayTitle = movie.title,
                            year = movie.year,
                            genres = movie.genres.joinToString(" · "),
                            posterUrl = movie.posterPath,
                            imdbId = movie.ids.imdb,
                            traktRating = movie.rating
                        )
                    } else {
                        val enrichment = tmdbRepository.enrichMovie(movie.ids.tmdb, movie.title, movie.year)
                        TraktSearchUiItem(
                            traktId = movie.ids.trakt,
                            tmdbId = movie.ids.tmdb,
                            title = movie.title,
                            displayTitle = enrichment.chineseTitle,
                            year = enrichment.year,
                            genres = enrichment.genres,
                            posterUrl = enrichment.posterUrl,
                            imdbId = movie.ids.imdb,
                            traktRating = movie.rating
                        )
                    }
                }
                MediaType.SHOW -> {
                    val show = result.show ?: return@withPermit TraktSearchUiItem()
                    if (show.ids.tmdb <= 0) {
                        TraktSearchUiItem(
                            traktId = show.ids.trakt,
                            tmdbId = 0,
                            title = show.title,
                            displayTitle = show.title,
                            year = show.year,
                            genres = show.genres.joinToString(" · "),
                            posterUrl = null,
                            imdbId = show.ids.imdb,
                            traktRating = show.rating
                        )
                    } else {
                        val enrichment = tmdbRepository.enrichTv(show.ids.tmdb, show.title, show.year)
                        TraktSearchUiItem(
                            traktId = show.ids.trakt,
                            tmdbId = show.ids.tmdb,
                            title = show.title,
                            displayTitle = enrichment.chineseTitle,
                            year = enrichment.year,
                            genres = enrichment.genres,
                            posterUrl = enrichment.posterUrl,
                            imdbId = show.ids.imdb,
                            traktRating = show.rating
                        )
                    }
                }
                MediaType.PERSON -> {
                    val person = result.person ?: return@withPermit TraktSearchUiItem()
                    val tmdbId = person.ids.tmdb
                    val tmdbPerson = if (tmdbId > 0) tmdbRepository.getPersonDetail(tmdbId) else null
                    val profileUrl = tmdbPerson?.profile_path?.let { TmdbImageUrls.build(it) }
                    TraktSearchUiItem(
                        traktId = person.ids.trakt,
                        tmdbId = tmdbId,
                        title = person.name,
                        displayTitle = person.name,
                        posterUrl = profileUrl,
                        knownForDepartment = tmdbPerson?.known_for_department ?: ""
                    )
                }
                MediaType.DISK -> TraktSearchUiItem()
            }
        }
    }

    // 网盘搜索
    fun searchDiskInternal(query: String) {
        if (query.isBlank()) return
        _uiState.value = _uiState.value.copy(query = query)

        val diskState = _uiState.value.diskState
        if (diskState.isLoading) return

        viewModelScope.launch {
            val storageEnabledSources = resourceRepository.getEnabledSources()
            val customSources = resourceRepository.getEnabledCustomSources()
            val customNames = customSources.associate { it.id to it.name }
            val totalSources = storageEnabledSources.size
            _uiState.value = _uiState.value.copy(
                diskState = diskState.copy(
                    isLoading = true, hasSearched = true, error = null,
                    totalSources = totalSources, completedSources = 0,
                    availableSources = storageEnabledSources.toList(),
                    enabledSources = storageEnabledSources,
                    customSourceNames = customNames
                )
            )

            try {
                resourceRepository.searchResourcesFlow(
                    keyword = query,
                    onSourceComplete = {
                        val current = _uiState.value.diskState
                        _uiState.value = _uiState.value.copy(
                            diskState = current.copy(completedSources = current.completedSources + 1)
                        )
                    }
                ).collect { items ->
                    // 搜索期间只更新资源列表，保持 isLoading = true 以显示进度
                    _uiState.value = _uiState.value.copy(
                        diskState = _uiState.value.diskState.copy(
                            resources = items
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 原来这里整段吞掉：搜索失败与「一条都没搜到」在界面上完全同形，用户只看到
                // 「0 个结果」。落进 diskState.error 之后界面会画错误态（resources 为空时），
                // 触感同时给一记 reject
                _uiState.value = _uiState.value.copy(
                    diskState = _uiState.value.diskState.copy(
                        error = e.toUserMessage(context, R.string.error_search_failed)
                    )
                )
                hapticOutcomeEmitter.emit(HapticOutcome.FAILURE)
            } finally {
                if (_uiState.value.diskState.isLoading) {
                    _uiState.value = _uiState.value.copy(
                        diskState = _uiState.value.diskState.copy(isLoading = false)
                    )
                }
            }
        }
    }

    fun toggleDiskSource(source: String) {
        val current = _uiState.value.diskState.enabledSources
        val newSources = if (source in current) current - source else current + source
        _uiState.value = _uiState.value.copy(
            diskState = _uiState.value.diskState.copy(enabledSources = newSources)
        )
    }

    fun toggleDiskType(type: com.tracktosearch.data.remote.dto.DiskType) {
        val current = _uiState.value.diskState.enabledDiskTypes
        val newTypes = if (type in current) current - type else current + type
        _uiState.value = _uiState.value.copy(
            diskState = _uiState.value.diskState.copy(enabledDiskTypes = newTypes)
        )
    }
}
