package com.tracktosearch.data.repository

import com.tracktosearch.data.remote.tmdb.TmdbApiService
import com.tracktosearch.data.remote.tmdb.dto.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbRepository @Inject constructor(
    private val tmdbApiService: TmdbApiService
) {
    companion object {
        private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"
    }

    private val movieDetailCache = mutableMapOf<Int, TmdbMovieDetail>()
    private val tvDetailCache = mutableMapOf<Int, TmdbTvDetail>()
    private val movieTitleCache = mutableMapOf<Int, String>()
    private val tvTitleCache = mutableMapOf<Int, String>()
    private val creditsCache = mutableMapOf<Int, TmdbCreditsResponse>()

    data class MovieEnrichment(
        val posterUrl: String?,
        val chineseTitle: String,
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
        val overview: String,
        val genres: String,
        val year: Int?,
        val rating: Double,
        val episodeRunTime: Int? = null,
        val releaseDate: String = ""
    )

    suspend fun enrichMovie(tmdbId: Int, originalTitle: String, year: Int?): MovieEnrichment {
        val cached = movieDetailCache[tmdbId]
        if (cached != null) {
            val chineseTitle = movieTitleCache.getOrPut(tmdbId) {
                resolveMovieChineseTitle(tmdbId, originalTitle, cached)
            }
            return MovieEnrichment(
                posterUrl = cached.poster_path?.let { "$IMAGE_BASE_URL$it" },
                chineseTitle = chineseTitle,
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
                movieDetailCache[tmdbId] = detail
                val chineseTitle = resolveMovieChineseTitle(tmdbId, originalTitle, detail)
                movieTitleCache[tmdbId] = chineseTitle
                MovieEnrichment(
                    posterUrl = detail.poster_path?.let { "$IMAGE_BASE_URL$it" },
                    chineseTitle = chineseTitle,
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
        val cached = tvDetailCache[tmdbId]
        if (cached != null) {
            val chineseTitle = tvTitleCache.getOrPut(tmdbId) {
                resolveTvChineseTitle(tmdbId, originalName, cached)
            }
            return TvEnrichment(
                posterUrl = cached.poster_path?.let { "$IMAGE_BASE_URL$it" },
                chineseTitle = chineseTitle,
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
                tvDetailCache[tmdbId] = detail
                val chineseTitle = resolveTvChineseTitle(tmdbId, originalName, detail)
                tvTitleCache[tmdbId] = chineseTitle
                TvEnrichment(
                    posterUrl = detail.poster_path?.let { "$IMAGE_BASE_URL$it" },
                    chineseTitle = chineseTitle,
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

    fun buildPosterUrl(posterPath: String?): String? {
        return posterPath?.let { "$IMAGE_BASE_URL$it" }
    }

    suspend fun getMovieDetail(tmdbId: Int): TmdbMovieDetail? {
        movieDetailCache[tmdbId]?.let { return it }
        return try {
            val response = tmdbApiService.getMovieDetail(tmdbId)
            if (response.isSuccessful) {
                response.body()?.also { movieDetailCache[tmdbId] = it }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getTvDetail(tmdbId: Int): TmdbTvDetail? {
        tvDetailCache[tmdbId]?.let { return it }
        return try {
            val response = tmdbApiService.getTvDetail(tmdbId)
            if (response.isSuccessful) {
                response.body()?.also { tvDetailCache[tmdbId] = it }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getCredits(tmdbId: Int, mediaType: MediaType): TmdbCreditsResponse? {
        creditsCache[tmdbId]?.let { return it }
        return try {
            val response = when (mediaType) {
                MediaType.MOVIE -> tmdbApiService.getMovieCredits(tmdbId)
                MediaType.SHOW -> tmdbApiService.getCredits(tmdbId)
            }
            if (response.isSuccessful) {
                response.body()?.also { creditsCache[tmdbId] = it }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun buildProfileUrl(profilePath: String?): String? {
        return profilePath?.let { "$IMAGE_BASE_URL$it" }
    }
}
