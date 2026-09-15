package com.tracktosearch.data.remote.trakt

import com.tracktosearch.data.remote.trakt.dto.*
import retrofit2.Response
import retrofit2.http.*

interface TraktApiService {
    @GET("sync/watchlist/{type}/added/desc")
    suspend fun getWatchlist(
        @Path("type") type: String,
        @Query("extended") extended: String = "full,images",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<List<TraktWatchlistMovieItem>>

    @GET("sync/watchlist/{type}/added/desc")
    suspend fun getShowWatchlist(
        @Path("type") type: String = "shows",
        @Query("extended") extended: String = "full,images",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<List<TraktWatchlistShowItem>>

    @POST("sync/history")
    suspend fun addToHistory(@Body body: TraktSyncRequest): Response<TraktSyncResponse>

    @POST("sync/history/remove")
    suspend fun removeFromHistory(@Body body: TraktSyncRequest): Response<TraktSyncResponse>

    @POST("sync/watchlist")
    suspend fun addToWatchlist(@Body body: TraktSyncRequest): Response<TraktSyncResponse>

    @POST("sync/watchlist/remove")
    suspend fun removeFromWatchlist(@Body body: TraktSyncRequest): Response<TraktSyncResponse>

    /**
     * 账号级活动时间。官方建议本地缓存时间戳，未变化时跳过对应同步请求。
     * 参考 https://docs.trakt.tv/reference/getsynclastactivities
     */
    @GET("sync/last_activities")
    suspend fun getLastActivities(): Response<TraktLastActivities>

    @GET("sync/history")
    suspend fun getMovieHistory(
        @Query("type") type: String = "movies",
        @Query("extended") extended: String = "full",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 200
    ): Response<List<TraktWatchlistMovieItem>>

    @GET("sync/history")
    suspend fun getShowHistory(
        @Query("type") type: String = "shows",
        @Query("extended") extended: String = "full",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 200
    ): Response<List<TraktWatchlistShowItem>>

    @GET("sync/history")
    suspend fun getEpisodeHistory(
        @Query("type") type: String = "episodes",
        @Query("extended") extended: String = "full",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<List<TraktHistoryEntry>>

    @POST("oauth/token")
    suspend fun exchangeCodeForToken(@Body body: TraktTokenRequest): Response<TraktTokenResponse>

    @POST("oauth/token")
    suspend fun refreshToken(@Body body: TraktRefreshTokenRequest): Response<TraktTokenResponse>

    @GET("movies/{id}/comments")
    suspend fun getMovieComments(
        @Path("id") id: String,
        @Query("limit") limit: Int = 5,
        @Query("page") page: Int = 1
    ): Response<List<TraktComment>>

    @GET("shows/{id}/comments")
    suspend fun getShowComments(
        @Path("id") id: String,
        @Query("limit") limit: Int = 5,
        @Query("page") page: Int = 1
    ): Response<List<TraktComment>>

    /** POST /comments 添加评论（短评），成功返回 201 */
    @POST("comments")
    suspend fun postComment(@Body body: TraktCommentRequest): Response<TraktComment>

    /** PUT /comments/{id} 更新本人评论。 */
    @PUT("comments/{id}")
    suspend fun editComment(
        @Path("id") id: Int,
        @Body body: TraktCommentEditRequest
    ): Response<TraktComment>

    /** 获取当前用户在指定媒体类型下发表的评论，用于恢复旧缓存的评论 ID。 */
    @GET("users/me/comments/all/{type}")
    suspend fun getMyComments(
        @Path("type") type: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<List<TraktUserComment>>

    @GET("shows/{id}/seasons")
    suspend fun getShowSeasons(
        @Path("id") id: String,
        @Query("extended") extended: String = "full"
    ): Response<List<TraktSeason>>

    @GET("shows/{id}/seasons/{season}/episodes")
    suspend fun getSeasonEpisodes(
        @Path("id") showTraktId: Int,
        @Path("season") seasonNumber: Int,
        @Query("extended") extended: String = "full"
    ): Response<List<TraktEpisode>>

    @GET("shows/{id}/progress/watched")
    suspend fun getShowWatchedProgress(@Path("id") id: Int): Response<TraktShowProgress>

    /**
     * 已看剧集列表。
     *
     * Trakt 自 2026-06-30 起对 watched 端点强制分页：不传 page/limit 只返回前 100 条。
     * 同时 `extended=full` 在该端点已失效（默认即完整剧信息但不含季进度），
     * 需要季/集进度必须显式请求 `extended=progress`。
     * 参考 https://github.com/trakt/trakt-api/discussions/775
     */
    @GET("sync/watched/shows")
    suspend fun getWatchedShows(
        @Query("extended") extended: String = "progress",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 250
    ): Response<List<TraktWatchedShow>>

    @GET("movies/{id}/related")
    suspend fun getRelatedMovies(
        @Path("id") id: Int,
        @Query("limit") limit: Int = 10,
        @Query("page") page: Int = 1
    ): Response<List<TraktMovie>>

    @GET("shows/{id}/related")
    suspend fun getRelatedShows(
        @Path("id") id: Int,
        @Query("limit") limit: Int = 10,
        @Query("page") page: Int = 1
    ): Response<List<TraktShow>>

    @POST("sync/ratings")
    suspend fun addRating(@Body body: RatingRequest): Response<Unit>

    @POST("sync/ratings/remove")
    suspend fun removeRating(@Body body: RatingRequest): Response<Unit>

    @GET("sync/ratings/{type}")
    suspend fun getRatings(
        @Path("type") type: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<List<TraktRatingItem>>

    @GET("search/tmdb/{id}")
    suspend fun searchByTmdb(
        @Path("id") id: Int,
        @Query("type") type: String
    ): Response<List<TraktSearchResult>>

    @GET("search/imdb/{id}")
    suspend fun searchByImdb(
        @Path("id") id: String,
        @Query("type") type: String
    ): Response<List<TraktSearchResult>>

    @GET("search/movie")
    suspend fun searchMovies(
        @Query("query") query: String,
        @Query("limit") limit: Int = 20,
        @Query("page") page: Int = 1
    ): Response<List<TraktSearchResult>>

    @GET("search/show")
    suspend fun searchShows(
        @Query("query") query: String,
        @Query("limit") limit: Int = 20,
        @Query("page") page: Int = 1
    ): Response<List<TraktSearchResult>>

    @GET("recommendations/movies")
    suspend fun getMovieRecommendations(
        @Query("limit") limit: Int = 10,
        @Query("extended") extended: String = "full"
    ): Response<List<TraktMovie>>

    @GET("people/{id}")
    suspend fun getPersonSummary(
        @Path("id") id: String,
        @Query("extended") extended: String = "full"
    ): Response<TraktPersonDetail>

    @GET("people/{id}/movies")
    suspend fun getPersonMovieCredits(
        @Path("id") id: String
    ): Response<TraktPersonCreditsResponse>

    @GET("people/{id}/shows")
    suspend fun getPersonShowCredits(
        @Path("id") id: String
    ): Response<TraktPersonCreditsResponse>

    @GET("people/{id}/aliases")
    suspend fun getPersonAliases(
        @Path("id") id: String
    ): Response<List<TraktPersonAlias>>

    @GET("search/person")
    suspend fun searchPeople(
        @Query("query") query: String,
        @Query("limit") limit: Int = 10,
        @Query("page") page: Int = 1
    ): Response<List<TraktSearchResult>>

    @GET("movies/trending")
    suspend fun getTrendingMovies(
        @Query("extended") extended: String = "full",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 10
    ): Response<List<TraktTrendingMovieResponse>>

    @GET("shows/trending")
    suspend fun getTrendingShows(
        @Query("extended") extended: String = "full",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 10
    ): Response<List<TraktTrendingShowResponse>>

    @GET("movies/anticipated")
    suspend fun getAnticipatedMovies(
        @Query("extended") extended: String = "full",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 10
    ): Response<List<TraktAnticipatedMovieResponse>>

    @GET("shows/anticipated")
    suspend fun getAnticipatedShows(
        @Query("extended") extended: String = "full",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 10
    ): Response<List<TraktAnticipatedShowResponse>>

    @GET("recommendations/shows")
    suspend fun getShowRecommendations(
        @Query("limit") limit: Int = 10,
        @Query("extended") extended: String = "full"
    ): Response<List<TraktRecommendationShowResponse>>

    @GET("lists/trending")
    suspend fun getTrendingLists(
        @Query("limit") limit: Int = 10,
        @Query("page") page: Int = 1
    ): Response<List<TraktTrendingListResponse>>

    @GET("lists/{id}/items")
    suspend fun getListItems(
        @Path("id") id: Int,
        @Query("limit") limit: Int = 20,
        @Query("page") page: Int = 1
    ): Response<List<TraktListItemResponse>>

    // 用户资料
    @GET("users/me")
    suspend fun getUserProfile(
        @Query("extended") extended: String = "full"
    ): Response<TraktUserProfileResponse>

    // users/me 可能只返回基础资料；按用户名读取公开资料可补齐头像 URL。
    @GET("users/{username}/profile")
    suspend fun getUserProfileByUsername(
        @Path("username") username: String,
        @Query("extended") extended: String = "full"
    ): Response<TraktUserProfileResponse>

    // 部分网关或上游版本对 profile 路径返回 405，公开用户资料入口可作为兼容回退。
    @GET("users/{username}")
    suspend fun getUserByUsername(
        @Path("username") username: String,
        @Query("extended") extended: String = "full"
    ): Response<TraktUserProfileResponse>

    // 用户统计
    @GET("users/{id}/stats")
    suspend fun getUserStats(
        @Path("id") userId: String = "me"
    ): Response<TraktUserStatsResponse>

    // 全量评分（必须分页：不传 page/limit 时 Trakt 只返回前 100 条）
    @GET("sync/ratings/movies")
    suspend fun getAllMovieRatings(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 250
    ): Response<List<TraktRatingItem>>

    @GET("sync/ratings/shows")
    suspend fun getAllShowRatings(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 250
    ): Response<List<TraktRatingItem>>

    @GET("movies/{id}/videos")
    suspend fun getMovieVideos(@Path("id") id: String): Response<List<TraktVideo>>

    @GET("shows/{id}/videos")
    suspend fun getShowVideos(@Path("id") id: String): Response<List<TraktVideo>>

    @GET("movies/{id}")
    suspend fun getMovieWithImages(
        @Path("id") id: String,
        @Query("extended") extended: String = "full,images"
    ): Response<TraktMovieWithImages>

    @GET("shows/{id}")
    suspend fun getShowWithImages(
        @Path("id") id: String,
        @Query("extended") extended: String = "full,images"
    ): Response<TraktShowWithImages>

    @GET("people/{id}")
    suspend fun getPersonWithImages(
        @Path("id") id: String,
        @Query("extended") extended: String = "full,images"
    ): Response<TraktPersonWithImages>
}
