package com.tracktosearch.data.remote.melost

import com.tracktosearch.data.remote.melost.dto.MelostSearchRequest
import com.tracktosearch.data.remote.melost.dto.MelostSearchResponse
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * 影盘社（melost.cn）站内搜索接口（2026-11 实测可用）。
 *
 * POST JSON `{"q":kw,"page":1,"size":30}`，需带 `Referer: https://www.melost.cn/`；
 * `size` 服务器上限 50（再大会被压回），每页实测可稳定返回 30 条。
 */
interface MelostApiService {

    @Headers("Referer: https://www.melost.cn/")
    @POST("v1/search/disk")
    suspend fun search(@Body request: MelostSearchRequest): MelostSearchResponse
}
