package com.tracktosearch.data.repository

import com.tracktosearch.BuildConfig
import com.tracktosearch.data.remote.config.RemoteConfigProvider
import com.tracktosearch.data.remote.omdb.OmdbApiService
import kotlinx.coroutines.CancellationException
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
    private val omdbApiService: OmdbApiService,
    private val remoteConfig: RemoteConfigProvider
) {
    private val cache = object : LinkedHashMap<String, MultiRatings>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MultiRatings>): Boolean {
            return size > 50
        }
    }

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
        synchronized(cache) { cache[imdbId] }?.let {
            emit(it)
            return@flow
        }

        // 第二步：OMDb 一返回就 emit
        val omdbRatings = try {
            // OMDB apiKey 从云端配置取(支持热更新),无值时回退 BuildConfig
            val omdbApiKey = remoteConfig.get("omdb.apiKey", BuildConfig.OMDB_API_KEY)
            val response = omdbApiService.getByImdbId(
                apiKey = omdbApiKey,
                imdbId = imdbId
            )
            if (response.Response == "True") response else null
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

        val result = MultiRatings(
            tmdbRating = tmdbRating,
            traktRating = traktRating,
            imdbRating = omdbRatings?.imdbRating?.takeIf { it != "N/A" && it.isNotEmpty() } ?: "",
            rottenTomatoes = omdbRatings?.Ratings?.find { it.Source == "Rotten Tomatoes" }?.Value?.takeIf { it.isNotEmpty() } ?: "",
            // OMDB Metacritic 格式为 "77/100"，统一转为 "77%" 以缩短显示宽度
            metacritic = run {
                val mtcRaw = omdbRatings?.Ratings?.find { it.Source == "Metacritic" }?.Value?.takeIf { it.isNotEmpty() } ?: ""
                if (mtcRaw.matches(Regex("""\d+/100"""))) {
                    mtcRaw.replace(Regex("""(\d+)/100"""), "$1%")
                } else mtcRaw
            },
            director = omdbRatings?.Director?.takeIf { it != "N/A" && it.isNotEmpty() } ?: "",
            actors = omdbRatings?.Actors?.takeIf { it != "N/A" && it.isNotEmpty() } ?: ""
        )
        synchronized(cache) { cache[imdbId] = result }
        emit(result)
    }.flowOn(Dispatchers.IO)
}
