package com.tracktosearch.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 串行化刷新令牌，避免并发请求使用同一个旧 refresh token 触发重放保护。
 */
internal class AuthRefreshCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun refreshIfNeeded(
        failedAccessToken: String,
        currentAccessToken: () -> String?,
        refresh: suspend () -> Boolean
    ): Boolean = mutex.withLock {
        val latestAccessToken = currentAccessToken()
        if (!latestAccessToken.isNullOrBlank() && latestAccessToken != failedAccessToken) {
            true
        } else {
            refresh()
        }
    }
}
