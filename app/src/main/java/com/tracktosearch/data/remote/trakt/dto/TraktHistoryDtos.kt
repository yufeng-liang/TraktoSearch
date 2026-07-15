package com.tracktosearch.data.remote.trakt.dto

import kotlinx.serialization.Serializable

/**
 * Trakt /sync/history 响应项。
 * 一条记录可能对应一部电影（type=movie）或一集（type=episode，内含 show 信息）。
 */
@Serializable
data class TraktHistoryEntry(
    val id: Long = 0,
    val watched_at: String? = null,
    val type: String = "",            // "movie" / "episode"
    val movie: TraktHistoryMovie? = null,
    val episode: TraktHistoryEpisode? = null,
    val show: TraktHistoryShow? = null
)

@Serializable
data class TraktHistoryMovie(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktHistoryIds = TraktHistoryIds()
)

@Serializable
data class TraktHistoryShow(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktHistoryIds = TraktHistoryIds()
)

@Serializable
data class TraktHistoryEpisode(
    val season: Int = 0,
    val number: Int = 0,
    val title: String = "",
    val ids: TraktHistoryIds = TraktHistoryIds()
)

@Serializable
data class TraktHistoryIds(
    val trakt: Int = 0,
    val tmdb: Int = 0,
    val imdb: String = ""
)
