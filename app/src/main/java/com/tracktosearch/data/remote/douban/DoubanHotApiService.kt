package com.tracktosearch.data.remote.douban

import com.tracktosearch.data.remote.douban.dto.DoubanHotResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface DoubanHotApiService {

    @GET("api/douban-hot")
    suspend fun getDoubanHot(
        @Query("category") category: String = "douban-movie",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 25
    ): DoubanHotResponse
}
