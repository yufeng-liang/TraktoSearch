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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
            sessionModeManager, doubanSyncedItemDao, context
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
    fun `loadMovies_成功加载电影列表`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1)) to 1)

        viewModel.loadMovies()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.movies).hasSize(1)
        assertThat(state.moviesLoaded).isTrue()
        assertThat(state.isLoadingMovies).isFalse()
        assertThat(state.moviesError).isNull()
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
    fun `loadMoreShows_page failure releases loading state`() = runTest {
        coEvery { traktRepository.getShowWatchlist(1, 200, any()) } returns
            Result.success(listOf(makeWatchlistShow(1)) to 2)
        viewModel.loadShows()
        advanceUntilIdle()

        coEvery { traktRepository.getShowWatchlist(2, 200, any()) } returns
            Result.failure(IOException("page 2 failed"))
        viewModel.loadMoreShows()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoadingShows).isFalse()
        assertThat(viewModel.uiState.value.shows).hasSize(1)
        assertThat(viewModel.uiState.value.hasMoreShows).isTrue()
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
    fun `loadShows_成功加载剧集列表`() = runTest {
        coEvery { traktRepository.getShowWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistShow(1)) to 1)

        viewModel.loadShows()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.shows).hasSize(1)
        assertThat(state.showsLoaded).isTrue()
    }

    @Test
    fun `loadHistoryMovies_成功加载已看电影`() = runTest {
        coEvery { traktRepository.getMovieHistory(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1)) to 1)

        viewModel.loadHistoryMovies()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.historyMovies).hasSize(1)
        assertThat(state.historyMoviesLoaded).isTrue()
    }

    @Test
    fun `loadHistoryMovies_跨页加载并合并全部记录`() = runTest {
        coEvery { traktRepository.getMovieHistory(1, 200, "full") } returns
            Result.success(listOf(makeWatchlistMovie(1, "Old Movie")) to 2)
        coEvery { traktRepository.getMovieHistory(2, 200, "full") } returns
            Result.success(listOf(makeWatchlistMovie(2, "New Movie")) to 2)

        viewModel.loadHistoryMovies()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.historyMovies.map { it.traktId })
            .containsExactly(1, 2)
            .inOrder()
        coVerify(exactly = 1) { traktRepository.getMovieHistory(2, 200, "full") }
    }

    @Test
    fun `loadHistoryShows_跨页加载并合并全部记录`() = runTest {
        coEvery { traktRepository.getShowHistory(1, 200, "full") } returns
            Result.success(listOf(makeWatchlistShow(1, "Old Show")) to 2)
        coEvery { traktRepository.getShowHistory(2, 200, "full") } returns
            Result.success(listOf(makeWatchlistShow(2, "New Show")) to 2)

        viewModel.loadHistoryShows()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.historyShows.map { it.traktId })
            .containsExactly(1, 2)
            .inOrder()
        coVerify(exactly = 1) { traktRepository.getShowHistory(2, 200, "full") }
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
    fun `refreshIfLoaded_已加载时静默刷新`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1)) to 1)

        viewModel.loadMovies()
        advanceUntilIdle()

        viewModel.refreshIfLoaded(silent = true)
        advanceUntilIdle()

        coVerify(atLeast = 2) { traktRepository.getMovieWatchlist(any(), any(), any()) }
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
    fun `Trakt和豆瓣共享TraktId且都无IMDb时总数不重复`() = runTest {
        every { sessionModeManager.sessionMode } returns MutableStateFlow(SessionMode.TRAKT)
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user", "cookie")
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(42, "Trakt item", imdbId = "")) to 1)
        every { traktRepository.getMovieWatchlistTotalCount(1, 200) } returns 1
        coEvery { doubanSyncedItemDao.getByStatus("wish") } returns listOf(
            makeDoubanItem("douban-42", imdbId = null).copy(traktId = 42)
        )

        viewModel.loadMovies()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies).hasSize(1)
        assertThat(viewModel.uiState.value.movieTotalCount).isEqualTo(1)
    }

    @Test
    fun `豆瓣collect与Trakt历史按IMDb合并不重复`() = runTest {
        every { sessionModeManager.sessionMode } returns MutableStateFlow(SessionMode.TRAKT)
        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user", "cookie")
        coEvery { traktRepository.getMovieHistory(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(8, "Trakt watched", "tt-watched")) to 1)
        coEvery { doubanSyncedItemDao.getByStatus("collect") } returns listOf(
            makeDoubanItem("douban-watched", status = "collect", imdbId = "tt-watched", title = "豆瓣 watched")
        )

        viewModel.loadHistoryMovies()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.historyMovies).hasSize(1)
        assertThat(viewModel.uiState.value.historyMovies.single().traktId).isEqualTo(8)
        assertThat(viewModel.uiState.value.historyMovies.single().doubanId).isEqualTo("douban-watched")
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
            sessionModeManager, doubanSyncedItemDao, context
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
            sessionModeManager, doubanSyncedItemDao, context
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

    @Test
    fun `豆瓣同步完成但成功数为零时仍刷新已写入的最低限度快照`() = runTest {
        every { sessionModeManager.sessionMode } returns MutableStateFlow(SessionMode.DOUBAN)
        var syncedItems = emptyList<DoubanSyncedItem>()
        coEvery { doubanSyncedItemDao.getByStatus("wish") } answers { syncedItems }

        viewModel.loadMovies()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.movies).isEmpty()

        syncedItems = listOf(
            makeDoubanItem("douban-minimum", imdbId = null, title = "最低快照")
        )
        syncProgressFlow.value = DoubanSyncProgress(
            isComplete = true,
            stage = com.tracktosearch.data.repository.DoubanSyncStage.COMPLETED,
            successCount = 0
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.movies.map { it.doubanId })
            .containsExactly("douban-minimum")
    }

    @Test
    fun `isDoubanLoggedIn_根据登录状态返回正确值`() = runTest {
        advanceUntilIdle()

        assertThat(viewModel.isDoubanLoggedIn()).isFalse()

        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("userId", "cookie")
        assertThat(viewModel.isDoubanLoggedIn()).isTrue()
    }

    @Test
    fun `updateSelectedGenres_更新筛选状态`() = runTest {
        advanceUntilIdle()

        viewModel.updateSelectedGenres(setOf("动作", "科幻"))

        assertThat(viewModel.filterState.value.selectedGenres).isEqualTo(setOf("动作", "科幻"))
    }

    @Test
    fun `resetFilters_重置筛选状态`() = runTest {
        advanceUntilIdle()

        viewModel.updateSelectedGenres(setOf("动作"))
        viewModel.toggleDecade(2020)

        viewModel.resetFilters()

        assertThat(viewModel.filterState.value).isEqualTo(FilterState())
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
    @Test
    fun `loadMovies_tmdbId大于0_enrichMovie返回中文标题和海报_字段正确映射`() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(1, title = "Inception", imdbId = "tt1375666", tmdb = 27205)) to 1)
        coEvery { tmdbRepository.enrichMovie(27205, "Inception", 2023) } returns
            TmdbRepository.MovieEnrichment(
                posterUrl = "https://image.tmdb.org/t/p/w500/inception.jpg",
                chineseTitle = "盗梦空间",
                overview = "梦境层层",
                genres = "科幻,动作",
                year = 2010,
                rating = 8.8
            )

        viewModel.loadMovies()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.movies).hasSize(1)
        val item = state.movies[0]
        // Trakt 原始字段
        assertThat(item.traktId).isEqualTo(1)
        assertThat(item.tmdbId).isEqualTo(27205)
        assertThat(item.title).isEqualTo("Inception") // title 保留 Trakt 原始
        assertThat(item.imdbId).isEqualTo("tt1375666")
        assertThat(item.traktRating).isEqualTo(8.0)
        // enrich 字段（bug 核心防护字段）
        assertThat(item.displayTitle).isEqualTo("盗梦空间") // 必须来自 enrichment.chineseTitle
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/inception.jpg") // 必须来自 enrichment.posterUrl
        assertThat(item.year).isEqualTo(2010) // 来自 enrichment.year
        assertThat(item.genres).isEqualTo("科幻,动作") // 来自 enrichment.genres
        coVerify(exactly = 1) { tmdbRepository.enrichMovie(27205, "Inception", 2023) }
    }

    /**
     * 测试：loadShows 收到 tmdbId>0 的剧集时调用 enrichTv，
     * 返回的 chineseTitle 和 posterUrl 必须正确映射到 MediaUiItem。
     */
    @Test
    fun `loadShows_tmdbId大于0_enrichTv返回中文标题和海报_字段正确映射`() = runTest {
        coEvery { traktRepository.getShowWatchlist(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistShow(2, title = "Breaking Bad", imdbId = "tt0903747", tmdb = 1396)) to 1)
        coEvery { tmdbRepository.enrichTv(1396, "Breaking Bad", 2023) } returns
            TmdbRepository.TvEnrichment(
                posterUrl = "https://image.tmdb.org/t/p/w500/breakingbad.jpg",
                chineseTitle = "绝命毒师",
                overview = "高中化学老师制毒",
                genres = "犯罪,剧情",
                year = 2008,
                rating = 9.5
            )

        viewModel.loadShows()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.shows).hasSize(1)
        val item = state.shows[0]
        assertThat(item.traktId).isEqualTo(2)
        assertThat(item.tmdbId).isEqualTo(1396)
        assertThat(item.title).isEqualTo("Breaking Bad")
        assertThat(item.imdbId).isEqualTo("tt0903747")
        // enrich 字段
        assertThat(item.displayTitle).isEqualTo("绝命毒师")
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/breakingbad.jpg")
        assertThat(item.year).isEqualTo(2008)
        assertThat(item.genres).isEqualTo("犯罪,剧情")
        coVerify(exactly = 1) { tmdbRepository.enrichTv(1396, "Breaking Bad", 2023) }
    }

    /**
     * 测试：loadHistoryMovies 收到 tmdbId>0 的电影时调用 enrichMovie，
     * 验证已看历史列表同样走 enrich 流程。
     */
    @Test
    fun `loadHistoryMovies_tmdbId大于0_enrichMovie返回中文标题和海报_字段正确映射`() = runTest {
        coEvery { traktRepository.getMovieHistory(any(), any(), any()) } returns
            Result.success(listOf(makeWatchlistMovie(3, title = "Interstellar", imdbId = "tt0816692", tmdb = 157336)) to 1)
        coEvery { tmdbRepository.enrichMovie(157336, "Interstellar", 2023) } returns
            TmdbRepository.MovieEnrichment(
                posterUrl = "https://image.tmdb.org/t/p/w500/interstellar.jpg",
                chineseTitle = "星际穿越",
                overview = "虫洞穿越",
                genres = "科幻,冒险",
                year = 2014,
                rating = 9.0
            )

        viewModel.loadHistoryMovies()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.historyMovies).hasSize(1)
        val item = state.historyMovies[0]
        assertThat(item.displayTitle).isEqualTo("星际穿越")
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/interstellar.jpg")
        assertThat(item.year).isEqualTo(2014)
        coVerify(exactly = 1) { tmdbRepository.enrichMovie(157336, "Interstellar", 2023) }
    }

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
    fun loadMovies_publishesOnlyPlaceholderAndFinalEnrichedList() = runTest {
        coEvery { traktRepository.getMovieWatchlist(any(), any(), any()) } returns
            Result.success(
                listOf(
                    makeWatchlistMovie(1, title = "Movie One", tmdb = 101),
                    makeWatchlistMovie(2, title = "Movie Two", tmdb = 102)
                ) to 1
            )
        coEvery { tmdbRepository.enrichMovie(101, "Movie One", 2023) } returns
            TmdbRepository.MovieEnrichment(
                posterUrl = "https://image.tmdb.org/t/p/w500/one.jpg",
                chineseTitle = "Movie One",
                overview = "",
                genres = "",
                year = 2023,
                rating = 8.0
            )
        coEvery { tmdbRepository.enrichMovie(102, "Movie Two", 2023) } returns
            TmdbRepository.MovieEnrichment(
                posterUrl = "https://image.tmdb.org/t/p/w500/two.jpg",
                chineseTitle = "Movie Two",
                overview = "",
                genres = "",
                year = 2023,
                rating = 8.0
            )

        val states = mutableListOf<WatchlistUiState>()
        val collectJob = launch {
            viewModel.uiState.collect { states.add(it) }
        }
        advanceUntilIdle()

        viewModel.loadMovies()
        advanceUntilIdle()
        collectJob.cancel()

        val statesWithEnrichedMovies = states.filter { state ->
            state.movies.any { movie -> movie.posterUrl != null }
        }
        assertThat(statesWithEnrichedMovies).hasSize(1)
        val finalMovies = statesWithEnrichedMovies.single().movies
        assertThat(finalMovies).hasSize(2)
        assertThat(finalMovies.all { it.posterUrl != null }).isTrue()
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
