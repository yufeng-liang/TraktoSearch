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

    @GET("search/person")
    suspend fun searchPerson(
        @Query("query") query: String,
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1
    ): Response<TmdbPersonSearchResponse>

    @GET("search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1
    ): Response<TmdbMultiSearchResponse>

    @GET("trending/movie/{time_window}")
    suspend fun getTrendingMovies(
        @Path("time_window") timeWindow: String = "day",
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

    @GET("movie/{movie_id}/videos")
    suspend fun getMovieVideos(
        @Path("movie_id") id: Int,
        @Query("language") language: String = "zh-CN"
    ): Response<TmdbVideosResponse>

    @GET("tv/{tv_id}/videos")
    suspend fun getTvVideos(
        @Path("tv_id") id: Int,
        @Query("language") language: String = "zh-CN"
    ): Response<TmdbVideosResponse>

    @GET("movie/{movie_id}/images")
    suspend fun getMovieImages(
        @Path("movie_id") id: Int,
        @Query("include_image_language") language: String = "zh,null"
    ): Response<TmdbImagesResponse>

    @GET("tv/{tv_id}/images")
    suspend fun getTvImages(
        @Path("tv_id") id: Int,
        @Query("include_image_language") language: String = "zh,null"
    ): Response<TmdbImagesResponse>

    @GET("person/{person_id}/images")
    suspend fun getPersonImages(
        @Path("person_id") id: Int
    ): Response<TmdbPersonImagesResponse>

    @GET("person/{person_id}/tagged_images")
    suspend fun getPersonTaggedImages(
        @Path("person_id") id: Int,
        @Query("page") page: Int = 1
    ): Response<TmdbPersonTaggedImagesResponse>

    @GET("movie/{movie_id}/similar")
    suspend fun getSimilarMovies(
        @Path("movie_id") id: Int,
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1
    ): Response<TmdbSearchResponse>

    @GET("tv/{tv_id}/similar")
    suspend fun getSimilarShows(
        @Path("tv_id") id: Int,
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1
    ): Response<TmdbSearchResponse>

    @GET("collection/{collection_id}")
    suspend fun getCollection(
        @Path("collection_id") id: Int,
        @Query("language") language: String = "zh-CN"
    ): Response<TmdbCollectionResponse>

    @GET("tv/{tv_id}/season/{season_number}")
    suspend fun getTvSeasonDetail(
        @Path("tv_id") tvId: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("language") language: String = "zh-CN"
    ): Response<TmdbTvSeasonDetail>

    // ========== Discover API：按类型/地区/评分等维度筛选影视 ==========

    @GET("discover/movie")
    suspend fun discoverMovie(
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1,
        @Query("with_genres") withGenres: String? = null,
        @Query("with_origin_country") withOriginCountry: String? = null,
        @Query("with_keywords") withKeywords: String? = null,
        @Query("vote_average.gte") voteAverageGte: Float? = null,
        @Query("vote_average.lte") voteAverageLte: Float? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("primary_release_date.gte") releaseDateGte: String? = null,
        @Query("primary_release_date.lte") releaseDateLte: String? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("include_adult") includeAdult: Boolean = false
    ): Response<TmdbSearchResponse>

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("language") language: String = "zh-CN",
        @Query("page") page: Int = 1,
        @Query("with_genres") withGenres: String? = null,
        @Query("with_origin_country") withOriginCountry: String? = null,
        @Query("with_keywords") withKeywords: String? = null,
        @Query("vote_average.gte") voteAverageGte: Float? = null,
        @Query("vote_average.lte") voteAverageLte: Float? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("first_air_date.gte") airDateGte: String? = null,
        @Query("first_air_date.lte") airDateLte: String? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("include_adult") includeAdult: Boolean = false
    ): Response<TmdbSearchResponse>
}
