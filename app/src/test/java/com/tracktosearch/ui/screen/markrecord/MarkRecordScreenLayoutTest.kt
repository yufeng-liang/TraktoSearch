package com.tracktosearch.ui.screen.markrecord

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkRecordScreenLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `空状态卡片在可用内容区域水平垂直居中`() {
        val viewModel = mockk<MarkRecordViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(MarkRecordUiState())
        val sessionViewModel = mockk<MarkRecordSessionViewModel>(relaxed = true)
        every { sessionViewModel.traktConnected } returns MutableStateFlow(true)

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 360.dp, height = 720.dp)) {
                    MarkRecordScreen(
                        onBack = {},
                        onMovieClick = { _, _, _, _, _ -> },
                        onShowClick = { _, _, _, _, _ -> },
                        viewModel = viewModel,
                        sessionViewModel = sessionViewModel
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val viewportBounds = composeRule
            .onNodeWithTag("mark_record_empty_state_container")
            .fetchSemanticsNode().boundsInRoot
        val cardBounds = composeRule
            .onNodeWithTag("mark_record_empty_state_card")
            .fetchSemanticsNode().boundsInRoot

        assertThat(cardBounds.center.x).isWithin(1f).of(viewportBounds.center.x)
        assertThat(cardBounds.center.y).isWithin(1f).of(viewportBounds.center.y)
    }
}
