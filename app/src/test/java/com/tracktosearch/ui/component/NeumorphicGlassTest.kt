package com.tracktosearch.ui.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NeumorphicGlassTest {
    @Test
    fun noVisibleItemDoesNotEnableTopBarHaze() {
        assertThat(
            hasListReachedTopBar(
                firstVisibleItemIndex = null,
                firstVisibleItemOffsetPx = null,
                topBarHeightPx = 100
            )
        ).isFalse()
    }

    @Test
    fun firstItemAtTopBarBottomEnablesTopBarHaze() {
        assertThat(
            hasListReachedTopBar(
                firstVisibleItemIndex = 0,
                firstVisibleItemOffsetPx = 100,
                topBarHeightPx = 100
            )
        ).isTrue()
    }

    @Test
    fun itemInsideTopBarEnablesTopBarHaze() {
        assertThat(
            hasListReachedTopBar(
                firstVisibleItemIndex = 0,
                firstVisibleItemOffsetPx = 99,
                topBarHeightPx = 100
            )
        ).isTrue()
    }

    @Test
    fun firstItemBelowTopBarDisablesTopBarHazeWhenScrollingBack() {
        assertThat(
            hasListReachedTopBar(
                firstVisibleItemIndex = 0,
                firstVisibleItemOffsetPx = 101,
                topBarHeightPx = 100
            )
        ).isFalse()
    }

    @Test
    fun laterItemKeepsTopBarHazeEnabledEvenWhenItsContentStartsBelowTopBar() {
        assertThat(
            hasListReachedTopBar(
                firstVisibleItemIndex = 1,
                firstVisibleItemOffsetPx = 240,
                topBarHeightPx = 100
            )
        ).isTrue()
    }
}
