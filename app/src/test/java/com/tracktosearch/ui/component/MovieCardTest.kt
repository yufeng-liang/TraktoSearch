package com.tracktosearch.ui.component

import androidx.compose.ui.test.junit4.createComposeRule
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
    fun `点击触发 onClick`() {
        var clicked = false
        composeRule.setContent {
            MovieCard(
                title = "点击测试",
                year = 2024,
                genres = "",
                posterUrl = null,
                tmdbId = 1,
                onClick = { clicked = true },
                origin = SharedOrigin.ANY
            )
        }
        composeRule.onNodeWithText("点击测试").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isTrue()
    }
}
