package com.tracktosearch.ui.screen.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.ConsistencyCheckResult
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
 * ConsistencyCheckDialog 真实 Compose UI 渲染测试。
 *
 * 不再验证 [ConsistencyCheckResult] 字段赋值，而是真实渲染 [ConsistencyCheckDialog] Composable，
 * 通过 `onNodeWithText` 验证按钮文案/可见性/启用状态，通过 `performClick` 验证点击回调。
 *
 * 通过 mockk 构造可控的 [SettingsViewModel]（relaxed = true，避免 25+ 构造参数），
 * stub `checkProgress` 返回 [MutableStateFlow] 以控制状态。
 *
 * Robolectric 默认加载 values/（英文），故使用英文文案断言：
 * - "Background" / "Cancel" / "Cancelling..." / "Done"
 *
 * Cookie 过期提示为硬编码中文 "豆瓣登录已过期，请重新登录"（不经过 stringResource）。
 *
 * phase 字段故意避开按钮文案（"Checking"/"Stopping"/"Finished"），避免 `onNodeWithText`
 * 命中多个节点；完成态使用非空 phase 避免与 "Done" 按钮文案冲突。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsistencyCheckDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var progressFlow: MutableStateFlow<ConsistencyCheckResult>
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        progressFlow = MutableStateFlow(ConsistencyCheckResult())
        viewModel = mockk(relaxed = true)
        every { viewModel.checkProgress } returns progressFlow
    }

    /** 推送新的进度状态到 flow */
    private fun emit(result: ConsistencyCheckResult) {
        progressFlow.value = result
    }

    /** 渲染 Dialog，回调默认空实现 */
    private fun setContent(
        onDismiss: () -> Unit = {},
        onBackground: () -> Unit = {}
    ) {
        composeRule.setContent {
            MaterialTheme {
                ConsistencyCheckDialog(
                    onDismiss = onDismiss,
                    onBackground = onBackground,
                    viewModel = viewModel
                )
            }
        }
    }

    // ==================== 运行中状态 ====================

    @Test
    fun `运行中显示转后台和取消按钮`() {
        emit(ConsistencyCheckResult(isRunning = true, phase = "Checking", current = 5, total = 100))
        setContent()
        composeRule.onNodeWithText("Background").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `运行中点击取消调用viewModel_cancelConsistencyCheck`() {
        emit(ConsistencyCheckResult(isRunning = true, phase = "Checking", current = 5, total = 100))
        setContent()
        composeRule.onNodeWithText("Cancel").performClick()
        verify { viewModel.cancelConsistencyCheck() }
    }

    @Test
    fun `正在取消时转后台和取消按钮被禁用`() {
        emit(ConsistencyCheckResult(isRunning = true, isCancelling = true, phase = "Stopping"))
        setContent()
        composeRule.onNodeWithText("Background").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancelling...").assertIsNotEnabled()
    }

    @Test
    fun `正在取消时取消按钮文案为正在取消`() {
        emit(ConsistencyCheckResult(isRunning = true, isCancelling = true, phase = "Stopping"))
        setContent()
        composeRule.onNodeWithText("Cancelling...").assertIsDisplayed()
    }

    // ==================== 完成状态 ====================

    @Test
    fun `完成状态显示完成按钮`() {
        emit(ConsistencyCheckResult(isComplete = true, phase = "Finished"))
        setContent()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun `完成状态点击完成调用onDismiss`() {
        emit(ConsistencyCheckResult(isComplete = true, phase = "Finished"))
        var dismissed = false
        setContent(onDismiss = { dismissed = true })
        composeRule.onNodeWithText("Done").performClick()
        assertThat(dismissed).isTrue()
    }

    // ==================== 文案展示 ====================

    @Test
    fun `运行中显示进度文案`() {
        emit(ConsistencyCheckResult(isRunning = true, phase = "Checking", current = 5, total = 100))
        setContent()
        // total > 0 时文案为 "${phase} (${current}/${total})"
        composeRule.onNodeWithText("Checking (5/100)").assertIsDisplayed()
    }

    @Test
    fun `运行中显示子阶段文案`() {
        emit(
            ConsistencyCheckResult(
                isRunning = true,
                phase = "Checking",
                subPhase = "Wish list",
                current = 5,
                total = 100
            )
        )
        setContent()
        // 子阶段文案为 "· ${subPhase}"，用 substring 匹配避免中点字符编码差异
        composeRule.onNodeWithText("Wish list", substring = true).assertIsDisplayed()
    }

    @Test
    fun `运行中显示当前条目标题`() {
        emit(
            ConsistencyCheckResult(
                isRunning = true,
                phase = "Checking",
                currentTitle = "Test Movie",
                current = 5,
                total = 100
            )
        )
        setContent()
        composeRule.onNodeWithText("Test Movie").assertIsDisplayed()
    }

    @Test
    fun `cookie过期显示重新登录提示`() {
        emit(ConsistencyCheckResult(isComplete = true, cookieExpired = true, phase = "Finished"))
        setContent()
        // 硬编码中文，不经过 stringResource
        composeRule.onNodeWithText("豆瓣登录已过期，请重新登录").assertIsDisplayed()
    }

    @Test
    fun `完成状态显示统计文案`() {
        emit(
            ConsistencyCheckResult(
                isComplete = true,
                phase = "Finished",
                totalChecked = 100,
                conflictsFound = 10,
                traktUpdated = 7,
                doubanUpdated = 3,
                errors = 2
            )
        )
        setContent()
        // consistency_check_summary = "Checked %1$d items, found %2$d conflicts, Trakt updated %3$d, Douban updated %4$d, errors %5$d"
        composeRule.onNodeWithText(
            "Checked 100 items, found 10 conflicts, Trakt updated 7, Douban updated 3, errors 2"
        ).assertIsDisplayed()
    }
}
