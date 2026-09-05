package com.tracktosearch.ui.screen.swiftie

import android.provider.Settings
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import org.junit.Assume.assumeTrue
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

    private val pauseLabel: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.swiftie_pause)

    private val resumeLabel: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.swiftie_resume)

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

    /**
     * 序列期间点屏幕**只浮出控件**，不许直接暂停 —— 原先「点一下就暂停 / 按住暂停」
     * 会在用户只想看清一张卡片时误触发，且没有任何可见确认。
     *
     * 无操作 3.5s 后两个按钮自己收回去。
     *
     * `animator_duration_scale = 0` 的机器走的是静态终态那条分支，整条序列一帧不播、
     * 控件根本不挂载，所以那种环境下跳过本条。
     */
    @Test
    fun tappingTheSequenceRevealsTheControlsWithoutPausing() {
        assumeAnimationsAreOn()
        launch()
        tap("1")
        tap("3")
        tap(submitLabel)
        // 走完扩散，进到轴线与卡片段
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.onNodeWithText(pauseLabel).assertDoesNotExist()

        composeRule.onRoot().performClick()
        composeRule.mainClock.advanceTimeBy(250)
        // 浮出来的是「暂停」而不是「继续」：这一下点击本身没有改播放状态
        composeRule.onNodeWithText(pauseLabel).assertExists()
        composeRule.onNodeWithText(resumeLabel).assertDoesNotExist()

        // 3.5s 无操作自动收回
        composeRule.mainClock.advanceTimeBy(3_600)
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.onNodeWithText(pauseLabel).assertDoesNotExist()
    }

    /** 定格之后控件要一直钉着 —— 暂停了却把「继续」藏起来，人就只能干等。 */
    @Test
    fun theControlsStayPinnedWhilePaused() {
        assumeAnimationsAreOn()
        launch()
        tap("1")
        tap("3")
        tap(submitLabel)
        composeRule.mainClock.advanceTimeBy(5_000)

        composeRule.onNodeWithText(pauseLabel).performClick()
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.onNodeWithText(resumeLabel).assertExists()
        // 远超 3.5s 的自动隐藏窗口
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.onNodeWithText(resumeLabel).assertExists()
    }

    /**
     * 与 `SwiftieReducedMotion` 读的是同一对系统键。
     *
     * 那两个键一旦命中，`sequenceRunning` 为 false、整条序列换成静态终态，
     * 关于控件的断言全都无从谈起 —— 这不是失败而是不适用。
     */
    private fun assumeAnimationsAreOn() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val scale = runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        val a11yDisabled = runCatching {
            Settings.Global.getInt(resolver, "accessibility_animation_disabled", 0)
        }.getOrDefault(0)
        assumeTrue(scale != 0f && a11yDisabled != 1)
    }
}
