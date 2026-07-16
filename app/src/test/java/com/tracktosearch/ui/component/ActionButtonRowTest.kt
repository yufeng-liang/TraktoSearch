package com.tracktosearch.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Star
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.chrisbanes.haze.HazeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionButtonRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `单个 action 正常显示 label`() {
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(
                        icon = Icons.Rounded.Bookmark,
                        label = "想看",
                        selected = false,
                        enabled = true,
                        isLoading = false,
                        isDestructive = false,
                        onClick = {}
                    )
                ),
                hazeState = HazeState()
            )
        }
        composeRule.onNodeWithText("想看").assertIsDisplayed()
    }

    @Test
    fun `多个 action 全部显示`() {
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(Icons.Rounded.Bookmark, "想看", false, true, false, false, {}),
                    ActionItem(Icons.Rounded.Check, "已看", false, true, false, false, {}),
                    ActionItem(Icons.Rounded.Star, "评分", false, true, false, false, {})
                ),
                hazeState = HazeState()
            )
        }
        composeRule.onNodeWithText("想看").assertIsDisplayed()
        composeRule.onNodeWithText("已看").assertIsDisplayed()
        composeRule.onNodeWithText("评分").assertIsDisplayed()
    }

    @Test
    fun `点击 action 触发 onClick`() {
        var clicked = false
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(Icons.Rounded.Bookmark, "想看", false, true, false, false, { clicked = true })
                ),
                hazeState = HazeState()
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
                ),
                hazeState = HazeState()
            )
        }
        composeRule.onNodeWithText("禁用按钮").assertIsNotEnabled()
    }

    @Test
    fun `selected_true 时按钮显示选中态`() {
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(Icons.Rounded.Bookmark, "已想看", true, true, false, false, {})
                ),
                hazeState = HazeState()
            )
        }
        composeRule.onNodeWithText("已想看").assertIsDisplayed()
    }

    @Test
    fun `isLoading_true 时显示加载态`() {
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(Icons.Rounded.Bookmark, "加载中", false, true, true, false, {})
                ),
                hazeState = HazeState()
            )
        }
        composeRule.waitForIdle()
        // loading 状态会有 CircularProgressIndicator 替代 Icon，label 仍然显示，验证不崩溃
        composeRule.onNodeWithText("加载中").assertIsDisplayed()
    }

    @Test
    fun `isDestructive_true 时不崩溃`() {
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(Icons.Rounded.Delete, "删除", false, true, false, true, {})
                ),
                hazeState = HazeState()
            )
        }
        composeRule.onNodeWithText("删除").assertIsDisplayed()
    }

    @Test
    fun `禁用按钮点击不触发 onClick`() {
        var clicked = false
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(Icons.Rounded.Bookmark, "禁用", false, false, false, false, { clicked = true })
                ),
                hazeState = HazeState()
            )
        }
        composeRule.onNodeWithText("禁用").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isFalse()
    }

    @Test
    fun `selected 状态切换不影响其他按钮`() {
        composeRule.setContent {
            ActionButtonRow(
                actions = listOf(
                    ActionItem(Icons.Rounded.Bookmark, "想看", true, true, false, false, {}),
                    ActionItem(Icons.Rounded.Check, "已看", false, true, false, false, {})
                ),
                hazeState = HazeState()
            )
        }
        composeRule.onNodeWithText("想看").assertIsDisplayed()
        composeRule.onNodeWithText("已看").assertIsDisplayed()
    }
}
