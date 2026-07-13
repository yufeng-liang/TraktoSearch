package com.tracktosearch.data.remote.config

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * API key 注入拦截器（不做重试，避免与 RetryInterceptor 产生乘法效应）。
 *
 * 工作流程:
 * 1. 从 ApiKeyProvider 取当前 key,注入到请求头
 * 2. 收到 403 响应 → 标记当前 key 失效,切到下一个 key 再试一次
 * 3. 429 交给 RetryInterceptor 处理(它有 Retry-After 感知)
 * 4. 401 交给 OkHttp Authenticator 处理(如 TraktAuthenticator 刷新 token)
 *
 * 不做 while 循环重试,避免与后续 RetryInterceptor 产生乘法效应
 * (旧实现: ApiKeyInterceptor 最多 4 次 × RetryInterceptor 最多 3 次 = 12 次请求)。
 *
 * @param headerName 请求头名(如 "Authorization" / "trakt-api-key" / "X-API-Key")
 * @param headerValueTemplate 把 key 转成请求头值的函数(如 { key -> "Bearer $key" })
 */
class ApiKeyInterceptor @Inject constructor(
    private val apiKeyProvider: ApiKeyProvider,
    private val headerName: String,
    private val headerValueTemplate: (String) -> String,
    private val maxRetries: Int = 3
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val currentKey = apiKeyProvider.pickKey()
        val request = chain.request().newBuilder()
            .header(headerName, headerValueTemplate(currentKey))
            .build()
        val response = chain.proceed(request)

        // 仅 403 → 切 key 重试一次(403 = API key 无效/权限不足)
        // 401 交给 OkHttp Authenticator 处理(token 刷新,不是 key 问题)
        // 429 交给 RetryInterceptor 处理(它有 Retry-After 感知)
        if (response.code == 403) {
            response.close()
            apiKeyProvider.markAndRotate(currentKey, response.code)
            val nextKey = apiKeyProvider.pickKey()
            // 有其他 key 可换才重试，避免用同一失效 key 再发一次必然 403 的请求
            if (nextKey == currentKey) {
                return response
            }
            val retryRequest = chain.request().newBuilder()
                .header(headerName, headerValueTemplate(nextKey))
                .build()
            return chain.proceed(retryRequest)
        }

        return response
    }
}
