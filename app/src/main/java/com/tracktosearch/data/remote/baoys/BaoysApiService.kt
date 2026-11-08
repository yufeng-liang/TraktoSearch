package com.tracktosearch.data.remote.baoys

import com.tracktosearch.data.remote.baoys.dto.BaoysResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 宝藏郎（baoys.baozang.asia）聚合搜索接口（2026-11 实测可用）。
 *
 * `GET /api?keyword=` 免鉴权；实测响应 10-25 秒，是所有内置源里最慢的，
 * 调用方超时要放宽（仓储层 22s / OkHttp call 30s）。
 */
interface BaoysApiService {

    @GET("api")
    suspend fun search(@Query("keyword") keyword: String): BaoysResponse
}
