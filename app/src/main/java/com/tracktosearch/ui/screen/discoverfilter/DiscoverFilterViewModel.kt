package com.tracktosearch.ui.screen.discoverfilter

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@Immutable
data class DiscoverFilterUiState(
    val type: TmdbRepository.DiscoverType = TmdbRepository.DiscoverType.MOVIE,
    val selectedGenreIds: Set<Int> = emptySet(),
    val selectedCountries: Set<String> = emptySet(),
    val selectedKeywordIds: Set<Int> = emptySet(),
    val selectedDecadeKeys: Set<String> = emptySet(),
    val voteAverageMin: Float = 0f,
    val voteAverageMax: Float = 10f,
    val sortBy: TmdbRepository.DiscoverSort = TmdbRepository.DiscoverSort.POPULARITY_DESC,
    val hideWatched: Boolean = false,
    val showAdvanced: Boolean = false,
    val items: List<TmdbSearchResult> = emptyList(),
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val totalResults: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false
)

@HiltViewModel
class DiscoverFilterViewModel @Inject constructor(
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val tokenStorage: TokenStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverFilterUiState())
    val uiState: StateFlow<DiscoverFilterUiState> = _uiState.asStateFlow()

    /** 上次搜索时使用的筛选条件快照，用于判断条件是否变化 */
    private var lastSearchSnapshot: FilterSnapshot? = null

    private data class FilterSnapshot(
        val type: TmdbRepository.DiscoverType,
        val genreIds: Set<Int>,
        val countries: Set<String>,
        val keywordIds: Set<Int>,
        val decadeKeys: Set<String>,
        val voteMin: Float,
        val voteMax: Float,
        val sortBy: TmdbRepository.DiscoverSort,
        val hideWatched: Boolean
    )

    private fun currentSnapshot(): FilterSnapshot = FilterSnapshot(
        type = _uiState.value.type,
        genreIds = _uiState.value.selectedGenreIds,
        countries = _uiState.value.selectedCountries,
        keywordIds = _uiState.value.selectedKeywordIds,
        decadeKeys = _uiState.value.selectedDecadeKeys,
        voteMin = _uiState.value.voteAverageMin,
        voteMax = _uiState.value.voteAverageMax,
        sortBy = _uiState.value.sortBy,
        hideWatched = _uiState.value.hideWatched
    )

    /** 筛选条件是否相比上次搜索有变化 */
    private fun hasFilterChanged(): Boolean = lastSearchSnapshot != currentSnapshot()

    /**
     * 每种类型（电影/电视剧）各自的筛选状态。
     * 切换类型时保存当前类型的状态，恢复目标类型的状态，实现独立但保留。
     */
    private data class TypeFilterState(
        val genreIds: Set<Int> = emptySet(),
        val countries: Set<String> = emptySet(),
        val keywordIds: Set<Int> = emptySet(),
        val decadeKeys: Set<String> = emptySet(),
        val voteMin: Float = 0f,
        val voteMax: Float = 10f,
        val sortBy: TmdbRepository.DiscoverSort = TmdbRepository.DiscoverSort.POPULARITY_DESC,
        val hideWatched: Boolean = false,
        val showAdvanced: Boolean = false
    )

    /** 两种类型各自的筛选状态缓存 */
    private val typeFilterStates = mutableMapOf(
        TmdbRepository.DiscoverType.MOVIE to TypeFilterState(),
        TmdbRepository.DiscoverType.SHOW to TypeFilterState()
    )

    /** 全局想看/已看 ID 缓存，用于"仅展示未标看过"过滤 */
    private val _watchlistWatchedIds = MutableStateFlow<TraktRepository.WatchlistWatchedIds?>(null)
    val watchlistWatchedIds: StateFlow<TraktRepository.WatchlistWatchedIds?> = _watchlistWatchedIds.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = tokenStorage.accessToken
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 年代选项列表（动态生成，含当前年份） */
    val decadeOptions: List<DiscoverFilterConstants.DecadeOption> by lazy {
        DiscoverFilterConstants.decadeOptions(Calendar.getInstance().get(Calendar.YEAR))
    }

    init {
        loadWatchlistWatchedIds()
    }

    private fun loadWatchlistWatchedIds() {
        viewModelScope.launch {
            traktRepository.loadWatchlistWatchedIds()
            _watchlistWatchedIds.value = traktRepository.getWatchlistWatchedIds()
        }
    }

    /**
     * 切换电影/电视剧类型：保存当前类型的筛选状态，恢复目标类型的筛选状态。
     * 两种类型的筛选互相独立但各自保留，切换回来时还原之前的选择。
     */
    fun switchType(type: TmdbRepository.DiscoverType) {
        if (_uiState.value.type == type) return
        val oldType = _uiState.value.type
        // 保存当前类型的筛选状态
        typeFilterStates[oldType] = TypeFilterState(
            genreIds = _uiState.value.selectedGenreIds,
            countries = _uiState.value.selectedCountries,
            keywordIds = _uiState.value.selectedKeywordIds,
            decadeKeys = _uiState.value.selectedDecadeKeys,
            voteMin = _uiState.value.voteAverageMin,
            voteMax = _uiState.value.voteAverageMax,
            sortBy = _uiState.value.sortBy,
            hideWatched = _uiState.value.hideWatched,
            showAdvanced = _uiState.value.showAdvanced
        )
        // 恢复目标类型的筛选状态
        val saved = typeFilterStates[type] ?: TypeFilterState()
        lastSearchSnapshot = null
        _uiState.value = _uiState.value.copy(
            type = type,
            selectedGenreIds = saved.genreIds,
            selectedCountries = saved.countries,
            selectedKeywordIds = saved.keywordIds,
            selectedDecadeKeys = saved.decadeKeys,
            voteAverageMin = saved.voteMin,
            voteAverageMax = saved.voteMax,
            sortBy = saved.sortBy,
            hideWatched = saved.hideWatched,
            showAdvanced = saved.showAdvanced,
            items = emptyList(),
            currentPage = 0,
            totalPages = 0,
            totalResults = 0,
            hasSearched = false,
            error = null
        )
    }

    fun toggleGenre(genreId: Int) {
        val current = _uiState.value.selectedGenreIds
        _uiState.value = _uiState.value.copy(
            selectedGenreIds = if (genreId in current) current - genreId else current + genreId
        )
    }

    fun toggleCountry(countryCode: String) {
        val current = _uiState.value.selectedCountries
        _uiState.value = _uiState.value.copy(
            selectedCountries = if (countryCode in current) current - countryCode else current + countryCode
        )
    }

    fun toggleKeyword(keywordId: Int) {
        val current = _uiState.value.selectedKeywordIds
        _uiState.value = _uiState.value.copy(
            selectedKeywordIds = if (keywordId in current) current - keywordId else current + keywordId
        )
    }

    fun toggleDecade(key: String) {
        val current = _uiState.value.selectedDecadeKeys
        // "全部"选项（key="0-0"）清空筛选
        val allOption = decadeOptions.firstOrNull { it.isAll }
        if (allOption != null && key == allOption.key) {
            _uiState.value = _uiState.value.copy(selectedDecadeKeys = emptySet())
        } else {
            _uiState.value = _uiState.value.copy(
                selectedDecadeKeys = if (key in current) current - key else current + key
            )
        }
    }

    fun setVoteRange(min: Float, max: Float) {
        _uiState.value = _uiState.value.copy(
            voteAverageMin = min.coerceIn(0f, 10f),
            voteAverageMax = max.coerceIn(0f, 10f)
        )
    }

    fun setSortBy(sort: TmdbRepository.DiscoverSort) {
        _uiState.value = _uiState.value.copy(sortBy = sort)
    }

    fun toggleHideWatched() {
        _uiState.value = _uiState.value.copy(hideWatched = !_uiState.value.hideWatched)
    }

    fun toggleAdvanced() {
        _uiState.value = _uiState.value.copy(showAdvanced = !_uiState.value.showAdvanced)
    }

    /** 收起高级筛选面板（滚动结果列表时调用） */
    fun collapseAdvanced() {
        if (_uiState.value.showAdvanced) {
            _uiState.value = _uiState.value.copy(showAdvanced = false)
        }
    }

    fun resetFilters() {
        lastSearchSnapshot = null
        _uiState.value = _uiState.value.copy(
            selectedGenreIds = emptySet(),
            selectedCountries = emptySet(),
            selectedKeywordIds = emptySet(),
            selectedDecadeKeys = emptySet(),
            voteAverageMin = 0f,
            voteAverageMax = 10f,
            sortBy = TmdbRepository.DiscoverSort.POPULARITY_DESC,
            hideWatched = false,
            items = emptyList(),
            currentPage = 0,
            totalPages = 0,
            totalResults = 0,
            hasSearched = false,
            error = null
        )
    }

    /**
     * 计算选中年代对应的日期范围（多选合并为连续范围）。
     * TMDB Discover 只支持连续日期范围，多选不连续年代会合并为最小起始到最大结束。
     */
    private fun computeDateRange(): Pair<String?, String?> {
        val keys = _uiState.value.selectedDecadeKeys
        if (keys.isEmpty()) return null to null
        val options = decadeOptions.filter { it.key in keys }
        if (options.isEmpty()) return null to null

        var minYear = Int.MAX_VALUE
        var maxYear = Int.MIN_VALUE
        for (opt in options) {
            val s = if (opt.startYear == 0) 1900 else opt.startYear
            val e = if (opt.endYear == 0) 1959 else opt.endYear
            if (s < minYear) minYear = s
            if (e > maxYear) maxYear = e
        }
        val start = "${minYear}-01-01"
        val end = "${maxYear}-12-31"
        return start to end
    }

    /** 应用筛选条件，发起搜索。筛选条件未变化时跳过（避免重复请求） */
    fun search() {
        if (_uiState.value.isLoading) return
        // 条件未变化且有结果：不刷新
        if (!hasFilterChanged() && _uiState.value.hasSearched && _uiState.value.items.isNotEmpty()) return
        lastSearchSnapshot = currentSnapshot()
        _uiState.value = _uiState.value.copy(
            items = emptyList(),
            currentPage = 0,
            totalPages = 0,
            totalResults = 0,
            isLoading = true,
            error = null,
            hasSearched = true
        )
        loadPage(1)
    }

    /** 加载下一页（滚动到底部时调用） */
    fun loadMore() {
        val s = _uiState.value
        if (s.isLoading || s.isLoadingMore) return
        if (s.currentPage >= s.totalPages) return
        loadPage(s.currentPage + 1)
    }

    private fun loadPage(page: Int) {
        viewModelScope.launch {
            val s = _uiState.value
            val (dateStart, dateEnd) = computeDateRange()
            val filter = TmdbRepository.DiscoverFilter(
                type = s.type,
                genreIds = s.selectedGenreIds.toList(),
                originCountries = s.selectedCountries.toList(),
                keywordIds = s.selectedKeywordIds.toList(),
                voteAverageMin = s.voteAverageMin,
                voteAverageMax = s.voteAverageMax,
                releaseDateStart = dateStart,
                releaseDateEnd = dateEnd,
                sortBy = s.sortBy,
                hideWatched = s.hideWatched
            )
            try {
                val result = tmdbRepository.discover(filter, page)
                // "仅展示未标看过"：客户端过滤
                val filtered = if (s.hideWatched && _watchlistWatchedIds.value != null) {
                    val wl = _watchlistWatchedIds.value!!
                    result.items.filter { item ->
                        val mediaType = if (s.type == TmdbRepository.DiscoverType.MOVIE) MediaType.MOVIE else MediaType.SHOW
                        !wl.isWatched(null, item.id, mediaType)
                    }
                } else result.items

                val existing = if (page == 1) emptyList() else _uiState.value.items
                _uiState.value = _uiState.value.copy(
                    items = existing + filtered,
                    currentPage = result.totalPages.coerceAtMost(page),
                    totalPages = result.totalPages,
                    totalResults = result.totalResults,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = e.message
                )
            }
        }
    }
}
