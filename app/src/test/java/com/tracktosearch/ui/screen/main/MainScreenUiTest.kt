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
