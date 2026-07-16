package com.tracktosearch.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.local.db.MarkActionRecordDao
import com.tracktosearch.data.local.db.MediaDetailDao
import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.remote.trakt.dto.TraktHistoryEntry
import com.tracktosearch.data.remote.trakt.dto.TraktHistoryEpisode
import com.tracktosearch.data.remote.trakt.dto.TraktHistoryIds
import com.tracktosearch.data.remote.trakt.dto.TraktHistoryMovie
import com.tracktosearch.data.remote.trakt.dto.TraktHistoryShow
import com.tracktosearch.data.remote.trakt.dto.TraktIds
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Headers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.time.Instant

/**
 * TraktRepository 单元测试。
 *
 * 聚焦于「标记记录」模块相关的 Repository 方法：
 * - [TraktRepository.fetchWatchHistory]：缓存命中 / API 成功合并 / API 失败处理
 * - [TraktRepository.clearWatchHistoryCache]：清空缓存
 * - parseTraktDate（private，反射测试）：4 个分支
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TraktRepositoryTest {

    private lateinit var traktApiService: TraktApiService
    private lateinit var userProfileStorage: UserProfileStorage
    private lateinit var markActionRecordDao: MarkActionRecordDao
    private lateinit var mediaDetailDao: MediaDetailDao
    private lateinit var repository: TraktRepository

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() = runTest {
        traktApiService = mockk(relaxed = true)
        userProfileStorage = mockk(relaxed = true)
        markActionRecordDao = mockk(relaxed = true)
        mediaDetailDao = mockk(relaxed = true)

        // 默认 stub：API 返回空成功响应
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns emptySuccessResponse()
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns emptyEpisodeResponse()

        repository = TraktRepository(
            traktApiService, userProfileStorage, markActionRecordDao,
            mediaDetailDao, Json { ignoreUnknownKeys = true }, context
        )
    }

    private fun emptySuccessResponse(): Response<List<TraktWatchlistMovieItem>> {
        val headers = Headers.headersOf(
            "X-Pagination-Item-Count", "0",
            "X-Pagination-Page-Count", "1"
        )
        return Response.success(emptyList(), headers)
    }

    private fun emptyEpisodeResponse(): Response<List<TraktHistoryEntry>> {
        val headers = Headers.headersOf(
            "X-Pagination-Item-Count", "0",
            "X-Pagination-Page-Count", "1"
        )
        return Response.success(emptyList(), headers)
    }

    // ==================== parseTraktDate（反射）====================

    private fun parseTraktDate(dateStr: String?): Long {
        val method = TraktRepository::class.java.getDeclaredMethod("parseTraktDate", String::class.java)
        method.isAccessible = true
        return method.invoke(repository, dateStr) as Long
    }

    @Test
    fun `parseTraktDate_null返回当前时间戳`() {
        val before = System.currentTimeMillis()
        val result = parseTraktDate(null)
        val after = System.currentTimeMillis()
        assertThat(result).isAtLeast(before)
        assertThat(result).isAtMost(after)
    }

    @Test
    fun `parseTraktDate_空字符串返回当前时间戳`() {
        val before = System.currentTimeMillis()
        val result = parseTraktDate("")
        val after = System.currentTimeMillis()
        assertThat(result).isAtLeast(before)
        assertThat(result).isAtMost(after)
    }

    @Test
    fun `parseTraktDate_合法ISO8601返回对应时间戳`() {
        val isoStr = "2024-01-15T10:30:00.000Z"
        val expected = Instant.parse(isoStr).toEpochMilli()
        val result = parseTraktDate(isoStr)
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `parseTraktDate_非法字符串返回当前时间戳`() {
        val before = System.currentTimeMillis()
        val result = parseTraktDate("not-a-date")
        val after = System.currentTimeMillis()
        assertThat(result).isAtLeast(before)
        assertThat(result).isAtMost(after)
    }

    // ==================== fetchWatchHistory ====================

    @Test
    fun `fetchWatchHistory_空响应返回空列表`() = runTest {
        val result = repository.fetchWatchHistory(1)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()!!.items).isEmpty()
    }

    @Test
    fun `fetchWatchHistory_movie和episode合并按时间倒序`() = runTest {
        // movie: watched_at = 2024-01-01（较早）
        val movieEntry = TraktWatchlistMovieItem(
            watched_at = "2024-01-01T00:00:00.000Z",
            movie = TraktMovie(title = "Movie A", year = 2024, ids = TraktIds(trakt = 1, tmdb = 10, imdb = "tt1"))
        )
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(movieEntry), Headers.headersOf("X-Pagination-Item-Count", "1", "X-Pagination-Page-Count", "1"))

        // episode: watched_at = 2024-06-01（较晚）
        val episodeEntry = TraktHistoryEntry(
            watched_at = "2024-06-01T00:00:00.000Z",
            episode = TraktHistoryEpisode(season = 1, number = 1, title = "Ep1", ids = TraktHistoryIds()),
            show = TraktHistoryShow(title = "Show B", year = 2024, ids = TraktHistoryIds(trakt = 2, tmdb = 20, imdb = "tt2"))
        )
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(episodeEntry), Headers.headersOf("X-Pagination-Item-Count", "1", "X-Pagination-Page-Count", "1"))

        val result = repository.fetchWatchHistory(1)
        assertThat(result.isSuccess).isTrue()
        val items = result.getOrNull()!!.items
        assertThat(items).hasSize(2)
        // 较晚的 episode（2024-06-01）在前，较早的 movie（2024-01-01）在后
        assertThat(items[0].title).isEqualTo("Show B")
        assertThat(items[1].title).isEqualTo("Movie A")
        // episode 的 actionType 在 toMarkRecordItem 中被设为 "WATCHED"
        assertThat(items[0].episodeInfo).isEqualTo("S1E1")
        assertThat(items[1].episodeInfo).isNull()
    }

    @Test
    fun `fetchWatchHistory_movie失败返回failure`() = runTest {
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.error(401, okhttp3.ResponseBody.create(null, ""))

        val result = repository.fetchWatchHistory(1)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `fetchWatchHistory_缓存命中不调用API`() = runTest {
        // 第一次调用：走 API
        val result1 = repository.fetchWatchHistory(1)
        assertThat(result1.isSuccess).isTrue()

        // 第二次调用：应走缓存
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val result2 = repository.fetchWatchHistory(1)
        assertThat(result2.isSuccess).isTrue()
        assertThat(result2.getOrNull()!!.items).isEmpty()
    }

    @Test
    fun `clearWatchHistoryCache_后重新走API`() = runTest {
        // 第一次调用：走 API 并缓存
        repository.fetchWatchHistory(1)

        // 清空缓存
        repository.clearWatchHistoryCache()

        // 重新调用应走 API（配置新的返回）
        val movieEntry = TraktWatchlistMovieItem(
            watched_at = "2024-03-01T00:00:00.000Z",
            movie = TraktMovie(title = "New Movie", year = 2024, ids = TraktIds(trakt = 99, tmdb = 99, imdb = "tt99"))
        )
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(movieEntry), Headers.headersOf("X-Pagination-Item-Count", "1", "X-Pagination-Page-Count", "1"))

        val result = repository.fetchWatchHistory(1)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()!!.items).hasSize(1)
        assertThat(result.getOrNull()!!.items[0].title).isEqualTo("New Movie")
    }

    @Test
    fun `fetchWatchHistory_分页信息正确传递`() = runTest {
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(
                emptyList(),
                Headers.headersOf("X-Pagination-Item-Count", "50", "X-Pagination-Page-Count", "3")
            )
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns
            Response.success(
                emptyList(),
                Headers.headersOf("X-Pagination-Item-Count", "30", "X-Pagination-Page-Count", "2")
            )

        val result = repository.fetchWatchHistory(1)
        val page = result.getOrNull()!!
        // totalPages = max(3, 2) = 3
        assertThat(page.totalPages).isEqualTo(3)
        // totalCount = 50 + 30 = 80
        assertThat(page.totalCount).isEqualTo(80)
        assertThat(page.currentPage).isEqualTo(1)
    }
}
