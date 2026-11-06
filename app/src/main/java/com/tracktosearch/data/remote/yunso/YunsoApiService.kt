package com.tracktosearch.data.remote.yunso

import com.tracktosearch.data.remote.yunso.dto.YunsoResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 云搜开放搜索接口。
 *
 * 实测（2026-11）：`/api/opensearch.php?wd=&mode=&page=` 免鉴权、GET 直连返回 JSON；
 * [mode] 90001 = 智能搜索、90002 = 精准搜索，两者结果集相近；[page] 从 1 开始，每页 30 条。
 */
interface YunsoApiService {

    @GET("api/opensearch.php")
    suspend fun search(
        @Query("wd") keyword: String,
        @Query("mode") mode: String = "90001",
        @Query("page") page: Int = 1
    ): YunsoResponse
}
