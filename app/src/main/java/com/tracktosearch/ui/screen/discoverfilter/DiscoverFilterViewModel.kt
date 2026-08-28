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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val voteAverageMin: Float = 0f,
    val voteAverageMax: Float = 10f,
    val sortBy: TmdbRepository.DiscoverSort = TmdbRepository.DiscoverSort.POPULARITY_DESC,
    val hideWatched: Boolean = false,
    val showAdvanced: Boolean = false,
    val items: List<TmdbSearchResult> = emptyList(),
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val totalResults: Int = 0,
    /**
     * 结果条数是不是近似值。
     *
     * 只有「仅展示未标看过」这种服务端不知情的客户端过滤才会为 true（还有统计请求失败兜底时）；
     * 年代不连续的情况已经按连续块分别查准了，不算近似。
     */
    val totalResultsApproximate: Boolean = false,
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
        val totalResultsApproximate: Boolean,
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
                totalResultsApproximate = before.totalResultsApproximate,
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
            voteAverageMin = saved.voteMin,
            voteAverageMax = saved.voteMax,
            sortBy = saved.sortBy,
            hideWatched = saved.hideWatched,
            showAdvanced = saved.showAdvanced,
            items = emptyList(),
            currentPage = 0,
            totalPages = 0,
            totalResults = 0,
            totalResultsApproximate = false,
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
                totalResultsApproximate = cached.totalResultsApproximate,
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
            selectedDecadeKeys = next
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
            voteAverageMin = 0f,
            voteAverageMax = 10f,
            sortBy = TmdbRepository.DiscoverSort.POPULARITY_DESC,
            hideWatched = false,
            items = emptyList(),
            currentPage = 0,
            totalPages = 0,
            totalResults = 0,
            totalResultsApproximate = false,
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

    /** 选中的每个年代各自覆盖的年份区间（"全部"不参与） */
    private fun selectedYearRanges(keys: Set<String>): List<IntRange> =
        decadeOptions.filter { it.key in keys && !it.isAll }.map { effectiveYears(it) }

    /** 选中的年代合并成的连续年份区间，没有有效选项时返回 null */
    private fun mergedYearRange(keys: Set<String>): IntRange? {
        val ranges = selectedYearRanges(keys)
        if (ranges.isEmpty()) return null
        return ranges.minOf { it.first }..ranges.maxOf { it.last }
    }

    /**
     * 把选中的年份区间合并成互不相邻的连续块。
     *
     * 用户可以同时点「2019」和「2010年代」，两者重叠；点「1990年代」和「2000年代」则首尾相接。
     * 合完之后块数就是"有几段"，>1 说明中间有没勾的年份。相接（如 1999 与 2000）也要合，
     * 否则会被当成两段，多打一次统计请求。
     */
    private fun coalesceRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val blocks = mutableListOf<IntRange>()
        var current = sorted.first()
        for (range in sorted.drop(1)) {
            current = if (range.first <= current.last + 1) {
                current.first..maxOf(current.last, range.last)
            } else {
                blocks += current
                range
            }
        }
        blocks += current
        return blocks
    }

    /** 条目的年份：电影看 release_date，剧集看 first_air_date，缺失时用另一个兜底 */
    private fun itemYear(item: TmdbSearchResult, type: TmdbRepository.DiscoverType): Int? {
        val date = if (type == TmdbRepository.DiscoverType.SHOW) {
            item.first_air_date?.takeIf { it.isNotBlank() } ?: item.release_date
        } else {
            item.release_date.takeIf { it.isNotBlank() } ?: item.first_air_date.orEmpty()
        }
        return date.take(4).toIntOrNull()
    }

    /**
     * 丢掉落在没勾选年份里的条目。
     *
     * TMDB 的日期筛选只有一个连续区间（gte/lte），选「1990年代 + 2010年代」服务端只能给
     * 1990-2019，中间的 2000年代 也会一起回来。用户没勾就不该出现，按发行年份在客户端补筛。
     */
    private fun filterDecadeGaps(
        items: List<TmdbSearchResult>,
        type: TmdbRepository.DiscoverType,
        ranges: List<IntRange>
    ): List<TmdbSearchResult> {
        if (ranges.isEmpty()) return items
        return items.filter { item ->
            // 取不到年份的条目保留：服务端已经按日期区间筛过，缺日期是数据不全，不是越界
            val year = itemYear(item, type) ?: return@filter true
            ranges.any { year in it }
        }
    }

    /**
     * 计算选中年代对应的日期范围（多选合并为连续范围）。
     * TMDB Discover 只支持连续日期范围，多选不连续年代会合并为最小起始到最大结束，
     * 中间用户没勾的年份由 [filterDecadeGaps] 在客户端筛掉。
     */
    private fun computeDateRange(): Pair<String?, String?> {
        val merged = mergedYearRange(_uiState.value.selectedDecadeKeys) ?: return null to null
        return "${merged.first}-01-01" to "${merged.last}-12-31"
    }

    /**
     * 选中年代不连续时的准确结果数。
     *
     * 服务端只能筛一个连续区间，它给的 total_results 把中间没勾的年份也算进去了（选
     * 「1990年代 + 2010年代」报的是 1990-2019 的总数）。每个连续块单独问一次、只取
     * total_results 再相加才是实际条数 —— 块之间既不重叠也不相邻，不会重复计数。
     *
     * 任一请求失败就返回 null，由调用方退回服务端给的近似值。
     */
    private suspend fun exactTotalResults(
        filter: TmdbRepository.DiscoverFilter,
        blocks: List<IntRange>
    ): Int? = try {
        coroutineScope {
            blocks.map { block ->
                async {
                    tmdbRepository.discover(
                        filter.copy(
                            releaseDateStart = "${block.first}-01-01",
                            releaseDateEnd = "${block.last}-12-31"
                        ),
                        1
                    ).totalResults
                }
            }.awaitAll().sum()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("FilterVM", "exactTotalResults failed: ${e.message}")
        null
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
            totalResultsApproximate = false,
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
            val yearRanges = selectedYearRanges(s.selectedDecadeKeys)
            val decadeBlocks = coalesceRanges(yearRanges)
            // 年代选得不连续（合出多段）时，服务端只能筛一个连续区间，没勾的年份得在客户端剔掉
            val gapRanges = if (decadeBlocks.size > 1) decadeBlocks else emptyList()
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
                // 有客户端过滤（未标看过 / 没勾的年代）时，一页可能只剩两三条：
                // 撑不满一屏就滚不动，也就触发不了自动翻页，列表看着像是加载完了。
                // 所以先攒够一批再上屏，攒不够也有请求数上限兜底
                val clientFiltered = s.hideWatched || gapRanges.isNotEmpty()
                var pageToLoad = page
                var result = tmdbRepository.discover(filter, pageToLoad)
                val collected = mutableListOf<TmdbSearchResult>()
                collected += filterDecadeGaps(filterWatched(result.items, s), s.type, gapRanges)
                var extraPages = 0
                while (
                    clientFiltered &&
                    collected.size < MIN_BATCH_SIZE &&
                    pageToLoad < result.totalPages &&
                    pageToLoad < TMDB_MAX_PAGE &&
                    extraPages < MAX_EXTRA_PAGE_FETCH
                ) {
                    pageToLoad++
                    extraPages++
                    result = tmdbRepository.discover(filter, pageToLoad)
                    collected += filterDecadeGaps(filterWatched(result.items, s), s.type, gapRanges)
                }

                val existing = if (page == 1) emptyList() else _uiState.value.items
                // 结果条数：合并区间的 total_results 把没勾的年份也算进去了，按连续块分别查再加起来。
                // 「仅展示未标看过」是客户端过滤，服务端不可能知道用户标过什么，这种情况只能标"约"
                val previous = _uiState.value
                var totalResults = result.totalResults
                var approximate = s.hideWatched
                if (gapRanges.isNotEmpty()) {
                    if (page != 1) {
                        // 翻页时沿用首页算好的数，别用合并区间的总数把它盖回去
                        totalResults = previous.totalResults
                        approximate = previous.totalResultsApproximate
                    } else if (gapRanges.size > MAX_COUNT_QUERY_BLOCKS) {
                        // 段数太多就不查了：一次点击打出十几个请求不值得，标"约"更实在
                        approximate = true
                    } else {
                        val exact = exactTotalResults(filter, gapRanges)
                        if (exact == null) approximate = true else totalResults = exact
                    }
                }
                // 按 id 去重：TMDB Discover API 在某些排序方式下可能跨页返回相同条目，
                // 直接拼接会导致 LazyColumn key 重复崩溃
                _uiState.value = _uiState.value.copy(
                    items = (existing + collected).distinctBy { it.id },
                    currentPage = result.totalPages.coerceAtMost(pageToLoad),
                    totalPages = result.totalPages,
                    totalResults = totalResults,
                    totalResultsApproximate = approximate,
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
        /** 客户端过滤后一批至少凑这么多条，凑够才上屏（TMDB 一页 20 条） */
        const val MIN_BATCH_SIZE = 10
        /** 为凑够一批最多额外多请求几页，避免一次点击打出十几个请求 */
        const val MAX_EXTRA_PAGE_FETCH = 4
        /** 查准确条数最多拆几段：段数越多请求越多，超过就退回近似值 */
        const val MAX_COUNT_QUERY_BLOCKS = 6
    }
}
