package com.tracktosearch.ui.component

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoadingViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `Inline 形态显示不确定进度指示器`() {
        composeRule.setContent {
            AppLoadingState(variant = AppLoadingVariant.Inline)
        }
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertExists()
    }

    @Test
    fun `Skeleton 形态渲染调用方传入的骨架`() {
        composeRule.setContent {
            AppLoadingState(
                variant = AppLoadingVariant.Skeleton,
                skeleton = { Text("骨架占位") }
            )
        }
        composeRule.onNodeWithText("骨架占位").assertIsDisplayed()
    }

    @Test
    fun `LoadingView 自定义消息显示`() {
        composeRule.setContent {
            LoadingView(message = "加载中...")
        }
        composeRule.onNodeWithText("加载中...").assertIsDisplayed()
    }
}
