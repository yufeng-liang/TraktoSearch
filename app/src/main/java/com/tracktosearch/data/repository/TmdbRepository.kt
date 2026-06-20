package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.tmdb.TmdbApiService
import com.tracktosearch.data.remote.tmdb.dto.*
import com.tracktosearch.data.util.TtlCache
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbRepository @Inject constructor(
    private val tmdbApiService: TmdbApiService
) {
    companion object {
        private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"
        private const val TTL_DETAIL = 30 * 60 * 1000L       // 详情 30 分钟
        private const val TTL_CREDITS = 60 * 60 * 1000L      // 演职员 1 小时
        private const val TTL_SEARCH = 60 * 60 * 1000L       // 搜索/ID转换 1 小时
        private const val TTL_PERSON = 60 * 60 * 1000L       // 人物信息 1 小时
        private const val TTL_REVIEWS = 10 * 60 * 1000L      // 评论 10 分钟
        private const val TTL_LISTS = 60 * 60 * 1000L        // 列表类 1 小时
        private const val PERSON_CREDITS_PAGE_SIZE = 20      // 人物作品每页数量
    }

    /** 人物作品分页结果 */
    data class PersonCreditsPage<T>(
        val items: List<T>,
        val hasMore: Boolean
    )

    // 带 TTL 的缓存
    private val movieDetailCache = TtlCache<TmdbMovieDetail>(TTL_DETAIL)
    private val tvDetailCache = TtlCache<TmdbTvDetail>(TTL_DETAIL)
    private val movieTitleCache = TtlCache<String>(TTL_DETAIL)
    private val tvTitleCache = TtlCache<String>(TTL_DETAIL)
    private val creditsCache = TtlCache<TmdbCreditsResponse>(TTL_CREDITS)
    private val searchMovieCache = TtlCache<TmdbSearchResult>(TTL_SEARCH)
    private val personDetailCache = TtlCache<TmdbPerson>(TTL_PERSON)
    // 人物作品缓存：TMDB 一次性返回全部作品，这里缓存按 vote_average 降序排列后的完整列表，按 personId 分页切片
    private val personMovieCreditsCache = TtlCache<List<TmdbPersonMovieCredit>>(TTL_PERSON)
    private val personTvCreditsCache = TtlCache<List<TmdbPersonTvCredit>>(TTL_PERSON)
    private val reviewsCache = TtlCache<TmdbReviewsResponse>(TTL_REVIEWS)
    private val popularMoviesCache = TtlCache<List<TmdbSearchResult>>(TTL_LISTS)
    private val upcomingMoviesCache = TtlCache<List<TmdbSearchResult>>(TTL_LISTS)
    private val topRatedMoviesCache = TtlCache<List<TmdbSearchResult>>(TTL_LISTS)

    data class MovieEnrichment(
        val posterUrl: String?,
        val chineseTitle: String,
        val originalTitle: String = "",
        val overview: String,
        val genres: String,
        val year: Int?,
        val rating: Double,
        val runtime: Int? = null,
        val releaseDate: String = ""
    )

    data class TvEnrichment(
        val posterUrl: String?,
        val chineseTitle: String,
        val originalTitle: String = "",
        val overview: String,
        val genres: String,
        val year: Int?,
        val rating: Double,
        val episodeRunTime: Int? = null,
        val releaseDate: String = ""
    )

    suspend fun enrichMovie(tmdbId: Int, originalTitle: String, year: Int?): MovieEnrichment {
        val key = tmdbId.toString()
        val cached = movieDetailCache.get(key)
        if (cached != null) {
            val chineseTitle = movieTitleCache.getOrPut(key) {
                resolveMovieChineseTitle(tmdbId, originalTitle, cached)
            }
            return MovieEnrichment(
                posterUrl = cached.poster_path?.let { "$IMAGE_BASE_URL$it" },
                chineseTitle = chineseTitle,
                originalTitle = cached.original_title,
                overview = cached.overview ?: "",
                genres = cached.genres?.joinToString(" · ") { it.name } ?: "",
                year = cached.release_date?.take(4)?.toIntOrNull() ?: year,
                rating = cached.vote_average,
                runtime = cached.runtime,
                releaseDate = cached.release_date ?: ""
            )
        }

        return try {
            val response = tmdbApiService.getMovieDetail(tmdbId)
            if (response.isSuccessful) {
                val detail = response.body() ?: return fallbackMovie(originalTitle, year)
                movieDetailCache.put(key, detail)
                val chineseTitle = resolveMovieChineseTitle(tmdbId, originalTitle, detail)
                movieTitleCache.put(key, chineseTitle)
                MovieEnrichment(
                    posterUrl = detail.poster_path?.let { "$IMAGE_BASE_URL$it" },
                    chineseTitle = chineseTitle,
                    originalTitle = detail.original_title,
                    overview = detail.overview ?: "",
                    genres = detail.genres?.joinToString(" · ") { it.name } ?: "",
                    year = detail.release_date?.take(4)?.toIntOrNull() ?: year,
                    rating = detail.vote_average,
                    runtime = detail.runtime,
                    releaseDate = detail.release_date ?: ""
                )
            } else {
                fallbackMovie(originalTitle, year)
            }
        } catch (_: Exception) {
            fallbackMovie(originalTitle, year)
        }
    }

    suspend fun enrichTv(tmdbId: Int, originalName: String, year: Int?): TvEnrichment {
        val key = tmdbId.toString()
        val cached = tvDetailCache.get(key)
        if (cached != null) {
            val chineseTitle = tvTitleCache.getOrPut(key) {
                resolveTvChineseTitle(tmdbId, originalName, cached)
            }
            return TvEnrichment(
                posterUrl = cached.poster_path?.let { "$IMAGE_BASE_URL$it" },
                chineseTitle = chineseTitle,
                originalTitle = cached.original_name,
                overview = cached.overview ?: "",
                genres = cached.genres?.joinToString(" · ") { it.name } ?: "",
                year = cached.first_air_date?.take(4)?.toIntOrNull() ?: year,
                rating = cached.vote_average,
                episodeRunTime = cached.episode_run_time?.firstOrNull(),
                releaseDate = cached.first_air_date ?: ""
            )
        }

        return try {
            val response = tmdbApiService.getTvDetail(tmdbId)
            if (response.isSuccessful) {
                val detail = response.body() ?: return fallbackTv(originalName, year)
                tvDetailCache.put(key, detail)
                val chineseTitle = resolveTvChineseTitle(tmdbId, originalName, detail)
                tvTitleCache.put(key, chineseTitle)
                TvEnrichment(
                    posterUrl = detail.poster_path?.let { "$IMAGE_BASE_URL$it" },
                    chineseTitle = chineseTitle,
                    originalTitle = detail.original_name,
                    overview = detail.overview ?: "",
                    genres = detail.genres?.joinToString(" · ") { it.name } ?: "",
                    year = detail.first_air_date?.take(4)?.toIntOrNull() ?: year,
                    rating = detail.vote_average,
                    episodeRunTime = detail.episode_run_time?.firstOrNull(),
                    releaseDate = detail.first_air_date ?: ""
                )
            } else {
                fallbackTv(originalName, year)
            }
        } catch (_: Exception) {
            fallbackTv(originalName, year)
        }
    }

    private suspend fun resolveMovieChineseTitle(tmdbId: Int, originalTitle: String, detail: TmdbMovieDetail): String {
        if (detail.title != detail.original_title && detail.title.isNotEmpty()) {
            return detail.title
        }
        return try {
            val altResponse = tmdbApiService.getMovieAlternativeTitles(tmdbId)
            if (altResponse.isSuccessful) {
                val cnTitle = altResponse.body()?.titles?.firstOrNull {
                    it.iso_3166_1 == "CN" && it.title.isNotEmpty()
                }?.title
                cnTitle ?: originalTitle
            } else originalTitle
        } catch (_: Exception) {
            originalTitle
        }
    }

    private suspend fun resolveTvChineseTitle(tmdbId: Int, originalName: String, detail: TmdbTvDetail): String {
        if (detail.name != detail.original_name && detail.name.isNotEmpty()) {
            return detail.name
        }
        return try {
            val altResponse = tmdbApiService.getTvAlternativeTitles(tmdbId)
            if (altResponse.isSuccessful) {
                val cnTitle = altResponse.body()?.titles?.firstOrNull {
                    it.iso_3166_1 == "CN" && it.title.isNotEmpty()
                }?.title
                cnTitle ?: originalName
            } else originalName
        } catch (_: Exception) {
            originalName
        }
    }

    private fun fallbackMovie(originalTitle: String, year: Int?) = MovieEnrichment(
        posterUrl = null,
        chineseTitle = originalTitle,
        overview = "",
        genres = "",
        year = year,
        rating = 0.0
    )

    private fun fallbackTv(originalName: String, year: Int?) = TvEnrichment(
        posterUrl = null,
        chineseTitle = originalName,
        overview = "",
        genres = "",
        year = year,
        rating = 0.0
    )

    suspend fun getCredits(tmdbId: Int, mediaType: MediaType): TmdbCreditsResponse? {
        val key = tmdbId.toString()
        creditsCache.get(key)?.let { return it }
        return try {
            val response = when (mediaType) {
                MediaType.MOVIE -> tmdbApiService.getMovieCredits(tmdbId)
                MediaType.SHOW -> tmdbApiService.getCredits(tmdbId)
            }
            if (response.isSuccessful) {
                response.body()?.also { creditsCache.put(key, it) }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getReviews(tmdbId: Int, mediaType: MediaType, page: Int = 1): TmdbReviewsResponse? {
        val key = "${tmdbId}_${mediaType.name}_$page"
        reviewsCache.get(key)?.let { return it }
        return try {
            val response = when (mediaType) {
                MediaType.MOVIE -> tmdbApiService.getMovieReviews(tmdbId, page)
                MediaType.SHOW -> tmdbApiService.getTvReviews(tmdbId, page)
            }
            if (response.isSuccessful) {
                response.body()?.also { reviewsCache.put(key, it) }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getPersonDetail(personId: Int): TmdbPerson? {
        val key = personId.toString()
        personDetailCache.get(key)?.let { return it }
        return try {
            val response = tmdbApiService.getPersonDetail(personId)
            if (response.isSuccessful) {
                response.body()?.also { personDetailCache.put(key, it) }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getPersonMovieCredits(personId: Int, page: Int = 1): PersonCreditsPage<TmdbPersonMovieCredit> {
        val key = personId.toString()
        val full = try {
            personMovieCreditsCache.get(key) ?: run {
                val response = tmdbApiService.getPersonMovieCredits(personId, page = 1)
                val result = response.body()?.cast?.sortedByDescending { it.vote_average } ?: emptyList()
                personMovieCreditsCache.put(key, result)
                result
            }
        } catch (_: Exception) {
            emptyList()
        }
        val start = (page - 1) * PERSON_CREDITS_PAGE_SIZE
        val slice = full.drop(start).take(PERSON_CREDITS_PAGE_SIZE)
        val hasMore = start + PERSON_CREDITS_PAGE_SIZE < full.size
        return PersonCreditsPage(slice, hasMore)
    }

    suspend fun getPersonTvCredits(personId: Int, page: Int = 1): PersonCreditsPage<TmdbPersonTvCredit> {
        val key = personId.toString()
        val full = try {
            personTvCreditsCache.get(key) ?: run {
                val response = tmdbApiService.getPersonTvCredits(personId, page = 1)
                val result = response.body()?.cast?.sortedByDescending { it.vote_average } ?: emptyList()
                personTvCreditsCache.put(key, result)
                result
            }
        } catch (_: Exception) {
            emptyList()
        }
        val start = (page - 1) * PERSON_CREDITS_PAGE_SIZE
        val slice = full.drop(start).take(PERSON_CREDITS_PAGE_SIZE)
        val hasMore = start + PERSON_CREDITS_PAGE_SIZE < full.size
        return PersonCreditsPage(slice, hasMore)
    }

    fun buildProfileUrl(profilePath: String?): String? {
        return profilePath?.let { "$IMAGE_BASE_URL$it" }
    }

    // 通过标题搜索电影，返回第一个匹配结果
    suspend fun searchMovie(query: String): TmdbSearchResult? {
        val key = query.trim()
        searchMovieCache.get(key)?.let { return it }
        return try {
            val response = tmdbApiService.searchMovie(query = query)
            if (response.isSuccessful) {
                response.body()?.results?.firstOrNull()?.also {
                    searchMovieCache.put(key, it)
                }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /** 热门电影 */
    suspend fun getPopularMovies(): List<TmdbSearchResult> {
        popularMoviesCache.get("default")?.let { return it }
        return try {
            val response = tmdbApiService.getPopularMovies()
            if (response.isSuccessful) {
                val results = response.body()?.results ?: emptyList()
                popularMoviesCache.put("default", results)
                results
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 热门电影（分页） */
    suspend fun getPopularMovies(page: Int): List<TmdbSearchResult> {
        return try {
            val response = tmdbApiService.getPopularMovies(page = page)
            if (response.isSuccessful) {
                response.body()?.results ?: emptyList()
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 即将上映 */
    suspend fun getUpcomingMovies(): List<TmdbSearchResult> {
        upcomingMoviesCache.get("default")?.let { return it }
        return try {
            val response = tmdbApiService.getUpcomingMovies()
            if (response.isSuccessful) {
                val results = response.body()?.results ?: emptyList()
                upcomingMoviesCache.put("default", results)
                results
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 即将上映（分页） */
    suspend fun getUpcomingMovies(page: Int): List<TmdbSearchResult> {
        return try {
            val response = tmdbApiService.getUpcomingMovies(page = page)
            if (response.isSuccessful) {
                response.body()?.results ?: emptyList()
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 高分电影（未登录时的推荐降级） */
    suspend fun getTopRatedMovies(): List<TmdbSearchResult> {
        topRatedMoviesCache.get("default")?.let { return it }
        return try {
            val response = tmdbApiService.getTopRatedMovies()
            if (response.isSuccessful) {
                val results = response.body()?.results ?: emptyList()
                topRatedMoviesCache.put("default", results)
                results
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 高分电影（分页） */
    suspend fun getTopRatedMovies(page: Int): List<TmdbSearchResult> {
        return try {
            val response = tmdbApiService.getTopRatedMovies(page = page)
            if (response.isSuccessful) {
                response.body()?.results ?: emptyList()
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 获取电影详情（含海报路径） */
    suspend fun getMovieDetail(movieId: Int): TmdbMovieDetail? {
        return try {
            val response = tmdbApiService.getMovieDetail(movieId)
            if (response.isSuccessful) response.body() else null
        } catch (_: Exception) {
            null
        }
    }
}
