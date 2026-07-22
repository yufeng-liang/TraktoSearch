package com.tracktosearch.data.remote.omdb

import com.tracktosearch.data.remote.omdb.dto.OmdbResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface OmdbApiService {
    @GET("/")
    suspend fun getByImdbId(
        @Query("i") imdbId: String,
        @Query("plot") plot: String = "short"
    ): OmdbResponse
}
