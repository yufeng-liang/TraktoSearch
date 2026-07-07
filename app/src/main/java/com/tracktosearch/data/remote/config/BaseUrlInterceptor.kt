package com.tracktosearch.data.remote.config

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * BaseUrl 重写拦截器:运行时根据 RemoteConfig 动态重写请求 URL。
 *
 * 替换规则:原 URL 中以 [fallbackUrl] 开头的部分替换为 RemoteConfig 中的 [configKey] 值。
 * 若 RemoteConfig 未配置或未初始化,不重写(用 Retrofit 编译期 baseUrl)。
 *
 * 不自动回退:无法探测 URL 可达性,由 OkHttp retryOnConnectionFailure + RetryInterceptor 处理。
 */
class BaseUrlInterceptor @Inject constructor(
    private val remoteConfig: RemoteConfigProvider,
    private val configKey: String,      // 如 "tmdb.baseUrl"
    private val fallbackUrl: String    // 如 "https://api.tmdb.org/3/"
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val originalUrl = original.url.toString()

        // 仅当原 URL 以 fallbackUrl 开头时才尝试重写
        if (!originalUrl.startsWith(fallbackUrl)) {
            return chain.proceed(original)
        }

        val dynamicUrl = remoteConfig.getOrNull(configKey) ?: fallbackUrl
        if (dynamicUrl == fallbackUrl) {
            return chain.proceed(original)
        }

        val newUrl = originalUrl.replace(fallbackUrl, dynamicUrl)
        val newRequest = original.newBuilder()
            .url(newUrl)
            .build()
        return chain.proceed(newRequest)
    }
}
