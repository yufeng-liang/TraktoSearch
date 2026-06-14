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
}
