package com.tracktosearch.ui.screen.feedback

import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.unit.Density
import org.junit.Test

class FeedbackDetailScreenTest {
    @Test
    fun replyListIndexIncludesOriginalCardAndConversationTitle() {
        assertThat(conversationReplyListItemIndex(0)).isEqualTo(2)
        assertThat(conversationReplyListItemIndex(3)).isEqualTo(5)
    }

    @Test
    fun parseScreenshotsHandlesEmptyAndJsonArrays() {
        assertThat(parseScreenshots(null)).isEmpty()
        assertThat(parseScreenshots("[]")).isEmpty()
        assertThat(parseScreenshots("[\"one\",\"two\"]")).containsExactly("one", "two").inOrder()
        assertThat(parseScreenshots("[\"https://example.test/a,b\"]"))
            .containsExactly("https://example.test/a,b")
    }

    @Test
    fun detailScaffoldLeavesImeInsetsForReplyBar() {
        assertThat(feedbackDetailScaffoldContentWindowInsets().getBottom(Density(1f)))
            .isEqualTo(0)
    }
}
