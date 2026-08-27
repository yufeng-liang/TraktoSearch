package com.tracktosearch.ui.screen.discoverfilter

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import javax.inject.Inject

@Immutable
data class DiscoverFilterUiState(
    val type: TmdbRepository.DiscoverType = TmdbRepository.DiscoverType.MOVIE,
    val selectedGenreIds: Set<Int> = emptySet(),
    val selectedCountries: Set<String> = emptySet(),
    val selectedKeywordIds: Set<Int> = emptySet(),
    val selectedDecadeKeys: Set<String> = emptySet(),
    /**
     * 选中年代不连续时实际生效的年份范围（起始年 to 结束年），连续或单选时为 null。
     *
     * TMDB 的日期筛选只有一个连续区间（gte/lte），选「2026 + 1990年代」只能合并成
     * 1990-01-01..2026-12-31，中间没选的 2000/2010 年代也会进结果。
     * UI 据此把实际范围标出来，否则用户以为只筛了自己点的那两格。
     */
    val decadeSpanHint: Pair<Int, Int>? = null,
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
    private val sessionModeManager: SessionModeManager,
    val posterColorExtractor: PosterColorExtractor,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverFilterUiState())
    val uiState: StateFlow<DiscoverFilterUiState> = _uiState.asStateFlow()

    /** 上次搜索时使用的筛选条件快照，用于判断条件是否变化 */
    private var lastSearchSnapshot: FilterSnapshot? = null

    /**
     * 当前在飞的分页请求。
     *
     * 切类型/重新搜索前取消：不取消的话旧请求回来会把结果写进已经换了条件的列表里
     * （切到剧集后电影结果才到，就混进剧集列表）。
     */
    private var searchJob: Job? = null

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

    private fun DiscoverFilterUiState.toSnapshot(): FilterSnapshot = FilterSnapshot(
        type = type,
        genreIds = selectedGenreIds,
        countries = selectedCountries,
        keywordIds = selectedKeywordIds,
        decadeKeys = selectedDecadeKeys,
        voteMin = voteAverageMin,
        voteMax = voteAverageMax,
        sortBy = sortBy,
        hideWatched = hideWatched
    )

    private fun currentSnapshot(): FilterSnapshot = _uiState.value.toSnapshot()

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

    /**
     * 每种类型上次搜到的结果，连同当时的筛选快照一起存。
     *
     * Tab 来回切换是最常见的操作，之前每次切换都清空结果重新下载，网速差时来回都是骨架屏。
     * 存快照是为了校验：还原出来的筛选条件必须和缓存那次搜索一致，否则宁可重新搜。
     */
    private data class TypeResultCache(
        val items: List<TmdbSearchResult>,
        val currentPage: Int,
        val totalPages: Int,
        val totalResults: Int,
        val snapshot: FilterSnapshot
    )

    private val typeResultCaches = mutableMapOf<TmdbRepository.DiscoverType, TypeResultCache>()

    /** 全局想看/已看 ID 缓存，用于"仅展示未标看过"过滤 */
    private val _watchlistWatchedIds = MutableStateFlow<TraktRepository.WatchlistWatchedIds?>(null)
    val watchlistWatchedIds: StateFlow<TraktRepository.WatchlistWatchedIds?> = _watchlistWatchedIds.asStateFlow()

    /** 只有 Trakt 已连接时才显示并使用想看/已看筛选。 */
    val isLoggedIn: StateFlow<Boolean> = sessionModeManager.traktConnected

    /** 年代选项列表（动态生成，含当前年份） */
    val decadeOptions: List<DiscoverFilterConstants.DecadeOption> by lazy {
        DiscoverFilterConstants.decadeOptions(Calendar.getInstance().get(Calendar.YEAR))
    }

    init {
        loadWatchlistWatchedIds()
        // 首屏搜索在这里发，不等第一次组合后的 LaunchedEffect：
        // 那样第一帧 isLoading 还是 false，用户会先看到一帧空白再看到骨架屏
        search()
    }

    private fun loadWatchlistWatchedIds() {
        viewModelScope.launch {
            if (!sessionModeManager.traktConnected.value) {
                _watchlistWatchedIds.value = TraktRepository.WatchlistWatchedIds()
                return@launch
            }
            // 仓库层网络失败会向上抛（约定由调用方兜底）；失败仅保留现有状态，
            // 未捕获会把协程异常抛到全局 handler 直接杀死进程（断网时必现）
            try {
                traktRepository.loadWatchlistWatchedIds()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("FilterVM", "loadWatchlistWatchedIds failed: ${e.message}")
            }
            _watchlistWatchedIds.value = traktRepository.getWatchlistWatchedIds()
        }
    }

    /**
     * 切换电影/电视剧类型：保存当前类型的筛选状态与结果，恢复目标类型的。
     * 两种类型的筛选互相独立但各自保留，切换回来时还原之前的选择。
     *
     * 结果也一起还原：条件没变就直接上屏旧结果，屏幕紧跟着调的 search() 会被
     * "条件未变且已有结果"挡掉，来回切 Tab 不再重新下载。
     */
    fun switchType(type: TmdbRepository.DiscoverType) {
        val before = _uiState.value
        if (before.type == type) return
        val oldType = before.type
        // 保存当前类型的筛选状态
        typeFilterStates[oldType] = TypeFilterState(
            genreIds = before.selectedGenreIds,
            countries = before.selectedCountries,
            keywordIds = before.selectedKeywordIds,
            decadeKeys = before.selectedDecadeKeys,
            voteMin = before.voteAverageMin,
            voteMax = before.voteAverageMax,
            sortBy = before.sortBy,
            hideWatched = before.hideWatched,
            showAdvanced = before.showAdvanced
        )
        // 保存当前类型的结果。只在这次结果确实搜出来过时存，翻页进度一并带上
        val leavingSnapshot = lastSearchSnapshot
        if (leavingSnapshot != null && before.hasSearched && before.items.isNotEmpty()) {
            typeResultCaches[oldType] = TypeResultCache(
                items = before.items,
                currentPage = before.currentPage,
                totalPages = before.totalPages,
                totalResults = before.totalResults,
                snapshot = leavingSnapshot
            )
        }
        // 旧类型的请求还在飞就丢掉：它回来时会把结果写进新类型的列表（电影结果混进剧集）
        searchJob?.cancel()
        // 恢复目标类型的筛选状态
        val saved = typeFilterStates[type] ?: TypeFilterState()
        val restored = before.copy(
            type = type,
            selectedGenreIds = saved.genreIds,
            selectedCountries = saved.countries,
            selectedKeywordIds = saved.keywordIds,
            selectedDecadeKeys = saved.decadeKeys,
            decadeSpanHint = decadeSpanHintFor(saved.decadeKeys),
            voteAverageMin = saved.voteMin,
            voteAverageMax = saved.voteMax,
            sortBy = saved.sortBy,
            hideWatched = saved.hideWatched,
            showAdvanced = saved.showAdvanced,
            items = emptyList(),
            currentPage = 0,
            totalPages = 0,
            totalResults = 0,
            isLoading = false,
            isLoadingMore = false,
            hasSearched = false,
            error = null
        )
        val cached = typeResultCaches[type]
        if (cached != null && cached.snapshot == restored.toSnapshot()) {
            lastSearchSnapshot = cached.snapshot
            _uiState.value = restored.copy(
                items = cached.items,
                currentPage = cached.currentPage,
                totalPages = cached.totalPages,
                totalResults = cached.totalResults,
                hasSearched = true
            )
        } else {
            lastSearchSnapshot = null
            _uiState.value = restored
        }
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
        val next = if (allOption != null && key == allOption.key) {
            emptySet()
        } else {
            if (key in current) current - key else current + key
        }
        _uiState.value = _uiState.value.copy(
            selectedDecadeKeys = next,
            decadeSpanHint = decadeSpanHintFor(next)
        )
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

    /**
     * 只展开高级面板，已展开时什么也不做。
     *
     * 评分 chip 用：点它是想调评分，面板开着时再调用 toggleAdvanced 会把面板收起来，
     * 用户看起来就是"点了没反应"。
     */
    fun expandAdvanced() {
        if (!_uiState.value.showAdvanced) {
            _uiState.value = _uiState.value.copy(showAdvanced = true)
        }
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
            decadeSpanHint = null,
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
     * 选项实际覆盖的年份区间。
     *
     * "更早"选项存的是 startYear=0，代表 1900 以来；endYear=0 只出现在"全部"上（不参与日期计算）。
     */
    private fun effectiveYears(opt: DiscoverFilterConstants.DecadeOption): IntRange {
        val start = if (opt.startYear == 0) EARLIEST_YEAR else opt.startYear
        val end = if (opt.endYear == 0) EARLIER_OPTION_END_YEAR else opt.endYear
        return start..end
    }

    /** 选中的年代合并成的连续年份区间，没有有效选项时返回 null */
    private fun mergedYearRange(keys: Set<String>): IntRange? {
        val options = decadeOptions.filter { it.key in keys && !it.isAll }
        if (options.isEmpty()) return null
        val ranges = options.map { effectiveYears(it) }
        return ranges.minOf { it.first }..ranges.maxOf { it.last }
    }

    /**
     * 选中年代之间有没有被"顺带筛进来"的年份。
     *
     * 有缺口才返回实际范围：连续多选（如 1990年代 + 2000年代）合并出来的区间和用户点的一致，
     * 提示反而是噪音。
     */
    private fun decadeSpanHintFor(keys: Set<String>): Pair<Int, Int>? {
        val merged = mergedYearRange(keys) ?: return null
        val selectedRanges = decadeOptions.filter { it.key in keys && !it.isAll }.map { effectiveYears(it) }
        val fullyCovered = merged.all { year -> selectedRanges.any { year in it } }
        return if (fullyCovered) null else merged.first to merged.last
    }

    /**
     * 计算选中年代对应的日期范围（多选合并为连续范围）。
     * TMDB Discover 只支持连续日期范围，多选不连续年代会合并为最小起始到最大结束，
     * 中间被顺带筛进来的年份由 [DiscoverFilterUiState.decadeSpanHint] 在 UI 上标出。
     */
    private fun computeDateRange(): Pair<String?, String?> {
        val merged = mergedYearRange(_uiState.value.selectedDecadeKeys) ?: return null to null
        return "${merged.first}-01-01" to "${merged.last}-12-31"
    }

    /** 应用筛选条件，发起搜索。筛选条件未变化时跳过（避免重复请求） */
    fun search() {
        // 加载中且条件没变：同一个请求不重复发。
        // 条件变了则必须放行——首屏还在加载时改条件点「应用」，早退会把这次改动直接丢掉，
        // 之后也没有补发时机，用户看到的是筛选条件亮着但结果是旧的。旧请求由 loadPage 取消。
        if (_uiState.value.isLoading && !hasFilterChanged()) return
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
        // TMDB 硬上限 500 页，越界会返回 400（"Invalid page"），到顶就当没有下一页
        if (s.currentPage >= TMDB_MAX_PAGE) return
        _uiState.value = s.copy(isLoadingMore = true, error = null)
        loadPage(s.currentPage + 1)
    }

    /** 「仅展示未标看过」的客户端过滤（TMDB 不知道用户标过什么） */
    private fun filterWatched(
        items: List<TmdbSearchResult>,
        state: DiscoverFilterUiState
    ): List<TmdbSearchResult> {
        if (!state.hideWatched) return items
        val ids = _watchlistWatchedIds.value ?: return items
        val mediaType = if (state.type == TmdbRepository.DiscoverType.MOVIE) MediaType.MOVIE else MediaType.SHOW
        return items.filter { !ids.isWatched(null, it.id, mediaType) }
    }

    private fun loadPage(page: Int) {
        // 新请求接管：翻页时上一个已经结束，重新搜索时会把旧条件的请求掐掉
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
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
                // 想看/已看 ID 还在加载时先等一下：不等的话这一页等于没过滤，
                // 开关看着是开的，看过的片照样排在最前面
                if (s.hideWatched && _watchlistWatchedIds.value == null) {
                    withTimeoutOrNull(WATCHED_IDS_WAIT_MILLIS) {
                        watchlistWatchedIds.filterNotNull().first()
                    }
                }
                var pageToLoad = page
                var result = tmdbRepository.discover(filter, pageToLoad)
                var filtered = filterWatched(result.items, s)
                // 整页都被「仅展示未标看过」筛空时继续往后翻：
                // 停在这里会显示「没有符合条件的结果」，而后面几页其实还有没看过的
                var skipped = 0
                while (
                    filtered.isEmpty() &&
                    s.hideWatched &&
                    pageToLoad < result.totalPages &&
                    pageToLoad < TMDB_MAX_PAGE &&
                    skipped < MAX_EMPTY_PAGE_SKIP
                ) {
                    pageToLoad++
                    skipped++
                    result = tmdbRepository.discover(filter, pageToLoad)
                    filtered = filterWatched(result.items, s)
                }

                val existing = if (page == 1) emptyList() else _uiState.value.items
                // 按 id 去重：TMDB Discover API 在某些排序方式下可能跨页返回相同条目，
                // 直接拼接会导致 LazyColumn key 重复崩溃
                _uiState.value = _uiState.value.copy(
                    items = (existing + filtered).distinctBy { it.id },
                    currentPage = result.totalPages.coerceAtMost(pageToLoad),
                    totalPages = result.totalPages,
                    totalResults = result.totalResults,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null
                )
            } catch (e: CancellationException) {
                // 被新请求取消：状态由新请求负责，这里不能落 error，否则切类型会闪一下错误页
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = e.toUserMessage(context, R.string.error_search_failed)
                )
            }
        }
    }

    private companion object {
        /** "更早"选项的起点：TMDB 上更早的条目极少，1900 足够覆盖 */
        const val EARLIEST_YEAR = 1900
        /** "更早"选项的终点（只有"全部"选项 endYear 为 0，不参与日期计算） */
        const val EARLIER_OPTION_END_YEAR = 1959
        /** TMDB Discover 的分页上限 */
        const val TMDB_MAX_PAGE = 500
        /** 等想看/已看 ID 的上限：拿不到就先不过滤，不能把筛选页一直卡在骨架屏 */
        const val WATCHED_IDS_WAIT_MILLIS = 3000L
        /** 「仅展示未标看过」最多连续跳过的空页数，避免一次点击打出十几个请求 */
        const val MAX_EMPTY_PAGE_SKIP = 3
    }
}
