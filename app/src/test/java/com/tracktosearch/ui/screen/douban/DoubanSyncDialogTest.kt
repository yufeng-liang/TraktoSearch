package com.tracktosearch.ui.screen.douban

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.DoubanFailureExporter
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanSyncProgress
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DoubanSyncDialog 真实 Compose UI 渲染测试。
 *
 * 不再验证 FakeSyncProgressHolder 字段赋值，而是真实渲染 [DoubanSyncDialog] Composable，
 * 通过 `onNodeWithText` 验证按钮文案/可见性/启用状态，通过 `performClick` 验证点击回调。
 *
 * 通过 mockk 构造可控的 [DoubanSyncManager]，让 `progress` 返回 [MutableStateFlow]，
 * 再传入真实的 [DoubanSyncViewModel]（绕过 hiltViewModel）。
 *
 * Robolectric 默认加载 values/（英文），故使用英文文案断言：
 * - "Background" / "Cancel" / "Cancelling..." / "Sync Complete" / "Re-login" / "Login Trakt"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanSyncDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var progressFlow: MutableStateFlow<DoubanSyncProgress>
    private lateinit var mockManager: DoubanSyncManager
    private lateinit var mockExporter: DoubanFailureExporter
    private lateinit var viewModel: DoubanSyncViewModel

    @Before
    fun setup() {
        progressFlow = MutableStateFlow(DoubanSyncProgress())
        mockManager = mockk(relaxed = true)
        every { mockManager.progress } returns progressFlow
        mockExporter = mockk(relaxed = true)
        viewModel = DoubanSyncViewModel(mockManager, mockExporter)
    }

    /** 推送新的进度状态到 flow */
    private fun emit(progress: DoubanSyncProgress) {
        progressFlow.value = progress
    }

    /** 渲染 Dialog，回调默认空实现；onBackground 走 Dialog 默认（= onDismiss） */
    private fun setContent(
        onDismiss: () -> Unit = {},
        onRelogin: (() -> Unit)? = null,
        onTraktLogin: (() -> Unit)? = null
    ) {
        composeRule.setContent {
            MaterialTheme {
                DoubanSyncDialog(
                    onDismiss = onDismiss,
                    onRelogin = onRelogin,
                    onTraktLogin = onTraktLogin,
                    viewModel = viewModel
                )
            }
        }
    }

    // ==================== 运行中状态 ====================

    @Test
    fun `运行中显示转后台和取消按钮`() {
        emit(DoubanSyncProgress(isRunning = true, current = 5, total = 100, phase = "Syncing"))
        setContent()
        composeRule.onNodeWithText("Background").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `运行中点击取消调用viewModel_cancel`() {
        emit(DoubanSyncProgress(isRunning = true, current = 5, total = 100, phase = "Syncing"))
        setContent()
        composeRule.onNodeWithText("Cancel").performClick()
        verify { mockManager.cancel() }
    }

    @Test
    fun `正在取消时转后台和取消按钮被禁用`() {
        emit(DoubanSyncProgress(isRunning = true, isCancelling = true, phase = "正在取消..."))
        setContent()
        composeRule.onNodeWithText("Background").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancelling...").assertIsNotEnabled()
    }

    @Test
    fun `正在取消时取消按钮文案为正在取消`() {
        emit(DoubanSyncProgress(isRunning = true, isCancelling = true, phase = "正在取消..."))
        setContent()
        composeRule.onNodeWithText("Cancelling...").assertIsDisplayed()
    }

    // ==================== 完成状态 ====================

    @Test
    fun `完成状态显示完成按钮`() {
        emit(DoubanSyncProgress(isComplete = true, phase = "Done", successCount = 50, failedCount = 5))
        setContent()
        composeRule.onNodeWithText("Sync Complete").assertIsDisplayed()
    }

    @Test
    fun `完成状态点击完成调用onDismiss`() {
        emit(DoubanSyncProgress(isComplete = true, phase = "Done", successCount = 50, failedCount = 5))
        var dismissed = false
        setContent(onDismiss = { dismissed = true })
        composeRule.onNodeWithText("Sync Complete").performClick()
        assertThat(dismissed).isTrue()
    }

    // ==================== Cookie 过期状态 ====================

    @Test
    fun `cookieExpired状态显示重新登录按钮`() {
        emit(DoubanSyncProgress(isComplete = true, cookieExpired = true, phase = "Cookie expired"))
        setContent(onRelogin = {})
        composeRule.onNodeWithText("Re-login").assertIsDisplayed()
    }

    @Test
    fun `cookieExpired点击重新登录调用onRelogin`() {
        emit(DoubanSyncProgress(isComplete = true, cookieExpired = true, phase = "Cookie expired"))
        var reloginCalled = false
        setContent(onRelogin = { reloginCalled = true })
        composeRule.onNodeWithText("Re-login").performClick()
        assertThat(reloginCalled).isTrue()
    }

    // ==================== Trakt 未登录状态 ====================

    @Test
    fun `未登录Trakt状态显示登录Trakt按钮`() {
        emit(DoubanSyncProgress(isComplete = true, phase = "未登录 Trakt,请先登录"))
        setContent(onTraktLogin = {})
        composeRule.onNodeWithText("Login Trakt").assertIsDisplayed()
    }

    // ==================== 文案展示 ====================

    @Test
    fun `运行中显示进度文案`() {
        emit(DoubanSyncProgress(isRunning = true, current = 5, total = 100, phase = "Syncing"))
        setContent()
        // douban_sync_progress_format = "%1$s (%2$d/%3$d)"
        composeRule.onNodeWithText("Syncing (5/100)").assertIsDisplayed()
    }

    @Test
    fun `完成状态显示统计文案`() {
        emit(
            DoubanSyncProgress(
                isComplete = true,
                phase = "Done",
                successCount = 50,
                failedCount = 5,
                skippedCount = 5,
                cacheHitCount = 10
            )
        )
        setContent()
        // douban_sync_summary_format = "Success %1$d · Skipped %2$d · Cached %3$d · Failed %4$d"
        composeRule.onNodeWithText("Success 50 · Skipped 5 · Cached 10 · Failed 5").assertIsDisplayed()
    }
}
