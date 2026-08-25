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
class AppEmptyStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `显示主文案与说明`() {
        composeRule.setContent {
            AppEmptyState(title = "还没有想看的片", description = "去发现页逛逛")
        }
        composeRule.onNodeWithText("还没有想看的片").assertIsDisplayed()
        composeRule.onNodeWithText("去发现页逛逛").assertIsDisplayed()
    }

    @Test
    fun `引导动作齐全时可点击`() {
        var clicked = false
        composeRule.setContent {
            AppEmptyState(
                title = "空",
                primaryActionLabel = "去发现",
                onPrimaryAction = { clicked = true }
            )
        }
        composeRule.onNodeWithText("去发现").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isTrue()
    }

    @Test
    fun `只给文案不给回调时不显示动作`() {
        composeRule.setContent {
            AppEmptyState(title = "空", primaryActionLabel = "去发现")
        }
        composeRule.onNodeWithText("去发现").assertDoesNotExist()
    }
}
