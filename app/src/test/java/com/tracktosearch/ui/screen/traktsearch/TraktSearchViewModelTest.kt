package com.tracktosearch.ui.screen.traktsearch

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.trakt.dto.TraktIds
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.TraktRepository.WatchlistWatchedIds
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * TraktSearchViewModel 单元测试。
 *
 * 验证搜索页的核心行为：
 * - 从 SavedStateHandle 读取 type 参数初始化 selectedTab
 * - search() 成功/失败/空查询
 * - switchTab() 自动触发未搜索 tab 的搜索
 * - 快速连续搜索的竞态控制（旧 searchJob 被取消）
 * - loadMore() 追加下一页 / hasMore=false 时不重复加载
 * - initSearch() 从导航参数初始化并执行首次搜索
 *
 * 测试策略：
 * 1. 所有 TraktSearchResult 的 movie/show 都使用 tmdb=0，走 enrichSearchResult 短路逻辑
 *    （tmdbId<=0 时不调用 tmdbRepository.enrichMovie/enrichTv），避免 enrich 复杂依赖
 * 2. 不测试 PERSON 类型：PERSON 分支会触发 tmdbRepository.searchPerson/searchMulti/searchByTmdb
 *    等复杂合并逻辑，难以稳定断言
 * 3. @Before 中桩 init 块的 getWatchlistWatchedIds/loadWatchlistWatchedIds，避免 init 协程干扰
 * 4. StandardTestDispatcher 下 init 协程处于 pending，由各测试在 runTest 内 advanceUntilIdle 推进
 * 5. 竞态测试（测试点6）：第一次 search 的 launch 协程尚未启动即被第二次 search 的 searchJob?.cancel 取消，
 *    searchMovies 可能只被调用 1 次，因此只验证最终状态（第二次结果正确），不严格验证调用次数
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TraktSearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val tmdbRepository = mockk<TmdbRepository>(relaxed = true)
    private val resourceRepository = mockk<ResourceRepository>(relaxed = true)
    private lateinit var viewModel: TraktSearchViewModel

    // ==================== 测试数据 ====================

    /** tmdb=0 走 enrichSearchResult 短路逻辑，不调用 tmdbRepository.enrichMovie */
    private val movieResultA = TraktSearchResult(
        type = "movie",
        score = 10.0,
        movie = TraktMovie(
            title = "盗梦空间", year = 2010,
            ids = TraktIds(trakt = 101, tmdb = 0, imdb = "tt101"),
            rating = 8.8,
            genres = listOf("科幻", "动作")
        )
    )

    private val movieResultB = TraktSearchResult(
        type = "movie",
        score = 9.0,
        movie = TraktMovie(
            title = "星际穿越", year = 2014,
            ids = TraktIds(trakt = 102, tmdb = 0, imdb = "tt102"),
            rating = 9.0
        )
    )

    private val showResultA = TraktSearchResult(
        type = "show",
        score = 10.0,
        show = TraktShow(
            title = "绝命毒师", year = 2008,
            ids = TraktIds(trakt = 201, tmdb = 0, imdb = "tt201"),
            rating = 9.5
        )
    )

    // ==================== 生命周期 ====================

    @Before
    fun setup() {
        clearMocks(traktRepository, tmdbRepository, resourceRepository)

        // init 块会调用：getWatchlistWatchedIds() (非 suspend) 和 loadWatchlistWatchedIds() (suspend)
        // 桩为空数据，避免 init 协程干扰主流程断言
        coEvery { traktRepository.getWatchlistWatchedIds() } returns null
        coEvery { traktRepository.loadWatchlistWatchedIds() } returns WatchlistWatchedIds()
    }

    /**
     * 构造 ViewModel：默认使用空 SavedStateHandle（selectedTab 默认 MOVIE）。
     * 各测试可传入自定义 SavedStateHandle 验证导航参数。
     */
    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ): TraktSearchViewModel {
        return TraktSearchViewModel(
            traktRepository = traktRepository,
            tmdbRepository = tmdbRepository,
            resourceRepository = resourceRepository,
            savedStateHandle = savedStateHandle,
            context = RuntimeEnvironment.getApplication()
        )
    }

    // ==================== 测试 ====================

    /**
     * 测试点1：构造时从 SavedStateHandle 读取 type="show" → uiState.selectedTab 为 SHOW
     *
     * selectedTab 在构造函数中同步赋值（_initialTab = initialTypeFromNav ?: MediaType.MOVIE），
     * 无需 advanceUntilIdle 即可断言。
     */
    @Test
    fun `构造_SavedStateHandle携带type为show_selectedTab为SHOW`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("type" to "show"))
        viewModel = createViewModel(savedStateHandle)

        assertThat(viewModel.uiState.value.selectedTab).isEqualTo(MediaType.SHOW)
    }

    /**
     * 测试点2：search("盗梦空间", MOVIE) 成功 → movieState.results 填充，isLoading=false
     *
     * 测试数据 movieResultA.ids.tmdb=0 走 enrichSearchResult 短路逻辑（第 566 行 if (movie.ids.tmdb <= 0)），
     * 不调用 tmdbRepository.enrichMovie，避免 enrich 复杂依赖。
     */
    @Test
    fun `search_电影搜索成功_results填充且isLoading为false`() = runTest {
        viewModel = createViewModel()
        coEvery { traktRepository.searchMovies("盗梦空间", any()) } returns
                Result.success(listOf(movieResultA) to 10)

        viewModel.search("盗梦空间", MediaType.MOVIE)
        advanceUntilIdle()

        val state = viewModel.uiState.value.movieState
        assertThat(state.results).hasSize(1)
        assertThat(state.results[0].title).isEqualTo("盗梦空间")
        assertThat(state.results[0].traktId).isEqualTo(101)
        assertThat(state.results[0].year).isEqualTo(2010)
        assertThat(state.isLoading).isFalse()
        assertThat(state.hasSearched).isTrue()
        assertThat(state.totalCount).isEqualTo(10)
        assertThat(state.hasMore).isTrue() // 1 < 10
        // tmdbId=0 走短路，不应调用 enrichMovie
        coVerify(exactly = 0) { tmdbRepository.enrichMovie(any(), any(), any()) }
    }

    /**
     * 测试点3：search 失败（Result.failure）→ movieState.error 非空
     *
     * search 第 365 行 onFailure 分支：error = e.message ?: context.getString(R.string.error_search_failed)
     */
    @Test
    fun `search_网络失败_error非空`() = runTest {
        viewModel = createViewModel()
        coEvery { traktRepository.searchMovies("盗梦空间", any()) } returns
                Result.failure(IOException("网络错误"))

        viewModel.search("盗梦空间", MediaType.MOVIE)
        advanceUntilIdle()

        val state = viewModel.uiState.value.movieState
        assertThat(state.error).isEqualTo("网络错误")
        assertThat(state.results).isEmpty()
        assertThat(state.isLoading).isFalse()
        assertThat(state.hasSearched).isTrue()
    }

    /**
     * 测试点4：search("") 空查询 → 不触发搜索（searchMovies 调用 0 次）
     *
     * search 第 181 行 `if (query.isBlank()) return`，直接返回不启动协程。
     */
    @Test
    fun `search_空查询_不触发搜索`() = runTest {
        viewModel = createViewModel()

        viewModel.search("", MediaType.MOVIE)
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.searchMovies(any(), any()) }
        coVerify(exactly = 0) { traktRepository.searchShows(any(), any()) }
        assertThat(viewModel.uiState.value.movieState.hasSearched).isFalse()
    }

    /**
     * 测试点5：switchTab(SHOW) → selectedTab=SHOW，且 query 非空时自动触发 SHOW 搜索
     *
     * switchTab 第 174 行：targetState.hasSearched=false 且 current.query 非空 → search(query, SHOW)
     * 自动触发 SHOW 搜索填充 showState。
     */
    @Test
    fun `switchTab_切到SHOW且query非空_自动触发SHOW搜索`() = runTest {
        viewModel = createViewModel()
        // 先搜索电影，让 query 进入 uiState
        coEvery { traktRepository.searchMovies("盗梦空间", any()) } returns
                Result.success(listOf(movieResultA) to 1)
        viewModel.search("盗梦空间", MediaType.MOVIE)
        advanceUntilIdle()

        // 桩 SHOW 搜索
        coEvery { traktRepository.searchShows("盗梦空间", any()) } returns
                Result.success(listOf(showResultA) to 1)

        viewModel.switchTab(MediaType.SHOW)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.selectedTab).isEqualTo(MediaType.SHOW)
        assertThat(state.showState.hasSearched).isTrue()
        assertThat(state.showState.results).hasSize(1)
        assertThat(state.showState.results[0].title).isEqualTo("绝命毒师")
        coVerify(atLeast = 1) { traktRepository.searchShows("盗梦空间", any()) }
    }

    /**
     * 测试点6：快速连续搜索两次 → 只保留第二次结果（searchJob?.cancel 取消旧任务）
     *
     * StandardTestDispatcher 下，第一次 search 的 launch 协程尚未启动即被第二次 search 的
     * searchJob?.cancel 取消（search 第 196 行）。因此 searchMovies 可能只被调用 1 次
     * （第一次协程被取消时还未执行到 searchMovies 调用）。
     *
     * 稳定性策略：只验证最终状态（第二次结果正确），不严格验证 searchMovies 调用次数。
     */
    @Test
    fun `search_快速连续两次_只保留第二次结果`() = runTest {
        viewModel = createViewModel()
        coEvery { traktRepository.searchMovies("query1", any()) } returns
                Result.success(listOf(movieResultA) to 1)
        coEvery { traktRepository.searchMovies("query2", any()) } returns
                Result.success(listOf(movieResultB) to 1)

        viewModel.search("query1", MediaType.MOVIE)
        viewModel.search("query2", MediaType.MOVIE)
        advanceUntilIdle()

        val state = viewModel.uiState.value.movieState
        // 只保留第二次结果
        assertThat(state.results).hasSize(1)
        assertThat(state.results[0].title).isEqualTo("星际穿越")
        // query 同步为第二次的查询词（search 第 190 行 _uiState.value.copy(query = query)）
        assertThat(viewModel.uiState.value.query).isEqualTo("query2")
    }

    /**
     * 测试点7：loadMore() 成功 → 追加下一页结果（currentPage 递增）
     *
     * 第一页 1 条 + totalCount=10 → hasMore=true（1 < 10）
     * loadMore 调用 searchMovies(page=2) 返回 1 条，合并为 2 条，currentPage=2
     */
    @Test
    fun `loadMore_有更多数据_追加下一页且currentPage递增`() = runTest {
        viewModel = createViewModel()
        // 第一页 1 条 totalCount=10 → hasMore=true
        coEvery { traktRepository.searchMovies("盗梦空间", any()) } returns
                Result.success(listOf(movieResultA) to 10)
        viewModel.search("盗梦空间", MediaType.MOVIE)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movieState.hasMore).isTrue()
        assertThat(viewModel.uiState.value.movieState.currentPage).isEqualTo(1)

        // 第二页：精确桩覆盖 any() 桩，返回 movieResultB
        coEvery { traktRepository.searchMovies("盗梦空间", 2) } returns
                Result.success(listOf(movieResultB) to 10)
        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value.movieState
        assertThat(state.results).hasSize(2)
        assertThat(state.results[0].title).isEqualTo("盗梦空间")
        assertThat(state.results[1].title).isEqualTo("星际穿越")
        assertThat(state.currentPage).isEqualTo(2)
        assertThat(state.isLoadingMore).isFalse()
        assertThat(state.hasMore).isTrue() // 2 < 10
    }

    /**
     * 测试点8：loadMore() hasMore=false → 不重复加载（直接 return）
     *
     * search 后 totalCount=1, results.size=1 → hasMore = 1 < 1 = false
     * loadMore 第 518 行 `!tabState.hasMore` 为 true → 直接 return，不调用 searchMovies
     */
    @Test
    fun `loadMore_hasMore为false_不调用searchMovies`() = runTest {
        viewModel = createViewModel()
        // 1 条 + totalCount=1 → hasMore = 1 < 1 = false
        coEvery { traktRepository.searchMovies("盗梦空间", any()) } returns
                Result.success(listOf(movieResultA) to 1)
        viewModel.search("盗梦空间", MediaType.MOVIE)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movieState.hasMore).isFalse()

        viewModel.loadMore()
        advanceUntilIdle()

        // searchMovies 只被 search 调用过 1 次，loadMore 未触发新调用
        coVerify(exactly = 1) { traktRepository.searchMovies(any(), any()) }
        // currentPage 不变
        assertThat(viewModel.uiState.value.movieState.currentPage).isEqualTo(1)
    }

    /**
     * 测试点9：initSearch(query, type) 从导航参数初始化 → 自动执行首次搜索
     *
     * initSearch 同步 selectedTab 后调用 search(query, type)。
     * 首次调用 anySearched=false，不会因「已搜索且 query 相同」return。
     */
    @Test
    fun `initSearch_传入query和type_自动执行首次搜索`() = runTest {
        viewModel = createViewModel()
        coEvery { traktRepository.searchMovies("盗梦空间", any()) } returns
                Result.success(listOf(movieResultA) to 1)

        viewModel.initSearch("盗梦空间", MediaType.MOVIE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.query).isEqualTo("盗梦空间")
        assertThat(state.selectedTab).isEqualTo(MediaType.MOVIE)
        assertThat(state.movieState.hasSearched).isTrue()
        assertThat(state.movieState.results).hasSize(1)
        assertThat(state.movieState.results[0].title).isEqualTo("盗梦空间")
        coVerify(atLeast = 1) { traktRepository.searchMovies("盗梦空间", any()) }
    }

    // ==================== enrichment 字段传递测试 ====================
    // 回归测试：tmdbId>0 时 enrichSearchResult 调用 enrichMovie/enrichTv，
    // 必须把 enrichment.chineseTitle→displayTitle、posterUrl→posterUrl 正确映射到 UI item。
    // 之前所有测试用 tmdb=0 走短路逻辑，从未验证 enrich 字段传递。

    /**
     * 测试：search 电影时 tmdbId>0 → 调用 enrichMovie 返回中文标题和海报 URL，
     * TraktSearchUiItem 的 displayTitle 和 posterUrl 必须来自 enrichment（而非 Trakt 原始字段）。
     *
     * 回归场景：Trakt 原始标题英文，enrichMovie 返回中文标题，若字段映射错误，
     * UI 上搜索结果标题显示英文而非中文。
     */
    @Test
    fun `search_电影tmdbId大于0_enrichMovie返回中文标题和海报_字段正确映射`() = runTest {
        viewModel = createViewModel()
        val movieWithTmdb = TraktSearchResult(
            type = "movie",
            score = 10.0,
            movie = TraktMovie(
                title = "Inception", year = 2010,
                ids = TraktIds(trakt = 101, tmdb = 27205, imdb = "tt1375666"),
                rating = 8.8,
                genres = listOf("科幻", "动作")
            )
        )
        coEvery { traktRepository.searchMovies("Inception", any()) } returns
                Result.success(listOf(movieWithTmdb) to 1)
        coEvery { tmdbRepository.enrichMovie(27205, "Inception", 2010) } returns
            TmdbRepository.MovieEnrichment(
                posterUrl = "https://image.tmdb.org/t/p/w500/inception.jpg",
                chineseTitle = "盗梦空间",
                overview = "梦境层层",
                genres = "科幻,动作",
                year = 2010,
                rating = 8.8
            )

        viewModel.search("Inception", MediaType.MOVIE)
        advanceUntilIdle()

        val state = viewModel.uiState.value.movieState
        assertThat(state.results).hasSize(1)
        val item = state.results[0]
        // Trakt 原始字段
        assertThat(item.traktId).isEqualTo(101)
        assertThat(item.tmdbId).isEqualTo(27205)
        assertThat(item.title).isEqualTo("Inception")
        assertThat(item.imdbId).isEqualTo("tt1375666")
        assertThat(item.traktRating).isEqualTo(8.8)
        // enrich 字段（bug 核心防护字段）
        assertThat(item.displayTitle).isEqualTo("盗梦空间") // 必须来自 enrichment.chineseTitle
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/inception.jpg")
        assertThat(item.genres).isEqualTo("科幻,动作")
        coVerify(exactly = 1) { tmdbRepository.enrichMovie(27205, "Inception", 2010) }
    }

    /**
     * 测试：search 剧集时 tmdbId>0 → 调用 enrichTv 返回中文标题和海报 URL，
     * TraktSearchUiItem 的 displayTitle 和 posterUrl 必须来自 enrichment。
     */
    @Test
    fun `search_剧集tmdbId大于0_enrichTv返回中文标题和海报_字段正确映射`() = runTest {
        viewModel = createViewModel()
        val showWithTmdb = TraktSearchResult(
            type = "show",
            score = 10.0,
            show = TraktShow(
                title = "Breaking Bad", year = 2008,
                ids = TraktIds(trakt = 201, tmdb = 1396, imdb = "tt0903747"),
                rating = 9.5
            )
        )
        coEvery { traktRepository.searchShows("Breaking Bad", any()) } returns
                Result.success(listOf(showWithTmdb) to 1)
        coEvery { tmdbRepository.enrichTv(1396, "Breaking Bad", 2008) } returns
            TmdbRepository.TvEnrichment(
                posterUrl = "https://image.tmdb.org/t/p/w500/breakingbad.jpg",
                chineseTitle = "绝命毒师",
                overview = "高中化学老师制毒",
                genres = "犯罪,剧情",
                year = 2008,
                rating = 9.5
            )

        viewModel.search("Breaking Bad", MediaType.SHOW)
        advanceUntilIdle()

        val state = viewModel.uiState.value.showState
        assertThat(state.results).hasSize(1)
        val item = state.results[0]
        assertThat(item.traktId).isEqualTo(201)
        assertThat(item.tmdbId).isEqualTo(1396)
        assertThat(item.title).isEqualTo("Breaking Bad")
        // enrich 字段
        assertThat(item.displayTitle).isEqualTo("绝命毒师")
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/breakingbad.jpg")
        assertThat(item.genres).isEqualTo("犯罪,剧情")
        coVerify(exactly = 1) { tmdbRepository.enrichTv(1396, "Breaking Bad", 2008) }
    }

    /**
     * 测试：enrich 失败 fallback（posterUrl=null, chineseTitle=原始标题）时，
     * TraktSearchUiItem 的 displayTitle 降级为原始标题、posterUrl=null。
     */
    @Test
    fun `search_enrichMovie_fallback_displayTitle降级且posterUrl为null`() = runTest {
        viewModel = createViewModel()
        val movieWithTmdb = TraktSearchResult(
            type = "movie",
            score = 10.0,
            movie = TraktMovie(
                title = "Inception", year = 2010,
                ids = TraktIds(trakt = 101, tmdb = 27205, imdb = "tt1375666")
            )
        )
        coEvery { traktRepository.searchMovies("Inception", any()) } returns
                Result.success(listOf(movieWithTmdb) to 1)
        // fallback：TMDB 不可用
        coEvery { tmdbRepository.enrichMovie(27205, "Inception", 2010) } returns
            TmdbRepository.MovieEnrichment(
                posterUrl = null,
                chineseTitle = "Inception",
                overview = "",
                genres = "",
                year = 2010,
                rating = 0.0
            )

        viewModel.search("Inception", MediaType.MOVIE)
        advanceUntilIdle()

        val item = viewModel.uiState.value.movieState.results[0]
        assertThat(item.displayTitle).isEqualTo("Inception")
        assertThat(item.posterUrl).isNull()
        assertThat(item.genres).isEqualTo("")
    }
}
