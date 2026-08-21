package com.tracktosearch.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.local.db.MarkActionRecordDao
import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.remote.trakt.dto.TraktHistoryEntry
import com.tracktosearch.data.remote.trakt.dto.TraktHistoryEpisode
import com.tracktosearch.data.remote.trakt.dto.TraktHistoryIds
import com.tracktosearch.data.remote.trakt.dto.TraktHistoryMovie
import com.tracktosearch.data.remote.trakt.dto.TraktHistoryShow
import com.tracktosearch.data.remote.trakt.dto.TraktIds
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktShow
import com.tracktosearch.data.remote.trakt.dto.TraktAvatar
import com.tracktosearch.data.remote.trakt.dto.TraktUserImages
import com.tracktosearch.data.remote.trakt.dto.TraktUserProfileResponse
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
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
@Config(sdk = [33], application = android.app.Application::class)
class TraktRepositoryTest {

    private lateinit var traktApiService: TraktApiService
    private lateinit var userProfileStorage: UserProfileStorage
    private lateinit var markActionRecordDao: MarkActionRecordDao
    private lateinit var tmdbRepository: TmdbRepository
    private lateinit var repository: TraktRepository

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() = runTest {
        traktApiService = mockk(relaxed = true)
        userProfileStorage = mockk(relaxed = true)
        markActionRecordDao = mockk(relaxed = true)
        tmdbRepository = mockk(relaxed = true)

        // 默认 stub：API 返回空成功响应
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns emptySuccessResponse()
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns emptyEpisodeResponse()

        repository = TraktRepository(
            traktApiService, userProfileStorage, markActionRecordDao,
            tmdbRepository, mockk(relaxed = true), Json { ignoreUnknownKeys = true }, context
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

    @Test
    fun `getWatchlist_读取服务端电影和电视剧总条数`() = runTest {
        coEvery { traktApiService.getWatchlist(any(), any(), any(), any()) } returns Response.success(
            emptyList<TraktWatchlistMovieItem>(),
            Headers.headersOf("X-Pagination-Item-Count", "237", "X-Pagination-Page-Count", "2")
        )
        coEvery { traktApiService.getShowWatchlist(any(), any(), any(), any()) } returns Response.success(
            emptyList<TraktWatchlistShowItem>(),
            Headers.headersOf("X-Pagination-Item-Count", "19", "X-Pagination-Page-Count", "1")
        )

        assertThat(repository.getMovieWatchlist(1, 200, forceRefresh = true).getOrThrow().second).isEqualTo(2)
        assertThat(repository.getMovieWatchlistTotalCount(1, 200)).isEqualTo(237)
        assertThat(repository.getShowWatchlist(1, 200, forceRefresh = true).getOrThrow().second).isEqualTo(1)
        assertThat(repository.getShowWatchlistTotalCount(1, 200)).isEqualTo(19)
    }

    @Test
    fun `loadWatchlistWatchedIds_propagates_snapshot_failure`() = runTest {
        repository.clearWatchlistWatchedCache()
        coEvery { traktApiService.getWatchlist(any(), any(), any(), any()) } returns
            Response.success(emptyList(), Headers.headersOf("X-Pagination-Page-Count", "1"))
        coEvery { traktApiService.getShowWatchlist(any(), any(), any(), any()) } returns
            Response.success(emptyList(), Headers.headersOf("X-Pagination-Page-Count", "1"))
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.error(503, "movie history unavailable".toResponseBody())
        coEvery { traktApiService.getShowHistory(any(), any(), any(), any()) } returns
            Response.success(emptyList(), Headers.headersOf("X-Pagination-Page-Count", "1"))

        val failure = runCatching { repository.loadWatchlistWatchedIds() }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(repository.getWatchlistWatchedIds()).isNull()
    }

    @Test
    fun `loadWatchlistWatchedIds_forceRefresh_bypasses_all_caches`() = runTest {
        repository.clearWatchlistWatchedCache()
        val headers = Headers.headersOf("X-Pagination-Page-Count", "1")
        coEvery { traktApiService.getWatchlist(any(), any(), any(), any()) } returns
            Response.success(listOf(TraktWatchlistMovieItem(movie = TraktMovie(ids = TraktIds(trakt = 1, tmdb = 101)))), headers)
        coEvery { traktApiService.getShowWatchlist(any(), any(), any(), any()) } returns
            Response.success(listOf(TraktWatchlistShowItem(show = TraktShow(ids = TraktIds(trakt = 2, tmdb = 102)))), headers)
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(TraktWatchlistMovieItem(movie = TraktMovie(ids = TraktIds(trakt = 3, tmdb = 103)))), headers)
        coEvery { traktApiService.getShowHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(TraktWatchlistShowItem(show = TraktShow(ids = TraktIds(trakt = 4, tmdb = 104)))), headers)

        val cached = repository.loadWatchlistWatchedIds()

        coEvery { traktApiService.getWatchlist(any(), any(), any(), any()) } returns
            Response.success(listOf(TraktWatchlistMovieItem(movie = TraktMovie(ids = TraktIds(trakt = 11, tmdb = 111)))), headers)
        coEvery { traktApiService.getShowWatchlist(any(), any(), any(), any()) } returns
            Response.success(listOf(TraktWatchlistShowItem(show = TraktShow(ids = TraktIds(trakt = 12, tmdb = 112)))), headers)
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(TraktWatchlistMovieItem(movie = TraktMovie(ids = TraktIds(trakt = 13, tmdb = 113)))), headers)
        coEvery { traktApiService.getShowHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(TraktWatchlistShowItem(show = TraktShow(ids = TraktIds(trakt = 14, tmdb = 114)))), headers)

        val refreshed = repository.loadWatchlistWatchedIds(forceRefresh = true)

        assertThat(cached.movieWatchlistTraktIds).containsExactly(1)
        assertThat(refreshed.movieWatchlistTraktIds).containsExactly(11)
        assertThat(refreshed.showWatchlistTraktIds).containsExactly(12)
        assertThat(refreshed.movieWatchedTraktIds).containsExactly(13)
        assertThat(refreshed.showWatchedTraktIds).containsExactly(14)
        coVerify(exactly = 2) { traktApiService.getWatchlist(any(), any(), any(), any()) }
        coVerify(exactly = 2) { traktApiService.getShowWatchlist(any(), any(), any(), any()) }
        coVerify(exactly = 2) { traktApiService.getMovieHistory(any(), any(), any(), any()) }
        coVerify(exactly = 2) { traktApiService.getShowHistory(any(), any(), any(), any()) }
    }

