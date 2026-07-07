package com.tracktosearch.data.remote.config

import okhttp3.ResponseBody
import retrofit2.http.GET

/**
 * 云端配置服务 Retrofit 接口。
 *
 * baseUrl 由 [com.tracktosearch.BuildConfig.CONFIG_BASE_URL] 提供
 * (默认 https://app-config.pages.dev/)。
 *
 * 返回的是 base64 编码的密文,客户端用 AES-256-GCM 解密后得到 JSON 明文。
 */
interface ConfigApiService {
    /**
     * 拉取加密的配置文件。
     *
     * @return base64 字符串形式的密文(iv + ciphertext + auth tag)
     */
    @GET("config.json.enc")
    suspend fun fetchEncryptedConfig(): ResponseBody
}
