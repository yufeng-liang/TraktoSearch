package com.tracktosearch.ui.screen.douban

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.DoubanSyncFailure
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.data.repository.DoubanSyncLoginTarget
import com.tracktosearch.data.repository.DoubanSyncPreviewItem
import com.tracktosearch.data.repository.DoubanSyncQueueItem
import com.tracktosearch.data.repository.DoubanSyncStage
import com.tracktosearch.data.repository.DoubanSyncSubStage
import com.tracktosearch.data.repository.FailureReason
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
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
@Config(sdk = [33], application = android.app.Application::class)
class DoubanSyncDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var progressFlow: MutableStateFlow<DoubanSyncProgress>
    private lateinit var mockManager: DoubanSyncManager
    private lateinit var viewModel: DoubanSyncViewModel

    @Before
    fun setup() {
        progressFlow = MutableStateFlow(DoubanSyncProgress())
        mockManager = mockk(relaxed = true)
        every { mockManager.progress } returns progressFlow
        viewModel = DoubanSyncViewModel(mockManager)
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
    fun `运行中点击取消调用viewModel_cancel`() {
        emit(DoubanSyncProgress(
            isRunning = true,
            current = 5,
            total = 100,
            stage = DoubanSyncStage.FETCHING_LIST,
            subStage = DoubanSyncSubStage.FETCHING_WISH_LIST
        ))
        setContent()
        composeRule.onNodeWithText("Cancel").performClick()
        verify { mockManager.cancel() }
    }

    @Test
    fun `正在取消时转后台和取消按钮被禁用`() {
        emit(DoubanSyncProgress(
            isRunning = true,
            isCancelling = true,
            stage = DoubanSyncStage.CANCELLING
        ))
        setContent()
        composeRule.onNodeWithText("Background").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancelling...").assertIsNotEnabled()
    }

    // ==================== 完成状态 ====================

    @Test
    fun `完成状态点击完成调用onDismiss`() {
        emit(DoubanSyncProgress(
            isComplete = true,
            stage = DoubanSyncStage.COMPLETED,
            successCount = 50,
            failedCount = 5
        ))
        var dismissed = false
        setContent(onDismiss = { dismissed = true })
        composeRule.onNodeWithText("Sync Complete").performClick()
        assertThat(dismissed).isTrue()
    }

    // ==================== Cookie 过期状态 ====================

    @Test
    fun `cookieExpired点击重新登录调用onRelogin`() {
        emit(DoubanSyncProgress(
            isComplete = true,
            stage = DoubanSyncStage.LOGIN_REQUIRED,
            loginTarget = DoubanSyncLoginTarget.DOUBAN,
            cookieExpired = true
        ))
        var reloginCalled = false
        setContent(onRelogin = { reloginCalled = true })
        composeRule.onNodeWithText("Re-login").performClick()
        assertThat(reloginCalled).isTrue()
    }

    // ==================== Trakt 未登录状态 ====================

    @Test
    fun `未登录Trakt点击登录调用onTraktLogin`() {
        emit(DoubanSyncProgress(
            isComplete = true,
            stage = DoubanSyncStage.LOGIN_REQUIRED,
            loginTarget = DoubanSyncLoginTarget.TRAKT
        ))
        var loginCalled = false
        setContent(onTraktLogin = { loginCalled = true })
        composeRule.onNodeWithText("Login Trakt").performClick()
        assertThat(loginCalled).isTrue()
    }

    // ==================== 文案展示 ====================

    @Test
    fun `解析阶段显示处理和待处理而不显示最近获取`() {
        emit(
            DoubanSyncProgress(
                isRunning = true,
                current = 1,
                total = 7,
                stage = DoubanSyncStage.PARSING_DATA,
                subStage = DoubanSyncSubStage.FETCHING_DETAIL,
                recentItems = listOf(preview("old", "Old recently fetched")),
                processingItems = listOf(
                    DoubanSyncQueueItem("processing-1", "Processing one"),
                    DoubanSyncQueueItem("processing-2", "Processing two"),
                    DoubanSyncQueueItem("processing-3", "Processing three"),
                    DoubanSyncQueueItem("processing-4", "Processing four")
                ),
                pendingItems = (1..6).map { DoubanSyncQueueItem("pending-$it", "Pending $it") },
                pendingItemCount = 7
            )
        )
        setContent()

        composeRule.onNodeWithText("Processing").assertIsDisplayed()
        composeRule.onNodeWithText("Processing one").assertIsDisplayed()
        composeRule.onNodeWithText("Processing three").assertIsDisplayed()
        composeRule.onAllNodesWithText("Processing four").assertCountEquals(0)
        composeRule.onNodeWithText("Pending").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pending 5").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Pending 6").assertCountEquals(0)
        composeRule.onNodeWithText("2 more pending").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Recently fetched").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old recently fetched").assertCountEquals(0)
    }

    @Test
    fun `写入阶段隐藏所有旧列表和队列条目`() {
        emit(
            DoubanSyncProgress(
                isRunning = true,
                stage = DoubanSyncStage.UPDATING_LIST,
                subStage = DoubanSyncSubStage.WRITING_TARGET,
                recentItems = listOf(preview("recent", "Old recent movie")),
                processingItems = listOf(DoubanSyncQueueItem("processing", "Old processing movie")),
                pendingItems = listOf(DoubanSyncQueueItem("pending", "Old pending movie")),
                pendingItemCount = 1
            )
        )
        setContent()

        composeRule.onNodeWithText("Updating list").assertIsDisplayed()
        composeRule.onNodeWithText("Writing Trakt").assertIsDisplayed()
        composeRule.onAllNodesWithText("Recently fetched").assertCountEquals(0)
        composeRule.onAllNodesWithText("Processing").assertCountEquals(0)
        composeRule.onAllNodesWithText("Pending").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old recent movie").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old processing movie").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old pending movie").assertCountEquals(0)
    }

    @Test
    fun `上传阶段显示专用阶段文案并隐藏所有旧列表和队列条目`() {
        emit(
            DoubanSyncProgress(
                isRunning = true,
                current = 12,
                total = 461,
                stage = DoubanSyncStage.UPLOADING,
                subStage = DoubanSyncSubStage.UPLOADING_DETAILS,
                etaSeconds = 120,
                recentItems = listOf(preview("recent", "Old recent movie")),
                processingItems = listOf(DoubanSyncQueueItem("processing", "Old processing movie")),
                pendingItems = listOf(DoubanSyncQueueItem("pending", "Old pending movie")),
                pendingItemCount = 1
            )
        )
        setContent()

        composeRule.onNodeWithText("Uploading data").assertIsDisplayed()
        composeRule.onAllNodesWithText("12/461", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Uploading details").assertIsDisplayed()
        composeRule.onNodeWithText("about 2 min remaining").assertIsDisplayed()
        composeRule.onAllNodesWithText("Recently fetched").assertCountEquals(0)
        composeRule.onAllNodesWithText("Processing").assertCountEquals(0)
        composeRule.onAllNodesWithText("Pending").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old recent movie").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old processing movie").assertCountEquals(0)
        composeRule.onAllNodesWithText("Old pending movie").assertCountEquals(0)
    }

    private fun preview(id: String, title: String) = DoubanSyncPreviewItem(
        doubanId = id,
        title = title,
        status = DoubanMarkStatus.WISH,
        rating = 4,
        markedAt = "2024-01-01"
    )

    @Test
    fun `同步异常结果显示错误详情`() {
        emit(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.FAILED,
                errorMessage = "Network unavailable"
            )
        )
        setContent()
        composeRule.onNodeWithText("Error: Network error", substring = true).assertIsDisplayed()
    }

    // ==================== 失败项展示 ====================

}
