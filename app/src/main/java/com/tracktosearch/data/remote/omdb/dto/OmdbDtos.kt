package com.tracktosearch.data.remote.omdb.dto

import kotlinx.serialization.Serializable

@Serializable
data class OmdbResponse(
    val Title: String = "",
    val Year: String = "",
    val Rated: String = "",
    val imdbRating: String = "",
    val imdbVotes: String = "",
    val Ratings: List<OmdbRating> = emptyList(),
    val Director: String = "",
    val Actors: String = "",
    val Writer: String = "",
    val Response: String = "False"
)

@Serializable
data class OmdbRating(
    val Source: String = "",
    val Value: String = ""
)
