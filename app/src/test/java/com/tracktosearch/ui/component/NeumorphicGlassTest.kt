package com.tracktosearch.ui.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NeumorphicGlassTest {
    @Test
    fun noVisibleItemDoesNotEnableTopBarHaze() {
        assertThat(
            hasListScrolled(
                firstVisibleItemIndex = null,
                firstVisibleItemScrollOffsetPx = null
            )
        ).isFalse()
    }

    @Test
    fun initialListPositionDoesNotEnableTopBarHaze() {
        assertThat(
            hasListScrolled(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 0
            )
        ).isFalse()
    }

    @Test
    fun anyScrollOffsetEnablesTopBarHaze() {
        assertThat(
            hasListScrolled(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 1
            )
        ).isTrue()
    }

    @Test
    fun laterItemEnablesTopBarHazeEvenWhenItemScrollOffsetIsZero() {
        assertThat(
            hasListScrolled(
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffsetPx = 0
            )
        ).isTrue()
    }

    @Test
    fun negativeScrollOffsetDoesNotEnableTopBarHaze() {
        assertThat(
            hasListScrolled(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = -1
            )
        ).isFalse()
    }
}
