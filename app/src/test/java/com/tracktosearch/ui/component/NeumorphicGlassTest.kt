package com.tracktosearch.ui.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NeumorphicGlassTest {
    @Test
    fun noVisibleItemDoesNotEnableTopBarHaze() {
        assertThat(
            isContentUnderTopBar(
                firstVisibleItemIndex = null,
                firstVisibleItemScrollOffsetPx = null,
                contentTopPaddingPx = 200,
                topBarHeightPx = 100
            )
        ).isFalse()
    }

    @Test
    fun itemAtTopBarBottomDoesNotEnableTopBarHaze() {
        assertThat(
            isContentUnderTopBar(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 100,
                contentTopPaddingPx = 200,
                topBarHeightPx = 100
            )
        ).isFalse()
    }

    @Test
    fun itemInsideTopBarEnablesTopBarHaze() {
        assertThat(
            isContentUnderTopBar(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 101,
                contentTopPaddingPx = 200,
                topBarHeightPx = 100
            )
        ).isTrue()
    }
}
