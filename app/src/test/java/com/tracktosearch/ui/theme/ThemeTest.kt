package com.tracktosearch.ui.theme

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ThemeTest {

    @Test
    fun vintageTicketColorScheme_usesCommonUnselectedNavigationColor() {
        assertThat(vintageTicketColorScheme(dark = false).onSurfaceVariant)
            .isEqualTo(Color(0xFF49454F))
        assertThat(vintageTicketColorScheme(dark = true).onSurfaceVariant)
            .isEqualTo(Color(0xFFE0E0E0))
    }

    @Test
    fun appHazeDefaultNoiseFactor_usesRequestedValue() {
        assertThat(AppHazeDefaultNoiseFactor).isEqualTo(0.10f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ThemeCompositionLocalTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `traktoSearchTheme provides glass variant to composition local`() {
        var observedVariant: GlassVariant? = null

        composeRule.setContent {
            TraktoSearchTheme(
                themeMode = "system",
                accentColor = null,
                visualEffectMode = VisualEffectMode.GLASS,
                glassVariant = GlassVariant.FOCUSED
            ) {
                observedVariant = LocalGlassVariant.current
            }
        }

        assertThat(observedVariant).isEqualTo(GlassVariant.FOCUSED)
    }
}
