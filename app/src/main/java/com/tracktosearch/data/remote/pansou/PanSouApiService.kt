package com.tracktosearch.data.remote.pansou

import com.tracktosearch.data.remote.pansou.dto.PanSouCheckRequest
import com.tracktosearch.data.remote.pansou.dto.PanSouCheckResponse
import com.tracktosearch.data.remote.pansou.dto.PanSouResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface PanSouApiService {

    @GET("api/search")
    suspend fun search(
        @Query("kw") keyword: String,
        @Query("res") res: String = "merge",
        @Query("cloud_types") cloudTypes: String = "quark,baidu,aliyun,xunlei,uc,115",
        @Query("src") src: String = "all"
    ): PanSouResponse

    /**
     * 链接有效性检测。PanSou 系站点（含盘搜 useai）共用该接口：
     * 返回的 state 为 ok/bad/locked/unsupported/uncertain 等，summary 是服务端中文说明。
     */
    @POST("api/check/links")
    suspend fun checkLinks(@Body request: PanSouCheckRequest): PanSouCheckResponse
}
