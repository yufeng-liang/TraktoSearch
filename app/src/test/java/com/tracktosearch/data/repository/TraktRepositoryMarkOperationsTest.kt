package com.tracktosearch.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.local.db.MarkActionRecordDao
import com.tracktosearch.data.local.db.MarkActionRecordEntity
import com.tracktosearch.data.local.db.MarkActionType
import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.remote.trakt.dto.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * TraktRepository 标记操作、批量操作、评分操作、评论操作单元测试。
 *
 * 覆盖：
 * - 单集标记（markEpisodeWatched / markEpisodesWatched / unmarkEpisodeWatched）
 * - 影视标记（markAsWatched / removeWatched，含副操作一致性）
 * - 想看列表（addToWatchlist / removeFromWatchlist）
 * - 评分（addRating / removeRating / batchAddRatings / batchAddRatingsAt）
 * - 批量同步（batchAddToWatchlist / batchRemoveFromWatchlist / batchMarkAsWatched / batchMarkAsWatchedAt / batchRemoveFromWatched）
 * - 评论（postComment / getComments）
 *
 * watchlistWatchedIds 字段通过反射预设，以验证缓存更新逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class TraktRepositoryMarkOperationsTest {

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

        repository = TraktRepository(
            traktApiService, userProfileStorage, markActionRecordDao,
            tmdbRepository, mockk(relaxed = true), Json { ignoreUnknownKeys = true },
            mockk(relaxed = true), context
        )
    }

    // ==================== 反射辅助 ====================

    private fun setWatchlistWatchedIds(ids: TraktRepository.WatchlistWatchedIds?) {
        val field = TraktRepository::class.java.getDeclaredField("watchlistWatchedIds")
        field.isAccessible = true
        field.set(repository, ids)
    }

    private fun getWatchlistWatchedIds(): TraktRepository.WatchlistWatchedIds? {
        val field = TraktRepository::class.java.getDeclaredField("watchlistWatchedIds")
        field.isAccessible = true
        return field.get(repository) as TraktRepository.WatchlistWatchedIds?
    }

    // ==================== 测试数据 ====================

    private fun emptyIds() = TraktRepository.WatchlistWatchedIds()

    private fun successSyncResponse() = Response.success(TraktSyncResponse())

    private fun errorResponse(code: Int = 500) = Response.error<TraktSyncResponse>(
        code, "".toResponseBody(null)
    )

    private fun successUnitResponse() = Response.success(Unit)

    private fun errorUnitResponse(code: Int = 500) = Response.error<Unit>(
        code, "".toResponseBody(null)
    )

    private fun movieEnrichment(
        chineseTitle: String = "",
        originalTitle: String = "",
        posterUrl: String? = null,
        year: Int? = null,
        imdbId: String? = null
    ) = TmdbRepository.MovieEnrichment(
        posterUrl = posterUrl,
        chineseTitle = chineseTitle,
        originalTitle = originalTitle,
        overview = "",
        genres = "",
        year = year,
        rating = 0.0,
        imdbId = imdbId
    )

    private fun tvEnrichment(
        chineseTitle: String = "",
        originalTitle: String = "",
        posterUrl: String? = null,
        year: Int? = null,
        imdbId: String? = null
    ) = TmdbRepository.TvEnrichment(
        posterUrl = posterUrl,
        chineseTitle = chineseTitle,
        originalTitle = originalTitle,
        overview = "",
        genres = "",
        year = year,
        rating = 0.0,
        imdbId = imdbId
    )

    // ==================== markEpisodeWatched ====================

    @Test
    fun markEpisodeWatched_API失败返回failure() = runTest {
        coEvery { traktApiService.addToHistory(any()) } returns errorResponse(404)

        val result = repository.markEpisodeWatched(12345)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun markEpisodeWatched_request包含正确的episodeId() = runTest {
        val requestSlot = slot<TraktSyncRequest>()
        coEvery { traktApiService.addToHistory(capture(requestSlot)) } returns successSyncResponse()

        repository.markEpisodeWatched(999)

        assertThat(requestSlot.captured.episodes).hasSize(1)
        assertThat(requestSlot.captured.episodes!![0].ids.trakt).isEqualTo(999)
        assertThat(requestSlot.captured.movies).isNull()
        assertThat(requestSlot.captured.shows).isNull()
    }

    // ==================== markEpisodesWatched ====================

    @Test
    fun markEpisodesWatched_成功更新缓存() = runTest {
        setWatchlistWatchedIds(emptyIds())
        coEvery { traktApiService.addToHistory(any()) } returns successSyncResponse()

        val result = repository.markEpisodesWatched(listOf(1, 2, 3), showTraktId = 100, showTmdbId = 200)

        assertThat(result.isSuccess).isTrue()
        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.showWatchedTraktIds).contains(100)
        assertThat(ids.showWatchedTmdbIds).contains(200)
    }

    @Test
    fun markEpisodesWatched_API失败返回failure() = runTest {
        coEvery { traktApiService.addToHistory(any()) } returns errorResponse(500)

        val result = repository.markEpisodesWatched(listOf(1, 2), showTraktId = 100)

        assertThat(result.isFailure).isTrue()
    }

    // ==================== unmarkEpisodeWatched ====================

    @Test
    fun unmarkEpisodeWatched_成功且写流水() = runTest {
        coEvery { traktApiService.removeFromHistory(any()) } returns successSyncResponse()
        coEvery { markActionRecordDao.count() } returns 0

        val result = repository.unmarkEpisodeWatched(
            episodeTraktId = 555, season = 1, episode = 2,
            showTraktId = 100, showTmdbId = 200, showTitle = "测试剧集"
        )

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { markActionRecordDao.insert(any()) }
    }

    @Test
    fun unmarkEpisodeWatched_season为0不写流水() = runTest {
        coEvery { traktApiService.removeFromHistory(any()) } returns successSyncResponse()

        val result = repository.unmarkEpisodeWatched(
            episodeTraktId = 555, season = 0, episode = 0,
            showTraktId = 100, showTitle = "测试"
        )

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { markActionRecordDao.insert(any()) }
    }

    @Test
    fun unmarkEpisodeWatched_使用TmdbEnrichment填充title和displayTitle() = runTest {
        // 新逻辑：title 来自 enrichment.originalTitle（空则回退 chineseTitle），
        // displayTitle 来自 enrichment.chineseTitle（空则回退 showTitle）
        coEvery { traktApiService.removeFromHistory(any()) } returns successSyncResponse()
        coEvery { tmdbRepository.enrichTv(200, "传入剧名", null) } returns tvEnrichment(
            chineseTitle = "中文剧名",
            originalTitle = "OriginalName",
            posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
            year = 2023,
            imdbId = "tt123"
        )
        val recordSlot = slot<MarkActionRecordEntity>()
        coEvery { markActionRecordDao.insert(capture(recordSlot)) } returns Unit

        repository.unmarkEpisodeWatched(
            episodeTraktId = 555, season = 1, episode = 1,
            showTraktId = 100, showTmdbId = 200, showTitle = "传入剧名"
        )

        assertThat(recordSlot.captured.title).isEqualTo("OriginalName")
        assertThat(recordSlot.captured.displayTitle).isEqualTo("中文剧名")
        assertThat(recordSlot.captured.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/poster.jpg")
        assertThat(recordSlot.captured.year).isEqualTo(2023)
        assertThat(recordSlot.captured.imdbId).isEqualTo("tt123")
        assertThat(recordSlot.captured.episodeInfo).isEqualTo("S1E1")
    }

    @Test
    fun unmarkEpisodeWatched_API失败返回failure() = runTest {
        coEvery { traktApiService.removeFromHistory(any()) } returns errorResponse(500)

        val result = repository.unmarkEpisodeWatched(555, season = 1, episode = 1)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { markActionRecordDao.insert(any()) }
    }

    @Test
    fun unmarkEpisodeWatched_流水写入失败不影响主操作() = runTest {
        coEvery { traktApiService.removeFromHistory(any()) } returns successSyncResponse()
        coEvery { markActionRecordDao.insert(any()) } throws RuntimeException("DB error")

        val result = repository.unmarkEpisodeWatched(555, season = 1, episode = 1, showTraktId = 100)

        assertThat(result.isSuccess).isTrue()
    }

    // ==================== markAsWatched ====================

    @Test
    fun markAsWatched_成功_调用副操作removeFromWatchlist() = runTest {
        coEvery { traktApiService.addToHistory(any()) } returns successSyncResponse()
        coEvery { traktApiService.removeFromWatchlist(any()) } returns successSyncResponse()

        val result = repository.markAsWatched(100, MediaType.MOVIE, 200)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { traktApiService.addToHistory(any()) }
        coVerify(exactly = 1) { traktApiService.removeFromWatchlist(any()) }
    }

    @Test
    fun markAsWatched_成功_更新已看缓存() = runTest {
        setWatchlistWatchedIds(emptyIds())
        coEvery { traktApiService.addToHistory(any()) } returns successSyncResponse()
        coEvery { traktApiService.removeFromWatchlist(any()) } returns successSyncResponse()

        repository.markAsWatched(100, MediaType.MOVIE, 200)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.movieWatchedTraktIds).contains(100)
        assertThat(ids.movieWatchedTmdbIds).contains(200)
    }

    @Test
    fun markAsWatched_API失败_不调用副操作() = runTest {
        coEvery { traktApiService.addToHistory(any()) } returns errorResponse(404)

        val result = repository.markAsWatched(100, MediaType.MOVIE)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { traktApiService.removeFromWatchlist(any()) }
    }

    @Test
    fun markAsWatched_MOVIE类型_request含movies不含shows() = runTest {
        val requestSlot = slot<TraktSyncRequest>()
        coEvery { traktApiService.addToHistory(capture(requestSlot)) } returns successSyncResponse()
        coEvery { traktApiService.removeFromWatchlist(any()) } returns successSyncResponse()

        repository.markAsWatched(100, MediaType.MOVIE)

        assertThat(requestSlot.captured.movies).hasSize(1)
        assertThat(requestSlot.captured.shows).isNull()
    }

    @Test
    fun markAsWatched_SHOW类型_request含shows不含movies() = runTest {
        val requestSlot = slot<TraktSyncRequest>()
        coEvery { traktApiService.addToHistory(capture(requestSlot)) } returns successSyncResponse()
        coEvery { traktApiService.removeFromWatchlist(any()) } returns successSyncResponse()

        repository.markAsWatched(200, MediaType.SHOW)

        assertThat(requestSlot.captured.shows).hasSize(1)
        assertThat(requestSlot.captured.movies).isNull()
    }

    // ==================== removeWatched ====================

    @Test
    fun removeWatched_成功_调用副操作addToWatchlist() = runTest {
        coEvery { traktApiService.removeFromHistory(any()) } returns successSyncResponse()
        coEvery { traktApiService.addToWatchlist(any()) } returns successSyncResponse()

        val result = repository.removeWatched(100, MediaType.MOVIE, 200)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { traktApiService.removeFromHistory(any()) }
        coVerify(exactly = 1) { traktApiService.addToWatchlist(any()) }
    }

    @Test
    fun removeWatched_成功_更新缓存并写流水() = runTest {
        setWatchlistWatchedIds(emptyIds())
        coEvery { traktApiService.removeFromHistory(any()) } returns successSyncResponse()
        coEvery { traktApiService.addToWatchlist(any()) } returns successSyncResponse()

        repository.removeWatched(100, MediaType.MOVIE, 200)

        val ids = getWatchlistWatchedIds()!!
        // removeFromWatchedCache 会从已看移除并加回想看
        assertThat(ids.movieWatchedTraktIds).doesNotContain(100)
        assertThat(ids.movieWatchlistTraktIds).contains(100)
        // 写流水 UNMARK_WATCHED
        coVerify(exactly = 1) { markActionRecordDao.insert(any()) }
    }

    @Test
    fun removeWatched_API失败_不调用副操作也不写流水() = runTest {
        coEvery { traktApiService.removeFromHistory(any()) } returns errorResponse(404)

        val result = repository.removeWatched(100, MediaType.MOVIE)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { traktApiService.addToWatchlist(any()) }
        coVerify(exactly = 0) { markActionRecordDao.insert(any()) }
    }

    // ==================== addToWatchlist（含对称副操作）====================

    @Test
    fun addToWatchlist_成功_调用副操作removeFromHistory() = runTest {
        coEvery { traktApiService.addToWatchlist(any()) } returns successSyncResponse()
        coEvery { traktApiService.removeFromHistory(any()) } returns successSyncResponse()

        val result = repository.addToWatchlist(100, MediaType.MOVIE, 200)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { traktApiService.addToWatchlist(any()) }
        coVerify(exactly = 1) { traktApiService.removeFromHistory(any()) }
    }

    @Test
    fun addToWatchlist_成功_更新缓存并写流水() = runTest {
        // 初始：100 在已看中
        setWatchlistWatchedIds(TraktRepository.WatchlistWatchedIds(
            movieWatchedTraktIds = setOf(100),
            movieWatchedTmdbIds = setOf(200)
        ))
        coEvery { traktApiService.addToWatchlist(any()) } returns successSyncResponse()
        coEvery { traktApiService.removeFromHistory(any()) } returns successSyncResponse()

        repository.addToWatchlist(100, MediaType.MOVIE, 200)

        val ids = getWatchlistWatchedIds()!!
        // 已加到想看
        assertThat(ids.movieWatchlistTraktIds).contains(100)
        // 从已看移除（对称互斥）
        assertThat(ids.movieWatchedTraktIds).doesNotContain(100)
        assertThat(ids.movieWatchedTmdbIds).doesNotContain(200)
        // 写入流水
        coVerify(exactly = 1) { markActionRecordDao.insert(any()) }
    }

    @Test
    fun addToWatchlist_副操作失败_主操作仍成功() = runTest {
        coEvery { traktApiService.addToWatchlist(any()) } returns successSyncResponse()
        coEvery { traktApiService.removeFromHistory(any()) } returns errorResponse(500)

        val result = repository.addToWatchlist(100, MediaType.MOVIE)

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun batchAddToWatchlist_成功_调用副操作batchRemoveFromWatched() = runTest {
        coEvery { traktApiService.addToWatchlist(any()) } returns successSyncResponse()
        coEvery { traktApiService.removeFromHistory(any()) } returns successSyncResponse()

        val result = repository.batchAddToWatchlist(listOf(100, 101), listOf(200))

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { traktApiService.addToWatchlist(any()) }
        coVerify(exactly = 1) { traktApiService.removeFromHistory(any()) }
    }

    @Test
    fun addToWatchlist_API失败返回failure() = runTest {
        coEvery { traktApiService.addToWatchlist(any()) } returns errorResponse(400)

        val result = repository.addToWatchlist(100, MediaType.MOVIE)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun addToWatchlist_MOVIE_写流水字段来自TmdbEnrichment() = runTest {
        setWatchlistWatchedIds(emptyIds())
        coEvery { traktApiService.addToWatchlist(any()) } returns successSyncResponse()
        coEvery { tmdbRepository.enrichMovie(200, "", null) } returns movieEnrichment(
            chineseTitle = "盗梦空间",
            originalTitle = "Inception",
            posterUrl = "https://image.tmdb.org/t/p/w500/inception.jpg",
            year = 2010,
            imdbId = "tt1375666"
        )
        val recordSlot = slot<MarkActionRecordEntity>()
        coEvery { markActionRecordDao.insert(capture(recordSlot)) } returns Unit

        repository.addToWatchlist(100, MediaType.MOVIE, 200)

        val entity = recordSlot.captured
        assertThat(entity.traktId).isEqualTo(100)
        assertThat(entity.tmdbId).isEqualTo(200)
        assertThat(entity.mediaType).isEqualTo("movie")
        assertThat(entity.actionType).isEqualTo(MarkActionType.ADD_WATCHLIST.value)
        // 核心断言：字段来自 enrichment 而非空值
        assertThat(entity.title).isEqualTo("Inception")          // originalTitle
        assertThat(entity.displayTitle).isEqualTo("盗梦空间")      // chineseTitle
        assertThat(entity.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/inception.jpg")
        assertThat(entity.year).isEqualTo(2010)
        assertThat(entity.imdbId).isEqualTo("tt1375666")
    }

    @Test
    fun removeFromWatchlist_成功_更新缓存并写流水() = runTest {
        setWatchlistWatchedIds(
            TraktRepository.WatchlistWatchedIds(
                movieWatchlistTraktIds = setOf(100),
                movieWatchlistTmdbIds = setOf(200)
            )
        )
        coEvery { traktApiService.removeFromWatchlist(any()) } returns successSyncResponse()

        val result = repository.removeFromWatchlist(100, MediaType.MOVIE, 200)

        assertThat(result.isSuccess).isTrue()
        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.movieWatchlistTraktIds).doesNotContain(100)
        assertThat(ids.movieWatchlistTmdbIds).doesNotContain(200)
        coVerify(exactly = 1) { markActionRecordDao.insert(any()) }
    }

    @Test
    fun removeFromWatchlist_API失败返回failure() = runTest {
        coEvery { traktApiService.removeFromWatchlist(any()) } returns errorResponse(404)

        val result = repository.removeFromWatchlist(100, MediaType.MOVIE)

        assertThat(result.isFailure).isTrue()
    }

    // ==================== addRating ====================

    @Test
    fun addRating_SHOW类型request含shows() = runTest {
        val requestSlot = slot<RatingRequest>()
        coEvery { traktApiService.addRating(capture(requestSlot)) } returns successUnitResponse()

        repository.addRating(200, 9, MediaType.SHOW)

        assertThat(requestSlot.captured.shows).hasSize(1)
        assertThat(requestSlot.captured.shows!![0].rating).isEqualTo(9)
        assertThat(requestSlot.captured.movies).isNull()
    }

    // ==================== removeRating ====================

    @Test
    fun removeRating_API失败返回failure() = runTest {
        coEvery { traktApiService.removeRating(any()) } returns errorUnitResponse(404)

        val result = repository.removeRating(100, MediaType.MOVIE)

        assertThat(result.isFailure).isTrue()
    }

    // ==================== batchAddToWatchlist ====================

    @Test
    fun batchAddToWatchlist_成功使双ID缓存失效() = runTest {
        setWatchlistWatchedIds(emptyIds())
        coEvery { traktApiService.addToWatchlist(any()) } returns successSyncResponse()

        val result = repository.batchAddToWatchlist(listOf(1, 2), listOf(3, 4))

        assertThat(result.isSuccess).isTrue()
        assertThat(getWatchlistWatchedIds()).isNull()
    }

    @Test
    fun batchAddToWatchlist_只有电影_只有剧集() = runTest {
        setWatchlistWatchedIds(emptyIds())
        val requestSlot = slot<TraktSyncRequest>()
        coEvery { traktApiService.addToWatchlist(capture(requestSlot)) } returns successSyncResponse()

        repository.batchAddToWatchlist(listOf(1, 2), emptyList())

        assertThat(requestSlot.captured.movies).hasSize(2)
        assertThat(requestSlot.captured.shows).isNull()
    }

    @Test
    fun batchAddToWatchlist_API失败返回failure() = runTest {
        coEvery { traktApiService.addToWatchlist(any()) } returns errorResponse(500)

        val result = repository.batchAddToWatchlist(listOf(1), listOf(2))

        assertThat(result.isFailure).isTrue()
    }

    // ==================== batchRemoveFromWatchlist ====================

    @Test
    fun batchRemoveFromWatchlist_成功使双ID缓存失效() = runTest {
        setWatchlistWatchedIds(
            TraktRepository.WatchlistWatchedIds(
                movieWatchlistTraktIds = setOf(1, 2),
                showWatchlistTraktIds = setOf(3, 4)
            )
        )
        coEvery { traktApiService.removeFromWatchlist(any()) } returns successSyncResponse()

        repository.batchRemoveFromWatchlist(listOf(1), listOf(3))

        assertThat(getWatchlistWatchedIds()).isNull()
    }

    // ==================== batchMarkAsWatched ====================

    @Test
    fun batchMarkAsWatched_成功使双ID缓存失效() = runTest {
        setWatchlistWatchedIds(emptyIds())
        coEvery { traktApiService.addToHistory(any()) } returns successSyncResponse()

        val result = repository.batchMarkAsWatched(listOf(1), listOf(2))

        assertThat(result.isSuccess).isTrue()
        assertThat(getWatchlistWatchedIds()).isNull()
    }

    // ==================== batchMarkAsWatchedAt ====================

    @Test
    fun batchMarkAsWatchedAt_带watchedAt正确传递() = runTest {
        val requestSlot = slot<TraktSyncRequest>()
        coEvery { traktApiService.addToHistory(capture(requestSlot)) } returns successSyncResponse()

        val result = repository.batchMarkAsWatchedAt(
            movieItems = listOf(Pair(1, "2024-01-01T00:00:00Z")),
            showItems = listOf(Pair(2, "2024-06-15T12:30:00Z"))
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(requestSlot.captured.movies).hasSize(1)
        assertThat(requestSlot.captured.movies!![0].watched_at).isEqualTo("2024-01-01T00:00:00Z")
        assertThat(requestSlot.captured.shows).hasSize(1)
        assertThat(requestSlot.captured.shows!![0].watched_at).isEqualTo("2024-06-15T12:30:00Z")
    }

    @Test
    fun batchMarkAsWatchedAt_watchedAt为null时不传递时间() = runTest {
        val requestSlot = slot<TraktSyncRequest>()
        coEvery { traktApiService.addToHistory(capture(requestSlot)) } returns successSyncResponse()

        repository.batchMarkAsWatchedAt(
            movieItems = listOf(Pair(1, null)),
            showItems = emptyList()
        )

        assertThat(requestSlot.captured.movies).hasSize(1)
        assertThat(requestSlot.captured.movies!![0].watched_at).isNull()
    }

    // ==================== batchRemoveFromWatched ====================

    @Test
    fun batchRemoveFromWatched_成功使双ID缓存失效() = runTest {
        setWatchlistWatchedIds(
            TraktRepository.WatchlistWatchedIds(
                movieWatchedTraktIds = setOf(1, 2),
                showWatchedTraktIds = setOf(3, 4)
            )
        )
        coEvery { traktApiService.removeFromHistory(any()) } returns successSyncResponse()

        repository.batchRemoveFromWatched(listOf(1), listOf(3))

        assertThat(getWatchlistWatchedIds()).isNull()
    }

    // ==================== batchAddRatings ====================

    @Test
    fun batchAddRatings_成功() = runTest {
        val requestSlot = slot<RatingRequest>()
        coEvery { traktApiService.addRating(capture(requestSlot)) } returns successUnitResponse()

        val result = repository.batchAddRatings(
            movieRatings = listOf(Pair(1, 8), Pair(2, 9)),
            showRatings = listOf(Pair(3, 7))
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(requestSlot.captured.movies).hasSize(2)
        assertThat(requestSlot.captured.shows).hasSize(1)
        assertThat(requestSlot.captured.movies!![0].rating).isEqualTo(8)
    }

    @Test
    fun batchAddRatingsAt_带ratedAt正确传递() = runTest {
        val requestSlot = slot<RatingRequest>()
        coEvery { traktApiService.addRating(capture(requestSlot)) } returns successUnitResponse()

        repository.batchAddRatingsAt(
            movieRatings = listOf(Triple(1, 8, "2024-01-01T00:00:00Z")),
            showRatings = listOf(Triple(2, 9, null))
        )

        assertThat(requestSlot.captured.movies).hasSize(1)
        assertThat(requestSlot.captured.movies!![0].rating).isEqualTo(8)
        assertThat(requestSlot.captured.movies!![0].rated_at).isEqualTo("2024-01-01T00:00:00Z")
        assertThat(requestSlot.captured.shows).hasSize(1)
        assertThat(requestSlot.captured.shows!![0].rated_at).isNull()
    }

    // ==================== postComment ====================

    @Test
    fun postComment_MOVIE成功() = runTest {
        val comment = TraktComment(id = 1, comment = "好电影")
        coEvery { traktApiService.postComment(any()) } returns Response.success(comment)

        val result = repository.postComment(100, MediaType.MOVIE, "好电影")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.comment).isEqualTo("好电影")
    }

    @Test
    fun postComment_API失败返回failure() = runTest {
        coEvery { traktApiService.postComment(any()) } returns Response.error(400, "".toResponseBody(null))

        val result = repository.postComment(100, MediaType.MOVIE, "评论")

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun postComment_spoiler参数正确传递() = runTest {
        val requestSlot = slot<TraktCommentRequest>()
        coEvery { traktApiService.postComment(capture(requestSlot)) } returns Response.success(TraktComment())

        repository.postComment(100, MediaType.MOVIE, "剧透评论", spoiler = true)

        assertThat(requestSlot.captured.spoiler).isTrue()
    }

    // ==================== getComments ====================

    @Test
    fun getComments_API成功返回评论列表() = runTest {
        val comments = listOf(TraktComment(id = 1, comment = "评论1"))
        coEvery { traktApiService.getMovieComments(any(), any(), any()) } returns Response.success(comments)

        val result = repository.getComments(100, MediaType.MOVIE)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).hasSize(1)
    }

    @Test
    fun getComments_缓存命中不调API() = runTest {
        val comments = listOf(TraktComment(id = 1, comment = "评论1"))
        coEvery { traktApiService.getMovieComments(any(), any(), any()) } returns Response.success(comments)

        // 第一次调用走 API
        repository.getComments(100, MediaType.MOVIE)
        // 第二次调用应命中缓存
        val result = repository.getComments(100, MediaType.MOVIE)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { traktApiService.getMovieComments(any(), any(), any()) }
    }

    @Test
    fun getComments_SHOW类型调用getShowComments() = runTest {
        coEvery { traktApiService.getShowComments(any(), any(), any()) } returns Response.success(emptyList())

        repository.getComments(200, MediaType.SHOW)

        coVerify(exactly = 1) { traktApiService.getShowComments(any(), any(), any()) }
        coVerify(exactly = 0) { traktApiService.getMovieComments(any(), any(), any()) }
    }

    @Test
    fun getComments_API失败返回failure() = runTest {
        coEvery { traktApiService.getMovieComments(any(), any(), any()) } returns Response.error(404, "".toResponseBody(null))

        val result = repository.getComments(100, MediaType.MOVIE)

        assertThat(result.isFailure).isTrue()
    }
}
