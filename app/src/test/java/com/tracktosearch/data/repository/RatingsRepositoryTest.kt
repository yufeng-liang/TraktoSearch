package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.config.RemoteConfigProvider
import com.tracktosearch.data.remote.omdb.OmdbApiService
import com.tracktosearch.data.remote.omdb.dto.OmdbRating
import com.tracktosearch.data.remote.omdb.dto.OmdbResponse
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * RatingsRepository 单元测试。
 *
 * 被测方法为 [RatingsRepository.fetchRatingsStream]，返回 Flow<MultiRatings>：
 * - 先 emit 仅含 tmdb/trakt 的占位结果
 * - 缓存命中 → emit 缓存值，不再调 OMDB API
 * - 缓存未命中 → 调 OMDB API，缓存后 emit 完整结果
 * - OMDB 抛异常或 Response="False" → 降级为仅 tmdb/trakt 的结果
 * - imdbId 为空 → 只 emit 占位，不调 API
 * - 内部 LinkedHashMap(accessOrder=true) 容量 50，LRU 淘汰
 */
class RatingsRepositoryTest {

    private val omdbApi = mockk<OmdbApiService>(relaxed = true)
    private val remoteConfig = mockk<RemoteConfigProvider>(relaxed = true)
    private val repo = RatingsRepository(omdbApi, remoteConfig)

    private fun omdbResponse(
        response: String = "True",
        imdbRating: String = "8.5",
        rottenTomatoes: String = "90%",
        metacritic: String = "77/100",
        director: String = "Christopher Nolan",
        actors: String = "Actor A, Actor B"
    ): OmdbResponse {
        val ratings = buildList {
            if (rottenTomatoes.isNotEmpty()) add(OmdbRating("Rotten Tomatoes", rottenTomatoes))
            if (metacritic.isNotEmpty()) add(OmdbRating("Metacritic", metacritic))
        }
        return OmdbResponse(
            Title = "Sample",
            Year = "2024",
            Rated = "PG-13",
            imdbRating = imdbRating,
            imdbVotes = "1000",
            Ratings = ratings,
            Director = director,
            Actors = actors,
            Writer = "Writer",
            Response = response
        )
    }

    @Test
    fun fetchRatingsStream_firstEmit_hasOnlyTmdbAndTrakt() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns omdbResponse()

