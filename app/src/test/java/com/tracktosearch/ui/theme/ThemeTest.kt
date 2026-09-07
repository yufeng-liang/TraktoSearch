package com.tracktosearch.ui.theme

import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
// 配色结构/语义约束见 ThemePaletteStructureTest / ThemeSemanticPaletteTest，
// 那些是纯 JVM 的，不用起 Robolectric。

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
