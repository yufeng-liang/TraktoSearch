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
class SectionHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `title 正常显示`() {
        composeRule.setContent {
            SectionHeader(title = "热门电影")
        }
        composeRule.onNodeWithText("热门电影").assertIsDisplayed()
    }

    @Test
    fun `无 actionText 时不显示操作按钮`() {
        composeRule.setContent {
            SectionHeader(title = "标题", actionText = null)
        }
        composeRule.waitForIdle()
        // 没有 actionText 时不应有操作按钮
        // 验证标题仍然显示
        composeRule.onNodeWithText("标题").assertIsDisplayed()
    }

    @Test
    fun `有 actionText 时显示操作按钮`() {
        composeRule.setContent {
            SectionHeader(title = "标题", actionText = "更多", onActionClick = {})
        }
        composeRule.onNodeWithText("更多").assertIsDisplayed()
    }

    @Test
    fun `点击 actionText 触发回调`() {
        var clicked = false
        composeRule.setContent {
            SectionHeader(title = "标题", actionText = "查看全部", onActionClick = { clicked = true })
        }
        composeRule.onNodeWithText("查看全部").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isTrue()
    }
}
