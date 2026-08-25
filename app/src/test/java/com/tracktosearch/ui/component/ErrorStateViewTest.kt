package com.tracktosearch.ui.component

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
    fun `Full 形态显示错误消息`() {
        composeRule.setContent {
            AppErrorState(message = "连接失败")
        }
        composeRule.onNodeWithText("连接失败").assertIsDisplayed()
    }

    @Test
    fun `Full 形态点击重试触发回调`() {
        var retryClicked = false
        composeRule.setContent {
            AppErrorState(message = "错误", onRetry = { retryClicked = true })
        }
        // 重试按钮文案来自 stringResource(R.string.error_retry)，Robolectric 默认 locale 下为 "Retry"
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitForIdle()
        assertThat(retryClicked).isTrue()
    }

    @Test
    fun `Inline 形态显示短文案并可展开错误详情`() {
        composeRule.setContent {
            AppErrorState(
                message = "HTTP 500",
                onRetry = {},
                variant = AppErrorVariant.Inline
            )
        }
        // Inline 只露短文案，原始错误收进详情对话框
        composeRule.onNodeWithText("Load failed").assertIsDisplayed()
        composeRule.onNodeWithText("HTTP 500").assertDoesNotExist()
        composeRule.onNodeWithText("Load failed").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("HTTP 500").assertIsDisplayed()
    }

    @Test
    fun `Inline 形态点击重试触发回调`() {
        var retryClicked = false
        composeRule.setContent {
            AppErrorState(
                message = "错误",
                onRetry = { retryClicked = true },
                variant = AppErrorVariant.Inline
            )
        }
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitForIdle()
        assertThat(retryClicked).isTrue()
    }

    @Test
    fun `Overlay 形态显示错误消息与自定义重试文案`() {
        var retryClicked = false
        composeRule.setContent {
            AppErrorState(
                message = "精灵正在休息",
                onRetry = { retryClicked = true },
                variant = AppErrorVariant.Overlay,
                retryLabel = "再试一次"
            )
        }
        composeRule.onNodeWithText("精灵正在休息").assertIsDisplayed()
        composeRule.onNodeWithText("再试一次").performClick()
        composeRule.waitForIdle()
        assertThat(retryClicked).isTrue()
    }

    @Test
    fun `不可重试的错误不显示重试按钮`() {
        composeRule.setContent {
            AppErrorState(
                message = "配额用完",
                onRetry = {},
                variant = AppErrorVariant.Overlay,
                retryable = false
            )
        }
        composeRule.onNodeWithText("配额用完").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `未传重试回调时不显示重试按钮`() {
        composeRule.setContent {
            AppErrorState(message = "错误", variant = AppErrorVariant.Inline)
        }
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }
}
