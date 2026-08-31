package com.tracktosearch.ui.screen.detail

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
    fun expand_action_is_inline_and_toggles_to_collapse() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    ExpandableText(text = longOverview)
                }
            }
        }

        // 「展开」内联在正文行末，与正文同属一个节点：没有单独的「展开」文本节点
        composeRule.onAllNodesWithText(expandLabel).assertCountEquals(0)
        // 点正文即切换（动作就在这段文字里）
        composeRule.onNodeWithText(expandLabel, substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText(collapseLabel, substring = true).assertIsDisplayed()
        // 展开后正文 5 行全部布局显示，内联的「收起」不额外占行
        assertThat(getTextLayouts("Overview line 1").single().lineCount).isEqualTo(5)
    }

}
