package com.tracktosearch.ui.screen.watchlist

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.CooldownStatus
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.db.MediaItemEntity
import com.tracktosearch.data.local.db.OfflineCacheManager
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.trakt.dto.TraktIds
import com.tracktosearch.data.remote.trakt.dto.TraktImages
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.remote.trakt.dto.TraktSyncResponse
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.BatchRemovalProgress
import com.tracktosearch.data.repository.ConsistencyCheckResult
import com.tracktosearch.data.repository.DoubanBatchRemovalManager
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.data.repository.DoubanTraktStatusConsistencyChecker
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.SyncMode
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.WatchlistMediaType
import com.tracktosearch.data.session.SessionMode
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * WatchlistViewModel 单元测试。
 *
 * 验证想看/已看页面的核心行为：
 * - loadMovies/loadShows/loadHistoryMovies 成功加载、失败降级离线缓存、silent 模式、防并发
 * - refresh/refreshIfLoaded 刷新逻辑
 * - batchRemoveFromWatchlist/batchRemoveFromHistory 批量移除 + 豆瓣同步
 * - startDoubanSync/isDoubanLoggedIn 豆瓣相关
 * - updateSelectedGenres/resetFilters 筛选状态
 *
 * 测试策略：
 * 1. init 块启动 checkFirstSyncNeeded + 3 个进度 collector。@Before 中 stub getCredentials()=null
 *    避免首次同步引导，3 个 StateFlow 返回默认实例使 collector 不触发逻辑。
 * 2. tmdbId=0 短路 enrich，不调 tmdbRepository。
 * 3. Result 返回方法必须显式 stub（relaxed mock 默认返回 Result.failure(NPE)）。
 * 4. 异步方法用 runTest + advanceUntilIdle；suspend 方法直接在 runTest 中调用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class WatchlistViewModelTest {

    // 与 Main 共用同一个 TestDispatcher：ViewModel 里的计算调度器也走虚拟时间，
    // advanceUntilIdle 仍能确定性推进 withContext(computeDispatcher) 里的工作
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private lateinit var traktRepository: TraktRepository
    private lateinit var tmdbRepository: TmdbRepository
    private lateinit var offlineCacheManager: OfflineCacheManager
    private lateinit var doubanSyncManager: DoubanSyncManager
    private lateinit var doubanAuthStorage: DoubanAuthStorage
    private lateinit var doubanSyncMetaStorage: DoubanSyncMetaStorage
    private lateinit var statusConsistencyChecker: DoubanTraktStatusConsistencyChecker
    private lateinit var doubanBatchRemovalManager: DoubanBatchRemovalManager
    private lateinit var sessionModeManager: SessionModeManager
    private lateinit var doubanSyncedItemDao: DoubanSyncedItemDao
    private lateinit var context: Context

    private lateinit var viewModel: WatchlistViewModel

    private val syncProgressFlow = MutableStateFlow(DoubanSyncProgress())
    private val consistencyProgressFlow = MutableStateFlow(ConsistencyCheckResult())
    private val batchRemovalProgressFlow = MutableStateFlow(BatchRemovalProgress())
    private val watchlistMutationFlow = MutableSharedFlow<TraktRepository.WatchlistMutation>(extraBufferCapacity = 8)

    @Before
    fun setup() {
        traktRepository = mockk(relaxed = true)
        tmdbRepository = mockk(relaxed = true)
        offlineCacheManager = mockk(relaxed = true)
        doubanSyncManager = mockk(relaxed = true)
        doubanAuthStorage = mockk(relaxed = true)
        doubanSyncMetaStorage = mockk(relaxed = true)
        statusConsistencyChecker = mockk(relaxed = true)
        doubanBatchRemovalManager = mockk(relaxed = true)
        sessionModeManager = mockk(relaxed = true)
        doubanSyncedItemDao = mockk(relaxed = true)
        context = RuntimeEnvironment.getApplication()

        // init 块副作用 stub
        every { doubanSyncManager.progress } returns syncProgressFlow
        every { statusConsistencyChecker.checkProgress } returns consistencyProgressFlow
        every { statusConsistencyChecker.consumeCheckCompleteEvent() } returns true
        every { doubanBatchRemovalManager.progress } returns batchRemovalProgressFlow
        every { sessionModeManager.isDoubanMode } returns MutableStateFlow(false)
        every { sessionModeManager.traktConnected } returns MutableStateFlow(false)
        // 默认覆盖 Trakt 看单用例；访客模式由专门用例显式设置，避免测试夹具跳过实际加载路径。
        every { sessionModeManager.sessionMode } returns MutableStateFlow(SessionMode.TRAKT)
        every { doubanAuthStorage.isLoggedIn } returns MutableStateFlow(false)
        every { doubanAuthStorage.getCredentials() } returns null
        every { traktRepository.watchlistMutations } returns watchlistMutationFlow
        coEvery { doubanSyncMetaStorage.getCooldownStatus(any()) } returns CooldownStatus(neverSynced = false)

        coEvery { offlineCacheManager.getMediaItems(any()) } returns emptyList()
        every { traktRepository.getLocallyWatchedOnlyTraktIds(any()) } returns emptySet()
        every { traktRepository.getLocallyWatchlistOnlyTraktIds(any()) } returns emptySet()

        // 默认判定为「活动时间有变化，需要拉取」；单个用例再按需覆盖为跳过
        coEvery { traktRepository.resolveWatchlistRefreshPlan(any()) } returns
            TraktRepository.WatchlistRefreshPlan(
                shouldRefreshMovies = true,
                shouldRefreshShows = true,
                activitiesAvailable = true,
                moviesWatchlistedAt = "2024-01-01T00:00:00Z",
                showsWatchlistedAt = "2024-01-01T00:00:00Z"
            )
        every { tmdbRepository.currentLanguageTag() } returns "zh-CN"

        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(emptyList<TraktWatchlistMovieItem>() to 1)
        coEvery { traktRepository.getShowWatchlist(any(), any(), any()) } returns
            Result.success(emptyList<TraktWatchlistShowItem>() to 1)
        coEvery { traktRepository.getMovieHistory(any(), any(), any()) } returns
            Result.success(emptyList<TraktWatchlistMovieItem>() to 1)
        coEvery { traktRepository.getShowHistory(any(), any(), any()) } returns
            Result.success(emptyList<TraktWatchlistShowItem>() to 1)

        viewModel = WatchlistViewModel(
            traktRepository, tmdbRepository, offlineCacheManager,
            doubanSyncManager, doubanAuthStorage, doubanSyncMetaStorage,
            statusConsistencyChecker, doubanBatchRemovalManager,
            sessionModeManager, doubanSyncedItemDao, testDispatcher, context
        )
    }

    private fun makeWatchlistMovie(
        traktId: Int,
        title: String = "Test Movie",
        imdbId: String = "tt123",
        tmdb: Int = 0
    ): TraktWatchlistMovieItem = TraktWatchlistMovieItem(
        listed_at = "2024-01-01T00:00:00Z",
        movie = TraktMovie(
            title = title, year = 2023,
            ids = TraktIds(trakt = traktId, tmdb = tmdb, imdb = imdbId),
            rating = 8.0
        )
    )

    private fun makeWatchlistShow(
        traktId: Int,
        title: String = "Test Show",
        imdbId: String = "tt456",
        tmdb: Int = 0
    ): TraktWatchlistShowItem = TraktWatchlistShowItem(
        listed_at = "2024-01-01T00:00:00Z",
        show = TraktShow(
            title = title, year = 2023,
            ids = TraktIds(trakt = traktId, tmdb = tmdb, imdb = imdbId),
            rating = 8.0
        )
    )

    @Test
    fun `从访客切换到Trakt后重新加载看单`() = runTest {
        val sessionMode = MutableStateFlow(SessionMode.GUEST)
        every { sessionModeManager.sessionMode } returns sessionMode
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1, "登录后的电影")) to 1)
        coEvery { traktRepository.getShowWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistShow(2, "登录后的剧集")) to 1)

        viewModel.loadMovies()
        viewModel.loadShows()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies).isEmpty()
        assertThat(viewModel.uiState.value.shows).isEmpty()
        coVerify(exactly = 0) { traktRepository.getMovieWatchlist(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.getShowWatchlist(any(), any(), any()) }

        sessionMode.value = SessionMode.TRAKT
        viewModel.onSessionModeChanged(SessionMode.TRAKT)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.title }).containsExactly("登录后的电影")
        assertThat(viewModel.uiState.value.shows.map { it.title }).containsExactly("登录后的剧集")
    }

    @Test
    fun `从访客切换到豆瓣后重新加载本地看单`() = runTest {
        val sessionMode = MutableStateFlow(SessionMode.GUEST)
        every { sessionModeManager.sessionMode } returns sessionMode
        coEvery { doubanSyncedItemDao.getByStatus("wish") } returns listOf(
            makeDoubanItem("movie-1", title = "登录后的豆瓣电影"),
            makeDoubanItem("show-1", mediaType = "show", title = "登录后的豆瓣剧集")
        )

        viewModel.loadMovies()
        viewModel.loadShows()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies).isEmpty()
        assertThat(viewModel.uiState.value.shows).isEmpty()
        coVerify(exactly = 0) { doubanSyncedItemDao.getByStatus("wish") }

        sessionMode.value = SessionMode.DOUBAN
        viewModel.onSessionModeChanged(SessionMode.DOUBAN)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.title }).containsExactly("登录后的豆瓣电影")
        assertThat(viewModel.uiState.value.shows.map { it.title }).containsExactly("登录后的豆瓣剧集")
    }

    @Test
    fun `会话未变化时重复进入页面不重新加载列表`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1, "已有电影")) to 1)
        coEvery { traktRepository.getShowWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistShow(2, "已有剧集")) to 1)

        viewModel.onSessionModeChanged(SessionMode.TRAKT)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.title }).containsExactly("已有电影")
        assertThat(viewModel.uiState.value.shows.map { it.title }).containsExactly("已有剧集")
        coVerify(exactly = 1) { traktRepository.getMovieWatchlist(any(), any(), any()) }

        // 模拟从详情页返回：同一会话再次调用不应重置列表也不重新请求
        viewModel.onSessionModeChanged(SessionMode.TRAKT)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.title }).containsExactly("已有电影")
        assertThat(viewModel.uiState.value.shows.map { it.title }).containsExactly("已有剧集")
        coVerify(exactly = 1) { traktRepository.getMovieWatchlist(any(), any(), any()) }
    }

    @Test
    fun `loadWatchlist_uses_server_total_count`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1)) to 2)
        coEvery { traktRepository.getShowWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistShow(2)) to 1)
        every { traktRepository.getMovieWatchlistTotalCount(1, 200) } returns 237
        every { traktRepository.getShowWatchlistTotalCount(1, 200) } returns 19

        viewModel.loadMovies()
        viewModel.loadShows()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movieTotalCount).isEqualTo(237)
        assertThat(viewModel.uiState.value.showTotalCount).isEqualTo(19)
    }

    @Test
    fun `loadMoreWatchlist_电影和电视剧跨页并按最新标记在前`() = runTest {
        val oldMovie = makeWatchlistMovie(1).copy(listed_at = "2024-01-01T00:00:00Z")
        val newMovie = makeWatchlistMovie(2).copy(listed_at = "2024-02-01T00:00:00Z")
        val oldShow = makeWatchlistShow(3).copy(listed_at = "2024-01-01T00:00:00Z")
        val newShow = makeWatchlistShow(4).copy(listed_at = "2024-02-01T00:00:00Z")
        coEvery { traktRepository.getMovieWatchlist(1, 200, any()) } returns Result.success(listOf(oldMovie) to 2)
        coEvery { traktRepository.getMovieWatchlist(2, 200, any()) } returns Result.success(listOf(newMovie) to 2)
        coEvery { traktRepository.getShowWatchlist(1, 200, any()) } returns Result.success(listOf(oldShow) to 2)
        coEvery { traktRepository.getShowWatchlist(2, 200, any()) } returns Result.success(listOf(newShow) to 2)
        every { traktRepository.getMovieWatchlistTotalCount(any(), 200) } returns 201
        every { traktRepository.getShowWatchlistTotalCount(any(), 200) } returns 201

        viewModel.loadMovies()
        viewModel.loadShows()
        advanceUntilIdle()
        viewModel.loadMoreMovies()
        viewModel.loadMoreShows()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.traktId }).containsExactly(2, 1).inOrder()
        assertThat(viewModel.uiState.value.shows.map { it.traktId }).containsExactly(4, 3).inOrder()
        assertThat(viewModel.uiState.value.movieTotalCount).isEqualTo(201)
        assertThat(viewModel.uiState.value.showTotalCount).isEqualTo(201)
        assertThat(viewModel.uiState.value.hasMoreMovies).isFalse()
        assertThat(viewModel.uiState.value.hasMoreShows).isFalse()
    }


    @Test
    fun `loadMovies_失败时从离线缓存读取`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.failure(IOException("network error"))

        val cachedEntity = MediaItemEntity(
            traktId = 1, tmdbId = 0, type = OfflineCacheManager.TYPE_WATCHLIST_MOVIE,
            title = "Cached Movie", displayTitle = "Cached Movie", year = 2023,
            genres = "", posterUrl = null, imdbId = "tt123",
            traktRating = 8.0, listedAt = "2024-01-01"
        )
        coEvery { offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_WATCHLIST_MOVIE) } returns
            listOf(cachedEntity)

        viewModel.loadMovies()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.movies).hasSize(1)
        assertThat(state.moviesError).isNull()
        assertThat(state.moviesLoaded).isTrue()
    }

    @Test
    fun `loadMovies_silent模式不显示loading`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1)) to 1)

        viewModel.loadMovies(silent = true)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoadingMovies).isFalse()
    }

    @Test
    fun `loadMoreMovies_page failure releases loading state`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(1, 200, any()) } returns
            Result.success(listOf(makeWatchlistMovie(1)) to 2)
        viewModel.loadMovies()
        advanceUntilIdle()

        coEvery { traktRepository.getMovieWatchlist(2, 200, any()) } returns
            Result.failure(IOException("page 2 failed"))
        viewModel.loadMoreMovies()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoadingMovies).isFalse()
        assertThat(viewModel.uiState.value.movies).hasSize(1)
        assertThat(viewModel.uiState.value.hasMoreMovies).isTrue()
    }


    @Test
    fun `loadMovies_已加载且非forceReload时不再请求`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1)) to 1)

        viewModel.loadMovies()
        advanceUntilIdle()

        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(2)) to 1)

        viewModel.loadMovies()
        advanceUntilIdle()

        coVerify(exactly = 1) { traktRepository.getMovieWatchlist(any(), any(), any()) }
    }





    @Test
    fun `refresh_重置状态并重新加载`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1)) to 1)

        viewModel.loadMovies()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.moviesLoaded).isTrue()
        coVerify(atLeast = 2) { traktRepository.getMovieWatchlist(any(), any(), any()) }
    }

    @Test
    fun `WatchlistTab重新可见_刷新并显示新标记电影`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1, title = "Old Movie")) to 1)

        viewModel.loadMovies()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.movies.map { it.traktId }).containsExactly(1)

        viewModel.onWatchlistTabVisible()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.traktId })
            .containsExactly(1)
            .inOrder()
        coVerify(exactly = 1) { traktRepository.getMovieWatchlist(any(), any(), any()) }
    }

    @Test
    fun `refreshWatchlist_跨页刷新后合并新增电影`() = runTest {
        val pageOne = listOf(makeWatchlistMovie(1, title = "Old Movie"))
        val pageTwo = listOf(makeWatchlistMovie(2, title = "New Movie"))
        coEvery { traktRepository.getMovieWatchlist(1, 200, any()) } returns
            Result.success(pageOne to 2)
        coEvery { traktRepository.getMovieWatchlist(2, 200, any()) } returns
            Result.success(pageTwo to 2)

        viewModel.loadMovies()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.movies.map { it.traktId }).containsExactly(1)

        viewModel.refreshWatchlist()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.traktId })
            .containsExactly(1, 2)
            .inOrder()
        coVerify(exactly = 1) { traktRepository.getMovieWatchlist(2, 200, true) }
    }


    @Test
    fun `batchRemoveFromWatchlist_全部成功_更新UI并触发豆瓣移除`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(
                listOf(makeWatchlistMovie(1, "Movie 1", "tt001"), makeWatchlistMovie(2, "Movie 2", "tt002")) to 1
            )

        viewModel.loadMovies()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.movies).hasSize(2)

        coEvery { traktRepository.removeFromWatchlist(any(), any(), any()) } returns
            Result.success(TraktSyncResponse())
        every { doubanBatchRemovalManager.startRemoval(any(), any()) } returns true

        viewModel.batchRemoveFromWatchlist(listOf(viewModel.uiState.value.movies.first()), MediaType.MOVIE)

        assertThat(viewModel.uiState.value.movies).hasSize(1)
        verify { doubanBatchRemovalManager.startRemoval(any(), true) }
    }

    @Test
    fun `batchRemoveFromHistory_全部成功_更新UI`() = runTest {
        coEvery { traktRepository.getMovieHistory(any(), any(), any()) } returns
            Result.success(
                listOf(makeWatchlistMovie(1, "Movie 1", "tt001"), makeWatchlistMovie(2, "Movie 2", "tt002")) to 1
            )

        viewModel.loadHistoryMovies()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.historyMovies).hasSize(2)

        coEvery { traktRepository.removeWatched(any(), any(), any()) } returns
            Result.success(TraktSyncResponse())
        every { doubanBatchRemovalManager.startRemoval(any(), any()) } returns true

        viewModel.batchRemoveFromHistory(listOf(viewModel.uiState.value.historyMovies.first()), MediaType.MOVIE)

        assertThat(viewModel.uiState.value.historyMovies).hasSize(1)
    }

    @Test
    fun `豆瓣独立模式按真实类型承载电影剧集和其他条目`() = runTest {
        every { sessionModeManager.sessionMode } returns MutableStateFlow(SessionMode.DOUBAN)
        val items = listOf(
            makeDoubanItem("movie-1", mediaType = "movie", title = "电影"),
            makeDoubanItem("show-1", mediaType = "show", title = "剧集"),
            makeDoubanItem("variety-1", mediaType = "variety", title = "综艺")
        )
        coEvery { doubanSyncedItemDao.getByStatus("wish") } returns items

        viewModel.loadMovies()
        viewModel.loadShows()
        viewModel.loadOthers()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.displayTitle })
            .containsExactly("电影")
        assertThat(viewModel.uiState.value.shows.map { it.displayTitle })
            .containsExactly("剧集")
        assertThat(viewModel.uiState.value.others.map { it.displayTitle })
            .containsExactly("综艺")
        coVerify(exactly = 0) { traktRepository.getMovieWatchlist(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.getShowWatchlist(any(), any(), any()) }
    }

    @Test
    fun `Trakt和豆瓣按IMDb合并且Trakt条目优先保留无IMDb豆瓣条目`() = runTest {
        every { sessionModeManager.sessionMode } returns MutableStateFlow(SessionMode.TRAKT)
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user", "cookie")
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(7, "Trakt title", "tt-match")) to 1)
        coEvery { doubanSyncedItemDao.getByStatus("wish") } returns listOf(
            makeDoubanItem("douban-match", mediaType = "movie", imdbId = " TT-MATCH ", title = "豆瓣 title"),
            makeDoubanItem("douban-other", mediaType = "documentary", imdbId = null, title = "豆瓣 other")
        )

        viewModel.loadMovies()
        viewModel.loadOthers()
        advanceUntilIdle()

        val movies = viewModel.uiState.value.movies
        assertThat(movies).hasSize(1)
        assertThat(movies.single().traktId).isEqualTo(7)
        assertThat(movies.single().title).isEqualTo("Trakt title")
        assertThat(movies.single().doubanId).isEqualTo("douban-match")
        assertThat(viewModel.uiState.value.others.map { it.doubanId })
            .containsExactly("douban-other")
    }



    @Test
    fun `详情页想看ADD变更直写离线缓存`() = runTest {
        watchlistMutationFlow.emit(
            TraktRepository.WatchlistMutation(
                action = TraktRepository.WatchlistMutationAction.ADD,
                traktId = 11, tmdbId = 0, mediaType = MediaType.MOVIE,
                title = "New Movie", displayTitle = "New Movie", year = 2025,
                genres = "Drama", posterUrl = null, imdbId = "tt-11",
                traktRating = 7.0, actedAt = 1_700_000_000_000
            )
        )
        advanceUntilIdle()

        coVerify {
            offlineCacheManager.saveMediaItem(
                OfflineCacheManager.TYPE_WATCHLIST_MOVIE,
                match {
                    it.traktId == 11 &&
                        it.type == OfflineCacheManager.TYPE_WATCHLIST_MOVIE &&
                        it.title == "New Movie" &&
                        it.listedAt == "2023-11-14T22:13:20Z"
                }
            )
        }
    }

    @Test
    fun `详情页想看REMOVE变更直写离线缓存`() = runTest {
        watchlistMutationFlow.emit(
            TraktRepository.WatchlistMutation(
                action = TraktRepository.WatchlistMutationAction.REMOVE,
                traktId = 12, tmdbId = 0, mediaType = MediaType.SHOW,
                title = "Old Show", displayTitle = "Old Show", year = 2020,
                genres = "", posterUrl = null, imdbId = "tt-12",
                traktRating = 0.0, actedAt = 1_700_000_000_000
            )
        )
        advanceUntilIdle()

        coVerify { offlineCacheManager.removeMediaItem(OfflineCacheManager.TYPE_WATCHLIST_SHOW, 12) }
    }

    @Test
    fun `批量移除想看电影后同步删除离线缓存行`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(11, "M1", "tt-11"), makeWatchlistMovie(12, "M2", "tt-12")) to 1)
        coEvery { traktRepository.removeFromWatchlist(any(), any(), any()) } returns
            Result.success(TraktSyncResponse())
        viewModel.loadMovies()
        advanceUntilIdle()

        viewModel.batchRemoveFromWatchlist(
            listOf(viewModel.uiState.value.movies.first { it.traktId == 11 }),
            WatchlistMediaType.MOVIE
        )
        advanceUntilIdle()

        coVerify { offlineCacheManager.removeMediaItem(OfflineCacheManager.TYPE_WATCHLIST_MOVIE, 11) }
        coVerify(exactly = 0) { offlineCacheManager.removeMediaItem(OfflineCacheManager.TYPE_WATCHLIST_MOVIE, 12) }
    }

    @Test
    fun `豆瓣独立模式其他条目沿用批量删除流程`() = runTest {
        every { sessionModeManager.sessionMode } returns MutableStateFlow(SessionMode.DOUBAN)
        val item = makeDoubanItem("douban-other", mediaType = "variety", title = "综艺")
        coEvery { doubanSyncedItemDao.getByStatus("wish") } returns listOf(item)
        every { doubanBatchRemovalManager.startRemoval(any(), any()) } returns true

        viewModel.loadOthers()
        advanceUntilIdle()
        val uiItem = viewModel.uiState.value.others.single()
        viewModel.batchRemoveFromWatchlist(listOf(uiItem), WatchlistMediaType.OTHER)

        coVerify { doubanSyncedItemDao.deleteByDoubanId("douban-other") }
        assertThat(viewModel.uiState.value.others).isEmpty()
    }

    @Test
    fun `startDoubanSync_调用doubanSyncManager的startSync`() = runTest {
        every { doubanSyncManager.startSync(any<SyncMode>(), any()) } returns true

        viewModel.startDoubanSync(SyncMode.INCREMENTAL_WITH_CHANGES)
        advanceUntilIdle()

        verify { doubanSyncManager.startSync(SyncMode.INCREMENTAL_WITH_CHANGES, any()) }
    }

    @Test
    fun `同步开始后不再显示首次同步引导`() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user", "cookie")
        coEvery { doubanSyncMetaStorage.getCooldownStatus(any()) } returns CooldownStatus(neverSynced = true)
        every { doubanSyncManager.isRunning() } returns false
        viewModel = WatchlistViewModel(
            traktRepository, tmdbRepository, offlineCacheManager,
            doubanSyncManager, doubanAuthStorage, doubanSyncMetaStorage,
            statusConsistencyChecker, doubanBatchRemovalManager,
            sessionModeManager, doubanSyncedItemDao, testDispatcher, context
        )
        advanceUntilIdle()
        assertThat(viewModel.needFirstSyncGuide.value).isTrue()

        syncProgressFlow.value = DoubanSyncProgress(isRunning = true)
        advanceUntilIdle()

        assertThat(viewModel.needFirstSyncGuide.value).isFalse()
    }

    @Test
    fun `已有运行中的同步不触发首次同步引导`() = runTest {
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user", "cookie")
        coEvery { doubanSyncMetaStorage.getCooldownStatus(any()) } returns CooldownStatus(neverSynced = true)
        every { doubanSyncManager.isRunning() } returns true
        viewModel = WatchlistViewModel(
            traktRepository, tmdbRepository, offlineCacheManager,
            doubanSyncManager, doubanAuthStorage, doubanSyncMetaStorage,
            statusConsistencyChecker, doubanBatchRemovalManager,
            sessionModeManager, doubanSyncedItemDao, testDispatcher, context
        )
        advanceUntilIdle()

        assertThat(viewModel.needFirstSyncGuide.value).isFalse()
    }

    @Test
    fun `同步完成后横幅可隐藏但结果状态保留到用户关闭`() = runTest {
        syncProgressFlow.value = DoubanSyncProgress(
            isRunning = true,
            stage = com.tracktosearch.data.repository.DoubanSyncStage.FETCHING_LIST
        )
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.doubanSyncBannerVisible).isTrue()

        syncProgressFlow.value = DoubanSyncProgress(
            isComplete = true,
            stage = com.tracktosearch.data.repository.DoubanSyncStage.COMPLETED,
            successCount = 3
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.doubanSyncBannerVisible).isFalse()
        assertThat(viewModel.uiState.value.doubanSyncProgress?.successCount).isEqualTo(3)
        verify(exactly = 0) { doubanSyncManager.resetProgress() }

        viewModel.clearDoubanSyncResult()
        assertThat(viewModel.uiState.value.doubanSyncProgress).isNull()
        verify { doubanSyncManager.resetProgress() }
    }





    /**
     * 回归测试：一致性检查取消时不触发 consistencyCheckCompleteEvent。
     *
     * Bug 场景：用户在设置页手动检查状态一致性，爬取 15 条后点取消，
     * WatchlistScreen 的 collector 收到 isComplete=true 后自动弹出结果弹窗，
     * 与设置页的 ConsistencyCheckDialog 重复（两个"已取消"弹窗）。
     *
     * 根因：ConsistencyCheckResult 没有 isCancelled 字段，取消时 isComplete=true
     * 无条件触发 _consistencyCheckCompleteEvent.emit(Unit)。
     *
     * 修复：ConsistencyCheckResult 新增 isCancelled 字段，取消时设为 true，
     * WatchlistViewModel 检查 !progress.isCancelled 才 emit。
     */
    @Test
    fun `consistencyCheck_取消时不触发完成事件`() = runTest {
        advanceUntilIdle() // 让 init collector 启动

        val events = mutableListOf<Unit>()
        val collectJob = launch { viewModel.consistencyCheckCompleteEvent.collect { events.add(it) } }
        advanceUntilIdle() // 让 collector 启动

        // 模拟用户取消：isComplete=true + isCancelled=true
        consistencyProgressFlow.value = ConsistencyCheckResult(
            isRunning = false,
            isComplete = true,
            isCancelled = true
        )
        advanceUntilIdle()

        // 取消时不应触发完成事件
        assertThat(events).isEmpty()
        collectJob.cancel()
    }

    /**
     * 对照测试：一致性检查正常完成时（isCancelled=false）应触发 event。
     * 与上一个测试配合，确保 isCancelled 标志正确区分取消与完成。
     */
    @Test
    fun `consistencyCheck_正常完成时触发完成事件`() = runTest {
        advanceUntilIdle()

        val events = mutableListOf<Unit>()
        val collectJob = launch { viewModel.consistencyCheckCompleteEvent.collect { events.add(it) } }
        advanceUntilIdle()

        // 模拟正常完成：isComplete=true + isCancelled=false
        consistencyProgressFlow.value = ConsistencyCheckResult(
            isRunning = false,
            isComplete = true,
            isCancelled = false
        )
        advanceUntilIdle()

        // 正常完成应触发完成事件
        assertThat(events).hasSize(1)
        collectJob.cancel()
    }

    // ==================== enrichment 字段传递测试 ====================
    // 回归测试：tmdbId>0 时 ViewModel 调用 enrichMovie/enrichTv，
    // 必须把 enrichment 返回的 chineseTitle→displayTitle、posterUrl→posterUrl 正确映射到 UI item。
    // 之前测试统一用 tmdb=0 短路 enrich，导致「标题英文+海报不显示」bug 无法被测试覆盖。

    /**
     * 测试：loadMovies 收到 tmdbId>0 的电影时调用 enrichMovie，
     * 返回的 chineseTitle 和 posterUrl 必须正确映射到 MediaUiItem 的 displayTitle 和 posterUrl。
     *
     * 回归场景：Trakt 原始标题为英文，enrichMovie 返回中文标题和海报 URL，
     * 若 enrichMediaItem 字段映射错误（如 displayTitle=title 而非 chineseTitle），
     * UI 上标题会显示英文而非中文。
     */

    /**
     * 测试：loadShows 收到 tmdbId>0 的剧集时调用 enrichTv，
     * 返回的 chineseTitle 和 posterUrl 必须正确映射到 MediaUiItem。
     */

    /**
     * 测试：loadHistoryMovies 收到 tmdbId>0 的电影时调用 enrichMovie，
     * 验证已看历史列表同样走 enrich 流程。
     */

    /**
     * 测试：enrichMovie 返回 fallback（posterUrl=null, chineseTitle=原始标题）时，
     * MediaUiItem 应保留 Trakt 原始 title 但 displayTitle 降级为原始标题、posterUrl=null。
     * 验证 TMDB 不可用时 UI 能检测到（isTmdbUnavailable=true）。
     */
    @Test
    fun `loadMovies_enrichMovie_fallback_posterUrl为null且displayTitle降级`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1, title = "Inception", tmdb = 27205)) to 1)
        // fallback：TMDB 不可用时
        coEvery { tmdbRepository.enrichMovie(27205, "Inception", 2023) } returns
            TmdbRepository.MovieEnrichment(
                posterUrl = null,
                chineseTitle = "Inception", // fallback 用原始标题
                overview = "",
                genres = "",
                year = 2023,
                rating = 0.0
            )

        viewModel.loadMovies()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.movies).hasSize(1)
        val item = state.movies[0]
        assertThat(item.displayTitle).isEqualTo("Inception")
        assertThat(item.posterUrl).isNull()
        assertThat(item.genres).isEqualTo("")
        // isTmdbUnavailable 应检测到该条目 TMDB 不可用
        // selectedMode=0(想看), selectedTab=0(movie)
        assertThat(state.isTmdbUnavailable(0, 0)).isTrue()
    }

    @Test
    fun loadMovies_直接使用Trakt图片且不触发TMDB富化() = runTest {
        val first = makeWatchlistMovie(1, title = "Movie One", tmdb = 101).copy(
            movie = makeWatchlistMovie(1, title = "Movie One", tmdb = 101).movie.copy(
                genres = listOf("Drama", "Sci-Fi"),
                // Trakt 实际返回不带协议的路径，Coil 需要补全后的完整 URL
                images = TraktImages(poster = listOf("media.trakt.tv/poster-one.webp"))
            )
        )
        val second = makeWatchlistMovie(2, title = "Movie Two", tmdb = 102).copy(
            movie = makeWatchlistMovie(2, title = "Movie Two", tmdb = 102).movie.copy(
                images = TraktImages(poster = listOf("https://media.trakt.tv/poster-two.webp"))
            )
        )
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(first, second) to 1)

        viewModel.loadMovies()
        advanceUntilIdle()

        val movies = viewModel.uiState.value.movies
        assertThat(movies).hasSize(2)
        assertThat(movies.map { it.posterUrl }).containsExactly(
            "https://media.trakt.tv/poster-one.webp",
            "https://media.trakt.tv/poster-two.webp"
        ).inOrder()
        assertThat(movies[0].genres).isEqualTo("Drama · Sci-Fi")
        coVerify(exactly = 0) { tmdbRepository.enrichMovie(any(), any(), any()) }
    }

    @Test
    fun `refreshWatchlist_同一批条目加入时间变化时仍重新排序`() = runTest {
        val firstLoad = listOf(
            makeWatchlistMovie(1).copy(listed_at = "2024-01-01T00:00:00Z"),
            makeWatchlistMovie(2).copy(listed_at = "2024-02-01T00:00:00Z")
        )
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(firstLoad to 1)

        viewModel.loadMovies()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.movies.map { it.traktId })
            .containsExactly(2, 1)
            .inOrder()

        val refreshed = listOf(
            firstLoad[0].copy(listed_at = "2024-03-01T00:00:00Z"),
            firstLoad[1]
        )
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(refreshed to 1)

        viewModel.refreshWatchlist()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.traktId })
            .containsExactly(1, 2)
            .inOrder()
    }

    @Test
    fun `详情页想看变更流会立即更新列表而不重新请求Trakt`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1, title = "Old Movie")) to 1)

        every { traktRepository.getMovieWatchlistTotalCount(any(), 200) } returns 1
        viewModel.loadMovies()
        advanceUntilIdle()
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(emptyList<TraktWatchlistMovieItem>() to 1)

        watchlistMutationFlow.emit(
            TraktRepository.WatchlistMutation(
                action = TraktRepository.WatchlistMutationAction.ADD,
                traktId = 2,
                tmdbId = 0,
                mediaType = MediaType.MOVIE,
                title = "New Movie",
                displayTitle = "New Movie",
                year = 2025,
                genres = "Drama",
                posterUrl = null,
                imdbId = "tt2",
                traktRating = 8.0,
                actedAt = 1_735_689_600_000
            )
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.traktId })
            .containsExactly(2, 1)
            .inOrder()
        assertThat(viewModel.uiState.value.movieTotalCount).isEqualTo(2)
        coVerify(exactly = 1) { traktRepository.getMovieWatchlist(any(), any(), any()) }
    }

    // ==================== Watchlist 增量同步 ====================

    @Test
    fun `活动时间未变化时跳过列表请求并保留列表与分页`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1, title = "Old Movie")) to 2)
        viewModel.loadMovies()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.movies.map { it.traktId }).containsExactly(1)
        val pageBeforeRevalidate = viewModel.uiState.value.moviePage

        coEvery { traktRepository.resolveWatchlistRefreshPlan(any()) } returns
            TraktRepository.WatchlistRefreshPlan(
                shouldRefreshMovies = false,
                shouldRefreshShows = false,
                activitiesAvailable = true
            )
        viewModel.refreshIfLoaded(silent = true)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.traktId }).containsExactly(1)
        assertThat(viewModel.uiState.value.moviePage).isEqualTo(pageBeforeRevalidate)
        coVerify(exactly = 1) { traktRepository.getMovieWatchlist(any(), any(), any()) }
    }

    @Test
    fun `跳过列表请求时用持久化总数恢复计数与分页`() = runTest {
        // 重启后离线快照只有首页数据、服务端总数已持久化：计数与分页起点都要恢复
        val cachedItems = List(3) { index ->
            MediaItemEntity(
                traktId = index + 1, tmdbId = 0, type = OfflineCacheManager.TYPE_WATCHLIST_MOVIE,
                title = "Cached $index", displayTitle = "Cached $index", year = 2020,
                genres = "", posterUrl = null, imdbId = "", traktRating = 0.0,
                listedAt = "2024-01-0${index + 1}"
            )
        }
        coEvery { offlineCacheManager.getMediaItems(OfflineCacheManager.TYPE_WATCHLIST_MOVIE) } returns cachedItems
        coEvery { traktRepository.savedWatchlistTotal(MediaType.MOVIE) } returns 290
        coEvery { traktRepository.resolveWatchlistRefreshPlan(any()) } returns
            TraktRepository.WatchlistRefreshPlan(
                shouldRefreshMovies = false,
                shouldRefreshShows = false,
                activitiesAvailable = true
            )

        viewModel.loadMovies()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.movies).hasSize(3)
        assertThat(state.movieTotalCount).isEqualTo(290)
        assertThat(state.hasMoreMovies).isTrue()
        assertThat(state.moviePage).isEqualTo(2)
        coVerify(exactly = 0) { traktRepository.getMovieWatchlist(any(), any(), any()) }

        // 翻页从第 2 页开始，不会把快照首页当分页起点重复拉取
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(4, title = "Page 2 Movie")) to 2)
        viewModel.loadMoreMovies()
        advanceUntilIdle()
        coVerify(exactly = 1) { traktRepository.getMovieWatchlist(2, 200, false) }
    }

    @Test
    fun `仅电影活动变化时只请求电影列表`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1)) to 1)
        coEvery { traktRepository.getShowWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistShow(9)) to 1)
        viewModel.loadMovies()
        advanceUntilIdle()
        viewModel.loadShows()
        advanceUntilIdle()

        coEvery { traktRepository.resolveWatchlistRefreshPlan(any()) } returns
            TraktRepository.WatchlistRefreshPlan(
                shouldRefreshMovies = true,
                shouldRefreshShows = false,
                activitiesAvailable = true
            )
        viewModel.refreshIfLoaded(silent = true)
        advanceUntilIdle()

        // 电影：首次加载 + 活动变化后刷新；剧集：只有首次加载
        coVerify(exactly = 2) { traktRepository.getMovieWatchlist(any(), any(), any()) }
        coVerify(exactly = 1) { traktRepository.getShowWatchlist(any(), any(), any()) }
    }

    @Test
    fun `下拉刷新保持强制拉取语义`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1)) to 1)
        viewModel.loadMovies()
        advanceUntilIdle()

        coEvery { traktRepository.resolveWatchlistRefreshPlan(any()) } returns
            TraktRepository.WatchlistRefreshPlan(
                shouldRefreshMovies = false,
                shouldRefreshShows = false,
                activitiesAvailable = true
            )
        viewModel.pullToRefresh()
        advanceUntilIdle()

        coVerify(exactly = 2) { traktRepository.getMovieWatchlist(any(), any(), any()) }
    }

    // ==================== 可见条目中文标题预取 ====================

    @Test
    fun `可见条目预取中文标题只更新标题并写离线快照`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1, title = "Inception", tmdb = 27205)) to 1)
        every { tmdbRepository.peekMovieLocalizedTitle(27205) } returns null
        coEvery { tmdbRepository.enrichMovie(27205, "Inception", 2023) } returns movieEnrichment("盗梦空间")

        viewModel.loadMovies()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.movies.single().displayTitle).isEqualTo("Inception")

        viewModel.onVisibleWatchlistItemsChanged(viewModel.uiState.value.movies)
        advanceUntilIdle()

        val item = viewModel.uiState.value.movies.single()
        assertThat(item.displayTitle).isEqualTo("盗梦空间")
        // 只改标题：Trakt 直出的年份与海报不被 TMDB 富化结果覆盖
        assertThat(item.year).isEqualTo(2023)
        assertThat(item.posterUrl).isNull()
        coVerify(exactly = 1) {
            offlineCacheManager.saveMediaItem(OfflineCacheManager.TYPE_WATCHLIST_MOVIE, any())
        }
    }

    @Test
    fun `可见剧集预取中文标题`() = runTest {
        coEvery { traktRepository.getShowWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistShow(9, title = "Breaking Bad", tmdb = 1396)) to 1)
        every { tmdbRepository.peekTvLocalizedTitle(1396) } returns null
        coEvery { tmdbRepository.enrichTv(1396, "Breaking Bad", 2023) } returns tvEnrichment("绝命毒师")

        viewModel.loadShows()
        advanceUntilIdle()
        viewModel.onVisibleWatchlistItemsChanged(viewModel.uiState.value.shows)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.shows.single().displayTitle).isEqualTo("绝命毒师")
        coVerify(exactly = 1) {
            offlineCacheManager.saveMediaItem(OfflineCacheManager.TYPE_WATCHLIST_SHOW, any())
        }
    }

    @Test
    fun `已有非空展示标题的条目不请求TMDB也不被覆盖`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1, title = "Inception", tmdb = 27205)) to 1)
        // 列表加载时已有本地化/豆瓣展示标题，当前显示的不再是 Trakt 原始标题
        every { tmdbRepository.peekMovieLocalizedTitle(27205) } returns "盗梦空间"
        coEvery { tmdbRepository.enrichMovie(any(), any(), any()) } returns movieEnrichment("新标题")

        viewModel.loadMovies()
        advanceUntilIdle()
        val item = viewModel.uiState.value.movies.single()
        assertThat(item.displayTitle).isEqualTo("盗梦空间")

        viewModel.onVisibleWatchlistItemsChanged(listOf(item))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.single().displayTitle).isEqualTo("盗梦空间")
        coVerify(exactly = 0) { tmdbRepository.enrichMovie(any(), any(), any()) }
        coVerify(exactly = 0) { offlineCacheManager.saveMediaItem(any(), any()) }
    }

    @Test
    fun `只预取通知的可见条目`() = runTest {
        val visible = makeWatchlistMovie(1, title = "Visible", tmdb = 101)
        val offscreen = makeWatchlistMovie(2, title = "Offscreen", tmdb = 202)
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(visible, offscreen) to 1)
        every { tmdbRepository.peekMovieLocalizedTitle(any()) } returns null
        coEvery { tmdbRepository.enrichMovie(any(), any(), any()) } returns movieEnrichment("本地化标题")

        viewModel.loadMovies()
        advanceUntilIdle()
        viewModel.onVisibleWatchlistItemsChanged(
            listOf(viewModel.uiState.value.movies.first { it.traktId == 1 })
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { tmdbRepository.enrichMovie(101, any(), any()) }
        coVerify(exactly = 0) { tmdbRepository.enrichMovie(202, any(), any()) }
    }

    @Test
    fun `同一条目重复可见只请求一次本地化`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1, title = "Inception", tmdb = 27205)) to 1)
        every { tmdbRepository.peekMovieLocalizedTitle(27205) } returns null
        coEvery { tmdbRepository.enrichMovie(any(), any(), any()) } returns movieEnrichment("盗梦空间")

        viewModel.loadMovies()
        advanceUntilIdle()
        val visible = viewModel.uiState.value.movies
        repeat(3) {
            viewModel.onVisibleWatchlistItemsChanged(visible)
            advanceUntilIdle()
        }

        coVerify(exactly = 1) { tmdbRepository.enrichMovie(27205, any(), any()) }
    }

    @Test
    fun `可见标题预取并发不超过三`() = runTest {
        val movies = (1..6).map { index ->
            makeWatchlistMovie(index, title = "Movie $index", tmdb = 100 + index)
        }
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(movies to 1)
        every { tmdbRepository.peekMovieLocalizedTitle(any()) } returns null
        var running = 0
        var maxRunning = 0
        coEvery { tmdbRepository.enrichMovie(any(), any(), any()) } coAnswers {
            running++
            maxRunning = maxOf(maxRunning, running)
            delay(50)
            running--
            movieEnrichment("本地化标题")
        }

        viewModel.loadMovies()
        advanceUntilIdle()
        viewModel.onVisibleWatchlistItemsChanged(viewModel.uiState.value.movies)
        advanceUntilIdle()

        assertThat(maxRunning).isAtMost(3)
        assertThat(maxRunning).isAtLeast(2)
    }

    @Test
    fun `预取失败保留Trakt原标题且不写快照`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1, title = "Inception", tmdb = 27205)) to 1)
        every { tmdbRepository.peekMovieLocalizedTitle(27205) } returns null
        coEvery { tmdbRepository.enrichMovie(any(), any(), any()) } throws IOException("network down")

        viewModel.loadMovies()
        advanceUntilIdle()
        viewModel.onVisibleWatchlistItemsChanged(viewModel.uiState.value.movies)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.single().displayTitle).isEqualTo("Inception")
        coVerify(exactly = 0) { offlineCacheManager.saveMediaItem(any(), any()) }
    }

    private fun movieEnrichment(title: String) = TmdbRepository.MovieEnrichment(
        posterUrl = "https://image.tmdb.org/t/p/w342/poster.webp",
        chineseTitle = title,
        originalTitle = "Inception",
        overview = "overview",
        genres = "Sci-Fi",
        year = 2010,
        rating = 8.8
    )

    private fun tvEnrichment(title: String) = TmdbRepository.TvEnrichment(
        posterUrl = "https://image.tmdb.org/t/p/w342/tv.webp",
        chineseTitle = title,
        originalTitle = "Breaking Bad",
        overview = "overview",
        genres = "Drama",
        year = 2008,
        rating = 8.9
    )

    private fun makeDoubanItem(
        doubanId: String,
        mediaType: String = "movie",
        status: String = "wish",
        imdbId: String? = "tt-$doubanId",
        title: String = "Douban $doubanId"
    ) = DoubanSyncedItem(
        doubanId = doubanId,
        imdbId = imdbId,
        traktId = null,
        title = title,
        status = status,
        rating = 4,
        syncedAt = 1L,
        mediaType = mediaType,
        tmdbId = null,
        displayTitle = title,
        year = 2024,
        genres = "Drama",
        posterUrl = null,
        listedAt = "2024-01-01T00:00:00Z"
    )
}
