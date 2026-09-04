package com.tracktosearch.ui.screen.swiftie

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 暂停 / 跳过合并成的那一组浮出控件。
 *
 * 覆盖的是**取代**「点一下就暂停 / 按住暂停」之后的新契约：点屏幕只把这组按钮浮出来，
 * 选了哪个才动作。自动隐藏的 3.5s 计时在 `SwiftieEggScreen` 那一层，不在本控件里，
 * 所以这里只驱动 `visible` 本身。
 *
 * `autoAdvance = false`：`AnimatedVisibility` 的 200ms 进出场要手动走完，
 * 否则 `waitForIdle` 会一直等下去。
 */
@RunWith(AndroidJUnit4::class)
class SwiftieSequenceControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val pauseLabel get() = string(R.string.swiftie_pause)
    private val resumeLabel get() = string(R.string.swiftie_resume)
    private val skipLabel get() = string(R.string.swiftie_skip)

    private var visible by mutableStateOf(true)
    private var paused by mutableStateOf(false)
    private var skipEnabled by mutableStateOf(true)
    private var toggleCount = 0
    private var skipCount = 0

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    private fun launch() {
        composeRule.setContent {
            SwiftieSequenceControls(
                visible = visible,
                paused = paused,
                skipEnabled = skipEnabled,
                onTogglePause = { toggleCount++ },
                onSkip = { skipCount++ }
            )
        }
        settle()
    }

    /** 走完 200ms 进出场 + 一帧余量。 */
    private fun settle() = composeRule.mainClock.advanceTimeBy(250)

    @Test
    fun hiddenUntilTheScreenIsTapped() {
        visible = false
        launch()
        // 不是「透明但还在」：alpha = 0 的按钮照样点得到，那正是这一层的历史坑
        composeRule.onNodeWithText(pauseLabel).assertDoesNotExist()
        composeRule.onNodeWithText(skipLabel).assertDoesNotExist()

        composeRule.runOnUiThread { visible = true }
        settle()
        composeRule.onNodeWithText(pauseLabel).assertExists()
        composeRule.onNodeWithText(skipLabel).assertExists()
    }

    @Test
    fun showingTheControlsDoesNotPauseByItself() {
        launch()
        // 浮出来的一刻仍是「暂停」而不是「继续」—— 点屏幕本身不许改播放状态
        composeRule.onNodeWithText(pauseLabel).assertExists()
        composeRule.onNodeWithText(resumeLabel).assertDoesNotExist()
        assertThat(toggleCount).isEqualTo(0)
    }

    @Test
    fun eachHalfReportsExactlyOneTap() {
        launch()
        composeRule.onNodeWithText(pauseLabel).assertHasClickAction().performClick()
        composeRule.mainClock.advanceTimeBy(32)
        assertThat(toggleCount).isEqualTo(1)
        assertThat(skipCount).isEqualTo(0)

        composeRule.onNodeWithText(skipLabel).assertHasClickAction().performClick()
        composeRule.mainClock.advanceTimeBy(32)
        assertThat(skipCount).isEqualTo(1)
        assertThat(toggleCount).isEqualTo(1)
    }

    @Test
    fun theLabelFollowsThePausedFlag() {
        launch()
        composeRule.runOnUiThread { paused = true }
        settle()
        composeRule.onNodeWithText(resumeLabel).assertExists()
        composeRule.onNodeWithText(pauseLabel).assertDoesNotExist()
    }

    @Test
    fun skipLeavesTheCapsuleEntirelyWhenTheFinaleOwnsTheClock() {
        skipEnabled = false
        launch()
        // 灰掉一个按钮只会让人反复去点，所以收尾段是整块不渲染
        composeRule.onNodeWithText(skipLabel).assertDoesNotExist()
        // 暂停那半边仍在：定格看最后一张卡片是合理的
        composeRule.onNodeWithText(pauseLabel).assertExists()
    }
}
