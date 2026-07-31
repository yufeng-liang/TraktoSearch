package com.tracktosearch.ui.screen.watchlist

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.CooldownStatus
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.DoubanSyncMetaStorage
import com.tracktosearch.data.local.db.MediaItemEntity
import com.tracktosearch.data.local.db.OfflineCacheManager
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
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
    private lateinit var sessionModeManager: SessionModeManager
    private lateinit var doubanSyncedItemDao: DoubanSyncedItemDao
    private lateinit var doubanSyncFailureDao: DoubanSyncFailureDao
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
        sessionModeManager = mockk(relaxed = true)
        doubanSyncedItemDao = mockk(relaxed = true)
        doubanSyncFailureDao = mockk(relaxed = true)
        context = RuntimeEnvironment.getApplication()

        // init 块副作用 stub
        every { doubanSyncManager.progress } returns syncProgressFlow
        every { statusConsistencyChecker.checkProgress } returns consistencyProgressFlow
        every { doubanBatchRemovalManager.progress } returns batchRemovalProgressFlow
        every { sessionModeManager.isDoubanMode } returns MutableStateFlow(false)
        every { sessionModeManager.traktConnected } returns MutableStateFlow(false)
        every { sessionModeManager.sessionMode } returns MutableStateFlow(SessionMode.GUEST)
        every { doubanAuthStorage.isLoggedIn } returns MutableStateFlow(false)
        every { doubanAuthStorage.getCredentials() } returns null
        coEvery { doubanSyncMetaStorage.getCooldownStatus(any()) } returns CooldownStatus(neverSynced = false)
        coEvery { doubanSyncFailureDao.count() } returns 0

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
            sessionModeManager, doubanSyncedItemDao, doubanSyncFailureDao, context
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
}
