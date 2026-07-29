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
                firstVisibleItemScrollOffsetPx = null,
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
                firstVisibleItemScrollOffsetPx = 1,
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
                firstVisibleItemScrollOffsetPx = 1,
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
                firstVisibleItemScrollOffsetPx = 0,
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
                firstVisibleItemScrollOffsetPx = 0,
                topBarHeightPx = 100
            )
        ).isTrue()
    }

    @Test
    fun initialListPositionDoesNotEnableTopBarHaze() {
        assertThat(
            hasListReachedTopBar(
                firstVisibleItemIndex = 0,
                firstVisibleItemOffsetPx = 100,
                firstVisibleItemScrollOffsetPx = 0,
                topBarHeightPx = 100
            )
        ).isFalse()
    }
}
