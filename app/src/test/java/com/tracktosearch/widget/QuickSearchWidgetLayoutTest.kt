package com.tracktosearch.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.theme.MonetAccent
import org.junit.Test

class QuickSearchWidgetLayoutTest {

    @Test
    fun layoutUsesWidthBreakpoint() {
        listOf(
            DpSize(120.dp, 60.dp) to QuickSearchWidgetLayout.COMPACT,
            DpSize(239.dp, 100.dp) to QuickSearchWidgetLayout.COMPACT,
            DpSize(240.dp, 100.dp) to QuickSearchWidgetLayout.EXPANDED,
        ).forEach { (size, expected) ->
            assertThat(resolveWidgetLayout(size)).isEqualTo(expected)
        }
    }

    @Test
    fun nullAccentFallsBackToVintageTicket() {
        assertThat(resolveWidgetAccent(null)).isEqualTo(MonetAccent.VINTAGE_TICKET)
    }
}