    @Test
    fun `getUserProfile_缓存头像为空时按用户名补拉真实头像`() = runTest {
        val cached = TraktUserProfileResponse(username = "yuhu", name = "yufeng liang")
        val refreshed = cached.copy(
            images = TraktUserImages(
                avatar = TraktAvatar("https://walter.trakt.tv/images/users/yuhu/avatar.jpg")
            )
        )
        coEvery { userProfileStorage.getProfile() } returns cached
        coEvery { traktApiService.getUserProfileByUsername("yuhu", "full") } returns Response.success(refreshed)

        val result = repository.getUserProfile()

        assertThat(result.getOrNull()?.images?.avatar?.full)
            .isEqualTo("https://walter.trakt.tv/images/users/yuhu/avatar.jpg")
        coVerify(exactly = 1) { traktApiService.getUserProfileByUsername("yuhu", "full") }
        coVerify(exactly = 1) { userProfileStorage.saveProfile(refreshed) }
    }

    // ==================== parseTraktDate（反射）====================

    @Test
    fun `getUserProfile_profile接口405时回退公开用户资料`() = runTest {
        val cached = TraktUserProfileResponse(username = "yuhu", name = "yufeng liang")
        val refreshed = cached.copy(
            images = TraktUserImages(
                avatar = TraktAvatar("https://walter.trakt.tv/images/users/yuhu/avatar.jpg")
            )
        )
        coEvery { userProfileStorage.getProfile() } returns cached
        coEvery {
            traktApiService.getUserProfileByUsername("yuhu", "full")
        } returns Response.error(405, "".toResponseBody())
        coEvery {
            traktApiService.getUserByUsername("yuhu", "full")
        } returns Response.success(refreshed)

        val result = repository.getUserProfile()

        assertThat(result.getOrNull()?.images?.avatar?.full)
            .isEqualTo("https://walter.trakt.tv/images/users/yuhu/avatar.jpg")
        coVerify(exactly = 1) { traktApiService.getUserByUsername("yuhu", "full") }
        coVerify(exactly = 1) { userProfileStorage.saveProfile(refreshed) }
    }

