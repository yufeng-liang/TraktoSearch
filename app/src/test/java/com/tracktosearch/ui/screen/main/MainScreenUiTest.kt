package com.tracktosearch.ui.screen.main

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.screen.ai.AiSpriteOverlayTrigger
import com.tracktosearch.ui.screen.search.shouldStartSearchCompletedOverlay
import org.junit.Test

class MainScreenUiTest {

    @Test
    fun bottomNavigationIsHiddenWhileAiSpriteCenterIsVisible() {
        assertThat(
            isMainBottomNavigationVisible(currentPagerPage = 0, aiSpriteCenterVisible = true)
        ).isEqualTo(false)
    }

    @Test
    fun bottomNavigationRemainsVisibleAfterAiSpriteCenterIsDismissed() {
        assertThat(
            isMainBottomNavigationVisible(currentPagerPage = 0, aiSpriteCenterVisible = false)
        ).isEqualTo(true)
    }

    @Test
    fun bottomNavigationRemainsVisibleWhenAiSpriteCenterIsMarkedVisibleOnAnotherPagerPage() {
        assertThat(
            isMainBottomNavigationVisible(currentPagerPage = 2, aiSpriteCenterVisible = true)
        ).isEqualTo(true)
    }

    @Test
    fun completedSearchOverlayCanStartWithNonBlankQueryWhenResultAnchorExists() {
        assertThat(
            shouldStartSearchCompletedOverlay(
                trigger = AiSpriteOverlayTrigger.SEARCH_COMPLETED,
                hasBlockingState = false,
                hasResultAnchor = true
            )
        ).isTrue()
    }
}
