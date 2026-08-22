package com.tracktosearch.ui.screen.detail

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tracktosearch.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DetailHeaderContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val expandLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.detail_text_expand)

    private val collapseLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.detail_text_collapse)

    private val longOverview = (1..5).joinToString("\n") { "Overview line $it" }

    private fun getTextLayouts(marker: String): List<TextLayoutResult> {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(marker, substring = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                action(layouts)
            }
        return layouts
    }

    @Test
    fun short_overview_does_not_show_expand_action() {
        composeRule.setContent {
            MaterialTheme {
                ExpandableText(text = "A short overview.")
            }
        }

        assertThat(composeRule.onAllNodesWithText(expandLabel, substring = true).fetchSemanticsNodes())
            .isEmpty()
    }

    @Test
    fun long_overview_shows_expand_action() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    ExpandableText(text = longOverview)
                }
            }
        }

        composeRule.onNodeWithText(expandLabel).assertIsDisplayed()
        // 折叠时正文只布局 3 行（第 4/5 行被省略号截断，文本语义仍含全文）
        assertThat(getTextLayouts("Overview line 1").single().lineCount).isEqualTo(3)
    }

    @Test
    fun clicking_expand_changes_action_to_collapse() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    ExpandableText(text = longOverview)
                }
            }
        }

        // 点击「展开」按钮（右对齐的独立 Text）
        composeRule.onNodeWithText(expandLabel).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText(collapseLabel).assertIsDisplayed()
        // 展开后正文 5 行全部布局显示
        assertThat(getTextLayouts("Overview line 1").single().lineCount).isEqualTo(5)
    }

    @Test
    fun toggle_action_sits_below_body_and_aligns_to_end() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    ExpandableText(text = longOverview)
                }
            }
        }

        val bodyBottom = composeRule.onNodeWithText("Overview line 1", substring = true)
            .getUnclippedBoundsInRoot().bottom.value
        val expandBounds = composeRule.onNodeWithText(expandLabel).getUnclippedBoundsInRoot()
        // 「展开」独占一行放在正文下方，不再叠在最后一行上遮挡文字
        assertThat(expandBounds.top.value).isAtLeast(bodyBottom)
        // 右对齐：按钮右边缘与 320.dp 容器右边缘一致
        assertThat(expandBounds.right.value).isWithin(0.5f).of(320f)

        // 展开后「收起」占用同一位置，仍然右对齐
        composeRule.onNodeWithText(expandLabel).performSemanticsAction(SemanticsActions.OnClick)
        val collapseBounds = composeRule.onNodeWithText(collapseLabel).getUnclippedBoundsInRoot()
        assertThat(collapseBounds.right.value).isWithin(0.5f).of(320f)
    }
}
