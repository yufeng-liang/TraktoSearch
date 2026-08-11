package com.tracktosearch.ui.screen.detail

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
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
    fun short_overview_display_text_has_no_action() {
        val display = buildOverviewDisplayText(
            text = "A short overview.",
            expanded = false,
            collapsedContent = null,
            expandLabel = expandLabel,
            collapseLabel = collapseLabel
        )

        assertThat(display.content).isEqualTo("A short overview.")
        assertThat(display.actionLabel).isNull()
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

        composeRule.onNodeWithText(expandLabel, substring = true).assertIsDisplayed()
        assertThat(composeRule.onAllNodesWithText("Overview line 5", substring = true).fetchSemanticsNodes())
            .isEmpty()
        assertThat(composeRule.onAllNodesWithText("View all", substring = true).fetchSemanticsNodes())
            .isEmpty()
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

        composeRule.onNodeWithText(expandLabel, substring = true).performClick()
        composeRule.onNodeWithText(collapseLabel, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Overview line 5", substring = true).assertIsDisplayed()
    }

    @Test
    fun collapsed_long_overview_display_text_shows_expand() {
        val display = buildOverviewDisplayText(
            text = "A long overview.",
            expanded = false,
            collapsedContent = "A long",
            expandLabel = expandLabel,
            collapseLabel = collapseLabel
        )

        assertThat(display.content).isEqualTo("A long")
        assertThat(display.actionLabel).isEqualTo(expandLabel)
    }

    @Test
    fun expanded_overview_display_text_shows_collapse() {
        val display = buildOverviewDisplayText(
            text = "A long overview.",
            expanded = true,
            collapsedContent = "A long",
            expandLabel = expandLabel,
            collapseLabel = collapseLabel
        )

        assertThat(display.content).isEqualTo("A long overview.")
        assertThat(display.actionLabel).isEqualTo(collapseLabel)
    }
}
