package com.tracktosearch.data.remote.trakt

import com.tracktosearch.data.remote.trakt.dto.*
import retrofit2.Response
import retrofit2.http.*

interface TraktApiService {
    @GET("sync/watchlist")
    suspend fun getWatchlist(
        @Query("type") type: String,
        @Query("extended") extended: String = "full",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<List<TraktWatchlistMovieItem>>

    @GET("sync/watchlist")
    suspend fun getShowWatchlist(
        @Query("type") type: String = "shows",
        @Query("extended") extended: String = "full",
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

    @GET("sync/watched/shows")
    suspend fun getWatchedShows(
        @Query("extended") extended: String = "full"
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
    suspend fun getRatings(@Path("type") type: String): Response<List<TraktRatingItem>>

    @GET("search/tmdb/{id}")
    suspend fun searchByTmdb(
        @Path("id") id: Int,
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
}
