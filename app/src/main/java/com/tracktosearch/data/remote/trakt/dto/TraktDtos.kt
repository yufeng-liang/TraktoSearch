package com.tracktosearch.data.remote.trakt.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
data class TraktSocialIds(
    @kotlinx.serialization.SerialName("facebook") val facebook: String? = null,
    @kotlinx.serialization.SerialName("instagram") val instagram: String? = null,
    @kotlinx.serialization.SerialName("twitter") val twitter: String? = null,
    @kotlinx.serialization.SerialName("wikipedia") val wikipedia: String? = null
)

@Serializable
data class TraktMovie(
    val title: String = "",
    val year: Int = 0,
    val ids: TraktIds = TraktIds(),
    val rating: Double = 0.0,
    val runtime: Int = 0,
    val genres: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("poster_path")
    val posterPath: String? = null
)

@Serializable
data class TraktShow(
    val title: String = "",
    val year: Int = 0,
    val ids: TraktIds = TraktIds(),
    val rating: Double = 0.0,
    val runtime: Int = 0,
    val genres: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("poster_path")
    val posterPath: String? = null
)

@Serializable
data class TraktWatchlistMovieItem(
    val listed_at: String = "",
    val watched_at: String = "",
    val movie: TraktMovie = TraktMovie()
)

@Serializable
data class TraktWatchlistShowItem(
    val listed_at: String = "",
    val watched_at: String = "",
    val show: TraktShow = TraktShow()
)

@Serializable
data class TraktSyncRequest(
    val movies: List<TraktSyncItem>? = null,
    val shows: List<TraktSyncItem>? = null,
    val episodes: List<TraktSyncItem>? = null
)

