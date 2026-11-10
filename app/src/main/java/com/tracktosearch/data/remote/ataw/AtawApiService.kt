package com.tracktosearch.data.remote.ataw

import com.tracktosearch.data.remote.ataw.dto.AtawSearchResponse
import java.util.UUID
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * 阿涛搜站内搜索接口（逆向所得，2026-11 实测可用）。
 *
 * 鉴权只有一个请求头 `x-miniapp-session`，值可以是任意 UUID；
 * 缺失会被拒：400 `{"code":"BAD_REQUEST","message":"缺少合法的网页端会话标识"}`。
 *
 * 不传 [platform] 时服务端返回跨网盘混合结果（每条 item 的 links[] 里带各自的 platform），
 * 一次请求就能拿到所有网盘类型，比按 platform 逐个查更省流量。
 */
interface AtawApiService {

    companion object {
        /**
         * 进程内固定一个会话标识（不随请求轮换）：
         * 服务端只验格式，但固定值更像一个稳定客户端，频繁随机值反而易被风控盯上。
         * 注意：该值在进程重启后会重新生成，并非跨安装持久化。
         */
        val SESSION: String = UUID.randomUUID().toString()
    }

    @GET("api/v1/public/resources/search")
    suspend fun search(
        @Query("q") keyword: String,
        @Query("scope") scope: String = "biz",
        @Query("rankMode") rankMode: String = "default",
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 30,
        @Query("botBiz") botBiz: String = "quark",
        @Header("x-miniapp-session") session: String = SESSION
    ): AtawSearchResponse
}
