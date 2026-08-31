package com.tracktosearch.ui.screen.person

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PersonImageOverlayLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `全部人物图片网格填满标题栏下方剩余空间`() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 360.dp, height = 720.dp)) {
                    AllPersonImagesPanel(
                        visible = true,
                        images = emptyList(),
                        personId = 1,
                        onImageClick = {},
                        onDismiss = {}
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.waitForIdle()

        val panelBounds = composeRule
            .onNodeWithTag("person_images_panel")
            .fetchSemanticsNode().boundsInRoot
        val gridBounds = composeRule
            .onNodeWithTag("person_images_grid")
            .fetchSemanticsNode().boundsInRoot

        assertThat(gridBounds.bottom).isWithin(1f).of(panelBounds.bottom)
    }
}
