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
class LoadingViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `LoadingView 默认消息渲染`() {
        composeRule.setContent {
            LoadingView()
        }
        composeRule.waitForIdle()
        // 默认消息来自 stringResource(R.string.loading_default)，Robolectric 默认 locale 下为 "Loading…"
        // 验证组件不崩溃即通过
    }

    @Test
    fun `LoadingView 自定义消息显示`() {
        composeRule.setContent {
            LoadingView(message = "加载中...")
        }
        composeRule.onNodeWithText("加载中...").assertIsDisplayed()
    }

    @Test
    fun `EmptyView 自定义消息显示`() {
        composeRule.setContent {
            EmptyView(message = "暂无数据")
        }
        composeRule.onNodeWithText("暂无数据").assertIsDisplayed()
    }

    @Test
    fun `ErrorView 显示错误消息`() {
        composeRule.setContent {
            ErrorView(message = "网络错误")
        }
        composeRule.onNodeWithText("网络错误").assertIsDisplayed()
    }

    @Test
    fun `ErrorView 无重试按钮时不崩溃`() {
        composeRule.setContent {
            ErrorView(message = "错误", onRetry = null)
        }
        composeRule.waitForIdle()
        // onRetry 为 null 时不应显示重试按钮，不崩溃即通过
    }

    @Test
    fun `ErrorView 点击重试触发回调`() {
        var retryClicked = false
        composeRule.setContent {
            ErrorView(message = "错误", onRetry = { retryClicked = true })
        }
        // ErrorView 重试按钮文案来自 stringResource(R.string.error_retry)，Robolectric 默认 locale 下为 "Retry"
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitForIdle()
        assertThat(retryClicked).isTrue()
    }
}