    @Test
    fun `clearTraktAccountCaches_清理个性化电影推荐缓存`() = runTest {
        val limit = 97
        val first = listOf(TraktMovie(title = "first account"))
        val second = listOf(TraktMovie(title = "second account"))
        coEvery { traktApiService.getMovieRecommendations(limit, "full") } returns
            Response.success(first) andThen Response.success(second)

        repository.clearTraktAccountCaches()
        assertThat(repository.getRecommendations(limit).getOrThrow()).isEqualTo(first)

        repository.clearTraktAccountCaches()
        assertThat(repository.getRecommendations(limit).getOrThrow()).isEqualTo(second)
        coVerify(exactly = 2) { traktApiService.getMovieRecommendations(limit, "full") }
    }

    @Test
    fun `旧会话中的推荐请求完成后不得重新写入缓存`() = runTest {
        val limit = 98
        val requestStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<Response<List<TraktMovie>>>()
        val old = listOf(TraktMovie(title = "old account"))
        val current = listOf(TraktMovie(title = "current account"))
        var requestCount = 0
        coEvery { traktApiService.getMovieRecommendations(limit, "full") } coAnswers {
            if (requestCount++ == 0) {
                requestStarted.complete(Unit)
                oldResponse.await()
            } else {
                Response.success(current)
            }
        }

        val oldRequest = async { repository.getRecommendations(limit) }
        requestStarted.await()
        repository.clearTraktAccountCaches()
        oldResponse.complete(Response.success(old))
        val oldResult = runCatching { oldRequest.await() }

        assertThat(oldResult.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(repository.getRecommendations(limit).getOrThrow()).isEqualTo(current)
        coVerify(exactly = 2) { traktApiService.getMovieRecommendations(limit, "full") }
    }

    @Test
    fun `旧会话中的watchlist请求完成后不得向调用方返回旧快照`() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<Response<List<TraktWatchlistMovieItem>>>()
        val headers = Headers.headersOf("X-Pagination-Page-Count", "1")
        val oldMovie = TraktWatchlistMovieItem(
            movie = TraktMovie(ids = TraktIds(trakt = 701, tmdb = 1701))
        )
        coEvery { traktApiService.getWatchlist(any(), any(), any(), any()) } coAnswers {
            requestStarted.complete(Unit)
            oldResponse.await()
        }
        coEvery { traktApiService.getShowWatchlist(any(), any(), any(), any()) } returns
            Response.success(emptyList(), headers)
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(emptyList(), headers)
        coEvery { traktApiService.getShowHistory(any(), any(), any(), any()) } returns
            Response.success(emptyList(), headers)

        val oldRequest = async { repository.loadWatchlistWatchedIds() }
        requestStarted.await()
        repository.clearTraktAccountCaches()
        oldResponse.complete(Response.success(listOf(oldMovie), headers))
        val oldResult = runCatching { oldRequest.await() }

        assertThat(oldResult.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `旧会话的watchlist请求完成后不得回写总条数`() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<Response<List<TraktWatchlistMovieItem>>>()
        val oldHeaders = Headers.headersOf(
            "X-Pagination-Item-Count", "701",
            "X-Pagination-Page-Count", "1"
        )
        val currentHeaders = Headers.headersOf(
            "X-Pagination-Item-Count", "702",
            "X-Pagination-Page-Count", "1"
        )
        var requestCount = 0
        coEvery { traktApiService.getWatchlist(any(), any(), any(), any()) } coAnswers {
            if (requestCount++ == 0) {
                requestStarted.complete(Unit)
                oldResponse.await()
            } else {
                Response.success(emptyList(), currentHeaders)
            }
        }

        val oldRequest = async { repository.getMovieWatchlist(page = 1, limit = 200) }
        requestStarted.await()
        repository.clearTraktAccountCaches()
        oldResponse.complete(Response.success(emptyList(), oldHeaders))

        val oldResult = runCatching { oldRequest.await() }
        assertThat(oldResult.getOrThrow().exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(repository.getMovieWatchlistTotalCount(page = 1, limit = 200)).isNull()

        repository.getMovieWatchlist(page = 1, limit = 200).getOrThrow()
        assertThat(repository.getMovieWatchlistTotalCount(page = 1, limit = 200)).isEqualTo(702)
    }

    @Test
    fun `旧会话的history请求完成后不得填充聚合缓存`() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<Response<List<TraktWatchlistMovieItem>>>()
        val headers = Headers.headersOf("X-Pagination-Page-Count", "1")
        var requestCount = 0
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } coAnswers {
            if (requestCount++ == 0) {
                requestStarted.complete(Unit)
                oldResponse.await()
            } else {
                Response.success(emptyList(), headers)
            }
        }

        val oldRequest = async { repository.getAllMovieHistory() }
        requestStarted.await()
        repository.clearTraktAccountCaches()
        oldResponse.complete(Response.success(emptyList(), headers))

        val oldResult = runCatching { oldRequest.await() }
        assertThat(oldResult.exceptionOrNull()).isInstanceOf(CancellationException::class.java)

        repository.getAllMovieHistory().getOrThrow()
        coVerify(exactly = 2) { traktApiService.getMovieHistory(any(), any(), any(), any()) }
    }

