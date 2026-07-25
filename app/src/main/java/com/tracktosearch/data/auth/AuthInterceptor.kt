package com.tracktosearch.data.auth

import com.tracktosearch.data.local.TokenStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.decodeFromString
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * JWT 拦截器
 * - 自动在请求头添加 Authorization: Bearer <access_token>
 * - 令牌过期时尝试刷新
 */
class AuthInterceptor @Inject constructor(
    private val tokenStorage: TokenStorage,
    private val authManager: AuthManager,
    private val json: Json
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // 跳过不需要授权的请求
        if (originalRequest.header("Authorization") != null) {
            return chain.proceed(originalRequest)
        }

        // 获取 access token
        val token = tokenStorage.getCachedAccessToken()
            ?: return chain.proceed(originalRequest)

        // 添加授权头
        val authorizedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        val response = chain.proceed(authorizedRequest)

        // 只有网关明确报告访问令牌无效时才刷新。Trakt 未连接、上游 401 等业务错误
        // 不应触发设备会话轮换，否则并发请求会把 refresh token 判定为重放。
        if (response.code == 401 && isAccessTokenFailure(response)) {
            response.close()
            // 同步刷新（runBlocking，仅在拦截器内）
            val refreshed = kotlinx.coroutines.runBlocking {
                authManager.refreshIfNeeded(token)
            }
            if (refreshed) {
                val newToken = tokenStorage.getCachedAccessToken() ?: return chain.proceed(authorizedRequest)
                val retryRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
                return chain.proceed(retryRequest)
            }
        }

        return response
    }

    private fun isAccessTokenFailure(response: Response): Boolean {
        val body = runCatching {
            json.decodeFromString<GatewayResponse<JsonElement>>(
                response.peekBody(MAX_ERROR_BODY_BYTES).string()
            )
        }.getOrNull()
        return body?.code == "INVALID_TOKEN" || body?.code == "TOKEN_EXPIRED"
    }

    private companion object {
        const val MAX_ERROR_BODY_BYTES = 16 * 1024L
    }
}
