package com.tracktosearch.ui.screen.watchlist

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.CooldownStatus
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.db.MediaItemEntity
import com.tracktosearch.data.local.db.OfflineCacheManager
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
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
@Config(sdk = [33])
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
    private lateinit var context: Context

    private lateinit var viewModel: WatchlistViewModel

    private val syncProgressFlow = MutableStateFlow(DoubanSyncProgress())
    private val consistencyProgressFlow = MutableStateFlow(ConsistencyCheckResult())
    private val batchRemovalProgressFlow = MutableStateFlow(BatchRemovalProgress())

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
        context = RuntimeEnvironment.getApplication()

        // init 块副作用 stub
        every { doubanSyncManager.progress } returns syncProgressFlow
        every { statusConsistencyChecker.checkProgress } returns consistencyProgressFlow
        every { doubanBatchRemovalManager.progress } returns batchRemovalProgressFlow
        every { doubanAuthStorage.getCredentials() } returns null
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
            statusConsistencyChecker, doubanBatchRemovalManager, context
        )
    }

    private fun makeWatchlistMovie(
        traktId: Int,
        title: String = "Test Movie",
        imdbId: String = "tt123"
    ): TraktWatchlistMovieItem = TraktWatchlistMovieItem(
        listed_at = "2024-01-01T00:00:00Z",
        movie = TraktMovie(
            title = title, year = 2023,
            ids = TraktIds(trakt = traktId, tmdb = 0, imdb = imdbId),
            rating = 8.0
        )
    )

    private fun makeWatchlistShow(
        traktId: Int,
        title: String = "Test Show",
        imdbId: String = "tt456"
    ): TraktWatchlistShowItem = TraktWatchlistShowItem(
        listed_at = "2024-01-01T00:00:00Z",
        show = TraktShow(
            title = title, year = 2023,
            ids = TraktIds(trakt = traktId, tmdb = 0, imdb = imdbId),
            rating = 8.0
        )
    )

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

        viewModel.batchRemoveFromWatchlist(listOf(1), MediaType.MOVIE)

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

        viewModel.batchRemoveFromHistory(listOf(1), MediaType.MOVIE)

        assertThat(viewModel.uiState.value.historyMovies).hasSize(1)
    }

    @Test
    fun `startDoubanSync_调用doubanSyncManager的startSync`() = runTest {
        every { doubanSyncManager.startSync(any<SyncMode>(), any()) } returns true

        viewModel.startDoubanSync(SyncMode.INCREMENTAL_WITH_CHANGES)
        advanceUntilIdle()

        verify { doubanSyncManager.startSync(SyncMode.INCREMENTAL_WITH_CHANGES, any()) }
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
}
