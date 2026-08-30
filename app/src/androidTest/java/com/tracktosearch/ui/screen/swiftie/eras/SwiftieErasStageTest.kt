package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tracktosearch.R
import com.tracktosearch.ui.screen.swiftie.SwiftieSequenceClock
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwiftieErasStageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val clock = SwiftieSequenceClock()

    private val skipLabel: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.swiftie_skip)

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    private fun launch(replay: Boolean = false) {
        composeRule.setContent {
            SwiftieErasStage(
                clock = clock,
                frozen = false,
                onFrozenChange = {},
                replay = replay
            )
        }
        composeRule.mainClock.advanceTimeBy(32)
    }

    private fun seek(ms: Long) {
        composeRule.runOnUiThread { clock.seekTo(ms) }
        composeRule.mainClock.advanceTimeBy(32)
    }

    @Test
    fun skipIsUnreachableBeforeThreeSeconds() {
        launch()
        seek(SwiftieTimeline.ERAS_INTRO_START) // T1100
        // alpha 为 0 的按钮照样点得到，所以这里断的是 enabled 而不是可见性
        composeRule.onNodeWithText(skipLabel).assertIsNotEnabled()
    }

    @Test
    fun skipBecomesReachableOnceTheCardsStart() {
        launch()
        seek(SwiftieTimeline.ERAS_CARDS_START) // T3100 > 3000
        composeRule.onNodeWithText(skipLabel).assertIsEnabled()
    }

    @Test
    fun replaySkipIsReachableImmediately() {
        launch(replay = true)
        seek(SwiftieTimeline.ERAS_INTRO_START)
        composeRule.onNodeWithText(skipLabel).assertIsEnabled()
    }

    @Test
    fun playheadPositionPicksTheMatchingCard() {
        launch()
        // 第 4 张是 Red，16 首
        seek(SwiftieTimeline.eraStartMs(3) + 500L)
        composeRule
            .onNodeWithContentDescription("Red", substring = true)
            .assertExists()
        // 第 7 张是 Lover，18 首
        seek(SwiftieTimeline.eraStartMs(6) + 500L)
        composeRule
            .onNodeWithContentDescription("Lover", substring = true)
            .assertExists()
    }
}
