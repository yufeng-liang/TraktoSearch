package com.tracktosearch.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionButtonRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `点击 action 触发 onClick`() {
        var clicked = false
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(Icons.Rounded.Bookmark, "想看", false, true, false, false, { clicked = true })
                )
            )
        }
        composeRule.onNodeWithText("想看").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isTrue()
    }

    @Test
    fun `enabled_false 时按钮禁用`() {
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(Icons.Rounded.Bookmark, "禁用按钮", false, false, false, false, {})
                )
            )
        }
        composeRule.onNodeWithText("禁用按钮").assertIsNotEnabled()
    }

    @Test
    fun `isLoading_true 时显示加载态`() {
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(Icons.Rounded.Bookmark, "加载中", false, true, true, false, {})
                )
            )
        }
        composeRule.waitForIdle()
        // loading 状态会有 CircularProgressIndicator 替代 Icon，label 仍然显示，验证不崩溃
        composeRule.onNodeWithText("加载中").assertIsDisplayed()
    }

    @Test
    fun `禁用按钮点击不触发 onClick`() {
        var clicked = false
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(Icons.Rounded.Bookmark, "禁用", false, false, false, false, { clicked = true })
                )
            )
        }
        composeRule.onNodeWithText("禁用").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isFalse()
    }
}
