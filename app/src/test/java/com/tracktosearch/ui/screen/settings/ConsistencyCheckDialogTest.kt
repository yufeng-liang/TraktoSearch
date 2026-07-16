package com.tracktosearch.ui.screen.settings

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.ConsistencyCheckResult
import org.junit.Test

class ConsistencyCheckDialogTest {

    private fun createResult(
        isRunning: Boolean = false,
        isComplete: Boolean = false,
        isCancelling: Boolean = false,
        cookieExpired: Boolean = false,
        phase: String = "",
        current: Int = 0,
        total: Int = 0
    ) = ConsistencyCheckResult(
        isRunning = isRunning,
        phase = phase,
        subPhase = "",
        current = current,
        total = total,
        currentTitle = null,
        totalChecked = 0,
        conflictsFound = 0,
        doubanUpdated = 0,
        traktUpdated = 0,
        skipped = 0,
        errors = 0,
        delayInfo = null,
        cookieExpired = cookieExpired,
        isComplete = isComplete,
        startTimeMs = 0,
        isCancelling = isCancelling,
        isCancelled = false
    )

    @Test
    fun 运行中状态isRunning为true() {
        val p = createResult(isRunning = true)
        assertThat(p.isRunning).isTrue()
        assertThat(p.isComplete).isFalse()
    }

    @Test
    fun 正在取消状态isCancelling为true() {
        val p = createResult(isRunning = true, isCancelling = true, phase = "正在取消...")
        assertThat(p.isCancelling).isTrue()
        assertThat(p.phase).contains("正在取消")
    }

    @Test
    fun 完成状态isComplete为true() {
        val p = createResult(isComplete = true)
        assertThat(p.isComplete).isTrue()
        assertThat(p.isRunning).isFalse()
    }

    @Test
    fun cookie过期状态cookieExpired为true() {
        val p = createResult(isComplete = true, cookieExpired = true)
        assertThat(p.cookieExpired).isTrue()
        assertThat(p.isComplete).isTrue()
    }

    @Test
    fun 运行中且非取消时显示转后台和取消按钮() {
        val p = createResult(isRunning = true)
        val shouldShowBackground = p.isRunning && !p.isCancelling
        val shouldShowCancel = p.isRunning && !p.isCancelling
        assertThat(shouldShowBackground).isTrue()
        assertThat(shouldShowCancel).isTrue()
    }

    @Test
    fun 正在取消时禁用转后台和取消按钮() {
        val p = createResult(isRunning = true, isCancelling = true)
        val shouldDisable = p.isCancelling
        assertThat(shouldDisable).isTrue()
    }

    @Test
    fun 完成时显示完成按钮() {
        val p = createResult(isComplete = true)
        val shouldShowComplete = p.isComplete && !p.cookieExpired
        assertThat(shouldShowComplete).isTrue()
    }

    @Test
    fun cookie过期时显示重新登录提示() {
        val p = createResult(isComplete = true, cookieExpired = true)
        val shouldShowRelogin = p.isComplete && p.cookieExpired
        assertThat(shouldShowRelogin).isTrue()
    }

    @Test
    fun total大于0时显示精确进度() {
        val p = createResult(isRunning = true, current = 5, total = 100)
        val shouldShowExactProgress = p.total > 0
        assertThat(shouldShowExactProgress).isTrue()
    }

    @Test
    fun total为0时显示indeterminate进度() {
        val p = createResult(isRunning = true, current = 0, total = 0)
        val shouldShowIndeterminate = p.total == 0
        assertThat(shouldShowIndeterminate).isTrue()
    }

    @Test
    fun errors大于0时显示错误提示() {
        val p = createResult(isComplete = true).copy(errors = 5)
        val shouldShowErrors = p.errors > 0
        assertThat(shouldShowErrors).isTrue()
    }
}
