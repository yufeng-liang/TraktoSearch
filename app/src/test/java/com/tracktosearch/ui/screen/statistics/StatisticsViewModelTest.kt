package com.tracktosearch.ui.screen.statistics

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.UserReviewEntity
import com.tracktosearch.data.remote.trakt.dto.*
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UserReviewRepository
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
    private lateinit var viewModel: StatisticsViewModel

    // ==================== 测试数据 ====================

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
        clearMocks(traktRepository, userReviewRepository)
        viewModel = StatisticsViewModel(
            traktRepository,
            userReviewRepository,
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
        assertThat(state.error).isEqualTo("网络错误")
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
}
