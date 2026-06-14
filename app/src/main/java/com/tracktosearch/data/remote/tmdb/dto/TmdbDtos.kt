package com.tracktosearch.data.remote.tmdb.dto

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
    val vote_average: Double = 0.0
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
    val vote_average: Double = 0.0
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

@Serializable
data class TmdbCast(
    val id: Int = 0,
    val name: String = "",
    val original_name: String = "",
    val profile_path: String? = null,
    val character: String = "",
    val order: Int = 0
)

@Serializable
data class TmdbCrew(
    val id: Int = 0,
    val name: String = "",
    val original_name: String = "",
    val profile_path: String? = null,
    val job: String = "",
    val department: String = ""
)
