package com.tracktosearch.ui.screen.discover

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.DiscoverSectionStorage
import com.tracktosearch.data.local.SearchHistoryStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.remote.douban.DoubanHotApiService
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.dto.DoubanHotData
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendItem
import com.tracktosearch.data.remote.tmdb.dto.TmdbSearchResult
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktAnticipatedShowResponse
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingListResponse
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingMovieResponse
import com.tracktosearch.data.remote.trakt.dto.TraktTrendingShowResponse
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
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
 * DiscoverViewModel 单元测试。
 *
 * 验证发现页面的核心行为：
 * - TMDB 热门/即将上映加载成功/失败
 * - Trakt 社区列表加载成功/失败
 * - 豆瓣推荐未登录/已登录/切换Tab
 * - 切换时间窗口、刷新想看/已看缓存、强制刷新全部栏目
 *
 * 测试策略：
 * 1. sectionConfigs 的 stateIn 初始值是 ALL_SECTION_IDS（全部可见），init 块会触发
 *    loadDoubanHot/loadTmdbPopular/loadTmdbUpcoming。@Before 中 stub 这些方法避免 init 副作用报错。
 * 2. doubanAuthStorage.getCredentials() 返回 null，loadDoubanRecommend 设置 NotLoggedIn。
 * 3. Result 返回方法必须显式 stub（relaxed mock 默认返回 Result.failure(NPE)）。
 * 4. 异步方法用 runTest + advanceUntilIdle；同步方法直接调用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiscoverViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var doubanHotApi: DoubanHotApiService
    private lateinit var tmdbRepository: TmdbRepository
    private lateinit var traktRepository: TraktRepository
    private lateinit var searchHistoryStorage: SearchHistoryStorage
    private lateinit var viewedItemStorage: ViewedItemStorage
    private lateinit var discoverSectionStorage: DiscoverSectionStorage
    private lateinit var tokenStorage: TokenStorage
    private lateinit var sharedDoubanHotCache: PersistentTtlCache<DoubanHotData>
    private lateinit var doubanRepository: DoubanRepository
    private lateinit var doubanAuthStorage: DoubanAuthStorage
    private lateinit var doubanRecommendCache: PersistentTtlCache<List<DoubanRecommendItem>>
    private lateinit var context: Context

    private lateinit var viewModel: DiscoverViewModel

    @Before
    fun setup() {
        doubanHotApi = mockk(relaxed = true)
        tmdbRepository = mockk(relaxed = true)
        traktRepository = mockk(relaxed = true)
        searchHistoryStorage = mockk(relaxed = true)
        viewedItemStorage = mockk(relaxed = true)
        discoverSectionStorage = mockk(relaxed = true)
        tokenStorage = mockk(relaxed = true)
        sharedDoubanHotCache = mockk(relaxed = true)
        doubanRepository = mockk(relaxed = true)
        doubanAuthStorage = mockk(relaxed = true)
        doubanRecommendCache = mockk(relaxed = true)
        context = RuntimeEnvironment.getApplication()

        // init 块副作用 stub
        every { discoverSectionStorage.sectionConfigs } returns MutableStateFlow(emptyList())
        every { doubanAuthStorage.getCredentials() } returns null
        coEvery { traktRepository.loadWatchlistWatchedIds() } returns mockk(relaxed = true)
        every { traktRepository.getWatchlistWatchedIds() } returns null
        every { tokenStorage.accessToken } returns MutableStateFlow(null)

        // sectionConfigs 的 stateIn 初始值是 ALL_SECTION_IDS（全部可见），
        // init 块的 loadInitialSections 会触发 loadDoubanHot/loadTmdbPopular/loadTmdbUpcoming
        coEvery { sharedDoubanHotCache.getOrAwait(any(), any(), any()) } returns DoubanHotData()
        coEvery { tmdbRepository.getTrendingMovies(any<String>()) } returns emptyList()
        coEvery { tmdbRepository.getUpcomingMovies() } returns emptyList()

        viewModel = DiscoverViewModel(
            doubanHotApi, tmdbRepository, traktRepository, searchHistoryStorage,
            viewedItemStorage, discoverSectionStorage, tokenStorage,
            sharedDoubanHotCache, doubanRepository, doubanAuthStorage,
            doubanRecommendCache, context
        )
    }

    /** 构造测试用 TmdbSearchResult */
    private fun makeTmdbResult(id: Int, title: String = "Test Movie"): TmdbSearchResult {
        return TmdbSearchResult(id = id, title = title)
    }

    // ===== TMDB 加载测试 =====

    @Test
    fun `loadTmdbPopular 成功加载热门电影`() = runTest {
        coEvery { tmdbRepository.getTrendingMovies(any<String>()) } returns
            listOf(makeTmdbResult(1), makeTmdbResult(2))

        viewModel.loadTmdbPopular()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.tmdbPopularMovies).hasSize(2)
        assertThat(viewModel.uiState.value.isLoadingPopular).isFalse()
        assertThat(viewModel.uiState.value.popularError).isNull()
    }

    @Test
    fun `loadTmdbPopular 失败时设置错误信息`() = runTest {
        coEvery { tmdbRepository.getTrendingMovies(any<String>()) } throws
            IOException("network error")

        viewModel.loadTmdbPopular()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoadingPopular).isFalse()
        assertThat(viewModel.uiState.value.popularError).isNotNull()
        assertThat(viewModel.uiState.value.popularError).contains("network error")
    }

    @Test
    fun `loadTmdbUpcoming 成功加载即将上映`() = runTest {
        coEvery { tmdbRepository.getUpcomingMovies() } returns listOf(makeTmdbResult(1))

        viewModel.loadTmdbUpcoming()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.tmdbUpcomingMovies).hasSize(1)
        assertThat(viewModel.uiState.value.isLoadingUpcoming).isFalse()
    }

    @Test
    fun `loadTmdbUpcoming 失败时设置错误信息`() = runTest {
        coEvery { tmdbRepository.getUpcomingMovies() } throws IOException("error")

        viewModel.loadTmdbUpcoming()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.upcomingError).isNotNull()
    }

    @Test
    fun `loadTmdbUpcoming 被取消时不展示内部取消错误`() = runTest {
        coEvery { tmdbRepository.getUpcomingMovies() } throws CancellationException("Fetch cancelled")

        viewModel.loadTmdbUpcoming()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.upcomingError).isNull()
    }

    // ===== Trakt 加载测试 =====

    @Test
    fun `loadTraktLists 成功加载社区列表`() = runTest {
        coEvery { traktRepository.getTrendingLists(any(), any()) } returns
            Result.success(listOf(mockk(relaxed = true)))

        viewModel.loadTraktLists()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.trendingLists).hasSize(1)
        assertThat(viewModel.uiState.value.isLoadingTraktLists).isFalse()
        assertThat(viewModel.uiState.value.trendingListsError).isNull()
    }

    @Test
    fun `loadTraktLists 失败时设置错误信息`() = runTest {
        coEvery { traktRepository.getTrendingLists(any(), any()) } returns
            Result.failure(IOException("error"))

        viewModel.loadTraktLists()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoadingTraktLists).isFalse()
        assertThat(viewModel.uiState.value.trendingListsError).isNotNull()
    }

    // ===== 豆瓣推荐测试 =====

    @Test
    fun `loadDoubanRecommend 未登录时状态为NotLoggedIn`() {
        viewModel.loadDoubanRecommend()

        assertThat(viewModel.uiState.value.doubanRecommendState).isEqualTo(DoubanRecommendState.NotLoggedIn)
    }

    @Test
    fun `loadDoubanRecommend 已登录时加载成功`() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user1", "cookie")
        coEvery { doubanRecommendCache.getOrAwait(any(), any(), any()) } returns emptyList()

        viewModel.loadDoubanRecommend()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.doubanRecommendState).isInstanceOf(DoubanRecommendState.Success::class.java)
    }

    @Test
    fun `refreshDoubanRecommendOnResume refreshes recommendations after login`() = runTest {
        every { doubanAuthStorage.getCredentials() } returnsMany listOf(
            null,
            DoubanCredentials("user1", "cookie")
        )
        coEvery { doubanRecommendCache.getOrAwait(any(), any(), any()) } returns emptyList()

        viewModel.refreshDoubanRecommendOnResume()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.doubanRecommendState)
            .isInstanceOf(DoubanRecommendState.Success::class.java)
    }

    @Test
    fun `switchRecommendTab 切换猜你喜欢Tab`() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user1", "cookie")
        coEvery { doubanRecommendCache.getOrAwait(any(), any(), any()) } returns emptyList()

        viewModel.loadDoubanRecommend()
        advanceUntilIdle()

        val initialState = viewModel.uiState.value.doubanRecommendState
        assertThat(initialState).isInstanceOf(DoubanRecommendState.Success::class.java)
        assertThat((initialState as DoubanRecommendState.Success).currentTab).isEqualTo(RecommendTab.MOVIE)

        viewModel.switchRecommendTab(RecommendTab.TV)

        val switchedState = viewModel.uiState.value.doubanRecommendState
        assertThat(switchedState).isInstanceOf(DoubanRecommendState.Success::class.java)
        assertThat((switchedState as DoubanRecommendState.Success).currentTab).isEqualTo(RecommendTab.TV)
    }

    // ===== 其他测试 =====

    @Test
    fun `switchTrendingTimeWindow 切换时间窗口`() = runTest {
        assertThat(viewModel.uiState.value.trendingTimeWindow).isEqualTo("day")

        viewModel.switchTrendingTimeWindow("week")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.trendingTimeWindow).isEqualTo("week")
    }

    @Test
    fun `refreshWatchlistWatchedIds 更新缓存快照`() {
        every { traktRepository.getWatchlistWatchedIds() } returns mockk(relaxed = true)

        viewModel.refreshWatchlistWatchedIds()

        assertThat(viewModel.watchlistWatchedIds.value).isNotNull()
    }

    @Test
    fun `forceRefreshAll 触发所有栏目加载`() = runTest {
        // forceRefreshAll → loadVisibleSections → loadDoubanHot + loadDoubanRecommend +
        // loadTmdbPopular + loadTmdbUpcoming + loadRemainingSections(force=true)
        // loadRemainingSections 会触发 loadTraktRecommendations/loadTraktLists/loadTraktData
        coEvery { sharedDoubanHotCache.getOrAwait(any(), any(), any()) } returns DoubanHotData()
        coEvery { tmdbRepository.getTrendingMovies(any<String>()) } returns emptyList()
        coEvery { tmdbRepository.getUpcomingMovies() } returns emptyList()
        coEvery { tmdbRepository.getTopRatedMovies() } returns emptyList()
        coEvery { traktRepository.getRecommendations(any()) } returns
            Result.success(emptyList<TraktMovie>())
        coEvery { traktRepository.getTrendingMovies(any(), any()) } returns
            Result.success(emptyList<TraktTrendingMovieResponse>() to 0)
        coEvery { traktRepository.getTrendingShows(any(), any()) } returns
            Result.success(emptyList<TraktTrendingShowResponse>() to 0)
        coEvery { traktRepository.getAnticipatedMovies(any(), any()) } returns
            Result.success(emptyList<TraktAnticipatedMovieResponse>() to 0)
        coEvery { traktRepository.getAnticipatedShows(any(), any()) } returns
            Result.success(emptyList<TraktAnticipatedShowResponse>() to 0)
        coEvery { traktRepository.getTrendingLists(any(), any()) } returns
            Result.success(emptyList<TraktTrendingListResponse>())

        viewModel.forceRefreshAll()
        advanceUntilIdle()

        coVerify { tmdbRepository.getTrendingMovies(any<String>()) }
        coVerify { tmdbRepository.getUpcomingMovies() }
    }
}
