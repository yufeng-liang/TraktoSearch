package com.tracktosearch.data.auth

import com.tracktosearch.data.local.TokenStorage
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * 为授权校验、挑战和刷新请求附加访问令牌。
 * 激活请求必须保持匿名，避免复用设备上残留的旧令牌。
 */
class AuthHeaderInterceptor @Inject constructor(
    private val tokenStorage: TokenStorage
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null ||
            request.url.encodedPath.endsWith("/api/auth/activate")
        ) {
            return chain.proceed(request)
        }

        val token = tokenStorage.getCachedAccessToken()
        if (token.isNullOrBlank()) return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        )
    }
}
