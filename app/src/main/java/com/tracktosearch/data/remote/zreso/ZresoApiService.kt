package com.tracktosearch.data.remote.zreso

import com.tracktosearch.data.remote.zreso.dto.ZresoResponse
import com.tracktosearch.data.remote.zreso.dto.ZresoWashResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface ZresoApiService {

    @GET("api/search")
    suspend fun search(
        @Query("q") keyword: String,
        @Query("cloud") cloud: String = "",
        @Query("sort") sort: String = "latest"
    ): ZresoResponse

    /** 把搜索结果里的 /api/wash?t=<token> 中转地址换成真实网盘直链（免 Turnstile） */
    @GET("api/wash")
    suspend fun wash(@Query("t") token: String): ZresoWashResponse
}
