package com.tracktosearch.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ErrorStateViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `显示错误消息`() {
        composeRule.setContent {
            ErrorStateView(message = "连接失败")
        }
        composeRule.onNodeWithText("连接失败").assertIsDisplayed()
    }

    @Test
    fun `点击重试触发回调`() {
        var retryClicked = false
        composeRule.setContent {
            ErrorStateView(message = "错误", onRetry = { retryClicked = true })
        }
        // ErrorStateView 重试按钮文案来自 stringResource(R.string.error_retry)，Robolectric 默认 locale 下为 "Retry"
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitForIdle()
        assertThat(retryClicked).isTrue()
    }
}
