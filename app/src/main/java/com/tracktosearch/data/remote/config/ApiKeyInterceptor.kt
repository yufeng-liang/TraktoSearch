package com.tracktosearch.data.remote.config

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * API key 注入 + 失败轮换拦截器。
 *
 * 工作流程:
 * 1. 从 ApiKeyProvider 取当前 key,注入到请求头
 * 2. 收到 429/401/403 响应 → 标记当前 key 失效 → 切下一个 key 重试
 * 3. 单请求最多重试 [maxRetries] 次,避免死循环
 *
 * 应放在 RetryInterceptor 之前,这样 RetryInterceptor 重试时本拦截器能感知到上一次失败状态码并切 key。
 *
 * @param headerName 请求头名(如 "Authorization" / "trakt-api-key" / "X-API-Key")
 * @param headerValueTemplate 把 key 转成请求头值的函数(如 { key -> "Bearer $key" })
 * @param maxRetries 单请求最大重试次数(默认 3,覆盖 3-key 池)
 */
class ApiKeyInterceptor @Inject constructor(
    private val apiKeyProvider: ApiKeyProvider,
    private val headerName: String,
    private val headerValueTemplate: (String) -> String,
    private val maxRetries: Int = 3
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var currentKey = apiKeyProvider.pickKey()
        var retryCount = 0

        while (true) {
            val request = chain.request().newBuilder()
                .header(headerName, headerValueTemplate(currentKey))
                .build()
            val response = chain.proceed(request)

            // 429 / 401 / 403 → 切 key 重试
            if (response.code == 429 || response.code == 401 || response.code == 403) {
                response.close()
                if (retryCount >= maxRetries) {
                    // 重试上限,不再切 key,直接用当前 key 重发一次让上层处理
                    val finalRequest = chain.request().newBuilder()
                        .header(headerName, headerValueTemplate(currentKey))
                        .build()
                    return chain.proceed(finalRequest)
                }
                apiKeyProvider.markAndRotate(currentKey, response.code)
                val nextKey = apiKeyProvider.pickKey()
                if (nextKey == currentKey) {
                    // 没有其他 key 可换,用当前 key 重发(让上层报错)
                    val finalRequest = chain.request().newBuilder()
                        .header(headerName, headerValueTemplate(currentKey))
                        .build()
                    return chain.proceed(finalRequest)
                }
                currentKey = nextKey
                retryCount++
            } else {
                return response
            }
        }
    }
}
