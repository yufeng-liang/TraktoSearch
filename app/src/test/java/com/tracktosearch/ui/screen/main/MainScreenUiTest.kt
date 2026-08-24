package com.tracktosearch.ui.screen.main

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.screen.ai.AiSpriteOverlayTrigger
import com.tracktosearch.ui.screen.ai.shouldStartSpriteOverlay
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
            shouldStartSpriteOverlay(
                trigger = AiSpriteOverlayTrigger.SEARCH_COMPLETED,
                hasBlockingState = false,
                hasAnchorBounds = true,
                hasResultAnchor = true,
                motionVisible = false
            )
        ).isTrue()
    }

    @Test
    fun overlayDoesNotStartBeforeAnchorBoundsAreMeasured() {
        // 锚点没量到就消费额度的话，用户什么都看不到但额度掉一格
        assertThat(
            shouldStartSpriteOverlay(
                trigger = AiSpriteOverlayTrigger.FIRST_ENTRY,
                hasBlockingState = false,
                hasAnchorBounds = false,
                hasResultAnchor = false,
                motionVisible = false
            )
        ).isFalse()
    }

    @Test
    fun overlayDoesNotRestartWhileAnotherMotionIsStillVisible() {
        assertThat(
            shouldStartSpriteOverlay(
                trigger = AiSpriteOverlayTrigger.IDLE,
                hasBlockingState = false,
                hasAnchorBounds = true,
                hasResultAnchor = true,
                motionVisible = true
            )
        ).isFalse()
    }
}
