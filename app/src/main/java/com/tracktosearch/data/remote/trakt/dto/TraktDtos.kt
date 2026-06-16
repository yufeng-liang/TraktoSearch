package com.tracktosearch.data.remote.trakt.dto

import kotlinx.serialization.Serializable

@Serializable
data class TraktIds(
    val trakt: Int = 0,
    val slug: String = "",
    val imdb: String = "",
    val tmdb: Int = 0,
    val tvdb: Int = 0
)

@Serializable
data class TraktMovie(
    val title: String = "",
    val year: Int = 0,
    val ids: TraktIds = TraktIds(),
    val rating: Double = 0.0
)

@Serializable
data class TraktShow(
    val title: String = "",
    val year: Int = 0,
    val ids: TraktIds = TraktIds(),
    val rating: Double = 0.0
)

@Serializable
data class TraktWatchlistMovieItem(
    val listed_at: String = "",
    val movie: TraktMovie = TraktMovie()
)

@Serializable
data class TraktWatchlistShowItem(
    val listed_at: String = "",
    val show: TraktShow = TraktShow()
)

@Serializable
data class TraktSyncRequest(
    val movies: List<TraktSyncItem>? = null,
    val shows: List<TraktSyncItem>? = null
)

@Serializable
data class TraktSyncItem(
    val ids: TraktIds
)

@Serializable
data class TraktSyncResponse(
    val added: TraktSyncStats = TraktSyncStats(),
    val existing: TraktSyncStats = TraktSyncStats(),
    val not_found: TraktSyncNotFound = TraktSyncNotFound()
)

@Serializable
data class TraktSyncStats(
    val movies: Int = 0,
    val shows: Int = 0,
    val episodes: Int = 0
)

@Serializable
data class TraktSyncNotFound(
    val movies: List<TraktSyncItem> = emptyList(),
    val shows: List<TraktSyncItem> = emptyList()
)

@Serializable
data class TraktTokenResponse(
    val access_token: String = "",
    val token_type: String = "",
    val expires_in: Long = 0,
    val refresh_token: String = "",
    val scope: String = "",
    val created_at: Long = 0
)

@Serializable
data class TraktTokenRequest(
    val code: String,
    val client_id: String,
    val client_secret: String,
    val redirect_uri: String,
    val grant_type: String = "authorization_code"
)

@Serializable
data class TraktRefreshTokenRequest(
    val refresh_token: String,
    val client_id: String,
    val client_secret: String,
    val redirect_uri: String,
    val grant_type: String = "refresh_token"
)

@Serializable
data class TraktComment(
    val id: Int = 0,
    val comment: String = "",
    val spoiler: Boolean = false,
    val review: Boolean = false,
    val parent_id: Int? = null,
    val created_at: String = "",
    val updated_at: String = "",
    val replies: Int = 0,
    val likes: Int = 0,
    val user_rating: Double? = null,
    val user: TraktCommentUser = TraktCommentUser(),
    val source: String = "Trakt"  // "Trakt" 或 "TMDB"
)

@Serializable
data class TraktCommentUser(
    val username: String = "",
    val name: String = ""
)

@Serializable
data class TraktSeason(
    val number: Int = 0,
    val title: String = "",
    val episode_count: Int = 0,
    val first_aired: String = "",
    val ids: TraktIds = TraktIds(),
    val overview: String = ""
)

@Serializable
data class TraktEpisode(
    val season: Int = 0,
    val number: Int = 0,
    val title: String = "",
    val ids: TraktIds = TraktIds(),
    val overview: String = "",
    val first_aired: String = ""
)
