package com.tracktosearch.ui.screen.swiftie

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * 题面交互的 Compose UI 测试。
 *
 * `autoAdvance = false` **不是可选项**：页面里跑着三条无限动画（闪粉、爱心、天空视差），
 * `waitForIdle` 在 `autoAdvance = true` 下永远等不到空闲，测试会挂死。断言只看**状态**，
 * 不看像素（Spec §13.3）。答案框是 Canvas 画的、没有文字语义，所以靠灯箱那一个
 * `contentDescription` 反查输入。
 */
@RunWith(AndroidJUnit4::class)
class SwiftieEggScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val submitLabel: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.swiftie_quiz_submit)

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    private fun launch(
        onDismiss: (Boolean) -> Unit = {},
        onCommitUnlock: () -> Unit = {}
    ) {
        composeRule.setContent {
            SwiftieEggScreen(
                visible = true,
                onDismiss = onDismiss,
                onCommitUnlock = onCommitUnlock
            )
        }
        // 走完 200ms 淡入
        composeRule.mainClock.advanceTimeBy(250)
    }

    private fun tap(label: String) {
        composeRule.onNodeWithText(label).performClick()
        composeRule.mainClock.advanceTimeBy(32)
    }

    @Test
    fun submitStaysDisabledUntilTwoDigitsAreEntered() {
        launch()
        composeRule.onNodeWithText(submitLabel).assertIsNotEnabled()
        tap("1")
        composeRule.onNodeWithText(submitLabel).assertIsNotEnabled()
        tap("3")
        composeRule.onNodeWithText(submitLabel).assertIsEnabled()
        // 答案框是 Canvas 画的，靠灯箱的 contentDescription 反查
        composeRule.onNodeWithContentDescription("13", substring = true).assertExists()
    }

    @Test
    fun wrongAnswerClearsItselfAfterTheShake() {
        launch()
        tap("1")
        tap("2")
        tap(submitLabel)
        composeRule.onNodeWithContentDescription("12", substring = true).assertExists()
        // 300ms 摇晃 + 余量
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithContentDescription("12", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(submitLabel).assertIsNotEnabled()
    }

    /**
     * 答对之后主题必须落地。
     *
     * 这里刻意不断言时刻：正常路径在 `THEME_COMMIT_AT`（T1100）提交，而 CI 常把
     * `animator_duration_scale` 设成 0，走的是「减少动效」那条立刻提交的分支。
     * 两条路都必须调到 `onCommitUnlock`，所以只断言「调到了」。
     * 精确时刻由 `SwiftieTimelineTest` 的账本单测守。
     */
    @Test
    fun correctAnswerCommitsTheUnlock() {
        var commitCount = 0
        launch(onCommitUnlock = { commitCount++ })
        tap("1")
        tap("3")
        tap(submitLabel)
        assertThat(commitCount).isEqualTo(0)
        // 覆盖 T0–1100 的确认窗口 + 扩散段，留足余量
        composeRule.mainClock.advanceTimeBy(1_600)
        assertThat(commitCount).isAtLeast(1)
    }
}
