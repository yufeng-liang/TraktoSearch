package com.tracktosearch.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

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
