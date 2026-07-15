package com.tracktosearch.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RatingBadgeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `rating_8_5 显示一位小数`() {
        composeRule.setContent {
            RatingBadge(rating = 8.5)
        }
        // RatingBadge 实际渲染 "★ 8.5"，用 substring 匹配数字部分避免 unicode 字符问题
        composeRule.onNodeWithText("8.5", substring = true).assertIsDisplayed()
    }

    @Test
    fun `rating_7_0 显示一位小数`() {
        composeRule.setContent {
            RatingBadge(rating = 7.0)
        }
        composeRule.waitForIdle()
        // 7.0 用 "%.1f".format 会得到 "7.0"
        composeRule.onNodeWithText("7.0", substring = true).assertIsDisplayed()
    }

    @Test
    fun `rating_0 显示`() {
        composeRule.setContent {
            RatingBadge(rating = 0.0)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("0.0", substring = true).assertIsDisplayed()
    }
}
