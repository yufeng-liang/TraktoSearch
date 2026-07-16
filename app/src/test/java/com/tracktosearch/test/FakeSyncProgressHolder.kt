package com.tracktosearch.test

import com.tracktosearch.data.repository.DoubanSyncProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 测试辅助：可控制的豆瓣同步进度 StateFlow。
 * 用于在 Robolectric/插桩测试中手动推送进度状态变化。
 */
class FakeSyncProgressHolder {
    private val _progress = MutableStateFlow(DoubanSyncProgress())
    val progress: StateFlow<DoubanSyncProgress> = _progress.asStateFlow()

    fun emitIdle() {
        _progress.value = DoubanSyncProgress()
    }

    fun emitRunning(current: Int = 0, total: Int = 100, phase: String = "同步中", currentTitle: String? = null) {
        _progress.value = _progress.value.copy(
            isRunning = true,
            isComplete = false,
            isCancelling = false,
            current = current,
            total = total,
            phase = phase,
            currentTitle = currentTitle,
            startTimeMs = System.currentTimeMillis()
        )
    }

    fun emitCancelling() {
        _progress.value = _progress.value.copy(
            isCancelling = true,
            phase = "正在取消...",
            delayInfo = null
        )
    }

    fun emitComplete(success: Int = 0, failed: Int = 0, skipped: Int = 0, cacheHit: Int = 0) {
        _progress.value = _progress.value.copy(
            isRunning = false,
            isComplete = true,
            isCancelling = false,
            successCount = success,
            failedCount = failed,
            skippedCount = skipped,
            cacheHitCount = cacheHit
        )
    }

    fun emitCookieExpired() {
        _progress.value = _progress.value.copy(
            isRunning = false,
            isComplete = true,
            cookieExpired = true,
            phase = "豆瓣登录已过期"
        )
    }

    fun emitTraktNotLoggedIn() {
        _progress.value = _progress.value.copy(
            isRunning = false,
            isComplete = true,
            phase = "未登录 Trakt,请先登录"
        )
    }
}
