package com.tracktosearch.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.theme.MonetAccent
import org.junit.Test

class QuickSearchWidgetLayoutTest {

    @Test
    fun `120x60 uses compact layout`() {
        assertThat(resolveWidgetLayout(DpSize(120.dp, 60.dp)))
            .isEqualTo(QuickSearchWidgetLayout.COMPACT)
    }

    @Test
    fun `239x100 uses compact layout`() {
        assertThat(resolveWidgetLayout(DpSize(239.dp, 100.dp)))
            .isEqualTo(QuickSearchWidgetLayout.COMPACT)
    }

    @Test
    fun `240x100 uses expanded layout`() {
        assertThat(resolveWidgetLayout(DpSize(240.dp, 100.dp)))
            .isEqualTo(QuickSearchWidgetLayout.EXPANDED)
    }

    @Test
    fun `null accent falls back to vintage ticket`() {
        assertThat(resolveWidgetAccent(null)).isEqualTo(MonetAccent.VINTAGE_TICKET)
    }
}
