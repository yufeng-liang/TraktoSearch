package com.tracktosearch.data.repository

import com.tracktosearch.BuildConfig
import com.tracktosearch.data.remote.omdb.OmdbApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

data class MultiRatings(
    val tmdbRating: Double = 0.0,
    val traktRating: Double = 0.0,
    val imdbRating: String = "",
    val rottenTomatoes: String = "",
    val metacritic: String = "",
    val director: String = "",
    val actors: String = ""
)

@Singleton
class RatingsRepository @Inject constructor(
    private val omdbApiService: OmdbApiService
) {
    private val cache = mutableMapOf<String, MultiRatings>()

    /**
     * 流式获取评分：
     * - 先立即 emit TMDB/Trakt（已有数据）
     * - OMDb 一返回就 emit 完整结果
     */
    fun fetchRatingsStream(
        imdbId: String,
        tmdbRating: Double,
        traktRating: Double
    ): Flow<MultiRatings> = flow {
        // 第一步：先 emit TMDB/Trakt（用户立刻看到）
        emit(MultiRatings(tmdbRating = tmdbRating, traktRating = traktRating))

        if (imdbId.isBlank()) return@flow
        cache[imdbId]?.let {
            emit(it)
            return@flow
        }

        // 第二步：OMDb 一返回就 emit
        val omdbRatings = try {
            val response = omdbApiService.getByImdbId(
                apiKey = BuildConfig.OMDB_API_KEY,
                imdbId = imdbId
            )
            if (response.Response == "True") response else null
        } catch (_: Exception) { null }

        val result = MultiRatings(
            tmdbRating = tmdbRating,
            traktRating = traktRating,
            imdbRating = omdbRatings?.imdbRating?.takeIf { it != "N/A" && it.isNotEmpty() } ?: "",
            rottenTomatoes = omdbRatings?.Ratings?.find { it.Source == "Rotten Tomatoes" }?.Value?.takeIf { it.isNotEmpty() } ?: "",
            metacritic = omdbRatings?.Ratings?.find { it.Source == "Metacritic" }?.Value?.takeIf { it.isNotEmpty() } ?: "",
            director = omdbRatings?.Director?.takeIf { it != "N/A" && it.isNotEmpty() } ?: "",
            actors = omdbRatings?.Actors?.takeIf { it != "N/A" && it.isNotEmpty() } ?: ""
        )
        cache[imdbId] = result
        emit(result)
    }.flowOn(Dispatchers.IO)

    fun clearCache() {
        cache.clear()
    }
}
