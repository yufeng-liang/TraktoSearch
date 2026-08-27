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
     * expandAdvanced 只展开不收起。
     *
     * 评分 chip 用它：面板开着时再 toggleAdvanced 会把面板收起来，用户看起来是"点了没反应"。
     */
    @Test
    fun `expandAdvanced_已展开_保持展开`() = runTest {
        viewModel = createViewModel()
        viewModel.expandAdvanced()
        assertThat(viewModel.uiState.value.showAdvanced).isTrue()

        viewModel.expandAdvanced()
        assertThat(viewModel.uiState.value.showAdvanced).isTrue()
    }
}
