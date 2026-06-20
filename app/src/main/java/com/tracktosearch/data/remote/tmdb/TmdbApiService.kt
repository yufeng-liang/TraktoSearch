package com.tracktosearch.data.remote.tmdb

import com.tracktosearch.data.remote.tmdb.dto.*
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApiService {
    @GET("movie/{movie_id}")
    suspend fun getMovieDetail(
        @Path("movie_id") id: Int,
        @Query("language") language: String = "zh-CN"
    ): Response<TmdbMovieDetail>

    @GET("tv/{tv_id}")
    suspend fun getTvDetail(
        @Path("tv_id") id: Int,
        @Query("language") language: String = "zh-CN"
    ): Response<TmdbTvDetail>

    @GET("movie/{movie_id}/alternative_titles")
    suspend fun getMovieAlternativeTitles(
        @Path("movie_id") id: Int,
        @Query("country") country: String = "CN"
    ): Response<TmdbAlternativeTitlesResponse>

    @GET("tv/{tv_id}/alternative_titles")
    suspend fun getTvAlternativeTitles(
        @Path("tv_id") id: Int,
        @Query("country") country: String = "CN"
    ): Response<TmdbAlternativeTitlesResponse>

    @GET("movie/{movie_id}/credits")
    suspend fun getMovieCredits(
        @Path("movie_id") id: Int,
        @Query("language") language: String = "zh-CN"
    ): Response<TmdbCreditsResponse>

    @GET("tv/{tv_id}/credits")
    suspend fun getCredits(
        @Path("tv_id") id: Int,
        @Query("language") language: String = "zh-CN"
    ): Response<TmdbCreditsResponse>

    @GET("movie/{movie_id}/reviews")
    suspend fun getMovieReviews(
        @Path("movie_id") id: Int,
        @Query("page") page: Int = 1
    ): Response<TmdbReviewsResponse>

    @GET("tv/{tv_id}/reviews")
    suspend fun getTvReviews(
        @Path("tv_id") id: Int,
        @Query("page") page: Int = 1
    ): Response<TmdbReviewsResponse>

    @GET("person/{person_id}")
    suspend fun getPersonDetail(
        @Path("person_id") id: Int,
        @Query("language") language: String = "zh-CN"
    ): Response<TmdbPerson>

    @GET("person/{person_id}/movie_credits")
    suspend fun getPersonMovieCredits(
        @Path("person_id") id: Int,
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1
    ): Response<TmdbPersonMovieCredits>

    @GET("person/{person_id}/tv_credits")
    suspend fun getPersonTvCredits(
        @Path("person_id") id: Int,
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1
    ): Response<TmdbPersonTvCredits>

    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query") query: String,
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1
    ): Response<TmdbSearchResponse>

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1
    ): Response<TmdbSearchResponse>

    @GET("movie/upcoming")
    suspend fun getUpcomingMovies(
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1
    ): Response<TmdbSearchResponse>

    @GET("movie/top_rated")
    suspend fun getTopRatedMovies(
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1
    ): Response<TmdbSearchResponse>
}
