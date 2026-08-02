package com.tracktosearch.ui.screen.detail

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    private val showAllLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.detail_overview_show_all)

    private val collapseLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.detail_overview_collapse)

    @Test
    fun short_overview_does_not_show_expand_action() {
        composeRule.setContent {
            MaterialTheme {
                ExpandableText(text = "A short overview.")
            }
        }

        assertThat(composeRule.onAllNodesWithText(showAllLabel, substring = true).fetchSemanticsNodes())
            .isEmpty()
    }

    @Test
    fun short_overview_display_text_has_no_action() {
        val display = buildOverviewDisplayText(
            text = "A short overview.",
            expanded = false,
            collapsedContent = null,
            showAllLabel = showAllLabel,
            collapseLabel = collapseLabel
        )

        assertThat(display.content).isEqualTo("A short overview.")
        assertThat(display.actionLabel).isNull()
    }

    @Test
    fun collapsed_long_overview_display_text_shows_view_all() {
        val display = buildOverviewDisplayText(
            text = "A long overview.",
            expanded = false,
            collapsedContent = "A long",
            showAllLabel = showAllLabel,
            collapseLabel = collapseLabel
        )

        assertThat(display.content).isEqualTo("A long")
        assertThat(display.actionLabel).isEqualTo(showAllLabel)
    }

    @Test
    fun expanded_overview_display_text_shows_collapse() {
        val display = buildOverviewDisplayText(
            text = "A long overview.",
            expanded = true,
            collapsedContent = "A long",
            showAllLabel = showAllLabel,
            collapseLabel = collapseLabel
        )

        assertThat(display.content).isEqualTo("A long overview.")
        assertThat(display.actionLabel).isEqualTo(collapseLabel)
    }
}
