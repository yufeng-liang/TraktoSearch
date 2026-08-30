package com.tracktosearch.ui.component

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
