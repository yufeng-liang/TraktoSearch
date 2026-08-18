package com.tracktosearch.ui.screen.search

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.local.SearchHistoryItem
import com.tracktosearch.data.local.SearchHistoryStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.DoubanRexxarApiService
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.dto.ResourceType
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import android.content.Context
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
 * SearchViewModel 单元测试。
 *
 * 验证搜索页的核心行为：
 * - loadHotSearches() 成功填充热词 / 本地缓存命中不重复请求
 * - search() 成功/空查询/竞态取消/网络失败
 * - setTypeFilter/clearResults 同步方法状态更新
 * - clearHistory/removeHistory/markViewed 委托到 Storage
 * - getSuggestions 从搜索历史过滤建议
 *
 * 测试策略：
 * 1. init 块会收集 searchHistoryStorage.history 并调用 loadHotSearches()。
 *    @Before 中默认桩 getOrAwait 抛异常 + get 返回 null，使 init 的 loadHotSearches
 *    静默失败，不污染 hotSearchCache，不干扰后续测试。
 * 2. 测 loadHotSearches 的测试点（1、2）在 createViewModel 前覆盖 getOrAwait 桩为返回数据。
 * 3. 同步方法（setTypeFilter/clearResults/getSuggestions）直接验证 uiState.value 字段，无需 advanceUntilIdle。
 * 4. 异步方法（search/loadHotSearches/removeHistory/clearHistory/markViewed）需 advanceUntilIdle 推进协程。
 * 5. searchResourcesFlow 是非 suspend 函数返回 Flow，用 every + flowOf/flow 桩。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val resourceRepository = mockk<ResourceRepository>(relaxed = true)
    private val searchHistoryStorage = mockk<SearchHistoryStorage>(relaxed = true)
    private val viewedItemStorage = mockk<ViewedItemStorage>(relaxed = true)
    private val doubanRexxarApi = mockk<DoubanRexxarApiService>(relaxed = true)
    private val doubanRepository = mockk<DoubanRepository>(relaxed = true)
    private val doubanAuthStorage = mockk<DoubanAuthStorage>(relaxed = true)
    private val tmdbRepository = mockk<TmdbRepository>(relaxed = true)
    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val sharedDoubanHotCache = mockk<PersistentTtlCache<DoubanHotData>>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var viewModel: SearchViewModel

    /** 搜索历史 Flow，各测试可动态修改 */
    private val historyFlow = MutableStateFlow<List<SearchHistoryItem>>(emptyList())

    // ==================== 测试数据 ====================

    private val resourceItemA = ResourceItem(
        name = "盗梦空间.2010.BluRay",
        diskType = DiskType.QUARK,
        fileSize = "10GB",
        url = "https://example.com/a",
        source = "sourceA"
    )

    private val resourceItemB = ResourceItem(
        name = "星际穿越.2014.BluRay",
        diskType = DiskType.BAIDU,
        fileSize = "15GB",
        url = "https://example.com/b",
        source = "sourceB"
    )

    /** 豆瓣热榜数据（带【评分】前缀，模拟 getOrAwait 返回值） */
    private val doubanHotDataWithItems = DoubanHotData(
        items = listOf(
            DoubanHotItem(id = 1, title = "【8.5】盗梦空间"),
            DoubanHotItem(id = 2, title = "【9.0】星际穿越"),
            DoubanHotItem(id = 3, title = "【8.0】禁闭岛")
        ),
        total = 3
    )

    // ==================== 生命周期 ====================

    @Before
    fun setup() {
        clearMocks(
            resourceRepository, searchHistoryStorage, viewedItemStorage,
            doubanRexxarApi, doubanRepository, doubanAuthStorage,
            tmdbRepository, traktRepository, sharedDoubanHotCache
        )
        historyFlow.value = emptyList()

        // init 会收集 searchHistoryStorage.history 并调用 loadHotSearches()
        every { searchHistoryStorage.history } returns historyFlow

        // 默认桩：loadHotSearches 静默失败（不污染 hotSearchCache），各测试可覆盖
        coEvery { sharedDoubanHotCache.getOrAwait(any(), any(), any()) } throws IOException("test default")
        coEvery { sharedDoubanHotCache.get(any()) } returns null
    }

    private fun createViewModel(): SearchViewModel {
        return SearchViewModel(
            resourceRepository = resourceRepository,
            searchHistoryStorage = searchHistoryStorage,
            viewedItemStorage = viewedItemStorage,
            doubanRexxarApi = doubanRexxarApi,
            doubanRepository = doubanRepository,
            doubanAuthStorage = doubanAuthStorage,
            tmdbRepository = tmdbRepository,
            traktRepository = traktRepository,
            sharedDoubanHotCache = sharedDoubanHotCache,
            context = context
        )
    }

    // ==================== 测试 ====================

    /**
     * 测试点1：loadHotSearches() 成功 → hotSearches StateFlow 填充热词列表
     *
     * init 调用 loadHotSearches()，getOrAwait 返回带【评分】前缀的 DoubanHotData，
     * loadHotSearches 用正则去除前缀后取前 8 条标题填充 _hotSearches。
     */
    @Test
    fun `loadHotSearches_成功_hotSearches填充热词列表`() = runTest {
        // 覆盖默认桩：getOrAwait 返回带数据的 DoubanHotData
        coEvery { sharedDoubanHotCache.getOrAwait(any(), any(), any()) } returns doubanHotDataWithItems

        viewModel = createViewModel()
        advanceUntilIdle()

        // 3 条热词，【评分】前缀已去除
        assertThat(viewModel.hotSearches.value).hasSize(3)
        assertThat(viewModel.hotSearches.value).containsExactly("盗梦空间", "星际穿越", "禁闭岛")
    }

    /**
     * 测试点2：loadHotSearches() 第二次调用命中本地 hotSearchCache → 不再调用 sharedDoubanHotCache
     *
     * init 的第一次 loadHotSearches 成功后，hotSearchCache 已缓存热词列表。
     * 显式调用 loadHotSearches() 时命中本地缓存直接 return，不再调用 sharedDoubanHotCache.getOrAwait。
     */
    @Test
    fun `loadHotSearches_第二次命中本地缓存_不调用sharedDoubanHotCache`() = runTest {
        coEvery { sharedDoubanHotCache.getOrAwait(any(), any(), any()) } returns doubanHotDataWithItems

        viewModel = createViewModel()
        advanceUntilIdle()

        // 第一次（init 调用）已执行 getOrAwait 1 次
        coVerify(exactly = 1) { sharedDoubanHotCache.getOrAwait(any(), any(), any()) }

        // 第二次显式调用，应命中本地 hotSearchCache 短路
        viewModel.loadHotSearches()
        advanceUntilIdle()

        // 仍然只调用 1 次（第二次命中本地缓存未到达 sharedDoubanHotCache）
        coVerify(exactly = 1) { sharedDoubanHotCache.getOrAwait(any(), any(), any()) }
    }

    /**
     * 测试点3：search("盗梦空间") 成功 → uiState.resources 填充，isLoading=false
     *
     * search() 收集 searchResourcesFlow 返回的 Flow，成功时缓存结果并更新 resources。
     * 额外验证：第二次 search 同一关键词命中 searchResultCache，不重复调用 searchResourcesFlow。
     */
    @Test
    fun `search_成功_resources填充且isLoading为false`() = runTest {
        every {
            resourceRepository.searchResourcesFlow(any(), any(), any(), any(), any())
        } returns flowOf(listOf(resourceItemA))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search("盗梦空间")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.resources).hasSize(1)
        assertThat(state.resources[0].name).isEqualTo("盗梦空间.2010.BluRay")
        assertThat(state.isLoading).isFalse()
        assertThat(state.keyword).isEqualTo("盗梦空间")
        assertThat(state.error).isNull()

        // 额外验证：searchResultCache 缓存命中，第二次 search 同一关键词不调 searchResourcesFlow
        viewModel.search("盗梦空间")
        advanceUntilIdle()

        verify(exactly = 1) { resourceRepository.searchResourcesFlow(any(), any(), any(), any(), any()) }
    }

    /**
     * 测试点4：search("") 空关键词 → 不触发搜索
     *
     * search() 第 458 行 `if (keyword.isBlank()) return`，直接返回不启动协程。
     */
    @Test
    fun `search_空关键词_不触发搜索`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search("")
        advanceUntilIdle()

        verify(exactly = 0) { resourceRepository.searchResourcesFlow(any(), any(), any(), any(), any()) }
        assertThat(viewModel.uiState.value.resources).isEmpty()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    /**
     * 测试点5：快速连续两次 search → 只保留第二次结果（searchJob?.cancel 取消旧任务）
     *
     * StandardTestDispatcher 下，第一次 search 的协程尚未执行即被第二次 search 的
     * searchJob?.cancel 取消。advanceUntilIdle 后只有第二次结果存活。
     */
    @Test
    fun `search_快速连续两次_只保留第二次结果`() = runTest {
        // 用 answers + firstArg 按 keyword 返回不同结果，避免使用 eq 匹配器
        every {
            resourceRepository.searchResourcesFlow(any(), any(), any(), any(), any())
        } answers {
            when (firstArg<String>()) {
                "query1" -> flowOf(listOf(resourceItemA))
                else -> flowOf(listOf(resourceItemB))
            }
        }

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search("query1")
        viewModel.search("query2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.resources).hasSize(1)
        assertThat(state.resources[0].name).isEqualTo("星际穿越.2014.BluRay")
        assertThat(state.keyword).isEqualTo("query2")
    }

    /**
     * 测试点6：setTypeFilter(MOVIE) → uiState.typeFilter 更新
     *
     * setTypeFilter 是同步方法，直接修改 _uiState，无需 advanceUntilIdle。
     */
    @Test
    fun `setTypeFilter_设置MOVIE_typeFilter更新`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.typeFilter).isEqualTo(ResourceType.ALL)

        viewModel.setTypeFilter(ResourceType.MOVIE)

        assertThat(viewModel.uiState.value.typeFilter).isEqualTo(ResourceType.MOVIE)
    }

    /**
     * 测试点7：clearResults() → resources 清空，keyword 清空
     *
     * clearResults 是同步方法，直接重置 _uiState，无需 advanceUntilIdle。
     */
    @Test
    fun `clearResults_清空resources和keyword`() = runTest {
        every {
            resourceRepository.searchResourcesFlow(any(), any(), any(), any(), any())
        } returns flowOf(listOf(resourceItemA))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search("盗梦空间")
        advanceUntilIdle()

        // 确认有结果
        assertThat(viewModel.uiState.value.resources).hasSize(1)
        assertThat(viewModel.uiState.value.keyword).isEqualTo("盗梦空间")

        viewModel.clearResults()

        assertThat(viewModel.uiState.value.resources).isEmpty()
        assertThat(viewModel.uiState.value.keyword).isEmpty()
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    /**
     * 测试点8：clearHistory() → searchHistoryStorage.clear() 被调用
     */
    @Test
    fun `clearHistory_调用searchHistoryStorage的clear`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.clearHistory()
        advanceUntilIdle()

        coVerify(exactly = 1) { searchHistoryStorage.clear() }
    }

    /**
     * 测试点9：removeHistory("测试") → searchHistoryStorage.remove("测试") 被调用
     */
    @Test
    fun `removeHistory_调用searchHistoryStorage的remove`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.removeHistory("测试")
        advanceUntilIdle()

        coVerify(exactly = 1) { searchHistoryStorage.remove("测试") }
    }

    /**
     * 测试点9a：removeHistory("痴迷", "person") → searchHistoryStorage.remove("痴迷", "person") 被调用
     *
     * 回归 Bug 5：删除人物"痴迷"时只删 person 类型，保留 movie 类型。
     * ViewModel 必须把 type 透传给 storage，不能丢弃。
     */
    @Test
    fun `removeHistory_带type_透传type到storage`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.removeHistory("痴迷", "person")
        advanceUntilIdle()

        coVerify(exactly = 1) { searchHistoryStorage.remove("痴迷", "person") }
    }

    /**
     * 测试点9b：addTraktHistory("痴迷", "movie") → searchHistoryStorage.add("痴迷", "movie") 被调用
     *
     * 回归 Bug 4：热门搜索点击后应记录历史（之前 onPopularClick 漏调 addTraktHistory）。
     * ViewModel 的 addTraktHistory 必须把 type 透传给 storage。
     */
    @Test
    fun `addTraktHistory_带type_透传type到storage`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addTraktHistory("痴迷", "movie")
        advanceUntilIdle()

        coVerify(exactly = 1) { searchHistoryStorage.add("痴迷", "movie") }
    }

    /**
     * 测试点10：getSuggestions("测") → 返回匹配的搜索历史
     *
     * getSuggestions 是同步方法，从 _uiState.value.searchHistory 过滤。
     * 需先通过 historyFlow 设置搜索历史，advanceUntilIdle 让 init 收集到。
     * 过滤规则：startsWith 或 contains（忽略大小写），排除完全匹配，取前 5 条。
     */
    @Test
    fun `getSuggestions_输入部分关键词_返回匹配的搜索历史`() = runTest {
        historyFlow.value = listOf(
            SearchHistoryItem("测试电影", "disk"),
            SearchHistoryItem("测", "disk"),       // 完全匹配，被排除
            SearchHistoryItem("电影", "disk"),      // 不匹配
            SearchHistoryItem("测试剧集", "disk")
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        // 确认 searchHistory 已通过 init 的 stateIn 收集
        assertThat(viewModel.uiState.value.searchHistory).hasSize(4)

        val suggestions = viewModel.getSuggestions("测")

        // "测" 完全匹配被排除，"电影" 不匹配，剩下 "测试电影" 和 "测试剧集"
        assertThat(suggestions).hasSize(2)
        assertThat(suggestions[0].keyword).isEqualTo("测试电影")
        assertThat(suggestions[1].keyword).isEqualTo("测试剧集")
    }

    /**
     * 测试点11：search() 网络失败（Flow 抛异常）→ uiState.error 非空
     *
     * searchResourcesFlow 返回的 Flow 在 collect 时抛 IOException，
     * search() 第 501 行 catch 分支设置 error = e.message。
     */
    @Test
    fun `search_网络失败_error非空`() = runTest {
        every {
            resourceRepository.searchResourcesFlow(any(), any(), any(), any(), any())
        } returns flow<List<ResourceItem>> {
            throw IOException("网络错误")
        }
        every { context.getString(R.string.error_network_unavailable) } returns "网络错误"

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.search("盗梦空间")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("网络错误")
        assertThat(state.resources).isEmpty()
        assertThat(state.isLoading).isFalse()
    }

    /**
     * 测试点12：markViewed(url) → viewedItemStorage.markViewed(url) 被调用
     */
    @Test
    fun `markViewed_调用viewedItemStorage的markViewed`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.markViewed("https://example.com/resource")
        advanceUntilIdle()

        coVerify(exactly = 1) { viewedItemStorage.markViewed("https://example.com/resource") }
    }

    @Test
    fun `resolveAndNavigate_榜单项已有完整ID时直接导航且不搜索`() = runTest {
        viewModel = createViewModel()
        var navigated: List<Any>? = null

        viewModel.resolveAndNavigate(
            DoubanHotItem(
                id = 42,
                title = "【9.0】测试电影",
                tmdbId = 597,
                traktId = 10,
                imdbId = "tt0120338"
            )
        ) { traktId, tmdbId, title, imdbId, traktRating ->
            navigated = listOf(traktId, tmdbId, title, imdbId, traktRating)
        }
        advanceUntilIdle()

        assertThat(navigated).containsExactly(10, 597, "测试电影", "tt0120338", 0.0).inOrder()
        coVerify(exactly = 0) { tmdbRepository.searchMovie(any()) }
        coVerify(exactly = 0) { traktRepository.searchByTmdb(any(), any()) }
    }
}
