package com.tracktosearch.ui.component

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PosterCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `点击触发 onClick`() {
        var clicked = false
        composeRule.setContent {
            PosterCard(
                imageUrl = "",
                title = "点击测试",
                year = "2024",
                rating = 8.0,
                onClick = { clicked = true }
            )
        }
        composeRule.onNodeWithContentDescription("点击测试").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isTrue()
    }
}
