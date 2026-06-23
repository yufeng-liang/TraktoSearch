package com.tracktosearch.data.repository

import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.remote.tmdb.TmdbApiService
import com.tracktosearch.data.remote.tmdb.dto.*
import com.tracktosearch.data.util.TtlCache
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbRepository @Inject constructor(
    private val tmdbApiService: TmdbApiService,
    private val languageStorage: LanguageStorage
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

    /** 根据当前语言设置返回 TMDB API 的 language 参数 */
    private suspend fun getTmdbLanguage(): String {
        val lang = languageStorage.language.first()
        return when (lang) {
            LanguageStorage.LANGUAGE_CHINESE -> "zh-CN"
            LanguageStorage.LANGUAGE_ENGLISH -> "en-US"
            LanguageStorage.LANGUAGE_JAPANESE -> "ja-JP"
            LanguageStorage.LANGUAGE_KOREAN -> "ko-KR"
            else -> "zh-CN" // 默认中文
        }
    }

    /** 根据当前语言设置返回 TMDB alternative_titles 的 country 参数 */
    private suspend fun getTmdbCountry(): String {
        val lang = languageStorage.language.first()
        return when (lang) {
            LanguageStorage.LANGUAGE_CHINESE -> "CN"
            LanguageStorage.LANGUAGE_ENGLISH -> "US"
            LanguageStorage.LANGUAGE_JAPANESE -> "JP"
            LanguageStorage.LANGUAGE_KOREAN -> "KR"
            else -> "CN"
        }
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
        val releaseDate: String = "",
        val country: String = ""
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
        val releaseDate: String = "",
        val country: String = ""
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
                releaseDate = cached.release_date ?: "",
                country = cached.production_countries.joinToString(" · ") { it.name }
            )
        }

        return try {
            val response = tmdbApiService.getMovieDetail(tmdbId, language = getTmdbLanguage())
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
                    releaseDate = detail.release_date ?: "",
                    country = detail.production_countries.joinToString(" · ") { it.name }
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
                releaseDate = cached.first_air_date ?: "",
                country = cached.origin_country.map { codeToCountryName(it) }.joinToString(" · ")
            )
        }

        return try {
            val response = tmdbApiService.getTvDetail(tmdbId, language = getTmdbLanguage())
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
                    releaseDate = detail.first_air_date ?: "",
                    country = detail.origin_country.map { codeToCountryName(it) }.joinToString(" · ")
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
            val altResponse = tmdbApiService.getMovieAlternativeTitles(tmdbId, country = getTmdbCountry())
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
            val altResponse = tmdbApiService.getTvAlternativeTitles(tmdbId, country = getTmdbCountry())
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
                MediaType.MOVIE -> tmdbApiService.getMovieCredits(tmdbId, language = getTmdbLanguage())
                MediaType.SHOW -> tmdbApiService.getCredits(tmdbId, language = getTmdbLanguage())
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
            val response = tmdbApiService.getPersonDetail(personId, language = getTmdbLanguage())
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
                val response = tmdbApiService.getPersonMovieCredits(personId, language = getTmdbLanguage(), page = 1)
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
                val response = tmdbApiService.getPersonTvCredits(personId, language = getTmdbLanguage(), page = 1)
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

    /** 将 ISO 3166-1 alpha-2 国家代码转换为当前 Locale 的国家名称 */
    @Suppress("DEPRECATION")
    private fun codeToCountryName(code: String): String {
        if (code.length != 2) return code
        return try {
            val locale = Locale("", code)
            locale.displayCountry.ifEmpty { code }
        } catch (_: Exception) {
            code
        }
    }

    // 通过标题搜索电影，返回第一个匹配结果
    suspend fun searchMovie(query: String): TmdbSearchResult? {
        val key = query.trim()
        searchMovieCache.get(key)?.let { return it }
        return try {
            val response = tmdbApiService.searchMovie(query = query, language = getTmdbLanguage())
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
            val response = tmdbApiService.getPopularMovies(language = getTmdbLanguage())
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
            val response = tmdbApiService.getPopularMovies(language = getTmdbLanguage(), page = page)
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
            val response = tmdbApiService.getUpcomingMovies(language = getTmdbLanguage())
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
            val response = tmdbApiService.getUpcomingMovies(language = getTmdbLanguage(), page = page)
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
            val response = tmdbApiService.getTopRatedMovies(language = getTmdbLanguage())
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
            val response = tmdbApiService.getTopRatedMovies(language = getTmdbLanguage(), page = page)
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
            val response = tmdbApiService.getMovieDetail(movieId, language = getTmdbLanguage())
            if (response.isSuccessful) response.body() else null
        } catch (_: Exception) {
            null
        }
    }
}
