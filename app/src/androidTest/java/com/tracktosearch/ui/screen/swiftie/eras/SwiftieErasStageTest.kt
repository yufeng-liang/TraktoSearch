package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tracktosearch.ui.screen.swiftie.SwiftieSequenceClock
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Eras 舞台的挂载与选片。
 *
 * **不再断「跳过」按钮** —— 暂停 / 跳过合并成一组浮出控件之后，按钮已经搬到
 * `SwiftieEggScreen` 那一层（它才是收全屏触摸的），本层只剩卡片、轴与雪景球。
 * 控件本身由 `SwiftieSequenceControlsTest` 覆盖。
 */
@RunWith(AndroidJUnit4::class)
class SwiftieErasStageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val clock = SwiftieSequenceClock()

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    private fun launch() {
        composeRule.setContent {
            SwiftieErasStage(
                clock = clock,
                frozen = false,
                onFrozenChange = {}
            )
        }
        composeRule.mainClock.advanceTimeBy(32)
    }

    private fun seek(ms: Long) {
        composeRule.runOnUiThread { clock.seekTo(ms) }
        composeRule.mainClock.advanceTimeBy(32)
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

    @Test
    fun theFinaleKeepsTheLoverCardAllTheWayThroughFadeOut() {
        launch()
        // 倒滑段：卡片正在卷收，仍然是 Lover
        seek(SwiftieTimeline.REWIND_START + 200L)
        composeRule
            .onNodeWithContentDescription("Lover", substring = true)
            .assertExists()
        // 雪景球接手之后，球内那张小卡还是同一张 Lover
        seek(SwiftieTimeline.LOVER_BLOOM_START + 500L)
        composeRule
            .onNodeWithContentDescription("Lover", substring = true)
            .assertExists()
        // 最后 2998ms 的淡出：主体是那只球，卡片不能提前被卸载
        seek(SwiftieTimeline.FADE_OUT_START + 500L)
        composeRule
            .onNodeWithContentDescription("Lover", substring = true)
            .assertExists()
    }
}
