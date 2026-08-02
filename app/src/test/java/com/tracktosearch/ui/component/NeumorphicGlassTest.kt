package com.tracktosearch.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class NeumorphicGlassTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disabled_icon_button_does_not_dispatch_click() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                NeumorphicIconButton(
                    onClick = { clicked = true },
                    isDark = false,
                    enabled = false
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Retry"
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Retry").assertIsNotEnabled().performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isFalse()
    }
}
