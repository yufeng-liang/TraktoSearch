package com.tracktosearch.ui.screen.traktsearch

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.dto.ResourceType
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.remote.trakt.dto.TraktSearchResult
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.tracktosearch.R
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
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
    val knownForDepartment: String = ""
)

@Immutable
data class SearchTabState(
    val results: List<TraktSearchUiItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
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
    val typeFilter: ResourceType = ResourceType.ALL,
    val diskTypeFilter: com.tracktosearch.data.remote.dto.DiskType? = null,
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
        get() = when (selectedTab) {
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
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
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

    /**
     * 初始化搜索：从 Composable 传入正确的 type 和 query。
     * inline 模式下 savedStateHandle 中没有 type，需要通过此方法同步正确的类型。
     */
    fun initSearch(query: String, type: MediaType) {
        val current = _uiState.value
        val anySearched = current.movieState.hasSearched || current.showState.hasSearched ||
                current.personState.hasSearched || current.diskState.hasSearched
        // 已有搜索结果且查询词相同，不重置（从详情页返回时保持状态）
        // 查询词不同则是新的搜索，需要重置
        if (anySearched && current.query == query) return

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

        val tabState = _uiState.value.currentTabState
        if (tabState.isLoading) return

        // 取消上一次未完成的搜索，避免旧结果覆盖新结果
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
                result.onSuccess { (searchResults, totalCount) ->
                    val traktItems = searchResults.map { item ->
                        async { withTimeoutOrNull(8_000) { enrichSearchResult(item, searchType) } }
                    }.awaitAll().filterNotNull()
                    // 收集已有的 tmdbId
                    val existingTmdbIds = traktItems.map { it.tmdbId }.toMutableSet()
                    // TMDB 独有的结果：通过 TMDB ID 反查 Trakt
                    val tmdbOnlyItems = tmdbResults.filter { it.id !in existingTmdbIds }.map { person ->
                        async {
                            withTimeoutOrNull(8_000) {
                                val traktLookup = traktRepository.searchByTmdb(person.id, MediaType.PERSON)
                                val traktPerson = traktLookup.getOrNull()?.firstOrNull()?.person
                                val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
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
                    }.awaitAll().filterNotNull()
                    val merged = traktItems + tmdbOnlyItems
                    val mergedTotal = totalCount + tmdbOnlyItems.size
                    updateTabState(searchType, SearchTabState(
                        results = merged,
                        isLoading = false,
                        totalCount = mergedTotal,
                        currentPage = 1,
                        hasMore = traktItems.size < totalCount,
                        hasSearched = true
                    ))
                    // 同时用 TMDB 多类型搜索填充电影和剧集标签页
                    val multiResult = tmdbRepository.searchMulti(query)
                    if (multiResult != null) {
                        val movieResults = multiResult.results.filter { it.media_type == "movie" }
                        val tvResults = multiResult.results.filter { it.media_type == "tv" }
                        if (movieResults.isNotEmpty()) {
                            val movieItems = movieResults.map { r ->
                                TraktSearchUiItem(
                                    tmdbId = r.id,
                                    title = r.name ?: r.title ?: "",
                                    displayTitle = r.title ?: r.name ?: "",
                                    posterUrl = r.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" },
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
                        if (tvResults.isNotEmpty()) {
                            val tvItems = tvResults.map { r ->
                                TraktSearchUiItem(
                                    tmdbId = r.id,
                                    title = r.name ?: r.title ?: "",
                                    displayTitle = r.title ?: r.name ?: "",
                                    posterUrl = r.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" },
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
                }.onFailure { e ->
                    // Trakt 失败时仍可用 TMDB 结果
                    if (tmdbResults.isNotEmpty()) {
                        val tmdbItems = tmdbResults.map { person ->
                            async {
                                withTimeoutOrNull(8_000) {
                                    val traktLookup = traktRepository.searchByTmdb(person.id, MediaType.PERSON)
                                    val traktPerson = traktLookup.getOrNull()?.firstOrNull()?.person
                                    val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
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
                        }.awaitAll().filterNotNull()
                        updateTabState(searchType, SearchTabState(
                            results = tmdbItems,
                            isLoading = false,
                            totalCount = tmdbResults.size,
                            currentPage = 1,
                            hasMore = false,
                            hasSearched = true
                        ))
                    } else {
                        updateTabState(searchType, SearchTabState(
                            isLoading = false,
                            error = e.message ?: context.getString(R.string.error_search_failed),
                            hasSearched = true
                        ))
                    }
                }
            } else {
                result.onSuccess { (searchResults, totalCount) ->
                    val uiItems = searchResults.map { item ->
                        async { withTimeoutOrNull(8_000) { enrichSearchResult(item, searchType) } }
                    }.awaitAll().filterNotNull()
                    updateTabState(searchType, SearchTabState(
                        results = uiItems,
                        isLoading = false,
                        totalCount = totalCount,
                        currentPage = 1,
                        hasMore = uiItems.size < totalCount,
                        hasSearched = true
                    ))
                }.onFailure { e ->
                    updateTabState(searchType, SearchTabState(
                        isLoading = false,
                        error = e.message ?: context.getString(R.string.error_search_failed),
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
                        }.awaitAll().filterNotNull()
                        val existingTmdbIds = traktItems.map { it.tmdbId }.toMutableSet()
                        val tmdbOnlyItems = tmdbResults.filter { it.id !in existingTmdbIds }.map { person ->
                            async {
                                withTimeoutOrNull(8_000) {
                                    val traktLookup = traktRepository.searchByTmdb(person.id, MediaType.PERSON)
                                    val traktPerson = traktLookup.getOrNull()?.firstOrNull()?.person
                                    val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
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
                        }.awaitAll().filterNotNull()
                        val merged = traktItems + tmdbOnlyItems
                        val mergedTotal = totalCount + tmdbOnlyItems.size
                        updateTabState(searchType, SearchTabState(
                            results = merged,
                            isLoading = false,
                            totalCount = mergedTotal,
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
                                        val profileUrl = person.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
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
                            }.awaitAll().filterNotNull()
                            updateTabState(searchType, SearchTabState(
                                results = tmdbItems,
                                isLoading = false,
                                totalCount = tmdbResults.size,
                                currentPage = 1,
                                hasMore = false,
                                hasSearched = true
                            ))
                        } else {
                            updateTabState(searchType, SearchTabState(
                                isLoading = false,
                                error = e.message ?: context.getString(R.string.error_search_failed),
                                hasSearched = true
                            ))
                        }
                    }
                }
                else -> {
                    result.onSuccess { (searchResults, totalCount) ->
                        val uiItems = searchResults.map { item ->
                            async { withTimeoutOrNull(8_000) { enrichSearchResult(item, searchType) } }
                        }.awaitAll().filterNotNull()
                        updateTabState(searchType, SearchTabState(
                            results = uiItems,
                            isLoading = false,
                            totalCount = totalCount,
                            currentPage = 1,
                            hasMore = uiItems.size < totalCount,
                            hasSearched = true
                        ))
                    }.onFailure { e ->
                        updateTabState(searchType, SearchTabState(
                            isLoading = false,
                            error = e.message ?: context.getString(R.string.error_search_failed),
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
        val tabState = current.currentTabState
        if (tabState.isLoading || tabState.isLoadingMore || !tabState.hasMore) return

        viewModelScope.launch {
            val nextPage = tabState.currentPage + 1
            updateTabState(current.selectedTab, tabState.copy(isLoadingMore = true))

            val result = when (current.selectedTab) {
                MediaType.MOVIE -> traktRepository.searchMovies(current.query, page = nextPage)
                MediaType.SHOW -> traktRepository.searchShows(current.query, page = nextPage)
                MediaType.PERSON -> traktRepository.searchPeople(current.query, page = nextPage)
                MediaType.DISK -> Result.failure(Exception("DISK not supported"))
            }

            result.onSuccess { (searchResults, totalCount) ->
                val newItems = searchResults.map { item ->
                    async { withTimeoutOrNull(8_000) { enrichSearchResult(item, current.selectedTab) } }
                }.awaitAll().filterNotNull()
                val updatedState = _uiState.value.currentTabState
                updateTabState(current.selectedTab, updatedState.copy(
                    results = updatedState.results + newItems,
                    isLoadingMore = false,
                    totalCount = totalCount,
                    currentPage = nextPage,
                    hasMore = (updatedState.results.size + newItems.size) < totalCount
                ))
            }.onFailure {
                val updatedState = _uiState.value.currentTabState
                updateTabState(current.selectedTab, updatedState.copy(isLoadingMore = false))
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
                    val profileUrl = tmdbPerson?.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
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
            val totalSources = resourceRepository.getEnabledSources().size
            _uiState.value = _uiState.value.copy(
                diskState = diskState.copy(
                    isLoading = true, hasSearched = true, error = null,
                    totalSources = totalSources, completedSources = 0
                )
            )

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
            if (_uiState.value.diskState.isLoading) {
                _uiState.value = _uiState.value.copy(
                    diskState = _uiState.value.diskState.copy(isLoading = false)
                )
            }
        }
    }

    fun setDiskTypeFilter(filter: com.tracktosearch.data.remote.dto.DiskType?) {
        _uiState.value = _uiState.value.copy(
            diskState = _uiState.value.diskState.copy(diskTypeFilter = filter)
        )
    }

    fun setDiskResourceTypeFilter(type: ResourceType) {
        _uiState.value = _uiState.value.copy(
            diskState = _uiState.value.diskState.copy(typeFilter = type)
        )
    }
}
