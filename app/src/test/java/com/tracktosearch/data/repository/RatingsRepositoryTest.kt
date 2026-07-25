package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.omdb.OmdbApiService
import com.tracktosearch.data.remote.omdb.dto.OmdbRating
import com.tracktosearch.data.remote.omdb.dto.OmdbResponse
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** RatingsRepository 测试：验证 OMDb 代理响应映射和失败降级。 */
class RatingsRepositoryTest {

    private val omdbApi = mockk<OmdbApiService>(relaxed = true)
    private val repo = RatingsRepository(omdbApi)

    private fun omdbResponse(
        response: String = "True",
        imdbRating: String = "8.5",
        rottenTomatoes: String = "90%",
        metacritic: String = "77/100",
        director: String = "Christopher Nolan",
        actors: String = "Actor A, Actor B",
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
            Response = response,
        )
    }

    @Test
    fun fetchRatingsStream_mapsOmdbRatingsAndCredits() = runTest {
        coEvery { omdbApi.getByImdbId("tt0111169", "short") } returns omdbResponse()

        val results = repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        assertThat(results).hasSize(2)
        assertThat(results[0]).isEqualTo(MultiRatings(tmdbRating = 7.5, traktRating = 8.0))
        assertThat(results[1].tmdbRating).isEqualTo(7.5)
        assertThat(results[1].traktRating).isEqualTo(8.0)
        assertThat(results[1].imdbRating).isEqualTo("8.5")
        assertThat(results[1].rottenTomatoes).isEqualTo("90%")
        assertThat(results[1].metacritic).isEqualTo("77%")
        assertThat(results[1].director).isEqualTo("Christopher Nolan")
        assertThat(results[1].actors).isEqualTo("Actor A, Actor B")
    }

    @Test
    fun fetchRatingsStream_cacheHit_doesNotCallApiAgain() = runTest {
        coEvery { omdbApi.getByImdbId(any(), any()) } returns omdbResponse()

        repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()
        val results = repo.fetchRatingsStream("tt0111169", 9.9, 6.6).toList()

        assertThat(results).hasSize(2)
        assertThat(results[0].tmdbRating).isEqualTo(9.9)
        assertThat(results[0].traktRating).isEqualTo(6.6)
        assertThat(results[1].tmdbRating).isEqualTo(7.5)
        assertThat(results[1].traktRating).isEqualTo(8.0)
        assertThat(results[1].imdbRating).isEqualTo("8.5")
        coVerify(exactly = 1) { omdbApi.getByImdbId("tt0111169", "short") }
    }

    @Test
    fun fetchRatingsStream_omdbFailure_fallsBackToTmdbAndTrakt() = runTest {
        coEvery { omdbApi.getByImdbId(any(), any()) } throws java.io.IOException("network error")

        val results = repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        assertThat(results).hasSize(2)
        assertThat(results[1]).isEqualTo(MultiRatings(tmdbRating = 7.5, traktRating = 8.0))
        coVerify(exactly = 1) { omdbApi.getByImdbId("tt0111169", "short") }
    }

    @Test
    fun fetchRatingsStream_falseResponse_fallsBackToTmdbAndTrakt() = runTest {
        coEvery { omdbApi.getByImdbId(any(), any()) } returns
            omdbResponse(response = "False", imdbRating = "8.5")

        val results = repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()

        assertThat(results).hasSize(2)
        assertThat(results[1]).isEqualTo(MultiRatings(tmdbRating = 7.5, traktRating = 8.0))
    }

    @Test
    fun fetchRatingsStream_blankImdbId_emitsOnlyExistingRatingsWithoutApiCall() = runTest {
        val results = repo.fetchRatingsStream("   ", 7.5, 8.0).toList()

        assertThat(results).containsExactly(MultiRatings(tmdbRating = 7.5, traktRating = 8.0))
        coVerify(exactly = 0) { omdbApi.getByImdbId(any(), any()) }
    }

    @Test
    fun fetchRatingsStream_filtersNaValuesAndNormalizesMetacritic() = runTest {
        coEvery { omdbApi.getByImdbId(any(), any()) } returns omdbResponse(
            imdbRating = "N/A",
            director = "N/A",
            actors = "N/A",
            rottenTomatoes = "",
            metacritic = "80/100",
        )

        val result = repo.fetchRatingsStream("tt0111169", 7.5, 8.0).toList()[1]

        assertThat(result.imdbRating).isEmpty()
        assertThat(result.director).isEmpty()
        assertThat(result.actors).isEmpty()
        assertThat(result.rottenTomatoes).isEmpty()
        assertThat(result.metacritic).isEqualTo("80%")
    }

    @Test
    fun fetchRatingsStream_lruCacheEvictsOldestEntry() = runTest {
        coEvery { omdbApi.getByImdbId(any(), any()) } returns omdbResponse()

        for (i in 0..50) {
            repo.fetchRatingsStream("tt%07d".format(i), 0.0, 0.0).toList()
        }
        clearMocks(omdbApi)
        coEvery { omdbApi.getByImdbId(any(), any()) } returns omdbResponse()

        repo.fetchRatingsStream("tt0000000", 1.0, 2.0).toList()

        coVerify(exactly = 1) { omdbApi.getByImdbId("tt0000000", "short") }
    }
}