    @Test
    fun `单独清理用户资料后旧请求不得回写资料`() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<Response<TraktUserProfileResponse>>()
        val oldProfile = TraktUserProfileResponse(username = "old-account")
        coEvery { userProfileStorage.getProfile() } returns null
        coEvery { traktApiService.getUserProfile() } coAnswers {
            requestStarted.complete(Unit)
            oldResponse.await()
        }

        val oldRequest = async { repository.getUserProfile() }
        requestStarted.await()
        repository.clearUserProfileCache()
        oldResponse.complete(Response.success(oldProfile))

        val oldResult = runCatching { oldRequest.await() }
        assertThat(oldResult.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        coVerify(exactly = 0) { userProfileStorage.saveProfile(any()) }
    }

    @Test
    fun `checkTraktConnection_成功时复用已获取的用户资料`() = runTest {
        val profile = TraktUserProfileResponse(username = "yuhu", name = "yufeng liang")
        coEvery { traktApiService.getUserProfile() } returns Response.success(profile)

        assertThat(repository.checkTraktConnection()).isTrue()

        val cachedResult = repository.getUserProfile()
        assertThat(cachedResult.getOrNull()).isEqualTo(profile)
        coVerify(exactly = 1) { traktApiService.getUserProfile() }
        coVerify(exactly = 1) { userProfileStorage.saveProfile(profile) }
    }

