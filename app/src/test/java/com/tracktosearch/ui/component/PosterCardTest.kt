package com.tracktosearch.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
    fun `title 作为内容描述正常显示`() {
        composeRule.setContent {
            PosterCard(
                imageUrl = "",
                title = "测试电影",
                year = "2024",
                rating = 8.5,
                onClick = {}
            )
        }
        // PosterCard 中 title 仅作为 AsyncImage 的 contentDescription，不作为 Text 渲染
        composeRule.onNodeWithContentDescription("测试电影").assertIsDisplayed()
    }

    @Test
    fun `year 正常显示`() {
        composeRule.setContent {
            PosterCard(
                imageUrl = "",
                title = "电影",
                year = "2023",
                rating = 7.0,
                onClick = {}
            )
        }
        // YearBadge 渲染阴影层 + 主层两个 Text("2023")，onNodeWithText 会匹配多节点失败，用 onFirst
        composeRule.onAllNodesWithText("2023").onFirst().assertIsDisplayed()
    }

    @Test
    fun `rating 正常显示`() {
        composeRule.setContent {
            PosterCard(
                imageUrl = "",
                title = "电影",
                year = "2024",
                rating = 9.2,
                onClick = {}
            )
        }
        // RatingBadge 实际渲染 "★ 9.2"，用 substring 匹配数字部分避免 unicode 字符问题
        composeRule.onNodeWithText("9.2", substring = true).assertIsDisplayed()
    }

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

    @Test
    fun `genres 非空时正常渲染`() {
        composeRule.setContent {
            PosterCard(
                imageUrl = "",
                title = "电影",
                year = "2024",
                rating = 8.0,
                onClick = {},
                genres = "动作 科幻"
            )
        }
        composeRule.waitForIdle()
        // genres 以 Text 形式渲染在左上角
        composeRule.onNodeWithText("动作 科幻").assertIsDisplayed()
    }

    @Test
    fun `空 imageUrl 不崩溃`() {
        composeRule.setContent {
            PosterCard(
                imageUrl = "",
                title = "无图电影",
                year = "2024",
                rating = 8.0,
                onClick = {}
            )
        }
        composeRule.onNodeWithContentDescription("无图电影").assertIsDisplayed()
    }
}
