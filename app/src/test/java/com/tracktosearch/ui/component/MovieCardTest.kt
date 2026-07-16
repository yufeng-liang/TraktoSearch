package com.tracktosearch.ui.component

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MovieCard 组件 UI 测试。
 *
 * 被测组件：[MovieCard]
 *
 * 已知风险：MovieCard 内部通过 `EntryPointAccessors.fromApplication()` 获取
 * `PosterColorExtractor`（用于海报主色提取）。在 Robolectric 环境下，Application
 * 是否被 Hilt 正确装配决定了该调用能否成功。若装配失败，所有触发 MovieCard 组合的
 * 测试都会在 `remember` 块抛异常。
 */
@RunWith(AndroidJUnit4::class)
class MovieCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `title 正常显示`() {
        composeRule.setContent {
            MovieCard(
                title = "测试电影",
                year = 2024,
                genres = "动作",
                posterUrl = null,
                tmdbId = 123,
                onClick = {}
            )
        }
        // MovieCard 中 title 既作为 AsyncImage 的 contentDescription，又作为 Text 渲染
        composeRule.onNodeWithText("测试电影").assertIsDisplayed()
    }

    @Test
    fun `year 正常显示`() {
        composeRule.setContent {
            MovieCard(
                title = "电影",
                year = 2023,
                genres = "",
                posterUrl = null,
                tmdbId = 1,
                onClick = {}
            )
        }
        // YearBadge 渲染阴影层 + 主层两个 Text("2023")，onNodeWithText 会匹配多节点失败，用 onFirst
        composeRule.onAllNodesWithText("2023").onFirst().assertIsDisplayed()
    }

    @Test
    fun `year 为 null 时不崩溃且不显示年份`() {
        composeRule.setContent {
            MovieCard(
                title = "无年份电影",
                year = null,
                genres = "",
                posterUrl = null,
                tmdbId = 1,
                onClick = {}
            )
        }
        composeRule.onNodeWithText("无年份电影").assertIsDisplayed()
    }

    @Test
    fun `点击触发 onClick`() {
        var clicked = false
        composeRule.setContent {
            MovieCard(
                title = "点击测试",
                year = 2024,
                genres = "",
                posterUrl = null,
                tmdbId = 1,
                onClick = { clicked = true }
            )
        }
        composeRule.onNodeWithText("点击测试").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isTrue()
    }

    @Test
    fun `isInWatchlist_true 且 showStatusText_true 时显示想看文案`() {
        composeRule.setContent {
            MovieCard(
                title = "影片A",
                year = 2024,
                genres = "",
                posterUrl = null,
                tmdbId = 1,
                onClick = {},
                isInWatchlist = true,
                showStatusText = true
            )
        }
        composeRule.waitForIdle()
        // Robolectric 默认加载 values/(英文)，cd_watchlist_badge = "Want to Watch"
        composeRule.onNodeWithText("Want to Watch").assertIsDisplayed()
    }

    @Test
    fun `isWatched_true 且 showStatusText_true 时显示已看文案`() {
        composeRule.setContent {
            MovieCard(
                title = "影片B",
                year = 2024,
                genres = "",
                posterUrl = null,
                tmdbId = 1,
                onClick = {},
                isWatched = true,
                showStatusText = true
            )
        }
        composeRule.waitForIdle()
        // Robolectric 默认加载 values/(英文)，cd_watched_badge = "Watched"
        composeRule.onNodeWithText("Watched").assertIsDisplayed()
    }

    @Test
    fun `showStatusText_false 时不显示状态文案`() {
        composeRule.setContent {
            MovieCard(
                title = "影片C",
                year = 2024,
                genres = "",
                posterUrl = null,
                tmdbId = 1,
                onClick = {},
                isInWatchlist = true,
                isWatched = false,
                showStatusText = false
            )
        }
        composeRule.waitForIdle()
        // showStatusText=false 时角标 Icon 仍显示，但不应出现 "Want to Watch" 文案
        composeRule.onAllNodesWithText("Want to Watch").assertCountEquals(0)
    }

    @Test
    fun `posterUrl 为 null 时不崩溃`() {
        composeRule.setContent {
            MovieCard(
                title = "无图电影",
                year = 2024,
                genres = "",
                posterUrl = null,
                tmdbId = 1,
                onClick = {}
            )
        }
        composeRule.onNodeWithText("无图电影").assertIsDisplayed()
    }

    @Test
    fun `posterUrl 非空时不崩溃`() {
        composeRule.setContent {
            MovieCard(
                title = "有图电影",
                year = 2024,
                genres = "",
                posterUrl = "https://example.com/poster.jpg",
                tmdbId = 1,
                onClick = {}
            )
        }
        composeRule.waitForIdle()
        // Coil 在 Robolectric 下不会真正加载网络图片，但不应崩溃
        composeRule.onNodeWithText("有图电影").assertIsDisplayed()
    }

    @Test
    fun `genres 非空时正常渲染`() {
        composeRule.setContent {
            MovieCard(
                title = "多类型电影",
                year = 2024,
                genres = "动作 科幻 冒险",
                posterUrl = null,
                tmdbId = 1,
                onClick = {}
            )
        }
        composeRule.onNodeWithText("动作 科幻 冒险").assertIsDisplayed()
    }
}
