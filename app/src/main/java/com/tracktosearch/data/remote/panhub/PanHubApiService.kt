package com.tracktosearch.data.remote.panhub

import com.tracktosearch.data.remote.pansou.dto.PanSouResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface PanHubApiService {

    @GET("api/search")
    suspend fun search(
        @Query("kw") keyword: String,
        @Query("res") res: String = "merged_by_type",
        @Query("src") src: String = "plugin",
        @Query("conc") concurrency: Int = 4,
        @Query("ext") ext: String = """{"__plugin_timeout_ms":5000}""",
        @Query("plugins") plugins: String? = null,
        @Query("channels") channels: String? = null
    ): PanSouResponse
}