    @Test
    fun `watchHistory缓存命中时收集器触发清理不得死锁`() = runTest {
        repository.fetchWatchHistory(1).toList()

        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(1_000L) {
                repository.fetchWatchHistory(1).collect {
                    repository.clearTraktAccountCaches()
                }
            }
        }
    }

    @Test
    fun `getCachedUserProfile_publishesCachedProfileForImmediateUi`() = runTest {
        val cached = TraktUserProfileResponse(username = "yuhu", name = "yufeng liang")
        coEvery { userProfileStorage.getProfile() } returns cached

        repository.getCachedUserProfile()

        assertThat(repository.userProfile.value).isEqualTo(cached)
    }

    @Test
    fun `checkTraktConnection_401_returnsDisconnected`() = runTest {
        coEvery { traktApiService.getUserProfile() } returns
            Response.error(401, "".toResponseBody())

        assertThat(repository.checkTraktConnectionResult())
            .isEqualTo(com.tracktosearch.data.remote.trakt.TraktConnectionCheckResult.DISCONNECTED)
    }

    @Test
    fun `checkTraktConnection_networkFailure_returnsUnknown`() = runTest {
        coEvery { traktApiService.getUserProfile() } throws IOException("offline")

        assertThat(repository.checkTraktConnectionResult())
            .isEqualTo(com.tracktosearch.data.remote.trakt.TraktConnectionCheckResult.UNKNOWN)
    }

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
    fun `fetchWatchHistory_返回Flow_分批emit且末批isComplete`() = runTest {
        val movieEntry = TraktWatchlistMovieItem(
            watched_at = "2024-01-01T00:00:00.000Z",
            movie = TraktMovie(title = "Movie A", year = 2024, ids = TraktIds(trakt = 1, tmdb = 10, imdb = "tt1"))
        )
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(movieEntry), Headers.headersOf("X-Pagination-Item-Count", "1", "X-Pagination-Page-Count", "1"))
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns
            Response.success(emptyList(), Headers.headersOf("X-Pagination-Item-Count", "0", "X-Pagination-Page-Count", "1"))

        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.isNotEmpty()).isTrue()
        assertThat(result.last().isComplete).isTrue()
        assertThat(result.last().items).hasSize(1)
        // 富化前先发一批未富化占位，让列表立刻有内容。
        // 用 first { !enriched } 而不是 first()：磁盘快照命中时会先发一批已富化的旧数据。
        val placeholder = result.first { !it.enriched }
        assertThat(placeholder.isComplete).isFalse()
        assertThat(placeholder.items).hasSize(1)
    }

    @Test
    fun `fetchWatchHistory_首批未富化占位用Trakt原始标题且无海报`() = runTest {
        coEvery { tmdbRepository.enrichMovie(any(), any(), any()) } returns TmdbRepository.MovieEnrichment(
            posterUrl = "/poster.jpg",
            chineseTitle = "中文名",
            originalTitle = "Movie A",
            overview = "",
            genres = "",
            year = 2024,
            rating = 0.0,
            runtime = 0,
            releaseDate = "",
            country = "",
            status = "",
            collectionId = null,
            imdbId = "tt1"
        )
        val movieEntry = TraktWatchlistMovieItem(
            watched_at = "2024-01-01T00:00:00.000Z",
            movie = TraktMovie(title = "Movie A", year = 2024, ids = TraktIds(trakt = 1, tmdb = 10, imdb = "tt1"))
        )
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(movieEntry), Headers.headersOf("X-Pagination-Item-Count", "1", "X-Pagination-Page-Count", "1"))
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns
            Response.success(emptyList(), Headers.headersOf("X-Pagination-Item-Count", "0", "X-Pagination-Page-Count", "1"))

        val result = repository.fetchWatchHistory(1).toList()
        val placeholder = result.first { !it.enriched }
        assertThat(placeholder.items.single().displayTitle).isEqualTo("Movie A")
        assertThat(placeholder.items.single().posterUrl).isNull()
        // 富化完成的末批替换为中文名和海报
        assertThat(result.last().isComplete).isTrue()
        assertThat(result.last().items.single().displayTitle).isEqualTo("中文名")
        assertThat(result.last().items.single().posterUrl).isEqualTo("/poster.jpg")
    }

    @Test
    fun `fetchWatchHistory_同一tmdbId只富化一次`() = runTest {
        // 同一部剧的 30 集：原实现每条各发一次 TMDB 请求，去重后只应发一次
        val episodes = (1..30).map { i ->
            TraktHistoryEntry(
                watched_at = "2024-01-01T00:00:00.000Z",
                show = TraktHistoryShow(title = "Show", year = 2024, ids = TraktHistoryIds(trakt = 7, tmdb = 700, imdb = "tt7")),
                episode = TraktHistoryEpisode(season = 1, number = i, ids = TraktHistoryIds(trakt = 1000 + i))
            )
        }
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(emptyList(), Headers.headersOf("X-Pagination-Item-Count", "0", "X-Pagination-Page-Count", "1"))
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns
            Response.success(episodes, Headers.headersOf("X-Pagination-Item-Count", "30", "X-Pagination-Page-Count", "1"))

        val result = repository.fetchWatchHistory(1).toList()

        assertThat(result.last().isComplete).isTrue()
        assertThat(result.last().items).hasSize(30)
        coVerify(exactly = 1) { tmdbRepository.enrichTv(700, any(), any()) }
    }

    @Test
    fun `fetchWatchHistory_分批emit数量正确`() = runTest {
        val movies = (1..45).map { i ->
            TraktWatchlistMovieItem(
                watched_at = "2024-01-${String.format("%02d", i)}T00:00:00.000Z",
                movie = TraktMovie(title = "Movie $i", year = 2024, ids = TraktIds(trakt = i, tmdb = i + 100, imdb = "tt$i"))
            )
        }
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(movies, Headers.headersOf("X-Pagination-Item-Count", "45", "X-Pagination-Page-Count", "1"))
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns
            Response.success(emptyList(), Headers.headersOf("X-Pagination-Item-Count", "0", "X-Pagination-Page-Count", "1"))

        val result = repository.fetchWatchHistory(1).toList()
        val finalBatch = result.last()
        assertThat(finalBatch.isComplete).isTrue()
        assertThat(finalBatch.error).isNull()
        // 末批包含全部 45 条
        assertThat(finalBatch.items).hasSize(45)
        // 未富化占位批一次给出全部条目
        assertThat(result.first { !it.enriched }.items).hasSize(45)
        // 富化过程分批提交：首批阈值 6，之后每 20 条一批 → 6 / 20 / 40
        val enrichedProgress = result.filter { it.enriched && !it.isComplete }.map { it.items.size }
        assertThat(enrichedProgress).containsExactly(6, 20, 40).inOrder()
    }

    @Test
    fun `fetchWatchHistory_enrich失败单条兜底不崩溃`() = runTest {
        coEvery { tmdbRepository.enrichMovie(eq(102), any(), any()) } throws RuntimeException("TMDB boom")
        val movies = listOf(
            TraktWatchlistMovieItem(
                watched_at = "2024-01-03T00:00:00.000Z",
                movie = TraktMovie(title = "Movie 3", year = 2024, ids = TraktIds(trakt = 3, tmdb = 101, imdb = "tt3"))
            ),
            TraktWatchlistMovieItem(
                watched_at = "2024-01-02T00:00:00.000Z",
                movie = TraktMovie(title = "Movie 2", year = 2024, ids = TraktIds(trakt = 2, tmdb = 102, imdb = "tt2"))
            ),
            TraktWatchlistMovieItem(
                watched_at = "2024-01-01T00:00:00.000Z",
                movie = TraktMovie(title = "Movie 1", year = 2024, ids = TraktIds(trakt = 1, tmdb = 103, imdb = "tt1"))
            )
        )
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(movies, Headers.headersOf("X-Pagination-Item-Count", "3", "X-Pagination-Page-Count", "1"))
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns
            Response.success(emptyList(), Headers.headersOf("X-Pagination-Item-Count", "0", "X-Pagination-Page-Count", "1"))

        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.last().isComplete).isTrue()
        assertThat(result.last().error).isNull()
        assertThat(result.last().items).hasSize(3)
    }

    @Test
    fun `fetchWatchHistory_空响应返回空列表`() = runTest {
        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.isNotEmpty()).isTrue()
        assertThat(result.last().isComplete).isTrue()
        assertThat(result.last().items).isEmpty()
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

        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.isNotEmpty()).isTrue()
        val items = result.last().items
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

        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.isNotEmpty()).isTrue()
        assertThat(result.last().isComplete).isTrue()
        assertThat(result.last().error).isNotNull()
    }

    @Test
    fun `fetchWatchHistory_缓存命中不调用API`() = runTest {
        // 第一次调用：走 API
        repository.fetchWatchHistory(1).toList()

        // 第二次调用：应走缓存
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val result2 = repository.fetchWatchHistory(1).toList()
        assertThat(result2.isNotEmpty()).isTrue()
        assertThat(result2.last().isComplete).isTrue()
    }

    @Test
    fun `clearWatchHistoryCache_后重新走API`() = runTest {
        // 第一次调用：走 API 并缓存
        repository.fetchWatchHistory(1).toList()

        // 清空缓存
        repository.clearWatchHistoryCache()

        // 重新调用应走 API（配置新的返回）
        val movieEntry = TraktWatchlistMovieItem(
            watched_at = "2024-03-01T00:00:00.000Z",
            movie = TraktMovie(title = "New Movie", year = 2024, ids = TraktIds(trakt = 99, tmdb = 99, imdb = "tt99"))
        )
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(movieEntry), Headers.headersOf("X-Pagination-Item-Count", "1", "X-Pagination-Page-Count", "1"))

        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.last().isComplete).isTrue()
        assertThat(result.last().items).hasSize(1)
        assertThat(result.last().items[0].title).isEqualTo("New Movie")
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

        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.isNotEmpty()).isTrue()
        assertThat(result.last().isComplete).isTrue()
        assertThat(result.last().items).isEmpty()
    }

    // ==================== fetchWatchHistory enrichment 字段验证（测试点 B）====================
    // 这些测试验证 fetchWatchHistory 返回的 WatchHistoryItem 的 displayTitle/posterUrl/year/imdbId
    // 来自 TmdbRepository.enrichMovie/enrichTv，而不是 Trakt 原始英文标题或空海报。
    // 这是「已看历史列表标题本地化+海报URL」bug 的核心防护。

    @Test
    fun `fetchWatchHistory_movie记录displayTitle和posterUrl来自TmdbEnrichment`() = runTest {
        val movieEntry = TraktWatchlistMovieItem(
            watched_at = "2024-01-01T00:00:00.000Z",
            movie = TraktMovie(title = "Inception", year = 2024, ids = TraktIds(trakt = 1, tmdb = 10, imdb = "tt1"))
        )
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(movieEntry), Headers.headersOf("X-Pagination-Item-Count", "1", "X-Pagination-Page-Count", "1"))
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns emptyEpisodeResponse()
        coEvery { tmdbRepository.enrichMovie(10, "Inception", 2024) } returns TmdbRepository.MovieEnrichment(
            posterUrl = "https://image.tmdb.org/t/p/w500/inception.jpg",
            chineseTitle = "盗梦空间",
            originalTitle = "Inception",
            overview = "",
            genres = "",
            year = 2010,
            rating = 8.8,
            imdbId = "tt1375666"
        )

        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.isNotEmpty()).isTrue()
        val item = result.last().items[0]

        // title 字段保留 Trakt 原始标题（用于 fallback）
        assertThat(item.title).isEqualTo("Inception")
        // displayTitle 来自 enrichment.chineseTitle（本地化标题）
        assertThat(item.displayTitle).isEqualTo("盗梦空间")
        // posterUrl 来自 enrichment（完整 URL）
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/inception.jpg")
        // year 来自 enrichment（2010，而非 Trakt 的 2024）
        assertThat(item.year).isEqualTo(2010)
        // imdbId 来自 enrichment
        assertThat(item.imdbId).isEqualTo("tt1375666")
    }

    @Test
    fun `fetchWatchHistory_episode记录displayTitle和posterUrl来自TmdbEnrichment`() = runTest {
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns emptySuccessResponse()
        val episodeEntry = TraktHistoryEntry(
            watched_at = "2024-06-01T00:00:00.000Z",
            episode = TraktHistoryEpisode(season = 1, number = 1, title = "Ep1", ids = TraktHistoryIds()),
            show = TraktHistoryShow(title = "Breaking Bad", year = 2024, ids = TraktHistoryIds(trakt = 2, tmdb = 20, imdb = "tt2"))
        )
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(episodeEntry), Headers.headersOf("X-Pagination-Item-Count", "1", "X-Pagination-Page-Count", "1"))
        coEvery { tmdbRepository.enrichTv(20, "Breaking Bad", 2024) } returns TmdbRepository.TvEnrichment(
            posterUrl = "https://image.tmdb.org/t/p/w500/bb.jpg",
            chineseTitle = "绝命毒师",
            originalTitle = "Breaking Bad",
            overview = "",
            genres = "",
            year = 2008,
            rating = 9.5,
            imdbId = "tt0903747"
        )

        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.isNotEmpty()).isTrue()
        val item = result.last().items[0]

        assertThat(item.title).isEqualTo("Breaking Bad")
        assertThat(item.displayTitle).isEqualTo("绝命毒师")
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/bb.jpg")
        assertThat(item.year).isEqualTo(2008)
        assertThat(item.imdbId).isEqualTo("tt0903747")
        assertThat(item.episodeInfo).isEqualTo("S1E1")
    }

    @Test
    fun `fetchWatchHistory_enrichment返回空chineseTitle时displayTitle回退到TraktTitle`() = runTest {
        val movieEntry = TraktWatchlistMovieItem(
            watched_at = "2024-01-01T00:00:00.000Z",
            movie = TraktMovie(title = "Inception", year = 2024, ids = TraktIds(trakt = 1, tmdb = 10, imdb = "tt1"))
        )
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(movieEntry), Headers.headersOf("X-Pagination-Item-Count", "1", "X-Pagination-Page-Count", "1"))
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns emptyEpisodeResponse()
        // enrichment 返回空 chineseTitle → displayTitle 应回退到 Trakt 原始标题
        coEvery { tmdbRepository.enrichMovie(10, "Inception", 2024) } returns TmdbRepository.MovieEnrichment(
            posterUrl = null,
            chineseTitle = "",
            originalTitle = "",
            overview = "",
            genres = "",
            year = null,
            rating = 0.0,
            imdbId = null
        )

        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.isNotEmpty()).isTrue()
        val item = result.last().items[0]

        // displayTitle = "".ifBlank { "Inception" } = "Inception"
        assertThat(item.displayTitle).isEqualTo("Inception")
        assertThat(item.posterUrl).isNull()
    }

    // ==================== fetchWatchHistory enrichment 失败降级（测试点 C）====================

    @Test
    fun `fetchWatchHistory_enrichment失败时displayTitle回退到TraktTitle不崩溃`() = runTest {
        val movieEntry = TraktWatchlistMovieItem(
            watched_at = "2024-01-01T00:00:00.000Z",
            movie = TraktMovie(title = "Inception", year = 2024, ids = TraktIds(trakt = 1, tmdb = 10, imdb = "tt1"))
        )
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(movieEntry), Headers.headersOf("X-Pagination-Item-Count", "1", "X-Pagination-Page-Count", "1"))
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns emptyEpisodeResponse()
        // enrichment 抛异常 → displayTitle 应回退到 Trakt 原始标题，posterUrl 为 null
        coEvery { tmdbRepository.enrichMovie(any(), any(), any()) } throws RuntimeException("TMDB API error")

        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.isNotEmpty()).isTrue()
        assertThat(result.last().isComplete).isTrue()
        val item = result.last().items[0]
        // 降级：displayTitle 回退到 Trakt 标题
        assertThat(item.displayTitle).isEqualTo("Inception")
        assertThat(item.posterUrl).isNull()
    }

    @Test
    fun `fetchWatchHistory_tmdbId为0时不调用enrich_字段用Trakt原始值`() = runTest {
        val movieEntry = TraktWatchlistMovieItem(
            watched_at = "2024-01-01T00:00:00.000Z",
            movie = TraktMovie(title = "Unknown Movie", year = 2023, ids = TraktIds(trakt = 1, tmdb = 0, imdb = "tt1"))
        )
        coEvery { traktApiService.getMovieHistory(any(), any(), any(), any()) } returns
            Response.success(listOf(movieEntry), Headers.headersOf("X-Pagination-Item-Count", "1", "X-Pagination-Page-Count", "1"))
        coEvery { traktApiService.getEpisodeHistory(any(), any(), any(), any()) } returns emptyEpisodeResponse()

        val result = repository.fetchWatchHistory(1).toList()
        assertThat(result.isNotEmpty()).isTrue()
        val item = result.last().items[0]

        // tmdbId=0 不触发 enrich
        coVerify(exactly = 0) { tmdbRepository.enrichMovie(any(), any(), any()) }
        // 字段用 Trakt 原始值
        assertThat(item.displayTitle).isEqualTo("Unknown Movie")
        assertThat(item.title).isEqualTo("Unknown Movie")
        assertThat(item.posterUrl).isNull()
    }
}
