package com.tracktosearch.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.remote.trakt.dto.TraktLastActivities
import com.tracktosearch.data.remote.trakt.dto.TraktLastActivityType
import com.tracktosearch.data.util.PersistentTtlCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * Watchlist 增量同步（/sync/last_activities）单元测试。
 *
 * 覆盖活动基线的判定与持久化：首次无基线全量拉取、未变化跳过、单类型变化只刷该类型、
 * forceRefresh 绕过判断、接口失败回退、切号清理基线、重启后仍可增量判断。
 *
 * 基线写入是 400ms 攒批异步落盘，验证跨重启持久化时需要真实等待。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class TraktWatchlistActivitySyncTest {

    private companion object {
        const val MOVIES_AT = "2026-09-01T00:00:00.000Z"
        const val MOVIES_AT_NEW = "2026-09-10T00:00:00.000Z"
        const val SHOWS_AT = "2026-08-01T00:00:00.000Z"
        const val SHOWS_AT_NEW = "2026-08-20T00:00:00.000Z"
        const val BASELINE_KEY = "watchlist_activity_baseline_v1"
    }

    private lateinit var traktApiService: TraktApiService
    private lateinit var repository: TraktRepository
    private val createdRepositories = mutableListOf<TraktRepository>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() = runTest {
        traktApiService = mockk(relaxed = true)
        repository = newRepository()
        // 持久化缓存落在进程级 DataStore 上，开跑前清空避免上一个用例的基线残留
        clearPersistentCaches()
    }

    @After
    fun tearDown() = runTest {
        createdRepositories.forEach { cancelPersistentScope(it) }
        clearPersistentCaches()
    }

    private fun newRepository(): TraktRepository {
        val created = TraktRepository(
            traktApiService,
            mockk(relaxed = true),                                  // UserProfileStorage
            mockk(relaxed = true),                                  // MarkActionRecordDao
            mockk(relaxed = true),                                  // TmdbRepository
            mockk(relaxed = true),                                  // StatisticsSnapshotStore
            Json { ignoreUnknownKeys = true },
            mockk(relaxed = true),                                  // OfflineCacheManager
            context
        )
        createdRepositories += created
        return created
    }

    private suspend fun clearPersistentCaches() {
        repository.persistentCaches.forEach { it.clearAll() }
    }

    private fun cancelPersistentScope(target: TraktRepository) {
        val field = TraktRepository::class.java.getDeclaredField("persistentScope")
        field.isAccessible = true
        (field.get(target) as CoroutineScope).cancel()
    }

    private fun activitiesResponse(movies: String?, shows: String?): Response<TraktLastActivities> =
        Response.success(
            TraktLastActivities(
                movies = TraktLastActivityType(watchlistedAt = movies),
                shows = TraktLastActivityType(watchlistedAt = shows)
            )
        )

    private suspend fun seedBaseline(movies: String?, shows: String?) {
        val field = TraktRepository::class.java.getDeclaredField("watchlistActivityBaselineCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(repository) as PersistentTtlCache<TraktRepository.WatchlistActivityBaseline>
        cache.put(
            BASELINE_KEY,
            TraktRepository.WatchlistActivityBaseline(
                moviesWatchlistedAt = movies,
                showsWatchlistedAt = shows
            )
        )
    }

    /** 持久化缓存按 400ms 攒批异步落盘，这里真实等待写入完成。 */
    private suspend fun awaitPersistentFlush() = withContext(Dispatchers.Default) { delay(900) }

    @Test
    fun `首次无基线时两个列表都需要拉取_保存基线后未变化则跳过`() = runTest {
        coEvery { traktApiService.getLastActivities() } returns activitiesResponse(MOVIES_AT, SHOWS_AT)

        val first = repository.resolveWatchlistRefreshPlan()
        assertThat(first.activitiesAvailable).isTrue()
        assertThat(first.shouldRefreshMovies).isTrue()
        assertThat(first.shouldRefreshShows).isTrue()
        assertThat(first.moviesWatchlistedAt).isEqualTo(MOVIES_AT)
        assertThat(first.showsWatchlistedAt).isEqualTo(SHOWS_AT)

        repository.markMovieWatchlistSynced(first)
        repository.markShowWatchlistSynced(first)

        val second = repository.resolveWatchlistRefreshPlan()
        assertThat(second.shouldRefreshMovies).isFalse()
        assertThat(second.shouldRefreshShows).isFalse()
    }

    @Test
    fun `活动时间未变化时两个列表都跳过`() = runTest {
        coEvery { traktApiService.getLastActivities() } returns activitiesResponse(MOVIES_AT, SHOWS_AT)
        seedBaseline(MOVIES_AT, SHOWS_AT)

        val plan = repository.resolveWatchlistRefreshPlan()

        assertThat(plan.shouldRefreshMovies).isFalse()
        assertThat(plan.shouldRefreshShows).isFalse()
    }

    @Test
    fun `仅电影活动变化时只刷新电影`() = runTest {
        coEvery { traktApiService.getLastActivities() } returns activitiesResponse(MOVIES_AT_NEW, SHOWS_AT)
        seedBaseline(MOVIES_AT, SHOWS_AT)

        val plan = repository.resolveWatchlistRefreshPlan()

        assertThat(plan.shouldRefreshMovies).isTrue()
        assertThat(plan.shouldRefreshShows).isFalse()
    }

    @Test
    fun `仅剧集活动变化时只刷新剧集`() = runTest {
        coEvery { traktApiService.getLastActivities() } returns activitiesResponse(MOVIES_AT, SHOWS_AT_NEW)
        seedBaseline(MOVIES_AT, SHOWS_AT)

        val plan = repository.resolveWatchlistRefreshPlan()

        assertThat(plan.shouldRefreshMovies).isFalse()
        assertThat(plan.shouldRefreshShows).isTrue()
    }

    @Test
    fun `并发判断电影与剧集时只发一次活动请求`() = runTest {
        coEvery { traktApiService.getLastActivities() } returns activitiesResponse(MOVIES_AT, SHOWS_AT)
        seedBaseline(MOVIES_AT, SHOWS_AT)

        // 两个调用方共享同一次 /sync/last_activities：先到的请求填充内存缓存，
        // 后到的要么命中飞行中去重、要么命中短 TTL 缓存，都不会再打接口。
        val plans = coroutineScope {
            listOf(
                async { repository.resolveWatchlistRefreshPlan() },
                async { repository.resolveWatchlistRefreshPlan() }
            ).awaitAll()
        }

        assertThat(plans).hasSize(2)
        coVerify(exactly = 1) { traktApiService.getLastActivities() }
    }

    @Test
    fun `forceRefresh绕过活动时间判断`() = runTest {
        coEvery { traktApiService.getLastActivities() } returns activitiesResponse(MOVIES_AT, SHOWS_AT)
        seedBaseline(MOVIES_AT, SHOWS_AT)

        val forced = repository.resolveWatchlistRefreshPlan(forceRefresh = true)

        assertThat(forced.activitiesAvailable).isTrue()
        assertThat(forced.shouldRefreshMovies).isTrue()
        assertThat(forced.shouldRefreshShows).isTrue()
    }

    @Test
    fun `活动接口失败时回退全量拉取且不写基线`() = runTest {
        coEvery { traktApiService.getLastActivities() } returns
            Response.error(500, "".toResponseBody(null))

        val failed = repository.resolveWatchlistRefreshPlan()
        assertThat(failed.activitiesAvailable).isFalse()
        assertThat(failed.shouldRefreshMovies).isTrue()
        assertThat(failed.shouldRefreshShows).isTrue()

        // 列表拉取成功后的基线回写必须被忽略，否则会用错误基线跳过后续刷新
        repository.markMovieWatchlistSynced(failed)
        repository.markShowWatchlistSynced(failed)

        coEvery { traktApiService.getLastActivities() } returns activitiesResponse(MOVIES_AT, SHOWS_AT)
        val next = repository.resolveWatchlistRefreshPlan(forceRefresh = true)
        assertThat(next.shouldRefreshMovies).isTrue()
        assertThat(next.shouldRefreshShows).isTrue()
    }

    @Test
    fun `服务端未返回活动时间时基线记空值不重复刷新`() = runTest {
        coEvery { traktApiService.getLastActivities() } returns activitiesResponse(null, null)

        val first = repository.resolveWatchlistRefreshPlan()
        assertThat(first.shouldRefreshMovies).isTrue()
        repository.markMovieWatchlistSynced(first)
        repository.markShowWatchlistSynced(first)

        val second = repository.resolveWatchlistRefreshPlan()
        assertThat(second.activitiesAvailable).isTrue()
        assertThat(second.shouldRefreshMovies).isFalse()
        assertThat(second.shouldRefreshShows).isFalse()
    }

    @Test
    fun `清除账号缓存后不复用旧活动基线`() = runTest {
        coEvery { traktApiService.getLastActivities() } returns activitiesResponse(MOVIES_AT, SHOWS_AT)
        val initial = repository.resolveWatchlistRefreshPlan()
        repository.markMovieWatchlistSynced(initial)
        repository.markShowWatchlistSynced(initial)
        assertThat(repository.resolveWatchlistRefreshPlan().shouldRefreshMovies).isFalse()

        // 退出登录 / 切换账号 / 重新授权
        repository.clearWatchlistWatchedCache()

        val afterSwitch = repository.resolveWatchlistRefreshPlan()
        assertThat(afterSwitch.shouldRefreshMovies).isTrue()
        assertThat(afterSwitch.shouldRefreshShows).isTrue()
    }

    @Test
    fun `活动基线持久化后重启仍可用于增量判断`() = runTest {
        coEvery { traktApiService.getLastActivities() } returns activitiesResponse(MOVIES_AT, SHOWS_AT)
        val initial = repository.resolveWatchlistRefreshPlan()
        repository.markMovieWatchlistSynced(initial)
        repository.markShowWatchlistSynced(initial)
        awaitPersistentFlush()

        // 新实例没有内存缓存，基线必须来自 DataStore
        val restarted = newRepository()
        val plan = restarted.resolveWatchlistRefreshPlan()

        assertThat(plan.shouldRefreshMovies).isFalse()
        assertThat(plan.shouldRefreshShows).isFalse()
    }
}
