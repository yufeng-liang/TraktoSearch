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

}