        val results = repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        assertThat(results).hasSize(2)
        // 第一次 emit：只有 tmdb/trakt，其余字段为默认值
        val first = results[0]
        assertThat(first.tmdbRating).isEqualTo(7.5)
        assertThat(first.traktRating).isEqualTo(8.0)
        assertThat(first.imdbRating).isEmpty()
        assertThat(first.rottenTomatoes).isEmpty()
        assertThat(first.metacritic).isEmpty()
        assertThat(first.director).isEmpty()
        assertThat(first.actors).isEmpty()
    }

    @Test
    fun fetchRatingsStream_cacheMiss_callsApiAndCaches() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns omdbResponse()

        val results = repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        assertThat(results).hasSize(2)
        // 第二次 emit：完整结果
        val second = results[1]
        assertThat(second.tmdbRating).isEqualTo(7.5)
        assertThat(second.traktRating).isEqualTo(8.0)
        assertThat(second.imdbRating).isEqualTo("8.5")
        assertThat(second.rottenTomatoes).isEqualTo("90%")
        // Metacritic "77/100" 被归一化为 "77%"
        assertThat(second.metacritic).isEqualTo("77%")
        assertThat(second.director).isEqualTo("Christopher Nolan")
        assertThat(second.actors).isEqualTo("Actor A, Actor B")
        coVerify(exactly = 1) { omdbApi.getByImdbId(any(), any(), any()) }
    }

    @Test
    fun fetchRatingsStream_cacheHit_doesNotCallApi() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns omdbResponse()
        // 第一次收集：填充缓存
        repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        // 第二次收集：应命中缓存，不再调 API
        val results = repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        // 缓存命中时：先 emit 占位，再 emit 缓存值
        assertThat(results).hasSize(2)
        assertThat(results[1].imdbRating).isEqualTo("8.5")
        assertThat(results[1].director).isEqualTo("Christopher Nolan")
        coVerify(exactly = 1) { omdbApi.getByImdbId(any(), any(), any()) }
    }

    @Test
    fun fetchRatingsStream_omdbThrowsException_fallsBackToTmdbTrakt() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } throws
            java.io.IOException("network error")

        val results = repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        // 降级：emit 占位 + 仅 tmdb/trakt 的结果，OMDB 字段为空
        assertThat(results).hasSize(2)
        val second = results[1]
        assertThat(second.tmdbRating).isEqualTo(7.5)
        assertThat(second.traktRating).isEqualTo(8.0)
        assertThat(second.imdbRating).isEmpty()
        assertThat(second.rottenTomatoes).isEmpty()
        assertThat(second.metacritic).isEmpty()
        assertThat(second.director).isEmpty()
        assertThat(second.actors).isEmpty()
        coVerify(exactly = 1) { omdbApi.getByImdbId(any(), any(), any()) }
    }

    @Test
    fun fetchRatingsStream_omdbReturnsFalseResponse_fallsBackToTmdbTrakt() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        // Response="False" 视为失败，走降级
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns
            omdbResponse(response = "False", imdbRating = "8.5")

        val results = repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        assertThat(results).hasSize(2)
        // Response=False 时 imdbRating 等字段应被置空（因 omdbRatings 为 null）
        assertThat(results[1].imdbRating).isEmpty()
        assertThat(results[1].director).isEmpty()
        assertThat(results[1].actors).isEmpty()
        // tmdb/trakt 仍保留
        assertThat(results[1].tmdbRating).isEqualTo(7.5)
        assertThat(results[1].traktRating).isEqualTo(8.0)
    }

    @Test
    fun fetchRatingsStream_emptyImdbId_doesNotCallApi() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } throws
            AssertionError("空 imdbId 不应调用 API")

        val results = repo.fetchRatingsStream("", 7.5, 8.0).toList()

        // 空 imdbId：只 emit 占位，return@flow，不调 API
        assertThat(results).hasSize(1)
        assertThat(results[0].tmdbRating).isEqualTo(7.5)
        assertThat(results[0].traktRating).isEqualTo(8.0)
        assertThat(results[0].imdbRating).isEmpty()
        coVerify(exactly = 0) { omdbApi.getByImdbId(any(), any(), any()) }
    }

    @Test
    fun fetchRatingsStream_blankImdbId_doesNotCallApi() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } throws
            AssertionError("空白 imdbId 不应调用 API")

        val results = repo.fetchRatingsStream("   ", 7.5, 8.0).toList()

        // isBlank() 覆盖空白字符
        assertThat(results).hasSize(1)
        coVerify(exactly = 0) { omdbApi.getByImdbId(any(), any(), any()) }
    }

    @Test
    fun fetchRatingsStream_multipleCollects_deduplicatesByCache() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns omdbResponse()

        // 同一 imdbId 收集 3 次，第 2、3 次命中缓存
        repeat(3) {
            repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()
        }

        // 只应调用 1 次 API
        coVerify(exactly = 1) { omdbApi.getByImdbId(any(), any(), any()) }
    }

    @Test
    fun fetchRatingsStream_differentImdbIds_eachCallsApiOnce() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns omdbResponse()

        repo.fetchRatingsStream("tt0000001", 1.0, 2.0).toList()
        repo.fetchRatingsStream("tt0000002", 3.0, 4.0).toList()
        // 第二次请求 tt0000001 命中缓存
        repo.fetchRatingsStream("tt0000001", 1.0, 2.0).toList()

        // 两个不同 imdbId 各调一次
        coVerify(exactly = 1) { omdbApi.getByImdbId(any(), "tt0000001", any()) }
        coVerify(exactly = 1) { omdbApi.getByImdbId(any(), "tt0000002", any()) }
    }

    @Test
    fun fetchRatingsStream_lruCacheEvictsOldestWhenFull() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns omdbResponse()

        // cache 容量 50，插入 0..50 共 51 个不同 imdbId，tt0 会被淘汰
        for (i in 0..50) {
            repo.fetchRatingsStream("tt%07d".format(i), 0.0, 0.0).toList()
        }

        // 清除调用记录，保留 stub（answers=false 默认）
        clearMocks(omdbApi)
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns omdbResponse()

        // 再次请求 tt0000000：因被淘汰应重新调 API
        val results = repo.fetchRatingsStream("tt0000000", 1.0, 2.0).toList()

        assertThat(results).hasSize(2)
        assertThat(results[1].imdbRating).isEqualTo("8.5")
        coVerify(exactly = 1) { omdbApi.getByImdbId(any(), "tt0000000", any()) }
    }

    @Test
    fun fetchRatingsStream_lruCacheKeepsRecentEntries() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns omdbResponse()

        // 插入 0..50 共 51 个，tt0 被淘汰，但 tt1..tt50 应保留
        for (i in 0..50) {
            repo.fetchRatingsStream("tt%07d".format(i), 0.0, 0.0).toList()
        }

        clearMocks(omdbApi)
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns omdbResponse()

        // tt0000001 应仍在缓存中（不调 API）
        repo.fetchRatingsStream("tt0000001", 0.0, 0.0).toList()
        // tt0000050 也应仍在缓存中
        repo.fetchRatingsStream("tt0000050", 0.0, 0.0).toList()

        coVerify(exactly = 0) { omdbApi.getByImdbId(any(), "tt0000001", any()) }
        coVerify(exactly = 0) { omdbApi.getByImdbId(any(), "tt0000050", any()) }
    }

    @Test
    fun fetchRatingsStream_naRatingsFilteredToEmpty() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns omdbResponse(
            imdbRating = "N/A",
            director = "N/A",
            actors = "N/A",
            rottenTomatoes = "",
            metacritic = ""
        )

        val results = repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        val second = results[1]
        assertThat(second.imdbRating).isEmpty()
        assertThat(second.director).isEmpty()
        assertThat(second.actors).isEmpty()
        assertThat(second.rottenTomatoes).isEmpty()
        assertThat(second.metacritic).isEmpty()
    }

    @Test
    fun fetchRatingsStream_metacriticNonStandardFormat_keptAsIs() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        // 非 "x/100" 格式不归一化
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns
            omdbResponse(metacritic = "80/100", rottenTomatoes = "")

        val results = repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        assertThat(results[1].metacritic).isEqualTo("80%")
    }

    @Test
    fun fetchRatingsStream_remoteConfigProvidesApiKey() = runTest {
        every { remoteConfig.get("omdb.apiKey", any()) } returns "cfg_key_123"
        coEvery { omdbApi.getByImdbId("cfg_key_123", any(), any()) } returns omdbResponse()

        repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        // 验证 OMDB apiKey 来自 remoteConfig
        coVerify { omdbApi.getByImdbId("cfg_key_123", "tt0111169", any()) }
    }

    @Test
    fun fetchRatingsStream_cachedEntryReusesTmdbTraktFromFirstCall() = runTest {
        every { remoteConfig.get(any(), any()) } returns "test_key"
        coEvery { omdbApi.getByImdbId(any(), any(), any()) } returns omdbResponse()

        // 第一次用 tmdb=7.5, trakt=8.0 填充缓存
        repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()
        clearMocks(omdbApi)

        // 第二次用不同的 tmdb/trakt 值收集，但缓存命中
        val results = repo.fetchRatingsStream("tt0111169", 9.9, 6.6).toList()

        // 第一个 emit 反映新的 tmdb/trakt（占位）
        assertThat(results[0].tmdbRating).isEqualTo(9.9)
        assertThat(results[0].traktRating).isEqualTo(6.6)
        // 第二个 emit 来自缓存，保留原始 tmdb/trakt（7.5/8.0）
        assertThat(results[1].tmdbRating).isEqualTo(7.5)
        assertThat(results[1].traktRating).isEqualTo(8.0)
        assertThat(results[1].imdbRating).isEqualTo("8.5")
        coVerify(exactly = 0) { omdbApi.getByImdbId(any(), any(), any()) }
    }
}
