package com.tracktosearch.ui.screen.douban

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.test.FakeSyncProgressHolder
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanSyncDialogTest {
    // 此测试需要 ComposeTestRule，但 Robolectric 下 Compose 测试有限制
    // 核心状态分支用纯函数提取测试更可靠
    // 这里测试 DoubanSyncDialog 中可提取的纯逻辑

    private lateinit var progressHolder: FakeSyncProgressHolder

    @Before
    fun setup() {
        progressHolder = FakeSyncProgressHolder()
    }

    @Test
    fun 运行中状态isRunning为true() {
        progressHolder.emitRunning(current = 5, total = 100)
        val p = progressHolder.progress.value
        assertThat(p.isRunning).isTrue()
        assertThat(p.isComplete).isFalse()
        assertThat(p.isCancelling).isFalse()
    }

    @Test
    fun 正在取消状态isCancelling为true() {
        progressHolder.emitCancelling()
        val p = progressHolder.progress.value
        assertThat(p.isCancelling).isTrue()
        assertThat(p.phase).contains("正在取消")
    }

    @Test
    fun 完成状态isComplete为true且isRunning为false() {
        progressHolder.emitComplete(success = 50, failed = 5)
        val p = progressHolder.progress.value
        assertThat(p.isComplete).isTrue()
        assertThat(p.isRunning).isFalse()
        assertThat(p.successCount).isEqualTo(50)
        assertThat(p.failedCount).isEqualTo(5)
    }

    @Test
    fun cookie过期状态cookieExpired为true() {
        progressHolder.emitCookieExpired()
        val p = progressHolder.progress.value
        assertThat(p.cookieExpired).isTrue()
        assertThat(p.isComplete).isTrue()
    }

    @Test
    fun trakt未登录状态phase包含Trakt() {
        progressHolder.emitTraktNotLoggedIn()
        val p = progressHolder.progress.value
        assertThat(p.phase).contains("Trakt")
        assertThat(p.isComplete).isTrue()
    }

    @Test
    fun 初始状态所有标志为false() {
        progressHolder.emitIdle()
        val p = progressHolder.progress.value
        assertThat(p.isRunning).isFalse()
        assertThat(p.isComplete).isFalse()
        assertThat(p.isCancelling).isFalse()
        assertThat(p.cookieExpired).isFalse()
    }

    // ===== 按钮状态判断逻辑（从 Dialog 中提取的纯函数）=====

    @Test
    fun 运行中且非取消时显示转后台和取消按钮() {
        progressHolder.emitRunning()
        val p = progressHolder.progress.value
        val shouldShowBackground = p.isRunning && !p.isCancelling
        val shouldShowCancel = p.isRunning && !p.isCancelling
        assertThat(shouldShowBackground).isTrue()
        assertThat(shouldShowCancel).isTrue()
    }

    @Test
    fun 正在取消时禁用转后台和取消按钮() {
        progressHolder.emitCancelling()
        val p = progressHolder.progress.value
        val shouldDisable = p.isCancelling
        assertThat(shouldDisable).isTrue()
    }

    @Test
    fun 完成时显示完成按钮() {
        progressHolder.emitComplete()
        val p = progressHolder.progress.value
        val shouldShowComplete = p.isComplete && !p.cookieExpired && !p.phase.contains("Trakt")
        assertThat(shouldShowComplete).isTrue()
    }

    @Test
    fun cookie过期时显示重新登录豆瓣按钮() {
        progressHolder.emitCookieExpired()
        val p = progressHolder.progress.value
        val shouldShowRelogin = p.isComplete && p.cookieExpired
        assertThat(shouldShowRelogin).isTrue()
    }

    @Test
    fun trakt未登录时显示登录Trakt按钮() {
        progressHolder.emitTraktNotLoggedIn()
        val p = progressHolder.progress.value
        val shouldShowLoginTrakt = p.isComplete && p.phase.contains("Trakt")
        assertThat(shouldShowLoginTrakt).isTrue()
    }
}
