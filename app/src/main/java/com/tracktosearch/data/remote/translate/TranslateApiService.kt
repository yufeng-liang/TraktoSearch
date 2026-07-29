package com.tracktosearch.data.remote.translate

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 百度翻译网关代理 Retrofit 接口。
 *
 * baseUrl 由 [com.tracktosearch.BuildConfig.GATEWAY_BASE_URL] + /api/translate/ 提供，
 * 走 auth-worker 代理，密钥由 worker 注入，客户端不持有 BAIDU_* 密钥。
 */
interface TranslateApiService {

    /** 百度大模型翻译（主路径） */
    @POST("ai")
    suspend fun translateAi(@Body body: TranslateRequest): ResponseBody

    /** 百度通用翻译（降级路径） */
    @POST("general")
    suspend fun translateGeneral(@Body body: TranslateRequest): ResponseBody
}

@Serializable
data class TranslateRequest(
    val q: String,
    val from: String = "en",
    val to: String,
    val reference: String = "",
)
