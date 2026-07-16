package com.tracktosearch.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarqueeTextTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `短文本正常显示`() {
        composeRule.setContent {
            MarqueeText(text = "短文本")
        }
        composeRule.onNodeWithText("短文本").assertIsDisplayed()
    }

    @Test
    fun `长文本正常渲染不崩溃`() {
        val longText = "这是一个非常非常非常非常非常非常非常非常非常非常非常非常非常非常长的文本".repeat(5)
        composeRule.setContent {
            MarqueeText(text = longText)
        }
        composeRule.waitForIdle()
        // 长文本应触发跑马灯效果，但不应崩溃。basicMarquee 不改变文本内容，只影响显示滚动
        composeRule.onNodeWithText(longText).assertIsDisplayed()
    }
}
