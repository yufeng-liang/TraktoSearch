package com.tracktosearch.ui.screen.main

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MainScreenUiTest {

    @Test
    fun bottomNavigationIsHiddenWhileAiSpriteCenterIsVisible() {
        assertThat(isMainBottomNavigationVisible(aiSpriteCenterVisible = true)).isEqualTo(false)
    }

    @Test
    fun bottomNavigationRemainsVisibleAfterAiSpriteCenterIsDismissed() {
        assertThat(isMainBottomNavigationVisible(aiSpriteCenterVisible = false)).isEqualTo(true)
    }
}
