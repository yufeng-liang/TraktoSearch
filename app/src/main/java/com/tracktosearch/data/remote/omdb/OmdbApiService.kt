package com.tracktosearch.data.remote.omdb

import com.tracktosearch.data.remote.omdb.dto.OmdbResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface OmdbApiService {
    // 使用相对路径，保留 Retrofit base URL 中的 /api/omdb/ 前缀。
    @GET(".")
    suspend fun getByImdbId(
        @Query("i") imdbId: String,
        @Query("plot") plot: String = "short"
    ): OmdbResponse
}