@Serializable
data class TraktSyncItem(
    val ids: TraktIds,
    // 仅 addToHistory(/sync/history POST)时传递,标记真实观看时间;
    // 其他场景(watchlist add/remove)留 null 不序列化
    val watched_at: String? = null
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

/** POST /comments 请求体 */
@Serializable
data class TraktCommentRequest(
    val item: TraktCommentItem,
    val comment: String,
    val spoiler: Boolean = false
)

@Serializable
data class TraktCommentItem(
    val type: String,               // "movie" 或 "show"
    val ids: TraktCommentItemId
)

@Serializable
data class TraktCommentItemId(
    val trakt: Int
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

// ==================== 评分相关 DTO ====================

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class RatingItem(
    val ids: TraktIds,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rating: Int? = null,
    // 评分时间(ISO 8601),留 null 不序列化
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rated_at: String? = null
)

@Serializable
data class RatingRequest(
    val movies: List<RatingItem>? = null,
    val shows: List<RatingItem>? = null
)

@Serializable
data class TraktRatingItem(
    val rated_at: String = "",
    val rating: Int = 0,
    val movie: TraktMovie? = null,
    val show: TraktShow? = null
)

// ==================== 观看进度 DTO ====================

@Serializable
data class TraktWatchedShow(
    val plays: Int = 0,
    val show: TraktShow = TraktShow(),
    val seasons: List<TraktWatchedSeason> = emptyList()
)

@Serializable
data class TraktWatchedSeason(
    val number: Int = 0,
    val episodes: List<TraktWatchedEpisode> = emptyList()
)

@Serializable
data class TraktWatchedEpisode(
    val number: Int = 0,
    val completed: Int = 0
)

@Serializable
data class TraktShowProgress(
    val aired: Int = 0,
    val completed: Int = 0,
    val seasons: List<TraktProgressSeason> = emptyList()
)

@Serializable
data class TraktProgressSeason(
    val number: Int = 0,
    val episodes: List<TraktProgressEpisode> = emptyList()
)

@Serializable
data class TraktProgressEpisode(
    val number: Int = 0,
    val completed: Boolean = false
)

@Serializable
data class TraktSearchResult(
    val type: String = "",
    val score: Double = 0.0,
    val movie: TraktMovie? = null,
    val show: TraktShow? = null,
    val person: TraktPerson? = null
)

@Serializable
data class TraktPerson(
    val name: String = "",
    val ids: TraktIds = TraktIds()
)

@Serializable
data class TraktPersonDetail(
    val name: String = "",
    val ids: TraktIds = TraktIds(),
    @kotlinx.serialization.SerialName("social_ids") val social_ids: TraktSocialIds = TraktSocialIds(),
    val gender: String = "",
    val known_for_department: String = "",
    val biography: String = "",
    val birthday: String? = null,
    val death: String? = null,
    val homepage: String? = null,
    val headshot: String? = null,
    val movie_credits: Int = 0,
    val show_credits: Int = 0
)

// ==================== Trakt 人物参演 DTO ====================

@Serializable
data class TraktPersonCreditsResponse(
    val cast: List<TraktPersonCreditItem> = emptyList(),
    val crew: Map<String, List<TraktPersonCreditItem>> = emptyMap()
)

@Serializable
data class TraktPersonCreditItem(
    val characters: List<String> = emptyList(),
    val movie: TraktMovie? = null,
    val show: TraktShow? = null
)

@Serializable
data class TraktPersonAlias(
    val name: String = "",
    val country: String? = null
)

// ==================== Trakt 视频 DTO ====================

@Serializable
data class TraktVideo(
    val title: String = "",
    val url: String = "",
    val site: String = "",
    val type: String = "",
    val size: Int = 0,
    val official: Boolean = false,
    val published_at: String? = null,
    val country: String? = null,
    val language: String? = null
)

// ==================== Trakt 图片 DTO ====================

@Serializable
data class TraktImages(
    val fanart: List<String> = emptyList(),
    val poster: List<String> = emptyList(),
    val logo: List<String> = emptyList(),
    val clearart: List<String> = emptyList(),
    val banner: List<String> = emptyList(),
    val thumb: List<String> = emptyList(),
    val headshot: List<String> = emptyList()
)

@Serializable
data class TraktMovieWithImages(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktIds = TraktIds(),
    val images: TraktImages = TraktImages()
)

@Serializable
data class TraktShowWithImages(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktIds = TraktIds(),
    val images: TraktImages = TraktImages()
)

@Serializable
data class TraktPersonWithImages(
    val name: String = "",
    val ids: TraktIds = TraktIds(),
    val images: TraktImages = TraktImages()
)

// ==================== 发现页热门/期待 DTO ====================

@Serializable
data class TraktTrendingMovieResponse(
    val watchers: Int = 0,
    val movie: TraktMovie = TraktMovie()
)

@Serializable
data class TraktTrendingShowResponse(
    val watchers: Int = 0,
    val show: TraktShow = TraktShow()
)

@Serializable
data class TraktAnticipatedMovieResponse(
    val list_count: Int = 0,
    val movie: TraktMovie = TraktMovie()
)

@Serializable
data class TraktAnticipatedShowResponse(
    val list_count: Int = 0,
    val show: TraktShow = TraktShow()
)

@Serializable
data class TraktRecommendationShowResponse(
    val show: TraktShow = TraktShow()
)

@Serializable
data class TraktTrendingListResponse(
    val like_count: Int = 0,
    val comment_count: Int = 0,
    val list: TraktListInfo = TraktListInfo()
)

@Serializable
data class TraktListInfo(
    val name: String = "",
    val description: String = "",
    val privacy: String = "",
    val display_numbers: Boolean = false,
    val allow_comments: Boolean = false,
    val sort_by: String = "",
    val sort_how: String = "",
    val created_at: String = "",
    val updated_at: String = "",
    val item_count: Int = 0,
    val comment_count: Int = 0,
    val like_count: Int = 0,
    val ids: TraktListIds = TraktListIds(),
    val user: TraktListUser? = null
)

@Serializable
data class TraktListIds(
    val trakt: Int = 0,
    val slug: String = ""
)

@Serializable
data class TraktListUser(
    val username: String = ""
)

@Serializable
data class TraktUserProfileResponse(
    val username: String = "",
    val name: String = "",
    val private: Boolean = false,
    val vip: Boolean = false,
    val vip_ep: Boolean = false,
    val images: TraktUserImages = TraktUserImages()
)

@Serializable
data class TraktUserImages(
    val avatar: TraktAvatar = TraktAvatar()
)

@Serializable
data class TraktAvatar(
    val full: String = ""
)

@Serializable
data class TraktUserStatsResponse(
    val movies: TraktStatsDetail = TraktStatsDetail(),
    val shows: TraktStatsDetail = TraktStatsDetail(),
    val episodes: TraktStatsDetail = TraktStatsDetail()
)

@Serializable
data class TraktStatsDetail(
    val watched: Int = 0,
    val collected: Int = 0,
    val ratings: Int = 0,
    val minutes: Int = 0
)

@Serializable
data class TraktListItemResponse(
    val rank: Int = 0,
    val id: Int = 0,
    val listed_at: String = "",
    val notes: String = "",
    val type: String = "",
    val movie: TraktMovie? = null,
    val show: TraktShow? = null
)
