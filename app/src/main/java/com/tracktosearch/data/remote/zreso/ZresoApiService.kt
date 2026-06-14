package com.tracktosearch.data.remote.zreso

import com.tracktosearch.data.remote.zreso.dto.ZresoResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface ZresoApiService {

    @GET("api/search")
    suspend fun search(
        @Query("q") keyword: String,
        @Query("cloud") cloud: String = "",
        @Query("sort") sort: String = "latest"
    ): ZresoResponse
}
