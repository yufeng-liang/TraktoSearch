package com.tracktosearch.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import dev.chrisbanes.haze.HazeState
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
    fun legacy_surface_accepts_an_explicit_glass_role() {
        composeRule.setContent {
            CompositionLocalProvider(LocalVisualEffectMode provides VisualEffectMode.GLASS) {
                MaterialTheme {
                    NeumorphicFrostedSurface(
                        isDark = false,
                        shape = RoundedCornerShape(24.dp),
                        glassRole = GlassSurfaceRole.BottomNavigation,
                        hazeState = HazeState()
                    ) {}
                }
            }
        }

        composeRule.waitForIdle()
    }

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

    @Test
    fun glass_icon_button_dispatches_click_when_enabled() {
        var clicked = false
        composeRule.setContent {
            CompositionLocalProvider(LocalVisualEffectMode provides VisualEffectMode.GLASS) {
                MaterialTheme {
                    NeumorphicIconButton(
                        onClick = { clicked = true },
                        isDark = false,
                        hazeState = HazeState(),
                        enabled = true
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Glass retry"
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Glass retry")
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isTrue()
    }

    @Test
    fun glass_icon_button_does_not_dispatch_click_when_disabled() {
        var clicked = false
        composeRule.setContent {
            CompositionLocalProvider(LocalVisualEffectMode provides VisualEffectMode.GLASS) {
                MaterialTheme {
                    NeumorphicIconButton(
                        onClick = { clicked = true },
                        isDark = false,
                        hazeState = HazeState(),
                        enabled = false
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Disabled glass retry"
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Disabled glass retry")
            .assertIsNotEnabled()
            .performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isFalse()
    }
}
