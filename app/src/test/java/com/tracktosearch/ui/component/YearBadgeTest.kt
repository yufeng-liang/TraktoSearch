package com.tracktosearch.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YearBadgeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `year_2024 正常显示`() {
        composeRule.setContent {
            YearBadge(year = "2024")
        }
        // YearBadge 渲染阴影层 + 主层两个 Text("2024")，onNodeWithText 会匹配多节点失败，用 onFirst
        composeRule.onAllNodesWithText("2024").onFirst().assertIsDisplayed()
    }
}
