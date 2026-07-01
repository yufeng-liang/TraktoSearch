package com.tracktosearch.data.remote.trakt

import com.tracktosearch.data.local.TokenStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trakt API 401 自动刷新 token 并重试请求。
 *
 * 设计要点：
 * - 用 Mutex single-flight：多个并发请求同时 401 时，只触发一次刷新，其余等待后复用新 token。
 * - 通过 Authorization 头比对识别"请求发出时使用的 token"，避免无效重试。
 * - 限制最多重试 1 次，防止 token 始终无效时无限循环。
 */
@Singleton
class TraktAuthenticator @Inject constructor(
    private val tokenStorage: TokenStorage,
    private val authManager: TraktAuthManager
) : Authenticator {

    private val refreshMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // 防止无限重试：本次请求已经是刷新后的 token 仍然 401，则放弃
        val failedRequest = response.request
        val failedToken = failedRequest.header("Authorization")?.removePrefix("Bearer ")
        val currentToken = tokenStorage.getCachedAccessToken()

        // 如果当前缓存的 token 与失败请求中的 token 不同，说明期间已被其他请求刷新过，直接用新 token 重试
        if (currentToken != null && currentToken != failedToken) {
            return rebuildRequest(failedRequest, currentToken)
        }

        // 否则触发一次刷新（single-flight）
        val newToken = runBlocking {
            refreshMutex.withLock {
                // double-check：等待期间可能已被其他请求刷新
                val latest = tokenStorage.getCachedAccessToken()
                if (latest != null && latest != failedToken) {
                    return@withLock latest
                }
                val result = authManager.refreshAccessToken()
                result.getOrNull()?.access_token
            }
        } ?: return null

        return rebuildRequest(failedRequest, newToken)
    }

    private fun rebuildRequest(original: Request, token: String): Request {
        return original.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
    }
}
