package com.tracktosearch.ui.screen.discoverfilter

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * DiscoverFilterViewModel 单元测试。
 *
 * 验证 Discover 筛选页的核心行为：
 * - switchType 切换电影/电视剧类型并清空旧结果
 * - toggle 方法（Genre/Country/Keyword/Decade）的增删逻辑
 * - setVoteRange/setSortBy/toggleHideWatched/toggleAdvanced 状态更新
 * - resetFilters 重置所有筛选条件
 * - search() 成功/失败/防重复
 *
 * 测试策略：
 * 1. SessionModeManager.traktConnected 桩为 false，模拟未连接 Trakt 的会话
 * 2. traktRepository.loadWatchlistWatchedIds/getWatchlistWatchedIds 桩为默认空值，
 *    避免 init 协程干扰（hideWatched 过滤分支在 _watchlistWatchedIds=null 时不进入）
 * 3. toggle/switchType/setXxx 是同步方法，直接验证 uiState.value 字段变化，无需 advanceUntilIdle
 * 4. search 内部用 viewModelScope.launch，需 advanceUntilIdle() 推进协程
 * 5. init 会发首屏搜索，且它的协程排在 runTest 测试体之前执行：VM 必须由各测试装好桩之后
 *    用 createViewModel() 自己建，不能在 @Before 里建
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiscoverFilterViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tmdbRepository = mockk<TmdbRepository>(relaxed = true)
    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val sessionModeManager = mockk<SessionModeManager>(relaxed = true)
    private val posterColorExtractor = mockk<PosterColorExtractor>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var viewModel: DiscoverFilterViewModel

    @Test
    fun `仅登录豆瓣时不加载Trakt私有想看已看数据`() = runTest {
        io.mockk.clearMocks(traktRepository)
        every { sessionModeManager.traktConnected } returns MutableStateFlow(false)

        viewModel = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.loadWatchlistWatchedIds() }
        assertThat(viewModel.isLoggedIn.value).isFalse()
    }

    // ==================== 测试数据 ====================

    private val testItems = listOf(
        TmdbSearchResult(id = 1, title = "电影A"),
        TmdbSearchResult(id = 2, title = "电影B")
    )

    // ==================== 生命周期 ====================

    @Before
    fun setup() {
        clearMocks(tmdbRepository, traktRepository, sessionModeManager, posterColorExtractor)

        // init 块调用 loadWatchlistWatchedIds（suspend）+ getWatchlistWatchedIds（非 suspend）
        // 桩为默认空值，hideWatched 过滤分支不进入
        coEvery { traktRepository.loadWatchlistWatchedIds() } returns TraktRepository.WatchlistWatchedIds()
        every { traktRepository.getWatchlistWatchedIds() } returns null

        every { sessionModeManager.traktConnected } returns MutableStateFlow(false)
    }

    /**
     * 建 ViewModel。必须由测试自己在装好桩之后调用。
     *
     * 不能放在 setup() 里：init 会发首屏搜索，它的协程排在 runTest 测试体之前，
     * 在 @Before 里建 VM 的话首屏搜索会先跑，拿到的是 relaxed mock 的空结果。
     */
    private fun createViewModel() = DiscoverFilterViewModel(
        tmdbRepository,
        traktRepository,
        sessionModeManager,
        posterColorExtractor,
        context
    )

    // ==================== 测试 ====================

    /**
     * 测试点1：switchType(SHOW) → type 变为 SHOW，items 清空，hasSearched 重置
     *
     * switchType 是同步方法，但需先 advanceUntilIdle 让 init 协程执行完，
     * 避免 init 的 launch 干扰状态断言。
     */
    @Test
    fun `switchType_切换到SHOW_type变更且清空结果`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.switchType(TmdbRepository.DiscoverType.SHOW)

        val state = viewModel.uiState.value
        assertThat(state.type).isEqualTo(TmdbRepository.DiscoverType.SHOW)
        assertThat(state.items).isEmpty()
        assertThat(state.hasSearched).isFalse()
    }

    /**
     * 测试点2：toggleGenre(18) 添加到 selectedGenreIds；再次调用移除
     *
     * 同步方法，无需 advanceUntilIdle。
     */
    @Test
    fun `toggleGenre_添加再次调用移除_selectedGenreIds正确变化`() = runTest {
        viewModel = createViewModel()
        viewModel.toggleGenre(18)
        assertThat(viewModel.uiState.value.selectedGenreIds).contains(18)

        viewModel.toggleGenre(18)
        assertThat(viewModel.uiState.value.selectedGenreIds).doesNotContain(18)
    }

    /**
     * 测试点3：toggleDecade("2010-2019") 添加到 selectedDecadeKeys；再次调用移除
     *
     * 使用 "2010-2019" 作为 key（decadeOptions 中始终包含此静态年代选项）。
     * 同步方法，无需 advanceUntilIdle。
     */
    @Test
    fun `toggleDecade_添加再次调用移除_selectedDecadeKeys正确变化`() = runTest {
        viewModel = createViewModel()
        viewModel.toggleDecade("2010-2019")
        assertThat(viewModel.uiState.value.selectedDecadeKeys).contains("2010-2019")

        viewModel.toggleDecade("2010-2019")
        assertThat(viewModel.uiState.value.selectedDecadeKeys).doesNotContain("2010-2019")
    }

    /**
     * 测试点4：setVoteRange(5f, 10f) → voteAverageMin=5f, voteAverageMax=10f
     *
     * 同步方法，无需 advanceUntilIdle。
     */
    @Test
    fun `setVoteRange_设置5到10_voteAverageMin和Max正确`() = runTest {
        viewModel = createViewModel()
        viewModel.setVoteRange(5f, 10f)

        val state = viewModel.uiState.value
        assertThat(state.voteAverageMin).isEqualTo(5f)
        assertThat(state.voteAverageMax).isEqualTo(10f)
    }

    /**
     * 测试点5：setSortBy(VOTE_AVERAGE_DESC) → sortBy 更新为非默认值
     *
     * 同步方法，无需 advanceUntilIdle。
     */
    @Test
    fun `setSortBy_设置为VOTE_AVERAGE_DESC_sortBy更新`() = runTest {
        viewModel = createViewModel()
        viewModel.setSortBy(TmdbRepository.DiscoverSort.VOTE_AVERAGE_DESC)

        assertThat(viewModel.uiState.value.sortBy).isEqualTo(TmdbRepository.DiscoverSort.VOTE_AVERAGE_DESC)
    }

    /**
     * 测试点6：toggleHideWatched() → hideWatched 取反（默认 false → true）
     *
     * 同步方法，无需 advanceUntilIdle。
     */
    @Test
    fun `toggleHideWatched_默认false_调用后变true`() = runTest {
        viewModel = createViewModel()
        assertThat(viewModel.uiState.value.hideWatched).isFalse()

        viewModel.toggleHideWatched()

        assertThat(viewModel.uiState.value.hideWatched).isTrue()
    }

    /**
     * 测试点7：resetFilters() → 所有筛选条件回到默认值
     *
     * 先设置多个非默认筛选条件，再调用 resetFilters 验证全部重置。
     * 同步方法，无需 advanceUntilIdle。
     */
    @Test
    fun `resetFilters_有筛选条件_全部回到默认值`() = runTest {
        viewModel = createViewModel()
        // 设置非默认筛选条件
        viewModel.toggleGenre(18)
        viewModel.toggleCountry("CN")
        viewModel.toggleKeyword(180547)
        viewModel.toggleDecade("2010-2019")
        viewModel.setVoteRange(5f, 8f)
        viewModel.setSortBy(TmdbRepository.DiscoverSort.VOTE_AVERAGE_DESC)
        viewModel.toggleHideWatched()

        // 重置
        viewModel.resetFilters()

        val state = viewModel.uiState.value
        assertThat(state.selectedGenreIds).isEmpty()
        assertThat(state.selectedCountries).isEmpty()
        assertThat(state.selectedKeywordIds).isEmpty()
        assertThat(state.selectedDecadeKeys).isEmpty()
        assertThat(state.voteAverageMin).isEqualTo(0f)
        assertThat(state.voteAverageMax).isEqualTo(10f)
        assertThat(state.sortBy).isEqualTo(TmdbRepository.DiscoverSort.POPULARITY_DESC)
        assertThat(state.hideWatched).isFalse()
        assertThat(state.items).isEmpty()
        assertThat(state.hasSearched).isFalse()
        assertThat(state.error).isNull()
    }

    /**
     * 测试点8：search() 成功 → items 填充，isLoading=false，hasSearched=true
     *
     * 额外验证防重复：条件未变化时第二次 search 被跳过（discover 只调用一次）。
     * search 内部用 viewModelScope.launch，需 advanceUntilIdle 推进。
     */
    @Test
    fun `search_成功_items填充且isLoading为false`() = runTest {
        coEvery { tmdbRepository.discover(any(), any()) } returns TmdbRepository.DiscoverPage(
            items = testItems,
            totalPages = 5,
            totalResults = 100
        )
        viewModel = createViewModel()
        advanceUntilIdle() // init 的首屏搜索
        // 清掉首屏搜索的调用记录，下面的 exactly = 1 数的是改条件后这次 search
        clearMocks(tmdbRepository, answers = false, childMocks = false)

        viewModel.toggleGenre(18) // 条件变了，search 不会被"条件未变"挡掉
        viewModel.search()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.items).hasSize(2)
        assertThat(state.items[0].title).isEqualTo("电影A")
        assertThat(state.items[1].title).isEqualTo("电影B")
        assertThat(state.isLoading).isFalse()
        assertThat(state.hasSearched).isTrue()
        assertThat(state.error).isNull()

        // 防重复：条件未变化 + hasSearched + items 非空 → 第二次 search 跳过
        viewModel.search()
        advanceUntilIdle()

        coVerify(exactly = 1) { tmdbRepository.discover(any(), any()) }
    }

    /**
     * 测试点9：search() 网络失败 → error 非空，isLoading=false
     *
     * discover 抛出 IOException，被 loadPage 的 catch 捕获后设置 error。
     */
    @Test
    fun `search_网络失败_error非空且isLoading为false`() = runTest {
        coEvery { tmdbRepository.discover(any(), any()) } throws IOException("网络错误")
        every { context.getString(R.string.error_network_unavailable) } returns "网络错误"

        viewModel = createViewModel()
        advanceUntilIdle() // init 的首屏搜索直接失败

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("网络错误")
        assertThat(state.isLoading).isFalse()
        assertThat(state.items).isEmpty()
    }

    /**
     * 测试点10：toggleAdvanced() → showAdvanced 取反（默认 false → true）
     *
     * 同步方法，无需 advanceUntilIdle。
     */
    @Test
    fun `toggleAdvanced_默认false_调用后变true`() = runTest {
        viewModel = createViewModel()
        assertThat(viewModel.uiState.value.showAdvanced).isFalse()

        viewModel.toggleAdvanced()

        assertThat(viewModel.uiState.value.showAdvanced).isTrue()
    }

    /**
     * 回归测试：loadMore 跨页返回重复 id 时 items 必须去重。
     *
     * Bug 场景：TMDB Discover API 在某些排序方式下可能跨页返回相同 id 的条目，
     * 直接拼接 existing + filtered 会导致 LazyColumn key 重复崩溃
     * （java.lang.IllegalArgumentException: Key "xxx" was already used）。
     *
     * 修复：loadPage 合并时用 distinctBy { it.id } 去重。
     *
     * 验证：第一页返回 [id=1, id=2]，第二页返回 [id=2, id=3]（id=2 跨页重复），
     * loadMore 后 items 应为 [id=1, id=2, id=3]，无重复 id。
     */
    @Test
    fun `loadMore_跨页返回重复id_items去重无重复`() = runTest {
        val page1 = listOf(
            TmdbSearchResult(id = 1, title = "电影A"),
            TmdbSearchResult(id = 2, title = "电影B")
        )
        val page2 = listOf(
            TmdbSearchResult(id = 2, title = "电影B"), // 与第一页重复
            TmdbSearchResult(id = 3, title = "电影C")
        )

        coEvery { tmdbRepository.discover(any(), any()) } returns
            TmdbRepository.DiscoverPage(items = page1, totalPages = 5, totalResults = 100) andThen
            TmdbRepository.DiscoverPage(items = page2, totalPages = 5, totalResults = 100)

        viewModel = createViewModel()
        advanceUntilIdle() // init 的首屏搜索拿到第一页

        // 第一页结果
        assertThat(viewModel.uiState.value.items.map { it.id }).containsExactly(1, 2).inOrder()

        viewModel.loadMore()
        advanceUntilIdle()

        // 第二页合并后：id=2 去重，结果应为 [1, 2, 3]
        val ids = viewModel.uiState.value.items.map { it.id }
        assertThat(ids).containsExactly(1, 2, 3).inOrder()
        // 确保没有重复 id（这正是 LazyColumn key 崩溃的根因）
        assertThat(ids.toSet().size).isEqualTo(ids.size)
    }

    /**
     * 首屏搜索在 init 里发，不等屏幕的 LaunchedEffect。
     *
     * 等第一次组合后再发，第一帧 isLoading 还是 false，用户会先看到一帧空白再看到骨架屏。
     */
    @Test
    fun `构造_不需要屏幕触发_init就发出首屏搜索`() = runTest {
        coEvery { tmdbRepository.discover(any(), any()) } returns TmdbRepository.DiscoverPage(
            items = testItems,
            totalPages = 5,
            totalResults = 100
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.hasSearched).isTrue()
        assertThat(state.items).hasSize(2)
        assertThat(state.isLoading).isFalse()
        coVerify(exactly = 1) { tmdbRepository.discover(any(), 1) }
    }

    /**
     * Tab 来回切换命中结果缓存：切回来立刻有内容，且不再发请求。
     *
     * 切换前每次都清空结果重新下载，网速差时来回都是骨架屏。
     * 屏幕的 Tab 点击是 switchType() + search()，这里照原样调。
     */
    @Test
    fun `switchType_来回切换_结果立刻还原且不重新请求`() = runTest {
        coEvery {
            tmdbRepository.discover(match { it.type == TmdbRepository.DiscoverType.MOVIE }, any())
        } returns TmdbRepository.DiscoverPage(
            items = listOf(TmdbSearchResult(id = 1, title = "电影A")),
            totalPages = 3,
            totalResults = 60
        )
        coEvery {
            tmdbRepository.discover(match { it.type == TmdbRepository.DiscoverType.SHOW }, any())
        } returns TmdbRepository.DiscoverPage(
            items = listOf(TmdbSearchResult(id = 9, name = "剧集A")),
            totalPages = 2,
            totalResults = 40
        )

        viewModel = createViewModel()
        advanceUntilIdle() // init 首屏搜索：电影
        assertThat(viewModel.uiState.value.items.map { it.id }).containsExactly(1)

        viewModel.switchType(TmdbRepository.DiscoverType.SHOW)
        viewModel.search()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items.map { it.id }).containsExactly(9)

        clearMocks(tmdbRepository, answers = false, childMocks = false)
        viewModel.switchType(TmdbRepository.DiscoverType.MOVIE)

        // 缓存命中：切回来这一刻结果就在，不用等协程
        val restored = viewModel.uiState.value
        assertThat(restored.items.map { it.id }).containsExactly(1)
        assertThat(restored.totalResults).isEqualTo(60)
        assertThat(restored.hasSearched).isTrue()

        // 屏幕紧跟着调的 search 被"条件未变且已有结果"挡掉
        viewModel.search()
        advanceUntilIdle()
        coVerify(exactly = 0) { tmdbRepository.discover(any(), any()) }
    }

    /**
     * 回归测试：切类型时旧类型的请求还在飞，它的结果不能落进新类型的列表。
     *
     * Bug 场景：在电影第一页还没回来时切到电视剧，电影结果到达后直接写进 items，
     * 用户在电视剧 Tab 下看到的是电影。修复：切类型/重新搜索前取消在飞的请求。
     */
    @Test
    fun `switchType_旧类型请求还在飞_结果不会混进新类型`() = runTest {
        val movieGate = CompletableDeferred<Unit>()
        coEvery {
            tmdbRepository.discover(match { it.type == TmdbRepository.DiscoverType.MOVIE }, any())
        } coAnswers {
            movieGate.await()
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 5, totalResults = 100)
        }
        coEvery {
            tmdbRepository.discover(match { it.type == TmdbRepository.DiscoverType.SHOW }, any())
        } returns TmdbRepository.DiscoverPage(items = emptyList(), totalPages = 0, totalResults = 0)

        viewModel = createViewModel()
        advanceUntilIdle() // 电影首屏请求挂在闸门上

        viewModel.switchType(TmdbRepository.DiscoverType.SHOW)
        viewModel.search()
        movieGate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.type).isEqualTo(TmdbRepository.DiscoverType.SHOW)
        assertThat(state.items).isEmpty()
        // 取消不是失败，不该落 error
        assertThat(state.error).isNull()
    }

    /**
     * 回归测试：首屏还在加载时改条件，这次改动不能被丢掉。
     *
     * Bug 场景：search() 原来一律 `if (isLoading) return`，首屏请求没回来时点「应用」
     * 直接早退，之后也没有补发时机 —— 筛选 chip 亮着，结果却是旧条件的。
     */
    @Test
    fun `search_首屏加载中改条件_新条件照样生效`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        coEvery { tmdbRepository.discover(match { it.genreIds.isEmpty() }, any()) } coAnswers {
            firstGate.await()
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 5, totalResults = 100)
        }
        coEvery { tmdbRepository.discover(match { it.genreIds == listOf(18) }, any()) } returns
            TmdbRepository.DiscoverPage(
                items = listOf(TmdbSearchResult(id = 7, title = "剧情片")),
                totalPages = 1,
                totalResults = 1
            )

        viewModel = createViewModel()
        advanceUntilIdle() // 首屏请求卡在闸门上
        assertThat(viewModel.uiState.value.isLoading).isTrue()

        viewModel.toggleGenre(18)
        viewModel.search()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items.map { it.id }).containsExactly(7)

        // 被取消的首屏请求即使这时才返回，也不能盖掉新条件的结果
        firstGate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items.map { it.id }).containsExactly(7)
    }

    /** 条件没变时的重复 search 仍然要挡住，别把在飞的请求重开一遍 */
    @Test
    fun `search_加载中条件未变_不重复请求`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { tmdbRepository.discover(any(), any()) } coAnswers {
            gate.await()
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 1, totalResults = 2)
        }

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search()
        viewModel.search()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { tmdbRepository.discover(any(), any()) }
    }

    // ==================== 年代不连续多选 ====================

    /**
     * 回归测试：用户没勾的年代不能被顺带带进结果。
     *
     * TMDB 的日期筛选只有一个连续区间（gte/lte），选「1990年代 + 2010年代」服务端只能给
     * 1990-2019，中间的 2000年代 也会一起回来 —— 用户没点就不该出现，客户端要补筛掉。
     */
    @Test
    fun `toggleDecade_年代不连续_没勾的年份被筛掉`() = runTest {
        coEvery { tmdbRepository.discover(any(), any()) } returns TmdbRepository.DiscoverPage(
            items = listOf(
                TmdbSearchResult(id = 1, title = "1995 年的", release_date = "1995-06-01"),
                TmdbSearchResult(id = 2, title = "2005 年的", release_date = "2005-06-01"),
                TmdbSearchResult(id = 3, title = "2015 年的", release_date = "2015-06-01")
            ),
            totalPages = 1,
            totalResults = 3
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleDecade("1990-1999")
        viewModel.toggleDecade("2010-2019")
        viewModel.search()
        advanceUntilIdle()

        // 服务端按 1990-2019 返回，2005 年那条落在没勾的 2000年代 里
        assertThat(viewModel.uiState.value.items.map { it.id }).containsExactly(1, 3)
    }

    /** 年代连续时服务端给回来的就是用户点的那些年，不该再筛掉任何东西 */
    @Test
    fun `toggleDecade_年代连续_结果原样保留`() = runTest {
        coEvery { tmdbRepository.discover(any(), any()) } returns TmdbRepository.DiscoverPage(
            items = listOf(
                TmdbSearchResult(id = 1, title = "1995 年的", release_date = "1995-06-01"),
                TmdbSearchResult(id = 2, title = "2005 年的", release_date = "2005-06-01")
            ),
            totalPages = 1,
            totalResults = 2
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleDecade("1990-1999")
        viewModel.toggleDecade("2000-2009")
        viewModel.search()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items.map { it.id }).containsExactly(1, 2)
    }

    /** 电视剧看 first_air_date，不是 release_date */
    @Test
    fun `toggleDecade_电视剧按首播年份筛`() = runTest {
        coEvery { tmdbRepository.discover(any(), any()) } returns TmdbRepository.DiscoverPage(
            items = listOf(
                TmdbSearchResult(id = 1, title = "1995 首播", first_air_date = "1995-06-01"),
                TmdbSearchResult(id = 2, title = "2005 首播", first_air_date = "2005-06-01")
            ),
            totalPages = 1,
            totalResults = 2
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.switchType(TmdbRepository.DiscoverType.SHOW)
        viewModel.toggleDecade("1990-1999")
        viewModel.toggleDecade("2010-2019")
        viewModel.search()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items.map { it.id }).containsExactly(1)
    }

    /** 拿不到年份的条目保留：服务端已经按日期区间筛过，缺日期是数据不全，不是越界 */
    @Test
    fun `toggleDecade_条目没有日期_保留`() = runTest {
        coEvery { tmdbRepository.discover(any(), any()) } returns TmdbRepository.DiscoverPage(
            items = listOf(TmdbSearchResult(id = 1, title = "没日期")),
            totalPages = 1,
            totalResults = 1
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleDecade("1990-1999")
        viewModel.toggleDecade("2010-2019")
        viewModel.search()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items.map { it.id }).containsExactly(1)
    }

    @Test
    fun `toggleDecade_全部选项_清空选择`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.toggleDecade("1990-1999")
        viewModel.toggleDecade("2010-2019")

        viewModel.toggleDecade("0-0") // "全部"

        assertThat(viewModel.uiState.value.selectedDecadeKeys).isEmpty()
    }

    /** 选中年代要如实转成日期区间传给 TMDB */
    @Test
    fun `toggleDecade_日期区间按选中年份传参`() = runTest {
        coEvery { tmdbRepository.discover(any(), any()) } returns
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 1, totalResults = 2)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleDecade("1990-1999")
        viewModel.search()
        advanceUntilIdle()

        coVerify {
            tmdbRepository.discover(
                match { it.releaseDateStart == "1990-01-01" && it.releaseDateEnd == "1999-12-31" },
                1
            )
        }
    }

    // ==================== 结果条数 ====================

    /** 无日期条件的请求（首屏用），条数拿服务端给的值 */
    private fun stubNoDateSearch() {
        coEvery { tmdbRepository.discover(match { it.releaseDateStart == null }, any()) } returns
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 1, totalResults = 2)
    }

    /** 合并区间 1990-2019 的主请求：条数把没勾的 2000年代 也算进去了 */
    private fun stubMergedRange(totalPages: Int = 1, totalResults: Int = 900) {
        coEvery {
            tmdbRepository.discover(
                match { it.releaseDateStart == "1990-01-01" && it.releaseDateEnd == "2019-12-31" },
                any()
            )
        } returns TmdbRepository.DiscoverPage(
            items = listOf(
                TmdbSearchResult(id = 1, title = "1995 年的", release_date = "1995-06-01"),
                TmdbSearchResult(id = 2, title = "2005 年的", release_date = "2005-06-01"),
                TmdbSearchResult(id = 3, title = "2015 年的", release_date = "2015-06-01")
            ),
            totalPages = totalPages,
            totalResults = totalResults
        )
    }

    /**
     * 服务端报的是合并区间（1990-2019）的总数，比实际多。
     * 按两个连续块分别查再相加才是用户勾的那些年的真实条数。
     */
    @Test
    fun `年代不连续_条数按连续块分别查后相加`() = runTest {
        stubNoDateSearch()
        stubMergedRange()
        coEvery { tmdbRepository.discover(match { it.releaseDateEnd == "1999-12-31" }, 1) } returns
            TmdbRepository.DiscoverPage(items = emptyList(), totalPages = 1, totalResults = 200)
        coEvery { tmdbRepository.discover(match { it.releaseDateStart == "2010-01-01" }, 1) } returns
            TmdbRepository.DiscoverPage(items = emptyList(), totalPages = 1, totalResults = 300)

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.toggleDecade("1990-1999")
        viewModel.toggleDecade("2010-2019")
        viewModel.search()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.totalResults).isEqualTo(500) // 不是服务端的 900
        assertThat(state.totalResultsApproximate).isFalse()
    }

    /** 统计请求失败时退回服务端给的数并标成近似，不能因此报错 */
    @Test
    fun `年代不连续_统计请求失败_退回近似值`() = runTest {
        stubNoDateSearch()
        stubMergedRange()
        coEvery { tmdbRepository.discover(match { it.releaseDateEnd == "1999-12-31" }, 1) } throws
            IOException("network down")
        coEvery { tmdbRepository.discover(match { it.releaseDateStart == "2010-01-01" }, 1) } returns
            TmdbRepository.DiscoverPage(items = emptyList(), totalPages = 1, totalResults = 300)

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.toggleDecade("1990-1999")
        viewModel.toggleDecade("2010-2019")
        viewModel.search()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.totalResults).isEqualTo(900)
        assertThat(state.totalResultsApproximate).isTrue()
        // 条数没查准不是搜索失败，结果照样上屏
        assertThat(state.error).isNull()
        assertThat(state.items.map { it.id }).containsExactly(1, 3)
    }

    /** 段数太多就不查了：一次点击打出十几个请求不值得，直接标成近似 */
    @Test
    fun `年代分成太多段_不查准确条数`() = runTest {
        stubNoDateSearch()
        coEvery { tmdbRepository.discover(match { it.releaseDateStart != null }, any()) } returns
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 1, totalResults = 900)

        viewModel = createViewModel()
        advanceUntilIdle()
        // 7 段：1960年代 / 1980年代 / 2000年代 / 2020 / 2022 / 2024 / 2026
        listOf("1960-1969", "1980-1989", "2000-2009", "2020-2020", "2022-2022", "2024-2024", "2026-2026")
            .forEach { viewModel.toggleDecade(it) }
        viewModel.search()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.totalResults).isEqualTo(900)
        assertThat(state.totalResultsApproximate).isTrue()
        coVerify(exactly = 0) { tmdbRepository.discover(match { it.releaseDateEnd == "2009-12-31" }, 1) }
    }

    /** 「仅展示未标看过」是客户端过滤，服务端不知道用户标过什么，只能标"约" */
    @Test
    fun `仅展示未标看过_条数标成近似`() = runTest {
        val watchedIds = TraktRepository.WatchlistWatchedIds(movieWatchedTmdbIds = setOf(1))
        every { sessionModeManager.traktConnected } returns MutableStateFlow(true)
        coEvery { traktRepository.loadWatchlistWatchedIds() } returns watchedIds
        every { traktRepository.getWatchlistWatchedIds() } returns watchedIds
        coEvery { tmdbRepository.discover(any(), any()) } returns
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 1, totalResults = 900)

        viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.totalResultsApproximate).isFalse()

        viewModel.toggleHideWatched()
        viewModel.search()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.totalResultsApproximate).isTrue()
    }

    /** 没有客户端过滤时服务端给的数就是准的，不标"约" */
    @Test
    fun `无客户端过滤_条数不标近似`() = runTest {
        coEvery { tmdbRepository.discover(any(), any()) } returns
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 1, totalResults = 900)

        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.totalResults).isEqualTo(900)
        assertThat(viewModel.uiState.value.totalResultsApproximate).isFalse()
    }

    /** 翻页沿用首页算好的数，别用合并区间的总数把它盖回去，也别每页都重查一遍 */
    @Test
    fun `翻页时不重查条数且沿用首页的数`() = runTest {
        stubNoDateSearch()
        stubMergedRange(totalPages = 100, totalResults = 900)
        coEvery { tmdbRepository.discover(match { it.releaseDateEnd == "1999-12-31" }, 1) } returns
            TmdbRepository.DiscoverPage(items = emptyList(), totalPages = 1, totalResults = 200)
        coEvery { tmdbRepository.discover(match { it.releaseDateStart == "2010-01-01" }, 1) } returns
            TmdbRepository.DiscoverPage(items = emptyList(), totalPages = 1, totalResults = 300)

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.toggleDecade("1990-1999")
        viewModel.toggleDecade("2010-2019")
        viewModel.search()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.totalResults).isEqualTo(500)

        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.totalResults).isEqualTo(500)
        assertThat(viewModel.uiState.value.totalResultsApproximate).isFalse()
        coVerify(exactly = 1) { tmdbRepository.discover(match { it.releaseDateEnd == "1999-12-31" }, 1) }
    }

    /** 重叠/相接的选择要合成一段：点「2010年代」再点「2019」不该被当成两段多打一次请求 */
    @Test
    fun `年代重叠选择_合成一段不查条数`() = runTest {
        stubNoDateSearch()
        coEvery { tmdbRepository.discover(match { it.releaseDateStart == "2010-01-01" }, any()) } returns
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 1, totalResults = 900)

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.toggleDecade("2010-2019")
        viewModel.toggleDecade("2019-2019")
        viewModel.search()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.totalResults).isEqualTo(900)
        assertThat(state.totalResultsApproximate).isFalse()
        coVerify(exactly = 1) { tmdbRepository.discover(match { it.releaseDateStart == "2010-01-01" }, any()) }
    }

    // ==================== 仅展示未标看过 ====================

    /**
     * 回归测试：整页都被「仅展示未标看过」筛空时要继续往后翻。
     *
     * Bug 场景：客户端过滤把第一页全筛掉后 items 为空，屏幕显示「没有符合条件的结果」，
     * 而自动翻页要求 items 非空 —— 后面几页明明还有没看过的片，列表却卡死在空态。
     */
    @Test
    fun `hideWatched_整页都看过_自动往后翻到有结果的页`() = runTest {
        // 第 1 页全是看过的，第 2 页有没看过的
        val watchedIds = TraktRepository.WatchlistWatchedIds(movieWatchedTmdbIds = setOf(1, 2))
        every { traktRepository.getWatchlistWatchedIds() } returns watchedIds
        every { sessionModeManager.traktConnected } returns MutableStateFlow(true)
        coEvery { traktRepository.loadWatchlistWatchedIds() } returns watchedIds
        coEvery { tmdbRepository.discover(any(), 1) } returns
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 2, totalResults = 4)
        coEvery { tmdbRepository.discover(any(), 2) } returns TmdbRepository.DiscoverPage(
            items = listOf(TmdbSearchResult(id = 3, title = "没看过的")),
            totalPages = 2,
            totalResults = 4
        )

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.toggleHideWatched()
        viewModel.search()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.items.map { it.id }).containsExactly(3)
        // 翻到第 2 页才有结果，currentPage 要跟上，否则下次 loadMore 又去要第 2 页
        assertThat(state.currentPage).isEqualTo(2)
    }

    /**
     * 客户端过滤后一页只剩两三条时，要接着往后拉够一批再上屏。
     *
     * 只剩两三条撑不满一屏，列表滚不动就触发不了自动翻页，用户看着像是「就这么多」。
     * 拉够 MIN_BATCH_SIZE(10) 条或用完额外请求预算(4 页)为止。
     */
    @Test
    fun `hideWatched_一页只剩几条_继续往后拉够一批`() = runTest {
        // 每页 20 条，其中 18 条已看，只剩 2 条 —— 要拉 5 页才够 10 条
        fun pageItems(p: Int) = List(20) { TmdbSearchResult(id = p * 100 + it, title = "p$p-$it") }
        val watchedIds = TraktRepository.WatchlistWatchedIds(
            movieWatchedTmdbIds = (1..6).flatMap { p -> (2..19).map { p * 100 + it } }.toSet()
        )
        every { traktRepository.getWatchlistWatchedIds() } returns watchedIds
        every { sessionModeManager.traktConnected } returns MutableStateFlow(true)
        coEvery { traktRepository.loadWatchlistWatchedIds() } returns watchedIds
        (1..6).forEach { p ->
            coEvery { tmdbRepository.discover(any(), p) } returns
                TmdbRepository.DiscoverPage(items = pageItems(p), totalPages = 6, totalResults = 120)
        }

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.toggleHideWatched()
        viewModel.search()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.items).hasSize(10)
        assertThat(state.currentPage).isEqualTo(5)
        // 额外请求有上限，不能为了凑数把 6 页全拉下来
        coVerify(exactly = 0) { tmdbRepository.discover(any(), 6) }
    }

    /**
     * 回归测试：想看/已看 ID 还没加载完时，「仅展示未标看过」不能静默失效。
     *
     * Bug 场景：过滤分支要求 _watchlistWatchedIds != null，加载没完成时整页直接放行，
     * 开关看着是开的，看过的片照样排在最前面。
     */
    @Test
    fun `hideWatched_ID还在加载_先等ID再过滤`() = runTest {
        val idsGate = CompletableDeferred<TraktRepository.WatchlistWatchedIds>()
        val watchedIds = TraktRepository.WatchlistWatchedIds(movieWatchedTmdbIds = setOf(1))
        every { sessionModeManager.traktConnected } returns MutableStateFlow(true)
        coEvery { traktRepository.loadWatchlistWatchedIds() } coAnswers { idsGate.await() }
        every { traktRepository.getWatchlistWatchedIds() } returns watchedIds
        coEvery { tmdbRepository.discover(any(), any()) } returns
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 1, totalResults = 2)

        viewModel = createViewModel()
        advanceUntilIdle() // ID 加载卡在闸门上；首屏（没开筛选）结果已上屏
        assertThat(viewModel.uiState.value.items.map { it.id }).containsExactly(1, 2)

        viewModel.toggleHideWatched()
        viewModel.search()
        // 这里不能 advanceUntilIdle：等 ID 的 withTimeoutOrNull 有 3s 兜底，
        // 推进虚拟时间会直接跳到超时，测不到"先等 ID"这段行为
        assertThat(viewModel.uiState.value.items).isEmpty()
        assertThat(viewModel.uiState.value.isLoading).isTrue()

        idsGate.complete(watchedIds)
        advanceUntilIdle()

        // id=1 已看，被筛掉；id=2 保留
        assertThat(viewModel.uiState.value.items.map { it.id }).containsExactly(2)
    }

    /** TMDB 分页硬上限 500 页，越界会返回 400（"Invalid page"），到顶就当没有下一页 */
    @Test
    fun `loadMore_到达TMDB分页上限_不再请求`() = runTest {
        coEvery { tmdbRepository.discover(any(), any()) } returns
            TmdbRepository.DiscoverPage(items = testItems, totalPages = 900, totalResults = 18000)
        viewModel = createViewModel()
        advanceUntilIdle()

        // 一路翻到上限之后再多点几次，越界的那一页不该被请求
        repeat(520) {
            viewModel.loadMore()
            advanceUntilIdle()
        }

        assertThat(viewModel.uiState.value.currentPage).isEqualTo(500)
        coVerify(exactly = 0) { tmdbRepository.discover(any(), 501) }
    }
}
