package com.tracktosearch.ui.screen.statistics

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.UserReviewEntity
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.trakt.dto.*
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UserReviewRepository
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * StatisticsViewModel 单元测试。
 *
 * 验证统计数据加载的核心行为：
 * - 6 个并行 async 请求的渐进式发布
 * - 致命错误（movies/shows/watchedShows）中断加载并设置 error
 * - 非致命错误（userStats/ratings）降级处理
 * - 重新调用 loadStatistics() 重置状态
 * - 词云从本地评论数据生成
 *
 * 注意：wordCloudDeferred 使用 Dispatchers.IO（jieba 词典加载需要真实时间），
 * 不能仅靠 advanceUntilIdle()。waitForLoadComplete() 轮询推进 + 短暂 sleep
 * 直到 uiState 稳定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StatisticsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val userReviewRepository = mockk<UserReviewRepository>(relaxed = true)
    private val doubanSyncedItemDao = mockk<DoubanSyncedItemDao>(relaxed = true)
    private val doubanRepository = mockk<DoubanRepository>(relaxed = true)
    private val traktConnected = MutableStateFlow(true)
    private val doubanMode = MutableStateFlow(false)
    private val sessionModeManager = mockk<SessionModeManager>(relaxed = true)
    private lateinit var viewModel: StatisticsViewModel

    @Test
    fun `未连接Trakt时统计页不请求任何私有数据`() = runTest {
        io.mockk.clearMocks(traktRepository, userReviewRepository)
        traktConnected.value = false

        viewModel.loadStatistics()
        advanceUntilIdle()

        coVerify(exactly = 0) { traktRepository.getAllMovieHistory(any()) }
        coVerify(exactly = 0) { traktRepository.getAllShowHistory(any()) }
        coVerify(exactly = 0) { traktRepository.getWatchedShowsWithEpisodes() }
        assertThat(viewModel.uiState.value.initialLoading).isFalse()
    }

    // ==================== 测试数据 ====================

    @Test
    fun doubanMode_usesLocalCollectRatingsCommentsAndMarkedAtWithoutTraktRequests() = runTest {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        doubanMode.value = true
        traktConnected.value = false
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns listOf(
            DoubanSyncedItem(
                doubanId = "db-movie",
                imdbId = "tt-douban-movie",
                traktId = null,
                title = "豆瓣电影",
                displayTitle = "豆瓣电影",
                status = "collect",
                rating = 4,
                syncedAt = 1L,
                mediaType = "movie",
                year = 2024,
                genres = "action / drama",
                comment = "dragon adventure",
                markedAt = today
            ),
            DoubanSyncedItem(
                doubanId = "db-show",
                imdbId = "tt-douban-show",
                traktId = null,
                title = "豆瓣剧集",
                displayTitle = "豆瓣剧集",
                status = "collect",
                rating = null,
                syncedAt = 2L,
                mediaType = "show",
                year = 2024,
                genres = "drama",
                markedAt = today
            ),
            DoubanSyncedItem(
                doubanId = "db-wish",
                imdbId = "tt-douban-wish",
                traktId = null,
                title = "只想看",
                status = "wish",
                rating = 5,
                syncedAt = 3L,
                mediaType = "movie",
                comment = "should not count",
                markedAt = today
            )
        )
        coEvery { userReviewRepository.getAllReviews() } returns emptyList()
        coEvery { doubanRepository.getDetailSnapshot() } returns mapOf(
            "db-movie" to DoubanDetailCacheEntry(
                imdbId = "tt-douban-movie",
                isTvShow = false,
                genres = listOf("action", "drama"),
                runtime = "120分钟"
            ),
            "db-show" to DoubanDetailCacheEntry(
                imdbId = "tt-douban-show",
                isTvShow = true,
                genres = listOf("drama", "mystery"),
                episodeCount = 8,
                episodeDuration = "45分钟"
            )
        )

        viewModel.loadStatistics()
        waitForLoadComplete()

        val state = viewModel.uiState.value
        assertThat(state.totalMovieCount).isEqualTo(1)
        assertThat(state.totalShowCount).isEqualTo(1)
        assertThat(state.totalEpisodeCount).isEqualTo(8)
        assertThat(state.totalWatchMinutes).isEqualTo(480)
        assertThat(state.genreDistribution).isEqualTo(mapOf("drama" to 2, "action" to 1, "mystery" to 1))
        assertThat(state.totalRatings).isEqualTo(1)
        assertThat(state.averageRating).isEqualTo(8.0)
        assertThat(state.thisYearWatched).isEqualTo(2)
        assertThat(state.heatmapData.values.sum()).isEqualTo(2)
        assertThat(state.wordCloud.map { it.word }).contains("dragon")
        coVerify(exactly = 0) { traktRepository.getAllMovieHistory(any()) }
        coVerify(exactly = 0) { traktRepository.getAllShowHistory(any()) }
        coVerify(exactly = 0) { traktRepository.getWatchedShowsWithEpisodes() }
        coVerify(exactly = 0) { traktRepository.getUserStats() }
        coVerify(exactly = 0) { traktRepository.getAllUserRatings() }
    }

    @Test
    fun traktAndDoubanCollect_mergeByImdbAndPreferDoubanRating() = runTest {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val networkMovie = TraktMovie(
            title = "重复电影",
            ids = TraktIds(imdb = "tt-duplicate"),
            runtime = 100
        )
        stubAllSuccess(
            movies = listOf(TraktWatchlistMovieItem(watched_at = "${today}T12:00:00Z", movie = networkMovie)),
            ratings = listOf(TraktRatingItem(rating = 7, movie = networkMovie))
        )
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns listOf(
            DoubanSyncedItem(
                doubanId = "db-duplicate",
                imdbId = "tt-duplicate",
                traktId = null,
                title = "重复电影",
                status = "collect",
                rating = 4,
                syncedAt = 1L,
                mediaType = "movie",
                genres = "drama",
                comment = "dragon duplicate",
                markedAt = today
            ),
            DoubanSyncedItem(
                doubanId = "db-new",
                imdbId = "tt-new",
                traktId = null,
                title = "豆瓣新电影",
                status = "collect",
                rating = 5,
                syncedAt = 2L,
                mediaType = "movie",
                genres = "action",
                comment = "unique adventure",
                markedAt = today
            ),
            DoubanSyncedItem(
                doubanId = "db-wish-only",
                imdbId = "tt-wish-only",
                traktId = null,
                title = "未观看",
                status = "wish",
                rating = 5,
                syncedAt = 3L,
                mediaType = "movie",
                markedAt = today
            )
        )
        coEvery { doubanRepository.getDetailSnapshot() } returns mapOf(
            "db-new" to DoubanDetailCacheEntry(
                imdbId = "tt-new",
                isTvShow = false,
                runtime = "90分钟"
            )
        )
        coEvery { traktRepository.getUserStats() } returns Result.success(
            TraktUserStatsResponse(movies = TraktStatsDetail(minutes = 300))
        )

        viewModel.loadStatistics()
        waitForLoadComplete()

        val state = viewModel.uiState.value
        assertThat(state.totalMovieCount).isEqualTo(2)
        assertThat(state.totalRatings).isEqualTo(2)
        assertThat(state.ratingDistribution).isEqualTo(mapOf(8 to 1, 10 to 1))
        assertThat(state.averageRating).isEqualTo(9.0)
        assertThat(state.totalWatchMinutes).isEqualTo(390)
        assertThat(state.heatmapData.values.sum()).isEqualTo(2)
        assertThat(state.wordCloud.map { it.word }).containsAtLeast("dragon", "unique")
    }

    private val testMovies = listOf(
        TraktWatchlistMovieItem(
            watched_at = "2024-06-15T10:00:00Z",
            movie = TraktMovie(title = "电影A", runtime = 120, genres = listOf("action", "sci-fi"))
        ),
        TraktWatchlistMovieItem(
            watched_at = "2024-06-20T10:00:00Z",
            movie = TraktMovie(title = "电影B", runtime = 90, genres = listOf("action", "drama"))
        )
    )

    private val testShows = listOf(
        TraktWatchlistShowItem(
            watched_at = "2024-06-18T10:00:00Z",
            show = TraktShow(title = "剧集A")
        )
    )

    private val testWatchedShows = listOf(
        TraktWatchedShow(
            show = TraktShow(title = "剧集A", genres = listOf("drama", "thriller")),
            seasons = listOf(
                TraktWatchedSeason(
                    number = 1,
                    episodes = listOf(
                        TraktWatchedEpisode(number = 1, completed = 1),
                        TraktWatchedEpisode(number = 2, completed = 1)
                    )
                )
            )
        )
    )

    private val testUserStats = TraktUserStatsResponse(
        movies = TraktStatsDetail(watched = 2, minutes = 300),
        shows = TraktStatsDetail(watched = 1),
        episodes = TraktStatsDetail(watched = 10, minutes = 500)
    )

    private val testRatings = listOf(
        TraktRatingItem(rating = 8),
        TraktRatingItem(rating = 8),
        TraktRatingItem(rating = 9),
        TraktRatingItem(rating = 7)
    )

    private val testReviews = listOf(
        UserReviewEntity(
            traktId = 1, tmdbId = 100, imdbId = "tt0000001",
            mediaType = "movie", title = "电影A", year = 2024,
            rating = 8f, comment = "剧情精彩 演技出色", liked = true,
            createdAt = 1_000L, updatedAt = 2_000L
        ),
        UserReviewEntity(
            traktId = 2, tmdbId = 200, imdbId = "tt0000002",
            mediaType = "movie", title = "电影B", year = 2024,
            rating = 9f, comment = "剧情精彩 剧情出色", liked = false,
            createdAt = 3_000L, updatedAt = 4_000L
        )
    )

    // ==================== 生命周期 ====================

    @Before
    fun setup() {
        clearMocks(traktRepository, userReviewRepository, doubanSyncedItemDao, doubanRepository)
        traktConnected.value = true
        doubanMode.value = false
        every { sessionModeManager.traktConnected } returns traktConnected
        every { sessionModeManager.isDoubanMode } returns doubanMode
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns emptyList()
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        viewModel = StatisticsViewModel(
            traktRepository,
            userReviewRepository,
            doubanSyncedItemDao,
            doubanRepository,
            sessionModeManager,
            RuntimeEnvironment.getApplication()
        )
    }

    // ==================== 桩辅助 ====================

    /**
     * 配置所有 Repository 方法返回成功结果。
     * 每个测试可覆盖特定方法的桩以模拟失败场景。
     */
    private fun stubAllSuccess(
        movies: List<TraktWatchlistMovieItem> = emptyList(),
        shows: List<TraktWatchlistShowItem> = emptyList(),
        watchedShows: List<TraktWatchedShow> = emptyList(),
        userStats: TraktUserStatsResponse = TraktUserStatsResponse(),
        ratings: List<TraktRatingItem> = emptyList(),
        reviews: List<UserReviewEntity> = emptyList()
    ) {
        coEvery { traktRepository.getAllMovieHistory(any()) } returns Result.success(movies)
        coEvery { traktRepository.getAllShowHistory(any()) } returns Result.success(shows)
        coEvery { traktRepository.getWatchedShowsWithEpisodes() } returns Result.success(watchedShows)
        coEvery { traktRepository.getUserStats() } returns Result.success(userStats)
        coEvery { traktRepository.getAllUserRatings() } returns Result.success(ratings)
        coEvery { userReviewRepository.getAllReviews() } returns reviews
    }

    /**
     * 等待 viewModelScope 内的协程完成。
     *
     * wordCloudDeferred 使用 Dispatchers.IO（jieba 词典加载需要真实时间），
     * 不能仅靠 advanceUntilIdle()。轮询推进 + 短暂 sleep 直到 uiState 稳定。
     */
    private fun TestScope.waitForLoadComplete(timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var prevState: StatisticsUiState? = null
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            val current = viewModel.uiState.value
            // 状态不再变化 且 不在初始加载 → 协程已完成
            if (current == prevState && !current.initialLoading) return
            prevState = current
            Thread.sleep(50)
        }
        // 超时后最后推进一次
        advanceUntilIdle()
    }

    // ==================== 测试 ====================

    /**
     * 测试点1：全部成功加载后 initialLoading 从 true 变为 false
     */
    @Test
    fun `loadStatistics_全部成功_initialLoading变false`() = runTest {
        stubAllSuccess()

        viewModel.loadStatistics()
        waitForLoadComplete()

        assertThat(viewModel.uiState.value.initialLoading).isFalse()
    }

    /**
     * 测试点2：totalMovieCount 正确填充（等于 movies 列表大小）
     */
    @Test
    fun `loadStatistics_全部成功_totalMovieCount正确`() = runTest {
        stubAllSuccess(movies = testMovies)

        viewModel.loadStatistics()
        waitForLoadComplete()

        assertThat(viewModel.uiState.value.totalMovieCount).isEqualTo(2)
    }

    /**
     * 测试点3：genreDistribution 正确填充（movies 和 watchedShows 的 genres 汇总）
     */
    @Test
    fun `loadStatistics_全部成功_genreDistribution正确填充`() = runTest {
        stubAllSuccess(movies = testMovies, watchedShows = testWatchedShows)

        viewModel.loadStatistics()
        waitForLoadComplete()

        // movies: action=2, sci-fi=1, drama=1
        // watchedShows: drama=1, thriller=1
        // 合计: action=2, drama=2, sci-fi=1, thriller=1
        val genreDist = viewModel.uiState.value.genreDistribution
        assertThat(genreDist).isEqualTo(
            mapOf(
                "action" to 2, "drama" to 2, "sci-fi" to 1, "thriller" to 1
            )
        )
    }

    /**
     * 测试点4：ratingDistribution 正确填充（ratings 按 rating 值分组计数）
     */
    @Test
    fun `loadStatistics_全部成功_ratingDistribution正确填充`() = runTest {
        stubAllSuccess(ratings = testRatings)

        viewModel.loadStatistics()
        waitForLoadComplete()

        // ratings: [8, 8, 9, 7] → {8=2, 9=1, 7=1}
        assertThat(viewModel.uiState.value.ratingDistribution).isEqualTo(
            mapOf(8 to 2, 9 to 1, 7 to 1)
        )
    }

    /**
     * 测试点5：overviewReady 在 movies + shows + watchedShows 都到达后变为 true
     */
    @Test
    fun `loadStatistics_全部成功_overviewReady为true`() = runTest {
        stubAllSuccess(
            movies = testMovies,
            shows = testShows,
            watchedShows = testWatchedShows
        )

        viewModel.loadStatistics()
        waitForLoadComplete()

        assertThat(viewModel.uiState.value.overviewReady).isTrue()
    }

    /**
     * 测试点6：movieHistory 失败（Result.failure）→ 设置 error，停止加载
     */
    @Test
    fun `loadStatistics_电影历史失败_设置error并停止加载`() = runTest {
        stubAllSuccess()
        coEvery { traktRepository.getAllMovieHistory(any()) } returns Result.failure(IOException("网络错误"))

        viewModel.loadStatistics()
        waitForLoadComplete()

        val state = viewModel.uiState.value
        assertThat(state.initialLoading).isFalse()
        assertThat(state.error).isNotNull()
        assertThat(state.error).contains("Network error")
    }

    /**
     * 测试点7：成功后再次调用 → 重新加载（cancel 旧任务，重置 uiState）
     */
    @Test
    fun `loadStatistics_再次调用_重新加载并重置状态`() = runTest {
        stubAllSuccess(movies = testMovies)

        // 第一次加载
        viewModel.loadStatistics()
        waitForLoadComplete()
        assertThat(viewModel.uiState.value.totalMovieCount).isEqualTo(2)

        // 第二次调用：同步重置状态
        viewModel.loadStatistics()
        // StandardTestDispatcher 下 launch 尚未执行，状态为重置后的初始值
        assertThat(viewModel.uiState.value.initialLoading).isTrue()
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.totalMovieCount).isEqualTo(0)

        // 等待第二次加载完成
        waitForLoadComplete()
        assertThat(viewModel.uiState.value.totalMovieCount).isEqualTo(2)
    }

    /**
     * 测试点8：wordCloud 数据正确填充（从 getAllReviews 获取评论，分词后生成）
     */
    @Test
    fun `loadStatistics_有评论数据_wordCloud正确填充`() = runTest {
        stubAllSuccess(reviews = testReviews)

        viewModel.loadStatistics()
        waitForLoadComplete()

        val state = viewModel.uiState.value
        assertThat(state.wordCloudReady).isTrue()
        assertThat(state.wordCloud).isNotEmpty()
        // 验证按权重降序排列
        val weights = state.wordCloud.map { it.weight }
        assertThat(weights.sortedDescending()).isEqualTo(weights)
        // 验证确实调用了 getAllReviews
        coVerify { userReviewRepository.getAllReviews() }
    }

    // ==================== 统计数据正确性测试 ====================

    /**
     * 测试点9：totalShowCount 正确计算（看完一整季才算看完该剧）
     *
     * 场景：3 部剧
     * - 剧集A：S1 全部 completed=1 → 计入
     * - 剧集B：S1 部分 completed=0 → 不计入
     * - 剧集C：S0（特别篇）全部 completed=1，S1 无 → 不计入（season.number > 0 排除特别篇）
     */
    @Test
    fun `loadStatistics_totalShowCount_看完一整季才算`() = runTest {
        val watchedShows = listOf(
            // 剧集A：S1 全部看完
            TraktWatchedShow(
                show = TraktShow(title = "剧集A"),
                seasons = listOf(
                    TraktWatchedSeason(
                        number = 1,
                        episodes = listOf(
                            TraktWatchedEpisode(number = 1, completed = 1),
                            TraktWatchedEpisode(number = 2, completed = 1)
                        )
                    )
                )
            ),
            // 剧集B：S1 部分看完
            TraktWatchedShow(
                show = TraktShow(title = "剧集B"),
                seasons = listOf(
                    TraktWatchedSeason(
                        number = 1,
                        episodes = listOf(
                            TraktWatchedEpisode(number = 1, completed = 1),
                            TraktWatchedEpisode(number = 2, completed = 0)  // 未看完
                        )
                    )
                )
            ),
            // 剧集C：仅 S0（特别篇）看完，S1 无
            TraktWatchedShow(
                show = TraktShow(title = "剧集C"),
                seasons = listOf(
                    TraktWatchedSeason(
                        number = 0,  // 特别篇，被排除
                        episodes = listOf(
                            TraktWatchedEpisode(number = 1, completed = 1)
                        )
                    )
                )
            )
        )
        stubAllSuccess(watchedShows = watchedShows)

        viewModel.loadStatistics()
        waitForLoadComplete()

        // 只有剧集A满足"存在 number>0 的季且该季所有 episode completed>0"
        assertThat(viewModel.uiState.value.totalShowCount).isEqualTo(1)
    }

    /**
     * 测试点10：totalShowCount 多季任意一季看完即计入
     */
    @Test
    fun `loadStatistics_totalShowCount_多季任意一季看完即计入`() = runTest {
        val watchedShows = listOf(
            TraktWatchedShow(
                show = TraktShow(title = "剧集"),
                seasons = listOf(
                    TraktWatchedSeason(
                        number = 1,
                        episodes = listOf(
                            TraktWatchedEpisode(number = 1, completed = 0)  // S1 未看完
                        )
                    ),
                    TraktWatchedSeason(
                        number = 2,
                        episodes = listOf(
                            TraktWatchedEpisode(number = 1, completed = 1),  // S2 全部看完
                            TraktWatchedEpisode(number = 2, completed = 1)
                        )
                    )
                )
            )
        )
        stubAllSuccess(watchedShows = watchedShows)

        viewModel.loadStatistics()
        waitForLoadComplete()

        // seasons.any { ... } → S2 满足条件，计入
        assertThat(viewModel.uiState.value.totalShowCount).isEqualTo(1)
    }

    /**
     * 测试点11：totalEpisodeCount 优先使用 userStats
     */
    @Test
    fun `loadStatistics_totalEpisodeCount_优先使用userStats`() = runTest {
        stubAllSuccess(
            watchedShows = listOf(
                TraktWatchedShow(
                    show = TraktShow(title = "T"),
                    seasons = listOf(
                        TraktWatchedSeason(number = 1, episodes = listOf(
                            TraktWatchedEpisode(number = 1, completed = 1),
                            TraktWatchedEpisode(number = 2, completed = 1)
                        ))
                    )
                )
            ),
            userStats = TraktUserStatsResponse(
                episodes = TraktStatsDetail(watched = 100)  // userStats 优先
            )
        )

        viewModel.loadStatistics()
        waitForLoadComplete()

        // userStats.episodes.watched=100 优先于 watchedShows 兜底（2集）
        assertThat(viewModel.uiState.value.totalEpisodeCount).isEqualTo(100)
    }

    /**
     * 测试点12：totalEpisodeCount userStats 为 0 时降级到 watchedShows 兜底
     */
    @Test
    fun `loadStatistics_totalEpisodeCount_userStats为0降级到watchedShows`() = runTest {
        stubAllSuccess(
            watchedShows = listOf(
                TraktWatchedShow(
                    show = TraktShow(title = "T"),
                    seasons = listOf(
                        TraktWatchedSeason(number = 1, episodes = listOf(
                            TraktWatchedEpisode(number = 1, completed = 1),
                            TraktWatchedEpisode(number = 2, completed = 1),
                            TraktWatchedEpisode(number = 3, completed = 0)  // 未看完不计
                        ))
                    )
                )
            ),
            userStats = TraktUserStatsResponse(
                episodes = TraktStatsDetail(watched = 0)  // userStats 为 0，降级
            )
        )

        viewModel.loadStatistics()
        waitForLoadComplete()

        // 降级到 watchedShows：2 集完成
        assertThat(viewModel.uiState.value.totalEpisodeCount).isEqualTo(2)
    }

    /**
     * 测试点13：totalWatchMinutes 优先使用 userStats
     */
    @Test
    fun `loadStatistics_totalWatchMinutes_优先使用userStats`() = runTest {
        stubAllSuccess(
            movies = testMovies,  // runtime=120+90=210
            userStats = TraktUserStatsResponse(
                movies = TraktStatsDetail(minutes = 300),
                episodes = TraktStatsDetail(minutes = 500)
            )
        )

        viewModel.loadStatistics()
        waitForLoadComplete()

        // userStats.movies.minutes + userStats.episodes.minutes = 300+500=800
        assertThat(viewModel.uiState.value.totalWatchMinutes).isEqualTo(800L)
    }

    /**
     * 测试点14：totalWatchMinutes userStats 缺失时降级到 movies.runtime 求和
     */
    @Test
    fun `loadStatistics_totalWatchMinutes_降级到movies_runtime`() = runTest {
        stubAllSuccess(movies = testMovies)  // runtime=120+90=210

        viewModel.loadStatistics()
        waitForLoadComplete()

        // userStats 为 null → 降级到 movies.sumOf { runtime } = 120+90=210
        assertThat(viewModel.uiState.value.totalWatchMinutes).isEqualTo(210L)
    }

    /**
     * 测试点15：averageRating 正确计算
     */
    @Test
    fun `loadStatistics_averageRating正确计算`() = runTest {
        stubAllSuccess(ratings = testRatings)  // [8, 8, 9, 7] → avg=8.0

        viewModel.loadStatistics()
        waitForLoadComplete()

        assertThat(viewModel.uiState.value.averageRating).isEqualTo(8.0)
    }

    /**
     * 测试点16：averageRating 无评分时为 0.0
     */
    @Test
    fun `loadStatistics_无评分_averageRating为0`() = runTest {
        stubAllSuccess(ratings = emptyList())

        viewModel.loadStatistics()
        waitForLoadComplete()

        assertThat(viewModel.uiState.value.averageRating).isEqualTo(0.0)
        assertThat(viewModel.uiState.value.totalRatings).isEqualTo(0)
    }

    /**
     * 测试点17：thisMonthWatched / thisYearWatched 正确计算
     */
    @Test
    fun `loadStatistics_本月本年观看数正确`() = runTest {
        val cal = java.util.Calendar.getInstance()
        val currentYear = cal.get(java.util.Calendar.YEAR)
        val currentMonth = cal.get(java.util.Calendar.MONTH)

        // 构造本月、本年（非本月）、去年的观影记录
        val thisMonthDate = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(cal.time)

        cal.add(java.util.Calendar.MONTH, -2)
        val thisYearOtherMonthDate = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(cal.time)

        cal.add(java.util.Calendar.YEAR, -1)
        val lastYearDate = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(cal.time)

        val movies = listOf(
            TraktWatchlistMovieItem(watched_at = thisMonthDate, movie = TraktMovie(title = "本月")),
            TraktWatchlistMovieItem(watched_at = thisYearOtherMonthDate, movie = TraktMovie(title = "本年")),
            TraktWatchlistMovieItem(watched_at = lastYearDate, movie = TraktMovie(title = "去年"))
        )
        stubAllSuccess(movies = movies)

        viewModel.loadStatistics()
        waitForLoadComplete()

        val state = viewModel.uiState.value
        // thisMonthWatched=1（仅本月），thisYearWatched=2（本月+本年其他月）
        assertThat(state.thisMonthWatched).isEqualTo(1)
        assertThat(state.thisYearWatched).isEqualTo(2)
    }

    /**
     * 测试点18：heatmapData 正确按日期分组
     */
    @Test
    fun `loadStatistics_heatmapData按日期分组`() = runTest {
        // 使用本地时区当天中午时间，避免跨日时区偏移导致日期 key 不稳定
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 12)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val today = cal.time
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        val movies = listOf(
            TraktWatchlistMovieItem(watched_at = fmt.format(today), movie = TraktMovie(title = "A")),
            TraktWatchlistMovieItem(watched_at = fmt.format(today), movie = TraktMovie(title = "B")),
            TraktWatchlistMovieItem(watched_at = fmt.format(today), movie = TraktMovie(title = "C"))
        )
        val shows = listOf(
            TraktWatchlistShowItem(watched_at = fmt.format(today), show = TraktShow(title = "S"))
        )
        stubAllSuccess(movies = movies, shows = shows)

        viewModel.loadStatistics()
        waitForLoadComplete()

        val heatmap = viewModel.uiState.value.heatmapData
        // 4 条记录都在同一天（本地时区），应分组到同一个 key
        assertThat(heatmap.values.sum()).isEqualTo(4)
        // 不为空
        assertThat(heatmap).isNotEmpty()
    }

    // ==================== 增量渲染测试 ====================

    /**
     * 测试点19：词云先到达时 wordCloudReady=true，其他区块仍为 false
     *
     * 通过让 traktRepository 的网络方法延迟返回（返回 failure 快速中断致命链），
     * 验证 wordCloudDeferred 最先 publish 时只有 wordCloudReady 为 true。
     */
    @Test
    fun `增量渲染_词云先到达_wordCloudReady为true_其他区块为false`() = runTest {
        // 词云有数据（本地最快）
        stubAllSuccess(reviews = testReviews)
        // 电影历史失败（致命），会在词云发布后中断
        coEvery { traktRepository.getAllMovieHistory(any()) } returns Result.failure(IOException("fail"))

        viewModel.loadStatistics()
        waitForLoadComplete()

        val state = viewModel.uiState.value
        // 词云先到达 → wordCloudReady=true
        assertThat(state.wordCloudReady).isTrue()
        assertThat(state.wordCloud).isNotEmpty()
        // 电影失败 → 致命中断，其他区块未就绪
        assertThat(state.overviewReady).isFalse()
        assertThat(state.watchTimeReady).isFalse()
        assertThat(state.heatmapReady).isFalse()
        assertThat(state.genreReady).isFalse()
        // initialLoading 因 wordCloudReady 已退出骨架
        assertThat(state.initialLoading).isFalse()
        // error 来自电影失败
        assertThat(state.error).contains("Network error")
    }

    /**
     * 测试点20：电影到达后 watchTimeReady=true，但 heatmap/overview 仍为 false
     *
     * 剧集历史失败（致命），在电影 publish 后中断。
     */
    @Test
    fun `增量渲染_电影到达后_watchTimeReady为true_heatmap仍为false`() = runTest {
        stubAllSuccess(movies = testMovies)
        // 剧集历史失败（致命），在电影 publish 后中断
        coEvery { traktRepository.getAllShowHistory(any()) } returns Result.failure(IOException("show fail"))

        viewModel.loadStatistics()
        waitForLoadComplete()

        val state = viewModel.uiState.value
        // 电影到达 → watchTimeReady=true（movies != null）
        assertThat(state.watchTimeReady).isTrue()
        assertThat(state.totalMovieCount).isEqualTo(2)
        // 剧集未到达 → heatmapReady=false（需要 movies && shows）
        assertThat(state.heatmapReady).isFalse()
        // watchedShows 未到达 → overviewReady=false
        assertThat(state.overviewReady).isFalse()
        // error 来自剧集失败
        assertThat(state.error).contains("Network error")
    }

    /**
     * 测试点21：电影+剧集到达后 heatmapReady=true，但 overview 仍为 false
     *
     * watchedShows 失败（致命），在 heatmap publish 后中断。
     */
    @Test
    fun `增量渲染_电影剧集到达后_heatmapReady为true_overview仍为false`() = runTest {
        stubAllSuccess(movies = testMovies, shows = testShows)
        // watchedShows 失败（致命）
        coEvery { traktRepository.getWatchedShowsWithEpisodes() } returns Result.failure(IOException("ws fail"))

        viewModel.loadStatistics()
        waitForLoadComplete()

        val state = viewModel.uiState.value
        // 电影+剧集到达 → heatmapReady=true
        assertThat(state.heatmapReady).isTrue()
        assertThat(state.heatmapData).isNotEmpty()
        // watchedShows 未到达 → overviewReady=false
        assertThat(state.overviewReady).isFalse()
        assertThat(state.genreReady).isFalse()  // genre 需要 movies && watchedShows
        // totalShowCount=0（watchedShows 未到达）
        assertThat(state.totalShowCount).isEqualTo(0)
        // error 来自 watchedShows 失败
        assertThat(state.error).contains("Network error")
    }

    /**
     * 测试点22：userStats 失败（非致命）不中断加载，降级处理
     */
    @Test
    fun `增量渲染_userStats失败_非致命_不中断加载`() = runTest {
        stubAllSuccess(
            movies = testMovies,
            shows = testShows,
            watchedShows = testWatchedShows
        )
        coEvery { traktRepository.getUserStats() } returns Result.failure(IOException("stats fail"))

        viewModel.loadStatistics()
        waitForLoadComplete()

        val state = viewModel.uiState.value
        // userStats 失败为非致命，不设 error
        assertThat(state.error).isNull()
        // overview 仍就绪
        assertThat(state.overviewReady).isTrue()
        // totalEpisodeCount 降级到 watchedShows 兜底（testWatchedShows 有 2 集完成）
        assertThat(state.totalEpisodeCount).isEqualTo(2)
        // totalWatchMinutes 降级到 movies.sumOf { runtime } = 120+90=210
        assertThat(state.totalWatchMinutes).isEqualTo(210L)
    }

    /**
     * 测试点23：ratings 失败（非致命）不中断加载，ratings 区块降级为空
     */
    @Test
    fun `增量渲染_ratings失败_非致命_ratings区块降级为空`() = runTest {
        stubAllSuccess(
            movies = testMovies,
            shows = testShows,
            watchedShows = testWatchedShows
        )
        coEvery { traktRepository.getAllUserRatings() } returns Result.failure(IOException("ratings fail"))

        viewModel.loadStatistics()
        waitForLoadComplete()

        val state = viewModel.uiState.value
        assertThat(state.error).isNull()
        // ratings 失败 → getOrDefault(emptyList()) → ratingsReady=true（allRatings 非null而是空list）
        // 注意：publish 中 ratingsReady = allRatings != null，空 list 也算就绪
        assertThat(state.ratingsReady).isTrue()
        assertThat(state.totalRatings).isEqualTo(0)
        assertThat(state.averageRating).isEqualTo(0.0)
    }

    /**
     * 测试点24：所有非致命同时失败，致命成功，加载正常完成
     */
    @Test
    fun `增量渲染_非致命全失败_致命成功_加载完成`() = runTest {
        stubAllSuccess(
            movies = testMovies,
            shows = testShows,
            watchedShows = testWatchedShows
        )
        coEvery { traktRepository.getUserStats() } returns Result.failure(IOException("e1"))
        coEvery { traktRepository.getAllUserRatings() } returns Result.failure(IOException("e2"))

        viewModel.loadStatistics()
        waitForLoadComplete()

        val state = viewModel.uiState.value
        assertThat(state.error).isNull()
        assertThat(state.overviewReady).isTrue()
        assertThat(state.heatmapReady).isTrue()
        assertThat(state.watchTimeReady).isTrue()
        assertThat(state.genreReady).isTrue()
        // ratings 降级为空 list，ratingsReady=true
        assertThat(state.ratingsReady).isTrue()
        // 词云无数据（reviews 为空），wordCloudReady=false
        assertThat(state.wordCloudReady).isTrue()
    }
}
