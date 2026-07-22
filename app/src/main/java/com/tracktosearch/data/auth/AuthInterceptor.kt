package com.tracktosearch.data.auth

import com.tracktosearch.data.local.TokenStorage
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
    private val authManager: AuthManager
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

        // 401 → 尝试刷新后重试
        if (response.code == 401) {
            response.close()
            // 同步刷新（runBlocking，仅在拦截器内）
            val refreshed = kotlinx.coroutines.runBlocking {
                authManager.refresh()
            }
            if (refreshed.isSuccess) {
                val newToken = tokenStorage.getCachedAccessToken() ?: return chain.proceed(authorizedRequest)
                val retryRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
                return chain.proceed(retryRequest)
            }
        }

        return response
    }
}
