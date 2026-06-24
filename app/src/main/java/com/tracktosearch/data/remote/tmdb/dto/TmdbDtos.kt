package com.tracktosearch.data.remote.tmdb.dto

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
data class TmdbMovieDetail(
    val id: Int = 0,
    val title: String = "",
    val original_title: String = "",
    val poster_path: String? = null,
    val overview: String = "",
    val genres: List<TmdbGenre> = emptyList(),
    val release_date: String = "",
    val vote_average: Double = 0.0,
    val runtime: Int? = null,
    val production_countries: List<TmdbProductionCountry> = emptyList()
)

@Serializable
data class TmdbTvDetail(
    val id: Int = 0,
    val name: String = "",
    val original_name: String = "",
    val poster_path: String? = null,
    val overview: String = "",
    val genres: List<TmdbGenre> = emptyList(),
    val first_air_date: String = "",
    val vote_average: Double = 0.0,
    val episode_run_time: List<Int>? = null,
    val origin_country: List<String> = emptyList()
)

@Serializable
data class TmdbProductionCountry(
    val iso_3166_1: String = "",
    val name: String = ""
)

@Serializable
data class TmdbGenre(
    val id: Int = 0,
    val name: String = ""
)

@Serializable
data class TmdbAlternativeTitlesResponse(
    val titles: List<TmdbAlternativeTitle> = emptyList()
)

@Serializable
data class TmdbAlternativeTitle(
    val iso_3166_1: String = "",
    val title: String = "",
    val type: String = ""
)

@Serializable
data class TmdbCreditsResponse(
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList()
)

@Immutable
@Serializable
data class TmdbCast(
    val id: Int = 0,
    val name: String = "",
    val original_name: String = "",
    val profile_path: String? = null,
    val character: String = "",
    val order: Int = 0
)

@Immutable
@Serializable
data class TmdbCrew(
    val id: Int = 0,
    val name: String = "",
    val original_name: String = "",
    val profile_path: String? = null,
    val job: String = "",
    val department: String = ""
)

@Serializable
data class TmdbReviewsResponse(
    val results: List<TmdbReview> = emptyList(),
    val total_pages: Int = 1,
    val total_results: Int = 0
)

@Serializable
data class TmdbReview(
    val id: String = "",
    val author: String = "",
    val author_details: TmdbReviewAuthor = TmdbReviewAuthor(),
    val content: String = "",
    val created_at: String = "",
    val url: String = ""
)

@Serializable
data class TmdbReviewAuthor(
    val name: String = "",
    val username: String = "",
    val avatar_path: String? = null,
    val rating: Double? = null
)

@Serializable
data class TmdbPerson(
    val id: Int = 0,
    val name: String = "",
    val birthday: String? = null,
    val deathday: String? = null,
    val biography: String = "",
    val place_of_birth: String? = null,
    val profile_path: String? = null,
    val known_for_department: String = "",
    val gender: Int? = null
)

@Serializable
data class TmdbPersonMovieCredits(
    val cast: List<TmdbPersonMovieCredit> = emptyList()
)

@Serializable
data class TmdbPersonMovieCredit(
    val id: Int = 0,
    val title: String = "",
    val poster_path: String? = null,
    val character: String = "",
    val release_date: String = "",
    val vote_average: Double = 0.0
)

@Serializable
data class TmdbPersonTvCredits(
    val cast: List<TmdbPersonTvCredit> = emptyList()
)

@Serializable
data class TmdbPersonTvCredit(
    val id: Int = 0,
    val name: String = "",
    val poster_path: String? = null,
    val character: String = "",
    val first_air_date: String = "",
    val vote_average: Double = 0.0
)

@Serializable
data class TmdbSearchResponse(
    val page: Int = 1,
    val results: List<TmdbSearchResult> = emptyList(),
    val total_pages: Int = 0,
    val total_results: Int = 0
)

@Serializable
data class TmdbMultiSearchResponse(
    val page: Int = 1,
    val results: List<TmdbMultiSearchResult> = emptyList(),
    val total_pages: Int = 0,
    val total_results: Int = 0
)

@Serializable
data class TmdbSearchResult(
    val id: Int = 0,
    val title: String = "",
    val overview: String = "",
    val poster_path: String? = null,
    val release_date: String = ""
)

@Serializable
data class TmdbMultiSearchResult(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val overview: String = "",
    val poster_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val media_type: String = "",
    val vote_average: Double = 0.0
)

@Serializable
data class TmdbPersonSearchResponse(
    val page: Int = 1,
    val results: List<TmdbPersonSearchResult> = emptyList(),
    val total_pages: Int = 0,
    val total_results: Int = 0
)

@Serializable
data class TmdbPersonSearchResult(
    val id: Int = 0,
    val name: String = "",
    val original_name: String = "",
    val profile_path: String? = null,
    val known_for_department: String = "",
    val gender: Int = 0,
    val popularity: Double = 0.0,
    val known_for: List<TmdbSearchResult> = emptyList()
)

@Serializable
data class TmdbVideosResponse(
    val results: List<TmdbVideo> = emptyList()
)

@Serializable
data class TmdbVideo(
    val id: String = "",
    val key: String = "",
    val name: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    val size: Int = 0
)

@Serializable
data class TmdbImagesResponse(
    val backdrops: List<TmdbImage> = emptyList()
)

@Serializable
data class TmdbPersonImagesResponse(
    val id: Int = 0,
    val profiles: List<TmdbImage> = emptyList()
)

@Serializable
data class TmdbImage(
    val file_path: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val iso_639_1: String? = null
)
