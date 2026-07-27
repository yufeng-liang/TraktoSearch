package com.tracktosearch.ui.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NeumorphicGlassTest {
    @Test
    fun noVisibleItemDoesNotEnableTopBarHaze() {
        assertThat(
            isContentUnderTopBar(
                firstVisibleItemOffsetPx = null,
                topBarHeightPx = 100
            )
        ).isFalse()
    }

    @Test
    fun itemAtTopBarBottomDoesNotEnableTopBarHaze() {
        assertThat(
            isContentUnderTopBar(
                firstVisibleItemOffsetPx = 100,
                topBarHeightPx = 100
            )
        ).isFalse()
    }

    @Test
    fun itemInsideTopBarEnablesTopBarHaze() {
        assertThat(
            isContentUnderTopBar(
                firstVisibleItemOffsetPx = 99,
                topBarHeightPx = 100
            )
        ).isTrue()
    }
}
