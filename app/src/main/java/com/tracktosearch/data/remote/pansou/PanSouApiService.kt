package com.tracktosearch.data.remote.pansou

import com.tracktosearch.data.remote.pansou.dto.PanSouResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface PanSouApiService {

    @GET("api/search")
    suspend fun search(
        @Query("kw") keyword: String,
        @Query("res") res: String = "merge",
        @Query("cloud_types") cloudTypes: String = "quark,baidu,aliyun,quark,xunlei,uc,115",
        @Query("src") src: String = "all"
    ): PanSouResponse
}
