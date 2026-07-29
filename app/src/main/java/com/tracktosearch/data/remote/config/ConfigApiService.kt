package com.tracktosearch.data.remote.config

import retrofit2.http.GET

/**
 * 云端配置服务 Retrofit 接口。
 *
 * baseUrl 由 [com.tracktosearch.BuildConfig.GATEWAY_BASE_URL] 提供，
 * 走网关 /api/config（需 JWT 鉴权），worker 服务端用 CONFIG_AES_KEY Secret
 * 解密后返回明文 JSON，密钥不再编译进 APK。
 */
interface ConfigApiService {
    /**
     * 拉取配置文件（明文 JSON，由网关服务端解密）。
     *
     * @return 解密后的 JSON 字符串
     */
    @GET("api/config")
    suspend fun fetchConfig(): String
}
